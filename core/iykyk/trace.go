package iykyk

import (
	"bufio"
	"io"
	"strings"
)

// TunnelInfo is what CheckTunnel found.
type TunnelInfo struct {
	IP      string
	Country string // ISO code, e.g. "NL"
	Latency int    // milliseconds
}

// parseTrace reads Cloudflare's trace ("ip=1.2.3.4", "loc=NL", one per line).
func parseTrace(body io.Reader) *TunnelInfo {
	info := &TunnelInfo{}
	scanner := bufio.NewScanner(body)
	for scanner.Scan() {
		key, value, ok := strings.Cut(scanner.Text(), "=")
		if !ok {
			continue
		}
		switch key {
		case "ip":
			info.IP = value
		case "loc":
			info.Country = value
		}
	}
	return info
}
