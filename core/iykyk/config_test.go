package iykyk

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
)

func decode(t *testing.T, text string) map[string]any {
	t.Helper()
	var v map[string]any
	if err := json.Unmarshal([]byte(text), &v); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, text)
	}
	return v
}

// path walks nested maps and slices: path(v, "outbounds", 0, "tag").
func path(t *testing.T, v any, keys ...any) any {
	t.Helper()
	for _, key := range keys {
		switch k := key.(type) {
		case string:
			m, ok := v.(map[string]any)
			if !ok {
				t.Fatalf("expected object at %v", key)
			}
			v = m[k]
		case int:
			s, ok := v.([]any)
			if !ok || k >= len(s) {
				t.Fatalf("expected array with index %d", k)
			}
			v = s[k]
		}
	}
	return v
}

// buildXray builds a profile the old way, with the proxy in Xray.
func buildXray(t *testing.T, link string) *Profile {
	t.Helper()
	p, err := BuildProfileWithOptions(link, `{"preferXray":true}`)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestVLESSReality(t *testing.T) {
	link := "vless://11111111-2222-3333-4444-555555555555@nl1.example.com:443?type=tcp&security=reality" +
		"&pbk=PUBKEY&sid=ab12&sni=www.microsoft.com&fp=chrome&flow=xtls-rprx-vision#%F0%9F%87%B3%F0%9F%87%B1%20Нидерланды"
	p := buildXray(t, link)
	if !p.UsesXray || p.Protocol != "vless" {
		t.Fatalf("unexpected profile %+v", p)
	}
	if p.Name != "🇳🇱 Нидерланды" {
		t.Fatalf("name = %q", p.Name)
	}
	x := decode(t, p.XrayConfig)
	out := path(t, x, "outbounds", 0)
	if got := path(t, out, "settings", "vnext", 0, "users", 0, "flow"); got != "xtls-rprx-vision" {
		t.Fatalf("flow = %v", got)
	}
	if got := path(t, out, "streamSettings", "network"); got != "raw" {
		t.Fatalf("network = %v", got)
	}
	if got := path(t, out, "streamSettings", "realitySettings", "publicKey"); got != "PUBKEY" {
		t.Fatalf("publicKey = %v", got)
	}
	sb := decode(t, p.SingBoxConfig)
	proxy := path(t, sb, "outbounds", 0)
	if path(t, proxy, "type") != "socks" || path(t, proxy, "server") != "127.0.0.1" {
		t.Fatalf("proxy outbound = %v", proxy)
	}
	xrayPort := path(t, x, "inbounds", 0, "port").(float64)
	if path(t, proxy, "server_port").(float64) != xrayPort {
		t.Fatal("sing-box and Xray ports differ")
	}
	if path(t, proxy, "username") != path(t, x, "inbounds", 0, "settings", "accounts", 0, "user") {
		t.Fatal("socks credentials differ")
	}
	// The server name must resolve through the direct DNS server.
	if got := path(t, sb, "dns", "rules", 0, "domain", 0); got != "nl1.example.com" {
		t.Fatalf("dns rule domain = %v", got)
	}
}

func TestVLESSXHTTP(t *testing.T) {
	extra := `{"xmux":{"maxConcurrency":"16-32"}}`
	link := "vless://uuid@1.2.3.4:443?type=xhttp&security=tls&sni=cdn.example.com&path=%2Fapi&mode=packet-up&alpn=h2,http/1.1&extra=" +
		strings.ReplaceAll(extra, `"`, "%22")
	p, err := BuildProfile(link)
	if err != nil {
		t.Fatal(err)
	}
	stream := path(t, decode(t, p.XrayConfig), "outbounds", 0, "streamSettings")
	if path(t, stream, "network") != "xhttp" || path(t, stream, "xhttpSettings", "mode") != "packet-up" {
		t.Fatalf("stream = %v", stream)
	}
	if path(t, stream, "xhttpSettings", "path") != "/api" {
		t.Fatalf("path = %v", path(t, stream, "xhttpSettings", "path"))
	}
	if path(t, stream, "xhttpSettings", "extra", "xmux", "maxConcurrency") != "16-32" {
		t.Fatal("extra not passed through")
	}
	if path(t, stream, "tlsSettings", "alpn", 1) != "http/1.1" {
		t.Fatal("alpn not parsed")
	}
	// IP address: no DNS rule needed.
	if rules := path(t, decode(t, p.SingBoxConfig), "dns", "rules").([]any); len(rules) != 0 {
		t.Fatalf("unexpected dns rules %v", rules)
	}
}

func TestTrojanWS(t *testing.T) {
	p := buildXray(t, "trojan://secret@example.org:443?type=ws&path=%2Fws&host=front.example.org#tr")
	out := path(t, decode(t, p.XrayConfig), "outbounds", 0)
	if path(t, out, "settings", "servers", 0, "password") != "secret" {
		t.Fatal("password")
	}
	if path(t, out, "streamSettings", "security") != "tls" {
		t.Fatal("trojan must default to tls")
	}
	if path(t, out, "streamSettings", "wsSettings", "host") != "front.example.org" {
		t.Fatal("ws host")
	}
}

func TestShadowsocks(t *testing.T) {
	cases := map[string]string{
		// SIP002 with base64 userinfo: "2022-blake3-aes-128-gcm:a2V5"
		"sip002": "ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206YTJWNQ@ss.example.com:8388#SS%20one",
		"plain":  "ss://2022-blake3-aes-128-gcm:a2V5@ss.example.com:8388#SS%20one",
		"legacy": "ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206YTJWNUBzcy5leGFtcGxlLmNvbTo4Mzg4#SS%20one",
	}
	for name, link := range cases {
		t.Run(name, func(t *testing.T) {
			p := buildXray(t, link)
			srv := path(t, decode(t, p.XrayConfig), "outbounds", 0, "settings", "servers", 0)
			if path(t, srv, "method") != "2022-blake3-aes-128-gcm" || path(t, srv, "password") != "a2V5" {
				t.Fatalf("server = %v", srv)
			}
			if path(t, srv, "port").(float64) != 8388 || p.Name != "SS one" {
				t.Fatalf("port/name: %v %q", path(t, srv, "port"), p.Name)
			}
		})
	}
}

func TestHysteria2(t *testing.T) {
	p, err := BuildProfile("hysteria2://pass@hy.example.com:443?sni=hy.example.com&obfs=salamander&obfs-password=xyz&mport=20000-30000#hy")
	if err != nil {
		t.Fatal(err)
	}
	if p.UsesXray || p.XrayConfig != "" {
		t.Fatal("hysteria2 must not use Xray")
	}
	out := path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
	if path(t, out, "type") != "hysteria2" || path(t, out, "password") != "pass" {
		t.Fatalf("outbound = %v", out)
	}
	if path(t, out, "server_ports", 0) != "20000:30000" {
		t.Fatalf("server_ports = %v", path(t, out, "server_ports"))
	}
	if path(t, out, "obfs", "type") != "salamander" || path(t, out, "tls", "server_name") != "hy.example.com" {
		t.Fatal("obfs/tls")
	}
}

func TestHysteria2PortRangeInHost(t *testing.T) {
	p, err := DescribeLink("hy2://pass@hy.example.com:20000-30000")
	if err != nil {
		t.Fatal(err)
	}
	if p.Address != "hy.example.com:20000-30000" {
		t.Fatalf("address = %q", p.Address)
	}
}

func TestRejectsUnknown(t *testing.T) {
	for _, link := range []string{"", "hello", "vmess://abc", "vless://@host:443", "vless://id@host"} {
		if _, err := BuildProfile(link); err == nil {
			t.Errorf("expected error for %q", link)
		}
	}
}

func TestDetails(t *testing.T) {
	for link, want := range map[string]string{
		"vless://id@h:443?type=grpc&security=reality&pbk=K&sni=a.com#x": "VLESS · gRPC · REALITY",
		"vless://id@h:443?type=xhttp&security=tls#x":                    "VLESS · XHTTP · TLS",
		"trojan://pw@h:443#x":    "Trojan · TCP · TLS",
		"hysteria2://pw@h:443#x": "Hysteria2",
		"ss://YWVzLTI1Ni1nY206cHc@h:1?plugin=shadow-tls%3Bhost%3Dc.com%3Bpasswd%3Dp#x": "Shadowsocks · ShadowTLS",
	} {
		p, err := DescribeLink(link)
		if err != nil {
			t.Fatal(err)
		}
		if p.Details != want {
			t.Errorf("%s: %q, want %q", link, p.Details, want)
		}
	}
}

func TestNativeSingBox(t *testing.T) {
	vision := "vless://11111111-2222-3333-4444-555555555555@nl1.example.com:443?type=tcp&security=tls" +
		"&sni=nl1.example.com&fp=chrome&flow=xtls-rprx-vision#nl"
	p, err := BuildProfile(vision)
	if err != nil {
		t.Fatal(err)
	}
	if p.UsesXray || p.XrayConfig != "" {
		t.Fatal("VLESS TLS Vision must run in sing-box alone")
	}
	out := path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
	if path(t, out, "type") != "vless" || path(t, out, "flow") != "xtls-rprx-vision" {
		t.Fatalf("outbound = %v", out)
	}
	if path(t, out, "tls", "server_name") != "nl1.example.com" || path(t, out, "tls", "utls", "fingerprint") != "chrome" {
		t.Fatalf("tls = %v", path(t, out, "tls"))
	}

	ws, err := BuildProfile("vmess://" + vmessJSON(`{"v":"2","ps":"w","add":"w.example.com","port":"443","id":"u","net":"ws","path":"/p","host":"h.example.com","tls":"tls","scy":"auto"}`))
	if err != nil {
		t.Fatal(err)
	}
	transport := path(t, decode(t, ws.SingBoxConfig), "outbounds", 0, "transport")
	if ws.UsesXray || path(t, transport, "type") != "ws" || path(t, transport, "headers", "Host") != "h.example.com" {
		t.Fatalf("vmess ws: xray=%v transport=%v", ws.UsesXray, transport)
	}

	ss, err := BuildProfile("ss://2022-blake3-aes-128-gcm:a2V5@ss.example.com:8388#ss")
	if err != nil {
		t.Fatal(err)
	}
	if ss.UsesXray || path(t, decode(t, ss.SingBoxConfig), "outbounds", 0, "method") != "2022-blake3-aes-128-gcm" {
		t.Fatal("plain Shadowsocks must run in sing-box")
	}

	// What only Xray can do stays in Xray.
	for _, link := range []string{
		"vless://u@h:443?type=xhttp&security=tls#x",
		"vless://u@h:443?type=grpc&security=tls&serviceName=s&mode=multi#g",
		"vless://u@h:443?type=tcp&security=none&encryption=mlkem768x25519plus.native.0rtt.abc#e",
		"vless://u@h:443?type=ws&security=tls&path=%2Fws%3Fed%3D2048#ed",
		"vmess://" + vmessJSON(`{"v":"2","ps":"k","add":"k.example.com","port":"443","id":"u","net":"tcp","type":"http","host":"a.com"}`),
		"vless://u@h:443?type=tcp&security=reality&pbk=PUBKEY&sid=ab12&sni=www.microsoft.com&flow=xtls-rprx-vision#r",
	} {
		p, err := BuildProfile(link)
		if err != nil {
			t.Fatalf("%s: %v", link, err)
		}
		if !p.UsesXray {
			t.Errorf("%s: must use Xray", link)
		}
	}
}

func vmessJSON(text string) string { return base64.StdEncoding.EncodeToString([]byte(text)) }
