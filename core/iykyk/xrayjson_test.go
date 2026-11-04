package iykyk

import (
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// A trimmed Remnawave-style XRAY_JSON body: one auto-select config with a
// balancer over two servers, and one plain server config.
const xrayJSONSubscription = `[
 {
  "remarks": "⚡ Автовыбор",
  "inbounds": [{"tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
    "sniffing": {"enabled": true, "routeOnly": true, "destOverride": ["http", "tls", "quic"]}}],
  "outbounds": [
   {"tag": "nl", "protocol": "vless", "settings": {"vnext": [{"address": "nl.example.com", "port": 443,
     "users": [{"id": "11111111-2222-3333-4444-555555555555", "encryption": "none", "flow": "xtls-rprx-vision"}]}]},
    "streamSettings": {"network": "tcp", "security": "reality",
     "realitySettings": {"serverName": "a.com", "publicKey": "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw", "shortId": "ab", "fingerprint": "chrome"}}},
   {"tag": "de", "protocol": "trojan", "settings": {"servers": [{"address": "de.example.com", "port": 443, "password": "pw"}]},
    "streamSettings": {"network": "tcp", "security": "tls"}},
   {"tag": "direct", "protocol": "freedom"},
   {"tag": "block", "protocol": "blackhole"},
   {"tag": "fragment", "protocol": "freedom", "settings": {"fragment": {"packets": "tlshello", "length": "50-100", "interval": "10-20"}},
    "streamSettings": {"network": "raw", "sockopt": {"mark": 255, "TcpNoDelay": true}}}
  ],
  "routing": {
   "rules": [{"inboundTag": ["socks"], "balancerTag": "auto"}],
   "balancers": [{"tag": "auto", "selector": ["nl", "de"], "strategy": {"type": "leastPing"}}]
  },
  "observatory": {"subjectSelector": ["nl", "de"], "probeURL": "https://www.gstatic.com/generate_204", "probeInterval": "1m"},
  "api": {"tag": "api", "listen": "127.0.0.1:10085"}
 },
 {
  "remarks": "🇳🇱 Нидерланды",
  "outbounds": [
   {"tag": "proxy", "protocol": "vless", "settings": {"vnext": [{"address": "nl.example.com", "port": 443,
     "users": [{"id": "11111111-2222-3333-4444-555555555555", "encryption": "none"}]}]},
    "streamSettings": {"network": "xhttp", "security": "tls", "xhttpSettings": {"path": "/x"}}},
   {"tag": "direct", "protocol": "freedom"}
  ]
 }
]`

func TestXrayJSONSubscription(t *testing.T) {
	text, err := ParseSubscriptionText(xrayJSONSubscription)
	if err != nil {
		t.Fatal(err)
	}
	sub, _ := subscriptionFromJSON(text)
	if len(sub.Servers) != 2 {
		t.Fatalf("servers %+v", sub.Servers)
	}
	auto, plain := sub.Servers[0], sub.Servers[1]
	if auto.Protocol != "xray-balancer" || auto.Name != "⚡ Автовыбор" || plain.Protocol != "xray-json" {
		t.Fatalf("servers %+v", sub.Servers)
	}

	p, err := BuildProfileWithOptions(auto.Link, `{"fragment":true}`)
	if err != nil {
		t.Fatal(err)
	}
	if !p.UsesXray {
		t.Fatal("Xray JSON must run in Xray")
	}
	x := decode(t, p.XrayConfig)
	inbounds := path(t, x, "inbounds").([]any)
	if len(inbounds) != 1 || path(t, x, "inbounds", 0, "tag") != "socks" || path(t, x, "inbounds", 0, "listen") != "127.0.0.1" {
		t.Fatalf("inbounds %v", inbounds)
	}
	if _, ok := x["api"]; ok {
		t.Fatal("api listener must be removed")
	}
	if path(t, x, "inbounds", 0, "sniffing", "routeOnly") != true {
		t.Fatal("sniffing must be carried over to the IYKYK inbound")
	}
	sockopt := path(t, x, "outbounds", 4, "streamSettings", "sockopt").(map[string]any)
	if _, ok := sockopt["mark"]; ok || sockopt["TcpNoDelay"] != true {
		t.Fatalf("SO_MARK must be removed, other options kept: %v", sockopt)
	}
	if path(t, x, "routing", "balancers", 0, "tag") != "auto" || x["observatory"] == nil {
		t.Fatal("balancer and observatory must be kept")
	}
	if path(t, x, "outbounds", 0, "streamSettings", "finalmask", "tcp", 0, "type") != "fragment" {
		t.Fatal("fragment not added to REALITY outbound")
	}
	if _, ok := path(t, x, "outbounds", 2).(map[string]any)["streamSettings"]; ok {
		t.Fatal("freedom outbound must stay untouched")
	}

	sb := decode(t, p.SingBoxConfig)
	names := path(t, sb, "dns", "rules", 0, "domain").([]any)
	if len(names) != 2 || names[0] != "nl.example.com" || names[1] != "de.example.com" {
		t.Fatalf("direct DNS names %v", names)
	}
	if int(path(t, sb, "outbounds", 0, "server_port").(float64)) != int(path(t, x, "inbounds", 0, "port").(float64)) {
		t.Fatal("ports differ")
	}
}

func TestFragmentOptionForLinks(t *testing.T) {
	p, _ := BuildProfileWithOptions("trojan://pw@de.example.com:443#DE", `{"fragment":true}`)
	if path(t, decode(t, p.XrayConfig), "outbounds", 0, "streamSettings", "finalmask", "tcp", 0, "settings", "packets") != "tlshello" {
		t.Fatal("xray fragment missing")
	}
	p, _ = BuildProfileWithOptions("anytls://pw@a.example.com:443#A", `{"fragment":true}`)
	if path(t, decode(t, p.SingBoxConfig), "outbounds", 0, "tls", "fragment") != true {
		t.Fatal("sing-box fragment missing")
	}
	p, _ = BuildProfileWithOptions("trojan://pw@de.example.com:443#DE", "")
	if _, ok := path(t, decode(t, p.XrayConfig), "outbounds", 0, "streamSettings").(map[string]any)["finalmask"]; ok {
		t.Fatal("fragment must be off by default")
	}
}

func TestSubscriptionFallbackURL(t *testing.T) {
	backup := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Iykyk-Fallback-Urls", "https://mirror1.example/sub, https://mirror2.example/sub")
		_, _ = w.Write([]byte(sampleLinks))
	}))
	defer backup.Close()
	deadPort, _ := freeLoopbackPort() // nothing listens here

	text, err := FetchSubscriptionWithFallbacks(
		"http://127.0.0.1:"+itoa(deadPort)+"/sub",
		`["`+backup.URL+`/sub"]`,
		"IYKYK/test", nil,
	)
	if err != nil {
		t.Fatal(err)
	}
	sub, _ := subscriptionFromJSON(text)
	if len(sub.Servers) != 3 || len(sub.Fallbacks) != 2 || sub.Fallbacks[1] != "https://mirror2.example/sub" {
		t.Fatalf("subscription %+v", sub)
	}
}

func TestFragmentConnKeepsData(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	payload := strings.Repeat("ClientHello-", 50)
	go func() {
		c := &fragmentConn{Conn: client}
		_, _ = c.Write([]byte(payload))
		_, _ = c.Write([]byte("|second"))
		client.Close()
	}()
	got, _ := io.ReadAll(server)
	if string(got) != payload+"|second" {
		t.Fatalf("data corrupted: %q", got)
	}
}
