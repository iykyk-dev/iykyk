package iykyk

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// readShadowTLS handles Shadowsocks links that run over ShadowTLS, in both
// common forms:
//
//	SIP003 plugin:  ?plugin=shadow-tls;host=cloud.example.com;passwd=secret;v3=1
//	Shadowrocket:   ?shadow-tls=base64({"version":"3","host":"...","password":"..."})
//
// Any other plugin is rejected.
func readShadowTLS(q url.Values, s *server) error {
	if encoded := q.Get("shadow-tls"); encoded != "" {
		decoded, err := decodeBase64(encoded)
		if err != nil {
			return errors.New("shadowsocks: invalid shadow-tls parameter")
		}
		var opts struct {
			Version  json.RawMessage `json:"version"`
			Host     string          `json:"host"`
			Password string          `json:"password"`
		}
		if err := json.Unmarshal([]byte(decoded), &opts); err != nil {
			return errors.New("shadowsocks: invalid shadow-tls parameter")
		}
		version, _ := strconv.Atoi(strings.Trim(string(opts.Version), `"`))
		s.ShadowTLSVersion, s.ShadowTLSHost, s.ShadowTLSPassword = version, opts.Host, opts.Password
	} else if plugin := q.Get("plugin"); plugin != "" {
		name, rest, _ := strings.Cut(plugin, ";")
		if name != "shadow-tls" {
			return fmt.Errorf("shadowsocks: plugin %q is not supported", name)
		}
		s.ShadowTLSVersion = 2
		for _, option := range strings.Split(rest, ";") {
			key, value, _ := strings.Cut(option, "=")
			switch key {
			case "host":
				s.ShadowTLSHost = value
			case "passwd", "password":
				s.ShadowTLSPassword = value
			case "v3":
				if isTrue(value) {
					s.ShadowTLSVersion = 3
				}
			case "version":
				if v, err := strconv.Atoi(value); err == nil {
					s.ShadowTLSVersion = v
				}
			}
		}
	} else {
		return nil
	}
	if s.ShadowTLSVersion == 0 {
		s.ShadowTLSVersion = 3
	}
	if s.ShadowTLSHost == "" || (s.ShadowTLSVersion > 1 && s.ShadowTLSPassword == "") {
		return errors.New("shadowsocks: shadow-tls needs host and password")
	}
	return nil
}

// parseVMess reads the v2rayN format: vmess://base64(JSON).
func parseVMess(link string) (*server, error) {
	body := link[len("vmess://"):]
	body, fragment, _ := strings.Cut(body, "#")
	decoded, err := decodeBase64(body)
	if err != nil {
		return nil, errors.New("vmess: expected base64 JSON (v2rayN format)")
	}
	var v struct {
		PS          string          `json:"ps"`
		Add         string          `json:"add"`
		Port        json.RawMessage `json:"port"`
		ID          string          `json:"id"`
		Scy         string          `json:"scy"`
		Net         string          `json:"net"`
		Type        string          `json:"type"`
		Host        string          `json:"host"`
		Path        string          `json:"path"`
		TLS         string          `json:"tls"`
		SNI         string          `json:"sni"`
		ALPN        string          `json:"alpn"`
		FP          string          `json:"fp"`
		Insecure    json.RawMessage `json:"allowInsecure"`
		Mode        string          `json:"mode"`
		ServiceName string          `json:"serviceName"`
	}
	if err := json.Unmarshal([]byte(decoded), &v); err != nil {
		return nil, fmt.Errorf("vmess: invalid JSON: %w", err)
	}
	port, err := strconv.Atoi(strings.Trim(string(v.Port), `"`))
	if err != nil || port <= 0 || port > 65535 {
		return nil, errors.New("vmess: invalid port")
	}
	if v.Add == "" || v.ID == "" {
		return nil, errors.New("vmess: missing address or id")
	}
	s := &server{
		Protocol:    "vmess",
		Host:        v.Add,
		Port:        port,
		UUID:        v.ID,
		Method:      v.Scy,
		Network:     strings.ToLower(v.Net),
		Path:        v.Path,
		HostHeader:  v.Host,
		SNI:         v.SNI,
		ALPN:        splitList(v.ALPN),
		Fingerprint: v.FP,
		Insecure:    isTrue(strings.Trim(string(v.Insecure), `"`)),
	}
	if s.Method == "" {
		s.Method = "auto"
	}
	switch s.Network {
	case "", "tcp", "raw":
		s.Network = "tcp"
		s.HeaderType = v.Type
	case "grpc":
		s.ServiceName = v.ServiceName
		if s.ServiceName == "" {
			s.ServiceName = v.Path
		}
		s.GRPCMode = v.Type
		if v.Mode != "" {
			s.GRPCMode = v.Mode
		}
	case "splithttp":
		s.Network = "xhttp"
	}
	if s.Network == "xhttp" {
		s.XHTTPMode = v.Mode
	}
	s.Security = strings.ToLower(v.TLS)
	if s.Security == "" {
		s.Security = "none"
	}
	switch {
	case v.PS != "":
		s.Name = v.PS
	case fragment != "":
		s.Name, _ = url.PathUnescape(fragment)
	default:
		s.Name = net.JoinHostPort(s.Host, strconv.Itoa(s.Port))
	}
	return s, nil
}

// tuic://uuid:password@host:port?congestion_control=bbr&alpn=h3&sni=...&udp_relay_mode=native
func parseTUIC(link string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "tuic", Security: "tls"}
	if s.Host, s.Port, err = parseHostPort(u); err != nil {
		return nil, err
	}
	s.UUID = u.User.Username()
	s.Password, _ = u.User.Password()
	if s.UUID == "" {
		return nil, errors.New("tuic: missing uuid")
	}
	q := u.Query()
	s.CongestionControl = q.Get("congestion_control")
	s.UDPRelayMode = q.Get("udp_relay_mode")
	s.SNI = q.Get("sni")
	s.ALPN = splitList(q.Get("alpn"))
	s.Insecure = isTrue(q.Get("allow_insecure")) || isTrue(q.Get("insecure")) || isTrue(q.Get("allowInsecure"))
	s.Name = linkName(u, s)
	return s, nil
}

// anytls://password@host:port?sni=...&insecure=1&fp=chrome
func parseAnyTLS(link string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "anytls", Security: "tls"}
	if s.Host, s.Port, err = parseHostPort(u); err != nil {
		return nil, err
	}
	s.Password = u.User.Username()
	if s.Password == "" {
		return nil, errors.New("anytls: missing password")
	}
	q := u.Query()
	s.SNI = q.Get("sni")
	s.ALPN = splitList(q.Get("alpn"))
	s.Fingerprint = q.Get("fp")
	s.Insecure = isTrue(q.Get("insecure")) || isTrue(q.Get("allowInsecure"))
	s.Name = linkName(u, s)
	return s, nil
}

// naive+https://user:pass@host:port#name, naive+quic://... for HTTP/3.
func parseNaive(link string) (*server, error) {
	scheme, rest, _ := strings.Cut(link, "://")
	u, err := url.Parse("https://" + rest)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "naive", Security: "tls", QUIC: strings.EqualFold(scheme, "naive+quic")}
	s.Host = u.Hostname()
	if s.Host == "" {
		return nil, errors.New("naive: missing server address")
	}
	s.Port = 443
	if portText := u.Port(); portText != "" {
		if s.Port, err = strconv.Atoi(portText); err != nil {
			return nil, errors.New("naive: invalid port")
		}
	}
	s.Username = u.User.Username()
	s.Password, _ = u.User.Password()
	s.SNI = u.Query().Get("sni")
	s.Name = linkName(u, s)
	return s, nil
}

// socks://base64(user:pass)@host:port, socks5://user:pass@host:port,
// http(s)://user:pass@host:port (HTTP proxy; https means TLS to the proxy).
func parseProxyLink(link, protocol string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: protocol, Security: "none"}
	if s.Host, s.Port, err = parseHostPort(u); err != nil {
		return nil, err
	}
	if strings.EqualFold(u.Scheme, "https") {
		s.Security = "tls"
		s.SNI = u.Query().Get("sni")
	}
	if u.User != nil {
		s.Username = u.User.Username()
		password, hasPassword := u.User.Password()
		if !hasPassword && protocol == "socks" && s.Username != "" {
			if decoded, err := decodeBase64(s.Username); err == nil {
				s.Username, password, _ = strings.Cut(decoded, ":")
			}
		}
		s.Password = password
	}
	s.Name = linkName(u, s)
	return s, nil
}

// wireguard://privateKey@host:port?publickey=...&address=10.0.0.2/32&reserved=1,2,3&mtu=1280
func parseWireGuardLink(link string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "wireguard"}
	if s.Host, s.Port, err = parseHostPort(u); err != nil {
		return nil, err
	}
	s.PrivateKey = u.User.Username()
	q := u.Query()
	s.PeerPublicKey = firstNonEmpty(q.Get("publickey"), q.Get("public_key"), q.Get("peer"))
	s.PresharedKey = firstNonEmpty(q.Get("presharedkey"), q.Get("pre_shared_key"))
	s.Addresses = splitList(firstNonEmpty(q.Get("address"), q.Get("ip")))
	s.MTU, _ = strconv.Atoi(q.Get("mtu"))
	s.Keepalive, _ = strconv.Atoi(q.Get("keepalive"))
	for _, part := range splitList(q.Get("reserved")) {
		if n, err := strconv.Atoi(part); err == nil {
			s.Reserved = append(s.Reserved, n)
		}
	}
	if err := s.checkWireGuard(); err != nil {
		return nil, err
	}
	s.Name = linkName(u, s)
	return s, nil
}

var amneziaKeys = []string{
	"jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
}

// parseWireGuardConf reads a WireGuard or AmneziaWG .conf file pasted as text.
// AmneziaWG is detected by its obfuscation keys (Jc, Jmin, S1, H1, I1, ...).
func parseWireGuardConf(text string) (*server, error) {
	s := &server{Protocol: "wireguard", AmneziaParams: map[string]string{}}
	section := ""
	for _, rawLine := range strings.Split(text, "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.ToLower(strings.TrimSpace(key))
		value = strings.TrimSpace(value)
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				s.PrivateKey = value
			case "address":
				s.Addresses = append(s.Addresses, splitList(value)...)
			case "mtu":
				s.MTU, _ = strconv.Atoi(value)
			default:
				for _, k := range amneziaKeys {
					if key == k {
						s.AmneziaParams[k] = value
					}
				}
			}
		case "peer":
			switch key {
			case "publickey":
				s.PeerPublicKey = value
			case "presharedkey":
				s.PresharedKey = value
			case "allowedips":
				s.AllowedIPs = append(s.AllowedIPs, splitList(value)...)
			case "persistentkeepalive":
				s.Keepalive, _ = strconv.Atoi(value)
			case "endpoint":
				host, portText, err := net.SplitHostPort(value)
				if err != nil {
					return nil, fmt.Errorf("wireguard: invalid endpoint %q", value)
				}
				port, err := strconv.Atoi(portText)
				if err != nil {
					return nil, fmt.Errorf("wireguard: invalid endpoint port %q", portText)
				}
				s.Host, s.Port = host, port
			}
		}
	}
	if s.Host == "" {
		return nil, errors.New("wireguard: missing [Peer] Endpoint")
	}
	if err := s.checkWireGuard(); err != nil {
		return nil, err
	}
	if len(s.AmneziaParams) > 0 {
		s.Protocol = "amneziawg"
	} else {
		s.AmneziaParams = nil
	}
	s.Name = net.JoinHostPort(s.Host, strconv.Itoa(s.Port))
	return s, nil
}

func (s *server) checkWireGuard() error {
	switch {
	case s.PrivateKey == "":
		return errors.New("wireguard: missing private key")
	case s.PeerPublicKey == "":
		return errors.New("wireguard: missing peer public key")
	case len(s.Addresses) == 0:
		return errors.New("wireguard: missing interface address")
	}
	for i, address := range s.Addresses {
		// Bare IPs (as in some configs) become single-host prefixes.
		if !strings.Contains(address, "/") {
			if ip := net.ParseIP(address); ip != nil && ip.To4() != nil {
				s.Addresses[i] = address + "/32"
			} else {
				s.Addresses[i] = address + "/128"
			}
		}
	}
	return nil
}

// mierus://user:pass@host?profile=default&port=8964&protocol=TCP#name
// The full link is passed to the Mieru engine, which parses it itself.
func parseMieru(link string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "mieru", Raw: link}
	s.Host = u.Hostname()
	if s.Host == "" || u.User == nil || u.User.Username() == "" {
		return nil, errors.New("mieru: missing server or user")
	}
	ports := u.Query()["port"]
	if len(ports) == 0 {
		return nil, errors.New("mieru: missing port")
	}
	if port, err := strconv.Atoi(ports[0]); err == nil {
		s.Port = port
	} else {
		s.PortHopping = ports[0]
	}
	switch {
	case u.Fragment != "":
		s.Name = u.Fragment
	case u.Query().Get("profile") != "":
		s.Name = u.Query().Get("profile")
	default:
		s.Name = s.Host
	}
	return s, nil
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
