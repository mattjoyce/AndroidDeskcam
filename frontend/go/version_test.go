package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTheVersionIsTheRepositorysVersion(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "VERSION"))
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.TrimSpace(string(raw)); got != version {
		t.Fatalf("VERSION says %s and the CLI says %s; change version.go with it", got, version)
	}
}
