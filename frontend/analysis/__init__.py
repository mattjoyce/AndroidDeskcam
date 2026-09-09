"""
Measurement tools for DeskCam captures.

Every published number about this camera came from code that was written once and thrown
away, so nobody could run the method again and see whether it held. These are that code,
in the repository, reading captures and their sidecars off disk.

Three rules run through all of it:

1. A measurement carries its value, its interval and the number of samples behind it.
   A bare number is not a result.
2. A measurement carries its confidence, and a tool refuses below its limit. A refusal is
   never a number with a warning next to it, because a number with a warning next to it
   gets quoted without the warning.
3. Nothing here speaks HTTP. These read files. Driving the camera is the CLI's job, which
   keeps the seam clean if the CLI is ever rewritten in something else.
"""

from .result import Measurement, NoiseFloor

__all__ = ["Measurement", "NoiseFloor"]
