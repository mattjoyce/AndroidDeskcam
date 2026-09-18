package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// The journal is the one place every operation on the camera is written down.
//
// The console's roll read a single directory, the one deskcam serve was started in. On
// 2026-09-19, 99 of the 100 captures on the workstation were in eleven scratch directories
// that agents had chosen for themselves, so the roll showed none of them. A capture can go
// anywhere. What it was, who asked for it and what came back goes here. Decision D19.
//
// One file for each entry. Two agents write at once, and an appended log would need a lock
// that a crashed writer could leave held. A file of its own needs none.
//
// A refusal is journalled as fully as a capture, because it is the most useful thing to
// see afterwards and an agent does not always mention one.

// journalCap is how many entries are kept. A thumbnail is about 15 kB, so this is about
// 30 MB, and it must have some limit because nobody tends it.
const journalCap = 2000

// thumbsPerEntry stops a sixty-frame burst from writing sixty thumbnails.
const thumbsPerEntry = 12

type journalFile struct {
	Path    string         `json:"path"`
	Bytes   int64          `json:"bytes,omitempty"`
	Thumb   string         `json:"thumb,omitempty"`
	Sidecar map[string]any `json:"sidecar,omitempty"`
}

type journalEntry struct {
	At        string         `json:"at"`
	Millis    int64          `json:"millis"`
	Operation string         `json:"operation"`
	Query     string         `json:"query,omitempty"`
	Target    string         `json:"target,omitempty"`
	Ok        bool           `json:"ok"`
	ExitCode  int            `json:"exit_code"`
	Error     string         `json:"error,omitempty"`
	Asker     map[string]any `json:"asker,omitempty"`
	Files     []journalFile  `json:"files"`
}

// journalDir is where the journal lives. State, in the XDG sense: it is not configuration
// and losing it loses nothing but history.
func journalDir() string {
	if v := strings.TrimSpace(os.Getenv("DESKCAM_JOURNAL")); v != "" {
		return v
	}
	if v := strings.TrimSpace(os.Getenv("XDG_STATE_HOME")); v != "" {
		return filepath.Join(v, "deskcam", "journal")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".local", "state", "deskcam", "journal")
}

// The commands that change the camera or take a picture with it. Reading the camera is
// not an operation on it: an agent polls status, and a journal full of polls hides the
// captures.
var journalled = map[string]bool{
	"snap": true, "still": true, "shot": true, "frame": true, "preview": true,
	"raw": true, "dng": true, "burst": true, "focussweep": true, "sweep": true,
	"bracket": true, "walk": true, "stream": true, "script": true,
	"set": true, "reset": true, "recall": true, "zoom": true, "center": true,
	"centre": true, "pan": true, "af": true, "autofocus": true, "focus": true,
	"exposure": true, "shutter": true, "iso": true, "auto": true, "torch": true,
	"light": true, "aatest": true,
}

// What the command said when it failed. fail and failWith have no invocation to write it
// on, and one process runs one command.
var (
	saidMu sync.Mutex
	said   string
)

func noteFailure(text string) {
	saidMu.Lock()
	said = text
	saidMu.Unlock()
}

func takeFailure() string {
	saidMu.Lock()
	defer saidMu.Unlock()
	text := said
	said = ""
	return text
}

// journalRun writes down what a command did. Nothing here can fail the command: a journal
// that cannot be written is a lost diagnostic and never a lost capture.
func journalRun(in *invocation, started time.Time, code int) {
	failure := takeFailure()
	if !journalled[in.command] {
		return
	}
	dir := journalDir()
	if dir == "" {
		return
	}
	entry := journalEntry{
		At:        started.UTC().Format(time.RFC3339Nano),
		Millis:    time.Since(started).Milliseconds(),
		Operation: in.command,
		Query:     keyInCommand.ReplaceAllString(in.query, "token=***"),
		Target:    in.cfg.URL,
		Ok:        code == 0,
		ExitCode:  code,
		Asker:     in.who.block(),
	}
	if code != 0 {
		entry.Error = failure
	}
	if err := writeJournal(dir, entry, in.produced); err != nil {
		fmt.Fprintln(os.Stderr, "deskcam: could not write the journal:", err)
		return
	}
	pruneJournal(dir, journalCap)
}

var journalSeq atomic.Int64

// writeJournal writes one entry and the thumbnails of what it produced. A produced path
// that is a directory stands for the pictures in it.
func writeJournal(dir string, entry journalEntry, produced []string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	when := time.Now().UTC()
	if entry.At == "" {
		entry.At = when.Format(time.RFC3339Nano)
	}
	// Sorts by time, and the process and a counter keep two writers apart.
	stem := fmt.Sprintf("%s-%d-%d", when.Format("20060102T150405.000000000"),
		os.Getpid(), journalSeq.Add(1))

	entry.Files = []journalFile{}
	for _, path := range picturesIn(produced) {
		file := journalFile{Path: path}
		if abs, err := filepath.Abs(path); err == nil {
			file.Path = abs
		}
		if info, err := os.Stat(path); err == nil {
			file.Bytes = info.Size()
		}
		if raw, err := os.ReadFile(sidecarPath(path)); err == nil {
			var doc map[string]any
			if json.Unmarshal(raw, &doc) == nil {
				file.Sidecar = doc
			}
		}
		if len(entry.Files) < thumbsPerEntry && isJPEG(path) {
			name := fmt.Sprintf("%s-%d.thumb.jpg", stem, len(entry.Files))
			if writeThumbTo(path, filepath.Join(dir, name)) == nil {
				file.Thumb = name
			}
		}
		entry.Files = append(entry.Files, file)
	}

	out, err := json.MarshalIndent(entry, "", "  ")
	if err != nil {
		return err
	}
	// Under another name and moved into place, so the console never reads half an entry.
	final := filepath.Join(dir, stem+".json")
	if err := os.WriteFile(final+".part", append(out, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(final+".part", final)
}

// picturesIn is the capture files among what a command produced, a directory standing for
// the pictures directly inside it.
func picturesIn(produced []string) []string {
	var out []string
	for _, path := range produced {
		info, err := os.Stat(path)
		if err != nil {
			continue
		}
		if !info.IsDir() {
			out = append(out, path)
			continue
		}
		entries, err := os.ReadDir(path)
		if err != nil {
			continue
		}
		for _, e := range entries {
			name := e.Name()
			ext := strings.ToLower(filepath.Ext(name))
			if e.IsDir() || strings.HasSuffix(name, ".thumb.jpg") ||
				(ext != ".jpg" && ext != ".jpeg" && ext != ".dng") {
				continue
			}
			out = append(out, filepath.Join(path, name))
		}
	}
	return out
}

// pruneJournal keeps the newest entries and removes the rest with their thumbnails. The
// names sort by time, so no file has to be opened to find the oldest.
func pruneJournal(dir string, keep int) {
	names, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil || len(names) <= keep {
		return
	}
	sort.Strings(names)
	for _, name := range names[:len(names)-keep] {
		stem := strings.TrimSuffix(name, ".json")
		thumbs, _ := filepath.Glob(stem + "-*.thumb.jpg")
		for _, thumb := range thumbs {
			_ = os.Remove(thumb)
		}
		_ = os.Remove(name)
	}
}
