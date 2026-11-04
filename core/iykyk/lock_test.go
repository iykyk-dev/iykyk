package iykyk

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func withAllowedHosts(t *testing.T, hosts string) {
	t.Helper()
	previous := allowedHosts
	allowedHosts = hosts
	t.Cleanup(func() { allowedHosts = previous })
}

func TestSubscriptionHostAllowed(t *testing.T) {
	withAllowedHosts(t, "dlyasvoih.xyz, mirror.example")
	for host, want := range map[string]bool{
		"dlyasvoih.xyz":          true,
		"sub.dlyasvoih.xyz":      true,
		"SUB.DlyaSvoih.xyz.":     true,
		"a.b.mirror.example":     true,
		"dlyasvoih.xyz.evil.com": false,
		"evildlyasvoih.xyz":      false,
		"sub.other-provider.com": false,
		"":                       false,
	} {
		if got := subscriptionHostAllowed(host); got != want {
			t.Errorf("%q: got %v, want %v", host, got, want)
		}
	}
}

func TestLockedRejectsOtherProviders(t *testing.T) {
	withAllowedHosts(t, "dlyasvoih.xyz")
	if !IsLocked() {
		t.Fatal("expected locked")
	}
	if _, err := FetchSubscription("https://sub.other-provider.com/api/sub/x", "ua", nil); !errors.Is(err, ErrSubscriptionNotAllowed) {
		t.Fatalf("got %v", err)
	}
	if _, err := ParseSubscriptionText(sampleLinks); !errors.Is(err, ErrSubscriptionNotAllowed) {
		t.Fatalf("pasted links must be refused when locked, got %v", err)
	}
}

func TestLockedRejectsRedirectAway(t *testing.T) {
	away := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(sampleLinks))
	}))
	defer away.Close()
	allowed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, away.URL+"/sub", http.StatusFound)
	}))
	defer allowed.Close()

	// Both test servers listen on 127.0.0.1; allow "localhost" and reach the
	// allowed one by that name, so the redirect to 127.0.0.1 leaves the list.
	withAllowedHosts(t, "localhost")
	url := "http://localhost:" + allowed.URL[len("http://127.0.0.1:"):] + "/sub"
	if _, err := FetchSubscription(url, "ua", nil); !errors.Is(err, ErrSubscriptionNotAllowed) {
		t.Fatalf("redirect away must be refused, got %v", err)
	}
}

func TestUnlockedAcceptsAnything(t *testing.T) {
	withAllowedHosts(t, "")
	if IsLocked() || !subscriptionHostAllowed("any.example") {
		t.Fatal("unlocked build must accept any provider")
	}
}
