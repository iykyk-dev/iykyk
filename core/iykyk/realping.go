package iykyk

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/xtls/xray-core/core"
	"golang.org/x/net/proxy"
)

// The real ping: an HTTPS request through each server, with the server's own
// engine started just for the test (no TUN). Unlike a TCP ping, it shows
// whether traffic actually flows, which is what matters when DPI lets the
// connection open but breaks the tunnel.

const realPingURL = "https://www.gstatic.com/generate_204"

// testXrayLogLevel is raised by tests to see why a test connection fails.
var testXrayLogLevel = "none"

// RealPingServers returns a JSON array of milliseconds, one per link
// (linksJSON is a JSON array of share links): -1 = not working,
// -2 = not measurable this way (Mieru, AmneziaWG). If an engine cannot be
// started for the test, the TCP ping is reported instead.
func RealPingServers(linksJSON string, optionsJSON string) (string, error) {
	var links []string
	if err := json.Unmarshal([]byte(linksJSON), &links); err != nil {
		return "", err
	}
	opts, err := parseOptions(optionsJSON)
	if err != nil {
		return "", err
	}
	opts.BypassRussia = false // the test request must go through the server
	results := make([]int, len(links))
	var wg sync.WaitGroup
	limit := make(chan struct{}, 6)
	for i, link := range links {
		wg.Add(1)
		go func() {
			defer wg.Done()
			limit <- struct{}{}
			defer func() { <-limit }()
			results[i] = realPing(link, opts)
		}()
	}
	wg.Wait()
	return marshal(results)
}

type dialFunc func(ctx context.Context, network, address string) (net.Conn, error)

func realPing(link string, opts options) int {
	s, err := parseLink(link)
	if err != nil {
		return -1
	}
	var dial dialFunc
	var cleanup func()
	switch selectEngine(s, opts) {
	case engineXray:
		dial, cleanup, err = xrayTestDialer(s, opts)
	case engineSingBox:
		dial, cleanup, err = singBoxTestDialer(s, opts)
	default:
		return -2
	}
	if err != nil {
		if pingableOverTCP(s) {
			return tcpPing(net.JoinHostPort(s.Host, strconv.Itoa(s.Port)))
		}
		return -1
	}
	defer cleanup()
	return timedRequest(dial)
}

func timedRequest(dial dialFunc) int {
	transport := &http.Transport{
		DialContext:         dial,
		TLSHandshakeTimeout: 6 * time.Second,
		DisableKeepAlives:   true,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 8 * time.Second}
	start := time.Now()
	resp, err := client.Head(realPingURL)
	if err != nil {
		return -1
	}
	resp.Body.Close()
	if resp.StatusCode >= 500 {
		return -1
	}
	if ms := int(time.Since(start).Milliseconds()); ms > 0 {
		return ms
	}
	return 1
}

// xrayTestDialer starts an Xray instance with only the server's outbound and
// dials through its dispatcher.
func xrayTestDialer(s *server, opts options) (dialFunc, func(), error) {
	// Same path as a real connection: a loopback SOCKS inbound in front of the
	// outbound. Dialing the instance directly (core.Dial) hangs with XTLS Vision.
	port, err := freeLoopbackPort()
	if err != nil {
		return nil, nil, err
	}
	user, pass := randomToken(), randomToken()
	var configJSON string
	if s.Protocol == "xray-json" || s.Protocol == "xray-balancer" {
		configJSON, err = xrayConfigFromJSON(s.Raw, port, user, pass, opts.Fragment)
	} else {
		var out jsonObject
		if out, err = xrayOutbound(s, opts); err == nil {
			configJSON, err = marshal(jsonObject{
				"log":       jsonObject{"loglevel": testXrayLogLevel},
				"inbounds":  []any{xraySocksInbound("socks-in", port, user, pass)},
				"outbounds": []any{out},
			})
		}
	}
	if err != nil {
		return nil, nil, err
	}
	instance, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		return nil, nil, err
	}
	socks, err := proxy.SOCKS5("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)),
		&proxy.Auth{User: user, Password: pass}, &net.Dialer{})
	if err != nil {
		_ = instance.Close()
		return nil, nil, err
	}
	dial := func(ctx context.Context, network string, address string) (net.Conn, error) {
		return socks.(proxy.ContextDialer).DialContext(ctx, "tcp", address)
	}
	return dial, func() { _ = instance.Close() }, nil
}

// singBoxTestDialer starts a sing-box instance without inbounds and dials
// through the server's outbound (or WireGuard endpoint).
func singBoxTestDialer(s *server, opts options) (dialFunc, func(), error) {
	outbounds, endpoints, err := singBoxProxy(s, opts)
	if err != nil {
		return nil, nil, err
	}
	config := jsonObject{
		"log": jsonObject{"disabled": true},
		"dns": jsonObject{"servers": []any{
			jsonObject{"type": "udp", "tag": "direct-dns", "server": directDNSServer},
		}},
		"outbounds": append(outbounds, jsonObject{"type": "direct", "tag": "direct"}),
		"route":     jsonObject{"default_domain_resolver": "direct-dns", "final": "proxy"},
	}
	if len(endpoints) > 0 {
		config["endpoints"] = endpoints
	}
	text, err := marshal(config)
	if err != nil {
		return nil, nil, err
	}
	ctx := box.Context(context.Background(), include.InboundRegistry(), include.OutboundRegistry(),
		include.EndpointRegistry(), include.DNSTransportRegistry(), include.ServiceRegistry(),
		include.CertificateProviderRegistry())
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(text))
	if err != nil {
		return nil, nil, err
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return nil, nil, err
	}
	if err := instance.Start(); err != nil {
		instance.Close()
		return nil, nil, err
	}
	proxy, ok := instance.Outbound().Outbound("proxy")
	if !ok {
		instance.Close()
		return nil, nil, net.UnknownNetworkError("proxy outbound missing")
	}
	dial := func(ctx context.Context, _ string, address string) (net.Conn, error) {
		return proxy.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddr(address))
	}
	return dial, func() { _ = instance.Close() }, nil
}
