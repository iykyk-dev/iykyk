package iykyk

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
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

	clashController string
	clashSecret     string
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

// retag renames "tag" and "detour" fields in sing-box/Xray objects, so several
// servers' outbounds can live in one config.
func retag(objects []any, rename map[string]string) {
	for _, o := range objects {
		m, ok := o.(jsonObject)
		if !ok {
			continue
		}
		for _, key := range []string{"tag", "detour"} {
			if v, ok := m[key].(string); ok {
				if renamed, ok := rename[v]; ok {
					m[key] = renamed
				}
			}
		}
	}
}

// BuildAutoProfile builds one profile over several servers (linksJSON is a JSON
// array of share links). sing-box tests each server with a real request through
// it every few minutes and uses the fastest working one, switching when a
// server stops working. Mieru and AmneziaWG servers are left out of auto mode.
func BuildAutoProfile(linksJSON string, optionsJSON string) (*Profile, error) {
	opts, err := parseOptions(optionsJSON)
	if err != nil {
		return nil, err
	}
	var links []string
	if err := json.Unmarshal([]byte(linksJSON), &links); err != nil {
		return nil, fmt.Errorf("invalid server list: %w", err)
	}

	profile := &Profile{Name: "Auto", Protocol: "auto", autoNames: map[string]string{}}
	var (
		servers       []*server
		outbounds     []any
		endpoints     []any
		groupTags     []string
		xrayInbounds  []any
		xrayOutbounds []any
		xrayRules     []any
		xrayUser      = randomToken()
		xrayPass      = randomToken()
	)
	for _, link := range links {
		s, err := parseLink(link)
		if err != nil {
			continue
		}
		tag := "s" + strconv.Itoa(len(groupTags))
		switch engineFor(s) {
		case engineXray:
			port, err := freeLoopbackPort()
			if err != nil {
				return nil, err
			}
			out, err := xrayOutbound(s)
			if err != nil {
				continue
			}
			retag([]any{out}, map[string]string{"proxy": tag + "-out"})
			xrayOutbounds = append(xrayOutbounds, out)
			xrayInbounds = append(xrayInbounds, xraySocksInbound(tag+"-in", port, xrayUser, xrayPass))
			xrayRules = append(xrayRules, jsonObject{
				"type": "field", "inboundTag": []string{tag + "-in"}, "outboundTag": tag + "-out",
			})
			socks := loopbackSocks(port, xrayUser, xrayPass)
			socks["tag"] = tag
			outbounds = append(outbounds, socks)
		case engineSingBox:
			outs, eps, err := singBoxProxy(s)
			if err != nil {
				continue
			}
			rename := map[string]string{"proxy": tag, "shadowtls": tag + "-stls"}
			retag(outs, rename)
			retag(eps, rename)
			outbounds = append(outbounds, outs...)
			endpoints = append(endpoints, eps...)
		default:
			continue // sidecar engines run one server at a time
		}
		servers = append(servers, s)
		groupTags = append(groupTags, tag)
		profile.autoNames[tag] = s.Name
	}
	if len(groupTags) == 0 {
		return nil, errors.New("no servers usable in auto mode")
	}

	if len(xrayOutbounds) > 0 {
		profile.UsesXray = true
		xrayConfig := jsonObject{
			"log":       jsonObject{"loglevel": "warning"},
			"inbounds":  xrayInbounds,
			"outbounds": xrayOutbounds,
			"routing":   jsonObject{"rules": xrayRules},
		}
		if profile.XrayConfig, err = marshal(xrayConfig); err != nil {
			return nil, err
		}
	}

	urltest := jsonObject{
		"type":                        "urltest",
		"tag":                         "proxy",
		"outbounds":                   groupTags,
		"url":                         "https://www.gstatic.com/generate_204",
		"interval":                    "3m",
		"tolerance":                   80,
		"idle_timeout":                "30m",
		"interrupt_exist_connections": false,
	}
	outbounds = append([]any{urltest}, outbounds...)

	port, err := freeLoopbackPort()
	if err != nil {
		return nil, err
	}
	profile.clashController = net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	profile.clashSecret = randomToken()
	opts.clashController, opts.clashSecret = profile.clashController, profile.clashSecret

	if profile.SingBoxConfig, err = marshal(singBoxConfig(servers, outbounds, endpoints, opts)); err != nil {
		return nil, err
	}
	profile.Address = strconv.Itoa(len(groupTags))
	return profile, nil
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
	}
}

// --- Auto mode status -----------------------------------------------------------

var (
	runningMu      sync.Mutex
	runningProfile *Profile
)

func setRunningProfile(p *Profile) {
	runningMu.Lock()
	runningProfile = p
	runningMu.Unlock()
}

// AutoStatus reports which server auto mode is using and the last measured delay
// of each, as JSON: {"now":"Server name","delays":{"Server name":123,...}}.
// A delay of 0 means the server failed its last test. Empty if auto mode is off.
func AutoStatus() (string, error) {
	runningMu.Lock()
	p := runningProfile
	runningMu.Unlock()
	if p == nil || !p.IsAuto() {
		return "", nil
	}
	var proxies struct {
		Proxies map[string]struct {
			Now     string `json:"now"`
			History []struct {
				Delay int `json:"delay"`
			} `json:"history"`
		} `json:"proxies"`
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+p.clashController+"/proxies", nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+p.clashSecret)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if err := json.Unmarshal(body, &proxies); err != nil {
		return "", err
	}
	status := struct {
		Now    string         `json:"now"`
		Delays map[string]int `json:"delays"`
	}{Delays: map[string]int{}}
	status.Now = p.autoNames[proxies.Proxies["proxy"].Now]
	for tag, name := range p.autoNames {
		if history := proxies.Proxies[tag].History; len(history) > 0 {
			status.Delays[name] = history[len(history)-1].Delay
		}
	}
	return marshal(status)
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
