package client

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"masterdnsvpn-go/internal/config"
)

func TestFormatMTULogLineSupportsDomainPlaceholder(t *testing.T) {
	c := buildTestClientWithResolvers(config.ClientConfig{}, "a")
	c.nowFn = func() time.Time {
		return time.Date(2026, 4, 11, 12, 0, 0, 0, time.UTC)
	}

	line := c.formatMTULogLine(
		"Resolver {IP} ({DOMAIN}) removed at {TIME} due to {CAUSE}",
		&Connection{
			ResolverLabel:    "8.8.8.8:53",
			Domain:           "example.com",
			UploadMTUBytes:   140,
			DownloadMTUBytes: 400,
		},
		"UPLOAD_MTU",
	)

	if !strings.Contains(line, "8.8.8.8:53") {
		t.Fatalf("expected resolver placeholder to be expanded, got=%q", line)
	}
	if !strings.Contains(line, "example.com") {
		t.Fatalf("expected domain placeholder to be expanded, got=%q", line)
	}
	if !strings.Contains(line, "UPLOAD_MTU") {
		t.Fatalf("expected cause placeholder to be expanded, got=%q", line)
	}
}

func TestAppendMTURemovedServerLineWritesConfiguredFormat(t *testing.T) {
	dir := t.TempDir()
	outputPath := filepath.Join(dir, "mtu.log")

	c := buildTestClientWithResolvers(config.ClientConfig{}, "a")
	c.mtuSaveToFile = true
	c.mtuSuccessOutputPath = outputPath
	c.mtuRemovedServerLogFormat = "Resolver {IP} ({DOMAIN}) removed due to {CAUSE}"

	c.appendMTURemovedServerLine(&Connection{
		ResolverLabel: "1.1.1.1:53",
		Domain:        "example.com",
	}, "DOWNLOAD_MTU")

	raw, err := os.ReadFile(outputPath)
	if err != nil {
		t.Fatalf("ReadFile failed: %v", err)
	}
	line := strings.TrimSpace(string(raw))
	if !strings.Contains(line, "1.1.1.1:53") || !strings.Contains(line, "example.com") || !strings.Contains(line, "DOWNLOAD_MTU") {
		t.Fatalf("unexpected logged line: %q", line)
	}
}
