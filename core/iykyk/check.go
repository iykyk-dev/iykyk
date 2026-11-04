package iykyk

import (
	"context"
	"errors"
	"io"
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

// checkFallbackURL is tried when the trace fails (e.g. the provider's routing
// blocks Cloudflare): the tunnel works, only IP and country stay unknown.
const checkFallbackURL = "https://www.gstatic.com/generate_204"

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
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			DialContext:       dialer.DialContext,
			DisableKeepAlives: true,
		},
	}
	start := time.Now()
	resp, err := get(client, checkURL)
	if err == nil && resp.StatusCode == http.StatusOK {
		defer resp.Body.Close()
		info := parseTrace(resp.Body)
		info.Latency = int(time.Since(start).Milliseconds())
		return info, nil
	}
	if resp != nil {
		resp.Body.Close()
	}
	start = time.Now()
	resp, err2 := get(client, checkFallbackURL)
	if err2 != nil {
		if err == nil {
			err = err2
		}
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, errors.New("check: HTTP " + strconv.Itoa(resp.StatusCode))
	}
	return &TunnelInfo{Latency: int(time.Since(start).Milliseconds())}, nil
}

func get(client *http.Client, url string) (*http.Response, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		cancel()
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		cancel()
		return nil, err
	}
	resp.Body = &cancelBody{ReadCloser: resp.Body, cancel: cancel}
	return resp, nil
}

type cancelBody struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (b *cancelBody) Close() error {
	err := b.ReadCloser.Close()
	b.cancel()
	return err
}
