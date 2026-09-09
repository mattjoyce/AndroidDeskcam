"""The shape of a measurement, and the rule that lets a tool refuse one."""

from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

# Two-sided 95% critical values of Student's t, by degrees of freedom.
#
# A table rather than scipy. Four exposure steps is a real sample size for this work, and
# at four samples the normal approximation understates the interval by about 40%, which is
# the difference between an honest interval and a decorative one.
_T95 = {
    1: 12.706,
    2: 4.303,
    3: 3.182,
    4: 2.776,
    5: 2.571,
    6: 2.447,
    7: 2.365,
    8: 2.306,
    9: 2.262,
    10: 2.228,
    12: 2.179,
    15: 2.131,
    20: 2.086,
    30: 2.042,
    60: 2.000,
}


def t95(df: int) -> float:
    """The 95% critical value for df degrees of freedom, rounded conservatively upward."""
    if df < 1:
        return math.inf
    if df in _T95:
        return _T95[df]
    smaller = [k for k in _T95 if k <= df]
    if not smaller:
        return _T95[1]
    if df > 60:
        return 1.960
    return _T95[max(smaller)]


def mean_interval(samples: list[float]) -> tuple[float, tuple[float, float] | None]:
    """The mean of some samples and its 95% interval. One sample has no interval."""
    n = len(samples)
    mean = sum(samples) / n
    if n < 2:
        return mean, None
    variance = sum((s - mean) ** 2 for s in samples) / (n - 1)
    half = t95(n - 1) * math.sqrt(variance / n)
    return mean, (mean - half, mean + half)


@dataclass
class Measurement:
    """
    One measured quantity, or a refusal to measure it.

    The invariant enforced below is the whole point of card 36: a refused measurement has
    no value at all. A scale figure was once printed as firm next to its own correlation of
    0.08, and nothing objected. A number with a caveat beside it travels without the caveat,
    so the only safe representation of "not certain enough" is the absence of a number.
    """

    method: str
    unit: str
    value: float | None = None
    interval: tuple[float, float] | None = None
    n: int = 0
    n_min: int = 0
    confidence: float | None = None
    confidence_kind: str = ""
    limit: float | None = None
    limit_justification: str = ""
    status: str = "ok"
    reason: str | None = None
    notes: list[str] = field(default_factory=list)
    inputs: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        if self.status not in ("ok", "refused"):
            raise ValueError(f"status must be ok or refused, got {self.status!r}")
        if self.status == "refused" and self.value is not None:
            raise ValueError(
                "a refused measurement must carry no value; "
                "a number with a warning beside it is how the warning gets lost"
            )
        if self.status == "ok" and self.value is None:
            raise ValueError("an accepted measurement must carry a value")

    @property
    def ok(self) -> bool:
        return self.status == "ok"

    @classmethod
    def refuse(cls, method: str, unit: str, reason: str, **kw: Any) -> Measurement:
        kw.pop("value", None)
        return cls(method=method, unit=unit, status="refused", reason=reason, **kw)

    def gate(self) -> Measurement:
        """
        Applies the confidence limit and the sample floor.

        Call this at the end of every measurement. It is the single place where a value
        turns into a refusal, so no tool can forget to check, and none can invent its own
        idea of what counts as certain enough.
        """
        if self.status == "refused":
            return self
        if self.n < self.n_min:
            return Measurement.refuse(
                self.method,
                self.unit,
                f"{self.n} samples is fewer than the {self.n_min} this method needs",
                n=self.n,
                n_min=self.n_min,
                confidence=self.confidence,
                confidence_kind=self.confidence_kind,
                limit=self.limit,
                limit_justification=self.limit_justification,
                notes=self.notes,
                inputs=self.inputs,
            )
        if self.limit is not None and self.confidence is not None and self.confidence < self.limit:
            return Measurement.refuse(
                self.method,
                self.unit,
                f"{self.confidence_kind} is {self.confidence:.3f}, "
                f"below the limit of {self.limit:.3f} for this method",
                n=self.n,
                n_min=self.n_min,
                confidence=self.confidence,
                confidence_kind=self.confidence_kind,
                limit=self.limit,
                limit_justification=self.limit_justification,
                notes=self.notes,
                inputs=self.inputs,
            )
        return self

    # ------------------------------------------------------------------ output

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, default=_plain)

    def human(self) -> str:
        """One line for a person. The interval and the sample count are never optional."""
        # No assert here on purpose. An assertion that guards an invariant disappears
        # under python -O, and this one is the invariant the whole class exists for.
        value = self.value
        if not self.ok or value is None:
            return f"{self.method}: REFUSED. {self.reason}"
        span = ""
        if self.interval:
            lo, hi = self.interval
            span = f" (95% {lo:.4g} to {hi:.4g})"
        elif self.n == 1:
            span = " (one sample, no interval)"
        conf = ""
        if self.confidence is not None:
            conf = f", {self.confidence_kind} {self.confidence:.3f}"
        return f"{self.method}: {value:.4g} {self.unit}{span}, n={self.n}{conf}"


@dataclass
class NoiseFloor:
    """
    What the instrument cannot tell apart, written down so other tools can read it.

    Produced by the same-against-same test. A measured difference smaller than this is the
    camera talking to itself.
    """

    levels_dn: float
    relative: float
    n_pixels: int
    measured_at: str
    settings: dict[str, Any] = field(default_factory=dict)
    inputs: list[str] = field(default_factory=list)

    FILENAME = "deskcam-noisefloor.json"

    def save(self, directory: Path) -> Path:
        path = directory / self.FILENAME
        path.write_text(json.dumps(asdict(self), indent=2, default=_plain))
        return path

    @classmethod
    def load(cls, directory: Path) -> NoiseFloor | None:
        """The floor recorded for this shots directory, if the aa test has been run."""
        path = directory / cls.FILENAME
        if not path.is_file():
            return None
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            return None
        known = {f for f in cls.__dataclass_fields__}
        return cls(**{k: v for k, v in data.items() if k in known})


def _plain(o: Any) -> Any:
    if hasattr(o, "item"):
        return o.item()
    if isinstance(o, (set, tuple)):
        return list(o)
    raise TypeError(f"cannot serialise {type(o)}")
