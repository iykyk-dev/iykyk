package iykyk

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
)

// Profile is what the app needs to start a connection: ready sing-box and
// (optionally) Xray configs built from a share link.
type Profile struct {
	Name     string
	Protocol string
	Address  string // host:port shown in the UI
	// UsesXray is true when XrayConfig must be started before SingBoxConfig.
	UsesXray      bool
	SingBoxConfig string
	XrayConfig    string

	// Set for protocols that run in an in-process engine behind a loopback
	// SOCKS5 bridge (Mieru, AmneziaWG). Started by StartEngines.
	sidecar     string
	sidecarSrv  *server
	sidecarPort int
	sidecarUser string
	sidecarPass string

	// Auto mode: sing-box urltest over several servers, observed through the
	// local Clash API. autoNames maps outbound tags to server names.
	autoNames       map[string]string
	clashController string
	clashSecret     string
}

// IsAuto reports whether this profile picks the best of several servers itself.
func (p *Profile) IsAuto() bool {
	return p.clashController != ""
}

// Server-name resolution and plain DNS for direct traffic. Yandex DNS answers
// reliably from Russian networks; it only ever sees the proxy server names.
const directDNSServer = "77.88.8.8"

// DNS for everything else goes through the tunnel.
const remoteDNSServer = "1.1.1.1"

type jsonObject = map[string]any

// DescribeLink parses a share link and returns its display name and protocol
// without building configs. Used to validate links the user pastes.
func DescribeLink(link string) (*Profile, error) {
	s, err := parseLink(link)
	if err != nil {
		return nil, err
	}
	return &Profile{Name: s.Name, Protocol: s.Protocol, Address: s.address()}, nil
}

// BuildProfile parses a share link and builds the configs to run it with
// default options.
func BuildProfile(link string) (*Profile, error) {
	return BuildProfileWithOptions(link, "")
}

// BuildProfileWithOptions is BuildProfile with app settings as JSON
// (see the options struct), e.g. {"bypassRussia":true,"ruleSetDir":"/data/..."}.
func BuildProfileWithOptions(link string, optionsJSON string) (*Profile, error) {
	opts, err := parseOptions(optionsJSON)
	if err != nil {
		return nil, err
	}
	s, err := parseLink(link)
	if err != nil {
		return nil, err
	}
	profile := &Profile{Name: s.Name, Protocol: s.Protocol, Address: s.address()}

	// The proxy is either an outbound or (WireGuard) an endpoint tagged "proxy".
	var proxyOutbounds, proxyEndpoints []any
	switch engineFor(s) {
	case engineXray:
		port, err := freeLoopbackPort()
		if err != nil {
			return nil, err
		}
		user, pass := randomToken(), randomToken()
		xrayOutbound, err := xrayOutbound(s)
		if err != nil {
			return nil, err
		}
		xrayConfig := jsonObject{
			"log": jsonObject{"loglevel": "warning"},
			"inbounds": []any{jsonObject{
				"tag":      "socks-in",
				"listen":   "127.0.0.1",
				"port":     port,
				"protocol": "socks",
				"settings": jsonObject{
					"auth":     "password",
					"accounts": []any{jsonObject{"user": user, "pass": pass}},
					"udp":      true,
					"ip":       "127.0.0.1",
				},
			}},
			"outbounds": []any{xrayOutbound},
		}
		profile.UsesXray = true
		if profile.XrayConfig, err = marshal(xrayConfig); err != nil {
			return nil, err
		}
		// sing-box hands these connections to Xray's local SOCKS inbound;
		// the random credentials keep other apps on the device out of it.
		proxyOutbounds = []any{loopbackSocks(port, user, pass)}
	case engineSingBox:
		outbounds, endpoints, err := singBoxProxy(s)
		if err != nil {
			return nil, err
		}
		proxyOutbounds, proxyEndpoints = outbounds, endpoints
	case engineSidecar:
		port, err := freeLoopbackPort()
		if err != nil {
			return nil, err
		}
		profile.sidecar = s.Protocol
		profile.sidecarSrv = s
		profile.sidecarPort = port
		profile.sidecarUser, profile.sidecarPass = randomToken(), randomToken()
		proxyOutbounds = []any{loopbackSocks(port, profile.sidecarUser, profile.sidecarPass)}
	default:
		return nil, fmt.Errorf("%s: support is coming in the next update", s.Protocol)
	}

	if profile.SingBoxConfig, err = marshal(singBoxConfig([]*server{s}, proxyOutbounds, proxyEndpoints, opts)); err != nil {
		return nil, err
	}
	return profile, nil
}

type engine int

const (
	engineUnsupported engine = iota
	engineXray
	engineSingBox
	engineSidecar // Mieru, AmneziaWG: in-process engines behind a SOCKS5 bridge
)

// engineFor picks the engine for a protocol: Xray where it is the reference
// implementation, sing-box for the rest.
func engineFor(s *server) engine {
	switch s.Protocol {
	case "vless", "vmess", "trojan":
		return engineXray
	case "shadowsocks":
		if s.ShadowTLSHost != "" {
			return engineSingBox // ShadowTLS exists only in sing-box
		}
		return engineXray
	case "hysteria2", "tuic", "anytls", "naive", "wireguard", "socks", "http":
		return engineSingBox
	case "mieru", "amneziawg":
		return engineSidecar
	}
	return engineUnsupported
}

// loopbackSocks is how sing-box hands connections to an in-process engine
// (Xray, Mieru, AmneziaWG) listening on 127.0.0.1. The random credentials keep
// other apps on the device out of it.
func loopbackSocks(port int, user, pass string) jsonObject {
	return jsonObject{
		"type":        "socks",
		"tag":         "proxy",
		"server":      "127.0.0.1",
		"server_port": port,
		"version":     "5",
		"username":    user,
		"password":    pass,
	}
}

func (s *server) address() string {
	if s.Port == 0 {
		return s.Host + ":" + s.PortHopping
	}
	return net.JoinHostPort(s.Host, strconv.Itoa(s.Port))
}

func singBoxConfig(servers []*server, proxyOutbounds, proxyEndpoints []any, opts options) jsonObject {
	outbounds := append(append([]any{}, proxyOutbounds...), jsonObject{"type": "direct", "tag": "direct"})
	dnsRules := []any{}
	var serverNames []string
	for _, s := range servers {
		if net.ParseIP(s.Host) == nil {
			serverNames = append(serverNames, s.Host)
		}
	}
	if len(serverNames) > 0 {
		// Proxy server names must resolve directly, never through the tunnel.
		dnsRules = append(dnsRules, jsonObject{"domain": serverNames, "server": "direct-dns"})
	}
	routeRules := []any{
		jsonObject{"action": "sniff"},
		jsonObject{"protocol": "dns", "action": "hijack-dns"},
		jsonObject{"ip_is_private": true, "outbound": "direct"},
	}
	var ruleSets []any
	if opts.BypassRussia {
		bypassDNS, bypassRoute, sets := russiaBypass(opts.RuleSetDir)
		dnsRules = append(dnsRules, bypassDNS...)
		routeRules = append(routeRules, bypassRoute...)
		ruleSets = sets
	}
	config := jsonObject{
		"log": jsonObject{"level": "info"},
		"dns": jsonObject{
			"servers": []any{
				jsonObject{"type": "https", "tag": "remote-dns", "server": remoteDNSServer, "detour": "proxy"},
				jsonObject{"type": "udp", "tag": "direct-dns", "server": directDNSServer},
			},
			"rules":    dnsRules,
			"final":    "remote-dns",
			"strategy": "ipv4_only",
		},
		"inbounds": []any{jsonObject{
			"type":         "tun",
			"tag":          "tun-in",
			"address":      []string{"172.19.0.1/30", "fdfe:dcba:9876::1/126"},
			"mtu":          9000,
			"auto_route":   true,
			"strict_route": true,
			"stack":        "mixed",
		}},
		"outbounds": outbounds,
		"route": jsonObject{
			"rules":                   routeRules,
			"final":                   "proxy",
			"auto_detect_interface":   true,
			"default_domain_resolver": "direct-dns",
		},
	}
	if len(proxyEndpoints) > 0 {
		config["endpoints"] = proxyEndpoints
	}
	if len(ruleSets) > 0 {
		config["route"].(jsonObject)["rule_set"] = ruleSets
	}
	if opts.clashController != "" {
		config["experimental"] = jsonObject{"clash_api": jsonObject{
			"external_controller": opts.clashController,
			"secret":              opts.clashSecret,
		}}
	}
	return config
}

// singBoxProxy builds the sing-box side of protocols that sing-box runs itself.
func singBoxProxy(s *server) (outbounds, endpoints []any, err error) {
	switch s.Protocol {
	case "hysteria2":
		return []any{singBoxHysteria2(s)}, nil, nil
	case "tuic":
		out := jsonObject{
			"type":        "tuic",
			"tag":         "proxy",
			"server":      s.Host,
			"server_port": s.Port,
			"uuid":        s.UUID,
			"password":    s.Password,
		}
		if s.CongestionControl != "" {
			out["congestion_control"] = s.CongestionControl
		}
		if s.UDPRelayMode != "" {
			out["udp_relay_mode"] = s.UDPRelayMode
		}
		alpn := s.ALPN
		if len(alpn) == 0 {
			alpn = []string{"h3"}
		}
		out["tls"] = singBoxTLS(s, alpn, false)
		return []any{out}, nil, nil
	case "anytls":
		return []any{jsonObject{
			"type":        "anytls",
			"tag":         "proxy",
			"server":      s.Host,
			"server_port": s.Port,
			"password":    s.Password,
			"tls":         singBoxTLS(s, s.ALPN, true),
		}}, nil, nil
	case "naive":
		out := jsonObject{
			"type":        "naive",
			"tag":         "proxy",
			"server":      s.Host,
			"server_port": s.Port,
			"tls":         singBoxTLS(s, nil, false),
		}
		if s.Username != "" {
			out["username"] = s.Username
			out["password"] = s.Password
		}
		if s.QUIC {
			out["quic"] = true
		}
		return []any{out}, nil, nil
	case "shadowsocks":
		// Shadowsocks over ShadowTLS: the Shadowsocks outbound is chained
		// through a ShadowTLS outbound that performs the TLS handshake.
		ss := jsonObject{
			"type":        "shadowsocks",
			"tag":         "proxy",
			"server":      s.Host,
			"server_port": s.Port,
			"method":      s.Method,
			"password":    s.Password,
			"detour":      "shadowtls",
		}
		stls := jsonObject{
			"type":        "shadowtls",
			"tag":         "shadowtls",
			"server":      s.Host,
			"server_port": s.Port,
			"version":     s.ShadowTLSVersion,
			"tls": jsonObject{
				"enabled":     true,
				"server_name": s.ShadowTLSHost,
				"utls":        jsonObject{"enabled": true, "fingerprint": "chrome"},
			},
		}
		if s.ShadowTLSVersion > 1 {
			stls["password"] = s.ShadowTLSPassword
		}
		return []any{ss, stls}, nil, nil
	case "socks", "http":
		out := jsonObject{
			"type":        s.Protocol,
			"tag":         "proxy",
			"server":      s.Host,
			"server_port": s.Port,
		}
		if s.Protocol == "socks" {
			out["version"] = "5"
		}
		if s.Username != "" {
			out["username"] = s.Username
			out["password"] = s.Password
		}
		if s.Security == "tls" {
			out["tls"] = singBoxTLS(s, nil, false)
		}
		return []any{out}, nil, nil
	case "wireguard":
		peer := jsonObject{
			"address":     s.Host,
			"port":        s.Port,
			"public_key":  s.PeerPublicKey,
			"allowed_ips": []string{"0.0.0.0/0", "::/0"},
		}
		if s.PresharedKey != "" {
			peer["pre_shared_key"] = s.PresharedKey
		}
		if len(s.Reserved) == 3 {
			peer["reserved"] = s.Reserved
		}
		keepalive := s.Keepalive
		if keepalive == 0 {
			keepalive = 25
		}
		peer["persistent_keepalive_interval"] = keepalive
		endpoint := jsonObject{
			"type":        "wireguard",
			"tag":         "proxy",
			"address":     s.Addresses,
			"private_key": s.PrivateKey,
			"peers":       []any{peer},
		}
		if s.MTU > 0 {
			endpoint["mtu"] = s.MTU
		}
		return nil, []any{endpoint}, nil
	}
	return nil, nil, errUnsupported
}

// singBoxTLS builds a TLS block. withUTLS adds a browser fingerprint, which
// matters for protocols that look like ordinary HTTPS (AnyTLS).
func singBoxTLS(s *server, alpn []string, withUTLS bool) jsonObject {
	tls := jsonObject{"enabled": true}
	serverName := s.SNI
	if serverName == "" && net.ParseIP(s.Host) == nil {
		serverName = s.Host
	}
	if serverName != "" {
		tls["server_name"] = serverName
	}
	if s.Insecure {
		tls["insecure"] = true
	}
	if len(alpn) > 0 {
		tls["alpn"] = alpn
	}
	if withUTLS {
		fingerprint := s.Fingerprint
		if fingerprint == "" {
			fingerprint = "chrome"
		}
		tls["utls"] = jsonObject{"enabled": true, "fingerprint": fingerprint}
	}
	return tls
}

func singBoxHysteria2(s *server) jsonObject {
	out := jsonObject{
		"type":     "hysteria2",
		"tag":      "proxy",
		"server":   s.Host,
		"password": s.Password,
	}
	if s.Port != 0 {
		out["server_port"] = s.Port
	}
	if s.PortHopping != "" {
		// Links use "20000-30000"; sing-box wants "20000:30000".
		var ranges []string
		for _, r := range splitList(s.PortHopping) {
			ranges = append(ranges, strings.ReplaceAll(r, "-", ":"))
		}
		out["server_ports"] = ranges
	}
	if s.Obfs != "" {
		out["obfs"] = jsonObject{"type": s.Obfs, "password": s.ObfsPassword}
	}
	tls := jsonObject{"enabled": true}
	if s.SNI != "" {
		tls["server_name"] = s.SNI
	}
	if s.Insecure {
		tls["insecure"] = true
	}
	if len(s.ALPN) > 0 {
		tls["alpn"] = s.ALPN
	}
	out["tls"] = tls
	return out
}

func xrayOutbound(s *server) (jsonObject, error) {
	out := jsonObject{"tag": "proxy"}
	switch s.Protocol {
	case "vless":
		user := jsonObject{"id": s.UUID, "encryption": s.Encrypt}
		if s.Flow != "" {
			user["flow"] = s.Flow
		}
		out["protocol"] = "vless"
		out["settings"] = jsonObject{"vnext": []any{jsonObject{
			"address": s.Host, "port": s.Port, "users": []any{user},
		}}}
	case "vmess":
		out["protocol"] = "vmess"
		out["settings"] = jsonObject{"vnext": []any{jsonObject{
			"address": s.Host, "port": s.Port,
			"users": []any{jsonObject{"id": s.UUID, "security": s.Method}},
		}}}
	case "trojan":
		out["protocol"] = "trojan"
		out["settings"] = jsonObject{"servers": []any{jsonObject{
			"address": s.Host, "port": s.Port, "password": s.Password,
		}}}
	case "shadowsocks":
		out["protocol"] = "shadowsocks"
		out["settings"] = jsonObject{"servers": []any{jsonObject{
			"address": s.Host, "port": s.Port, "method": s.Method, "password": s.Password,
		}}}
		return out, nil // no stream settings for plain Shadowsocks
	default:
		return nil, errUnsupported
	}
	stream, err := xrayStreamSettings(s)
	if err != nil {
		return nil, err
	}
	out["streamSettings"] = stream
	return out, nil
}

func xrayStreamSettings(s *server) (jsonObject, error) {
	stream := jsonObject{"network": s.Network, "security": s.Security}
	switch s.Network {
	case "tcp":
		stream["network"] = "raw"
		if s.HeaderType == "http" {
			request := jsonObject{"path": []string{firstNonEmpty(s.Path, "/")}}
			if s.HostHeader != "" {
				request["headers"] = jsonObject{"Host": splitList(s.HostHeader)}
			}
			stream["rawSettings"] = jsonObject{"header": jsonObject{"type": "http", "request": request}}
		}
	case "ws":
		ws := jsonObject{"path": s.Path}
		if s.HostHeader != "" {
			ws["host"] = s.HostHeader
		}
		stream["wsSettings"] = ws
	case "httpupgrade":
		hu := jsonObject{"path": s.Path}
		if s.HostHeader != "" {
			hu["host"] = s.HostHeader
		}
		stream["httpupgradeSettings"] = hu
	case "grpc":
		grpc := jsonObject{"serviceName": s.ServiceName, "multiMode": s.GRPCMode == "multi"}
		if s.Authority != "" {
			grpc["authority"] = s.Authority
		}
		stream["grpcSettings"] = grpc
	case "xhttp":
		xhttp := jsonObject{"path": s.Path}
		if s.HostHeader != "" {
			xhttp["host"] = s.HostHeader
		}
		if s.XHTTPMode != "" {
			xhttp["mode"] = s.XHTTPMode
		}
		if s.XHTTPExtra != "" {
			var extra any
			if err := json.Unmarshal([]byte(s.XHTTPExtra), &extra); err != nil {
				return nil, errors.New("xhttp: invalid extra parameter")
			}
			xhttp["extra"] = extra
		}
		stream["xhttpSettings"] = xhttp
	default:
		return nil, errors.New("unsupported transport: " + s.Network)
	}

	fingerprint := s.Fingerprint
	if fingerprint == "" {
		fingerprint = "chrome"
	}
	switch s.Security {
	case "none", "":
		stream["security"] = "none"
	case "tls":
		tls := jsonObject{"fingerprint": fingerprint}
		if s.SNI != "" {
			tls["serverName"] = s.SNI
		}
		if len(s.ALPN) > 0 {
			tls["alpn"] = s.ALPN
		}
		if s.Insecure {
			tls["allowInsecure"] = true
		}
		stream["tlsSettings"] = tls
	case "reality":
		if s.PublicKey == "" {
			return nil, errors.New("reality: missing public key (pbk)")
		}
		reality := jsonObject{
			"fingerprint": fingerprint,
			"serverName":  s.SNI,
			"publicKey":   s.PublicKey,
			"shortId":     s.ShortID,
		}
		if s.SpiderX != "" {
			reality["spiderX"] = s.SpiderX
		}
		stream["realitySettings"] = reality
	default:
		return nil, errors.New("unsupported security: " + s.Security)
	}
	return stream, nil
}

func freeLoopbackPort() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

func randomToken() string {
	buf := make([]byte, 12)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

func marshal(v any) (string, error) {
	data, err := json.Marshal(v)
	return string(data), err
}
