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

from . import aatest, burstnoise, linearity, scale
from .images import parse_region
from .result import Measurement

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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python3 -m analysis",
        description="Measurement tools for DeskCam captures. Each one reads captures and "
        "their sidecars off disk and refuses rather than guessing.",
    )
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument(
        "--region",
        type=str,
        default=None,
        help="cx,cy,w,h in normalised coordinates, default the centre 30%%",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_scale = sub.add_parser("scale", help="pixels per millimetre from a reference in the frame")
    p_scale.add_argument("image", type=Path)
    p_scale.add_argument(
        "--pitch-mm",
        type=float,
        default=1.0,
        help="the real pitch of the reference, e.g. 1.0 for a rule, 5.0 for graph paper",
    )

    p_lin = sub.add_parser("linearity", help="pixel value against exposure")
    p_lin.add_argument("directory", type=Path)

    p_burst = sub.add_parser("burst-noise", help="how far averaging a burst lowers the noise")
    p_burst.add_argument("directory", type=Path)
    p_burst.add_argument(
        "--group", type=int, default=0, help="frames per average, default half the burst"
    )

    p_aa = sub.add_parser("aatest", help="the noise floor of the instrument itself")
    p_aa.add_argument("first", type=Path)
    p_aa.add_argument("second", type=Path)
    p_aa.add_argument(
        "--write",
        type=Path,
        default=None,
        help="directory to record the floor in, for the other tools to read",
    )

    args = parser.parse_args(argv)
    region = parse_region(args.region) if args.region else None

    try:
        if args.command == "scale":
            result = scale.measure(args.image, pitch_mm=args.pitch_mm, region=region)
        elif args.command == "linearity":
            result = linearity.measure(args.directory, region=region or linearity.CENTRE)
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
    return _emit(result, args.json)


if __name__ == "__main__":
    sys.exit(main())
