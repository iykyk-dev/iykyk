//go:build engine

// Feeds generated configs to the real engines. Runs in CI (`go test -tags engine,...`)
// because it needs the full sing-box and Xray dependency tree.
package iykyk

import (
	"encoding/base64"
	"testing"

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
