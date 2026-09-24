package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// The roll is the journal, read back and grouped for a person. Decision D19.
//
// It used to be a listing of one directory, the one deskcam serve was started in, and the
// captures it was meant to show were somewhere else. The journal does not move, so the roll
// reads that, and a capture appears whichever directory it was written to.
//
// The journal is still the only index. Nothing here is stored: the groups and the sessions
// are worked out again on every read, so there is no state to fall out of step with the
// files.

// sessionGap is how long a project has to be quiet before the next operation starts a new
// session. A session is derived and never managed: nobody opens, names or closes one.
const sessionGap = 30 * time.Minute

// rollLimit is how many journal entries one read of the roll looks at.
const rollLimit = 300

// rollItem is one row. An entry that made files gives a row for each, and an entry that
// made none, a refusal or a change to the camera, gives one row with N of -1.
type rollItem struct {
	ID          string         `json:"id"`
	N           int            `json:"n"`
	Operation   string         `json:"operation"`
	Query       string         `json:"query,omitempty"`
	At          string         `json:"at"`
	Millis      int64          `json:"millis"`
	Ok          bool           `json:"ok"`
	Error       string         `json:"error,omitempty"`
	Why         string         `json:"why,omitempty"`
	Via         string         `json:"via,omitempty"`
	Command     string         `json:"command,omitempty"`
	Cwd         string         `json:"cwd,omitempty"`
	Name        string         `json:"name,omitempty"`
	Path        string         `json:"path,omitempty"`
	Bytes       int64          `json:"bytes,omitempty"`
	Exists      bool           `json:"exists"`
	Thumb       bool           `json:"thumb"`
	Summary     string         `json:"summary,omitempty"`
	Exposure    string         `json:"exposure,omitempty"`
	ISO         any            `json:"iso,omitempty"`
	Tilt        any            `json:"tilt,omitempty"`
	CapturePath string         `json:"capture_path,omitempty"`
	Settings    map[string]any `json:"settings,omitempty"`
}

type rollSession struct {
	Started string     `json:"started"`
	Ended   string     `json:"ended"`
	Session string     `json:"session,omitempty"`
	Count   int        `json:"count"`
	Refused int        `json:"refused"`
	Why     []string   `json:"why"`
	Items   []rollItem `json:"items"`
}

type rollGroup struct {
	// Project is the repository the work was for. When nobody knew it, the directory the
	// command ran in stands for it and Known is false, so the page can say which it has.
	Project  string        `json:"project"`
	Known    bool          `json:"known"`
	Sessions []rollSession `json:"sessions"`
}

// An entry's name: a time, a process and a counter, and nothing that could be a path.
var entryID = regexp.MustCompile(`^[0-9]{8}T[0-9]{6}\.[0-9]{9}-[0-9]+-[0-9]+$`)

// readJournal reads the newest entries, oldest first. The names sort by time, so only the
// entries that will be shown are opened.
func readJournal(dir string, limit int) []journalEntry {
	names, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil {
		return nil
	}
	sort.Strings(names)
	if len(names) > limit {
		names = names[len(names)-limit:]
	}
	var out []journalEntry
	for _, name := range names {
		id := strings.TrimSuffix(filepath.Base(name), ".json")
		if !entryID.MatchString(id) {
			continue
		}
		raw, err := os.ReadFile(name)
		if err != nil {
			continue
		}
		var entry journalEntry
		if json.Unmarshal(raw, &entry) != nil {
			continue
		}
		entry.id = id
		out = append(out, entry)
	}
	return out
}

// rollFrom groups the journal by project and, within a project, into sessions. Everything
// comes back newest first, because the last thing an agent did is the thing being asked
// about.
func rollFrom(dir string, limit int, gap time.Duration) []rollGroup {
	byProject := map[string]*rollGroup{}
	var order []string
	last := map[string]time.Time{}

	for _, entry := range readJournal(dir, limit) {
		key, known := str(entry.Asker, "project"), true
		if key == "" {
			key, known = str(entry.Asker, "cwd"), false
		}
		group, ok := byProject[key]
		if !ok {
			group = &rollGroup{Project: key, Known: known}
			byProject[key] = group
			order = append(order, key)
		}
		at, _ := time.Parse(time.RFC3339Nano, entry.At)
		if len(group.Sessions) == 0 || at.Sub(last[key]) > gap {
			group.Sessions = append(group.Sessions, rollSession{Started: entry.At, Why: []string{}})
		}
		last[key] = at
		session := &group.Sessions[len(group.Sessions)-1]
		session.Ended = entry.At
		session.Count++
		if !entry.Ok {
			session.Refused++
		}
		if s := str(entry.Asker, "session"); s != "" {
			session.Session = s
		}
		if why := str(entry.Asker, "why"); why != "" && !contains(session.Why, why) {
			session.Why = append(session.Why, why)
		}
		session.Items = append(session.Items, itemsOf(entry)...)
	}

	out := make([]rollGroup, 0, len(order))
	for _, key := range order {
		group := byProject[key]
		reverse(group.Sessions)
		for i := range group.Sessions {
			reverse(group.Sessions[i].Items)
		}
		out = append(out, *group)
	}
	sort.SliceStable(out, func(i, j int) bool {
		return out[i].Sessions[0].Ended > out[j].Sessions[0].Ended
	})
	return out
}

func itemsOf(entry journalEntry) []rollItem {
	base := rollItem{
		ID: entry.id, N: -1, Operation: entry.Operation, Query: entry.Query, At: entry.At,
		Millis: entry.Millis, Ok: entry.Ok, Error: entry.Error,
		Why: str(entry.Asker, "why"), Via: str(entry.Asker, "via"), Cwd: str(entry.Asker, "cwd"),
		Command: str(entry.Asker, "command"),
	}
	if len(entry.Files) == 0 {
		return []rollItem{base}
	}
	out := make([]rollItem, 0, len(entry.Files))
	for n, file := range entry.Files {
		item := base
		item.N = n
		item.Name = filepath.Base(file.Path)
		item.Path = file.Path
		item.Bytes = file.Bytes
		item.Thumb = file.Thumb != ""
		if info, err := os.Stat(file.Path); err == nil && !info.IsDir() {
			item.Exists = true
		}
		if file.Sidecar != nil {
			settings := sub(file.Sidecar, "settings")
			measured := sub(file.Sidecar, "measured")
			if len(settings) > 0 {
				item.Summary = "zoom " + str(settings, "zoom") + "x  " +
					str(settings, "cx") + "," + str(settings, "cy")
				if flag(settings, "measure") {
					item.Summary += "  measure"
				}
				item.Settings = settings
			}
			item.Exposure = str(measured, "exposure_human")
			item.ISO = measured["iso"]
			item.Tilt = sub(file.Sidecar, "orientation")["tilt_degrees"]
			item.CapturePath = str(file.Sidecar, "capture_path")
		}
		out = append(out, item)
	}
	return out
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

func reverse[T any](s []T) {
	for i, j := 0, len(s)-1; i < j; i, j = i+1, j-1 {
		s[i], s[j] = s[j], s[i]
	}
}

// journalledFile finds one file of one entry, by the entry's name and the file's place in it.
//
// This is what the console will hand out, and it is asked for by name and number, never by
// path. The name must be an entry's name exactly, so nothing in a request can be a path.
// The path then comes from the journal, which only this user can write, and anything able
// to write there can already read the file it names.
func journalledFile(dir, id, n string) (journalFile, bool) {
	if !entryID.MatchString(id) {
		return journalFile{}, false
	}
	index, err := strconv.Atoi(n)
	if err != nil || index < 0 {
		return journalFile{}, false
	}
	raw, err := os.ReadFile(filepath.Join(dir, id+".json"))
	if err != nil {
		return journalFile{}, false
	}
	var entry journalEntry
	if json.Unmarshal(raw, &entry) != nil || index >= len(entry.Files) {
		return journalFile{}, false
	}
	return entry.Files[index], true
}

// The only kinds of file the console serves from a path. A sidecar is served from the
// journal's own copy, so no .json is ever read from a path a journal entry names.
var servable = map[string]bool{".jpg": true, ".jpeg": true, ".dng": true}

// original is the capture itself, if it is still where the journal says and is a kind of
// file the console serves.
func original(file journalFile) (string, bool) {
	if !servable[strings.ToLower(filepath.Ext(file.Path))] || !filepath.IsAbs(file.Path) {
		return "", false
	}
	info, err := os.Stat(file.Path)
	if err != nil || !info.Mode().IsRegular() {
		return "", false
	}
	return file.Path, true
}

// thumbOf is the journal's own copy of the thumbnail. Its name must be the entry's name
// with a number, so an entry cannot point the console at some other file in the journal.
func thumbOf(dir, id string, file journalFile) (string, bool) {
	name := file.Thumb
	if name == "" || name != filepath.Base(name) ||
		!strings.HasPrefix(name, id+"-") || !strings.HasSuffix(name, ".thumb.jpg") {
		return "", false
	}
	path := filepath.Join(dir, name)
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return "", false
	}
	return path, true
}
