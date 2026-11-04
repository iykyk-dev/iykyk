package iykyk

import (
	"encoding/json"
	"net"
	"testing"
)

func linksJSON(t *testing.T, links ...string) string {
	t.Helper()
	b, err := json.Marshal(links)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestRussiaBypass(t *testing.T) {
	link := "trojan://pw@de.example.com:443#DE"
	off, _ := BuildProfileWithOptions(link, "")
	if _, ok := decode(t, off.SingBoxConfig)["route"].(map[string]any)["rule_set"]; ok {
		t.Fatal("rule sets without bypass")
	}

	on, err := BuildProfileWithOptions(link, `{"bypassRussia":true,"ruleSetDir":"/data/rulesets"}`)
	if err != nil {
		t.Fatal(err)
	}
	sb := decode(t, on.SingBoxConfig)
	if path(t, sb, "route", "rule_set", 1, "path") != "/data/rulesets/geoip-ru.srs" {
		t.Fatalf("rule sets %v", path(t, sb, "route", "rule_set"))
	}
	rules := path(t, sb, "route", "rules").([]any)
	last := rules[len(rules)-1].(map[string]any)
	if last["outbound"] != "direct" || last["rule_set"].([]any)[0] != "geoip-ru" {
		t.Fatalf("last rule %v", last)
	}
	if path(t, sb, "dns", "rules", 1, "domain_suffix", 2) != "xn--p1ai" {
		t.Fatalf("dns rules %v", path(t, sb, "dns", "rules"))
	}

	// Without rule-set files only the domain zones are bypassed.
	zones, _ := BuildProfileWithOptions(link, `{"bypassRussia":true}`)
	if _, ok := decode(t, zones.SingBoxConfig)["route"].(map[string]any)["rule_set"]; ok {
		t.Fatal("rule sets without a directory")
	}
}

func TestPingServers(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	open := listener.Addr().(*net.TCPAddr).Port
	closedPort, _ := freeLoopbackPort()

	text, err := PingServers(linksJSON(t,
		"trojan://pw@127.0.0.1:"+itoa(open)+"#up",
		"trojan://pw@127.0.0.1:"+itoa(closedPort)+"#down",
		"hysteria2://pw@127.0.0.1:443#udp",
		"garbage",
	))
	if err != nil {
		t.Fatal(err)
	}
	var results []int
	_ = json.Unmarshal([]byte(text), &results)
	if len(results) != 4 || results[0] <= 0 || results[1] != -1 || results[2] != -2 || results[3] != -1 {
		t.Fatalf("results %v", results)
	}
}

func itoa(n int) string { b, _ := json.Marshal(n); return string(b) }
