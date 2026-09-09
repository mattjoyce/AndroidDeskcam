package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// A capture, as the console's page shows it.
type rollItem struct {
	Name        string         `json:"name"`
	Modified    int64          `json:"mtime"`
	Bytes       int64          `json:"bytes"`
	When        string         `json:"when,omitempty"`
	Summary     string         `json:"summary,omitempty"`
	Exposure    string         `json:"exposure,omitempty"`
	ISO         any            `json:"iso,omitempty"`
	Tilt        any            `json:"tilt,omitempty"`
	CapturePath string         `json:"capture_path,omitempty"`
	Settings    map[string]any `json:"settings,omitempty"`
}

// roll lists the captures of this session, newest first.
//
// The sidecar written beside each capture is the only index. A directory listing plus
// those files is enough, so there is no database to keep in step with the files.
func roll(shots string, limit int) []rollItem {
	entries, err := os.ReadDir(shots)
	if err != nil {
		return []rollItem{}
	}
	type candidate struct {
		name string
		info os.FileInfo
	}
	var found []candidate
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".jpg") || strings.HasSuffix(name, ".thumb.jpg") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		found = append(found, candidate{name, info})
	}
	sort.Slice(found, func(i, j int) bool {
		return found[i].info.ModTime().After(found[j].info.ModTime())
	})
	if len(found) > limit {
		found = found[:limit]
	}

	out := make([]rollItem, 0, len(found))
	for _, c := range found {
		item := rollItem{
			Name:     c.name,
			Modified: c.info.ModTime().Unix(),
			Bytes:    c.info.Size(),
		}
		side := filepath.Join(shots, strings.TrimSuffix(c.name, ".jpg")+".json")
		if raw, err := os.ReadFile(side); err == nil {
			var doc map[string]any
			if json.Unmarshal(raw, &doc) == nil {
				settings := sub(doc, "settings")
				measured := sub(doc, "measured")
				orientation := sub(doc, "orientation")
				item.When = str(doc, "captured_at")
				item.Summary = "zoom " + str(settings, "zoom") + "x  " +
					str(settings, "cx") + "," + str(settings, "cy")
				if flag(settings, "measure") {
					item.Summary += "  measure"
				}
				item.Exposure = str(measured, "exposure_human")
				item.ISO = measured["iso"]
				item.Tilt = orientation["tilt_degrees"]
				item.CapturePath = str(settings, "capture_path")
				item.Settings = settings
			}
		}
		out = append(out, item)
	}
	return out
}

// servable is what the console will hand out by name.
//
// Only files it wrote, only from the shots directory, and only a name that stays inside
// it. The console listens on every interface so the phone can reach it, which makes
// "serve a file from the working directory by name" an offer to the whole network.
var servable = map[string]bool{".jpg": true, ".jpeg": true, ".dng": true, ".json": true}

func inShots(shots, name string) (string, bool) {
	name = filepath.Base(name)
	if name == "" || name == "." || name == ".." || strings.HasPrefix(name, ".") {
		return "", false
	}
	if !servable[strings.ToLower(filepath.Ext(name))] {
		return "", false
	}
	root, err := filepath.Abs(shots)
	if err != nil {
		return "", false
	}
	full := filepath.Join(root, name)
	resolved, err := filepath.Abs(full)
	if err != nil || filepath.Dir(resolved) != root {
		return "", false
	}
	info, err := os.Stat(resolved)
	if err != nil || info.IsDir() {
		return "", false
	}
	return resolved, true
}
