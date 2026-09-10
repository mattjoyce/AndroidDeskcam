"""
How far apart two points in a capture are, in millimetres.

The arithmetic is one line. Everything else here is about the one question that decides
whether the answer means anything: does the scale in this capture's sidecar describe this
capture? The sidecar carries that judgement already, made when the capture was written and
recorded with its reason, so this reads it rather than making a second one that could
disagree. Card 23, card 31.
"""

from __future__ import annotations

import math
from pathlib import Path

from .images import load_sidecar
from .result import Measurement

METHOD = "distance"


def parse_point(text: str) -> tuple[float, float]:
    """A point as x,y in pixels of the capture, with the origin at the top left."""
    parts = [p.strip() for p in text.split(",")]
    if len(parts) != 2:
        raise ValueError(f"a point is x,y in pixels, e.g. 640,480; got {text!r}")
    try:
        return float(parts[0]), float(parts[1])
    except ValueError:
        raise ValueError(f"a point is x,y in pixels, e.g. 640,480; got {text!r}") from None


def measure(image: Path, a: tuple[float, float], b: tuple[float, float]) -> Measurement:
    sidecar = load_sidecar(image)
    notes = [f"from ({a[0]:g}, {a[1]:g}) to ({b[0]:g}, {b[1]:g}) in pixels"]
    inputs = [str(image)]

    def refuse(reason: str) -> Measurement:
        return Measurement.refuse(METHOD, "mm", reason, n=0, n_min=1, notes=notes, inputs=inputs)

    if not sidecar:
        return refuse(
            f"{image.name} has no sidecar, so nothing records how it was taken. "
            "A distance in pixels is not a distance."
        )
    scale = sidecar.get("scale")
    if not isinstance(scale, dict):
        return refuse(
            f"{image.name} carries no scale. Measure one from a rule or graph paper in "
            "the frame with deskcam scale, then take the capture."
        )
    if not scale.get("applies"):
        why = scale.get("why") or "the framing changed after the scale was measured"
        return refuse(f"the scale in {image.name} does not describe it: {why}")
    px_per_mm = scale.get("px_per_mm")
    if not isinstance(px_per_mm, (int, float)) or px_per_mm <= 0:
        return refuse(f"the scale in {image.name} is not a number of pixels per millimetre")

    if source := scale.get("measured_from"):
        notes.append(f"scale measured from {source}")
        inputs.append(str(source))
    notes.append(f"{px_per_mm:.4g} px/mm")

    pixels = math.dist(a, b)
    notes.append(f"{pixels:.2f} px apart")
    millimetres = pixels / px_per_mm

    # The interval is the scale's own, carried through the division. More pixels per
    # millimetre is a shorter distance, so the ends swap. Nothing here adds uncertainty of
    # its own: the two points came from a person reading them off the picture, and how
    # well they did that is not something this tool can see.
    interval = None
    span = scale.get("interval")
    if isinstance(span, (list, tuple)) and len(span) == 2 and all(x > 0 for x in span):
        interval = (pixels / span[1], pixels / span[0])
        notes.append(
            "the interval is the scale's, and says nothing about how well the "
            "two points were placed"
        )

    return Measurement(
        method=METHOD,
        unit="mm",
        value=millimetres,
        interval=interval,
        n=1,
        n_min=1,
        notes=notes,
        inputs=inputs,
    ).gate()
