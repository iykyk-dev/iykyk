//go:build !windows

package main

import (
	"errors"
	"os"
	"path/filepath"
)

// Other systems: console mode only (used for development and CI).

func dataDir() string { return filepath.Join(os.TempDir(), "iykyk") }

func isService() bool { return false }

func runService() error { return errors.New("only on Windows") }

func installService() error { return errors.New("only on Windows") }

func uninstallService() error { return errors.New("only on Windows") }
