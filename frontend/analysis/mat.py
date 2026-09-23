"""Find the mat in a picture, and map between its millimetres and the sensor.

The mat is a printed calibration target, not just something to lay parts on: eight coded
markers sit at corners this repository already knows, in `explainer/mats/geometry.json`,
given in millimetres on the page. Four of them fix the mapping between the page and the
picture, which answers three questions the camera cannot answer by itself.

Where a thing is, in millimetres on the mat, rather than in pixels of whatever framing
happened to be set. How large it is, from the mat's own printed geometry rather than from
a scale somebody measured once and hoped still held. And whether the bench has moved,
which until now nothing could see: a mark is stored against the sensor, so moving the
camera or the stand leaves every mark pointing at the wrong part with nothing to say so.

**Finding the markers and solving the mapping are separate jobs, and only the first one
needs a library.** `solve` takes marker corners somebody has already located and needs
nothing but numpy, so it is always available. `detect` finds them in a picture with
OpenCV, which is an optional extra. The reason for the split is that the corners can come
from somewhere other than OpenCV: an agent looking at the picture can read the eight
markers off it and call `solve` directly, and gets the same millimetres. OpenCV is the
recommended way because it is precise and repeatable, not the only way.

Solving the mapping, rather than assuming one, is the whole point. A homography carries
the perspective as well as the position, so a mat photographed at an angle still reads
true, and the residual it leaves behind is the check on whether the answer can be trusted.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np

GEOMETRY = Path(__file__).resolve().parents[2] / "explainer" / "mats" / "geometry.json"

# A mat read from four markers in one corner of the page describes that corner well and
# the far side badly. Spread is the guard: the markers actually seen must cover enough of
# the page for the mapping to mean anything across it.
MIN_MARKERS = 4
MIN_SPREAD = 0.35          # of the page's width and height, per axis
MAX_RESIDUAL_MM = 1.0      # worst marker corner, after the fit


@dataclass(frozen=True)
class Mat:
    """A solved mapping between one sheet's millimetres and one picture's pixels."""

    sheet: str
    revision: str
    page_mm: tuple[float, float]
    ids: tuple[int, ...]
    H: np.ndarray              # mm -> pixels
    Hinv: np.ndarray           # pixels -> mm
    residual_mm: float         # worst marker corner, refit through Hinv
    size: tuple[int, int]      # the picture this was solved on
    source: str                # who found the corners: "opencv", or "observed"

    def to_pixels(self, xy_mm: np.ndarray) -> np.ndarray:
        return _apply(self.H, xy_mm)

    def to_mm(self, xy_px: np.ndarray) -> np.ndarray:
        return _apply(self.Hinv, xy_px)

    def to_frame(self, xy_mm: np.ndarray) -> np.ndarray:
        """Millimetres to the 0..1 whole-frame coordinates a mark is placed in."""
        return self.to_pixels(xy_mm) / np.array(self.size, dtype=float)

    def from_frame(self, xy_frame: np.ndarray) -> np.ndarray:
        """The 0..1 coordinates a mark is stored in, back to millimetres on the mat."""
        return self.to_mm(np.asarray(xy_frame, dtype=float).reshape(-1, 2)
                          * np.array(self.size, dtype=float))

    def mm_per_pixel(self) -> float:
        """At the centre of the page. Perspective makes this vary, so it is indicative."""
        cx, cy = self.page_mm[0] / 2, self.page_mm[1] / 2
        a, b = self.to_pixels(np.array([[cx, cy], [cx + 1.0, cy]]))
        return float(1.0 / np.hypot(*(b - a)))

    def rotation_degrees(self) -> float:
        """How far the mat's own x axis is turned in the picture, -180 to 180."""
        a, b = self.to_pixels(np.array([[0.0, 0.0], [self.page_mm[0], 0.0]]))
        return float(math.degrees(math.atan2(b[1] - a[1], b[0] - a[0])))

    def as_dict(self) -> dict:
        return {
            "sheet": self.sheet, "revision": self.revision, "source": self.source,
            "markers": len(self.ids), "ids": list(self.ids),
            "micrometres_per_pixel": round(self.mm_per_pixel() * 1000, 1),
            "rotation_degrees": round(self.rotation_degrees(), 2),
            "residual_mm": round(self.residual_mm, 3),
            "picture": {"w": self.size[0], "h": self.size[1]},
        }


def _apply(M: np.ndarray, pts: np.ndarray) -> np.ndarray:
    pts = np.asarray(pts, dtype=float).reshape(-1, 2)
    h = np.hstack([pts, np.ones((len(pts), 1))])
    out = h @ M.T
    return out[:, :2] / out[:, 2:3]


def _normalise(pts: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Hartley conditioning: centre on the mean, scale so the mean distance is sqrt(2).

    Without it the design matrix mixes millimetres with pixels and squared terms of both,
    and the smallest singular vector is chosen partly by the units.
    """
    c = pts.mean(axis=0)
    d = np.hypot(*(pts - c).T).mean()
    s = math.sqrt(2.0) / d if d > 0 else 1.0
    T = np.array([[s, 0, -s * c[0]], [0, s, -s * c[1]], [0, 0, 1]], dtype=float)
    return _apply(T, pts), T


def _homography(src: np.ndarray, dst: np.ndarray) -> np.ndarray:
    """The direct linear transform, so that solving needs no computer-vision library."""
    sn, Ts = _normalise(src)
    dn, Td = _normalise(dst)
    rows = []
    for (x, y), (u, v) in zip(sn, dn):
        rows.append([-x, -y, -1, 0, 0, 0, u * x, u * y, u])
        rows.append([0, 0, 0, -x, -y, -1, v * x, v * y, v])
    _, _, vt = np.linalg.svd(np.array(rows, dtype=float))
    H = vt[-1].reshape(3, 3)
    H = np.linalg.inv(Td) @ H @ Ts
    if abs(H[2, 2]) < 1e-12:
        raise ValueError("the markers are degenerate; no mapping fits them")
    return H / H[2, 2]


def sheets() -> dict:
    return json.loads(GEOMETRY.read_text())["sheets"]


def have_detector() -> bool:
    """Whether markers can be found automatically, or must be supplied."""
    try:
        import cv2  # noqa: F401
    except ImportError:
        return False
    return True


def solve(observed: dict[int, list], size: tuple[int, int],
          sheet: str | None = None, source: str = "observed") -> Mat:
    """Solve the mapping from marker corners somebody has already located.

    `observed` maps a marker id to its four corners in pixels, in the order the sheet
    lists them: top-left, top-right, bottom-right, bottom-left as printed. `size` is the
    picture's width and height. Needs numpy and nothing else, so this is the path that
    always works, whether the corners came from OpenCV or from an agent reading them off
    the picture.

    Raises ValueError saying what was not good enough, and carries no mapping when it
    does. Every number downstream is in millimetres because of this, and a mapping that
    quietly described half the page would put a plausible wrong number into a report with
    nothing to mark it as wrong.
    """
    all_sheets = sheets()
    if sheet is None:
        if not observed:
            raise ValueError("no mat markers given")
        sheet = _sheet_for(all_sheets, set(observed))
        if sheet is None:
            raise ValueError(f"markers {sorted(observed)} match no sheet in {GEOMETRY.name}")
    elif sheet not in all_sheets:
        raise ValueError(f"unknown sheet {sheet!r}")

    spec = all_sheets[sheet]
    page = (float(spec["page"][0]), float(spec["page"][1]))
    wanted = {m["id"]: m["corners"] for m in spec["codedMarkers"]}

    src, dst, ids = [], [], []
    for mid, px_corners in sorted(observed.items()):
        if mid not in wanted:
            continue
        if len(px_corners) != 4:
            raise ValueError(f"marker {mid} needs 4 corners, got {len(px_corners)}")
        ids.append(mid)
        src.extend([(float(x), float(y)) for x, y in wanted[mid]])
        dst.extend([(float(x), float(y)) for x, y in px_corners])

    if len(ids) < MIN_MARKERS:
        raise ValueError(
            f"found {len(ids)} marker(s) of {sheet}, need {MIN_MARKERS}. "
            "Show more of the mat, or light it more evenly."
        )

    src_a, dst_a = np.array(src, dtype=float), np.array(dst, dtype=float)
    span_x = (src_a[:, 0].max() - src_a[:, 0].min()) / page[0]
    span_y = (src_a[:, 1].max() - src_a[:, 1].min()) / page[1]
    if span_x < MIN_SPREAD or span_y < MIN_SPREAD:
        raise ValueError(
            f"the markers seen cover {span_x:.0%} by {span_y:.0%} of the page, under "
            f"{MIN_SPREAD:.0%}. They are too close together to describe the whole mat."
        )

    H = _homography(src_a, dst_a)
    Hinv = np.linalg.inv(H)

    residual = float(np.abs(_apply(Hinv, dst_a) - src_a).max())
    if residual > MAX_RESIDUAL_MM:
        raise ValueError(
            f"the fit misses a marker corner by {residual:.2f} mm, over "
            f"{MAX_RESIDUAL_MM} mm. The sheet is probably creased, curled or not flat, "
            "or a corner was read wrongly."
        )

    return Mat(sheet=sheet, revision=spec.get("revision", "?"), page_mm=page,
               ids=tuple(ids), H=H, Hinv=Hinv, residual_mm=residual,
               size=(int(size[0]), int(size[1])), source=source)


def detect(image_path: str | Path, sheet: str | None = None) -> Mat:
    """Find the markers with OpenCV and solve. Needs the `mat` extra.

    The recommendation, not the requirement: it locates corners to a fraction of a pixel
    and does it the same way every time. Without it, read the markers off the picture and
    call `solve`; the mapping is identical, only the corners are less precise.
    """
    try:
        import cv2
    except ImportError as e:
        raise ValueError(
            "no marker detector: install the mat extra with "
            "`uv pip install 'opencv-python-headless>=4.7'`, or read the marker corners "
            "off the picture and call mat.solve() with them."
        ) from e

    img = cv2.imread(str(image_path), cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise ValueError(f"cannot read {image_path}")
    height, width = img.shape[:2]

    all_sheets = sheets()
    dictionary = (all_sheets[sheet] if sheet else next(iter(all_sheets.values())))["dictionary"]
    d = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, dictionary))
    corners, ids, _ = cv2.aruco.ArucoDetector(d).detectMarkers(img)
    if ids is None:
        raise ValueError("no mat markers found in the picture")
    observed = {int(i): c.reshape(4, 2).tolist() for i, c in zip(ids.flatten(), corners)}
    return solve(observed, (width, height), sheet=sheet, source="opencv")


def _sheet_for(all_sheets: dict, seen: set[int]) -> str | None:
    best, score = None, 0
    for name, spec in all_sheets.items():
        hits = len(seen & set(spec.get("markerIds", [])))
        if hits > score:
            best, score = name, hits
    return best


def moved(a: Mat, b: Mat, tolerance_mm: float = 2.0) -> float:
    """How far the view shifted between two solved mats, in millimetres on the page.

    Measured at the page's four corners, because a rotation about the centre moves little
    there and a great deal at the edges. Anything over the tolerance means a mark placed
    against the first picture no longer names the same part in the second.
    """
    corners = np.array([[0, 0], [a.page_mm[0], 0], a.page_mm, [0, a.page_mm[1]]], dtype=float)
    return float(np.abs(b.to_mm(a.to_pixels(corners)) - corners).max())
