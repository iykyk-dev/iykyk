package iykyk

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
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

// BuildProfile parses a share link and builds the configs to run it.
func BuildProfile(link string) (*Profile, error) {
	s, err := parseLink(link)
	if err != nil {
		return nil, err
	}
	profile := &Profile{Name: s.Name, Protocol: s.Protocol, Address: s.address()}

	var proxyOutbound jsonObject
	switch s.Protocol {
	case "vless", "trojan", "shadowsocks":
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
		proxyOutbound = jsonObject{
			"type":        "socks",
			"tag":         "proxy",
			"server":      "127.0.0.1",
			"server_port": port,
			"version":     "5",
			"username":    user,
			"password":    pass,
		}
	case "hysteria2":
		proxyOutbound = singBoxHysteria2(s)
	default:
		return nil, errUnsupported
	}

	if profile.SingBoxConfig, err = marshal(singBoxConfig(s, proxyOutbound)); err != nil {
		return nil, err
	}
	return profile, nil
}

func (s *server) address() string {
	if s.Port == 0 {
		return s.Host + ":" + s.PortHopping
	}
	return net.JoinHostPort(s.Host, strconv.Itoa(s.Port))
}

func singBoxConfig(s *server, proxyOutbound jsonObject) jsonObject {
	dnsRules := []any{}
	if net.ParseIP(s.Host) == nil {
		// The proxy server's own name must resolve directly, never through the tunnel.
		dnsRules = append(dnsRules, jsonObject{"domain": []string{s.Host}, "server": "direct-dns"})
	}
	return jsonObject{
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
		"outbounds": []any{
			proxyOutbound,
			jsonObject{"type": "direct", "tag": "direct"},
		},
		"route": jsonObject{
			"rules": []any{
				jsonObject{"action": "sniff"},
				jsonObject{"protocol": "dns", "action": "hijack-dns"},
				jsonObject{"ip_is_private": true, "outbound": "direct"},
			},
			"final":                   "proxy",
			"auto_detect_interface":   true,
			"default_domain_resolver": "direct-dns",
		},
	}
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
