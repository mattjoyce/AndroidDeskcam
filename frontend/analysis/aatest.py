"""
The same against the same. What the instrument cannot tell apart.

Take two captures with identical settings of a subject that has not moved. Whatever differs
between them is the camera, not the world. That difference is the floor, and a measurement
smaller than it means nothing at all.

Three wrong measurements were made in one session and every one had this shape. An FFT
locked onto a different harmonic in each tile and reported a 71% spread. A scale figure was
printed as firm beside its own correlation of 0.08. Nothing in the system objected to any of
them, because nothing knew how large a difference had to be before it was a difference.
"""

from __future__ import annotations

import time
from pathlib import Path

import numpy as np

from .images import gains_differ, gains_of, level_health, load_gray, load_sidecar, region_of
from .result import Measurement, NoiseFloor

METHOD = "aa-test"

# Pixels pinned at either end of the range cannot vary, so they drag the floor down and
# make the instrument look better than it is.
CLEAN_LIMIT = 0.99
CLEAN_JUSTIFICATION = (
    "A pixel at 0 or 255 cannot record a difference, so it contributes zero noise and "
    "flatters the floor. Above 1% pinned, the floor is describing the clip and not the "
    "camera."
)
MIN_PIXELS = 1000

# How far apart two sets of white balance gains may be and still describe one colour.
#
# Half a percent. The camera reports gains to three decimals and an automatic white
# balance wanders a little between adjacent frames, so equality would refuse almost every
# honest pair. Half a percent is far below any difference that would move a noise floor
# and far above the last digit.
GAIN_TOLERANCE = 0.005
CENTRE = (0.5, 0.5, 0.30, 0.30)

# The settings that must match for the comparison to mean anything. Presentation
# parameters are deliberately absent: they change the file, not the measurement.
MUST_MATCH = (
    "camera",
    "zoom",
    "cx",
    "cy",
    "rotate",
    "measure",
    "torch",
    "ae",
    "exposure_ns",
    "iso",
    "awb",
    "focus_diopters",
)


def measure(
    first: Path,
    second: Path,
    region: tuple[float, float, float, float] = CENTRE,
    write_to: Path | None = None,
) -> Measurement:
    notes: list[str] = []
    a_settings = load_sidecar(first).get("settings", {})
    b_settings = load_sidecar(second).get("settings", {})

    if not a_settings or not b_settings:
        return Measurement.refuse(
            METHOD,
            "DN",
            "both captures need a sidecar; without one nothing can confirm the settings "
            "were the same, and this test is meaningless if they were not",
            n=0,
            n_min=MIN_PIXELS,
            limit=CLEAN_LIMIT,
            limit_justification=CLEAN_JUSTIFICATION,
            inputs=[str(first), str(second)],
        )

    differing = [k for k in MUST_MATCH if a_settings.get(k) != b_settings.get(k)]
    if differing:
        detail = ", ".join(
            f"{k} {a_settings.get(k)} against {b_settings.get(k)}" for k in differing
        )
        return Measurement.refuse(
            METHOD,
            "DN",
            f"these two captures were not taken with the same settings: {detail}. "
            f"The difference between them is the settings, not the instrument.",
            n=0,
            n_min=MIN_PIXELS,
            limit=CLEAN_LIMIT,
            limit_justification=CLEAN_JUSTIFICATION,
            notes=notes,
            inputs=[str(first), str(second)],
        )

    # The gains are in `measured`, not in `settings`, which is why MUST_MATCH above cannot
    # catch this. Two captures can agree on awb and awb_lock and still have been taken
    # through different colour, because a lock holds whatever the gains happened to be.
    # Card 42.
    a_gains, b_gains = gains_of(first), gains_of(second)
    if gains_differ(a_gains, b_gains, GAIN_TOLERANCE):
        return Measurement.refuse(
            METHOD,
            "DN",
            f"these two captures were taken through different white balance gains, "
            f"{a_gains} against {b_gains}. That is a difference in colour, not in the "
            f"instrument. Set them with awbgains=neutral, or awbgains=R,GE,GO,B, so a "
            f"later session can be compared with this one.",
            n=0,
            n_min=MIN_PIXELS,
            limit=CLEAN_LIMIT,
            limit_justification=CLEAN_JUSTIFICATION,
            notes=notes,
            inputs=[str(first), str(second)],
        )
    if a_gains is not None:
        notes.append(f"white balance gains {a_gains}")
        if a_settings.get("awb_gains_set") is None:
            notes.append(
                "these gains were found by the camera and not chosen, so another session "
                "will lock different ones. awbgains=neutral makes this repeatable."
            )

    if a_settings.get("ae") == "auto":
        notes.append(
            "WARNING: automatic exposure was on. The floor measured here includes the AE "
            "loop moving between the two frames, which is a property of the mode and not "
            "of the sensor. Fix the exposure and the ISO for a floor you can compare against."
        )

    a = region_of(load_gray(first), region)
    b = region_of(load_gray(second), region)
    if a.shape != b.shape:
        return Measurement.refuse(
            METHOD,
            "DN",
            f"the two captures are different sizes, {a.shape} and {b.shape}",
            n=0,
            n_min=MIN_PIXELS,
            limit=CLEAN_LIMIT,
            limit_justification=CLEAN_JUSTIFICATION,
            notes=notes,
            inputs=[str(first), str(second)],
        )

    health = level_health(np.concatenate([a.ravel(), b.ravel()]))
    clean = 1.0 - health["at_floor"] - health["at_ceiling"]

    difference = a - b
    # The difference of two independent frames carries twice the variance of one, so the
    # per-capture floor is the spread of the difference divided by the square root of two.
    floor_dn = float(difference.std(ddof=1) / np.sqrt(2))
    mean_level = float(health["mean"])
    relative = floor_dn / mean_level if mean_level > 0 else 0.0

    notes.append(f"region mean {mean_level:.1f} DN, {a.size} pixels compared")
    notes.append(f"median absolute difference {float(np.median(np.abs(difference))):.3f} DN")
    notes.append(
        f"a measurement of this scene must differ by more than {floor_dn:.2f} DN "
        f"({relative:.2%} of the level) before it is a difference and not this camera"
    )
    notes.append(
        f"exposure {a_settings.get('exposure_ns')} ns, iso {a_settings.get('iso')}, "
        f"measure {a_settings.get('measure')}"
    )

    result = Measurement(
        method=METHOD,
        unit="DN",
        value=floor_dn,
        interval=None,
        n=int(a.size),
        n_min=MIN_PIXELS,
        confidence=float(clean),
        confidence_kind="fraction of pixels not pinned",
        limit=CLEAN_LIMIT,
        limit_justification=CLEAN_JUSTIFICATION,
        notes=notes,
        inputs=[str(first), str(second)],
    ).gate()

    if result.ok and write_to is not None:
        record = NoiseFloor(
            levels_dn=floor_dn,
            relative=relative,
            n_pixels=int(a.size),
            measured_at=time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            settings={k: a_settings.get(k) for k in MUST_MATCH},
            inputs=[first.name, second.name],
        )
        path = record.save(write_to)
        result.notes.append(f"written to {path}")
    return result
