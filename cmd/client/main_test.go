package main

import (
	"bytes"
	"testing"
)

func TestParseClientCLIArgsAcceptsDefaultNoArgs(t *testing.T) {
	opts, overrides, err := parseClientCLIArgs(nil, &bytes.Buffer{})
	if err != nil {
		t.Fatalf("parseClientCLIArgs returned error: %v", err)
	}
	if opts.configPath != "client_config.toml" {
		t.Fatalf("unexpected default config path: got=%q want=%q", opts.configPath, "client_config.toml")
	}
	if overrides.ResolversFilePath != nil {
		t.Fatal("did not expect resolver override for default invocation")
	}
}

func TestParseClientCLIArgsAcceptsSinglePositionalConfigPath(t *testing.T) {
	opts, _, err := parseClientCLIArgs([]string{"./custom-client.toml"}, &bytes.Buffer{})
	if err != nil {
		t.Fatalf("parseClientCLIArgs returned error: %v", err)
	}
	if opts.configPath != "./custom-client.toml" {
		t.Fatalf("unexpected positional config path: got=%q want=%q", opts.configPath, "./custom-client.toml")
	}
}

func TestParseClientCLIArgsAcceptsLegacyKeyAlias(t *testing.T) {
	_, overrides, err := parseClientCLIArgs([]string{"-key", "secret-value"}, &bytes.Buffer{})
	if err != nil {
		t.Fatalf("parseClientCLIArgs returned error: %v", err)
	}
	got, ok := overrides.Values["EncryptionKey"].(string)
	if !ok || got != "secret-value" {
		t.Fatalf("unexpected encryption key override: %#v", overrides.Values["EncryptionKey"])
	}
}

func TestParseClientCLIArgsAcceptsPositionalConfigAndResolvers(t *testing.T) {
	opts, overrides, err := parseClientCLIArgs([]string{"./custom-client.toml", "./client_resolvers.txt"}, &bytes.Buffer{})
	if err != nil {
		t.Fatalf("parseClientCLIArgs returned error: %v", err)
	}
	if opts.configPath != "./custom-client.toml" {
		t.Fatalf("unexpected positional config path: got=%q want=%q", opts.configPath, "./custom-client.toml")
	}
	if overrides.ResolversFilePath == nil {
		t.Fatal("expected positional resolvers path override")
	}
}

func TestParseClientCLIArgsAcceptsJSONBase64Mode(t *testing.T) {
	opts, overrides, err := parseClientCLIArgs([]string{"-json_base64", "Zm9v"}, &bytes.Buffer{})
	if err != nil {
		t.Fatalf("parseClientCLIArgs returned error: %v", err)
	}
	if opts.jsonBase64 != "Zm9v" {
		t.Fatalf("unexpected json base64 payload: got=%q want=%q", opts.jsonBase64, "Zm9v")
	}
	if opts.jsonPath != "" {
		t.Fatalf("did not expect json file path in json base64 mode: %q", opts.jsonPath)
	}
	if overrides.ResolversFilePath != nil {
		t.Fatal("did not expect resolver override in json base64 mode")
	}
}
