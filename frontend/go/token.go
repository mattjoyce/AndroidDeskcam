package main

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"os"
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

	case "clear", "remove", "off":
		if err := clearToken(); err != nil {
			return fail("%v", err)
		}
		fmt.Fprintln(os.Stderr, "deskcam: key removed here. Pair the phone again to clear "+
			"it there too, which opens the camera to the network.")
		return 0
	}
	return fail("usage: deskcam token new|show|clear")
}
