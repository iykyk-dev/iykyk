package iykyk

import (
	"context"
	"errors"
	"net"
	"net/http"
	"strconv"
	"time"

	"golang.org/x/net/proxy"
)

// CheckTunnel tests the running tunnel through the profile's local check
// inbound and returns the exit IP, its country and how long the request took.
// The app calls it after connecting, when the network changes and when the
// screen turns on; if it fails while the internet works, the app reconnects.

const checkURL = "https://www.cloudflare.com/cdn-cgi/trace"

func CheckTunnel(port int, user, pass string) (*TunnelInfo, error) {
	socks, err := proxy.SOCKS5("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)),
		&proxy.Auth{User: user, Password: pass}, &net.Dialer{Timeout: 5 * time.Second})
	if err != nil {
		return nil, err
	}
	dialer, ok := socks.(proxy.ContextDialer)
	if !ok {
		return nil, errors.New("socks dialer")
	}
	client := &http.Client{
		Timeout: 8 * time.Second,
		Transport: &http.Transport{
			DialContext:       dialer.DialContext,
			DisableKeepAlives: true,
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, checkURL, nil)
	if err != nil {
		return nil, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("check: HTTP " + strconv.Itoa(resp.StatusCode))
	}
	info := parseTrace(resp.Body)
	info.Latency = int(time.Since(start).Milliseconds())
	return info, nil
}
