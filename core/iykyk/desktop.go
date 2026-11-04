//go:build !android

package iykyk

import (
	"encoding/json"
	"errors"
	"sync"
	"sync/atomic"

	box "github.com/sagernet/sing-box"
)

// Desktop (Windows): no platform layer as on Android. sing-box opens the TUN
// itself (Wintun, embedded in sing-tun) and routes everything into it. Our own
// engines' sockets (Xray, Mieru, AmneziaWG, subscription and ping requests)
// are bound to the physical interface that sing-box's interface monitor
// reports, so they bypass the TUN, which is what VpnService.protect does on
// Android.

// TunName is the name of the VPN adapter shown in Windows.
const TunName = "IYKYK"

var (
	desktopMu  sync.Mutex
	desktopBox atomic.Pointer[box.Box]
)

// StartDesktop connects with the profile: engines first, then sing-box with the TUN.
func StartDesktop(profile *Profile) error {
	if err := StopDesktop(); err != nil {
		return err
	}
	desktopMu.Lock()
	defer desktopMu.Unlock()
	config, err := desktopSingBoxConfig(profile.SingBoxConfig)
	if err != nil {
		return err
	}
	bind := func(fd uintptr) error { return bindToInterface(fd, desktopInterfaceIndex()) }
	socketBinder.Store(&bind)
	if err := StartEngines(profile); err != nil {
		socketBinder.Store(nil)
		return err
	}
	instance, err := newBox(config)
	if err != nil {
		_ = StopEngines()
		socketBinder.Store(nil)
		return err
	}
	// Published before Start: once the TUN is up, traffic reaches Xray at
	// once, and its sockets must already find the interface to bind to.
	desktopBox.Store(instance)
	if err := instance.Start(); err != nil {
		desktopBox.Store(nil)
		_ = instance.Close()
		_ = StopEngines()
		socketBinder.Store(nil)
		return err
	}
	return nil
}

// StopDesktop disconnects.
func StopDesktop() error {
	desktopMu.Lock()
	defer desktopMu.Unlock()
	var err error
	if instance := desktopBox.Swap(nil); instance != nil {
		err = instance.Close()
	}
	err = errors.Join(err, StopEngines())
	socketBinder.Store(nil)
	return err
}

// IsDesktopRunning reports whether the desktop tunnel is up.
func IsDesktopRunning() bool { return desktopBox.Load() != nil }

// desktopInterfaceIndex is the physical default interface sing-box sees (its
// monitor ignores our own TUN), or 0 before the tunnel is up.
func desktopInterfaceIndex() int {
	instance := desktopBox.Load()
	if instance == nil {
		return 0
	}
	monitor := instance.Network().InterfaceMonitor()
	if monitor == nil {
		return 0
	}
	if iface := monitor.DefaultInterface(); iface != nil {
		return iface.Index
	}
	return 0
}

// desktopSingBoxConfig adapts the app's config to the desktop: a named adapter.
func desktopSingBoxConfig(text string) (string, error) {
	var config map[string]any
	if err := json.Unmarshal([]byte(text), &config); err != nil {
		return "", err
	}
	inbounds, _ := config["inbounds"].([]any)
	for _, in := range inbounds {
		if inbound, ok := in.(map[string]any); ok && inbound["type"] == "tun" {
			inbound["interface_name"] = TunName
		}
	}
	out, err := json.Marshal(config)
	return string(out), err
}
