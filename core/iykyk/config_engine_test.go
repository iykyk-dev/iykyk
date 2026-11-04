//go:build engine

// Feeds generated configs to the real engines. Runs in CI (`go test -tags engine,...`)
// because it needs the full sing-box and Xray dependency tree.
package iykyk

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net"
	"os"
	"strconv"
	"testing"
	"time"

	"github.com/sagernet/sing-box/experimental/libbox"
)

func testKey(seed byte) string {
	key := make([]byte, 32)
	for i := range key {
		key[i] = seed + byte(i)
	}
	return base64.StdEncoding.EncodeToString(key)
}

func TestConfigsAcceptedByEngines(t *testing.T) {
	wgConfig := "[Interface]\nPrivateKey = " + testKey(1) + "\nAddress = 10.8.0.2/32\n\n" +
		"[Peer]\nPublicKey = " + testKey(50) + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = wg.example.com:51820\n"
	vmess := "vmess://" + base64.StdEncoding.EncodeToString([]byte(
		`{"ps":"vm","add":"vm.example.com","port":"443","id":"11111111-2222-3333-4444-555555555555","net":"grpc","path":"svc","tls":"tls"}`))
	links := []string{
		vmess,
		"tuic://11111111-2222-3333-4444-555555555555:pw@tuic.example.com:8443?congestion_control=bbr&udp_relay_mode=native#tuic",
		"anytls://secret@any.example.com:443?sni=any.example.com#any",
		"ss://2022-blake3-aes-128-gcm:AAAAAAAAAAAAAAAAAAAAAA==@stls.example.com:443?plugin=shadow-tls%3Bhost%3Dcloud.example.com%3Bpasswd%3Dpw%3Bv3%3D1#stls",
		"socks5://u:p@s.example.com:1080#socks",
		"https://u:p@h.example.com:443#http",
		wgConfig,
		"vless://11111111-2222-3333-4444-555555555555@nl1.example.com:443?type=tcp&security=reality&pbk=" +
			"Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=ab12&sni=www.microsoft.com&fp=chrome&flow=xtls-rprx-vision#nl",
		"vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?type=xhttp&security=tls&sni=cdn.example.com&path=%2Fapi&mode=auto#x",
		"vless://11111111-2222-3333-4444-555555555555@ws.example.com:443?type=ws&security=tls&path=%2Fws&host=ws.example.com#ws",
		"vless://11111111-2222-3333-4444-555555555555@grpc.example.com:443?type=grpc&security=tls&serviceName=svc&mode=multi#grpc",
		"trojan://secret@example.org:443?type=ws&path=%2Fws#tr",
		"ss://2022-blake3-aes-128-gcm:AAAAAAAAAAAAAAAAAAAAAA==@ss.example.com:8388#ss",
		"hysteria2://pass@hy.example.com:443?sni=hy.example.com&obfs=salamander&obfs-password=xyz&mport=20000-30000#hy",
	}
	for _, link := range links {
		p, err := BuildProfile(link)
		if err != nil {
			t.Fatalf("%s: build: %v", link, err)
		}
		if err := libbox.CheckConfig(p.SingBoxConfig); err != nil {
			t.Errorf("%s: sing-box rejected config: %v\n%s", p.Name, err, p.SingBoxConfig)
		}
		if p.UsesXray {
			if err := CheckXrayConfig(p.XrayConfig); err != nil {
				t.Errorf("%s: Xray rejected config: %v\n%s", p.Name, err, p.XrayConfig)
			}
		}
	}
}

// TestSidecarEnginesStart starts the in-process engines for real. There is no
// server to talk to, so this checks wiring: the engine starts, its loopback
// SOCKS5 bridge listens, and everything stops cleanly.
func TestSidecarEnginesStart(t *testing.T) {
	for _, link := range []string{
		amneziaConf(),
		"mierus://user:pass@127.0.0.1?profile=default&port=8964&protocol=TCP#Mieru",
	} {
		p, err := BuildProfile(link)
		if err != nil {
			t.Fatal(err)
		}
		if err := StartEngines(p); err != nil {
			t.Fatalf("%s: start: %v", p.Protocol, err)
		}
		conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(p.sidecarPort)), 2*time.Second)
		if err != nil {
			t.Errorf("%s: SOCKS bridge not listening: %v", p.Protocol, err)
		} else {
			conn.Close()
		}
		if err := StopEngines(); err != nil {
			t.Errorf("%s: stop: %v", p.Protocol, err)
		}
	}
}

// TestBypassAcceptedBySingBox checks the Russia bypass with the real rule-set
// files, which CI downloads into IYKYK_RULESET_DIR.
func TestBypassAcceptedBySingBox(t *testing.T) {
	dir := os.Getenv("IYKYK_RULESET_DIR")
	if dir == "" {
		t.Skip("IYKYK_RULESET_DIR not set")
	}
	for _, link := range []string{
		"trojan://pw@de.example.com:443#DE",
		"hysteria2://pass@fi.example.com:443#FI",
	} {
		p, err := BuildProfileWithOptions(link, `{"bypassRussia":true,"ruleSetDir":"`+dir+`"}`)
		if err != nil {
			t.Fatal(err)
		}
		if err := libbox.CheckConfig(p.SingBoxConfig); err != nil {
			t.Fatalf("sing-box rejected bypass config: %v\n%s", err, p.SingBoxConfig)
		}
	}
}

// TestFragmentAndXrayJSONAcceptedByEngines checks TLS fragmentation (Xray
// finalmask, sing-box tls.fragment) and Xray JSON configs with balancers.
func TestFragmentAndXrayJSONAcceptedByEngines(t *testing.T) {
	links := []string{
		"vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?type=tcp&security=reality&pbk=" +
			"Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw&sid=ab12&sni=www.microsoft.com&fp=chrome&flow=xtls-rprx-vision#nl",
		"trojan://pw@de.example.com:443?type=ws&path=%2Fws#tr",
		"anytls://secret@any.example.com:443?sni=any.example.com#any",
	}
	sub, err := parseXrayJSONSubscription(xrayJSONSubscription)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range sub {
		links = append(links, s.Raw)
	}
	for _, link := range links {
		p, err := BuildProfileWithOptions(link, `{"fragment":true}`)
		if err != nil {
			t.Fatal(err)
		}
		if err := libbox.CheckConfig(p.SingBoxConfig); err != nil {
			t.Errorf("%s: sing-box rejected config: %v\n%s", p.Name, err, p.SingBoxConfig)
		}
		if p.UsesXray {
			if err := CheckXrayConfig(p.XrayConfig); err != nil {
				t.Errorf("%s: Xray rejected config: %v\n%s", p.Name, err, p.XrayConfig)
			}
		}
	}
}

// TestRealPing runs the real ping through both engines against a local SOCKS5
// proxy that reaches the internet directly (CI runners have internet access).
func TestRealPing(t *testing.T) {
	port, _ := freeLoopbackPort()
	srv, err := listenSocks(port, "u", "p", func(ctx context.Context, network, address string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, address)
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	p := strconv.Itoa(port)
	viaXray := `{"remarks":"x","outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":` + p +
		`,"users":[{"user":"u","pass":"p"}]}]}}]}`
	links := []string{
		"socks5://u:p@127.0.0.1:" + p + "#sing-box", // sing-box engine
		viaXray, // Xray engine (core.Dial)
		"mierus://u:p@127.0.0.1?profile=d&port=1&protocol=TCP#m",
	}
	b, _ := json.Marshal(links)
	text, err := RealPingServers(string(b), "")
	if err != nil {
		t.Fatal(err)
	}
	var results []int
	_ = json.Unmarshal([]byte(text), &results)
	if len(results) != 3 || results[0] <= 0 || results[1] <= 0 || results[2] != -2 {
		t.Fatalf("results %v (want positive, positive, -2)", results)
	}
	t.Logf("real ping: sing-box %d ms, Xray %d ms", results[0], results[1])
}

// TestRoutingAndDNSAcceptedBySingBox checks provider routing and DNS presets.
func TestRoutingAndDNSAcceptedBySingBox(t *testing.T) {
	dir := os.Getenv("IYKYK_RULESET_DIR")
	routing := `"routing":{"block":["domain:ads.example","keyword:tracker"],"proxy":["full:youtube.ru"],` +
		`"direct":["domain:bank.example","regexp:^.+\\.local$","10.0.0.0/8","2001:db8::/32","geoip:ru","geosite:category-ru"]}`
	for _, dns := range []string{
		`"remoteDNS":"cloudflare","directDNS":"yandex"`,
		`"remoteDNS":"adguard","directDNS":"google"`,
		`"remoteDNS":"quad9","directDNS":"1.1.1.1"`,
		`"remoteDNS":"https://dns.example.net/dns-query","directDNS":"cloudflare"`,
	} {
		opts := `{"bypassRussia":true,"ruleSetDir":"` + dir + `",` + dns + `,` + routing + `}`
		p, err := BuildProfileWithOptions("hysteria2://pass@fi.example.com:443#FI", opts)
		if err != nil {
			t.Fatal(err)
		}
		if err := libbox.CheckConfig(p.SingBoxConfig); err != nil {
			t.Errorf("%s: sing-box rejected config: %v\n%s", dns, err, p.SingBoxConfig)
		}
	}
}
