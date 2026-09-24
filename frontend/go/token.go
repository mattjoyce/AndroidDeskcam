package main

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"strings"
)

// The access key. Card 17.
//
// The token exists on the phone as a shared secret, and it used to have to be typed there
// on the on-screen keyboard, which is the reason nobody turned it on. It is made here and
// carried to the phone inside the pairing code, so it is never typed at all.
//
// It lives in one file that both this binary and the console read, so the CLI picks up a
// key the console made without being told.

func newToken() (string, error) {
	raw := make([]byte, 15) // 120 bits, and a multiple of 3 so the base64 has no padding
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func saveToken(value string) error { return writeConfig(tokenFile(), value) }

func clearToken() error {
	if err := os.Remove(tokenFile()); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func token(in *invocation) int {
	switch in.arg(0) {
	case "", "show":
		current := readTrimmed(tokenFile())
		if current == "" {
			fmt.Println("no access key set; the camera answers anyone on the network")
			return 0
		}
		fmt.Println(current)
		fmt.Fprintln(os.Stderr, "deskcam: pair the phone again to send it this key")
		return 0

	case "new", "rotate":
		value, err := newToken()
		if err != nil {
			return fail("%v", err)
		}
		if err := saveToken(value); err != nil {
			return fail("%v", err)
		}
		fmt.Println(value)
		fmt.Fprintln(os.Stderr, "deskcam: pair the phone again so it learns the new key. "+
			"Until then the phone still expects the old one.")
		return 0

	// Adopting a key somebody else generated, which is the second machine at a bench: the
	// phone already expects it, so this side has to be told rather than invent one. Before
	// this there was no way to do it at all except editing the file under ~/.config, and
	// `deskcam use URL KEY` quietly threw the key away.
	case "set", "use":
		if in.arg(1) == "" {
			return fail("usage: deskcam token set KEY\n" +
				"  KEY as `deskcam token show` prints it on the machine that paired.\n" +
				"  Use - to read it from standard input, which keeps it out of the\n" +
				"  command line and therefore out of ps and your shell history.")
		}
		value, err := keyFrom(in.arg(1))
		if err != nil {
			return fail("%v", err)
		}
		if err := saveToken(value); err != nil {
			return fail("%v", err)
		}
		// Never echoed. It is a credential, and this is the one command certain to be run
		// while somebody is watching the screen.
		fmt.Fprintln(os.Stderr, "deskcam: key stored. The phone must already expect this "+
			"one; pairing is what teaches it, and this only tells the workstation.")
		return 0

	case "clear", "remove", "off":
		if err := clearToken(); err != nil {
			return fail("%v", err)
		}
		fmt.Fprintln(os.Stderr, "deskcam: key removed here. Pair the phone again to clear "+
			"it there too, which opens the camera to the network.")
		return 0
	}
	return fail("usage: deskcam token new|show|set KEY|clear")
}

// keyFrom reads an access key given as a word, or from standard input when it is "-".
//
// A key on the command line is visible to every process on the machine through ps, and it
// lands in the shell history besides. The dash is the way to avoid both, and it is worth
// having because the case this exists for is pasting a key onto a second machine.
func keyFrom(given string) (string, error) {
	if given != "-" {
		return strings.TrimSpace(given), nil
	}
	b, err := io.ReadAll(os.Stdin)
	if err != nil {
		return "", fmt.Errorf("reading the key from standard input: %w", err)
	}
	value := strings.TrimSpace(string(b))
	if value == "" {
		return "", fmt.Errorf("no key arrived on standard input")
	}
	return value, nil
}
