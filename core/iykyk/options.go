package iykyk

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
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
	// Fragment splits TLS handshakes into small pieces to get past DPI that
	// blocks by server name. Applies to TCP-based TLS/REALITY connections.
	Fragment bool `json:"fragment"`
	// RemoteDNS resolves names for traffic through the tunnel: "cloudflare"
	// (default), "google", "quad9", "adguard" (blocks ads), or a DoH URL.
	RemoteDNS string `json:"remoteDNS"`
	// DirectDNS resolves proxy servers and bypassed sites: "yandex" (default),
	// "cloudflare", "google", or an IP address (plain DNS).
	DirectDNS string `json:"directDNS"`
	// Routing is the provider's routing from the subscription (Iykyk-Routing).
	Routing *providerRouting `json:"routing"`
	// PreferXray runs VLESS/VMess/Trojan/Shadowsocks in Xray even when sing-box
	// could handle them natively (a fallback setting in case of trouble).
	PreferXray bool `json:"preferXray"`
	// LogDir receives singbox.log and xray.log (warnings and errors) for
	// "Send a report to support". Empty: no log files.
	LogDir string `json:"logDir"`

	// Desktop only. AppMode "only" sends just the programs in Apps through the
	// VPN, "except" sends everything but them; "" or "all" sends everything.
	// Apps are process names as Windows reports them ("chrome.exe").
	AppMode string   `json:"appMode"`
	Apps    []string `json:"apps"`
	// SystemProxyPort > 0 replaces the TUN adapter with an HTTP/SOCKS proxy on
	// 127.0.0.1:port, which the app then sets as the Windows system proxy.
	SystemProxyPort int `json:"systemProxyPort"`

	// The local check inbound (see check.go); set by BuildProfileWithOptions.
	checkPort            int
	checkUser, checkPass string
}

func (o options) logFile(name string) string {
	if o.LogDir == "" {
		return ""
	}
	return filepath.Join(o.LogDir, name)
}

// xrayLog is Xray's log section: warnings and errors, to a file if there is one.
func xrayLog(file string) jsonObject {
	log := jsonObject{"loglevel": "warning"}
	if file != "" {
		log["error"] = file
		log["access"] = "none"
	}
	return log
}

// providerRouting lists rules in the Xray template notation:
// "domain:example.com", "full:host.example.com", "keyword:ads", "regexp:...",
// a bare domain (= domain:), an IP or CIDR, and "geosite:category-ru" /
// "geoip:ru" (backed by the rule sets shipped with the app).
type providerRouting struct {
	Direct []string `json:"direct"`
	Proxy  []string `json:"proxy"`
	Block  []string `json:"block"`
}

func (o options) remoteDNSServer() jsonObject {
	server := jsonObject{"type": "https", "tag": "remote-dns", "detour": "proxy"}
	switch v := strings.ToLower(strings.TrimSpace(o.RemoteDNS)); {
	case v == "" || v == "cloudflare":
		server["server"] = remoteDNSServer
	case v == "google":
		server["server"] = "8.8.8.8"
	case v == "quad9":
		server["server"] = "9.9.9.9"
	case v == "adguard":
		server["server"] = "dns.adguard-dns.com"
		server["domain_resolver"] = "direct-dns"
	case strings.HasPrefix(v, "https://"):
		u, err := url.Parse(strings.TrimSpace(o.RemoteDNS))
		if err != nil || u.Host == "" {
			server["server"] = remoteDNSServer
			break
		}
		server["server"] = u.Hostname()
		if u.Port() != "" {
			port, _ := strconv.Atoi(u.Port())
			server["server_port"] = port
		}
		if u.Path != "" && u.Path != "/" {
			server["path"] = u.Path
		}
		if net.ParseIP(u.Hostname()) == nil {
			server["domain_resolver"] = "direct-dns"
		}
	default:
		server["server"] = remoteDNSServer
	}
	return server
}

func (o options) directDNSServer() jsonObject {
	server := jsonObject{"type": "udp", "tag": "direct-dns"}
	switch v := strings.ToLower(strings.TrimSpace(o.DirectDNS)); {
	case v == "cloudflare":
		server["server"] = "1.1.1.1"
	case v == "google":
		server["server"] = "8.8.8.8"
	case net.ParseIP(v) != nil:
		server["server"] = v
	default:
		server["server"] = directDNSServer
	}
	return server
}

// routeRules turns the provider's routing into sing-box DNS and route rules.
// Blocking comes first, then explicit proxying, then direct.
func (r *providerRouting) rules(ruleSetDir string) (dnsRules, routeRules []any, usesRuleSets bool) {
	if r == nil {
		return nil, nil, false
	}
	for _, group := range []struct {
		entries []string
		rule    jsonObject
	}{
		{r.Block, jsonObject{"action": "reject"}},
		{r.Proxy, jsonObject{"outbound": "proxy"}},
		{r.Direct, jsonObject{"outbound": "direct"}},
	} {
		match, ruleSets := matchFromEntries(group.entries, ruleSetDir)
		if len(match) == 0 && len(ruleSets) == 0 {
			continue
		}
		// Domain/IP lists and rule sets are separate rules: sing-box ANDs fields
		// of different kinds inside one rule.
		for _, m := range splitMatch(match) {
			rule := jsonObject{}
			for k, v := range m {
				rule[k] = v
			}
			for k, v := range group.rule {
				rule[k] = v
			}
			routeRules = append(routeRules, rule)
		}
		if len(ruleSets) > 0 {
			usesRuleSets = true
			rule := jsonObject{"rule_set": ruleSets}
			for k, v := range group.rule {
				rule[k] = v
			}
			routeRules = append(routeRules, rule)
		}
		if group.rule["outbound"] == "direct" {
			// Directly reached names resolve with the direct DNS server.
			for _, m := range splitMatch(match) {
				if _, isIP := m["ip_cidr"]; isIP {
					continue
				}
				rule := jsonObject{"server": "direct-dns"}
				for k, v := range m {
					rule[k] = v
				}
				dnsRules = append(dnsRules, rule)
			}
		}
	}
	return dnsRules, routeRules, usesRuleSets
}

// matchFromEntries groups entries by sing-box match field.
func matchFromEntries(entries []string, ruleSetDir string) (map[string][]string, []string) {
	match := map[string][]string{}
	var ruleSets []string
	for _, entry := range entries {
		entry = strings.TrimSpace(entry)
		// IPs and CIDRs first: IPv6 addresses contain colons too.
		if _, _, err := net.ParseCIDR(entry); err == nil || net.ParseIP(entry) != nil {
			match["ip_cidr"] = append(match["ip_cidr"], entry)
			continue
		}
		kind, value, hasKind := strings.Cut(entry, ":")
		if !hasKind || strings.Contains(kind, ".") || strings.Contains(kind, "/") {
			kind, value = "", entry
		}
		switch strings.ToLower(kind) {
		case "domain", "":
			if _, _, err := net.ParseCIDR(value); err == nil {
				match["ip_cidr"] = append(match["ip_cidr"], value)
			} else if ip := net.ParseIP(value); ip != nil {
				match["ip_cidr"] = append(match["ip_cidr"], value)
			} else if value != "" {
				match["domain_suffix"] = append(match["domain_suffix"], strings.TrimPrefix(value, "*."))
			}
		case "full":
			match["domain"] = append(match["domain"], value)
		case "keyword":
			match["domain_keyword"] = append(match["domain_keyword"], value)
		case "regexp":
			match["domain_regex"] = append(match["domain_regex"], value)
		case "geosite":
			if ruleSetDir != "" && strings.EqualFold(value, "category-ru") {
				ruleSets = append(ruleSets, "geosite-ru")
			}
		case "geoip":
			if ruleSetDir != "" && strings.EqualFold(value, "ru") {
				ruleSets = append(ruleSets, "geoip-ru")
			}
		}
	}
	return match, ruleSets
}

// splitMatch keeps domain fields together (sing-box ORs them) and IPs apart.
func splitMatch(match map[string][]string) []jsonObject {
	var result []jsonObject
	domains := jsonObject{}
	for _, key := range []string{"domain", "domain_suffix", "domain_keyword", "domain_regex"} {
		if values := match[key]; len(values) > 0 {
			domains[key] = values
		}
	}
	if len(domains) > 0 {
		result = append(result, domains)
	}
	if ips := match["ip_cidr"]; len(ips) > 0 {
		result = append(result, jsonObject{"ip_cidr": ips})
	}
	return result
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

// appRules routes programs by name (desktop): the rule goes before every other
// so a program the user listed is never caught by a site rule first.
func (o options) appRules() []any {
	var names []string
	for _, name := range o.Apps {
		if name = strings.TrimSpace(name); name != "" {
			names = append(names, name)
		}
	}
	if len(names) == 0 {
		return nil
	}
	switch o.AppMode {
	case "except":
		return []any{jsonObject{"process_name": names, "outbound": "direct"}}
	case "only":
		// Everything else goes direct; the listed programs continue to the other rules.
		return []any{jsonObject{"process_name": names, "invert": true, "outbound": "direct"}}
	}
	return nil
}
