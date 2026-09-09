"""Reading a capture and its sidecar off disk, and nothing else."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image


def load_gray(path: Path) -> np.ndarray:
    """
    A capture as a 2-D float array of luminance, 0 to 255.

    Luma rather than a plain channel average, because the green channel carries most of
    the sensor's spatial resolution and weighting it correctly gives a sharper tick edge
    for the scale measurement.
    """
    with Image.open(path) as im:
        return np.asarray(im.convert("L"), dtype=np.float64)


def load_rgb(path: Path) -> np.ndarray:
    with Image.open(path) as im:
        return np.asarray(im.convert("RGB"), dtype=np.float64)


def load_sidecar(image: Path) -> dict[str, Any]:
    """
    The record written beside a capture, or an empty dict.

    A capture with no sidecar is not a measurement input. The caller decides whether to
    refuse; this only reports what is there.
    """
    side = image.with_suffix(".json")
    if not side.is_file():
        return {}
    try:
        loaded = json.loads(side.read_text())
    except (OSError, ValueError):
        return {}
    return loaded if isinstance(loaded, dict) else {}


def settings_of(image: Path) -> dict[str, Any]:
    return load_sidecar(image).get("settings", {})


def measured_of(image: Path) -> dict[str, Any]:
    return load_sidecar(image).get("measured", {})


def captures_in(directory: Path) -> list[Path]:
    """Every capture in a directory, oldest first, thumbnails excluded."""
    return sorted(
        (f for f in directory.glob("*.jpg") if not f.name.endswith(".thumb.jpg")),
        key=lambda f: f.name,
    )


def region_of(array: np.ndarray, region: tuple[float, float, float, float]) -> np.ndarray:
    """A sub-array named in normalised coordinates as centre-x, centre-y, width, height."""
    cx, cy, w, h = region
    height, width = array.shape[:2]
    x0 = round((cx - w / 2) * width)
    y0 = round((cy - h / 2) * height)
    x1 = round((cx + w / 2) * width)
    y1 = round((cy + h / 2) * height)
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(width, x1), min(height, y1)
    if x1 - x0 < 2 or y1 - y0 < 2:
        raise ValueError(f"region {region} selects nothing in a {width}x{height} image")
    return array[y0:y1, x0:x1]


def level_health(patch: np.ndarray, floor: float = 8.0, ceiling: float = 247.0) -> dict[str, float]:
    """
    How much of a patch sits against the ends of the 8-bit range.

    This is not a detail. The published linearity figure of 2.02x was the mean of four
    ratios whose lowest sample sat on the floor of the range, and that one sample covers
    the whole difference between 2.02 and 2.00. A sample pinned at either end is not a
    measurement of anything, so every tool here reports the fractions and drops the
    samples that are pinned.
    """
    total = patch.size
    return {
        "mean": float(patch.mean()),
        "at_floor": float((patch <= floor).sum() / total),
        "at_ceiling": float((patch >= ceiling).sum() / total),
    }


def parse_region(text: str) -> tuple[float, float, float, float]:
    parts = [p.strip() for p in text.split(",")]
    if len(parts) != 4:
        raise ValueError("a region is cx,cy,w,h in normalised coordinates, e.g. 0.5,0.5,0.3,0.3")
    cx, cy, w, h = (float(p) for p in parts)
    if not (0 < w <= 1 and 0 < h <= 1):
        raise ValueError("region width and height must be between 0 and 1")
    return cx, cy, w, h
