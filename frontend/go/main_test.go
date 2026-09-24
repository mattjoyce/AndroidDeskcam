package main

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

// No test may write into the journal of the person running the tests, and none may read
// their configuration either. Any test that goes through run() would do both, so the whole
// package is pointed at directories of its own, and a test that cares names another with
// t.Setenv.
//
// The configuration half was added after a test that asserts on request URLs passed on a
// fresh checkout and failed on a paired bench: the CLI appends the access key it finds, so
// the expected `/api/marks?` arrived as `/api/marks?token=qwerty`. A suite whose result
// depends on whose machine it runs on is worse than no suite, because it teaches people to
// ignore a red result.
func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "deskcam-test-")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	_ = os.Setenv("DESKCAM_JOURNAL", filepath.Join(dir, "journal"))
	_ = os.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, "config"))
	for _, name := range []string{"DESKCAM_TOKEN", "DESKCAM_URL", "DESKCAM_SERIAL",
		"DESKCAM_SHOTS", "DESKCAM_TIMEOUT", "DESKCAM_PROJECT"} {
		_ = os.Unsetenv(name)
	}
	code := m.Run()
	_ = os.RemoveAll(dir)
	os.Exit(code)
}
