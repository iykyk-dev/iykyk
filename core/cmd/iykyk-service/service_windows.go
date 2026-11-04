//go:build windows

package main

import (
	"os"
	"path/filepath"
	"time"

	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

const serviceName = "IYKYK"

func dataDir() string {
	base := os.Getenv("ProgramData")
	if base == "" {
		base = `C:\ProgramData`
	}
	return filepath.Join(base, "IYKYK")
}

func isService() bool {
	ok, err := svc.IsWindowsService()
	return err == nil && ok
}

type windowsService struct{}

func (windowsService) Execute(_ []string, requests <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	srv, err := newServer()
	if err != nil {
		return true, 1
	}
	done := make(chan error, 1)
	go func() { done <- srv.listen() }()
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for {
		select {
		case request := <-requests:
			switch request.Cmd {
			case svc.Interrogate:
				status <- request.CurrentStatus
			case svc.Stop, svc.Shutdown:
				status <- svc.Status{State: svc.StopPending}
				srv.shutdown()
				return false, 0
			}
		case <-done:
			srv.shutdown()
			return true, 2
		}
	}
}

func runService() error { return svc.Run(serviceName, windowsService{}) }

func installService() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	if old, err := m.OpenService(serviceName); err == nil {
		_, _ = old.Control(svc.Stop)
		time.Sleep(2 * time.Second)
		_ = old.Delete()
		old.Close()
		time.Sleep(time.Second)
	}
	s, err := m.CreateService(serviceName, exe, mgr.Config{
		DisplayName: "IYKYK VPN",
		Description: "IYKYK: VPN «Для своих». Keeps the VPN connection for the IYKYK app.",
		StartType:   mgr.StartAutomatic,
	}, "service")
	if err != nil {
		return err
	}
	defer s.Close()
	_ = s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 30 * time.Second},
	}, 24*60*60)
	return s.Start()
}

func uninstallService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(serviceName)
	if err != nil {
		return nil // not installed
	}
	defer s.Close()
	_, _ = s.Control(svc.Stop)
	time.Sleep(2 * time.Second)
	return s.Delete()
}
