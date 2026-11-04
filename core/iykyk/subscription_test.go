package iykyk

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const sampleLinks = "vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?type=tcp&security=reality&pbk=K&sid=1&sni=a.com#%F0%9F%87%B3%F0%9F%87%B1%20NL\n" +
	"hysteria2://pass@fi.example.com:443#FI\n" +
	"vmess://eyJ2IjoiMiJ9\n" +
	"vless://11111111-2222-3333-4444-555555555555@de.example.com:443?type=xhttp&security=tls&sni=b.com#DE\n"

func TestSubscriptionBase64(t *testing.T) {
	body := base64.StdEncoding.EncodeToString([]byte(sampleLinks))
	text, err := ParseSubscriptionText(body)
	if err != nil {
		t.Fatal(err)
	}
	sub, err := subscriptionFromJSON(text)
	if err != nil {
		t.Fatal(err)
	}
	if len(sub.Servers) != 3 {
		t.Fatalf("servers = %d, want 3: %+v", len(sub.Servers), sub.Servers)
	}
	if sub.Servers[0].Name != "🇳🇱 NL" || sub.Servers[1].Protocol != "hysteria2" || sub.Servers[2].Name != "DE" {
		t.Fatalf("unexpected servers %+v", sub.Servers)
	}
	if len(sub.Unsupported) != 1 || sub.Unsupported[0] != "vmess" {
		t.Fatalf("unsupported = %v", sub.Unsupported)
	}
	// The stored link must be usable to build a profile.
	if _, err := BuildProfile(sub.Servers[2].Link); err != nil {
		t.Fatal(err)
	}
}

func TestSubscriptionPlainAndBase64WithNewlines(t *testing.T) {
	if _, err := ParseSubscriptionText(sampleLinks); err != nil {
		t.Fatal(err)
	}
	encoded := base64.StdEncoding.EncodeToString([]byte(sampleLinks))
	wrapped := encoded[:20] + "\n" + encoded[20:]
	if _, err := ParseSubscriptionText(wrapped); err != nil {
		t.Fatal(err)
	}
}

func TestFetchSubscriptionHeaders(t *testing.T) {
	var gotUA, gotHWID string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUA = r.Header.Get("User-Agent")
		gotHWID = r.Header.Get("x-hwid")
		w.Header().Set("Profile-Title", "base64:"+base64.StdEncoding.EncodeToString([]byte("Для своих")))
		w.Header().Set("Subscription-Userinfo", "upload=100; download=200; total=1073741824; expire=1893456000")
		w.Header().Set("Support-Url", "https://t.me/support")
		w.Header().Set("Profile-Update-Interval", "12")
		w.Header().Set("Announce", "Плановые работы в 03:00")
		_, _ = w.Write([]byte(base64.StdEncoding.EncodeToString([]byte(sampleLinks))))
	}))
	defer srv.Close()

	text, err := FetchSubscription(srv.URL+"/api/sub/token", "IYKYK/0.1.0", &DeviceInfo{HWID: "abc", OS: "Android"})
	if err != nil {
		t.Fatal(err)
	}
	sub, _ := subscriptionFromJSON(text)
	if gotUA != "IYKYK/0.1.0" || gotHWID != "abc" {
		t.Fatalf("user agent = %q, hwid = %q", gotUA, gotHWID)
	}
	if sub.Title != "Для своих" || sub.Total != 1073741824 || sub.Download != 200 || sub.Expire != 1893456000 {
		t.Fatalf("headers not parsed: %+v", sub)
	}
	if sub.SupportURL != "https://t.me/support" || sub.UpdateHours != 12 || sub.Announce != "Плановые работы в 03:00" {
		t.Fatalf("headers not parsed: %+v", sub)
	}
}

func TestSubscriptionErrors(t *testing.T) {
	if _, err := ParseSubscriptionText(`{"outbounds":[]}`); err == nil || !strings.Contains(err.Error(), "Xray") {
		t.Fatalf("expected Xray config error, got %v", err)
	}
	if _, err := ParseSubscriptionText("vmess://abc\nwireguard://x"); err == nil || !strings.Contains(err.Error(), "vmess") {
		t.Fatalf("expected unsupported error, got %v", err)
	}
	if _, err := FetchSubscription("ftp://x", "ua", nil); err == nil {
		t.Fatal("expected invalid URL error")
	}
}

func TestHwidHeaders(t *testing.T) {
	header := http.Header{}
	header.Set("X-Hwid-Active", "true")
	header.Set("Iykyk-Devices-Api", "https://sub.example.com/iykyk/api")
	sub, err := parseSubscription(sampleLinks, header)
	if err != nil {
		t.Fatal(err)
	}
	if !sub.HwidLimited || sub.HwidLimitReached || sub.DevicesAPI != "https://sub.example.com/iykyk/api" {
		t.Fatalf("%+v", sub)
	}
	header.Set("X-Hwid-Max-Devices-Reached", "true")
	if _, err := parseSubscription("", header); err != ErrDeviceLimit {
		t.Fatalf("err = %v, want ErrDeviceLimit", err)
	}
}
