//go:build engine

// Feeds generated configs to the real engines. Runs in CI (`go test -tags engine,...`)
// because it needs the full sing-box and Xray dependency tree.
package iykyk

import (
	"testing"

	"github.com/sagernet/sing-box/experimental/libbox"
)

func TestConfigsAcceptedByEngines(t *testing.T) {
	links := []string{
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
