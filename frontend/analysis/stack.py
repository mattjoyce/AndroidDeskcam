"""
One sharp image out of a focus sweep.

A board has parts of different heights and the lens has one or two millimetres of depth of
field at bench distance, so no single frame is sharp everywhere. The sweep of card 5 walks
the lens through the subject; this takes the sharp part of each frame and puts them
together. Card 6.

Three things make it work, and the second is the one that is easy to miss.

**Sharpness per pixel.** The magnitude of the Laplacian, smoothed. Card 9 uses the variance
of the same operator for a whole frame, and this is the same physics per pixel: a focused
edge has a large second derivative and a blurred one has almost none.

**Focus breathing.** Moving the lens changes the magnification slightly, so the frames are
not the same size on the subject. Stacking them without correcting for it puts a sharp edge
next to where the same edge is in another frame, which is what haloes in a bad stack are.
The scale is measured from the frames themselves rather than assumed.

**No hard choice per pixel.** Taking the single sharpest frame at each pixel produces a
seam wherever the winner changes, because two frames disagree slightly about colour and
brightness. Blending by a smoothed weight instead means the transition is gradual and the
seam has nowhere to appear.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image

from .images import captures_in, image_size, settings_of
from .png16 import write as write_png16
from .result import Measurement

METHOD = "focus-stack"

MIN_FRAMES = 3

# The range of magnification change a lens is allowed to have across a sweep, and how
# finely it is searched. Focus breathing on a phone lens is well under a percent between
# neighbouring steps and a couple of percent across a full sweep; anything outside this is
# not breathing, it is a subject that moved.
SCALE_RANGE = 0.04
SCALE_STEPS = 81

# The sharpness map is smoothed over this many pixels before it is used as a weight. Small
# enough to follow the edge of a component, large enough that noise does not win a pixel.
SMOOTH = 15

# How decisively the sharpest frame wins a pixel.
#
# Applied to each frame's sharpness as a fraction of the best at that pixel, not to the
# sharpness itself, and that distinction is the whole of it. On an absolute scale a
# low-contrast subject gives every frame a similar small number, a fourth power of similar
# numbers is still similar, and the result is a plain average of the sweep: measured at
# 0.368 times the sharpness of the best single frame, which is a blur and not a stack.
# Against the best at each pixel, a frame 5% behind keeps half its weight and one at half
# the sharpness keeps almost none, whatever the contrast of the subject.
DECISIVENESS = 12.0

# How much of the picture must need a frame other than the best one.
#
# The obvious gate is "the stack must be sharper than its best frame", and it is wrong. On
# a subject flat enough to sit inside one depth of field, the best single frame already is
# the answer: blending anything into it, or resampling it to correct a magnification, can
# only take sharpness away. Measured on a rule lying nearly flat, where one frame was
# sharpest over 83% of the picture, every weighting tried landed between 0.48 and 0.86
# times the best frame, and no amount of tuning could have passed a gate set above 1.0.
#
# So the question to refuse on is not how sharp the stack came out. It is whether the
# subject had depth at all. A sweep of something flat does not want stacking, and saying so
# is more use than handing back a slightly worse copy of the sharpest frame.
DEPTH_LIMIT = 0.30
DEPTH_JUSTIFICATION = (
    "A stack is worth making when different parts of the subject are sharp in different "
    "frames. When one frame wins most of the picture, the subject fits inside one depth of "
    "field and that frame is already the answer; blending or resampling can then only "
    "blur it. Below 30% of the picture won by frames other than the best one, this is a "
    "sweep of something flat and the right answer is the sharpest frame."
)

# How well two frames must match before a magnification measured between them is believed.
#
# The correlation of two edge maps, so 1.0 is the same picture and 0 is nothing in common.
# Measured on a synthetic sweep: neighbouring frames match at 0.99 and give the right
# answer, a frame against the blurriest one in the sweep matches at 0.02 and gives 1.2%
# of magnification that is not there, and frames from opposite ends match at -0.38. A
# correction taken from a match below this is noise wearing the shape of a measurement.
MATCH_LIMIT = 0.30

MUST_MATCH = ("camera", "zoom", "cx", "cy", "rotate")


def _laplacian(plane: np.ndarray) -> np.ndarray:
    """The four-neighbour Laplacian, the same operator the phone's sharpness metric uses."""
    out = np.zeros_like(plane)
    out[1:-1, 1:-1] = (
        4 * plane[1:-1, 1:-1]
        - plane[:-2, 1:-1]
        - plane[2:, 1:-1]
        - plane[1:-1, :-2]
        - plane[1:-1, 2:]
    )
    return out


def _box(plane: np.ndarray, size: int) -> np.ndarray:
    """A box blur by summed-area table, which costs the same at any window size."""
    size = max(1, size | 1)
    height, width = plane.shape
    padded = np.pad(plane, size // 2, mode="edge")
    # A zero row and column in front, so the four corners of a window are one index apart
    # from the window itself and the arithmetic below needs no special case at the edges.
    total = np.zeros((padded.shape[0] + 1, padded.shape[1] + 1))
    total[1:, 1:] = padded.cumsum(0).cumsum(1)
    a = total[size : size + height, size : size + width]
    b = total[0:height, size : size + width]
    c = total[size : size + height, 0:width]
    d = total[0:height, 0:width]
    return (a - b - c + d) / (size * size)


def _resample(plane: np.ndarray, scale: float) -> np.ndarray:
    """
    The plane magnified about its centre by `scale`, bilinearly.

    Written out rather than reached for, because scipy is not a dependency of this project
    and this is fifteen lines. Bilinear is enough: the corrections are a fraction of a
    percent, so no pixel moves more than a few pixels anywhere in the frame.
    """
    if scale == 1.0:
        return plane
    height, width = plane.shape
    cy, cx = (height - 1) / 2.0, (width - 1) / 2.0
    ys = (np.arange(height) - cy) / scale + cy
    xs = (np.arange(width) - cx) / scale + cx
    y0 = np.clip(np.floor(ys), 0, height - 2).astype(np.intp)
    x0 = np.clip(np.floor(xs), 0, width - 2).astype(np.intp)
    fy = (ys - y0)[:, None]
    fx = (xs - x0)[None, :]
    top = plane[y0][:, x0] * (1 - fx) + plane[y0][:, x0 + 1] * fx
    bottom = plane[y0 + 1][:, x0] * (1 - fx) + plane[y0 + 1][:, x0 + 1] * fx
    return top * (1 - fy) + bottom * fy


def _best_scale(reference: np.ndarray, plane: np.ndarray) -> tuple[float, float]:
    """
    The magnification that lines this frame up with the reference, and how well it matched.

    Searched rather than derived. The lens calibration is APPROXIMATE, so the relation
    between focus position and magnification is not something to compute from the numbers
    the camera reports; the frames themselves know it.

    The score is the correlation of the two Laplacians, which matches edges to edges rather
    than brightness to brightness, so it is not fooled by the frames differing in exposure.
    It comes back with the answer because it is the only thing that says whether the answer
    means anything.
    """
    ref_edges = np.abs(_laplacian(reference))
    ref_edges -= ref_edges.mean()
    best, best_score = 1.0, -np.inf
    for scale in np.linspace(1 - SCALE_RANGE / 2, 1 + SCALE_RANGE / 2, SCALE_STEPS):
        edges = np.abs(_laplacian(_resample(plane, float(scale))))
        edges -= edges.mean()
        denominator = np.sqrt((ref_edges**2).sum() * (edges**2).sum())
        score = float((ref_edges * edges).sum() / denominator) if denominator > 0 else -np.inf
        if score > best_score:
            best, best_score = float(scale), score
    return best, best_score


def _sharpness_of(plane: np.ndarray) -> float:
    """One number for a whole frame, the same as the phone's metric. Card 9."""
    lap = _laplacian(plane)[1:-1, 1:-1]
    return float(lap.var())


def measure(directory: Path, out: Path | None = None) -> Measurement:
    frames = captures_in(directory)
    notes = [f"{len(frames)} frames in {directory.name}"]
    inputs = [str(f) for f in frames]

    def refuse(reason: str) -> Measurement:
        return Measurement.refuse(
            METHOD,
            "x sharper",
            reason,
            n=len(frames),
            n_min=MIN_FRAMES,
            limit=DEPTH_LIMIT,
            limit_justification=DEPTH_JUSTIFICATION,
            notes=notes,
            inputs=inputs,
        )

    if len(frames) < MIN_FRAMES:
        return refuse(f"{len(frames)} frames is fewer than the {MIN_FRAMES} a stack needs")

    first = settings_of(frames[0])
    for f in frames[1:]:
        other = settings_of(f)
        differing = [k for k in MUST_MATCH if first.get(k) != other.get(k)]
        if differing:
            detail = ", ".join(f"{k} {first.get(k)} against {other.get(k)}" for k in differing)
            return refuse(
                f"a sweep moves the focus and holds the framing still. "
                f"{frames[0].name} and {f.name} differ in {detail}."
            )

    width, height = image_size(frames[0])
    if (width, height) != image_size(frames[-1]):
        return refuse("the frames are not all the same size")

    def luma(path: Path) -> np.ndarray:
        with Image.open(path) as im:
            return np.asarray(im.convert("L"), dtype=np.float64)

    # The sharpest frame is the reference, not the middle one.
    #
    # The middle of a sweep is the obvious choice and it is wrong: a sweep that passes
    # through focus at one end has its blurriest frame in the middle, and correlating every
    # other frame against a blur finds a magnification that is not there. Measured on a
    # synthetic sweep with no scale change at all, a blurred reference produced corrections
    # of a full percent, and resampling by them turned a stack that should have been twice
    # as sharp as its best frame into one that was 0.57 times as sharp.
    #
    # The sharpest frame has the most edge structure to match against, and being the
    # reference it is never resampled, so the frame that contributes most contributes
    # unblurred.
    per_frame = [_sharpness_of(luma(f)) for f in frames]
    middle = int(np.argmax(per_frame))
    notes.append(f"{frames[middle].name} is the sharpest frame and is the reference")
    notes.append("sharpness of each frame " + ", ".join(f"{s:.1f}" for s in per_frame))

    # Measured between neighbours and chained outward, not against the reference directly.
    #
    # Two frames from opposite ends of a sweep are sharp in different places, so their edge
    # maps are anti-correlated: measured on a synthetic sweep, the ends matched the middle
    # at a correlation of -0.38, and the best scale for a pair like that is whatever lines
    # up two unrelated blurs. Neighbouring steps are one small focus change apart, match at
    # 0.99, and multiply into the scale against the reference.
    #
    # Searched on a quarter-size copy: the scale is a property of the whole frame, so a
    # sixteenth of the pixels finds it to the same precision for a sixteenth of the work.
    small = [luma(f)[::2, ::2] for f in frames]
    scales = [1.0] * len(frames)
    step = SCALE_RANGE / (SCALE_STEPS - 1)
    unmatched: list[str] = []

    def chained(index: int, anchor: int, last: float) -> float:
        """Sets scales[index] from its neighbour, and returns the step ratio it used."""
        found, score = _best_scale(small[anchor], small[index])
        if score < MATCH_LIMIT:
            # Breathing is smooth and one-directional across a sweep, so the step that was
            # measured between the last pair is a better guess for this one than no step at
            # all. Falling back to 1.0 here put every later frame in the chain one step out.
            unmatched.append(f"{frames[index].name} ({score:.2f})")
            scales[index] = scales[anchor] * last
            return last
        # A correction the search cannot tell from none is none. Resampling costs a little
        # blur wherever it moves a pixel off the grid, and paying that for a number one
        # search step away from 1.0 is a loss.
        if abs(found - 1.0) <= step:
            found = 1.0
        scales[index] = scales[anchor] * found
        return found

    ratio = 1.0
    for i in range(middle - 1, -1, -1):
        ratio = chained(i, i + 1, ratio)
    ratio = 1.0
    for i in range(middle + 1, len(frames)):
        ratio = chained(i, i - 1, ratio)
    if unmatched:
        notes.append(
            "could not match "
            + ", ".join(unmatched)
            + f" to its neighbour above {MATCH_LIMIT:.2f}. Each took the step measured for "
            "the pair before it, which is the best available guess because breathing is "
            "smooth, and any frame further out along that chain carries the same guess."
        )
    notes.append("magnification against the reference " + ", ".join(f"{s:.4f}" for s in scales))
    spread = max(scales) - min(scales)
    notes.append(f"focus breathing across the sweep is {spread:.2%} of the frame")

    sharpness_maps = []
    for f, scale in zip(frames, scales, strict=True):
        plane = _resample(luma(f), scale)
        sharpness_maps.append(_box(np.abs(_laplacian(plane)), SMOOTH))

    # Relative to the best frame at each pixel, so the weighting behaves the same on a
    # low-contrast subject as on a high-contrast one.
    best_sharpness = np.maximum.reduce(sharpness_maps)
    safe = np.where(best_sharpness > 0, best_sharpness, 1.0)
    weights = [np.power(m / safe, DECISIVENESS) for m in sharpness_maps]
    total = np.sum(weights, axis=0)
    # A patch that is flat in every frame has no sharpest frame. Everything there is the
    # same anyway, so an even blend is both harmless and the only honest answer.
    flat = total <= 0
    if flat.any():
        for w in weights:
            w[flat] = 1.0
        total[flat] = float(len(frames))

    stacked = np.zeros((height, width, 3))
    won = np.zeros(len(frames))
    best = np.argmax(np.stack(weights), axis=0)
    for i, (f, scale, w) in enumerate(zip(frames, scales, weights, strict=True)):
        with Image.open(f) as im:
            colour = np.asarray(im.convert("RGB"), dtype=np.float64)
        for channel in range(3):
            stacked[:, :, channel] += _resample(colour[:, :, channel], scale) * w
        won[i] = float((best == i).mean())
    stacked /= total[:, :, None]

    notes.append(
        "each frame is sharpest over " + ", ".join(f"{p:.0%}" for p in won) + " of the frame"
    )
    depth = 1.0 - float(max(won))
    notes.append(f"{depth:.0%} of the picture is sharpest in a frame other than the best one")

    sharper = _sharpness_of(stacked.mean(axis=2)) / max(per_frame)
    notes.append(f"the stack is {sharper:.3f} times as sharp as the sharpest single frame")

    written = out if out is not None else directory / f"{directory.name}-stack.png"
    try:
        write_png16(written, stacked * 257.0)
    except (OSError, ValueError) as e:
        return refuse(f"could not write {written}: {e}")
    notes.append(f"wrote {written.name}, 16-bit, {width}x{height}")

    record = {
        "tool": "DeskCam",
        "method": METHOD,
        "frames": [
            {"file": f.name, "scale": s, "sharpness": v, "sharpest_over": p}
            for f, s, v, p in zip(frames, scales, per_frame, won, strict=True)
        ],
        "sharper_than_best_frame": sharper,
    }
    written.with_suffix(".json").write_text(json.dumps(record, indent=2))

    return Measurement(
        method=METHOD,
        unit="x sharper",
        value=sharper,
        n=len(frames),
        n_min=MIN_FRAMES,
        confidence=depth,
        confidence_kind="of the picture that needed a frame other than the best one",
        limit=DEPTH_LIMIT,
        limit_justification=DEPTH_JUSTIFICATION,
        notes=notes,
        inputs=inputs,
    ).gate()
