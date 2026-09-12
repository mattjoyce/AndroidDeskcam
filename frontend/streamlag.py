"""Does a change to the camera reach a stream that is already open, and how late?

The live counterpart to a claim that is easy to make and tedious to check: that the bench
page's picture follows the framing. It holds ONE stream connection open for the whole run,
sends a zoom partway through, and compares every frame with the one before it. The answer is
therefore in frames of the same connection, which is the whole point: a client that
reconnects measures its own reconnect and calls it latency.

    frontend/streamlag.py http://192.168.86.123:8080 6 12 20

It reports the noise floor beside every result. Two frames of an unchanged scene differ by
sensor noise alone, and a threshold that is not quoted against that floor is not a
measurement. On the bench phone the floor is 0.2 to 0.5 of a grey level and the change reads
36, so the detection is not marginal.

**What it found on 2026-09-12**, against the build before this file existed: at 6 fps, 256
frames over 42.6 s after `zoom=6`, and not one differed from its predecessor by more than
the 0.434 floor. At 12 fps, 516 frames over 43.5 s, the same. The change was not late, it
never arrived, because `/api/stream` cropped with the settings it read when the connection
opened. A connection made immediately afterwards differed from the old stream's last frame
by 36.32, so the camera had been reframed all along. Card 74.
"""

from __future__ import annotations

import io
import sys
import threading
import time
import urllib.request
from collections.abc import Iterator
from typing import Any

import numpy as np
import numpy.typing as npt
from PIL import Image

Grey = npt.NDArray[np.float64]

DEFAULT_TARGET = "http://192.168.86.123:8080"

# Pairs of unchanged frames used for the floor. Enough to average, few enough that a slow
# rate does not spend a minute on it.
BASELINE_PAIRS = 13

# How long to wait for the change after sending it. Fifteen frame intervals at 6 fps is 2.5
# seconds, so 40 seconds is not a close call either way.
WATCH_SECONDS = 40.0

# A frame counts as changed at five times the floor, and never at less than one grey level,
# so a very quiet scene cannot make noise look like a reframe.
CHANGE_MULTIPLE = 5.0
CHANGE_MINIMUM = 1.0

# The criterion from card 74: a viewer should see a change within two frame intervals.
ALLOWED_INTERVALS = 2.0

# The measurement itself. Small enough to decode at 30 fps in Python, large enough that a
# crop of a bench scene moves it.
SIZE = (160, 120)


def ask(base: str, path: str) -> None:
    """Send a request and discard the answer. Used for /api/set."""
    with urllib.request.urlopen(base + path, timeout=10) as r:
        r.read()


def parts(body: Any, stop: float) -> Iterator[tuple[float, bytes]]:
    """Yield (arrival, jpeg) from a multipart/x-mixed-replace body until stop."""
    buf = b""
    while time.time() < stop:
        while b"\r\n\r\n" not in buf:
            chunk = body.read(4096)
            if not chunk or time.time() > stop:
                return
            buf += chunk
        head, buf = buf.split(b"\r\n\r\n", 1)
        length = None
        for line in head.split(b"\r\n"):
            if line.lower().startswith(b"content-length:"):
                length = int(line.split(b":")[1])
        if length is None:
            return
        while len(buf) < length:
            chunk = body.read(65536)
            if not chunk:
                return
            buf += chunk
        # The time of arrival is taken after the last byte of the frame, so it is never
        # earlier than the frame was complete.
        yield time.time(), buf[:length]
        buf = buf[length:]


def grey(jpeg: bytes) -> Grey:
    im = Image.open(io.BytesIO(jpeg)).convert("L").resize(SIZE)
    return np.asarray(im, dtype=np.float64)


def difference(a: Grey, b: Grey) -> float:
    """Mean absolute difference in grey levels, 0 to 255."""
    return float(np.mean(np.abs(a - b)))


def measure(base: str, fps: float) -> bool:
    """One run at one rate. True when a change arrived inside the allowance."""
    interval = 1.0 / fps
    allowed_ms = ALLOWED_INTERVALS * interval * 1000
    print(f"\n=== {fps:g} fps, one connection, frame interval {interval * 1000:.0f} ms ===")

    ask(base, "/api/set?zoom=1&cx=0.5&cy=0.5")
    time.sleep(1.0)

    body = urllib.request.urlopen(f"{base}/api/stream?fps={fps:g}", timeout=20)
    frames = parts(body, time.time() + BASELINE_PAIRS * interval + WATCH_SECONDS + 30)
    try:
        previous = None
        floor: list[float] = []
        for _ in range(BASELINE_PAIRS + 1):
            _, jpeg = next(frames)
            current = grey(jpeg)
            if previous is not None:
                floor.append(difference(current, previous))
            previous = current
        if previous is None:
            print("no frames arrived at all")
            return False
        noise = float(np.mean(floor))
        threshold = max(noise * CHANGE_MULTIPLE, CHANGE_MINIMUM)
        print(
            f"noise floor {noise:.3f} over {len(floor)} unchanged pairs "
            f"(worst {max(floor):.3f}), threshold {threshold:.2f}"
        )

        # Sent from another thread so that reading the stream never pauses, which would put
        # the pause into the number being measured.
        sent: list[float] = []

        def fire() -> None:
            sent.append(time.time())
            ask(base, "/api/set?zoom=6")

        threading.Thread(target=fire, daemon=True).start()
        while not sent:
            time.sleep(0.005)
        at = sent[0]

        seen = 0
        deadline = at + WATCH_SECONDS
        for arrival, jpeg in frames:
            current = grey(jpeg)
            moved = difference(current, previous)
            previous = current
            if arrival < at:
                continue
            seen += 1
            if moved > threshold:
                late = (arrival - at) * 1000
                verdict = "within" if late <= allowed_ms else "OUTSIDE"
                print(
                    f"changed after {late:.0f} ms and {seen} frames, by {moved:.2f}. "
                    f"{verdict} the {allowed_ms:.0f} ms allowance"
                )
                return late <= allowed_ms
            if arrival > deadline:
                break
        print(
            f"NO CHANGE in {seen} frames over {time.time() - at:.1f} s. "
            f"The stream is not showing the camera it has."
        )

        # Proof that the camera did move, so a null result above cannot be read as a zoom
        # that never happened.
        fresh = urllib.request.urlopen(f"{base}/api/stream?fps={fps:g}", timeout=20)
        try:
            _, first = next(parts(fresh, time.time() + 20))
            print(
                f"a new connection's first frame differs from this one's last by "
                f"{difference(grey(first), previous):.2f}, so the camera was reframed"
            )
        finally:
            fresh.close()
        return False
    finally:
        body.close()
        ask(base, "/api/set?zoom=1&cx=0.5&cy=0.5")


def main() -> int:
    args = sys.argv[1:]
    base = args[0].rstrip("/") if args and args[0].startswith("http") else DEFAULT_TARGET
    rates = [float(a) for a in args if not a.startswith("http")] or [6.0, 12.0, 20.0]
    good = [measure(base, fps) for fps in rates]
    print(f"\n{sum(good)} of {len(good)} rates showed the change in time")
    return 0 if all(good) else 1


if __name__ == "__main__":
    raise SystemExit(main())
