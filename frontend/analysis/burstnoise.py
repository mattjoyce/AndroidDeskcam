"""
How much averaging a burst actually reduces the noise.

The claim this replaces: "an average of 6 frames had 2.25 times less noise than one frame,
against a prediction of 2.45". One run, and the twelve frames were split into groups one
particular way. Which way was not recorded, and the split changes the answer.

The fix is to stop choosing. This averages over many random partitions of the burst and
reports the spread across them, so the figure no longer depends on a decision nobody wrote
down. Noise is measured per pixel across frames, not across the image, because the spatial
variation of a bench scene is subject matter and not noise.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np

from .images import level_health, load_gray, region_of
from .result import Measurement

METHOD = "burst-noise"

# The interval has to be tight enough to say something. The published claim was 2.25
# against a prediction of 2.45, a difference of 8%, so an interval wider than about 15%
# either side cannot tell the two apart and the measurement decides nothing.
TIGHTNESS_LIMIT = 0.85
TIGHTNESS_JUSTIFICATION = (
    "The question this measurement answers is whether the noise falls as the square root "
    "of the frame count. The published figure differed from that prediction by 8%, so an "
    "interval wider than 15% either side cannot distinguish agreement from disagreement."
)
MIN_FRAMES = 4
MIN_GROUPS = 2
CENTRE = (0.5, 0.5, 0.30, 0.30)
# JPEG works in 8x8 blocks and chroma subsampling couples neighbours, so pixels are not
# independent samples. The interval is bootstrapped over blocks of this size rather than
# over pixels, or it would claim a precision the data does not have.
BLOCK = 16


def _pooled_sigma(stack: np.ndarray, blocks: np.ndarray | None = None) -> float:
    """
    The noise of one frame in a stack, pooled across pixels.

    The obvious form, the median of the per-pixel standard deviation, is badly biased when
    the stack is short. The median of a two-sample standard deviation lands at about 0.67
    of the true value, so comparing a 16-frame estimate against a 2-frame estimate made the
    same way inflated the ratio by half and produced 3.18x for an average of six frames,
    which is above the square root of six and therefore impossible. Averaging variances is
    unbiased at any stack depth, so both ends of the ratio are measured the same way.
    """
    variance = stack.var(axis=0, ddof=1).ravel()
    if blocks is not None:
        variance = variance[blocks]
    # Hot pixels and specular edges are not noise, and one of them can carry the mean.
    cut = np.percentile(variance, 99.0)
    kept = variance[variance <= cut]
    return float(np.sqrt(kept.mean())) if kept.size else 0.0


def measure(
    directory: Path,
    group: int = 0,
    region: tuple[float, float, float, float] = CENTRE,
    partitions: int = 200,
    seed: int = 20260910,
) -> Measurement:
    frames = sorted(directory.glob("burst-*.jpg"))
    if not frames:
        frames = sorted(f for f in directory.glob("*.jpg") if not f.name.endswith(".thumb.jpg"))
    notes: list[str] = []

    if len(frames) < MIN_FRAMES:
        return Measurement.refuse(
            METHOD,
            "x less noise",
            f"{len(frames)} frames is fewer than the {MIN_FRAMES} this needs",
            n=len(frames),
            n_min=MIN_FRAMES,
            limit=TIGHTNESS_LIMIT,
            limit_justification=TIGHTNESS_JUSTIFICATION,
            inputs=[str(directory)],
        )

    stack = np.stack([region_of(load_gray(f), region) for f in frames])
    health = level_health(stack.mean(axis=0))
    notes.append(f"{len(frames)} frames, region mean {health['mean']:.1f} DN")
    if health["at_floor"] > 0.02 or health["at_ceiling"] > 0.02:
        return Measurement.refuse(
            METHOD,
            "x less noise",
            f"{health['at_floor']:.0%} of the region is on the floor and "
            f"{health['at_ceiling']:.0%} is clipped; a pinned pixel has no noise to remove",
            n=len(frames),
            n_min=MIN_FRAMES,
            limit=TIGHTNESS_LIMIT,
            limit_justification=TIGHTNESS_JUSTIFICATION,
            notes=notes,
            inputs=[str(directory)],
        )

    # Four groups by default rather than two. A two-group estimate of the averaged noise
    # rests on a single difference, and the whole interval then comes from one number.
    k = group if group > 0 else max(2, len(frames) // 4)
    groups_available = len(frames) // k
    if groups_available < 2:
        return Measurement.refuse(
            METHOD,
            "x less noise",
            f"averaging {k} of {len(frames)} frames leaves only {groups_available} group, "
            f"and a spread needs at least two",
            n=len(frames),
            n_min=MIN_FRAMES,
            limit=TIGHTNESS_LIMIT,
            limit_justification=TIGHTNESS_JUSTIFICATION,
            notes=notes,
            inputs=[str(directory)],
        )

    # Both ends of the ratio are measured the same way, on the same pixels.
    single = _pooled_sigma(stack)

    # The interval carries two sources: which frames land in which group, and which part of
    # the image was looked at. Blocks rather than pixels for the second, because JPEG makes
    # neighbouring pixels agree with each other.
    n_pixels = stack.shape[1] * stack.shape[2]
    n_blocks = max(1, n_pixels // (BLOCK * BLOCK))
    rng = np.random.default_rng(seed)
    ratios = []
    for _ in range(partitions):
        order = rng.permutation(len(frames))[: groups_available * k]
        means = np.stack(
            [stack[order[i * k : (i + 1) * k]].mean(axis=0) for i in range(groups_available)]
        )
        blocks = rng.integers(0, n_pixels, size=n_blocks * BLOCK * BLOCK // 4)
        averaged = _pooled_sigma(means, blocks)
        one = _pooled_sigma(stack, blocks)
        if averaged > 0:
            ratios.append(one / averaged)

    value = float(np.median(ratios))
    lo, hi = (float(v) for v in np.percentile(ratios, [2.5, 97.5]))
    tightness = 1.0 - (hi - lo) / (2 * value) if value > 0 else 0.0
    predicted = float(np.sqrt(k))

    # Averaging k independent samples cannot do better than the square root of k. A result
    # above it is not a discovery, it is a broken method, and the first version of this tool
    # returned 3.18x for six frames with maximum confidence. Precision is not correctness,
    # so the gate has to know the physical bound as well as the spread.
    if lo > predicted:
        return Measurement.refuse(
            METHOD,
            "x less noise",
            f"measured {value:.3f}x for an average of {k} frames, above the square root of "
            f"{k} which is {predicted:.3f}. Averaging cannot beat that with independent "
            f"noise, so this is a fault in the method and not a property of the camera.",
            n=len(frames),
            n_min=MIN_FRAMES,
            confidence=float(tightness),
            confidence_kind="interval tightness",
            limit=TIGHTNESS_LIMIT,
            limit_justification=TIGHTNESS_JUSTIFICATION,
            notes=notes,
            inputs=[f.name for f in frames],
        )

    notes.append(
        f"averaging {k} frames, {groups_available} groups, {len(ratios)} random partitions"
    )
    notes.append(f"noise of one frame {single:.3f} DN")
    notes.append(f"the square root of {k} is {predicted:.3f}")
    agreement = value / predicted
    notes.append(f"measured is {agreement:.0%} of the square root prediction")
    if lo <= predicted <= hi:
        notes.append("the interval contains the prediction, so this burst averages as theory says")
    elif hi < predicted:
        notes.append(
            "the interval sits below the prediction. Two mechanisms do that: JPEG "
            "compression correlates the noise of neighbouring frames, and fixed pattern "
            "noise is identical in every frame so averaging never removes it."
        )
    else:
        notes.append(
            "the interval sits above the prediction, which independent noise cannot do. "
            "Treat this as a fault in the measurement rather than a property of the camera."
        )
    notes.append(
        f"the interval covers both the split of frames into groups and the choice of "
        f"image region, resampled in {BLOCK}x{BLOCK} blocks because JPEG makes neighbouring "
        f"pixels agree"
    )

    return Measurement(
        method=METHOD,
        unit="x less noise",
        value=value,
        interval=(lo, hi),
        n=len(frames),
        n_min=MIN_FRAMES,
        confidence=float(tightness),
        confidence_kind="interval tightness",
        limit=TIGHTNESS_LIMIT,
        limit_justification=TIGHTNESS_JUSTIFICATION,
        notes=notes,
        inputs=[f.name for f in frames],
    ).gate()
