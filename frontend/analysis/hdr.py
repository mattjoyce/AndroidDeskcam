"""
One linear image out of an exposure bracket.

A lit panel in a dark bezel is wider than the sensor holds in one frame. The bracket of
card 7 takes it in several, and this puts them back together as radiance: pixel value
divided by the exposure that produced it, averaged over the frames that saw each pixel
well. Card 8.

The method is Debevec and Malik 1997, in its easy case. The hard part of that paper is
recovering an unknown camera response curve; here the response is already linear, so the
merge reduces to a weighted mean of `value / exposure` and the weight only has to say how
much each frame is to be believed for each pixel.

**Linear is a precondition, not a hope.** `measure=1` puts the tone map on the identity
curve, and card 34 measured 2.0x per doubling through that pipeline. Without it the JPEG
carries a display curve, `value / exposure` is not radiance, and the output would be a
number that looks like a measurement and is not one. So the tone map is checked and a
bracket taken without it is refused rather than merged.

**Merged on the exposure that happened, never on the nominal stop.** A sensor quantises:
asked for four whole periods of 1/240 it gave 3.9920. The sidecars carry `exposure_ns` for
exactly this reason, and using `2 ** step` instead would bake a fifth of a percent of error
into every frame.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image

from .images import (
    captures_in,
    gains_differ,
    gains_of,
    image_size,
    measured_of,
    set_sidecar,
    settings_of,
)
from .png16 import write as write_png16
from .result import Measurement

METHOD = "hdr-merge"

MIN_FRAMES = 2

# A pixel this close to either end of the range records the end of the range and not the
# light. The same floor and ceiling the rest of the tools use.
FLOOR, CEILING = 8.0, 247.0

# How much of the frame must land somewhere usable for the merge to mean anything.
COVERAGE_LIMIT = 0.98
COVERAGE_JUSTIFICATION = (
    "A pixel that is on the floor in the longest exposure and on the ceiling in the "
    "shortest was never measured by any frame, and the merge can only guess at it. Below "
    "98% covered, the bracket does not span its own subject and wants another stop at one "
    "end or the other."
)

# The settings a bracket varies, and everything else it must hold still.
MUST_MATCH = ("camera", "zoom", "cx", "cy", "rotate", "measure", "torch", "iso")

# How far two neighbouring exposures may disagree about one unchanging scene before it is
# worth saying so. Two frames one stop apart, of the same subject, through a linear
# pipeline, should give the same radiance on every pixel they both saw well.
AGREEMENT_NOTICE = 0.02

# The band the agreement is measured in, which is narrower than the band the merge uses.
#
# Two frames far apart in exposure only overlap in the deepest shadow of the longer one,
# where a small offset in the pipeline is a large fraction of a small number, and near the
# ceiling where the response rolls off. Measuring the agreement there would report a
# disagreement on every honest bracket and the warning would mean nothing. This band is the
# part of the range where the response can be trusted, so a disagreement inside it is real.
AGREEMENT_BAND = (40.0, 200.0)
GAIN_TOLERANCE = 0.005


def _weight(value: np.ndarray) -> np.ndarray:
    """
    How much a pixel is to be believed, by how far it sits from the ends of the range.

    A triangle peaking in the middle, which is Debevec's hat. Zero at and beyond the floor
    and the ceiling, so a clipped pixel contributes nothing rather than contributing a
    number that is smaller than the light that made it.
    """
    w = np.minimum(value - FLOOR, CEILING - value)
    return np.clip(w, 0.0, None)


def _agreement(frames: list[Path], seconds: list[float]) -> list[float]:
    """
    The ratio of the radiance each neighbouring pair of exposures reports, on the pixels
    both of them saw well.

    One number per pair, and each should be 1.000. It is a check on the merge and on the
    linearity the merge assumes, made from the data rather than from the specification.
    """
    ratios: list[float] = []
    previous: tuple[np.ndarray, np.ndarray] | None = None
    for f, t in zip(frames, seconds, strict=True):
        with Image.open(f) as im:
            value = np.asarray(im.convert("L"), dtype=np.float64)
        low, high = AGREEMENT_BAND
        usable = (value > low) & (value < high)
        radiance = value / t
        if previous is not None:
            both = usable & previous[1]
            # A thousand pixels, so a ratio is not read off a handful of them.
            if both.sum() > 1000:
                ratios.append(float(np.median(radiance[both] / previous[0][both])))
        previous = (radiance, usable)
    return ratios


def _exposures(frames: list[Path]) -> tuple[list[float], str | None]:
    """The exposure of every frame in seconds, or why one of them has none."""
    seconds = []
    for f in frames:
        ns = measured_of(f).get("exposure_ns")
        if not isinstance(ns, (int, float)) or ns <= 0:
            return [], (
                f"{f.name} does not record the exposure it was taken at. A merge divides "
                f"by that number; without it there is nothing to divide by."
            )
        seconds.append(float(ns) / 1e9)
    return seconds, None


def _linear(frames: list[Path]) -> str | None:
    """Why this pipeline is not linear, or None when it is."""
    record = set_sidecar(frames[0].parent)
    pipeline = record.get("pipeline") or {}
    if not pipeline:
        first = measured_of(frames[0])
        if not first:
            return (
                f"{frames[0].name} has no sidecar, so nothing records whether the tone map "
                f"was on. A merge of tone-mapped frames is not radiance."
            )
        return None
    points = pipeline.get("tonemap_points")
    curve = str(pipeline.get("tonemap_curve", ""))
    if points == 2 and curve.replace(" ", "") == "(0.0,0.0)(1.0,1.0)":
        return None
    return (
        f"these frames were taken through a tone map ({points} points, {curve}), so the "
        f"pixel value is not proportional to the light and dividing it by the exposure "
        f"does not give radiance. Take the bracket with measure=1, which puts the tone map "
        f"on the identity curve."
    )


def measure(directory: Path, out: Path | None = None) -> Measurement:
    frames = captures_in(directory)
    notes = [f"{len(frames)} frames in {directory.name}"]
    inputs = [str(f) for f in frames]

    def refuse(reason: str) -> Measurement:
        return Measurement.refuse(
            METHOD,
            "covered",
            reason,
            n=len(frames),
            n_min=MIN_FRAMES,
            limit=COVERAGE_LIMIT,
            limit_justification=COVERAGE_JUSTIFICATION,
            notes=notes,
            inputs=inputs,
        )

    if len(frames) < MIN_FRAMES:
        return refuse(f"{len(frames)} frames is fewer than the {MIN_FRAMES} a merge needs")

    first = settings_of(frames[0])
    for f in frames[1:]:
        other = settings_of(f)
        differing = [k for k in MUST_MATCH if first.get(k) != other.get(k)]
        if differing:
            detail = ", ".join(f"{k} {first.get(k)} against {other.get(k)}" for k in differing)
            return refuse(
                f"a bracket varies the exposure and holds everything else still. "
                f"{frames[0].name} and {f.name} differ in {detail}."
            )
        if gains_differ(gains_of(frames[0]), gains_of(f), GAIN_TOLERANCE):
            return refuse(
                f"{frames[0].name} and {f.name} were taken through different white balance "
                f"gains. Set them with awbgains= before bracketing. Card 42."
            )

    if problem := _linear(frames):
        return refuse(problem)
    seconds, problem = _exposures(frames)
    if problem:
        return refuse(problem)
    if len(set(seconds)) < 2:
        return refuse(
            "every frame here was taken at the same exposure, so this is a burst and not a "
            "bracket. Averaging it is deskcam analyse average."
        )

    order = np.argsort(seconds)
    frames = [frames[i] for i in order]
    seconds = [seconds[i] for i in order]
    inputs = [str(f) for f in frames]
    notes.append(
        "exposures "
        + ", ".join(f"{s * 1000:.2f}ms" for s in seconds)
        + f", a range of {max(seconds) / min(seconds):.0f} to 1"
    )

    # One frame in memory at a time. Six frames of 2016x1512 in float would be 440 MB,
    # and a bracket is allowed to be longer than six.
    width, height = image_size(frames[0])
    weighted = np.zeros((height, width, 3))
    total = np.zeros((height, width, 3))
    covered = np.zeros((height, width), dtype=bool)
    for f, t in zip(frames, seconds, strict=True):
        with Image.open(f) as im:
            value = np.asarray(im.convert("RGB"), dtype=np.float64)
        if value.shape != weighted.shape:
            return refuse(f"{f.name} is a different size from the frames before it")
        w = _weight(value)
        weighted += w * (value / t)
        total += w
        covered |= (w > 0).all(axis=2)

    fraction = float(covered.mean())
    notes.append(f"{fraction:.1%} of the frame was measured by at least one exposure")

    # Whether the frames agree with each other about the light, which is the question
    # "covered" does not answer. Two exposures of one unchanging scene should give the same
    # radiance on every pixel they both saw well, so the median of that ratio is a direct
    # check on the merge and on the linearity it assumes.
    agreement = _agreement(frames, seconds)
    if agreement:
        worst = max(abs(r - 1.0) for r in agreement)
        notes.append(
            "neighbouring exposures agree to "
            + ", ".join(f"{r:.3f}" for r in agreement)
            + f"; the worst is {worst:.1%} from 1.000"
        )
        if worst > AGREEMENT_NOTICE:
            notes.append(
                f"WARNING: {worst:.1%} is more than the {AGREEMENT_NOTICE:.0%} two exposures "
                "of one scene should differ by. Measured on this camera, the short frames "
                "read low, which is a small negative offset in the pipeline rather than a "
                "fault in the merge: a dark frame would measure it and let it be subtracted. "
                "Card 10. Until then the darkest frames carry that error into the result."
            )

    # A pixel no frame saw well is filled from the frame that is least wrong about it: the
    # longest exposure if it is dark everywhere, the shortest if it is bright everywhere.
    # Marked in the notes rather than hidden, because it is the one part of the output that
    # is not a measurement.
    empty = total <= 0
    if empty.any():
        with Image.open(frames[-1]) as im:
            longest = np.asarray(im.convert("RGB"), dtype=np.float64)
        with Image.open(frames[0]) as im:
            shortest = np.asarray(im.convert("RGB"), dtype=np.float64)
        dark = longest <= FLOOR
        weighted[empty] = np.where(dark, longest / seconds[-1], shortest / seconds[0])[empty]
        total[empty] = 1.0
        notes.append(
            f"{float(empty.mean()):.2%} of the samples were outside every frame's usable "
            "range and were filled from the nearest end. They are not measurements."
        )

    radiance = weighted / total
    peak = float(radiance.max())
    notes.append(f"radiance in DN per second, from {float(radiance.min()):.1f} to {peak:.1f}")

    stem = out if out is not None else directory / f"{directory.name}-hdr"
    stem = stem.with_suffix("")
    data = stem.with_suffix(".npy")
    try:
        np.save(data, radiance.astype(np.float32))
    except OSError as e:
        return refuse(f"could not write {data}: {e}")
    notes.append(f"wrote {data.name}, 32-bit float, DN per second, no tone map")

    # A 16-bit view of the same numbers, scaled and never curved. The scale is recorded so
    # two merges of the same subject can be compared, which is the point of the exercise.
    scale = 65535.0 / peak if peak > 0 else 1.0
    preview = stem.with_suffix(".png")
    try:
        write_png16(preview, radiance * scale)
    except (OSError, ValueError) as e:
        return refuse(f"could not write {preview}: {e}")
    notes.append(f"wrote {preview.name}, 16-bit, the same radiance times {scale:.6g}")

    record = {
        "tool": "DeskCam",
        "method": METHOD,
        "unit": "DN per second",
        "tone_map": "none",
        "png_scale": scale,
        "frames": [{"file": f.name, "exposure_s": t} for f, t in zip(frames, seconds, strict=True)],
        "covered": fraction,
    }
    stem.with_suffix(".json").write_text(json.dumps(record, indent=2))

    return Measurement(
        method=METHOD,
        unit="covered",
        value=fraction,
        n=len(frames),
        n_min=MIN_FRAMES,
        confidence=fraction,
        confidence_kind="fraction of the frame a real exposure measured",
        limit=COVERAGE_LIMIT,
        limit_justification=COVERAGE_JUSTIFICATION,
        notes=notes,
        inputs=inputs,
    ).gate()
