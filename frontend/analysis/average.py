"""
One clean image out of a burst.

This is the useful half of HDR+: take many frames, average them, and the noise falls while
the signal does not. Card 4.

The output is 16-bit, because averaging sixteen frames lowers the noise by a factor of four
and an 8-bit file would throw away the two bits that were just paid for.

**The noise figure is measured, not predicted.** `before / sqrt(N)` is arithmetic, and
writing it in the notes as though it came from the data is exactly the failure card 36
exists to stop. The measurement is delegated to burstnoise, which splits the burst many
ways, averages each group, and measures both ends of the ratio the same way, with an
interval. One implementation of that, not two. Card 31.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

from . import burstnoise
from .images import captures_in, gains_differ, gains_of, measured_of, settings_of
from .png16 import write as write_png16
from .result import Measurement

METHOD = "burst-average"

MIN_FRAMES = 2
CENTRE = burstnoise.CENTRE

# The settings that must match for the frames to be one burst rather than a pile of
# pictures. Presentation is deliberately absent: it changes the file, not the scene.
MUST_MATCH = ("camera", "zoom", "cx", "cy", "rotate", "measure", "torch", "ae", "iso")
GAIN_TOLERANCE = 0.005

# 8-bit levels scaled so 255 lands exactly on 65535. A round factor keeps the numbers
# readable as what they came from, and rescaling to the actual range would make two files
# of the same subject incomparable.
SCALE = 257.0


def _load_stack(frames: list[Path]) -> np.ndarray:
    planes = []
    for f in frames:
        with Image.open(f) as im:
            planes.append(np.asarray(im.convert("RGB"), dtype=np.float64))
    shapes = {p.shape for p in planes}
    if len(shapes) != 1:
        raise ValueError(f"the frames are not all the same size: {sorted(shapes)}")
    return np.stack(planes)


def _one_burst(frames: list[Path]) -> str | None:
    """Why these frames are not one burst, or None when they are."""
    first = settings_of(frames[0])
    if not first:
        return (
            f"{frames[0].name} has no sidecar, so nothing records how these were taken. "
            "An average of frames that are not one burst is a smooth picture of nothing. "
            "A burst directory carries burst.json for the set; a loose pile of captures "
            "carries one sidecar each."
        )
    for f in frames[1:]:
        other = settings_of(f)
        differing = [k for k in MUST_MATCH if first.get(k) != other.get(k)]
        if differing:
            detail = ", ".join(f"{k} {first.get(k)} against {other.get(k)}" for k in differing)
            return f"{frames[0].name} and {f.name} were not taken alike: {detail}"
        if gains_differ(gains_of(frames[0]), gains_of(f), GAIN_TOLERANCE):
            return (
                f"{frames[0].name} and {f.name} were taken through different white balance "
                f"gains, {gains_of(frames[0])} against {gains_of(f)}. Set them with "
                f"awbgains= so a burst is one colour throughout. Card 42."
            )
    return None


def measure(
    directory: Path,
    out: Path | None = None,
    region: tuple[float, float, float, float] = CENTRE,
) -> Measurement:
    frames = captures_in(directory)
    notes = [f"{len(frames)} frames in {directory.name}"]
    inputs = [str(f) for f in frames]

    def refuse(reason: str) -> Measurement:
        return Measurement.refuse(
            METHOD,
            "x less noise",
            reason,
            n=len(frames),
            n_min=MIN_FRAMES,
            notes=notes,
            inputs=inputs,
        )

    if len(frames) < MIN_FRAMES:
        return refuse(f"{len(frames)} frames is fewer than the {MIN_FRAMES} an average needs")
    if problem := _one_burst(frames):
        return refuse(problem)

    try:
        stack = _load_stack(frames)
    except (OSError, ValueError) as e:
        return refuse(str(e))

    mean = stack.mean(axis=0)
    height, width = mean.shape[:2]

    written = out if out is not None else directory / f"{directory.name}-average.png"
    try:
        write_png16(written, mean * SCALE)
    except (OSError, ValueError) as e:
        return refuse(f"could not write {written}: {e}")
    notes.append(f"wrote {written.name}, 16-bit, {width}x{height}")
    notes.append(f"values are the 8-bit levels times {SCALE:.0f}, so 255 becomes 65535")

    settings = settings_of(frames[0])
    if exposure := measured_of(frames[0]).get("exposure_human"):
        notes.append(f"every frame at {exposure}, iso {settings.get('iso')}")

    # The image is written either way. Whether the averaging can be shown to have worked is
    # a separate question, and burstnoise answers it on the same frames.
    noise = burstnoise.measure(directory, region=region)
    notes.extend(noise.notes)
    if not noise.ok:
        notes.append("the image was written; the noise could not be measured on it")
        return Measurement.refuse(
            METHOD,
            "x less noise",
            f"averaged and written, but the improvement could not be measured: {noise.reason}",
            n=len(frames),
            n_min=MIN_FRAMES,
            limit=noise.limit,
            limit_justification=noise.limit_justification,
            notes=notes,
            inputs=inputs,
        )

    return Measurement(
        method=METHOD,
        unit=noise.unit,
        value=noise.value,
        interval=noise.interval,
        n=len(frames),
        n_min=MIN_FRAMES,
        confidence=noise.confidence,
        confidence_kind=noise.confidence_kind,
        limit=noise.limit,
        limit_justification=noise.limit_justification,
        notes=notes,
        inputs=inputs,
    ).gate()
