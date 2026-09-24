"""
Tests for solving the mat from corners somebody already located.

No OpenCV and no picture. The corners are computed by applying a homography this file
chose to the millimetre corners the sheet declares, which is exactly the shape of input an
agent produces when it reads the markers off a photograph by eye. So these run on a base
install, and they are the ones that decide whether the optional extra is genuinely
optional.

Nothing here needs a phone, a printed mat, or a camera.
"""

from __future__ import annotations

import math

import numpy as np
import pytest
from analysis import mat

SHEET = "deskcam-mat-a4-landscape-hybrid"
SIZE = (1600, 1200)


def mapping(scale=4.0, dx=60.0, dy=40.0, theta=0.0, perspective=(0.0, 0.0)):
    c, s = math.cos(theta), math.sin(theta)
    return np.array([
        [scale * c, -scale * s, dx],
        [scale * s, scale * c, dy],
        [perspective[0], perspective[1], 1.0],
    ], dtype=float)


def corners(H, ids=None) -> dict[int, list]:
    """The marker corners as a reader of the picture would report them."""
    spec = mat.sheets()[SHEET]
    return {
        m["id"]: mat._apply(H, np.array(m["corners"], dtype=float)).tolist()
        for m in spec["codedMarkers"] if ids is None or m["id"] in ids
    }


def test_the_solver_needs_nothing_but_numpy():
    H = mapping()
    solved = mat.solve(corners(H), SIZE)

    assert solved.sheet == SHEET
    assert solved.source == "observed"
    page = np.array([[0, 0], [297, 0], [297, 210], [0, 210], [148, 105]], dtype=float)
    assert np.allclose(solved.to_pixels(page), mat._apply(H, page), atol=1e-6)
    assert np.allclose(solved.to_mm(solved.to_pixels(page)), page, atol=1e-9)
    assert solved.residual_mm < 1e-6
    assert solved.mm_per_pixel() == pytest.approx(0.25, rel=1e-6)


def test_it_reads_a_tilted_mat_and_a_mat_seen_at_an_angle():
    turned = mat.solve(corners(mapping(theta=math.radians(30))), SIZE)
    assert turned.rotation_degrees() == pytest.approx(30.0, abs=0.01)

    # A homography, not an affine: a sheet photographed off-square still reads true.
    skew = mapping(theta=math.radians(-12), perspective=(2.5e-4, 1.1e-4))
    seen = mat.solve(corners(skew), SIZE)
    page = np.array([[0, 0], [297, 0], [297, 210], [0, 210]], dtype=float)
    assert np.allclose(seen.to_mm(mat._apply(skew, page)), page, atol=1e-6)


def test_frame_fractions_round_trip_through_the_mat():
    """What a mark actually stores, back to a place on the page and out again."""
    solved = mat.solve(corners(mapping()), SIZE)
    part_mm = np.array([[120.0, 95.0]])
    frame = solved.to_frame(part_mm)
    assert (0 < frame).all() and (frame < 1).all()
    assert np.allclose(solved.from_frame(frame), part_mm, atol=1e-6)


def test_a_moved_camera_is_measured_in_millimetres():
    before = mat.solve(corners(mapping()), SIZE)
    same = mat.solve(corners(mapping()), SIZE)
    shifted = mat.solve(corners(mapping(dx=80.0)), SIZE)     # 20 px at 4 px/mm
    zoomed = mat.solve(corners(mapping(scale=6.0)), SIZE)

    assert mat.moved(before, same) < 1e-6
    assert mat.moved(before, shifted) == pytest.approx(5.0, abs=1e-3)
    assert mat.moved(before, zoomed) > 2.0

    # And the part stays where it is on the page, which is the whole point.
    part_mm = np.array([[120.0, 95.0]])
    assert np.allclose(zoomed.to_mm(zoomed.to_pixels(part_mm)), part_mm, atol=1e-9)


def test_it_refuses_rather_than_describing_a_page_it_cannot_see():
    H = mapping()

    with pytest.raises(ValueError, match="need 4"):
        mat.solve(corners(H, ids={8, 9, 10}), SIZE)

    # Enough markers, all along the top edge: they describe that edge and say nothing
    # about the rest of the sheet, which is the quiet way to a confident wrong answer
    # about a part at the bottom of it.
    with pytest.raises(ValueError, match="too close together"):
        mat.solve(corners(H, ids={8, 9, 10, 11}), SIZE)

    with pytest.raises(ValueError, match="no mat markers given"):
        mat.solve({}, SIZE)

    with pytest.raises(ValueError, match="match no sheet"):
        mat.solve({91: [[0, 0]] * 4, 92: [[1, 1]] * 4,
                   93: [[2, 2]] * 4, 94: [[3, 3]] * 4}, SIZE)

    with pytest.raises(ValueError, match="needs 4 corners"):
        bad = corners(H)
        bad[8] = bad[8][:3]
        mat.solve(bad, SIZE)


def test_a_misread_corner_is_refused_not_averaged_away():
    """An agent reading corners by eye will sometimes read one wrongly.

    The fit must not absorb it: eight markers give plenty of freedom to spread one bad
    corner across the page as a small error everywhere, which is worse than a refusal
    because nothing downstream can tell.
    """
    H = mapping()
    good = corners(H)
    assert mat.solve(good, SIZE).residual_mm < 1e-6

    nudged = {k: [list(c) for c in v] for k, v in good.items()}
    nudged[12][0][0] += 40.0          # 40 px at 4 px/mm is 10 mm out
    with pytest.raises(ValueError, match="misses a marker corner"):
        mat.solve(nudged, SIZE)


def test_the_detector_is_optional_and_says_so_when_missing(monkeypatch):
    assert mat.have_detector() in (True, False)

    import builtins
    real = builtins.__import__

    def no_cv2(name, *a, **k):
        if name == "cv2":
            raise ImportError("no cv2")
        return real(name, *a, **k)

    monkeypatch.setattr(builtins, "__import__", no_cv2)
    assert mat.have_detector() is False
    with pytest.raises(ValueError, match="no marker detector"):
        mat.detect("anything.jpg")
