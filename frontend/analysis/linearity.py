"""
How the pixel value responds to light, measured rather than asserted.

The claim this replaces: "in measurement mode the pixel value rose by 2.02x for each
doubling of exposure, and with the default pipeline it rose by 1.30x". One run, four
doublings, the mean of four ratios with the spread discarded, and the lowest sample sitting
on the floor of the 8-bit range where nothing is measurable at all.

The method here fits a power law to level against exposure. The exponent is 1.0 for a
linear response and around 0.45 for an sRGB curve, so the exponent is the quantity of
interest and the familiar "x per doubling" is just two raised to it. Samples pinned at
either end of the range are dropped and reported, because a pinned sample is not a
measurement of the response, it is a measurement of the clip.
"""

from __future__ import annotations

import math
from itertools import pairwise
from pathlib import Path

import numpy as np

from .images import (
    captures_in,
    gains_of,
    level_health,
    load_gray,
    measured_of,
    region_of,
    settings_of,
)
from .result import Measurement, NoiseFloor, t95

METHOD = "linearity"

# A power law has to describe the data before its exponent means anything. R squared below
# this says the response is not a power law at all, and quoting an exponent for it would be
# describing a shape that is not there.
FIT_LIMIT = 0.980
FIT_JUSTIFICATION = (
    "Both a linear response and an sRGB curve are power laws over this range and fit above "
    "0.99. Below 0.98 the data is describing some other shape, and an exponent taken from "
    "it is meaningless rather than merely imprecise."
)
MIN_POINTS = 4
CENTRE = (0.5, 0.5, 0.30, 0.30)


def measure(
    directory: Path,
    region: tuple[float, float, float, float] = CENTRE,
    floor: float = 8.0,
    ceiling: float = 247.0,
) -> Measurement:
    images = captures_in(directory)
    notes: list[str] = []
    points: list[tuple[float, float, str]] = []
    dropped: list[str] = []

    for image in images:
        exposure = measured_of(image).get("exposure_ns")
        if exposure is None:
            dropped.append(f"{image.name}: no measured exposure in its sidecar")
            continue
        patch = region_of(load_gray(image), region)
        health = level_health(patch, floor, ceiling)
        if health["at_floor"] > 0.02:
            dropped.append(f"{image.name}: {health['at_floor']:.0%} of the patch is on the floor")
            continue
        if health["at_ceiling"] > 0.02:
            dropped.append(f"{image.name}: {health['at_ceiling']:.0%} of the patch is clipped")
            continue
        points.append((float(exposure), health["mean"], image.name))

    for d in dropped:
        notes.append("dropped " + d)

    isos = {measured_of(i).get("iso") for i in images}
    isos.discard(None)
    if len(isos) > 1:
        listed = ", ".join(str(i) for i in sorted(isos, key=str))
        notes.append(f"WARNING: the ISO is not constant across these captures: {listed}")
    # Card 42. Two captures can agree on every setting and still have been taken through
    # different colour, because a lock holds whatever the gains happened to be.
    gains = {g for g in (gains_of(i) for i in images) if g is not None}
    if len(gains) > 1:
        notes.append(
            "WARNING: the white balance gains are not constant across these captures: "
            + "; ".join(str(g) for g in sorted(gains))
            + ". Set them with awbgains= so a series is one colour throughout."
        )
    measure_modes = {bool(settings_of(i).get("measure")) for i in images}
    if len(measure_modes) > 1:
        notes.append("WARNING: some captures are in measurement mode and some are not")
    elif measure_modes:
        notes.append(f"measurement mode {'on' if measure_modes.pop() else 'off'}")

    if len(points) < 2:
        return Measurement.refuse(
            METHOD,
            "x per doubling",
            f"only {len(points)} usable captures; this needs {MIN_POINTS} at different exposures",
            n=len(points),
            n_min=MIN_POINTS,
            limit=FIT_LIMIT,
            limit_justification=FIT_JUSTIFICATION,
            notes=notes,
            inputs=[str(directory)],
        )

    floor_record = NoiseFloor.load(directory)
    if floor_record:
        levels = sorted(p[1] for p in points)
        smallest = min(b - a for a, b in pairwise(levels))
        notes.append(
            f"the same-against-same floor for this directory is {floor_record.levels_dn:.2f} DN; "
            f"the smallest step between these captures is {smallest:.2f} DN"
        )
        if smallest < floor_record.levels_dn:
            return Measurement.refuse(
                METHOD,
                "x per doubling",
                f"two of these captures differ by {smallest:.2f} DN, which is inside the "
                f"instrument's own noise floor of {floor_record.levels_dn:.2f} DN. Spread the "
                f"exposures further apart.",
                n=len(points),
                n_min=MIN_POINTS,
                limit=FIT_LIMIT,
                limit_justification=FIT_JUSTIFICATION,
                notes=notes,
                inputs=[str(directory)],
            )
    else:
        notes.append(
            "no same-against-same floor recorded here, so nothing checked whether these "
            "steps are larger than the instrument's own noise. Run deskcam aatest."
        )

    x = np.log2(np.array([p[0] for p in points]))
    y = np.log2(np.array([p[1] for p in points]))
    n = len(points)
    slope, intercept = np.polyfit(x, y, 1)
    predicted = slope * x + intercept
    residual = float(((y - predicted) ** 2).sum())
    total = float(((y - y.mean()) ** 2).sum())
    r_squared = 1.0 - residual / total if total > 0 else 0.0

    # The interval on the exponent, then carried through 2**a to the familiar figure.
    interval = None
    if n > 2:
        se_slope = math.sqrt(residual / (n - 2) / float(((x - x.mean()) ** 2).sum()))
        half = t95(n - 2) * se_slope
        interval = (float(2 ** (slope - half)), float(2 ** (slope + half)))

    notes.append(f"exponent {slope:.4f} (1.0 is linear, about 0.45 is an sRGB curve)")

    # A constant offset in the levels bends the exponent, and it bends it upward when the
    # offset is negative. An exponent above 1.0 is far more likely to be a black level
    # pedestal of a digit or two than a sensor that responds better than linearly, so the
    # pedestal is measured and reported rather than left for the reader to suspect.
    raw_x = np.array([p[0] for p in points])
    raw_y = np.array([p[1] for p in points])
    _, pedestal = np.polyfit(raw_x, raw_y, 1)
    notes.append(f"straight-line fit gives a pedestal of {pedestal:+.2f} DN at zero exposure")
    if abs(pedestal) > 1.0:
        direction = "above" if pedestal < 0 else "below"
        notes.append(
            f"that pedestal is large enough to push the exponent {direction} 1.0 on its "
            f"own. The response may be linear with an offset rather than non-linear."
        )
    shortest = min(p[0] for p in points) / 1e6
    longest = max(p[0] for p in points) / 1e6
    notes.append(f"exposures {shortest:.2f} to {longest:.2f} ms")
    notes.append(f"levels {min(p[1] for p in points):.1f} to {max(p[1] for p in points):.1f} DN")
    notes.append(f"region {region} of each frame")

    return Measurement(
        method=METHOD,
        unit="x per doubling",
        value=float(2**slope),
        interval=interval,
        n=n,
        n_min=MIN_POINTS,
        confidence=float(r_squared),
        confidence_kind="power-law fit R squared",
        limit=FIT_LIMIT,
        limit_justification=FIT_JUSTIFICATION,
        notes=notes,
        inputs=[p[2] for p in points],
    ).gate()
