package iykyk

import (
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
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

func TestProviderRoutingAndDNS(t *testing.T) {
	opts := `{"bypassRussia":false,"ruleSetDir":"/rs","remoteDNS":"adguard","directDNS":"google","routing":{
		"block":["domain:ads.example","keyword:tracker"],
		"proxy":["full:youtube.ru"],
		"direct":["domain:my-bank.example","regexp:^.+\\.local$","10.0.0.0/8","2001:db8::/32","geoip:ru","geosite:unknown"]}}`
	p, err := BuildProfileWithOptions("trojan://pw@de.example.com:443#DE", opts)
	if err != nil {
		t.Fatal(err)
	}
	sb := decode(t, p.SingBoxConfig)
	rules := path(t, sb, "route", "rules").([]any)
	// sniff, hijack-dns, private, then: block(domains), proxy(domains), direct(domains), direct(ips), direct(rule set)
	got := func(i int) map[string]any { return rules[i].(map[string]any) }
	if got(3)["action"] != "reject" || got(3)["domain_suffix"].([]any)[0] != "ads.example" || got(3)["domain_keyword"].([]any)[0] != "tracker" {
		t.Fatalf("block rule %v", got(3))
	}
	if got(4)["outbound"] != "proxy" || got(4)["domain"].([]any)[0] != "youtube.ru" {
		t.Fatalf("proxy rule %v", got(4))
	}
	if got(5)["outbound"] != "direct" || got(5)["domain_regex"] == nil {
		t.Fatalf("direct domains %v", got(5))
	}
	ips := got(6)["ip_cidr"].([]any)
	if got(6)["outbound"] != "direct" || len(ips) != 2 || ips[1] != "2001:db8::/32" {
		t.Fatalf("direct ips %v", got(6))
	}
	if got(7)["rule_set"].([]any)[0] != "geoip-ru" || len(rules) != 8 {
		t.Fatalf("rule set rule %v (rules %d)", got(7), len(rules))
	}
	// Rule-set definitions are present even with the bypass off.
	if path(t, sb, "route", "rule_set", 1, "tag") != "geoip-ru" {
		t.Fatal("rule-set definitions missing")
	}
	// DNS presets.
	if path(t, sb, "dns", "servers", 0, "server") != "dns.adguard-dns.com" || path(t, sb, "dns", "servers", 1, "server") != "8.8.8.8" {
		t.Fatalf("dns servers %v", path(t, sb, "dns", "servers"))
	}
	// Direct domains resolve with the direct DNS, IPs do not create DNS rules.
	dnsRules := path(t, sb, "dns", "rules").([]any)
	if len(dnsRules) != 2 || dnsRules[1].(map[string]any)["domain_suffix"].([]any)[0] != "my-bank.example" {
		t.Fatalf("dns rules %v", dnsRules)
	}

	custom, _ := BuildProfileWithOptions("trojan://pw@de.example.com:443#DE", `{"remoteDNS":"https://doh.example.net:8443/q"}`)
	server := path(t, decode(t, custom.SingBoxConfig), "dns", "servers", 0).(map[string]any)
	if server["server"] != "doh.example.net" || server["server_port"].(float64) != 8443 || server["path"] != "/q" {
		t.Fatalf("custom DoH %v", server)
	}
}

func TestRoutingHeader(t *testing.T) {
	routing := `{"direct":["domain:a.example"]}`
	header := http.Header{}
	header.Set("Iykyk-Routing", "base64:"+base64.StdEncoding.EncodeToString([]byte(routing)))
	sub, err := parseSubscription(sampleLinks, header)
	if err != nil {
		t.Fatal(err)
	}
	if string(sub.Routing) != routing {
		t.Fatalf("routing %q", sub.Routing)
	}
	header.Set("Iykyk-Routing", "not json")
	sub, _ = parseSubscription(sampleLinks, header)
	if sub.Routing != nil {
		t.Fatal("invalid routing must be ignored")
	}
}
