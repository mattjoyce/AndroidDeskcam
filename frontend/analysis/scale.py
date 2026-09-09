"""
Pixels per millimetre, measured from a regular reference in the frame.

The scale cannot be stored. It changes with every change of stand position or distance, so
the only figure that is true for a picture is one measured from something inside that
picture: a steel rule, graph paper, anything with a known pitch.

The method, and why it is this method. Take a strip across the ticks, remove the shading
with a window much wider than the tick pitch, then take the first autocorrelation peak.
An earlier attempt used an FFT over whole tiles and failed: it locked onto a different
harmonic in each tile and reported a 71% spread that did not exist. Autocorrelation with a
peak-height threshold and agreement across strips catches that, because a strip that has
locked onto the wrong harmonic both correlates badly and disagrees with its neighbours.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np

from .images import load_gray, region_of
from .result import Measurement, mean_interval

METHOD = "scale"

# The limit, and the evidence for it.
#
# In the measurement of 2026-09-09 six strips agreed at 16 px/mm with normalised peak
# heights from 0.76 to 0.96, and two strips locked onto the second harmonic at 33 px with
# peaks near 0.33. The limit goes in the gap. 0.60 sits well clear of the highest observed
# failure and well below the lowest observed success, so it does not depend on either
# number being exact.
PEAK_LIMIT = 0.60
PEAK_JUSTIFICATION = (
    "Six correct strips measured 0.76 to 0.96; two strips that had locked onto the second "
    "harmonic measured about 0.33. 0.60 lies in the gap, clear of both."
)

# Strips must agree with each other, not merely correlate well on their own. A harmonic is
# a whole multiple of the truth, so it misses by 100% or more and a 12% window excludes it
# while still allowing for genuine perspective across the frame.
AGREEMENT = 0.12
MIN_STRIPS = 4


def _detrend(profile: np.ndarray, window: int) -> np.ndarray:
    """Removes illumination shading, which is slow, and keeps the ticks, which are fast."""
    window = max(3, window | 1)
    if window >= profile.size:
        return profile - profile.mean()
    kernel = np.ones(window) / window
    smooth = np.convolve(profile, kernel, mode="same")
    edge = window // 2
    smooth[:edge] = smooth[edge]
    smooth[-edge:] = smooth[-edge - 1]
    return profile - smooth


def _first_peak(profile: np.ndarray, min_lag: int, max_lag: int) -> tuple[float, float]:
    """
    The pitch of a periodic profile, and how strongly it repeats.

    Returns the lag of the first autocorrelation peak, interpolated to sub-pixel precision,
    and its height relative to zero lag. A height near 1 means the pattern repeats almost
    exactly; the tick pattern of a rule sits around 0.8.
    """
    centred = profile - profile.mean()
    if not np.any(centred):
        return 0.0, 0.0
    size = 1 << int(np.ceil(np.log2(centred.size * 2)))
    spectrum = np.fft.rfft(centred, size)
    correlation = np.fft.irfft(spectrum * np.conj(spectrum), size)[: centred.size]
    if correlation[0] <= 0:
        return 0.0, 0.0
    correlation = correlation / correlation[0]

    max_lag = min(max_lag, correlation.size - 2)
    if max_lag <= min_lag:
        return 0.0, 0.0
    window = correlation[min_lag : max_lag + 1]
    # The first local maximum, not the tallest. The tallest is often a higher multiple of
    # the true pitch, which is exactly the harmonic trap this method exists to avoid.
    best = -1
    for i in range(1, window.size - 1):
        if window[i] > window[i - 1] and window[i] >= window[i + 1] and window[i] > 0.2:
            best = i
            break
    if best < 0:
        best = int(np.argmax(window))
        if best == 0 or best == window.size - 1:
            return 0.0, 0.0
    lag = min_lag + best
    height = float(correlation[lag])
    # Parabolic interpolation through the three points around the peak.
    y0, y1, y2 = correlation[lag - 1], correlation[lag], correlation[lag + 1]
    denominator = y0 - 2 * y1 + y2
    offset = 0.5 * (y0 - y2) / denominator if denominator != 0 else 0.0
    return float(lag + np.clip(offset, -1, 1)), height


def _strips(gray: np.ndarray, axis: int, strip_long: int, strip_thick: int) -> list[np.ndarray]:
    """Profiles taken across a frame, along the axis the ticks are expected to repeat on."""
    image = gray if axis == 1 else gray.T
    height, width = image.shape
    strip_long = min(strip_long, width)
    out = []
    # Short strips on purpose. A rule is never perfectly square to the sensor, and across a
    # full frame a two degree tilt walks the ticks by several whole pitches, which smears
    # the correlation into nothing.
    for y in range(0, height - strip_thick + 1, max(1, strip_thick)):
        for x in range(0, width - strip_long + 1, max(1, strip_long // 2)):
            out.append(image[y : y + strip_thick, x : x + strip_long].mean(axis=0))
    return out


def _families(pitches: list[float], heights: list[float]) -> list[dict[str, float]]:
    """
    Groups the strips by the pitch they found.

    A bench usually has more than one regular thing on it. This frame had a steel rule at
    1 mm and graph paper at 5 mm, and the first run of this tool measured the graph paper,
    was told it was looking at millimetres, and reported a scale five times too large with
    107 strips agreeing and a healthy correlation. Nothing about the fit was wrong. The
    assumption about what it had found was wrong, and the only defence is to say out loud
    what else is in the frame.
    """
    order = sorted(range(len(pitches)), key=lambda i: pitches[i])
    groups: list[list[int]] = []
    for i in order:
        if groups and abs(pitches[i] - pitches[groups[-1][-1]]) / pitches[i] <= AGREEMENT:
            groups[-1].append(i)
        else:
            groups.append([i])
    out = []
    for g in groups:
        out.append(
            {
                "pitch_px": float(np.median([pitches[i] for i in g])),
                "peak": float(np.median([heights[i] for i in g])),
                "strips": float(len(g)),
            }
        )
    return sorted(out, key=lambda f: -f["strips"])


def measure(
    image_path: Path,
    pitch_mm: float = 1.0,
    min_pitch_px: int = 4,
    max_pitch_px: int = 120,
    strip_long: int = 512,
    strip_thick: int = 16,
    detrend: int | None = None,
    region: tuple[float, float, float, float] | None = None,
) -> Measurement:
    gray = load_gray(image_path)
    full_width = gray.shape[1]
    notes = [f"image {gray.shape[1]}x{gray.shape[0]}"]
    if region:
        gray = region_of(gray, region)
        notes.append(f"region {region} selects {gray.shape[1]}x{gray.shape[0]}")
    notes.append(f"reference pitch given as {pitch_mm} mm")

    best: tuple[list[float], list[float], str, list[float], list[float]] | None = None
    for axis, name in ((1, "horizontal"), (0, "vertical")):
        pitches: list[float] = []
        heights: list[float] = []
        for profile in _strips(gray, axis, strip_long, strip_thick):
            window = detrend if detrend else max(31, profile.size // 8)
            lag, height = _first_peak(_detrend(profile, window), min_pitch_px, max_pitch_px)
            if lag > 0 and height >= PEAK_LIMIT:
                pitches.append(lag)
                heights.append(height)
        if not pitches:
            continue
        median = float(np.median(pitches))
        kept = [
            (p, h)
            for p, h in zip(pitches, heights, strict=True)
            if abs(p - median) / median <= AGREEMENT
        ]
        if best is None or len(kept) > len(best[0]):
            # The unfiltered lists are carried too. The agreement window is what picks the
            # answer, and running the family search on its survivors would only ever find
            # one family, which is how the first version of this hid a second reference
            # instead of reporting it.
            best = ([p for p, _ in kept], [h for _, h in kept], name, pitches, heights)

    if best is None or not best[0]:
        return Measurement.refuse(
            METHOD,
            "px/mm",
            "no strip in this image repeats strongly enough to be a scale reference; "
            "is there a rule or graph paper in the frame, and is it in focus?",
            n=0,
            n_min=MIN_STRIPS,
            limit=PEAK_LIMIT,
            limit_justification=PEAK_JUSTIFICATION,
            notes=notes,
            inputs=[str(image_path)],
        )

    pitches, heights, axis_name, all_pitches, all_heights = best
    notes.append(f"ticks repeat {axis_name}ly")

    families = _families(all_pitches, all_heights)
    if len(families) > 1:
        others = []
        for f in families[1:]:
            ratio = f["pitch_px"] / families[0]["pitch_px"]
            near_multiple = abs(ratio - round(ratio)) < 0.08 and round(ratio) > 1
            kind = f"harmonic x{round(ratio)}" if near_multiple else "a different reference"
            others.append(f"{f['pitch_px']:.2f} px ({f['strips']:.0f} strips, {kind})")
        notes.append(
            "more than one regular pattern is in this frame, and only you know which one "
            "is the reference. Also present: " + "; ".join(others) + ". Use --region to choose one."
        )

    pitch_px, span = mean_interval(pitches)
    px_per_mm = pitch_px / pitch_mm
    interval = None
    if span:
        interval = (span[0] / pitch_mm, span[1] / pitch_mm)

    notes.append(f"tick pitch {pitch_px:.2f} px")
    notes.append(f"{1000.0 / px_per_mm:.2f} micrometres per pixel")
    # The frame, not the region that was searched inside it.
    notes.append(f"field of view {full_width / px_per_mm:.1f} mm wide")

    return Measurement(
        method=METHOD,
        unit="px/mm",
        value=px_per_mm,
        interval=interval,
        n=len(pitches),
        n_min=MIN_STRIPS,
        confidence=float(np.median(heights)),
        confidence_kind="autocorrelation peak height",
        limit=PEAK_LIMIT,
        limit_justification=PEAK_JUSTIFICATION,
        notes=notes,
        inputs=[str(image_path)],
    ).gate()
