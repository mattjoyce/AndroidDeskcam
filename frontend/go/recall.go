package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// recallQuery turns a sidecar back into the parameters that made it.
//
// Camera state only. w, h and jpegq describe one request and were forgotten the moment it
// finished, so naming them here would promise something the phone no longer does.
// Decision D9.
func recallQuery(sidecar string) (string, error) {
	raw, err := os.ReadFile(sidecar)
	if err != nil {
		return "", err
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		return "", fmt.Errorf("%s is not a sidecar: %w", sidecar, err)
	}
	settings, ok := doc["settings"].(map[string]any)
	if !ok {
		settings = doc
	}

	var q []string
	add := func(key string, value any) {
		if value == nil {
			return
		}
		q = append(q, key+"="+valueString(value))
	}
	boolAs01 := func(key string, value any) {
		if b, ok := value.(bool); ok && b {
			q = append(q, key+"=1")
		} else if ok {
			q = append(q, key+"=0")
		}
	}

	add("camera", settings["camera"])
	add("zoom", settings["zoom"])
	add("cx", settings["cx"])
	add("cy", settings["cy"])
	add("rotate", settings["rotate"])
	add("torch", settings["torch"])
	add("awb", settings["awb"])
	boolAs01("awblock", settings["awb_lock"])
	boolAs01("aelock", settings["ae_lock"])
	boolAs01("measure", settings["measure"])
	boolAs01("shadingmap", settings["shading_map"])

	if v, ok := settings["preview_size_requested"].(string); ok && v != "" {
		add("previewsize", v)
	}
	if v, ok := settings["still_size"].(string); ok && v != "" && v != "max" {
		add("stillsize", v)
	}

	if d := settings["focus_diopters"]; d != nil {
		add("focus", d)
	} else {
		add("af", settings["af"])
	}

	if settings["ae"] == "manual" {
		add("exposure", settings["exposure_ns"])
		add("iso", settings["iso"])
	} else {
		q = append(q, "ae=on")
		add("ev", settings["ev"])
	}
	return strings.Join(q, "&"), nil
}
