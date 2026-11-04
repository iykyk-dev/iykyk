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
	Title       string               `json:"title,omitempty"`
	Upload      int64                `json:"upload"`
	Download    int64                `json:"download"`
	Total       int64                `json:"total"`  // bytes, 0 = unlimited
	Expire      int64                `json:"expire"` // unix seconds, 0 = never
	SupportURL  string               `json:"supportUrl,omitempty"`
	WebPageURL  string               `json:"webPageUrl,omitempty"`
	Announce    string               `json:"announce,omitempty"`
	UpdateHours int                  `json:"updateHours,omitempty"`
	Servers     []subscriptionServer `json:"servers"`
	Unsupported []string             `json:"unsupported,omitempty"` // schemes we skipped
}

type subscriptionServer struct {
	Name     string `json:"name"`
	Protocol string `json:"protocol"`
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
	u, err := url.Parse(strings.TrimSpace(subscriptionURL))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return "", errors.New("invalid subscription URL")
	}
	if !subscriptionHostAllowed(u.Hostname()) {
		return "", ErrSubscriptionNotAllowed
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
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

	client := &http.Client{
		// A redirect must not lead away from the allowed hosts either.
		CheckRedirect: func(next *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return errors.New("too many redirects")
			}
			if !subscriptionHostAllowed(next.URL.Hostname()) {
				return ErrSubscriptionNotAllowed
			}
			return nil
		},
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
		return "", fmt.Errorf("server answered %s", resp.Status)
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

func parseSubscription(body string, header http.Header) (*subscription, error) {
	sub := &subscription{Servers: []subscriptionServer{}}
	readSubscriptionHeaders(sub, header)

	text := strings.TrimSpace(body)
	if !strings.Contains(text, "://") {
		if decoded, err := decodeBase64(strings.Join(strings.Fields(text), "")); err == nil {
			text = decoded
		}
	}
	if strings.HasPrefix(text, "{") || strings.HasPrefix(text, "[") {
		return nil, errors.New("the panel returned a JSON config instead of a link list; " +
			"check the response rules for the IYKYK user agent")
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
