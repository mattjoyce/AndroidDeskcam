"""
Tests for finding the mat.

Synthetic pictures with a known answer, in the spirit of test_analysis.py: the markers are
drawn at the millimetre corners the sheet declares, then warped by a homography this file
chose, so the mapping the solver recovers can be checked against the one it was given.
A real photograph cannot do that, because a real photograph has no known answer.

Nothing here needs a phone, and nothing here needs the mat printed.
"""

from __future__ import annotations

import numpy as np
import pytest
from analysis import mat

cv2 = pytest.importorskip("cv2", reason="rendering a test picture needs the mat extra")

SHEET = "deskcam-mat-a4-landscape-hybrid"


def render(tmp_path, H, size=(1600, 1200), ids=None, name="mat.png"):
    """Draw the sheet's markers under a known mm -> pixel mapping."""
    spec = mat.sheets()[SHEET]
    d = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, spec["dictionary"]))
    img = np.full((size[1], size[0]), 255, np.uint8)
    for m in spec["codedMarkers"]:
        if ids is not None and m["id"] not in ids:
            continue
        # The marker bitmap, warped from its own square into the place on the page the
        # sheet says it occupies, so the picture is built the way the printer built it.
        cell = cv2.aruco.generateImageMarker(d, m["id"], 200)
        src = np.array([[0, 0], [199, 0], [199, 199], [0, 199]], dtype=np.float32)
        dst = mat._apply(H, np.array(m["corners"], dtype=float)).astype(np.float32)
        warp = cv2.getPerspectiveTransform(src, dst)
        cv2.warpPerspective(cell, warp, size, img,
                            flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_TRANSPARENT)
    path = tmp_path / name
    cv2.imwrite(str(path), img)
    return path


def mapping(scale=4.0, dx=60.0, dy=40.0):
    """A plain scale and offset, millimetres to pixels."""
    return np.array([[scale, 0, dx], [0, scale, dy], [0, 0, 1]], dtype=float)


def test_it_finds_the_sheet_and_recovers_the_mapping(tmp_path):
    H = mapping()
    found = mat.detect(render(tmp_path, H))

    assert found.sheet == SHEET
    assert found.revision == "DCM-02-L"
    assert set(found.ids) == set(mat.sheets()[SHEET]["markerIds"])

    # The mapping it solved must agree with the one the picture was drawn with, checked
    # where it matters: across the whole page, not at one convenient corner.
    page = np.array([[0, 0], [297, 0], [297, 210], [0, 210], [148, 105]], dtype=float)
    assert np.allclose(found.to_pixels(page), mat._apply(H, page), atol=1.0)
    assert np.allclose(found.to_mm(found.to_pixels(page)), page, atol=0.05)
    assert found.residual_mm < 0.25
    assert found.mm_per_pixel() == pytest.approx(1 / 4.0, rel=0.02)


def test_a_moved_camera_is_measured_in_millimetres(tmp_path):
    still = mat.detect(render(tmp_path, mapping(), name="a.png"))
    same = mat.detect(render(tmp_path, mapping(), name="b.png"))
    # 20 px at 4 px/mm is 5 mm of page, whatever the picture's framing happens to be.
    shifted = mat.detect(render(tmp_path, mapping(dx=80.0), name="c.png"))

    assert mat.moved(still, same) < 0.2
    assert mat.moved(still, shifted) == pytest.approx(5.0, abs=0.2)


def test_it_refuses_rather_than_describing_a_page_it_cannot_see(tmp_path):
    # Too few. Three markers leave the fourth degree of freedom unconstrained.
    with pytest.raises(ValueError, match="need 4"):
        mat.detect(render(tmp_path, mapping(), ids={8, 9, 10}, name="few.png"))

    # Enough markers, all bunched along the top edge: they describe that edge and say
    # nothing about the rest of the sheet, which is the quiet way to a confident wrong
    # answer about a part at the bottom of it.
    with pytest.raises(ValueError, match="too close together"):
        mat.detect(render(tmp_path, mapping(), ids={8, 9, 10, 11}, name="row.png"))

    with pytest.raises(ValueError, match="no mat markers"):
        mat.detect(render(tmp_path, mapping(), ids=set(), name="blank.png"))

    with pytest.raises(ValueError, match="cannot read"):
        mat.detect(tmp_path / "nothing.png")


def test_a_mark_survives_the_camera_moving(tmp_path):
    """The point of the whole module: a place on the mat, not a place on the sensor."""
    before = mat.detect(render(tmp_path, mapping(), name="before.png"))
    after = mat.detect(render(tmp_path, mapping(scale=6.0, dx=10.0), name="after.png"))

    part_mm = np.array([[120.0, 95.0]])
    # Where it sat in the first picture, carried through the mat into the second.
    px_before = before.to_pixels(part_mm)
    px_after = after.to_pixels(part_mm)
    assert not np.allclose(px_before, px_after, atol=20)      # the view really did move
    assert np.allclose(after.to_mm(px_after), part_mm, atol=0.05)

    # And as the 0..1 frame fractions a mark is actually placed in.
    fx, fy = after.to_frame(part_mm)[0]
    assert 0 < fx < 1 and 0 < fy < 1
