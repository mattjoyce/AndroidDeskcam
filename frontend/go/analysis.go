package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

// The measurement tools stay in Python, and this is the only place that knows it.
//
// They are array maths over image data, which is NumPy's job and not Go's, and nine open
// cards are more of the same. They read files and never speak HTTP, so the seam is clean:
// this passes paths and arguments and does nothing else. Card 53.
func findAnalysis() (string, error) {
	var tries []string
	if v := os.Getenv("DESKCAM_ANALYSIS"); v != "" {
		tries = append(tries, v)
	}
	if exe, err := os.Executable(); err == nil {
		if real, err := filepath.EvalSymlinks(exe); err == nil {
			exe = real
		}
		dir := filepath.Dir(exe)
		// Built in place at frontend/go/deskcam, or installed anywhere with the tools beside it.
		tries = append(tries, filepath.Dir(dir), dir, filepath.Join(dir, "frontend"))
	}
	if wd, err := os.Getwd(); err == nil {
		tries = append(tries, wd, filepath.Join(wd, "frontend"))
	}
	for _, dir := range tries {
		if _, err := os.Stat(filepath.Join(dir, "analysis", "__init__.py")); err == nil {
			return dir, nil
		}
	}
	return "", fmt.Errorf("cannot find the analysis package; set DESKCAM_ANALYSIS to the " +
		"directory that contains it")
}

// runAnalysis passes through to the Python tools, exit code and all: 0 measured,
// 2 refused, 1 could not run. A refusal is a normal outcome with its own code, so a
// caller never has to read English to tell the three apart.
func runAnalysis(args []string) int {
	dir, err := findAnalysis()
	if err != nil {
		fmt.Fprintln(os.Stderr, "deskcam:", err)
		return 1
	}
	cmd := exec.Command("python3", append([]string{"-m", "analysis"}, args...)...)
	cmd.Env = append(os.Environ(), "PYTHONPATH="+dir+string(os.PathListSeparator)+os.Getenv("PYTHONPATH"))
	cmd.Stdout, cmd.Stderr, cmd.Stdin = os.Stdout, os.Stderr, os.Stdin
	err = cmd.Run()
	code := 0
	if exit, ok := err.(*exec.ExitError); ok {
		code = exit.ExitCode()
	} else if err != nil {
		fmt.Fprintln(os.Stderr, "deskcam:", err)
		code = 1
	}
	if code == 1 {
		explainMissingPython(dir)
	}
	return code
}

// A tool that could not run is usually a tool that was never installed. Say which, and how.
func explainMissingPython(dir string) {
	probe := exec.Command("python3", "-c", "import numpy, PIL")
	probe.Env = append(os.Environ(), "PYTHONPATH="+dir)
	if probe.Run() == nil {
		return
	}
	fmt.Fprint(os.Stderr, `deskcam: the measurement tools need numpy and pillow.
  pip install -e '.[analysis]'      (from the repository root)
Taking pictures does not need them; measuring what is in one does.
`)
}
