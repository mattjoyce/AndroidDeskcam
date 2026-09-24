"""
Command line for the measurement tools.

Human-readable by default, because a person running this wants a line. `--json` for
anything consuming it, which includes the CLI and anything that replaces the CLI later.
The exit code is 0 when a measurement was made, 2 when it was refused, 1 when the tool
could not run. A refusal is a normal outcome, not a crash, and it has its own code so a
caller can tell the three apart without reading English.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import aatest, average, burstnoise, calibration, distance, hdr, linearity, scale, stack
from .images import image_size, load_sidecar, parse_region
from .result import Measurement, Scale

REFUSED = 2


def _emit(result: Measurement, as_json: bool) -> int:
    if as_json:
        print(result.to_json())
    else:
        print(result.human())
        for note in result.notes:
            print(f"  {note}")
        if not result.ok and result.limit_justification:
            print(f"  the limit for this method: {result.limit_justification}")
    return 0 if result.ok else REFUSED


def _record_scale(
    result: Measurement, image: Path, pitch_mm: float, directory: Path
) -> Path | None:
    """
    Writes the measured scale beside the captures, for later ones to carry.

    Only the settings are recorded with it, never a claim that the bench has not moved.
    Nothing here can see the stand move, and the check a later capture gets says so.
    """
    if result.value is None:
        return None
    width, height = image_size(image)
    sidecar = load_sidecar(image)
    record = Scale(
        px_per_mm=result.value,
        pitch_mm=pitch_mm,
        width_px=width,
        height_px=height,
        settings=sidecar.get("settings", {}),
        measured_at=str(sidecar.get("captured_at", "")),
        image=image.name,
        interval=result.interval,
        confidence=result.confidence,
    )
    return record.save(directory)


def main(argv: list[str] | None = None) -> int:
    # Both orders work. argparse puts a top-level flag before the subcommand, which is not
    # where anyone types it, so the same flags are given to every subparser through a
    # shared parent. `analysis --region R scale F` and `analysis scale F --region R` are
    # the same command.
    # SUPPRESS matters. Without it the subparser writes its own default over whatever the
    # top-level parser already parsed, so `--region R scale F` silently measured the centre
    # of the frame while `scale F --region R` measured the region, and the two disagreed
    # without either one looking wrong.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--json",
        action="store_true",
        default=argparse.SUPPRESS,
        help="machine-readable output",
    )
    common.add_argument(
        "--region",
        type=str,
        default=argparse.SUPPRESS,
        help="cx,cy,w,h in normalised coordinates, default the centre 30%%",
    )
    parser = argparse.ArgumentParser(
        prog="python3 -m analysis",
        parents=[common],
        description="Measurement tools for DeskCam captures. Each one reads captures and "
        "their sidecars off disk and refuses rather than guessing.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_scale = sub.add_parser(
        "scale",
        parents=[common],
        help="pixels per millimetre from a reference in the frame",
    )
    p_scale.add_argument("image", type=Path)
    p_scale.add_argument(
        "--pitch-mm",
        type=float,
        default=1.0,
        help="the real pitch of the reference, e.g. 1.0 for a rule, 5.0 for graph paper",
    )
    p_scale.add_argument(
        "--write",
        type=Path,
        default=None,
        help="directory to record the scale in, so later captures carry it",
    )

    p_dist = sub.add_parser(
        "distance",
        parents=[common],
        help="millimetres between two points in a capture, from the scale in its sidecar",
    )
    p_dist.add_argument("image", type=Path)
    p_dist.add_argument("first", type=str, help="x,y in pixels")
    p_dist.add_argument("second", type=str, help="x,y in pixels")

    p_cal = sub.add_parser(
        "calibration",
        parents=[common],
        help="what the camera knows about the mat, and whether the bench has moved",
    )
    p_cal.add_argument("image", type=Path)
    p_cal.add_argument(
        "--corners",
        type=Path,
        default=None,
        help="marker corners already located, as JSON, instead of finding them with OpenCV",
    )
    p_cal.add_argument(
        "--against",
        type=Path,
        default=None,
        help="a recorded calibration to measure the drift from",
    )
    p_cal.add_argument(
        "--tolerance-mm",
        type=float,
        default=2.0,
        help="how far the view may move before marks are called stale",
    )
    p_cal.add_argument(
        "--write",
        type=Path,
        default=None,
        help="directory to record this calibration in, to compare a later capture against",
    )

    p_lin = sub.add_parser("linearity", parents=[common], help="pixel value against exposure")
    p_lin.add_argument("directory", type=Path)

    p_avg = sub.add_parser(
        "average",
        parents=[common],
        help="average a burst into one 16-bit image, and measure what that bought",
    )
    p_avg.add_argument("directory", type=Path)
    p_avg.add_argument("-o", "--out", type=Path, default=None, help="where to write the image")

    p_hdr = sub.add_parser(
        "hdr",
        parents=[common],
        help="merge an exposure bracket into one linear image, on the measured exposures",
    )
    p_hdr.add_argument("directory", type=Path)
    p_hdr.add_argument("-o", "--out", type=Path, default=None, help="where to write it")

    p_stack = sub.add_parser(
        "stack",
        parents=[common],
        help="stack a focus sweep into one image that is sharp at every depth",
    )
    p_stack.add_argument("directory", type=Path)
    p_stack.add_argument("-o", "--out", type=Path, default=None, help="where to write it")

    p_burst = sub.add_parser(
        "burst-noise", parents=[common], help="how far averaging a burst lowers the noise"
    )
    p_burst.add_argument("directory", type=Path)
    p_burst.add_argument(
        "--group", type=int, default=0, help="frames per average, default half the burst"
    )

    p_aa = sub.add_parser(
        "aatest", parents=[common], help="the noise floor of the instrument itself"
    )
    p_aa.add_argument("first", type=Path)
    p_aa.add_argument("second", type=Path)
    p_aa.add_argument(
        "--write",
        type=Path,
        default=None,
        help="directory to record the floor in, for the other tools to read",
    )

    args = parser.parse_args(argv)
    given = getattr(args, "region", None)
    as_json = getattr(args, "json", False)
    region = parse_region(given) if given else None
    recorded: Path | None = None

    try:
        if args.command == "scale":
            result = scale.measure(args.image, pitch_mm=args.pitch_mm, region=region)
            if args.write is not None and result.ok:
                recorded = _record_scale(result, args.image, args.pitch_mm, args.write)
        elif args.command == "distance":
            result = distance.measure(
                args.image, distance.parse_point(args.first), distance.parse_point(args.second)
            )
        elif args.command == "calibration":
            result = calibration.measure(
                args.image,
                corners=args.corners,
                against=args.against,
                tolerance_mm=args.tolerance_mm,
            )
            if args.write is not None and result.ok:
                recorded = calibration.record(args.image, args.write, corners=args.corners)
        elif args.command == "linearity":
            result = linearity.measure(args.directory, region=region or linearity.CENTRE)
        elif args.command == "average":
            result = average.measure(args.directory, out=args.out, region=region or average.CENTRE)
        elif args.command == "hdr":
            result = hdr.measure(args.directory, out=args.out)
        elif args.command == "stack":
            result = stack.measure(args.directory, out=args.out)
        elif args.command == "burst-noise":
            result = burstnoise.measure(
                args.directory, group=args.group, region=region or burstnoise.CENTRE
            )
        else:
            result = aatest.measure(
                args.first, args.second, region=region or aatest.CENTRE, write_to=args.write
            )
    except (OSError, ValueError) as e:
        print(f"analysis: {e}", file=sys.stderr)
        return 1
    code = _emit(result, as_json)
    # After the measurement, and never in JSON, which has one document in it and no room
    # for a remark.
    if recorded is not None and not as_json:
        print(f"  recorded in {recorded}; later captures with this framing carry it")
    return code


if __name__ == "__main__":
    sys.exit(main())
