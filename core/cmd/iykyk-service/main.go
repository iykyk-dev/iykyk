// iykyk-service is the IYKYK core for Windows: the same Go core as on Android
// (sing-box, Xray, Mieru, AmneziaWG), run as a Windows service so the TUN
// (which needs administrator rights) works without a UAC prompt on every
// connect. The desktop app talks to it over a local HTTP API with a token.
//
//	iykyk-service install     register and start the service (installer, as admin)
//	iykyk-service uninstall   stop and remove it
//	iykyk-service run         run in the console (debugging)
package main

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
)

func main() {
	command := ""
	if len(os.Args) > 1 {
		command = os.Args[1]
	}
	var err error
	switch command {
	case "install":
		err = installService()
	case "uninstall":
		err = uninstallService()
	case "run":
		err = runConsole()
	case "service", "":
		if isService() {
			err = runService()
		} else {
			err = runConsole()
		}
	default:
		err = fmt.Errorf("unknown command %q (install, uninstall, run)", command)
	}
	if err != nil {
		log.Fatal(err)
	}
}

func runConsole() error {
	srv, err := newServer()
	if err != nil {
		return err
	}
	log.Printf("IYKYK core API on %s, token in %s", apiAddress, srv.tokenFile)
	return srv.listen()
}

// installDir is where the executable and the rule sets are.
func installDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}
