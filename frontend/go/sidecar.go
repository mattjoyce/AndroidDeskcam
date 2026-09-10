package main

import (
	"encoding/json"
	"fmt"
	"image/jpeg"
	"os"
	"path/filepath"
	"strings"
)

// writeSidecar records how a capture was taken, beside the capture.
//
// The values come from the X-DeskCam-Provenance header of the reply that carried the
// picture, so they describe that frame. The shell version asked /api/status afterwards,
// which described whatever the camera was doing by then, and two captures running at once
// could exchange their records. Card 43.
func writeSidecar(image string, reply *Reply, client *Client, target string) error {
	doc := map[string]any{}
	source := "the capture itself"

	if raw := reply.Provenance(); raw != nil {
		if err := json.Unmarshal(raw, &doc); err != nil {
			return fmt.Errorf("the provenance header is not JSON: %w", err)
		}
	} else {
		// An older phone build. Say so in the file rather than quietly writing a sidecar
		// that came from a different moment and looks identical to a good one.
		source = "a second request after the capture, not from the capture itself"
		status, err := client.GetJSON("/api/status", "")
		if err != nil {
			return err
		}
		for _, key := range []string{"settings", "measured", "pipeline", "sensor", "orientation"} {
			if v, ok := status[key]; ok {
				doc[key] = v
			}
		}
	}

	doc["image"] = filepath.Base(image)
	doc["target"] = target
	doc["from"] = source
	if info, err := os.Stat(image); err == nil {
		doc["bytes"] = info.Size()
	}
	width, height := pixelSize(image)
	if width > 0 {
		doc["width_px"] = width
		doc["height_px"] = height
	}

	// The scale measured from a reference in an earlier capture, carried forward while it
	// still describes the framing. Card 23. When it does not, the sidecar says so rather
	// than going quiet, because a missing scale and a stale one are different problems and
	// only one of them is fixed by measuring again.
	if block := scaleFor(readScale(filepath.Dir(image)), sub(doc, "settings"), width, height); block != nil {
		doc["scale"] = block
	}

	out, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(sidecarPath(image), append(out, '\n'), 0o644)
}

// pixelSize reads the dimensions out of the header without decoding the image.
func pixelSize(path string) (int, int) {
	if !isJPEG(path) {
		return 0, 0
	}
	f, err := os.Open(path)
	if err != nil {
		return 0, 0
	}
	defer func() { _ = f.Close() }()
	cfg, err := jpeg.DecodeConfig(f)
	if err != nil {
		return 0, 0
	}
	return cfg.Width, cfg.Height
}

func sidecarPath(image string) string {
	return strings.TrimSuffix(image, filepath.Ext(image)) + ".json"
}
