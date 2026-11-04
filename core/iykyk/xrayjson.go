package iykyk

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"
)

// Full Xray JSON configs, as panels serve them to Xray-JSON clients (Remnawave's
// XRAY_JSON response type): a JSON array of configs, each with "remarks" and its
// own outbounds, routing, DNS and possibly balancers with an observatory, which
// is how panels implement server auto-selection. IYKYK runs each config in Xray
// as is, replacing only the inbounds with its own loopback SOCKS inbound.

// parseXrayJSONSubscription returns one server per config in the body.
func parseXrayJSONSubscription(body string) ([]*server, error) {
	body = strings.TrimSpace(body)
	var configs []json.RawMessage
	if strings.HasPrefix(body, "[") {
		if err := json.Unmarshal([]byte(body), &configs); err != nil {
			return nil, fmt.Errorf("invalid Xray JSON subscription: %w", err)
		}
	} else {
		configs = []json.RawMessage{json.RawMessage(body)}
	}
	var servers []*server
	for _, raw := range configs {
		s, err := parseXrayJSON(string(raw))
		if err != nil {
			continue
		}
		servers = append(servers, s)
	}
	if len(servers) == 0 {
		return nil, errors.New("no usable Xray configs in the subscription")
	}
	return servers, nil
}

// parseXrayJSON reads one Xray config. The compact JSON becomes the server's
// "link", so it can be stored and passed around like any share link.
func parseXrayJSON(text string) (*server, error) {
	var config map[string]any
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.UseNumber()
	if err := decoder.Decode(&config); err != nil {
		return nil, fmt.Errorf("invalid Xray config: %w", err)
	}
	outbounds, _ := config["outbounds"].([]any)
	if len(outbounds) == 0 {
		return nil, errors.New("Xray config has no outbounds")
	}
	var compact bytes.Buffer
	if err := json.Compact(&compact, []byte(text)); err != nil {
		return nil, err
	}
	s := &server{Protocol: "xray-json", Raw: compact.String()}
	if routing, ok := config["routing"].(map[string]any); ok {
		if balancers, ok := routing["balancers"].([]any); ok && len(balancers) > 0 {
			s.Protocol = "xray-balancer"
		}
	}
	hosts := xrayOutboundHosts(outbounds)
	if len(hosts) > 0 {
		s.Host, s.Port = hosts[0].host, hosts[0].port
	}
	for _, h := range hosts {
		s.ExtraHosts = append(s.ExtraHosts, h.host)
	}
	s.Name, _ = config["remarks"].(string)
	if s.Name == "" {
		s.Name = s.address()
	}
	return s, nil
}

type hostPort struct {
	host string
	port int
}

// xrayOutboundHosts collects the proxy servers' addresses from Xray outbounds
// (vnext/servers lists and the newer flat "address"/"port" form).
func xrayOutboundHosts(outbounds []any) []hostPort {
	var hosts []hostPort
	add := func(entry map[string]any) {
		address, _ := entry["address"].(string)
		if address == "" || address == "127.0.0.1" || address == "localhost" {
			return
		}
		port := 0
		switch p := entry["port"].(type) {
		case json.Number:
			n, _ := p.Int64()
			port = int(n)
		case float64:
			port = int(p)
		case string:
			port, _ = strconv.Atoi(p)
		}
		hosts = append(hosts, hostPort{address, port})
	}
	for _, o := range outbounds {
		outbound, ok := o.(map[string]any)
		if !ok {
			continue
		}
		switch outbound["protocol"] {
		case "freedom", "blackhole", "dns", "loopback":
			continue
		}
		settings, _ := outbound["settings"].(map[string]any)
		if settings == nil {
			continue
		}
		for _, key := range []string{"vnext", "servers", "peers"} {
			if list, ok := settings[key].([]any); ok {
				for _, item := range list {
					if entry, ok := item.(map[string]any); ok {
						if endpoint, ok := entry["endpoint"].(string); ok && key == "peers" {
							if host, port, err := net.SplitHostPort(endpoint); err == nil {
								p, _ := strconv.Atoi(port)
								hosts = append(hosts, hostPort{host, p})
							}
							continue
						}
						add(entry)
					}
				}
			}
		}
		add(settings)
	}
	return hosts
}

// xrayConfigFromJSON prepares a subscription's Xray config to run inside IYKYK:
// its inbounds are replaced by one loopback SOCKS inbound (keeping the first
// inbound's tag, so routing rules that refer to it still match), and listeners
// IYKYK does not need (API, metrics) are removed. With fragment, TLS/REALITY
// outbounds split their ClientHello.
func xrayConfigFromJSON(raw string, port int, user, pass string, fragment bool, logFile string) (string, error) {
	var config map[string]any
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.UseNumber()
	if err := decoder.Decode(&config); err != nil {
		return "", err
	}
	tag := "socks"
	var sniffing any
	if inbounds, ok := config["inbounds"].([]any); ok && len(inbounds) > 0 {
		if first, ok := inbounds[0].(map[string]any); ok {
			if t, ok := first["tag"].(string); ok && t != "" {
				tag = t
			}
			// Domain-based routing rules need the inbound to sniff names.
			sniffing = first["sniffing"]
		}
	}
	inbound := xraySocksInbound(tag, port, user, pass)
	if sniffing != nil {
		inbound["sniffing"] = sniffing // the provider's own choice wins
	}
	if port == 0 {
		delete(config, "inbounds") // real ping dials through the instance directly
	} else {
		config["inbounds"] = []any{inbound}
	}
	slowObservatory(config)
	delete(config, "api")
	delete(config, "metrics")
	delete(config, "remarks")
	config["log"] = xrayLog(logFile)
	if outbounds, ok := config["outbounds"].([]any); ok {
		for _, o := range outbounds {
			stripSocketMark(o)
			if fragment {
				addXrayFragment(o)
			}
		}
	}
	return marshal(config)
}

// minProbeInterval is how often an auto-select balancer may test its servers.
// Panels often use 10-30 s, which keeps the phone's radio awake all day just
// for probes; 5 minutes still notices a dead server and costs little battery.
const minProbeInterval = 5 * time.Minute

func slowObservatory(config map[string]any) {
	if o, ok := config["observatory"].(map[string]any); ok {
		o["probeInterval"] = atLeast(o["probeInterval"], minProbeInterval)
		// Without concurrency Xray probes one server per interval, so with a long
		// interval most servers would stay untested for many minutes after
		// connecting and the balancer would have nothing to choose from. With it,
		// all servers are probed at once on start, then once per interval.
		o["enableConcurrency"] = true
	}
	if b, ok := config["burstObservatory"].(map[string]any); ok {
		ping, _ := b["pingConfig"].(map[string]any)
		if ping == nil {
			ping = map[string]any{}
			b["pingConfig"] = ping
		}
		ping["interval"] = atLeast(ping["interval"], minProbeInterval)
	}
}

// atLeast returns the Xray duration string v, raised to min if shorter or missing.
func atLeast(v any, min time.Duration) string {
	if text, ok := v.(string); ok {
		if d, err := time.ParseDuration(text); err == nil && d >= min {
			return text
		}
	}
	return min.String()
}

// stripSocketMark removes sockopt.mark: setting SO_MARK needs root on Android,
// and Xray refuses to dial when it fails. Desktop templates often include it.
func stripSocketMark(o any) {
	outbound, ok := o.(map[string]any)
	if !ok {
		return
	}
	stream, ok := outbound["streamSettings"].(map[string]any)
	if !ok {
		return
	}
	if sockopt, ok := stream["sockopt"].(map[string]any); ok {
		delete(sockopt, "mark")
	}
}

func addXrayFragment(o any) {
	outbound, ok := o.(map[string]any)
	if !ok {
		return
	}
	stream, ok := outbound["streamSettings"].(map[string]any)
	if !ok {
		return
	}
	security, _ := stream["security"].(string)
	network, _ := stream["network"].(string)
	if (security != "tls" && security != "reality") || network == "kcp" || network == "quic" {
		return
	}
	if _, exists := stream["finalmask"]; exists {
		return
	}
	stream["finalmask"] = jsonObject{"tcp": []any{jsonObject{
		"type":     "fragment",
		"settings": jsonObject{"packets": "tlshello", "length": "100-200", "delay": "10-20"},
	}}}
}

func xraySocksInbound(tag string, port int, user, pass string) jsonObject {
	return jsonObject{
		"tag":      tag,
		"listen":   "127.0.0.1",
		"port":     port,
		"protocol": "socks",
		"settings": jsonObject{
			"auth":     "password",
			"accounts": []any{jsonObject{"user": user, "pass": pass}},
			"udp":      true,
			"ip":       "127.0.0.1",
		},
		// Connections from the TUN arrive with the destination IP only.
		// Sniffing restores the site's name from the TLS/HTTP/QUIC handshake,
		// so the server gets "example.org:443" as it does from Happ or INCY.
		// Servers that route or balance by domain otherwise drop the traffic.
		"sniffing": jsonObject{
			"enabled":      true,
			"destOverride": []string{"http", "tls", "quic"},
		},
	}
}
