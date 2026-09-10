"""
Record what every endpoint of a running phone answers, and compare two recordings.

This is the live counterpart to `test_contract.py`, which reads the source and can run
anywhere. This one needs the phone, and it exists for one job: proving that a change to
the router or the handlers left the HTTP surface alone.

    frontend/surface.py record /tmp/before
    # change the code, ./backend/build.sh, adb install -r -g ...
    frontend/surface.py record /tmp/after
    frontend/surface.py compare /tmp/before /tmp/after

It was written for the verb refactor of card 57, which turned every handler from something
that wrote to the socket into something that returns a value. The claim that all of them
still answered exactly as they had is the kind that is easy to make and tedious to check,
and this is the difference between the two.

**What it compares is the shape, never the values.** Two captures of one scene differ in
every byte, the measured exposure moves, and a sensor timestamp is a clock. So it compares
the status line, the set of header names, and the type of every field of every JSON body.
A field that appears or disappears is a change to the contract; a field whose number moved
is a camera.

**It fixes the camera before it records.** Several fields are there or not depending on the
state: `focus_metres` only exists while the focus is manual, and `tonemap_curve` only once
the linear curve of measurement mode has reached a capture result. Recording from whatever
state the camera happened to be in produces differences that have nothing to do with the
change, which is exactly the noise that makes a check like this get ignored.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# The state both recordings start from, so a field that depends on the camera cannot
# differ for a reason that has nothing to do with the change being checked.
FIXED_STATE = (
    "reset=1&zoom=2&cx=0.5&cy=0.5&focus=4&exposure=1/33&iso=200&measure=on&awbgains=neutral"
)

# Long enough for the measurement-mode tone map to reach a capture result, which is what
# `pipeline.tonemap_curve` reports.
SETTLE_SECONDS = 3.0

# Every endpoint, with parameters that make it do its real work rather than refuse.
#
# Hand-written, because a probe needs values `/api/help` cannot supply: which parameter to
# walk, how many stops to bracket. `missing_from()` below is the guard that keeps it from
# falling behind the phone, in the spirit of rule R6.
PROBES: list[tuple[str, str, str]] = [
    ("help", "/api/help", ""),
    ("cameras", "/api/cameras", ""),
    ("status", "/api/status", ""),
    ("set", "/api/set", "zoom=2&cx=0.4&cy=0.6"),
    ("af", "/api/af", "wait=300"),
    ("orientation", "/api/orientation", ""),
    ("still", "/api/still", ""),
    ("raw", "/api/raw", ""),
    ("frame", "/api/frame", "w=320"),
    ("burst", "/api/burst", "n=2"),
    ("focussweep", "/api/focussweep", "from=3&to=4&steps=2"),
    ("bracket", "/api/bracket", "base=1/240&stops=2"),
    ("walk", "/api/walk", "vary=torch&values=0,5"),
    ("focushunt", "/api/focushunt", "from=3&to=5&coarse=3&fine=0"),
    ("shadingmap", "/api/shadingmap", ""),
    ("nettest", "/api/nettest", "port=9"),
    ("reset", "/api/reset", ""),
    # The refusals are contract too, and they are the half a refactor is most likely to
    # drop: an error that stops naming what is wrong is still an error.
    ("badparam", "/api/set", "zomo=4"),
    ("badvalue", "/api/still", "zoom=banana"),
    ("notfound", "/api/nosuchthing", ""),
    ("streamrefusal", "/api/stream", "rotate=90"),
    ("scriptbadverb", "/api/script", ""),
]

# The ones that are a POST, with the body to send.
BODIES: dict[str, bytes] = {"scriptbadverb": b"SNAP\nSNPA\n"}


def target() -> str:
    """Where the phone is, by the same rules the CLI uses."""
    if len(sys.argv) > 4:
        return sys.argv[4].rstrip("/")
    if url := os.environ.get("DESKCAM_URL"):
        return url.rstrip("/")
    config = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    saved = Path(config) / "deskcam" / "url"
    if saved.is_file():
        return saved.read_text().strip().rstrip("/")
    return "http://127.0.0.1:8080"


def ask(url: str, path: str, query: str, body: bytes | None) -> tuple[str, list[str], bytes]:
    """One request. A 4xx is an answer here, not a failure: refusals are contract."""
    if token := os.environ.get("DESKCAM_TOKEN"):
        query = f"{query}&token={urllib.parse.quote(token)}" if query else f"token={token}"
    # The URL is a phone on the local network, from the same place the CLI reads it.
    request = urllib.request.Request(
        f"{url}{path}?{query}", data=body, method="POST" if body else "GET"
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as answer:
            return f"{answer.status}", sorted(k.lower() for k in answer.headers), answer.read()
    except urllib.error.HTTPError as refused:
        return f"{refused.status}", sorted(k.lower() for k in refused.headers), refused.read()


def record(into: Path, url: str) -> int:
    into.mkdir(parents=True, exist_ok=True)
    print(f"recording {url} into {into}")
    ask(url, "/api/set", FIXED_STATE, None)
    time.sleep(SETTLE_SECONDS)

    names = []
    for name, path, query in PROBES:
        status, headers, body = ask(url, path, query, BODIES.get(name))
        (into / f"{name}.body").write_bytes(body)
        (into / f"{name}.head").write_text(status + "\n" + "\n".join(headers) + "\n")
        names.append(name)
        print(f"  {name:<15} {status}  {len(body)} bytes")
    (into / "probed.txt").write_text("\n".join(names) + "\n")

    if missing := missing_from(into):
        print(f"\nthe phone advertises endpoints this probe does not visit: {missing}")
        print("add them to PROBES, or the next refactor changes them unwatched")
        return 1
    return 0


def missing_from(recording: Path) -> list[str]:
    """Rule R6 turned on this file: the phone says what it has, so nothing may be skipped."""
    try:
        advertised = json.loads((recording / "help.body").read_bytes())["endpoints"]
    except (OSError, ValueError, KeyError):
        return []
    visited = {path for _, path, _ in PROBES}
    return sorted(
        name.split(" ", 1)[1] for name in advertised if name.split(" ", 1)[1] not in visited
    )


def shape(value: object, prefix: str = "") -> list[str]:
    """Every field of a document and the type it holds, as a flat sorted list.

    A JSON number is one type, so 9 and 9.15 are the same shape. They are the same field
    reporting a cost that differs by a rounding, and treating them as a change would make
    this cry wolf on every run.
    """
    if isinstance(value, dict):
        return [k for key in sorted(value) for k in shape(value[key], f"{prefix}.{key}")]
    if isinstance(value, list):
        return shape(value[0], prefix + "[]") if value else [prefix + "[]"]
    kind = "num" if isinstance(value, int | float) and not isinstance(value, bool) else "value"
    return [f"{prefix}:{kind}"]


def read(recording: Path, name: str) -> tuple[str, list[str], list[str] | None]:
    head = (recording / f"{name}.head").read_text().splitlines()
    body = (recording / f"{name}.body").read_bytes()
    try:
        return head[0], head[1:], shape(json.loads(body))
    except ValueError:
        # An image or an archive. Its bytes differ every capture, so only its head is
        # evidence; that it arrived at all is the rest.
        return head[0], head[1:], None


def compare(before: Path, after: Path) -> int:
    names = (before / "probed.txt").read_text().split()
    differences = 0
    for name in names:
        was_status, was_headers, was_shape = read(before, name)
        now_status, now_headers, now_shape = read(after, name)
        for what, was, now in (
            ("status", was_status, now_status),
            ("headers", was_headers, now_headers),
            ("body", was_shape, now_shape),
        ):
            if was == now:
                continue
            differences += 1
            print(f"{name}: the {what} changed")
            if isinstance(was, list) and isinstance(now, list):
                for gone in sorted(set(was) - set(now)):
                    print(f"  gone:  {gone}")
                for new in sorted(set(now) - set(was)):
                    print(f"  new:   {new}")
            else:
                print(f"  before {was}\n  after  {now}")
    print(f"\n{len(names)} endpoints, {differences} differences")
    return 1 if differences else 0


def main() -> int:
    if len(sys.argv) >= 3 and sys.argv[1] == "record":
        return record(Path(sys.argv[2]), target())
    if len(sys.argv) >= 4 and sys.argv[1] == "compare":
        return compare(Path(sys.argv[2]), Path(sys.argv[3]))
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
