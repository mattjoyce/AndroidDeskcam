"""What the camera currently knows about the mat, and whether the bench has moved.

`deskcam scale` answers "how many pixels per millimetre" from a reference somebody put in
the frame, and its own docstring admits the thing it cannot check: nothing can see the
stand move. This can. The mat carries coded markers at corners the repository knows in
millimetres, so a picture of it fixes the mapping between the page and the sensor, and two
such mappings compared say how far the view has shifted.

That matters because a mark is stored against the sensor. Move the camera and every mark
points at the wrong part, with nothing anywhere to say so. A recorded calibration turns
that silent failure into a number.

The markers can be found by OpenCV, which is the recommendation, or read off the picture
by whoever is looking at it and passed in. Both go through the same solver and produce the
same millimetres; only the precision of the corners differs.
"""

from __future__ import annotations

import json
from pathlib import Path

from . import mat
from .result import Measurement

METHOD = "mat calibration"
UNIT = "um/px"
RECORD = "deskcam-calibration.json"


def _describe(m: mat.Mat, notes: list[str]) -> Measurement:
    return Measurement(
        method=METHOD,
        unit=UNIT,
        value=round(m.mm_per_pixel() * 1000, 1),
        n=len(m.ids),
        n_min=mat.MIN_MARKERS,
        notes=notes,
        inputs=[m.sheet],
    ).gate()


def measure(
    image: Path,
    corners: Path | None = None,
    against: Path | None = None,
    tolerance_mm: float = 2.0,
) -> Measurement:
    """The current calibration, and how far it has drifted from a recorded one."""
    try:
        if corners is not None:
            given, size = _read_corners(corners)
            solved = mat.solve(given, size, source="observed")
        else:
            solved = mat.detect(image)
    except (OSError, ValueError) as e:
        return Measurement.refuse(METHOD, UNIT, str(e), n_min=mat.MIN_MARKERS)

    d = solved.as_dict()
    notes = [
        f"sheet {d['sheet']} rev {d['revision']}, {d['markers']} markers, found by {d['source']}",
        f"mat rotated {d['rotation_degrees']:+.2f} degrees in the frame",
        f"worst marker corner misses by {d['residual_mm']:.3f} mm",
        f"picture {d['picture']['w']}x{d['picture']['h']}",
    ]

    if against is not None:
        try:
            base = _read_record(against)
        except (OSError, ValueError) as e:
            return Measurement.refuse(METHOD, UNIT, f"cannot read the recorded mat: {e}",
                                      n=len(solved.ids), n_min=mat.MIN_MARKERS, notes=notes)
        shift = mat.moved(base, solved)
        notes.append(
            f"the view has moved {shift:.1f} mm on the page since that calibration"
            + (
                f", over the {tolerance_mm:.1f} mm tolerance: marks placed against it now "
                "name the wrong parts"
                if shift > tolerance_mm
                else ", within tolerance"
            )
        )

    return _describe(solved, notes)


def record(image: Path, directory: Path, corners: Path | None = None) -> Path | None:
    """Write the calibration beside the captures, for a later one to be compared against.

    Only what was solved, never a claim that it still holds. The comparison is the thing
    that decides that, and it needs a picture to do it with.
    """
    try:
        if corners is not None:
            given, size = _read_corners(corners)
            solved = mat.solve(given, size, source="observed")
        else:
            solved = mat.detect(image)
    except (OSError, ValueError):
        return None

    path = Path(directory) / RECORD
    path.write_text(
        json.dumps(
            {
                "mat": solved.as_dict(),
                "image": str(image),
                "H": solved.H.tolist(),
                "page_mm": list(solved.page_mm),
                "size": list(solved.size),
                "note": "mm -> pixel mapping for the framing this was solved on. A later "
                "capture is compared against it; nothing here claims the bench has not moved.",
            },
            indent=2,
        )
        + "\n"
    )
    return path


def _read_corners(path: Path) -> tuple[dict[int, list], tuple[int, int]]:
    """Marker corners somebody located, as JSON: {"size": [w, h], "markers": {"8": [...]}}.

    The shape an agent produces after reading a picture, and the reason the solver is
    separate from the detector.
    """
    doc = json.loads(Path(path).read_text())
    if "markers" not in doc or "size" not in doc:
        raise ValueError('needs {"size": [w, h], "markers": {"<id>": [[x,y] x4]}}')
    size = (int(doc["size"][0]), int(doc["size"][1]))
    return {int(k): v for k, v in doc["markers"].items()}, size


def _read_record(path: Path) -> mat.Mat:
    """Rebuild a solved mat from what `record` wrote."""
    import numpy as np

    doc = json.loads(Path(path).read_text())
    H = np.array(doc["H"], dtype=float)
    d = doc["mat"]
    return mat.Mat(
        sheet=d["sheet"],
        revision=d.get("revision", "?"),
        page_mm=(float(doc["page_mm"][0]), float(doc["page_mm"][1])),
        ids=tuple(d.get("ids", [])),
        H=H,
        Hinv=np.linalg.inv(H),
        residual_mm=float(d.get("residual_mm", 0.0)),
        size=(int(doc["size"][0]), int(doc["size"][1])),
        source=d.get("source", "recorded"),
    )
