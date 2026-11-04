package iykyk

import (
	"encoding/json"
	"strings"
)

// details is the short description shown under a server name, in the style of
// other clients: "VLESS · gRPC · REALITY".
func (s *server) details() string {
	parts := []string{protocolTitle(s.Protocol)}
	switch s.Protocol {
	case "vless", "vmess", "trojan":
		parts = append(parts, transportTitle(s.Network))
		if security := securityTitle(s.Security); security != "" {
			parts = append(parts, security)
		}
	case "shadowsocks":
		if s.ShadowTLSHost != "" {
			parts = append(parts, "ShadowTLS")
		}
	case "naive":
		if s.QUIC {
			parts = append(parts, "QUIC")
		}
	case "socks", "http":
		if s.Security == "tls" {
			parts = append(parts, "TLS")
		}
	case "xray-json", "xray-balancer":
		parts = xrayJSONDetails(s.Raw)
	}
	return strings.Join(parts, " · ")
}

func protocolTitle(protocol string) string {
	switch protocol {
	case "vless":
		return "VLESS"
	case "vmess":
		return "VMess"
	case "trojan":
		return "Trojan"
	case "shadowsocks":
		return "Shadowsocks"
	case "hysteria2":
		return "Hysteria2"
	case "tuic":
		return "TUIC"
	case "anytls":
		return "AnyTLS"
	case "naive":
		return "NaiveProxy"
	case "wireguard":
		return "WireGuard"
	case "amneziawg":
		return "AmneziaWG"
	case "mieru":
		return "Mieru"
	case "socks":
		return "SOCKS5"
	case "http":
		return "HTTP"
	case "xray-json", "xray-balancer":
		return "Xray"
	}
	return protocol
}

func transportTitle(network string) string {
	switch strings.ToLower(network) {
	case "", "tcp", "raw":
		return "TCP"
	case "ws":
		return "WS"
	case "grpc":
		return "gRPC"
	case "xhttp", "splithttp":
		return "XHTTP"
	case "httpupgrade":
		return "HTTPUpgrade"
	case "kcp":
		return "mKCP"
	}
	return network
}

func securityTitle(security string) string {
	switch strings.ToLower(security) {
	case "reality":
		return "REALITY"
	case "tls":
		return "TLS"
	}
	return ""
}

// xrayJSONDetails describes a full Xray config by its first proxy outbound.
func xrayJSONDetails(raw string) []string {
	var config struct {
		Outbounds []struct {
			Protocol       string `json:"protocol"`
			StreamSettings struct {
				Network  string `json:"network"`
				Security string `json:"security"`
			} `json:"streamSettings"`
		} `json:"outbounds"`
		Routing struct {
			Balancers []json.RawMessage `json:"balancers"`
		} `json:"routing"`
	}
	if json.Unmarshal([]byte(raw), &config) != nil {
		return []string{"Xray"}
	}
	for _, o := range config.Outbounds {
		switch o.Protocol {
		case "freedom", "blackhole", "dns", "loopback", "":
			continue
		}
		parts := []string{protocolTitle(o.Protocol), transportTitle(o.StreamSettings.Network)}
		if security := securityTitle(o.StreamSettings.Security); security != "" {
			parts = append(parts, security)
		}
		if len(config.Routing.Balancers) > 0 {
			parts = append(parts, "Auto")
		}
		return parts
	}
	return []string{"Xray"}
}
