package iykyk

import (
	"errors"
	"io"
	"sync"
)

var (
	enginesMu sync.Mutex
	sidecar   io.Closer
)

// StartEngines starts whatever in-process engine the profile needs before
// sing-box: Xray, Mieru or AmneziaWG. It is a no-op for protocols sing-box
// runs itself. Call it after SetSocketProtector and before the TUN exists.
func StartEngines(profile *Profile) error {
	if err := StopEngines(); err != nil {
		return err
	}
	if profile.UsesXray {
		return StartXray(profile.XrayConfig)
	}
	if profile.sidecar == "" {
		return nil
	}
	enginesMu.Lock()
	defer enginesMu.Unlock()
	var engine io.Closer
	var err error
	switch profile.sidecar {
	case "mieru":
		engine, err = startMieru(profile.sidecarSrv.Raw, profile.sidecarPort, profile.sidecarUser, profile.sidecarPass)
	case "amneziawg":
		engine, err = startAmnezia(profile.sidecarSrv, profile.sidecarPort, profile.sidecarUser, profile.sidecarPass)
	default:
		err = errors.New("unknown engine " + profile.sidecar)
	}
	if err != nil {
		return err
	}
	sidecar = engine
	return nil
}

// StopEngines stops every in-process engine.
func StopEngines() error {
	err := StopXray()
	enginesMu.Lock()
	defer enginesMu.Unlock()
	if sidecar != nil {
		err = errors.Join(err, sidecar.Close())
		sidecar = nil
	}
	return err
}
