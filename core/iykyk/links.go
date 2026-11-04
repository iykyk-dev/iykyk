package iykyk

import (
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// server is a parsed share link. Only fields relevant to the protocol are set.
type server struct {
	// vless, vmess, trojan, shadowsocks, hysteria2, tuic, anytls, naive,
	// wireguard, amneziawg, mieru, socks, http
	Protocol string
	Name     string
	Host     string
	Port     int

	// Credentials.
	UUID     string // vless, vmess, tuic
	Username string // socks, http, naive
	Password string // trojan, shadowsocks, hysteria2, tuic, anytls, socks, http, naive
	Method   string // shadowsocks; vmess cipher
	Flow     string // vless
	Encrypt  string // vless encryption ("none" by default)

	// Transport: tcp (raw), ws, grpc, xhttp, httpupgrade.
	Network     string
	HeaderType  string // tcp header obfuscation: "http"
	Path        string
	HostHeader  string
	ServiceName string // grpc
	GRPCMode    string // gun | multi
	Authority   string // grpc
	XHTTPMode   string
	XHTTPExtra  string // raw JSON

	// TUIC.
	CongestionControl string
	UDPRelayMode      string

	// NaiveProxy over HTTP/3.
	QUIC bool

	// ShadowTLS in front of Shadowsocks.
	ShadowTLSVersion  int
	ShadowTLSPassword string
	ShadowTLSHost     string

	// WireGuard / AmneziaWG.
	PrivateKey    string
	PeerPublicKey string
	PresharedKey  string
	Addresses     []string
	AllowedIPs    []string
	Reserved      []int
	MTU           int
	Keepalive     int
	AmneziaParams map[string]string // jc, jmin, jmax, s1-s4, h1-h4, i1-i5

	// Raw link, needed by engines that parse it themselves (Mieru, Xray JSON).
	Raw string
	// All proxy server names in an Xray JSON config (they resolve directly).
	ExtraHosts []string

	// Security: none, tls, reality.
	Security    string
	SNI         string
	ALPN        []string
	Fingerprint string
	PublicKey   string // reality
	ShortID     string // reality
	SpiderX     string // reality
	Insecure    bool

	// Hysteria2.
	Obfs         string
	ObfsPassword string
	PortHopping  string // e.g. "20000-30000,40000"
}

var errUnsupported = errors.New("unsupported link")

func parseLink(link string) (*server, error) {
	link = strings.TrimSpace(link)
	if strings.HasPrefix(link, "{") {
		return parseXrayJSON(link)
	}
	if strings.Contains(link, "[Interface]") {
		return parseWireGuardConf(link)
	}
	scheme, _, ok := strings.Cut(link, "://")
	if !ok {
		return nil, errUnsupported
	}
	switch strings.ToLower(scheme) {
	case "vless":
		return parseVLESS(link)
	case "vmess":
		return parseVMess(link)
	case "trojan":
		return parseTrojan(link)
	case "ss":
		return parseShadowsocks(link)
	case "hysteria2", "hy2":
		return parseHysteria2(link)
	case "tuic":
		return parseTUIC(link)
	case "anytls":
		return parseAnyTLS(link)
	case "naive+https", "naive+quic", "naive":
		return parseNaive(link)
	case "wireguard", "wg":
		return parseWireGuardLink(link)
	case "mierus":
		return parseMieru(link)
	case "socks", "socks5", "socks5h":
		return parseProxyLink(link, "socks")
	case "http", "https":
		return parseProxyLink(link, "http")
	default:
		return nil, fmt.Errorf("%w: %s", errUnsupported, scheme)
	}
}

// parseHostPort reads host and port from a URL, allowing IPv6 in brackets.
func parseHostPort(u *url.URL) (string, int, error) {
	host := u.Hostname()
	if host == "" {
		return "", 0, errors.New("missing server address")
	}
	portText := u.Port()
	if portText == "" {
		return "", 0, errors.New("missing server port")
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, fmt.Errorf("invalid port %q", portText)
	}
	return host, port, nil
}

func linkName(u *url.URL, s *server) string {
	if u.Fragment != "" {
		return u.Fragment
	}
	return net.JoinHostPort(s.Host, strconv.Itoa(s.Port))
}

func splitList(value string) []string {
	var items []string
	for _, item := range strings.Split(value, ",") {
		if item = strings.TrimSpace(item); item != "" {
			items = append(items, item)
		}
	}
	return items
}

func isTrue(value string) bool {
	switch strings.ToLower(value) {
	case "1", "true", "yes":
		return true
	}
	return false
}

// readTransport fills transport and security fields from the standard
// v2rayN-style query parameters used by vless:// and trojan:// links.
func readTransport(q url.Values, s *server, defaultSecurity string) {
	s.Network = strings.ToLower(q.Get("type"))
	switch s.Network {
	case "", "tcp", "raw":
		s.Network = "tcp"
	case "splithttp":
		s.Network = "xhttp"
	}
	s.HeaderType = q.Get("headerType")
	s.Path = q.Get("path")
	s.HostHeader = q.Get("host")
	s.ServiceName = q.Get("serviceName")
	s.GRPCMode = q.Get("mode")
	s.Authority = q.Get("authority")
	if s.Network == "xhttp" {
		s.XHTTPMode = q.Get("mode")
		s.XHTTPExtra = q.Get("extra")
	}

	s.Security = strings.ToLower(q.Get("security"))
	if s.Security == "" {
		s.Security = defaultSecurity
	}
	s.SNI = q.Get("sni")
	if s.SNI == "" {
		s.SNI = q.Get("peer")
	}
	s.ALPN = splitList(q.Get("alpn"))
	s.Fingerprint = q.Get("fp")
	s.PublicKey = q.Get("pbk")
	s.ShortID = q.Get("sid")
	s.SpiderX = q.Get("spx")
	s.Insecure = isTrue(q.Get("allowInsecure")) || isTrue(q.Get("insecure"))
}

func parseVLESS(link string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "vless"}
	if s.Host, s.Port, err = parseHostPort(u); err != nil {
		return nil, err
	}
	s.UUID = u.User.Username()
	if s.UUID == "" {
		return nil, errors.New("vless: missing user id")
	}
	q := u.Query()
	s.Flow = q.Get("flow")
	s.Encrypt = q.Get("encryption")
	if s.Encrypt == "" {
		s.Encrypt = "none"
	}
	readTransport(q, s, "none")
	s.Name = linkName(u, s)
	return s, nil
}

func parseTrojan(link string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "trojan"}
	if s.Host, s.Port, err = parseHostPort(u); err != nil {
		return nil, err
	}
	s.Password = u.User.Username()
	if s.Password == "" {
		return nil, errors.New("trojan: missing password")
	}
	readTransport(u.Query(), s, "tls")
	s.Name = linkName(u, s)
	return s, nil
}

func decodeBase64(text string) (string, error) {
	text = strings.TrimSpace(text)
	for _, enc := range []*base64.Encoding{
		base64.RawURLEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.StdEncoding,
	} {
		if decoded, err := enc.DecodeString(text); err == nil {
			return string(decoded), nil
		}
	}
	return "", errors.New("invalid base64")
}

// parseShadowsocks supports SIP002 (ss://base64(method:password)@host:port#name),
// SIP002 with plain userinfo (ss://method:password@host:port) and the legacy
// form ss://base64(method:password@host:port)#name.
func parseShadowsocks(link string) (*server, error) {
	body := strings.TrimPrefix(link, link[:len("ss://")])
	fragment := ""
	if before, after, ok := strings.Cut(body, "#"); ok {
		body, fragment = before, after
	}
	if !strings.Contains(body, "@") {
		// Legacy: the whole body is base64.
		withoutQuery, _, _ := strings.Cut(body, "?")
		decoded, err := decodeBase64(withoutQuery)
		if err != nil {
			return nil, fmt.Errorf("shadowsocks: %w", err)
		}
		body = decoded
	}
	u, err := url.Parse("ss://" + body)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "shadowsocks"}
	if s.Host, s.Port, err = parseHostPort(u); err != nil {
		return nil, err
	}
	if err := readShadowTLS(u.Query(), s); err != nil {
		return nil, err
	}
	method := u.User.Username()
	password, hasPassword := u.User.Password()
	if !hasPassword {
		decoded, err := decodeBase64(method)
		if err != nil {
			return nil, fmt.Errorf("shadowsocks: invalid credentials: %w", err)
		}
		method, password, hasPassword = strings.Cut(decoded, ":")
	}
	if !hasPassword || method == "" {
		return nil, errors.New("shadowsocks: missing method or password")
	}
	s.Method, s.Password = method, password
	if fragment != "" {
		if unescaped, err := url.PathUnescape(fragment); err == nil {
			fragment = unescaped
		}
		s.Name = fragment
	} else {
		s.Name = net.JoinHostPort(s.Host, strconv.Itoa(s.Port))
	}
	return s, nil
}

func parseHysteria2(link string) (*server, error) {
	// Port hopping lists like host:20000-30000 are not valid URL ports, and
	// url.Parse rejects them, so cut the port out before parsing.
	hopping := ""
	scheme, rest, _ := strings.Cut(link, "://")
	authEnd := strings.IndexAny(rest, "/?#")
	if authEnd < 0 {
		authEnd = len(rest)
	}
	authority := rest[:authEnd]
	hostStart := strings.LastIndex(authority, "@") + 1
	if colon := strings.LastIndex(authority, ":"); colon > hostStart && !strings.HasSuffix(authority, "]") {
		portText := authority[colon+1:]
		if strings.ContainsAny(portText, "-,") {
			hopping = portText
			authority = authority[:colon]
			link = scheme + "://" + authority + rest[authEnd:]
		}
	}

	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}
	s := &server{Protocol: "hysteria2", Security: "tls"}
	s.Host = u.Hostname()
	if s.Host == "" {
		return nil, errors.New("hysteria2: missing server address")
	}
	q := u.Query()
	s.PortHopping = q.Get("mport")
	if s.PortHopping == "" {
		s.PortHopping = hopping
	}
	if portText := u.Port(); portText != "" {
		port, err := strconv.Atoi(portText)
		if err != nil {
			return nil, fmt.Errorf("hysteria2: invalid port %q", portText)
		}
		s.Port = port
	}
	if s.Port == 0 && s.PortHopping == "" {
		s.Port = 443
	}
	s.Password = u.User.Username()
	if pass, ok := u.User.Password(); ok {
		// Some generators put "user:pass" in userinfo.
		s.Password = s.Password + ":" + pass
	}
	if s.Password == "" {
		s.Password = q.Get("auth")
	}
	s.SNI = q.Get("sni")
	s.ALPN = splitList(q.Get("alpn"))
	s.Insecure = isTrue(q.Get("insecure"))
	s.Obfs = q.Get("obfs")
	s.ObfsPassword = q.Get("obfs-password")
	s.Name = linkName(u, s)
	if u.Fragment == "" && s.Port == 0 {
		s.Name = s.Host
	}
	return s, nil
}
