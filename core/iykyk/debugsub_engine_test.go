//go:build engine

package iykyk

import (
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/experimental/libbox"
)

// TestDebugSubscription runs the provider's real subscription through the same
// code the app uses: fetch, parse, build each server's configs, check them with
// both engines, and send real traffic through each server. It runs only in the
// manual "Debug subscription" workflow, with the URL in a repository secret.
// Nothing secret is printed: no URL, no hosts, no keys.
func TestDebugSubscription(t *testing.T) {
	url := os.Getenv("IYKYK_DEBUG_SUB")
	if url == "" {
		t.Skip("IYKYK_DEBUG_SUB not set")
	}
	device := &DeviceInfo{HWID: "iykyk-ci-debug", OS: "Android", OSVersion: "16", Model: "IYKYK CI check"}
	text, err := FetchSubscriptionWithFallbacks(url, "[]", "IYKYK/0.1.0 (Android 16)", device)
	if err != nil {
		t.Fatalf("fetch: %v", redactURL(err.Error(), url))
	}
	var sub subscription
	if err := json.Unmarshal([]byte(text), &sub); err != nil {
		t.Fatal(err)
	}
	t.Logf("title=%q servers=%d hwidLimited=%v limitReached=%v routing=%v unsupported=%v",
		sub.Title, len(sub.Servers), sub.HwidLimited, sub.HwidLimitReached, len(sub.Routing) > 0, sub.Unsupported)

	ruleSets := os.Getenv("IYKYK_RULESET_DIR")
	for _, mode := range []struct{ name, opts string }{
		{"default", `{"bypassRussia":true,"ruleSetDir":"` + ruleSets + `"}`},
		{"preferXray", `{"bypassRussia":true,"ruleSetDir":"` + ruleSets + `","preferXray":true}`},
	} {
		for i, srv := range sub.Servers {
			s, _ := parseLink(srv.Link)
			engine := "?"
			if s != nil {
				opts, _ := parseOptions(mode.opts)
				switch selectEngine(s, opts) {
				case engineXray:
					engine = "xray"
				case engineSingBox:
					engine = "sing-box"
				case engineSidecar:
					engine = "sidecar"
				}
			}
			line := []string{mode.name, srv.Name, srv.Details, "engine=" + engine}
			p, err := BuildProfileWithOptions(srv.Link, mode.opts)
			if err != nil {
				t.Logf("#%d %s build error: %v", i, strings.Join(line, " | "), err)
				continue
			}
			if err := libbox.CheckConfig(p.SingBoxConfig); err != nil {
				line = append(line, "sing-box config: "+err.Error())
			}
			if p.UsesXray {
				if err := CheckXrayConfig(p.XrayConfig); err != nil {
					line = append(line, "xray config: "+err.Error())
				}
			}
			links, _ := json.Marshal([]string{srv.Link})
			result, _ := RealPingServers(string(links), mode.opts)
			line = append(line, "real ping="+result)
			t.Logf("#%d %s", i, strings.Join(line, " | "))
		}
	}
}

func redactURL(text, url string) string { return strings.ReplaceAll(text, url, "<subscription>") }
