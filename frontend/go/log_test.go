package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func journalled3(t *testing.T) {
	t.Helper()
	dir := journalDir()
	for _, e := range []journalEntry{
		{Operation: "snap", Ok: true, Asker: map[string]any{"via": "cli", "command": "deskcam snap zoom=2", "project": "/home/me/esp32"}},
		{Operation: "focus", Ok: true, Asker: map[string]any{"via": "console", "command": "deskcam focus at 0.3,0.6", "project": "/home/me/AndroidDeskcam"}},
		{Operation: "snap", Ok: false, Error: "a script holds the camera", Asker: map[string]any{"via": "cli", "command": "deskcam snap", "project": "/home/me/esp32"}},
	} {
		if err := writeJournal(dir, e, nil); err != nil {
			t.Fatal(err)
		}
	}
}

// The journal is the record of what everybody did, a person at the console included, so an
// agent that reads it sees what the person pointed at and what they took. It needs a
// command to read it with, because R1 says an operation is one shell command.
func TestTheLogIsTheJournalNewestLast(t *testing.T) {
	t.Setenv("DESKCAM_JOURNAL", t.TempDir())
	journalled3(t)
	out, code := captureStdout(t, func() int { return run([]string{"log"}) })
	if code != 0 {
		t.Fatalf("got %d", code)
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 3 {
		t.Fatalf("want three lines, got %q", out)
	}
	// Oldest first, like any log, so the last line is the last thing that happened.
	if !strings.Contains(lines[0], "deskcam snap zoom=2") || !strings.Contains(lines[1], "console") ||
		!strings.Contains(lines[2], "REFUSED") || !strings.Contains(lines[2], "a script holds the camera") {
		t.Errorf("got:\n%s", out)
	}
}

func TestTheLogCanBeNarrowedAndCounted(t *testing.T) {
	t.Setenv("DESKCAM_JOURNAL", t.TempDir())
	journalled3(t)
	out, _ := captureStdout(t, func() int { return run([]string{"log", "via=console"}) })
	if n := strings.Count(strings.TrimSpace(out), "\n") + 1; n != 1 || !strings.Contains(out, "focus at 0.3,0.6") {
		t.Errorf("via=console gave:\n%s", out)
	}
	out, _ = captureStdout(t, func() int { return run([]string{"log", "1"}) })
	if n := strings.Count(strings.TrimSpace(out), "\n") + 1; n != 1 || !strings.Contains(out, "REFUSED") {
		t.Errorf("log 1 gave:\n%s", out)
	}
	out, _ = captureStdout(t, func() int { return run([]string{"log", "--json"}) })
	var entries []map[string]any
	if err := json.Unmarshal([]byte(out), &entries); err != nil || len(entries) != 3 {
		t.Fatalf("--json should be the entries themselves: %v\n%s", err, out)
	}
}

// "Watch for my signal." The agent runs one command and it returns when the person does
// something at the console, with what they did.
func TestLogWaitReturnsWhatThePersonDidNext(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("DESKCAM_JOURNAL", dir)
	journalled3(t) // history is not a signal
	go func() {
		time.Sleep(250 * time.Millisecond)
		_ = writeJournal(dir, journalEntry{Operation: "snap", Ok: true,
			Asker: map[string]any{"via": "cli", "command": "deskcam snap"}}, nil) // another agent, not the person
		time.Sleep(150 * time.Millisecond)
		_ = writeJournal(dir, journalEntry{Operation: "mark", Query: "mark=0.3,0.6,0.1,0.1&label=this+one", Ok: true,
			Asker: map[string]any{"via": "console", "command": "deskcam mark at 0.3,0.6,0.1,0.1 label=this+one"}}, nil)
	}()
	out, code := captureStdout(t, func() int { return run([]string{"log", "wait", "timeout=5"}) })
	if code != 0 {
		t.Fatalf("got %d: %s", code, out)
	}
	var entry map[string]any
	if err := json.Unmarshal([]byte(out), &entry); err != nil {
		t.Fatalf("want one entry as JSON: %v\n%s", err, out)
	}
	if entry["operation"] != "mark" || sub(entry, "asker")["via"] != "console" {
		t.Errorf("got %v", entry)
	}
}

// Nothing happened is an answer, and it is not a success.
func TestLogWaitThatSeesNothingExitsTwo(t *testing.T) {
	t.Setenv("DESKCAM_JOURNAL", t.TempDir())
	if _, code := captureStdout(t, func() int { return run([]string{"log", "wait", "timeout=1"}) }); code != 2 {
		t.Fatalf("want 2, got %d", code)
	}
}

// A mark points without touching the camera, which a double tap cannot do: that one moves
// the lens. It names a place in the picture you can see, by the same sum focus at uses.
func TestMarkAtPointsWithoutMovingAnything(t *testing.T) {
	t.Setenv("DESKCAM_JOURNAL", t.TempDir())
	phone, asked := zoomedPhone(t, `{"zoom":4,"cx":0.3,"cy":0.7}`)
	_, code := captureStdout(t, func() int {
		return run([]string{"mark", "at", "0.5,0.5,0.2,0.4", "label=this one", "by=matt", "--url", phone.URL})
	})
	if code != 0 {
		t.Fatalf("got %d", code)
	}
	got := asked()
	if len(got) != 2 || !strings.HasPrefix(got[1], "/api/marks?") {
		t.Fatalf("want a status and then a mark, got %v", got)
	}
	sent := strings.ReplaceAll(got[1], "%2C", ",")
	// The crop is 0.25 wide, so a box 0.2 by 0.4 of the picture is 0.05 by 0.1 of the sensor.
	for _, want := range []string{"mark=0.3000,0.7000,0.0500,0.1000", "label=this+one", "by=matt"} {
		if !strings.Contains(sent, want) {
			t.Errorf("want %s in %s", want, sent)
		}
	}
	for _, p := range got {
		if strings.Contains(p, "/api/set") || strings.Contains(p, "/api/af") {
			t.Errorf("a mark must not change the camera, but it asked for %s", p)
		}
	}
	// What an agent reads the place from: the journal has what was sent, on the sensor.
	entries := readJournal(journalDir(), 10)
	if len(entries) != 1 || !strings.Contains(entries[0].Query, "mark=0.3000,0.7000,0.0500,0.1000") ||
		!strings.Contains(entries[0].Query, "label=this+one") {
		t.Errorf("the journal should hold the mark as it was sent, got %+v", entries)
	}
	// A point is two numbers.
	_, code = captureStdout(t, func() int { return run([]string{"mark", "at", "0.5,0.5", "--url", phone.URL}) })
	if code != 0 || !strings.Contains(strings.ReplaceAll(asked()[3], "%2C", ","), "mark=0.3000,0.7000&") &&
		!strings.HasSuffix(strings.ReplaceAll(asked()[3], "%2C", ","), "mark=0.3000,0.7000") {
		t.Errorf("a point mark went as %v (exit %d)", asked()[3:], code)
	}
}

func TestAShiftDragAtTheSeatIsTheClisMarkAt(t *testing.T) {
	state, server, _ := testConsole(t)
	phone, asked := zoomedPhone(t, `{"zoom":1}`)
	pairTo(state, phone.URL)
	code, body := post(t, server, "/api/op?do=markat&fx=0.4&fy=0.6&fw=0.2&fh=0.1&label=the+cap")
	if code != 200 || !strings.Contains(body, `"ok": true`) {
		t.Fatalf("got %d %s", code, body)
	}
	if got := asked(); len(got) != 2 || !strings.Contains(strings.ReplaceAll(got[1], "%2C", ","), "mark=0.4000,0.6000,0.2000,0.1000") {
		t.Fatalf("got %v", got)
	}
	item := rollFrom(journalDir(), rollLimit, sessionGap)[0].Sessions[0].Items[0]
	if item.Operation != "mark" || item.Via != "console" {
		t.Errorf("the mark should be in the journal for an agent to read, got %+v", item)
	}
}
