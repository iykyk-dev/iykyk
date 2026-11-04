package iykyk

import (
	"encoding/base64"
	"testing"
)

func vmessLink(json string) string {
	return "vmess://" + base64.StdEncoding.EncodeToString([]byte(json))
}

func TestVMess(t *testing.T) {
	link := vmessLink(`{"v":"2","ps":"VM ws","add":"vm.example.com","port":"443","id":"11111111-2222-3333-4444-555555555555",` +
		`"aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ray","tls":"tls","sni":"cdn.example.com"}`)
	p := buildXray(t, link)
	if !p.UsesXray || p.Name != "VM ws" {
		t.Fatalf("profile %+v", p)
	}
	out := path(t, decode(t, p.XrayConfig), "outbounds", 0)
	if path(t, out, "protocol") != "vmess" || path(t, out, "settings", "vnext", 0, "users", 0, "security") != "auto" {
		t.Fatalf("outbound %v", out)
	}
	if path(t, out, "streamSettings", "wsSettings", "path") != "/ray" || path(t, out, "streamSettings", "security") != "tls" {
		t.Fatal("stream")
	}
}

func TestVMessTCPHTTPHeaderAndNumericPort(t *testing.T) {
	link := vmessLink(`{"ps":"tcp","add":"1.2.3.4","port":8080,"id":"id","net":"tcp","type":"http","host":"a.com","path":"/x"}`)
	p, err := BuildProfile(link)
	if err != nil {
		t.Fatal(err)
	}
	stream := path(t, decode(t, p.XrayConfig), "outbounds", 0, "streamSettings")
	if path(t, stream, "rawSettings", "header", "type") != "http" || path(t, stream, "rawSettings", "header", "request", "headers", "Host", 0) != "a.com" {
		t.Fatalf("stream %v", stream)
	}
}

func TestTUIC(t *testing.T) {
	p, err := BuildProfile("tuic://uuid-1:pw@tuic.example.com:8443?congestion_control=bbr&udp_relay_mode=native&sni=tuic.example.com#TUIC")
	if err != nil {
		t.Fatal(err)
	}
	out := path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
	if path(t, out, "type") != "tuic" || path(t, out, "uuid") != "uuid-1" || path(t, out, "password") != "pw" {
		t.Fatalf("outbound %v", out)
	}
	if path(t, out, "congestion_control") != "bbr" || path(t, out, "tls", "alpn", 0) != "h3" {
		t.Fatalf("outbound %v", out)
	}
}

func TestAnyTLS(t *testing.T) {
	p, err := BuildProfile("anytls://secret@any.example.com:443?sni=any.example.com#Any")
	if err != nil {
		t.Fatal(err)
	}
	out := path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
	if path(t, out, "type") != "anytls" || path(t, out, "tls", "utls", "fingerprint") != "chrome" {
		t.Fatalf("outbound %v", out)
	}
}

func TestNaive(t *testing.T) {
	p, err := BuildProfile("naive+https://user:pass@naive.example.com:443#Naive")
	if err != nil {
		t.Fatal(err)
	}
	out := path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
	if path(t, out, "type") != "naive" || path(t, out, "username") != "user" || path(t, out, "tls", "server_name") != "naive.example.com" {
		t.Fatalf("outbound %v", out)
	}
	p, err = BuildProfile("naive+quic://user:pass@naive.example.com#q")
	if err != nil {
		t.Fatal(err)
	}
	if path(t, decode(t, p.SingBoxConfig), "outbounds", 0, "quic") != true {
		t.Fatal("quic not set")
	}
}

func TestShadowTLS(t *testing.T) {
	plugin := "ss://2022-blake3-aes-128-gcm:a2V5@stls.example.com:443?plugin=shadow-tls%3Bhost%3Dcloud.example.com%3Bpasswd%3Dstlspw%3Bv3%3D1#stls"
	stlsJSON := base64.StdEncoding.EncodeToString([]byte(`{"version":"3","host":"cloud.example.com","password":"stlspw"}`))
	rocket := "ss://2022-blake3-aes-128-gcm:a2V5@stls.example.com:443?shadow-tls=" + stlsJSON + "#stls"
	for _, link := range []string{plugin, rocket} {
		p, err := BuildProfile(link)
		if err != nil {
			t.Fatal(err)
		}
		if p.UsesXray {
			t.Fatal("ShadowTLS must run in sing-box")
		}
		sb := decode(t, p.SingBoxConfig)
		if path(t, sb, "outbounds", 0, "detour") != "shadowtls" {
			t.Fatal("shadowsocks not chained")
		}
		stls := path(t, sb, "outbounds", 1)
		if path(t, stls, "version").(float64) != 3 || path(t, stls, "password") != "stlspw" || path(t, stls, "tls", "server_name") != "cloud.example.com" {
			t.Fatalf("shadowtls %v", stls)
		}
	}
	if _, err := BuildProfile("ss://YWVzLTI1Ni1nY206cHc@h:1?plugin=v2ray-plugin%3Bmode%3Dwebsocket"); err == nil {
		t.Fatal("other plugins must be rejected")
	}
}

func TestSocksAndHTTP(t *testing.T) {
	p, err := BuildProfile("socks://" + base64.RawURLEncoding.EncodeToString([]byte("u:p")) + "@s.example.com:1080#S")
	if err != nil {
		t.Fatal(err)
	}
	out := path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
	if path(t, out, "type") != "socks" || path(t, out, "username") != "u" || path(t, out, "password") != "p" {
		t.Fatalf("socks %v", out)
	}
	p, err = BuildProfile("https://u:p@h.example.com:443#H")
	if err != nil {
		t.Fatal(err)
	}
	out = path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
	if path(t, out, "type") != "http" || path(t, out, "tls", "enabled") != true {
		t.Fatalf("http %v", out)
	}
}

const wgConf = `[Interface]
PrivateKey = cPrivKeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Address = 10.8.0.2/32, fd00::2/128
DNS = 1.1.1.1
MTU = 1280

[Peer]
PublicKey = pPubKeyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
PresharedKey = psk
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = wg.example.com:51820
PersistentKeepalive = 25
`

func TestWireGuardConf(t *testing.T) {
	p, err := BuildProfile(wgConf)
	if err != nil {
		t.Fatal(err)
	}
	sb := decode(t, p.SingBoxConfig)
	ep := path(t, sb, "endpoints", 0)
	if path(t, ep, "type") != "wireguard" || path(t, ep, "tag") != "proxy" || path(t, ep, "address", 1) != "fd00::2/128" {
		t.Fatalf("endpoint %v", ep)
	}
	if path(t, ep, "peers", 0, "address") != "wg.example.com" || path(t, ep, "peers", 0, "pre_shared_key") != "psk" {
		t.Fatalf("peer %v", path(t, ep, "peers", 0))
	}
	if outs := path(t, sb, "outbounds").([]any); len(outs) != 1 {
		t.Fatalf("only the direct outbound expected, got %v", outs)
	}
}

func TestWireGuardLink(t *testing.T) {
	p, err := BuildProfile("wireguard://cPriv%3D@1.2.3.4:51820?publickey=pPub%3D&address=10.0.0.2&reserved=1,2,3&mtu=1280#WG")
	if err != nil {
		t.Fatal(err)
	}
	ep := path(t, decode(t, p.SingBoxConfig), "endpoints", 0)
	if path(t, ep, "private_key") != "cPriv=" || path(t, ep, "address", 0) != "10.0.0.2/32" || path(t, ep, "peers", 0, "reserved", 2).(float64) != 3 {
		t.Fatalf("endpoint %v", ep)
	}
}

func TestAmneziaDetectedAndMieruDescribed(t *testing.T) {
	awg := wgConf[:len("[Interface]\n")] + "Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\nH1 = 1234\n" + wgConf[len("[Interface]\n"):]
	p, err := DescribeLink(awg)
	if err != nil {
		t.Fatal(err)
	}
	if p.Protocol != "amneziawg" {
		t.Fatalf("protocol = %q", p.Protocol)
	}
	m, err := DescribeLink("mierus://user:pass@m.example.com?profile=default&port=8964&protocol=TCP#Mieru")
	if err != nil {
		t.Fatal(err)
	}
	if m.Protocol != "mieru" || m.Name != "Mieru" || m.Address != "m.example.com:8964" {
		t.Fatalf("mieru %+v", m)
	}
}
