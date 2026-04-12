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
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
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

func printClientUsage(fs *flag.FlagSet) {
	bin := filepath.Base(os.Args[0])
	if bin == "" || bin == "." || strings.Contains(bin, "go-build") || strings.HasPrefix(bin, "main") {
		bin = "masterdnsvpn-client"
	}

	fmt.Fprintf(fs.Output(), "MasterDnsVPN Client - A high-performance DNS-based VPN Tunnel\n\n")
	fmt.Fprintf(fs.Output(), "Usage:\n")
	fmt.Fprintf(fs.Output(), "  %s [flags]\n\n", bin)
	fmt.Fprintf(fs.Output(), "Examples:\n")
	fmt.Fprintf(fs.Output(), "  %s -config client_config.toml\n", bin)
	fmt.Fprintf(fs.Output(), "  %s -config ./client_config.toml -resolvers ./client_resolvers.txt\n", bin)
	fmt.Fprintf(fs.Output(), "  %s -log ./client.log -version\n", bin)
	fmt.Fprintf(fs.Output(), "  %s -config ./client_config.toml -d domain1.com,domain2.com -k my-secret-key\n\n", bin)
	fmt.Fprintf(fs.Output(), "Flags:\n")
	fs.PrintDefaults()
}

type clientCLIOptions struct {
	configPath    string
	jsonPath      string
	jsonBase64    string
	logPath       string
	resolversPath string
	showVersion   bool
	showHelp      bool
	domainsShort  string
	keyShort      string
}

func newClientFlagSet(output io.Writer) (*flag.FlagSet, *clientCLIOptions, *config.ClientConfigFlagBinder, error) {
	fs := flag.NewFlagSet("masterdnsvpn-client", flag.ContinueOnError)
	fs.SetOutput(output)

	opts := &clientCLIOptions{}
	fs.Usage = func() {
		printClientUsage(fs)
	}

	fs.StringVar(&opts.configPath, "config", "client_config.toml", "Path to client configuration file")
	fs.StringVar(&opts.configPath, "c", "client_config.toml", "Alias for -config")
	fs.StringVar(&opts.jsonPath, "json", "", "Path to client JSON configuration file")
	fs.StringVar(&opts.jsonPath, "j", "", "Alias for -json")
	fs.StringVar(&opts.jsonBase64, "json_base64", "", "Load client JSON configuration from base64")
	fs.StringVar(&opts.jsonBase64, "json-base64", "", "Alias for -json_base64")

	fs.StringVar(&opts.logPath, "log", "", "Path to log file (optional)")
	fs.StringVar(&opts.logPath, "l", "", "Alias for -log")

	fs.StringVar(&opts.resolversPath, "resolvers", "", "Path to resolver file override (optional)")
	fs.StringVar(&opts.resolversPath, "r", "", "Alias for -resolvers")

	fs.BoolVar(&opts.showVersion, "version", false, "Print version and exit")
	fs.BoolVar(&opts.showVersion, "v", false, "Alias for -version")

	fs.BoolVar(&opts.showHelp, "help", false, "Show help and exit")
	fs.BoolVar(&opts.showHelp, "h", false, "Alias for -help")

	fs.StringVar(&opts.domainsShort, "d", "", "Alias for -domains (comma separated)")
	fs.StringVar(&opts.keyShort, "k", "", "Alias for -encryption-key")
	fs.StringVar(&opts.keyShort, "key", "", "Compatibility alias for -encryption-key")

	configFlags, err := config.NewClientConfigFlagBinder(fs)
	if err != nil {
		return nil, nil, nil, err
	}

	return fs, opts, configFlags, nil
}

func parseClientCLIArgs(args []string, output io.Writer) (*clientCLIOptions, config.ClientConfigOverrides, error) {
	fs, opts, configFlags, err := newClientFlagSet(output)
	if err != nil {
		return nil, config.ClientConfigOverrides{}, err
	}
	if err := fs.Parse(args); err != nil {
		return nil, config.ClientConfigOverrides{}, err
	}

	if opts.showHelp {
		return opts, configFlags.Overrides(), nil
	}

	overrides := configFlags.Overrides()
	if opts.jsonPath != "" && opts.jsonBase64 != "" {
		return nil, config.ClientConfigOverrides{}, fmt.Errorf("only one of -json and -json_base64 can be used")
	}
	if opts.resolversPath != "" {
		resolvedResolversPath := runtimepath.Resolve(opts.resolversPath)
		overrides.ResolversFilePath = &resolvedResolversPath
	}
	if opts.domainsShort != "" {
		overrides.Values["Domains"] = strings.Split(opts.domainsShort, ",")
	}
	if opts.keyShort != "" {
		overrides.Values["EncryptionKey"] = opts.keyShort
	}

	switch fs.NArg() {
	case 0:
	case 1:
		if opts.jsonPath == "" && opts.jsonBase64 == "" && (opts.configPath == "" || opts.configPath == "client_config.toml") {
			opts.configPath = fs.Arg(0)
		} else {
			return nil, config.ClientConfigOverrides{}, fmt.Errorf("unexpected positional arguments: %v", fs.Args())
		}
	case 2:
		if opts.jsonPath == "" && opts.jsonBase64 == "" && (opts.configPath == "" || opts.configPath == "client_config.toml") && opts.resolversPath == "" {
			opts.configPath = fs.Arg(0)
			resolvedResolversPath := runtimepath.Resolve(fs.Arg(1))
			overrides.ResolversFilePath = &resolvedResolversPath
		} else {
			return nil, config.ClientConfigOverrides{}, fmt.Errorf("unexpected positional arguments: %v", fs.Args())
		}
	default:
		return nil, config.ClientConfigOverrides{}, fmt.Errorf("unexpected positional arguments: %v", fs.Args())
	}

	return opts, overrides, nil
}

func main() {
	opts, overrides, err := parseClientCLIArgs(os.Args[1:], os.Stdout)
	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n\n", err)
		fs, _, _, fsErr := newClientFlagSet(os.Stdout)
		if fsErr == nil {
			fs.Usage()
		}
		os.Exit(2)
	}

	if opts.showHelp {
		fs, _, _, err := newClientFlagSet(os.Stdout)
		if err == nil {
			fs.Usage()
		}
		return
	}

	if opts.showVersion {
		fmt.Printf("MasterDnsVPN Client Version: %s\n", version.GetVersion())
		return
	}

	resolvedConfigPath := runtimepath.Resolve(opts.configPath)

	var app *client.Client
	switch {
	case opts.jsonBase64 != "":
		cfg, err := config.LoadClientConfigFromJSONBase64WithOverrides(opts.jsonBase64, overrides)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Client startup failed: %v\n", err)
			waitForExitInput()
			os.Exit(1)
		}
		app, err = client.BootstrapLoadedConfig(cfg, opts.logPath)
		resolvedConfigPath = cfg.ConfigPath
	case opts.jsonPath != "":
		app, err = client.Bootstrap(runtimepath.Resolve(opts.jsonPath), opts.logPath, overrides)
		resolvedConfigPath = runtimepath.Resolve(opts.jsonPath)
	default:
		app, err = client.Bootstrap(resolvedConfigPath, opts.logPath, overrides)
	}
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
		log.Infof("\U0001F5C2  <green>Connection Catalog: <cyan>%d</cyan> domain-resolver pairs</green>", app.Balancer().TotalCount())
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
