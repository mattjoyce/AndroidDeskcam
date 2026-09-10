"""
Writing a 16-bit PNG, because nothing here can.

Pillow reads 16-bit PNGs and will not write one with more than a single channel:
`Image.fromarray` on a (H, W, 3) uint16 array raises "cannot handle this data type". The
choice was a dependency or forty lines, and this project has made that choice before. Tar.java
exists for the same reason.

Eight bits is not enough for the output of these tools. Averaging sixteen frames lowers the
noise by a factor of four, which puts real detail two bits below what an 8-bit file can
hold, and writing the result as 8-bit throws away exactly the thing the averaging bought.

PNG is a good target for this: lossless, 16 bits a channel in the specification since 1996,
and readable by everything including Pillow. The format is a signature, three chunks, and
zlib. There is no encoder to tune and no quality setting to get wrong.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

import numpy as np


def _chunk(kind: bytes, body: bytes) -> bytes:
    """One PNG chunk: length, type, body, CRC of type and body."""
    return (
        struct.pack(">I", len(body))
        + kind
        + body
        + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    )


def write(path: Path, image: np.ndarray) -> Path:
    """
    Writes a 2-D greyscale or 3-D RGB array as a 16-bit PNG.

    The array is taken as it is: values outside 0..65535 are clipped rather than rescaled,
    because a tool that quietly renormalises its output makes two files that cannot be
    compared with each other. Scaling is the caller's decision and it has to be recorded.
    """
    if image.ndim == 2:
        colour, planes = 0, 1
        rows = image[:, :, None]
    elif image.ndim == 3 and image.shape[2] == 3:
        colour, planes = 2, 3
        rows = image
    else:
        raise ValueError(f"a PNG is greyscale or RGB, got an array of shape {image.shape}")

    height, width = rows.shape[0], rows.shape[1]
    if height < 1 or width < 1:
        raise ValueError("a PNG needs at least one pixel")

    data = np.clip(np.rint(rows), 0, 65535).astype(">u2")
    # Filter type 0 (none) in front of every row. Filtering would compress better and the
    # files here are written once and read once, so the bytes are not worth the code.
    raw = np.zeros((height, width * planes * 2 + 1), dtype=np.uint8)
    raw[:, 1:] = data.reshape(height, width * planes).view(np.uint8)

    header = struct.pack(">IIBBBBB", width, height, 16, colour, 0, 0, 0)
    body = (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", header)
        + _chunk(b"IDAT", zlib.compress(raw.tobytes(), 6))
        + _chunk(b"IEND", b"")
    )
    path.write_bytes(body)
    return path
