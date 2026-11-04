package iykyk

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

func keyToHex(key string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(key))
	if err != nil || len(raw) != 32 {
		return "", errors.New("amneziawg: invalid key")
	}
	return hex.EncodeToString(raw), nil
}

// amneziaUAPI builds the configuration in the WireGuard UAPI text format,
// extended with AmneziaWG's obfuscation keys (jc, jmin, jmax, s1-s4, h1-h4, i1-i5).
func amneziaUAPI(s *server, endpoint string) (string, error) {
	var b strings.Builder
	privateKey, err := keyToHex(s.PrivateKey)
	if err != nil {
		return "", err
	}
	publicKey, err := keyToHex(s.PeerPublicKey)
	if err != nil {
		return "", err
	}
	fmt.Fprintf(&b, "private_key=%s\n", privateKey)
	for _, key := range amneziaKeys {
		if value, ok := s.AmneziaParams[key]; ok && value != "" {
			fmt.Fprintf(&b, "%s=%s\n", key, value)
		}
	}
	b.WriteString("replace_peers=true\n")
	fmt.Fprintf(&b, "public_key=%s\n", publicKey)
	if s.PresharedKey != "" {
		psk, err := keyToHex(s.PresharedKey)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(&b, "preshared_key=%s\n", psk)
	}
	fmt.Fprintf(&b, "endpoint=%s\n", endpoint)
	keepalive := s.Keepalive
	if keepalive == 0 {
		keepalive = 25
	}
	fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", keepalive)
	b.WriteString("replace_allowed_ips=true\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\n")
	return b.String(), nil
}
