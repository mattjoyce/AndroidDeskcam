package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// asker is who asked for an operation, and from where.
//
// A sidecar described the camera completely and said nothing about the caller. Agents
// write their captures into temporary directories of their own choosing, so the only
// record of which project a capture belonged to was the name of a directory that is
// cleaned away. Decision D19.
//
// Via is how the request arrived, the CLI or the console. It is not a claim about whether
// an agent or a person was typing, because the CLI cannot tell and must not guess.
type asker struct {
	Cwd     string
	Project string
	Command string
	Session string
	Why     string
	Via     string
}

// whoAsked builds the record from the things a process knows about itself. The directory
// and the environment are parameters so a test can name them.
func whoAsked(cwd string, argv []string, why, via string, getenv func(string) string) asker {
	who := asker{Cwd: cwd, Project: projectOf(cwd), Why: why, Via: via}
	// An agent shoots from a scratch directory under /tmp, which is in no repository, so
	// the directory cannot say which project the work is for. The caller can.
	if v := strings.TrimSpace(getenv("DESKCAM_PROJECT")); v != "" {
		who.Project = v
	}
	if len(argv) > 0 {
		who.Command = commandLine(argv)
	}
	// This tool's own name for a session first, then the one an agent harness sets.
	for _, key := range []string{"DESKCAM_SESSION", "AGENT_SESSION_ID"} {
		if v := strings.TrimSpace(getenv(key)); v != "" {
			who.Session = v
			break
		}
	}
	return who
}

// thisProcess is whoAsked for the command that is running.
func thisProcess(argv []string, why string) asker {
	cwd, err := os.Getwd()
	if err != nil {
		cwd = ""
	}
	return whoAsked(cwd, argv, why, "cli", os.Getenv)
}

// projectOf is the nearest directory above cwd that holds a .git, which is a directory in
// a repository and a file in a worktree. No repository is no project: a guess from the
// directory's name would put a confident label on a temporary directory.
func projectOf(cwd string) string {
	if cwd == "" {
		return ""
	}
	for dir := cwd; ; dir = filepath.Dir(dir) {
		if _, err := os.Lstat(filepath.Join(dir, ".git")); err == nil {
			return dir
		}
		if dir == filepath.Dir(dir) {
			return ""
		}
	}
}

var keyInCommand = regexp.MustCompile(`token=[^&\s']*`)

// commandLine is the command as it was typed, near enough to paste, with the key taken
// out. A sidecar gets attached to bug reports and a key is a credential.
func commandLine(argv []string) string {
	words := []string{"deskcam"}
	for _, a := range argv {
		a = keyInCommand.ReplaceAllString(a, "token=***")
		if a == "" || strings.ContainsAny(a, " \t\n\"'\\$&|;<>()*?") {
			a = "'" + strings.ReplaceAll(a, "'", `'\''`) + "'"
		}
		words = append(words, a)
	}
	return strings.Join(words, " ")
}

// block is the record as it is written. Nothing is written for a value nobody has, and no
// block at all when nobody asked.
func (a asker) block() map[string]any {
	out := map[string]any{}
	for key, value := range map[string]string{
		"cwd": a.Cwd, "project": a.Project, "command": a.Command,
		"session": a.Session, "why": a.Why, "via": a.Via,
	} {
		if value != "" {
			out[key] = value
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}
