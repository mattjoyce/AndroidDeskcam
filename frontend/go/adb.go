package main

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// adbArgs puts the serial in front when one is named, so a bench with two phones on it
// does not become a guessing game.
func adbArgs(rest ...string) []string {
	if serial := os.Getenv("DESKCAM_SERIAL"); serial != "" {
		return append([]string{"-s", serial}, rest...)
	}
	return rest
}

func adb(rest ...string) ([]byte, error) {
	if _, err := exec.LookPath("adb"); err != nil {
		return nil, fmt.Errorf("adb not found")
	}
	return exec.Command("adb", adbArgs(rest...)...).Output()
}

func usb(in *invocation) int {
	port := 8080
	if in.arg(0) != "" {
		n, err := strconv.Atoi(in.arg(0))
		if err != nil {
			return fail("port must be a number, got %q", in.arg(0))
		}
		port = n
	}
	if _, err := adb("forward", "tcp:"+strconv.Itoa(port), "tcp:8080"); err != nil {
		return fail("adb forward failed: %v", err)
	}
	target := "http://127.0.0.1:" + strconv.Itoa(port)
	if err := writeConfig(urlFile(), target); err != nil {
		return fail("%v", err)
	}
	fmt.Println("target:", target, " (tunnelled over USB)")
	return 0
}

func wifi(in *invocation) int {
	out, err := adb("shell",
		"ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2")
	if err != nil {
		return fail("could not read the device Wi-Fi address: %v", err)
	}
	ip := strings.TrimSpace(string(out))
	if ip == "" {
		return fail("could not read the device Wi-Fi address")
	}
	target := "http://" + ip + ":8080"
	if err := writeConfig(urlFile(), target); err != nil {
		return fail("%v", err)
	}
	fmt.Println("target:", target)
	return 0
}

// service starts or stops the phone's service through the activity, because Android only
// lets a camera foreground service start from the foreground.
func service(in *invocation) int {
	action := "dev.deskcam.START"
	if in.command == "stop" {
		action = "dev.deskcam.STOP"
	}
	if _, err := adb("shell", "am", "start", "-n", "dev.deskcam/.MainActivity",
		"-a", action, "--ez", "finish", "true"); err != nil {
		return fail("%v", err)
	}
	if in.command == "stop" {
		fmt.Println("stopped")
		return 0
	}
	time.Sleep(4 * time.Second)
	return printSummary(in, "/api/status", "")
}
