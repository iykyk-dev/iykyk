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

func TestAutoProfile(t *testing.T) {
	links := linksJSON(t,
		"vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?type=tcp&security=reality&pbk=K&sid=1&sni=a.com#NL",
		"trojan://pw@de.example.com:443#DE",
		"hysteria2://pass@fi.example.com:443#FI",
		"ss://2022-blake3-aes-128-gcm:a2V5@stls.example.com:443?plugin=shadow-tls%3Bhost%3Dc.com%3Bpasswd%3Dp%3Bv3%3D1#STLS",
		"mierus://u:p@m.example.com?profile=d&port=1&protocol=TCP#Mieru", // left out of auto mode
		"not a link",
	)
	p, err := BuildAutoProfile(links, `{"bypassRussia":true}`)
	if err != nil {
		t.Fatal(err)
	}
	if !p.IsAuto() || !p.UsesXray || len(p.autoNames) != 4 {
		t.Fatalf("profile %+v", p)
	}

	sb := decode(t, p.SingBoxConfig)
	group := path(t, sb, "outbounds", 0)
	if path(t, group, "type") != "urltest" || path(t, group, "tag") != "proxy" {
		t.Fatalf("group %v", group)
	}
	members := path(t, group, "outbounds").([]any)
	if len(members) != 4 {
		t.Fatalf("members %v", members)
	}
	// Every member tag exists, and ShadowTLS's chain was renamed consistently.
	tags := map[string]map[string]any{}
	for _, o := range path(t, sb, "outbounds").([]any) {
		m := o.(map[string]any)
		tags[m["tag"].(string)] = m
	}
	for _, m := range members {
		if tags[m.(string)] == nil {
			t.Fatalf("member %v has no outbound", m)
		}
	}
	if tags["s3"]["detour"] != "s3-stls" || tags["s3-stls"]["type"] != "shadowtls" {
		t.Fatalf("shadowtls chain not retagged: %v", tags["s3"])
	}
	if path(t, sb, "experimental", "clash_api", "external_controller") != p.clashController {
		t.Fatal("clash API not enabled")
	}
	// All server names resolve directly.
	if got := len(path(t, sb, "dns", "rules", 0, "domain").([]any)); got != 4 {
		t.Fatalf("direct DNS names = %d", got)
	}

	// One Xray instance serves both Xray servers through separate inbounds.
	x := decode(t, p.XrayConfig)
	if len(path(t, x, "inbounds").([]any)) != 2 || len(path(t, x, "routing", "rules").([]any)) != 2 {
		t.Fatalf("xray config %v", x)
	}
	if path(t, x, "routing", "rules", 1, "outboundTag") != path(t, x, "outbounds", 1, "tag") {
		t.Fatal("xray routing does not match outbounds")
	}
	if tags["s1"]["server_port"] != path(t, x, "inbounds", 1, "port") {
		t.Fatal("sing-box and xray ports differ")
	}
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
