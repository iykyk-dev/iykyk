package iykyk

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

// options are the app settings that affect the generated configs.
type options struct {
	// BypassRussia sends Russian sites and IPs directly instead of through the VPN.
	BypassRussia bool `json:"bypassRussia"`
	// RuleSetDir holds geosite-category-ru.srs and geoip-ru.srs shipped with the app.
	// Without it, only the .ru/.su/.рф domain zones are bypassed.
	RuleSetDir string `json:"ruleSetDir"`
}

func parseOptions(text string) (options, error) {
	var opts options
	if text == "" {
		return opts, nil
	}
	if err := json.Unmarshal([]byte(text), &opts); err != nil {
		return opts, fmt.Errorf("invalid options: %w", err)
	}
	return opts, nil
}

// Rule-set files for the Russia bypass, from SagerNet/sing-geosite and sing-geoip.
const (
	ruleSetGeositeRU = "geosite-category-ru.srs"
	ruleSetGeoipRU   = "geoip-ru.srs"
)

// russiaBypass returns DNS rules, route rules and rule-set definitions that send
// Russian services directly. Domains resolve through the direct DNS server so
// they get Russian CDN addresses.
func russiaBypass(ruleSetDir string) (dnsRules, routeRules, ruleSets []any) {
	zones := jsonObject{"domain_suffix": []string{"ru", "su", "xn--p1ai"}} // xn--p1ai = .рф
	dnsRules = append(dnsRules, jsonObject{"domain_suffix": zones["domain_suffix"], "server": "direct-dns"})
	routeRules = append(routeRules, jsonObject{"domain_suffix": zones["domain_suffix"], "outbound": "direct"})
	if ruleSetDir == "" {
		return
	}
	ruleSets = []any{
		jsonObject{"type": "local", "tag": "geosite-ru", "format": "binary", "path": filepath.Join(ruleSetDir, ruleSetGeositeRU)},
		jsonObject{"type": "local", "tag": "geoip-ru", "format": "binary", "path": filepath.Join(ruleSetDir, ruleSetGeoipRU)},
	}
	dnsRules = append(dnsRules, jsonObject{"rule_set": []string{"geosite-ru"}, "server": "direct-dns"})
	// Domain rules first; the IP rule then catches apps that connect by address.
	routeRules = append(routeRules,
		jsonObject{"rule_set": []string{"geosite-ru"}, "outbound": "direct"},
		jsonObject{"rule_set": []string{"geoip-ru"}, "outbound": "direct"},
	)
	return
}

// --- Ping -----------------------------------------------------------------------

// PingServers measures TCP connect time to each server (linksJSON is a JSON array
// of share links) and returns a JSON array of milliseconds in the same order:
// -1 = unreachable, -2 = not measurable this way (UDP-based protocols).
// Sockets bypass the VPN when it is running, so this is the direct route.
func PingServers(linksJSON string) (string, error) {
	var links []string
	if err := json.Unmarshal([]byte(linksJSON), &links); err != nil {
		return "", err
	}
	results := make([]int, len(links))
	var wg sync.WaitGroup
	limit := make(chan struct{}, 16)
	for i, link := range links {
		s, err := parseLink(link)
		if err != nil {
			results[i] = -1
			continue
		}
		if !pingableOverTCP(s) {
			results[i] = -2
			continue
		}
		wg.Add(1)
		go func(i int, address string) {
			defer wg.Done()
			limit <- struct{}{}
			defer func() { <-limit }()
			results[i] = tcpPing(address)
		}(i, net.JoinHostPort(s.Host, strconv.Itoa(s.Port)))
	}
	wg.Wait()
	return marshal(results)
}

func pingableOverTCP(s *server) bool {
	switch s.Protocol {
	case "hysteria2", "tuic", "wireguard", "amneziawg":
		return false
	case "naive":
		return !s.QUIC
	}
	return s.Port != 0
}

func tcpPing(address string) int {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	start := time.Now()
	conn, err := protectedDialer().DialContext(ctx, "tcp", address)
	if err != nil {
		return -1
	}
	elapsed := time.Since(start)
	conn.Close()
	if ms := int(elapsed.Milliseconds()); ms > 0 {
		return ms
	}
	return 1
}
