// ==============================================================================
// MasterDnsVPN
// Author: MasterkinG32
// Github: https://github.com/masterking32
// Year: 2026
// ==============================================================================

package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"masterdnsvpn-go/internal/client"
	"masterdnsvpn-go/internal/config"
	"masterdnsvpn-go/internal/runtimepath"
	"masterdnsvpn-go/internal/version"
)

func waitForExitInput() {
	_, _ = fmt.Fprint(os.Stderr, "Press Enter to exit...")
	reader := bufio.NewReader(os.Stdin)
	_, _ = reader.ReadString('\n')
}

func main() {
	configPath := flag.String("config", "client_config.toml", "Path to client configuration file")
	logPath := flag.String("log", "", "Path to log file (optional)")
	resolversPath := flag.String("resolvers", "", "Path to resolver file override (optional)")
	versionFlag := flag.Bool("version", false, "Print version and exit")
	configFlags, err := config.NewClientConfigFlagBinder(flag.CommandLine)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Client flag setup failed: %v\n", err)
		os.Exit(2)
	}
	flag.Parse()

	if *versionFlag {
		fmt.Printf("MasterDnsVPN Client Version: %s\n", version.GetVersion())
		return
	}

	resolvedConfigPath := runtimepath.Resolve(*configPath)
	overrides := configFlags.Overrides()
	if *resolversPath != "" {
		resolvedResolversPath := runtimepath.Resolve(*resolversPath)
		overrides.ResolversFilePath = &resolvedResolversPath
	}

	app, err := client.Bootstrap(resolvedConfigPath, *logPath, overrides)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Client startup failed: %v\n", err)
		waitForExitInput()
		os.Exit(1)
	}

	app.PrintBanner()

	log := app.Log()
	if log != nil {
		log.Infof("\U0001F680 <green>MasterDnsVPN Client Started</green>")
		log.Infof("\U0001F4C4 <green>Configuration loaded from: <cyan>%s</cyan></green>", resolvedConfigPath)
		log.Infof("\U0001F5C2  <green>Connection Catalog: <cyan>%d</cyan> domain-resolver pairs</green>", len(app.Connections()))
	}

	// Wait for termination signal
	sigCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := app.Run(sigCtx); err != nil {
		if log != nil {
			log.Errorf("Runtime error: %v", err)
		}
	}

	if log != nil {
		log.Infof("\U0001F6D1 <red>Shutting down...</red>")
	}
}
