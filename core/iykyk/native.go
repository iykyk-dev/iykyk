package iykyk

import (
	"errors"
	"strings"
)

// Battery: VLESS, VMess, Trojan and Shadowsocks used to always go through Xray,
// so every packet crossed two engines (sing-box TUN → loopback SOCKS → Xray).
// sing-box implements the common variants itself, so those now run in sing-box
// alone and Xray starts only for what it alone supports: XHTTP, mKCP, HTTP
// header obfuscation, gRPC multi mode, VLESS encryption, early data, full Xray
// JSON configs, and REALITY.
//
// REALITY stays in Xray: since September 2026 the REALITY server rejects
// ClientHellos without an X25519MLKEM768 key share, and sing-box's REALITY
// client removes that share, so it cannot connect to current Xray servers
// (interop_engine_test.go checks this against a real server).

// selectEngine is engineFor with the native sing-box path applied.
func selectEngine(s *server, opts options) engine {
	e := engineFor(s)
	if e == engineXray && !opts.PreferXray && nativeInSingBox(s, opts) {
		return engineSingBox
	}
	return e
}

func nativeInSingBox(s *server, opts options) bool {
	switch s.Protocol {
	case "shadowsocks":
		return true // plain Shadowsocks (with ShadowTLS it is sing-box anyway)
	case "vless", "vmess", "trojan":
	default:
		return false
	}
	if s.Protocol == "vless" {
		if s.Encrypt != "" && s.Encrypt != "none" {
			return false // VLESS post-quantum encryption: Xray only
		}
		if s.Flow != "" && s.Flow != "xtls-rprx-vision" {
			return false
		}
	}
	switch s.Network {
	case "", "tcp", "raw":
		if s.HeaderType != "" && s.HeaderType != "none" {
			return false
		}
	case "ws", "httpupgrade":
		if strings.Contains(s.Path, "ed=") {
			return false // Xray-style early data in the path
		}
	case "grpc":
		if s.GRPCMode == "multi" || s.Authority != "" {
			return false
		}
	default:
		return false // xhttp, kcp, ...
	}
	switch s.Security {
	case "", "none", "tls":
		return true
	}
	return false // reality and anything unknown
}

// singBoxV2Ray builds a native sing-box outbound for VLESS, VMess or Trojan.
func singBoxV2Ray(s *server, opts options) (jsonObject, error) {
	out := jsonObject{
		"type":        s.Protocol,
		"tag":         "proxy",
		"server":      s.Host,
		"server_port": s.Port,
	}
	switch s.Protocol {
	case "vless":
		out["uuid"] = s.UUID
		if s.Flow != "" {
			out["flow"] = s.Flow
		}
	case "vmess":
		out["uuid"] = s.UUID
		out["security"] = firstNonEmpty(s.Method, "auto")
		out["alter_id"] = 0
	case "trojan":
		out["password"] = s.Password
	}

	switch s.Network {
	case "ws":
		ws := jsonObject{"type": "ws", "path": firstNonEmpty(s.Path, "/")}
		if s.HostHeader != "" {
			ws["headers"] = jsonObject{"Host": s.HostHeader}
		}
		out["transport"] = ws
	case "httpupgrade":
		hu := jsonObject{"type": "httpupgrade", "path": firstNonEmpty(s.Path, "/")}
		if s.HostHeader != "" {
			hu["host"] = s.HostHeader
		}
		out["transport"] = hu
	case "grpc":
		out["transport"] = jsonObject{"type": "grpc", "service_name": s.ServiceName}
	}

	switch s.Security {
	case "tls":
		out["tls"] = singBoxTLS(s, s.ALPN, true, opts.Fragment)
	case "reality":
		return nil, errors.New("reality runs in Xray")
	}
	return out, nil
}
