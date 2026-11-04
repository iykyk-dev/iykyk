package iykyk

import (
	"errors"
	"strings"
)

// allowedHosts locks the app to one provider: a comma-separated list of
// subscription domains (subdomains included). Set at build time by core/build.sh
// (-ldflags "-X .../iykyk.allowedHosts=example.com"). Empty means no lock.
//
// The source code is open (GPL-3.0), so anyone may build an unlocked copy; the
// lock is what the official IYKYK build enforces.
var allowedHosts = ""

// ErrSubscriptionNotAllowed is returned for subscriptions from other providers.
var ErrSubscriptionNotAllowed = errors.New("subscription not allowed: this app works only with its own service")

// IsLocked reports whether this build only accepts the provider's subscriptions.
// A locked app accepts no manual server links either.
func IsLocked() bool {
	return strings.TrimSpace(allowedHosts) != ""
}

func subscriptionHostAllowed(host string) bool {
	if !IsLocked() {
		return true
	}
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	for _, allowed := range strings.Split(allowedHosts, ",") {
		allowed = strings.ToLower(strings.TrimSpace(allowed))
		if allowed == "" {
			continue
		}
		if host == allowed || strings.HasSuffix(host, "."+allowed) {
			return true
		}
	}
	return false
}
