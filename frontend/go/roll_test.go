package main

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// journalIt writes an entry as the CLI would and gives back its name, which is how the
// console is asked for anything.
func journalIt(t *testing.T, entry journalEntry, produced ...string) string {
	t.Helper()
	dir := journalDir()
	before, _ := filepath.Glob(filepath.Join(dir, "*.json"))
	if err := writeJournal(dir, entry, produced); err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, name := range before {
		seen[name] = true
	}
	after, _ := filepath.Glob(filepath.Join(dir, "*.json"))
	for _, name := range after {
		if !seen[name] {
			return strings.TrimSuffix(filepath.Base(name), ".json")
		}
	}
	t.Fatal("the entry was not written")
	return ""
}

func TestACaptureAndItsSidecarComeBack(t *testing.T) {
	_, server, shots := testConsole(t)
	id := writeCapture(t, shots, "one.jpg", map[string]any{"zoom": 3.0, "cx": 0.5, "cy": 0.5})
	if code, _ := get(t, server, "/img/"+id+"/0"); code != http.StatusOK {
		t.Fatalf("the capture should be served, got %d", code)
	}
	code, body := get(t, server, "/sidecar/"+id+"/0")
	if code != http.StatusOK || !strings.Contains(body, `"zoom": 3`) {
		t.Fatalf("the sidecar should be served, got %d %.60q", code, body)
	}
}

// The reason the journal keeps its own copies. An agent's scratch directory is cleaned
// away, and the record of what it saw has to outlive that.
func TestACleanedScratchDirectoryLeavesTheRecordStanding(t *testing.T) {
	_, server, _ := testConsole(t)
	scratch := filepath.Join(t.TempDir(), "scratchpad")
	if err := os.MkdirAll(scratch, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(scratch, "shot.jpg"), aJPEG(t), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(scratch, "shot.json"), []byte(`{"settings":{"zoom":4}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	id := journalIt(t, journalEntry{Operation: "snap", Ok: true}, filepath.Join(scratch, "shot.jpg"))
	if err := os.RemoveAll(scratch); err != nil {
		t.Fatal(err)
	}
	if code, _ := get(t, server, "/thumb/"+id+"/0"); code != http.StatusOK {
		t.Errorf("the thumbnail should still be served, got %d", code)
	}
	if code, body := get(t, server, "/sidecar/"+id+"/0"); code != http.StatusOK || !strings.Contains(body, `"zoom": 4`) {
		t.Errorf("the sidecar should still be served, got %d %.60q", code, body)
	}
	code, body := get(t, server, "/img/"+id+"/0")
	if code != http.StatusGone || !strings.Contains(body, "gone") {
		t.Errorf("the original is gone and the console should say so, got %d %.60q", code, body)
	}
}

func TestAMissingSidecarSaysSo(t *testing.T) {
	_, server, shots := testConsole(t)
	lonely := filepath.Join(shots, "lonely.jpg")
	if err := os.WriteFile(lonely, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	id := journalIt(t, journalEntry{Operation: "snap", Ok: true}, lonely)
	code, body := get(t, server, "/sidecar/"+id+"/0")
	if code != http.StatusNotFound || !strings.Contains(body, "no sidecar") {
		t.Fatalf("want a 404 saying so, got %d %.60q", code, body)
	}
}

// A file is asked for by entry and number. Nothing in a request is a path, and a path in
// an entry is served only if it is a picture.
func TestTheConsoleServesOnlyWhatTheJournalNames(t *testing.T) {
	_, server, shots := testConsole(t)
	id := writeCapture(t, shots, "good.jpg", map[string]any{"zoom": 1.0})
	secret := filepath.Join(shots, "notes.txt")
	if err := os.WriteFile(secret, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	// An entry somebody wrote by hand, naming a file that is not a picture and a
	// thumbnail that is some other file in the journal.
	forged := "20260101T000000.000000000-1-1"
	doc := `{"operation":"snap","ok":true,"files":[{"path":"` + secret + `","thumb":"` + id + `.json"}]}`
	if err := os.WriteFile(filepath.Join(journalDir(), forged+".json"), []byte(doc), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{
		"/img/" + forged + "/0", "/thumb/" + forged + "/0",
		"/img/" + id + "/1", "/img/" + id + "/-1", "/img/" + id + "/x",
		"/img/..%2F..%2Fmain.go/0", "/img/" + id, "/img/good.jpg", "/img/" + id + "/0/extra",
	} {
		if code, body := get(t, server, path); code == http.StatusOK {
			t.Errorf("%s must not be served, got %d %.40q", path, code, body)
		}
	}
	if code, _ := get(t, server, "/img/"+id+"/0"); code != http.StatusOK {
		t.Errorf("a journalled capture should be served, got %d", code)
	}
}

func TestTheRollIsTheJournalNewestFirst(t *testing.T) {
	_, _, shots := testConsole(t)
	writeCapture(t, shots, "one.jpg", map[string]any{"zoom": 1.0, "cx": 0.5, "cy": 0.5})
	writeCapture(t, shots, "two.jpg", map[string]any{"zoom": 6.0, "cx": 0.3, "cy": 0.7, "measure": true})

	groups := rollFrom(journalDir(), rollLimit, sessionGap)
	if len(groups) != 1 || len(groups[0].Sessions) != 1 {
		t.Fatalf("want one group with one session, got %+v", groups)
	}
	items := groups[0].Sessions[0].Items
	if len(items) != 2 || items[0].Name != "two.jpg" {
		t.Fatalf("newest first, got %+v", items)
	}
	if !strings.Contains(items[0].Summary, "measure") {
		t.Errorf("the summary should mention measurement mode, got %q", items[0].Summary)
	}
	if items[0].Exposure != "8.00ms (1/125)" {
		t.Errorf("the exposure should come from the sidecar, got %q", items[0].Exposure)
	}
	if !items[0].Exists {
		t.Error("the capture is on disk and the roll should say so")
	}
}

// The case the roll failed on: captures in directories it was never pointed at, from two
// projects, with one of them in no repository at all.
func TestTheRollGroupsByProjectWhereverTheFilesWent(t *testing.T) {
	testConsole(t)
	dir := journalDir()
	write := func(at string, asker map[string]any, ok bool) {
		t.Helper()
		if err := writeJournal(dir, journalEntry{At: at, Operation: "snap", Ok: ok, Asker: asker}, nil); err != nil {
			t.Fatal(err)
		}
	}
	esp := map[string]any{"project": "/home/me/esp32", "cwd": "/tmp/scratch/a", "why": "read the OLED"}
	write("2026-09-09T10:00:00Z", esp, true)
	write("2026-09-09T10:05:00Z", esp, false)
	write("2026-09-09T12:00:00Z", esp, true) // two hours on: a new session
	write("2026-09-10T09:00:00Z", map[string]any{"cwd": "/tmp/scratch/b"}, true)

	groups := rollFrom(dir, rollLimit, sessionGap)
	if len(groups) != 2 {
		t.Fatalf("want two groups, got %d", len(groups))
	}
	if groups[0].Project != "/tmp/scratch/b" || groups[0].Known {
		t.Errorf("the newest group first, and a directory is not a known project: %+v", groups[0])
	}
	known := groups[1]
	if known.Project != "/home/me/esp32" || !known.Known || len(known.Sessions) != 2 {
		t.Fatalf("want esp32 with two sessions, got %+v", known)
	}
	first := known.Sessions[1]
	if first.Count != 2 || first.Refused != 1 || len(first.Why) != 1 || first.Why[0] != "read the OLED" {
		t.Errorf("the earlier session is %+v", first)
	}
}

func TestTheRollSurvivesABrokenEntry(t *testing.T) {
	_, _, shots := testConsole(t)
	writeCapture(t, shots, "good.jpg", map[string]any{"zoom": 1.0})
	bad := filepath.Join(journalDir(), "20260101T000000.000000000-1-1.json")
	if err := os.WriteFile(bad, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	groups := rollFrom(journalDir(), rollLimit, sessionGap)
	if len(groups) != 1 || groups[0].Sessions[0].Items[0].Name != "good.jpg" {
		t.Fatal("a broken entry must not lose the others")
	}
}

func TestTheRollOfAJournalThatIsNotThere(t *testing.T) {
	if got := rollFrom(filepath.Join(t.TempDir(), "nowhere"), rollLimit, sessionGap); len(got) != 0 {
		t.Fatalf("want nothing, got %d", len(got))
	}
}
