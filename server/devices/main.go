// iykyk-devices answers one question for the IYKYK app: how many devices use
// this subscription and which ones, and lets the owner remove an old device to
// free a slot. It talks to the Remnawave API with an admin token that never
// leaves the server; the app identifies itself only by its subscription's
// short UUID, the same secret that already grants access to the subscription.
//
//	GET  /iykyk/api/devices          X-Subscription: <shortUuid>, X-Hwid: <this device>
//	POST /iykyk/api/devices/delete   same headers, body {"id": "<device id>"}
//
// Device IDs in responses are hashes: raw HWIDs never leave the server.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

type config struct {
	panelURL      string // e.g. http://remnawave:3000 (inside the Docker network)
	token         string // Remnawave API token
	fallbackLimit int    // the panel's HWID_FALLBACK_DEVICE_LIMIT, for users without their own
	listen        string
}

func main() {
	cfg := config{
		panelURL: strings.TrimRight(env("REMNAWAVE_URL", "http://remnawave:3000"), "/"),
		token:    os.Getenv("REMNAWAVE_TOKEN"),
		listen:   env("LISTEN", ":8080"),
	}
	cfg.fallbackLimit, _ = strconv.Atoi(env("FALLBACK_DEVICE_LIMIT", "0"))
	if cfg.token == "" {
		log.Fatal("REMNAWAVE_TOKEN is not set")
	}
	log.Printf("iykyk-devices listening on %s, panel %s", cfg.listen, cfg.panelURL)
	srv := &http.Server{
		Addr:              cfg.listen,
		Handler:           newHandler(cfg, &panel{base: cfg.panelURL, token: cfg.token, client: &http.Client{Timeout: 10 * time.Second}}),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Fatal(srv.ListenAndServe())
}

func env(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

// --- HTTP API for the app -------------------------------------------------------

type device struct {
	ID       string `json:"id"`
	Platform string `json:"platform,omitempty"`
	OS       string `json:"os,omitempty"`
	Model    string `json:"model,omitempty"`
	App      string `json:"app,omitempty"`
	Added    string `json:"added,omitempty"`
	LastSeen string `json:"lastSeen,omitempty"`
	Current  bool   `json:"current,omitempty"`
}

type devicesResponse struct {
	Used    int      `json:"used"`
	Limit   int      `json:"limit"` // 0 = unlimited
	Devices []device `json:"devices"`
}

var shortUUID = regexp.MustCompile(`^[A-Za-z0-9_-]{6,64}$`)

func newHandler(cfg config, p panelAPI) http.Handler {
	limiter := newRateLimiter(30, time.Minute)
	mux := http.NewServeMux()
	handle := func(w http.ResponseWriter, r *http.Request, del bool) {
		w.Header().Set("Cache-Control", "no-store")
		if !limiter.allow(clientIP(r)) {
			httpError(w, http.StatusTooManyRequests, "too many requests")
			return
		}
		sub := strings.TrimSpace(r.Header.Get("X-Subscription"))
		if !shortUUID.MatchString(sub) {
			httpError(w, http.StatusBadRequest, "bad subscription")
			return
		}
		u, err := p.userByShortUUID(sub)
		if err != nil {
			httpError(w, http.StatusNotFound, "subscription not found")
			return
		}
		if del {
			var body struct {
				ID string `json:"id"`
			}
			if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&body); err != nil || body.ID == "" {
				httpError(w, http.StatusBadRequest, "bad request")
				return
			}
			devices, err := p.devices(u)
			if err != nil {
				httpError(w, http.StatusBadGateway, "panel error")
				return
			}
			found := false
			for _, d := range devices {
				if deviceID(d.HWID) == body.ID {
					if err := p.deleteDevice(u, d.HWID); err != nil {
						httpError(w, http.StatusBadGateway, "panel error")
						return
					}
					found = true
					break
				}
			}
			if !found {
				httpError(w, http.StatusNotFound, "device not found")
				return
			}
		}
		devices, err := p.devices(u)
		if err != nil {
			httpError(w, http.StatusBadGateway, "panel error")
			return
		}
		current := strings.TrimSpace(r.Header.Get("X-Hwid"))
		resp := devicesResponse{Used: len(devices), Limit: cfg.fallbackLimit, Devices: []device{}}
		if u.Limit != nil {
			resp.Limit = *u.Limit
		}
		for _, d := range devices {
			resp.Devices = append(resp.Devices, device{
				ID:       deviceID(d.HWID),
				Platform: d.Platform,
				OS:       d.OSVersion,
				Model:    d.DeviceModel,
				App:      appName(d.UserAgent),
				Added:    d.CreatedAt,
				LastSeen: d.UpdatedAt,
				Current:  current != "" && d.HWID == current,
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}
	mux.HandleFunc("GET /iykyk/api/devices", func(w http.ResponseWriter, r *http.Request) { handle(w, r, false) })
	mux.HandleFunc("POST /iykyk/api/devices/delete", func(w http.ResponseWriter, r *http.Request) { handle(w, r, true) })
	mux.HandleFunc("GET /iykyk/api/health", func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "ok") })
	return mux
}

func deviceID(hwid string) string {
	sum := sha256.Sum256([]byte("iykyk-device:" + hwid))
	return hex.EncodeToString(sum[:8])
}

// appName shortens a User-Agent like "IYKYK/0.1.0 (Android 16)" or "Happ/3.2.1/ios".
func appName(ua string) string {
	name, _, _ := strings.Cut(ua, " ")
	if len(name) > 40 {
		name = name[:40]
	}
	return name
}

func httpError(w http.ResponseWriter, code int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}

func clientIP(r *http.Request) string {
	// Behind Caddy/nginx: the first X-Forwarded-For entry is the client.
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		first, _, _ := strings.Cut(xff, ",")
		return strings.TrimSpace(first)
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	return host
}

type rateLimiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	hits   map[string][]time.Time
}

func newRateLimiter(limit int, window time.Duration) *rateLimiter {
	return &rateLimiter{limit: limit, window: window, hits: map[string][]time.Time{}}
}

func (l *rateLimiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	recent := l.hits[key][:0]
	for _, t := range l.hits[key] {
		if now.Sub(t) < l.window {
			recent = append(recent, t)
		}
	}
	if len(recent) >= l.limit {
		l.hits[key] = recent
		return false
	}
	l.hits[key] = append(recent, now)
	if len(l.hits) > 10000 { // forget everyone now and then
		l.hits = map[string][]time.Time{key: l.hits[key]}
	}
	return true
}

// --- Remnawave API --------------------------------------------------------------

type panelUser struct {
	ID    int64  // Remnawave 2.x+: numeric id
	UUID  string // older panels address HWID devices by uuid
	Limit *int   // hwidDeviceLimit; nil = panel default
}

type panelDevice struct {
	HWID        string `json:"hwid"`
	Platform    string `json:"platform"`
	OSVersion   string `json:"osVersion"`
	DeviceModel string `json:"deviceModel"`
	UserAgent   string `json:"userAgent"`
	CreatedAt   string `json:"createdAt"`
	UpdatedAt   string `json:"updatedAt"`
}

type panelAPI interface {
	userByShortUUID(shortUUID string) (*panelUser, error)
	devices(u *panelUser) ([]panelDevice, error)
	deleteDevice(u *panelUser, hwid string) error
}

type panel struct {
	base, token string
	client      *http.Client
}

func (p *panel) call(method, path string, body any, out any) error {
	var reader io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, p.base+path, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+p.token)
	req.Header.Set("Content-Type", "application/json")
	// Remnawave accepts plain-HTTP API calls from the Docker network only with these.
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-For", "127.0.0.1")
	resp, err := p.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("%s %s: HTTP %d", method, path, resp.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(out)
}

func (p *panel) userByShortUUID(shortUUID string) (*panelUser, error) {
	var out struct {
		Response struct {
			ID              json.Number `json:"id"`
			UUID            string      `json:"uuid"`
			HwidDeviceLimit *int        `json:"hwidDeviceLimit"`
		} `json:"response"`
	}
	if err := p.call("GET", "/api/users/by-short-uuid/"+shortUUID, nil, &out); err != nil {
		return nil, err
	}
	id, _ := out.Response.ID.Int64()
	if id == 0 && out.Response.UUID == "" {
		return nil, errors.New("user not found")
	}
	return &panelUser{ID: id, UUID: out.Response.UUID, Limit: out.Response.HwidDeviceLimit}, nil
}

func (u *panelUser) key() string {
	if u.ID != 0 {
		return strconv.FormatInt(u.ID, 10)
	}
	return u.UUID
}

func (p *panel) devices(u *panelUser) ([]panelDevice, error) {
	var out struct {
		Response struct {
			Devices []panelDevice `json:"devices"`
		} `json:"response"`
	}
	err := p.call("GET", "/api/hwid/devices/"+u.key(), nil, &out)
	if err != nil && u.ID != 0 && u.UUID != "" {
		err = p.call("GET", "/api/hwid/devices/"+u.UUID, nil, &out) // older panels
	}
	return out.Response.Devices, err
}

func (p *panel) deleteDevice(u *panelUser, hwid string) error {
	var out json.RawMessage
	if u.ID != 0 {
		if err := p.call("POST", "/api/hwid/devices/delete", map[string]any{"userId": u.ID, "hwid": hwid}, &out); err == nil || u.UUID == "" {
			return err
		}
	}
	return p.call("POST", "/api/hwid/devices/delete", map[string]any{"userUuid": u.UUID, "hwid": hwid}, &out)
}
