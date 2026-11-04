package iykyk

import (
	"encoding/base64"
	"strings"
	"testing"
)

func key32(seed byte) string {
	b := make([]byte, 32)
	for i := range b {
		b[i] = seed + byte(i)
	}
	return base64.StdEncoding.EncodeToString(b)
}

func amneziaConf() string {
	return "[Interface]\nPrivateKey = " + key32(1) + "\nAddress = 10.8.0.2/32\nMTU = 1280\n" +
		"Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\nS2 = 68\nH1 = 1234\nH2 = 5678\nH3 = 9012\nH4 = 3456\n" +
		"I1 = <b 0xc6000000010843290a47ba8ba2ed000044d0e3efd9326adb60561baa3bc4b52471b2d459ddcc9a508dffddc97e4d40d811>\n\n" +
		"[Peer]\nPublicKey = " + key32(50) + "\nPresharedKey = " + key32(100) +
		"\nAllowedIPs = 0.0.0.0/0\nEndpoint = 1.2.3.4:51820\nPersistentKeepalive = 25\n"
}

func TestAmneziaUAPI(t *testing.T) {
	s, err := parseLink(amneziaConf())
	if err != nil {
		t.Fatal(err)
	}
	uapi, err := amneziaUAPI(s, "1.2.3.4:51820")
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(uapi), "\n")
	index := func(prefix string) int {
		for i, line := range lines {
			if strings.HasPrefix(line, prefix) {
				return i
			}
		}
		t.Fatalf("missing %q in\n%s", prefix, uapi)
		return -1
	}
	// Interface keys (incl. AmneziaWG obfuscation) must come before the peer.
	if !(index("private_key=") < index("jc=4") && index("jc=4") < index("public_key=")) {
		t.Fatalf("wrong order:\n%s", uapi)
	}
	if !strings.HasPrefix(lines[index("i1=")], "i1=<b 0xc6") || index("h4=3456") > index("public_key=") {
		t.Fatalf("obfuscation params:\n%s", uapi)
	}
	// Keys are converted from base64 to hex.
	if lines[0] != "private_key=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20" {
		t.Fatalf("private key line: %s", lines[0])
	}
	index("preshared_key=")
	index("endpoint=1.2.3.4:51820")
	index("allowed_ip=::/0")
}

func TestSidecarProfiles(t *testing.T) {
	for _, link := range []string{
		amneziaConf(),
		"mierus://user:pass@m.example.com?profile=default&port=8964&protocol=TCP#Mieru",
	} {
		p, err := BuildProfile(link)
		if err != nil {
			t.Fatal(err)
		}
		if p.UsesXray || p.sidecar == "" || p.sidecarPort == 0 {
			t.Fatalf("expected a sidecar profile: %+v", p)
		}
		proxy := path(t, decode(t, p.SingBoxConfig), "outbounds", 0)
		if path(t, proxy, "type") != "socks" || int(path(t, proxy, "server_port").(float64)) != p.sidecarPort {
			t.Fatalf("proxy %v", proxy)
		}
		if path(t, proxy, "username") != p.sidecarUser {
			t.Fatal("credentials differ")
		}
	}
}
