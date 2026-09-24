package main

import (
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// journalEntries reads a journal directory back, oldest first.
func journalEntries(t *testing.T, dir string) []map[string]any {
	t.Helper()
	names, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	var out []map[string]any
	for _, name := range names {
		raw, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		var doc map[string]any
		if err := json.Unmarshal(raw, &doc); err != nil {
			t.Fatalf("%s is not JSON: %v", name, err)
		}
		out = append(out, doc)
	}
	return out
}

func aJPEG(t *testing.T) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 640, 480))
	for y := 0; y < 480; y++ {
		for x := 0; x < 640; x++ {
			img.Set(x, y, color.RGBA{uint8(x / 3), uint8(y / 2), 90, 255})
		}
	}
	var b strings.Builder
	if err := jpeg.Encode(&b, img, nil); err != nil {
		t.Fatal(err)
	}
	return []byte(b.String())
}

// The roll read one directory while the captures went to eleven others. The journal is the
// one place every operation is written down, wherever its files went. Decision D19.
func TestACaptureIsJournalledWithItsThumbnailWhereverItWent(t *testing.T) {
	picture := aJPEG(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-DeskCam-Provenance", `{"settings":{"zoom":3}}`)
		_, _ = w.Write(picture)
	}))
	defer server.Close()

	journal := t.TempDir()
	t.Setenv("DESKCAM_JOURNAL", journal)
	out := filepath.Join(t.TempDir(), "somewhere", "else", "shot.jpg")
	_, code := captureStdout(t, func() int {
		return run([]string{"snap", "--url", server.URL, "-o", out, "zoom=3", "--why", "find the bridge"})
	})
	if code != 0 {
		t.Fatalf("got %d", code)
	}

	entries := journalEntries(t, journal)
	if len(entries) != 1 {
		t.Fatalf("want one entry, got %d", len(entries))
	}
	e := entries[0]
	if e["operation"] != "snap" || e["ok"] != true || e["exit_code"] != float64(0) {
		t.Errorf("entry is %v", e)
	}
	if sub(e, "asker")["why"] != "find the bridge" {
		t.Errorf("asker is %v", e["asker"])
	}
	files, _ := e["files"].([]any)
	if len(files) != 1 {
		t.Fatalf("want one file, got %v", e["files"])
	}
	file, _ := files[0].(map[string]any)
	if file["path"] != out {
		t.Errorf("path is %v, want the absolute path %s", file["path"], out)
	}
	if sub(sub(file, "sidecar"), "settings")["zoom"] != float64(3) {
		t.Errorf("the sidecar did not come along: %v", file["sidecar"])
	}
	// The thumbnail is a copy inside the journal, so it outlives a cleaned scratch directory.
	thumb, _ := file["thumb"].(string)
	if thumb == "" || thumb != filepath.Base(thumb) {
		t.Fatalf("thumb is %q", thumb)
	}
	if err := os.RemoveAll(filepath.Dir(out)); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(journal, thumb)); err != nil {
		t.Fatal("the thumbnail should survive the capture's directory being removed")
	}
}

// A refusal is the most useful thing to see afterwards, and an agent does not always
// mention one.
func TestARefusalIsJournalledInThePhonesWords(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"ok":false,"error":"a script holds the camera"}`))
	}))
	defer server.Close()

	journal := t.TempDir()
	t.Setenv("DESKCAM_JOURNAL", journal)
	out := filepath.Join(t.TempDir(), "shot.jpg")
	_, code := captureStdout(t, func() int {
		return run([]string{"snap", "--url", server.URL, "-o", out})
	})
	if code == 0 {
		t.Fatal("a refused capture should not exit 0")
	}
	entries := journalEntries(t, journal)
	if len(entries) != 1 {
		t.Fatalf("want one entry, got %d", len(entries))
	}
	e := entries[0]
	if e["ok"] != false {
		t.Errorf("ok is %v", e["ok"])
	}
	if said, _ := e["error"].(string); !strings.Contains(said, "a script holds the camera") {
		t.Errorf("error is %q", said)
	}
	if files, _ := e["files"].([]any); len(files) != 0 {
		t.Errorf("a refusal wrote no file, got %v", files)
	}
}

// A journal that cannot be written is a lost diagnostic. It is never a lost capture.
func TestAJournalThatCannotBeWrittenFailsNothing(t *testing.T) {
	picture := aJPEG(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(picture)
	}))
	defer server.Close()

	blocked := filepath.Join(t.TempDir(), "a-file-not-a-directory")
	if err := os.WriteFile(blocked, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("DESKCAM_JOURNAL", blocked)
	out := filepath.Join(t.TempDir(), "shot.jpg")
	_, code := captureStdout(t, func() int {
		return run([]string{"snap", "--url", server.URL, "-o", out})
	})
	if code != 0 {
		t.Fatalf("the capture should still succeed, got %d", code)
	}
	if _, err := os.Stat(out); err != nil {
		t.Fatal("the capture should be on disk")
	}
}

// Reading the camera is not an operation on it. An agent polls status, and a journal full
// of polls hides the captures.
func TestLookingAtTheCameraIsNotJournalled(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"state":"running","settings":{}}`))
	}))
	defer server.Close()
	journal := t.TempDir()
	t.Setenv("DESKCAM_JOURNAL", journal)
	if _, code := captureStdout(t, func() int { return run([]string{"status", "--url", server.URL}) }); code != 0 {
		t.Fatalf("got %d", code)
	}
	if n := len(journalEntries(t, journal)); n != 0 {
		t.Fatalf("status wrote %d entries", n)
	}
}

// The key is a credential, in the query as much as in the command.
func TestTheKeyNeverReachesTheJournal(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true,"settings":{}}`))
	}))
	defer server.Close()
	journal := t.TempDir()
	t.Setenv("DESKCAM_JOURNAL", journal)
	t.Setenv("DESKCAM_TOKEN", "hunter2")
	captureStdout(t, func() int { return run([]string{"set", "--url", server.URL, "zoom=2", "token=hunter2"}) })
	names, _ := filepath.Glob(filepath.Join(journal, "*.json"))
	if len(names) != 1 {
		t.Fatalf("want one entry, got %d", len(names))
	}
	raw, _ := os.ReadFile(names[0])
	if strings.Contains(string(raw), "hunter2") {
		t.Fatalf("the key is in the journal:\n%s", raw)
	}
}

// Two agents write at once, so an entry is its own file and no two share a name.
func TestTwoWritersDoNotCollide(t *testing.T) {
	journal := t.TempDir()
	var wg sync.WaitGroup
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = writeJournal(journal, journalEntry{Operation: "snap", Ok: true}, nil)
		}()
	}
	wg.Wait()
	if n := len(journalEntries(t, journal)); n != 40 {
		t.Fatalf("want 40 entries, got %d", n)
	}
}

// It runs for months and must never grow without limit. The oldest go first, with their
// thumbnails.
func TestTheJournalKeepsItsNewestAndDropsTheRest(t *testing.T) {
	journal := t.TempDir()
	for i := 0; i < 12; i++ {
		if err := writeJournal(journal, journalEntry{Operation: "snap", Query: string(rune('a' + i)), Ok: true}, nil); err != nil {
			t.Fatal(err)
		}
	}
	pruneJournal(journal, 5)
	entries := journalEntries(t, journal)
	if len(entries) != 5 {
		t.Fatalf("want 5 entries, got %d", len(entries))
	}
	if entries[0]["query"] != "h" || entries[4]["query"] != "l" {
		t.Errorf("kept %v to %v, want the newest five", entries[0]["query"], entries[4]["query"])
	}
}
