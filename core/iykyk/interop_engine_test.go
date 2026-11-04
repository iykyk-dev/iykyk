//go:build engine

package iykyk

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/xtls/xray-core/core"
)

// TestNativeClientsAgainstXrayServers is the safety net for running VLESS,
// VMess, Trojan and Shadowsocks in sing-box instead of Xray: it starts real
// Xray servers (as on the provider's nodes) and does a real HTTPS request
// through each of them with the native sing-box client.
func TestNativeClientsAgainstXrayServers(t *testing.T) {
	const uuid = "11111111-2222-3333-4444-555555555555"
	certPEM, keyPEM := selfSignedCert(t, "example.com")
	tlsSettings := `"tlsSettings":{"certificates":[{"certificate":` + pemLines(certPEM) + `,"key":` + pemLines(keyPEM) + `}]}`

	private, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	privKey := base64.RawURLEncoding.EncodeToString(private.Bytes())
	pubKey := base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes())
	ssKey := base64.StdEncoding.EncodeToString([]byte("0123456789abcdef"))

	cases := []struct {
		name    string
		inbound string // Xray inbound without listen/port
		link    string // client link; PORT is replaced
	}{
		{
			"VLESS REALITY Vision",
			`"protocol":"vless","settings":{"clients":[{"id":"` + uuid + `","flow":"xtls-rprx-vision"}],"decryption":"none"},` +
				`"streamSettings":{"network":"raw","security":"reality","realitySettings":{"target":"www.google.com:443",` +
				`"serverNames":["www.google.com"],"privateKey":"` + privKey + `","shortIds":["ab12"]}}`,
			"vless://" + uuid + "@127.0.0.1:PORT?type=tcp&security=reality&pbk=" + pubKey +
				"&sid=ab12&sni=www.google.com&fp=chrome&flow=xtls-rprx-vision#r",
		},
		{
			"VLESS gRPC TLS",
			`"protocol":"vless","settings":{"clients":[{"id":"` + uuid + `"}],"decryption":"none"},` +
				`"streamSettings":{"network":"grpc","grpcSettings":{"serviceName":"svc"},"security":"tls",` + tlsSettings + `}`,
			"vless://" + uuid + "@127.0.0.1:PORT?type=grpc&serviceName=svc&security=tls&sni=example.com&allowInsecure=1#g",
		},
		{
			"Trojan WS TLS",
			`"protocol":"trojan","settings":{"clients":[{"password":"pw"}]},` +
				`"streamSettings":{"network":"ws","wsSettings":{"path":"/tr"},"security":"tls",` + tlsSettings + `}`,
			"trojan://pw@127.0.0.1:PORT?type=ws&path=%2Ftr&security=tls&sni=example.com&allowInsecure=1#t",
		},
		{
			"VMess WS",
			`"protocol":"vmess","settings":{"clients":[{"id":"` + uuid + `"}]},` +
				`"streamSettings":{"network":"ws","wsSettings":{"path":"/vm"}}`,
			"vmess://" + base64.StdEncoding.EncodeToString([]byte(
				`{"v":"2","ps":"v","add":"127.0.0.1","port":"PORT","id":"`+uuid+`","net":"ws","path":"/vm","tls":"","scy":"auto"}`)),
		},
		{
			"Shadowsocks 2022",
			`"protocol":"shadowsocks","settings":{"method":"2022-blake3-aes-128-gcm","password":"` + ssKey + `","network":"tcp,udp"}`,
			"ss://2022-blake3-aes-128-gcm:" + ssKey + "@127.0.0.1:PORT#s",
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			port, err := freeLoopbackPort()
			if err != nil {
				t.Fatal(err)
			}
			p := strconv.Itoa(port)
			config := `{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":` + p + `,` + c.inbound +
				`}],"outbounds":[{"protocol":"freedom"}]}`
			server, err := core.StartInstance("json", []byte(config))
			if err != nil {
				t.Fatalf("xray server: %v", err)
			}
			defer server.Close()

			link := c.link
			if strings.HasPrefix(link, "vmess://") {
				raw, _ := base64.StdEncoding.DecodeString(strings.TrimPrefix(link, "vmess://"))
				link = "vmess://" + base64.StdEncoding.EncodeToString([]byte(strings.Replace(string(raw), "PORT", p, 1)))
			} else {
				link = strings.Replace(link, "PORT", p, 1)
			}
			s, err := parseLink(link)
			if err != nil {
				t.Fatal(err)
			}
			if selectEngine(s, options{}) != engineSingBox {
				t.Fatal("expected the native sing-box client")
			}
			b, _ := json.Marshal([]string{link})
			text, err := RealPingServers(string(b), "")
			if err != nil {
				t.Fatal(err)
			}
			var results []int
			_ = json.Unmarshal([]byte(text), &results)
			if len(results) != 1 || results[0] <= 0 {
				t.Fatalf("no traffic through the native client: %v", results)
			}
			t.Logf("%s: %d ms", c.name, results[0])
		})
	}
}

func selfSignedCert(t *testing.T, name string) (certPEM, keyPEM string) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: name},
		DNSNames:     []string{name},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}))
}

// pemLines formats PEM text as Xray's JSON array of lines.
func pemLines(text string) string {
	b, _ := json.Marshal(strings.Split(strings.TrimSpace(text), "\n"))
	return string(b)
}
