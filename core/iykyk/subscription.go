package iykyk

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Subscription is the parsed result of a subscription URL (Remnawave, Marzban,
// 3x-ui and other panels that serve a base64 or plain list of share links).
type subscription struct {
	Title       string `json:"title,omitempty"`
	Upload      int64  `json:"upload"`
	Download    int64  `json:"download"`
	Total       int64  `json:"total"`  // bytes, 0 = unlimited
	Expire      int64  `json:"expire"` // unix seconds, 0 = never
	SupportURL  string `json:"supportUrl,omitempty"`
	WebPageURL  string `json:"webPageUrl,omitempty"`
	Announce    string `json:"announce,omitempty"`
	UpdateHours int    `json:"updateHours,omitempty"`
	// Backup subscription URLs (header "Fallback-Urls" or "Iykyk-Fallback-Urls").
	Fallbacks []string `json:"fallbacks,omitempty"`
	// Provider routing (header "Iykyk-Routing": JSON, plain or "base64:...").
	Routing json.RawMessage `json:"routing,omitempty"`
	// Remnawave HWID device limit (x-hwid-* response headers).
	HwidLimited      bool `json:"hwidLimited,omitempty"`
	HwidLimitReached bool `json:"hwidLimitReached,omitempty"`
	// Where the app asks how many devices are connected (Iykyk-Devices-Api).
	DevicesAPI  string               `json:"devicesApi,omitempty"`
	Servers     []subscriptionServer `json:"servers"`
	Unsupported []string             `json:"unsupported,omitempty"` // schemes we skipped
}

type subscriptionServer struct {
	Name     string `json:"name"`
	Protocol string `json:"protocol"`
	Details  string `json:"details"` // e.g. "VLESS · gRPC · REALITY"
	Address  string `json:"address"`
	Link     string `json:"link"`
}

const subscriptionMaxBytes = 4 << 20

// DeviceInfo identifies the device to panels with a device limit
// (Remnawave HWID: x-hwid, x-device-os, x-ver-os, x-device-model).
type DeviceInfo struct {
	HWID      string
	OS        string
	OSVersion string
	Model     string
}

// FetchSubscription downloads a subscription and returns it as JSON
// (see the subscription struct). userAgent lets the panel pick the format;
// panels answer unknown clients with the universal base64 list.
// device may be nil.
func FetchSubscription(subscriptionURL string, userAgent string, device *DeviceInfo) (string, error) {
	return FetchSubscriptionWithFallbacks(subscriptionURL, "", userAgent, device)
}

// FetchSubscriptionWithFallbacks is FetchSubscription with backup URLs
// (fallbacksJSON: JSON array, usually the "fallbacks" of the last result).
// Each URL is tried normally first, then hardened (DoH + TLS fragmentation),
// so a blocked domain or a spoofed DNS answer does not cut users off.
func FetchSubscriptionWithFallbacks(subscriptionURL, fallbacksJSON, userAgent string, device *DeviceInfo) (string, error) {
	urls := []string{subscriptionURL}
	if fallbacksJSON != "" {
		var fallbacks []string
		if err := json.Unmarshal([]byte(fallbacksJSON), &fallbacks); err == nil {
			urls = append(urls, fallbacks...)
		}
	}
	var firstErr error
	for i, raw := range urls {
		for _, hardened := range []bool{false, true} {
			result, err := fetchSubscriptionOnce(raw, userAgent, device, hardened)
			if err == nil {
				return result, nil
			}
			if i == 0 && firstErr == nil {
				firstErr = err
			}
			var status *httpStatusError
			if errors.Is(err, ErrSubscriptionNotAllowed) || errors.As(err, &status) || errors.Is(err, errInvalidURL) {
				break // the server was reached or the URL is unusable: no point in hardening
			}
		}
	}
	if firstErr == nil {
		firstErr = errors.New("download failed")
	}
	return "", firstErr
}

var errInvalidURL = errors.New("invalid subscription URL")

type httpStatusError struct{ status string }

func (e *httpStatusError) Error() string { return "server answered " + e.status }

func fetchSubscriptionOnce(subscriptionURL, userAgent string, device *DeviceInfo, hardened bool) (string, error) {
	u, err := url.Parse(strings.TrimSpace(subscriptionURL))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return "", errInvalidURL
	}
	if !subscriptionHostAllowed(u.Hostname()) {
		return "", ErrSubscriptionNotAllowed
	}
	timeout := 10 * time.Second
	if hardened {
		timeout = 20 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "*/*")
	if device != nil {
		for name, value := range map[string]string{
			"X-Hwid":         device.HWID,
			"X-Device-Os":    device.OS,
			"X-Ver-Os":       device.OSVersion,
			"X-Device-Model": device.Model,
		} {
			if value != "" {
				req.Header.Set(name, value)
			}
		}
	}

	// A redirect must not lead away from the allowed hosts either.
	redirect := func(next *http.Request, via []*http.Request) error {
		if len(via) >= 5 {
			return errors.New("too many redirects")
		}
		if !subscriptionHostAllowed(next.URL.Hostname()) {
			return ErrSubscriptionNotAllowed
		}
		return nil
	}
	client := &http.Client{CheckRedirect: redirect}
	if hardened {
		client = hardenedClient(redirect)
	}
	resp, err := client.Do(req)
	if err != nil {
		if errors.Is(err, ErrSubscriptionNotAllowed) {
			return "", ErrSubscriptionNotAllowed
		}
		return "", fmt.Errorf("download failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", &httpStatusError{resp.Status}
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, subscriptionMaxBytes))
	if err != nil {
		return "", fmt.Errorf("download failed: %w", err)
	}
	sub, err := parseSubscription(string(body), resp.Header)
	if err != nil {
		return "", err
	}
	return marshal(sub)
}

// ParseSubscriptionText parses a subscription body without downloading it,
// for links pasted as text (a base64 blob or several links at once).
func ParseSubscriptionText(body string) (string, error) {
	if IsLocked() {
		return "", ErrSubscriptionNotAllowed
	}
	sub, err := parseSubscription(body, nil)
	if err != nil {
		return "", err
	}
	return marshal(sub)
}

// ErrDeviceLimit: Remnawave refused this device because the subscription's
// device limit is reached. The app shows a clear message instead of "no servers".
var ErrDeviceLimit = errors.New("hwid: device limit reached")

func parseSubscription(body string, header http.Header) (*subscription, error) {
	sub := &subscription{Servers: []subscriptionServer{}}
	readSubscriptionHeaders(sub, header)
	sub2, err := parseSubscriptionBody(sub, body)
	if err != nil && sub.HwidLimitReached {
		return nil, ErrDeviceLimit
	}
	return sub2, err
}

func parseSubscriptionBody(sub *subscription, body string) (*subscription, error) {
	text := strings.TrimSpace(body)
	if !strings.Contains(text, "://") {
		if decoded, err := decodeBase64(strings.Join(strings.Fields(text), "")); err == nil {
			text = decoded
		}
	}
	if strings.HasPrefix(text, "{") || strings.HasPrefix(text, "[") {
		// Full Xray configs (e.g. Remnawave's XRAY_JSON, with balancers for auto-select).
		servers, err := parseXrayJSONSubscription(text)
		if err != nil {
			return nil, err
		}
		for _, s := range servers {
			sub.Servers = append(sub.Servers, subscriptionServer{
				Name: s.Name, Protocol: s.Protocol, Details: s.details(), Address: s.address(), Link: s.Raw,
			})
		}
		return sub, nil
	}

	seen := map[string]bool{}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, "://") {
			continue
		}
		s, err := parseLink(line)
		if err != nil {
			scheme, _, _ := strings.Cut(line, "://")
			scheme = strings.ToLower(scheme)
			if !seen["unsupported:"+scheme] {
				seen["unsupported:"+scheme] = true
				sub.Unsupported = append(sub.Unsupported, scheme)
			}
			continue
		}
		sub.Servers = append(sub.Servers, subscriptionServer{
			Name:     s.Name,
			Protocol: s.Protocol,
			Details:  s.details(),
			Address:  s.address(),
			Link:     line,
		})
	}
	if len(sub.Servers) == 0 {
		if len(sub.Unsupported) > 0 {
			return nil, fmt.Errorf("no supported servers (found: %s)", strings.Join(sub.Unsupported, ", "))
		}
		return nil, errors.New("no servers found in the subscription")
	}
	return sub, nil
}

func readSubscriptionHeaders(sub *subscription, header http.Header) {
	if header == nil {
		return
	}
	sub.Title = decodeHeaderText(header.Get("Profile-Title"))
	sub.Announce = decodeHeaderText(header.Get("Announce"))
	sub.SupportURL = header.Get("Support-Url")
	sub.WebPageURL = header.Get("Profile-Web-Page-Url")
	sub.HwidLimited = isTrue(header.Get("X-Hwid-Active"))
	sub.HwidLimitReached = isTrue(header.Get("X-Hwid-Max-Devices-Reached")) || isTrue(header.Get("X-Hwid-Limit"))
	if api := strings.TrimSpace(header.Get("Iykyk-Devices-Api")); strings.HasPrefix(api, "https://") {
		if u, err := url.Parse(api); err == nil && subscriptionHostAllowed(u.Hostname()) {
			sub.DevicesAPI = api
		}
	}
	if routing := decodeHeaderText(header.Get("Iykyk-Routing")); routing != "" {
		if decoded, err := decodeBase64(routing); err == nil && strings.HasPrefix(strings.TrimSpace(decoded), "{") {
			routing = decoded
		}
		var check providerRouting
		if json.Unmarshal([]byte(routing), &check) == nil {
			sub.Routing = json.RawMessage(routing)
		}
	}
	for _, name := range []string{"Iykyk-Fallback-Urls", "Fallback-Urls"} {
		for _, u := range splitList(header.Get(name)) {
			if strings.HasPrefix(u, "https://") || strings.HasPrefix(u, "http://") {
				sub.Fallbacks = append(sub.Fallbacks, u)
			}
		}
	}
	if hours, err := strconv.Atoi(strings.TrimSpace(header.Get("Profile-Update-Interval"))); err == nil {
		sub.UpdateHours = hours
	}
	// subscription-userinfo: upload=123; download=456; total=789; expire=1700000000
	for _, part := range strings.Split(header.Get("Subscription-Userinfo"), ";") {
		key, value, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok {
			continue
		}
		number, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
		if err != nil {
			continue
		}
		switch strings.ToLower(key) {
		case "upload":
			sub.Upload = number
		case "download":
			sub.Download = number
		case "total":
			sub.Total = number
		case "expire":
			sub.Expire = number
		}
	}
}

// decodeHeaderText handles the "base64:..." convention panels use for
// non-ASCII header values such as profile titles and announcements.
func decodeHeaderText(value string) string {
	value = strings.TrimSpace(value)
	if rest, ok := strings.CutPrefix(value, "base64:"); ok {
		if decoded, err := decodeBase64(rest); err == nil {
			return decoded
		}
	}
	return value
}

// subscriptionJSON is used by tests to read results back.
func subscriptionFromJSON(text string) (*subscription, error) {
	var sub subscription
	err := json.Unmarshal([]byte(text), &sub)
	return &sub, err
}
