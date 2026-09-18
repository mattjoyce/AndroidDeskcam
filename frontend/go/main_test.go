package main

import (
	"fmt"
	"os"
	"testing"
)

// No test may write into the journal of the person running the tests. Any test that goes
// through run() would, so the whole package is pointed at a directory of its own, and a
// test that cares names another with t.Setenv.
func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "deskcam-journal-")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	_ = os.Setenv("DESKCAM_JOURNAL", dir)
	code := m.Run()
	_ = os.RemoveAll(dir)
	os.Exit(code)
}
