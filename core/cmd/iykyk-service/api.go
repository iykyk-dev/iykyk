package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/iykyk-dev/iykyk/core/iykyk"
)

// apiAddress: loopback only. Every request needs the token from tokenFile,
// which only local users can read; requests from web pages (with an Origin
// header) are refused, so a site cannot drive the VPN.
const apiAddress = "127.0.0.1:47231"

type server struct {
	dataDir   string
	tokenFile string
	token     string
	http      *http.Server

	mu          sync.Mutex
	state       string // disconnected, connecting, connected
	lastError   string
	name        string
	connectedAt time.Time
	profile     *iykyk.Profile
}

func newServer() (*server, error) {
	dir := dataDir()
	if err := os.MkdirAll(filepath.Join(dir, "logs"), 0o755); err != nil {
		return nil, err
	}
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	s := &server{
		dataDir:   dir,
		tokenFile: filepath.Join(dir, "api-token"),
		token:     hex.EncodeToString(raw),
		state:     "disconnected",
	}
	if err := os.WriteFile(s.tokenFile, []byte(s.token), 0o644); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *server) listen() error {
	s.http = &http.Server{Addr: apiAddress, Handler: s.handler(), ReadHeaderTimeout: 5 * time.Second}
	err := s.http.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *server) shutdown() {
	_ = iykyk.StopDesktop()
	if s.http != nil {
		_ = s.http.Close()
	}
}

func (s *server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/status", func(w http.ResponseWriter, r *http.Request) { reply(w, s.status(), nil) })
	mux.HandleFunc("POST /v1/versions", func(w http.ResponseWriter, r *http.Request) {
		reply(w, map[string]any{"versions": iykyk.Versions(), "locked": iykyk.IsLocked()}, nil)
	})
	mux.HandleFunc("POST /v1/describe", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Link string }
		if !decode(w, r, &in) {
			return
		}
		p, err := iykyk.DescribeLink(in.Link)
		if err != nil {
			reply(w, nil, err)
			return
		}
		reply(w, map[string]any{"name": p.Name, "protocol": p.Protocol, "details": p.Details, "address": p.Address}, nil)
	})
	mux.HandleFunc("POST /v1/fetch", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			URL       string
			Fallbacks string
			UserAgent string
			Device    iykyk.DeviceInfo
		}
		if !decode(w, r, &in) {
			return
		}
		text, err := iykyk.FetchSubscriptionWithFallbacks(in.URL, in.Fallbacks, in.UserAgent, &in.Device)
		replyRaw(w, text, err)
	})
	mux.HandleFunc("POST /v1/parse-text", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Text string }
		if !decode(w, r, &in) {
			return
		}
		text, err := iykyk.ParseSubscriptionText(in.Text)
		replyRaw(w, text, err)
	})
	mux.HandleFunc("POST /v1/real-ping", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Links, Options string }
		if !decode(w, r, &in) {
			return
		}
		text, err := iykyk.RealPingServers(in.Links, s.options(in.Options))
		replyRaw(w, text, err)
	})
	mux.HandleFunc("POST /v1/connect", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Link, Options string }
		if !decode(w, r, &in) {
			return
		}
		reply(w, s.connect(in.Link, in.Options), nil)
	})
	mux.HandleFunc("POST /v1/disconnect", func(w http.ResponseWriter, r *http.Request) {
		s.disconnect()
		reply(w, s.status(), nil)
	})
	mux.HandleFunc("POST /v1/check", func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		p := s.profile
		s.mu.Unlock()
		if p == nil {
			reply(w, nil, errors.New("not connected"))
			return
		}
		info, err := iykyk.CheckTunnel(p.CheckPort, p.CheckUser, p.CheckPass)
		if err != nil {
			reply(w, nil, err)
			return
		}
		reply(w, map[string]any{"ip": info.IP, "country": info.Country, "latency": info.Latency}, nil)
	})
	return s.auth(mux)
}

func (s *server) auth(next http.Handler) http.Handler {
	expected := []byte("Bearer " + s.token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Origin") != "" ||
			subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), expected) != 1 {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// options pins what the service decides itself: where logs and rule sets are.
func (s *server) options(text string) string {
	opts := map[string]any{}
	if text != "" {
		_ = json.Unmarshal([]byte(text), &opts)
	}
	opts["logDir"] = filepath.Join(s.dataDir, "logs")
	opts["ruleSetDir"] = filepath.Join(installDir(), "rulesets")
	out, _ := json.Marshal(opts)
	return string(out)
}

func (s *server) connect(link, options string) map[string]any {
	s.mu.Lock()
	s.state, s.lastError = "connecting", ""
	s.mu.Unlock()
	for _, name := range []string{"singbox.log", "xray.log"} {
		_ = os.Remove(filepath.Join(s.dataDir, "logs", name))
	}
	profile, err := iykyk.BuildProfileWithOptions(link, s.options(options))
	if err == nil {
		err = iykyk.StartDesktop(profile)
	}
	s.mu.Lock()
	if err != nil {
		s.state, s.lastError, s.profile = "disconnected", err.Error(), nil
	} else {
		if s.profile == nil {
			s.connectedAt = time.Now()
		}
		s.state, s.name, s.profile = "connected", profile.Name, profile
	}
	s.mu.Unlock()
	return s.status()
}

func (s *server) disconnect() {
	_ = iykyk.StopDesktop()
	s.mu.Lock()
	s.state, s.profile, s.connectedAt = "disconnected", nil, time.Time{}
	s.mu.Unlock()
}

func (s *server) status() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	status := map[string]any{"state": s.state, "error": s.lastError, "name": s.name, "logDir": filepath.Join(s.dataDir, "logs")}
	if !s.connectedAt.IsZero() {
		status["connectedAt"] = s.connectedAt.UnixMilli()
	}
	return status
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(v); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return false
	}
	return true
}

func reply(w http.ResponseWriter, v any, err error) {
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		w.WriteHeader(http.StatusUnprocessableEntity)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	_ = json.NewEncoder(w).Encode(v)
}

// replyRaw passes a JSON document from the core through unchanged.
func replyRaw(w http.ResponseWriter, text string, err error) {
	if err != nil {
		reply(w, nil, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, text)
}
