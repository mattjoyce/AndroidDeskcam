package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func noEnv(string) string { return "" }

// A sidecar recorded the camera completely and nothing about who asked, so a capture in a
// temporary directory belonged to nobody once the directory's name was gone. Decision D19.
func TestASidecarSaysWhoAsked(t *testing.T) {
	project := t.TempDir()
	if err := os.Mkdir(filepath.Join(project, ".git"), 0o755); err != nil {
		t.Fatal(err)
	}
	cwd := filepath.Join(project, "firmware", "display")
	if err := os.MkdirAll(cwd, 0o755); err != nil {
		t.Fatal(err)
	}
	env := func(k string) string {
		return map[string]string{"AGENT_SESSION_ID": "20260915_4"}[k]
	}
	who := whoAsked(cwd, []string{"snap", "zoom=2", "--why", "read the OLED"}, "read the OLED", "cli", env)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-DeskCam-Provenance", `{"settings":{"zoom":2}}`)
		_, _ = w.Write([]byte("not really a jpeg"))
	}))
	defer server.Close()

	out := filepath.Join(t.TempDir(), "shot.jpg")
	cfg := Config{URL: server.URL, Timeout: defaultTestTimeout}
	in := &invocation{command: "snap", out: out, cfg: cfg, client: NewClient(cfg), who: who}
	if _, code := captureStdout(t, func() int { return capture(in, "/api/still", "jpg") }); code != 0 {
		t.Fatalf("the capture should have worked, got %d", code)
	}

	raw, err := os.ReadFile(sidecarPath(out))
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	got := sub(doc, "asker")
	want := map[string]string{
		"cwd":     cwd,
		"project": project,
		"command": "deskcam snap zoom=2 --why 'read the OLED'",
		"session": "20260915_4",
		"why":     "read the OLED",
		"via":     "cli",
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("asker.%s is %q, want %q", k, got[k], v)
		}
	}
}

// DESKCAM_SESSION is this tool's own name for it, so it wins over a name borrowed from
// somebody else's environment.
func TestTheSessionNamedForDeskcamWins(t *testing.T) {
	env := func(k string) string {
		return map[string]string{"DESKCAM_SESSION": "mine", "AGENT_SESSION_ID": "theirs"}[k]
	}
	if got := whoAsked(t.TempDir(), nil, "", "cli", env).Session; got != "mine" {
		t.Fatalf("session is %q", got)
	}
}

// Outside a repository there is no project, and the block says nothing rather than
// guessing one from the directory's name.
func TestNoRepositoryIsNoProject(t *testing.T) {
	who := whoAsked(t.TempDir(), []string{"snap"}, "", "cli", noEnv)
	if who.Project != "" {
		t.Fatalf("project is %q", who.Project)
	}
	if _, ok := who.block()["project"]; ok {
		t.Fatal("an empty project should not be written")
	}
}

// The key is a credential. A sidecar is a file people attach to bug reports.
func TestTheKeyNeverReachesTheRecord(t *testing.T) {
	who := whoAsked(t.TempDir(),
		[]string{"snap", "token=hunter2", "--url", "http://10.0.0.5:8080/?token=hunter2&x=1"},
		"", "cli", noEnv)
	if strings.Contains(who.Command, "hunter2") {
		t.Fatalf("the key is in the record: %s", who.Command)
	}
	if !strings.Contains(who.Command, "token=") {
		t.Fatalf("the record should still say a key was given: %s", who.Command)
	}
}

// Nobody asked, in a test that builds its own invocation, and then there is no block.
func TestNobodyAskedIsNoBlock(t *testing.T) {
	if got := (asker{}).block(); got != nil {
		t.Fatalf("got %v", got)
	}
}

// --why takes a value, as --url does, and says so when it has none.
func TestWhyNeedsAValue(t *testing.T) {
	if code := run([]string{"snap", "--why"}); code != 1 {
		t.Fatalf("got %d", code)
	}
}

// An agent's scratch directory is in no repository. Measured on 2026-09-19: a still taken
// from one came back with no project at all, which is the case this exists for.
func TestAProjectCanBeStatedWhenTheDirectoryCannotSayIt(t *testing.T) {
	env := func(k string) string {
		return map[string]string{"DESKCAM_PROJECT": "/home/me/Projects/esp32-voice-note2"}[k]
	}
	if got := whoAsked(t.TempDir(), nil, "", "cli", env).Project; got != "/home/me/Projects/esp32-voice-note2" {
		t.Fatalf("project is %q", got)
	}
}
