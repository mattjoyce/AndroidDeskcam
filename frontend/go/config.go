package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Config is where the binary looks for its target, its key and its captures.
//
// The resolution order is the one the shell version used, because people have these in
// their profiles: an explicit flag, then the environment, then the saved file, then
// loopback.
type Config struct {
	URL     string
	Token   string
	Shots   string
	Timeout time.Duration
}

func configDir() string {
	if x := os.Getenv("XDG_CONFIG_HOME"); x != "" {
		return filepath.Join(x, "deskcam")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ".deskcam"
	}
	return filepath.Join(home, ".config", "deskcam")
}

func urlFile() string   { return filepath.Join(configDir(), "url") }
func tokenFile() string { return filepath.Join(configDir(), "token") }

func readTrimmed(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func writeConfig(path, value string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	// The token is a credential, so it is not world readable even for a moment.
	return os.WriteFile(path, []byte(value+"\n"), 0o600)
}

func loadConfig(urlFlag string) Config {
	c := Config{Timeout: 30 * time.Second}

	switch {
	case urlFlag != "":
		c.URL = urlFlag
	case os.Getenv("DESKCAM_URL") != "":
		c.URL = os.Getenv("DESKCAM_URL")
	default:
		c.URL = readTrimmed(urlFile())
	}
	if c.URL == "" {
		c.URL = "http://127.0.0.1:8080"
	}
	c.URL = strings.TrimRight(c.URL, "/")

	c.Token = os.Getenv("DESKCAM_TOKEN")
	if c.Token == "" {
		c.Token = readTrimmed(tokenFile())
	}

	c.Shots = os.Getenv("DESKCAM_SHOTS")
	if c.Shots == "" {
		if wd, err := os.Getwd(); err == nil {
			c.Shots = wd
		} else {
			c.Shots = "."
		}
	}

	if s := os.Getenv("DESKCAM_TIMEOUT"); s != "" {
		if n, err := strconv.Atoi(s); err == nil && n > 0 {
			c.Timeout = time.Duration(n) * time.Second
		}
	}
	return c
}

// defaultTestTimeout keeps the tests from hanging if a stub misbehaves.
const defaultTestTimeout = 10 * time.Second
