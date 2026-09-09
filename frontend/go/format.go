package main

import (
	"fmt"
	"strconv"
	"strings"
)

// valueString prints a JSON value the way a query parameter wants it. Whole floats lose
// their decimal point, because "zoom=6" reads better than "zoom=6.0" and the phone parses
// both.
func valueString(v any) string {
	switch t := v.(type) {
	case nil:
		return ""
	case string:
		return t
	case bool:
		if t {
			return "1"
		}
		return "0"
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64)
	default:
		return fmt.Sprint(t)
	}
}

func str(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	if v, ok := m[key]; ok && v != nil {
		return valueString(v)
	}
	return ""
}

func num(m map[string]any, key string) (float64, bool) {
	if m == nil {
		return 0, false
	}
	v, ok := m[key].(float64)
	return v, ok
}

func flag(m map[string]any, key string) bool {
	if m == nil {
		return false
	}
	b, _ := m[key].(bool)
	return b
}

func sub(m map[string]any, key string) map[string]any {
	if m == nil {
		return nil
	}
	s, _ := m[key].(map[string]any)
	return s
}

// summarise is the one line `deskcam show` prints.
//
// It names every setting that can change the next capture. The four most able to spoil it,
// rotate, measure and the output size, were the four the shell version left out. Card 26.
func summarise(status map[string]any) string {
	s := sub(status, "settings")
	m := sub(status, "measured")

	bits := []string{
		"zoom " + str(s, "zoom") + "x",
		"at " + str(s, "cx") + "," + str(s, "cy"),
		"af " + str(s, "af"),
	}
	if v := str(s, "focus_metres"); v != "" {
		bits = append(bits, "focus "+v+"m")
	}
	bits = append(bits, "ae "+str(s, "ae"))
	if v := str(m, "exposure_human"); v != "" {
		bits = append(bits, v)
	}
	if v, ok := num(m, "iso"); ok && v > 0 {
		bits = append(bits, "iso "+valueString(v))
	}
	if v, ok := num(s, "torch"); ok && v > 0 {
		bits = append(bits, "torch "+valueString(v))
	}
	if v, ok := num(s, "rotate"); ok && v != 0 {
		bits = append(bits, "rotate "+valueString(v))
	}
	if flag(s, "measure") {
		bits = append(bits, "MEASURE")
	}
	if s["out_w"] != nil || s["out_h"] != nil {
		w, h := str(s, "out_w"), str(s, "out_h")
		if w == "" {
			w = "-"
		}
		if h == "" {
			h = "-"
		}
		bits = append(bits, "out "+w+"x"+h)
	}
	if flag(s, "awb_lock") {
		bits = append(bits, "awb locked")
	}
	if p := str(s, "capture_path"); p != "" && p != "camera_jpeg" {
		bits = append(bits, "path "+p)
	}
	if c := str(s, "camera"); c != "" && c != "0" {
		bits = append(bits, "camera "+c)
	}
	if state := str(status, "state"); state != "" && state != "running" {
		bits = append(bits, "["+state+"]")
	}
	return strings.Join(bits, "  ")
}
