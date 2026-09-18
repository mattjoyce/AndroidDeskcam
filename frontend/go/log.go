package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// deskcam log reads the journal back.
//
//	deskcam log [N] [via=console|cli] [--json]     the last N operations, oldest first
//	deskcam log wait [via=console|cli|any] [timeout=120]
//
// The journal holds what everybody did, a person at the console included. So an agent that
// reads it sees what the person pointed at, the mark they drew and the still they took for
// it, and the person needs no second channel to signal with. "Watch for my signal" is
// deskcam log wait: it returns when somebody does something at the console, with what they
// did, as one JSON entry. Decision D19.
//
// Exit 0 for an entry, 2 when the time ran out with nothing seen. Nothing here reaches the
// phone, and reading the journal is not itself journalled.
func logCommand(in *invocation) int {
	dir := journalDir()
	filter := map[string]string{}
	for _, pair := range strings.Split(in.query, "&") {
		if k, v, ok := strings.Cut(pair, "="); ok {
			filter[k] = v
		}
	}
	if in.arg(0) == "wait" {
		return logWait(dir, filter)
	}

	limit, asJSON := 20, false
	for _, word := range in.args {
		if word == "--json" {
			asJSON = true
		} else if n, err := strconv.Atoi(word); err == nil && n > 0 {
			limit = n
		} else {
			return fail("usage: deskcam log [N] [via=console|cli] [--json]   or   deskcam log wait [timeout=120]")
		}
	}
	var kept []journalEntry
	for _, entry := range readJournal(dir, journalCap) {
		if matches(entry, filter["via"]) {
			kept = append(kept, entry)
		}
	}
	if len(kept) > limit {
		kept = kept[len(kept)-limit:]
	}
	if asJSON {
		if kept == nil {
			kept = []journalEntry{}
		}
		out, _ := json.MarshalIndent(kept, "", "  ")
		fmt.Println(string(out))
		return 0
	}
	for _, entry := range kept {
		fmt.Println(logLine(entry))
	}
	return 0
}

func matches(entry journalEntry, via string) bool {
	return via == "" || via == "any" || str(entry.Asker, "via") == via
}

// One line for a person to read. An agent wants --json.
func logLine(entry journalEntry) string {
	when := entry.At
	if at, err := time.Parse(time.RFC3339Nano, entry.At); err == nil {
		when = at.Local().Format("2006-01-02 15:04:05")
	}
	project := filepath.Base(str(entry.Asker, "project"))
	if project == "." {
		project = "-"
	}
	command := str(entry.Asker, "command")
	if command == "" {
		command = strings.TrimSpace("deskcam " + entry.Operation + " " + strings.ReplaceAll(entry.Query, "&", " "))
	}
	line := fmt.Sprintf("%s  %-7s  %-16s  %s", when, str(entry.Asker, "via"), project, command)
	if !entry.Ok {
		line += "   REFUSED: " + strings.Join(strings.Fields(entry.Error), " ")
	}
	for _, file := range entry.Files {
		line += "\n    " + file.Path
	}
	return line
}

// logWait returns the next entry written after it was started. It defaults to the console,
// because the question is what the person did, and another agent's still is not a signal.
func logWait(dir string, filter map[string]string) int {
	via := filter["via"]
	if via == "" {
		via = "console"
	}
	timeout := 120
	if v := filter["timeout"]; v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 3600 {
			return fail("timeout is seconds from 1 to 3600, got %q", v)
		}
		timeout = n
	}
	// The names sort by time, so "after I started" is "sorts after this name".
	since := time.Now().UTC().Format("20060102T150405.000000000")
	seen := map[string]bool{}
	deadline := time.Now().Add(time.Duration(timeout) * time.Second)
	for {
		names, _ := filepath.Glob(filepath.Join(dir, "*.json"))
		sort.Strings(names)
		for _, name := range names {
			id := strings.TrimSuffix(filepath.Base(name), ".json")
			if id <= since || seen[id] || !entryID.MatchString(id) {
				continue
			}
			seen[id] = true
			raw, err := os.ReadFile(name)
			if err != nil {
				continue
			}
			var entry journalEntry
			if json.Unmarshal(raw, &entry) != nil || !matches(entry, via) {
				continue
			}
			fmt.Println(strings.TrimSpace(string(raw)))
			return 0
		}
		if time.Now().After(deadline) {
			fmt.Fprintf(os.Stderr, "deskcam: nothing was done at the %s in %d seconds\n", via, timeout)
			return 2
		}
		time.Sleep(200 * time.Millisecond)
	}
}
