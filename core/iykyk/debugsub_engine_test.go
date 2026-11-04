//go:build engine

package iykyk

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/experimental/libbox"
	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/proxy"
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
			line = append(line, "full config: "+fullConfigCheck(t, srv.Link, mode.opts, hosts(sub)))
			t.Logf("#%d %s", i, strings.Join(line, " | "))
		}
	}
}

// fullConfigCheck runs the app's complete config (DNS, routing, rule sets,
// Xray) with only the TUN removed, and does the in-app tunnel check through
// its check inbound: a request by domain name, so DNS through the tunnel is
// exercised exactly as for apps on the phone.
func fullConfigCheck(t *testing.T, link, optionsJSON string, secrets []string) string {
	logDir := t.TempDir()
	optionsJSON = strings.TrimSuffix(optionsJSON, "}") + `,"logDir":"` + logDir + `"}`
	p, err := BuildProfileWithOptions(link, optionsJSON)
	if err != nil {
		return "build: " + err.Error()
	}
	var config map[string]any
	_ = json.Unmarshal([]byte(p.SingBoxConfig), &config)
	var inbounds []any
	for _, in := range config["inbounds"].([]any) {
		if in.(map[string]any)["type"] != "tun" {
			inbounds = append(inbounds, in)
		}
	}
	config["inbounds"] = inbounds
	text, _ := json.Marshal(config)
	if err := StartEngines(p); err != nil {
		return "engines: " + err.Error()
	}
	defer StopEngines()
	instance, err := startHeadlessBox(string(text))
	if err != nil {
		return "sing-box: " + err.Error()
	}
	defer instance.Close()
	dnsResult := dnsThroughTunnel(p.CheckPort, p.CheckUser, p.CheckPass, "example.org")
	info, err := CheckTunnel(p.CheckPort, p.CheckUser, p.CheckPass)
	result := "dns: " + dnsResult + "; "
	if err != nil {
		result += "CHECK FAILED: " + err.Error()
	} else {
		result += "check ok country=" + info.Country + " " + strconv.Itoa(info.Latency) + "ms"
	}
	for _, name := range []string{"singbox.log", "xray.log"} {
		data, _ := os.ReadFile(filepath.Join(logDir, name))
		lines := strings.Split(strings.TrimSpace(string(data)), "\n")
		if len(lines) > 8 {
			lines = lines[len(lines)-8:]
		}
		text := strings.Join(lines, " ⏎ ")
		for _, secret := range secrets {
			text = strings.ReplaceAll(text, secret, "<host>")
		}
		if strings.TrimSpace(text) != "" {
			result += " || " + name + ": " + text
		}
	}
	return result
}

func hosts(sub subscription) []string {
	var out []string
	for _, srv := range sub.Servers {
		if s, err := parseLink(srv.Link); err == nil {
			out = append(out, s.Host)
			out = append(out, s.ExtraHosts...)
			if s.UUID != "" {
				out = append(out, s.UUID)
			}
		}
	}
	return out
}

func redactURL(text, url string) string { return strings.ReplaceAll(text, url, "<subscription>") }

// dnsThroughTunnel sends a DNS query over TCP to 9.9.9.9:53 through the check
// inbound. sing-box sniffs it as DNS and answers with its own DNS (hijack-dns),
// as it does for apps' queries arriving through the TUN.
func dnsThroughTunnel(port int, user, pass, name string) string {
	socks, err := proxy.SOCKS5("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), &proxy.Auth{User: user, Password: pass}, &net.Dialer{})
	if err != nil {
		return "socks: " + err.Error()
	}
	conn, err := socks.Dial("tcp", "9.9.9.9:53")
	if err != nil {
		return "dial: " + err.Error()
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	query, _ := (&dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 7, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: dnsmessage.MustNewName(name + "."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}},
	}).Pack()
	frame := append([]byte{byte(len(query) >> 8), byte(len(query))}, query...)
	if _, err := conn.Write(frame); err != nil {
		return "write: " + err.Error()
	}
	size := make([]byte, 2)
	if _, err := io.ReadFull(conn, size); err != nil {
		return "FAILED read: " + err.Error()
	}
	answer := make([]byte, int(size[0])<<8|int(size[1]))
	if _, err := io.ReadFull(conn, answer); err != nil {
		return "FAILED read: " + err.Error()
	}
	var msg dnsmessage.Message
	if err := msg.Unpack(answer); err != nil {
		return "FAILED unpack: " + err.Error()
	}
	return fmt.Sprintf("rcode=%v answers=%d", msg.RCode, len(msg.Answers))
}
