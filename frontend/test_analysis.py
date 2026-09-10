"""
Tests for the measurement tools.

Synthetic data with a known answer, because the only way to know a measurement tool is
right is to feed it something whose answer you already know. The first version of the burst
noise tool returned 3.18x for an average of six frames, which is above the square root of
six and therefore impossible, and reported maximum confidence while doing it. A test with a
known noise level catches that in a second; a real capture does not, because a real capture
has no known answer to check against.

Nothing here needs a phone.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import pytest
from analysis import aatest, burstnoise, distance, linearity, scale
from analysis.result import Measurement, NoiseFloor, Scale, mean_interval, t95
from PIL import Image

SETTINGS = {
    "camera": "0",
    "zoom": 1.0,
    "cx": 0.5,
    "cy": 0.5,
    "rotate": 0,
    "measure": True,
    "torch": 0,
    "ae": "manual",
    "iso": 56,
    "awb": "auto",
    "focus_diopters": 3.7,
}


def write_capture(
    path: Path, pixels: np.ndarray, exposure_ns: int | None = None, **overrides: object
) -> Path:
    """A capture as the phone writes it: the image plus its sidecar."""
    # Rounded, not truncated. astype() truncates toward zero, which subtracts half a digit
    # from every level, and half a digit is a larger fraction of a dark sample than a bright
    # one. That is a pedestal, and a pedestal bends the measured exponent, so a truncating
    # generator makes a perfectly linear scene measure as 2.02x per doubling. Which is
    # roughly the number this project published.
    Image.fromarray(np.clip(np.round(pixels), 0, 255).astype(np.uint8)).save(path, quality=100)
    settings = {**SETTINGS, **overrides}
    if exposure_ns is not None:
        settings["exposure_ns"] = exposure_ns
    sidecar = {
        "tool": "DeskCam",
        "capture_path": "camera_jpeg",
        "settings": settings,
        "measured": {"exposure_ns": exposure_ns, "iso": settings.get("iso")},
    }
    path.with_suffix(".json").write_text(json.dumps(sidecar))
    return path


# ------------------------------------------------------------------ the gate


def test_a_refusal_carries_no_number() -> None:
    with pytest.raises(ValueError, match="no value"):
        Measurement(method="m", unit="u", value=1.0, status="refused", reason="nope")


def test_an_accepted_measurement_carries_a_number() -> None:
    with pytest.raises(ValueError, match="must carry a value"):
        Measurement(method="m", unit="u", value=None, status="ok")


def test_the_gate_refuses_below_the_confidence_limit() -> None:
    m = Measurement(
        method="m",
        unit="u",
        value=16.0,
        n=8,
        n_min=4,
        confidence=0.33,
        confidence_kind="peak",
        limit=0.60,
    ).gate()
    assert not m.ok
    assert m.value is None
    assert "0.330" in str(m.reason) and "0.600" in str(m.reason)


def test_the_gate_refuses_too_few_samples() -> None:
    m = Measurement(
        method="m",
        unit="u",
        value=16.0,
        n=2,
        n_min=4,
        confidence=0.99,
        confidence_kind="peak",
        limit=0.60,
    ).gate()
    assert not m.ok
    assert "2 samples" in str(m.reason)


def test_the_gate_passes_a_good_measurement() -> None:
    m = Measurement(
        method="m",
        unit="u",
        value=16.0,
        n=8,
        n_min=4,
        confidence=0.9,
        confidence_kind="peak",
        limit=0.60,
    ).gate()
    assert m.ok
    assert m.value == 16.0


def test_a_human_line_always_carries_the_sample_count() -> None:
    m = Measurement(method="m", unit="px/mm", value=16.4, interval=(16.3, 16.5), n=40)
    assert "n=40" in m.human()
    assert "16.3" in m.human() and "16.5" in m.human()


def test_one_sample_says_it_is_one_sample() -> None:
    assert "one sample, no interval" in Measurement(method="m", unit="u", value=2.02, n=1).human()


# --------------------------------------------------------------- statistics


def test_the_interval_widens_for_small_samples() -> None:
    assert t95(3) > t95(10) > t95(60) >= 1.96
    _, few = mean_interval([2.0, 2.1, 1.9, 2.05])
    _, many = mean_interval([2.0, 2.1, 1.9, 2.05] * 8)
    assert few is not None and many is not None
    assert (few[1] - few[0]) > (many[1] - many[0])


def test_one_sample_has_no_interval() -> None:
    value, interval = mean_interval([2.02])
    assert value == 2.02
    assert interval is None


# -------------------------------------------------------------------- scale


def test_scale_finds_a_known_pitch(tmp_path: Path) -> None:
    pitch = 20
    x = np.arange(1600)
    row = 128 + 90 * np.sign(np.sin(2 * np.pi * x / pitch))
    image = np.tile(row, (400, 1))
    path = write_capture(tmp_path / "ticks.jpg", image)

    m = scale.measure(path, pitch_mm=1.0)
    assert m.ok, m.reason
    assert m.value == pytest.approx(pitch, rel=0.02)
    assert m.n >= scale.MIN_STRIPS


def test_scale_converts_a_five_millimetre_reference(tmp_path: Path) -> None:
    x = np.arange(1600)
    image = np.tile(128 + 90 * np.sign(np.sin(2 * np.pi * x / 80)), (400, 1))
    m = scale.measure(write_capture(tmp_path / "grid.jpg", image), pitch_mm=5.0)
    assert m.ok
    assert m.value == pytest.approx(16.0, rel=0.02)


def test_scale_refuses_a_frame_with_no_reference(tmp_path: Path) -> None:
    rng = np.random.default_rng(7)
    image = rng.normal(128, 12, size=(400, 1600))
    m = scale.measure(write_capture(tmp_path / "noise.jpg", image))
    assert not m.ok
    assert m.value is None
    assert "reference" in str(m.reason)


def test_scale_reports_a_competing_pattern(tmp_path: Path) -> None:
    """Two references in one frame is a bench, not an error. It must be said out loud."""
    x = np.arange(1600)
    fine = np.tile(128 + 80 * np.sign(np.sin(2 * np.pi * x / 16)), (300, 1))
    coarse = np.tile(128 + 80 * np.sign(np.sin(2 * np.pi * x / 80)), (500, 1))
    m = scale.measure(write_capture(tmp_path / "both.jpg", np.vstack([fine, coarse])))
    assert m.ok
    assert any("more than one regular pattern" in n for n in m.notes)


def test_scale_can_be_pointed_at_one_region(tmp_path: Path) -> None:
    x = np.arange(1600)
    fine = np.tile(128 + 80 * np.sign(np.sin(2 * np.pi * x / 16)), (400, 1))
    coarse = np.tile(128 + 80 * np.sign(np.sin(2 * np.pi * x / 80)), (400, 1))
    path = write_capture(tmp_path / "both.jpg", np.vstack([fine, coarse]))
    top = scale.measure(path, pitch_mm=1.0, region=(0.5, 0.25, 0.9, 0.4))
    bottom = scale.measure(path, pitch_mm=1.0, region=(0.5, 0.75, 0.9, 0.4))
    assert top.ok and bottom.ok
    assert top.value == pytest.approx(16, rel=0.03)
    assert bottom.value == pytest.approx(80, rel=0.03)


# ---------------------------------------------------------------- linearity


def _series(tmp_path: Path, exponent: float, base: float = 40.0) -> Path:
    d = tmp_path / "lin"
    d.mkdir()
    rng = np.random.default_rng(3)
    for i, ms in enumerate([50, 71, 100, 141, 200]):
        level = base * (ms / 100.0) ** exponent
        pixels = rng.normal(level, 0.5, size=(300, 400))
        write_capture(d / f"e{i}.jpg", pixels, exposure_ns=int(ms * 1e6))
    return d


def test_linearity_measures_a_linear_response(tmp_path: Path) -> None:
    m = linearity.measure(_series(tmp_path, 1.0))
    assert m.ok, m.reason
    assert m.value == pytest.approx(2.0, rel=0.02)
    assert m.interval is not None
    assert m.interval[0] < 2.0 < m.interval[1]


def test_linearity_measures_a_curved_response(tmp_path: Path) -> None:
    """An sRGB-like curve must not be reported as linear."""
    m = linearity.measure(_series(tmp_path, 0.45))
    assert m.ok, m.reason
    assert m.value is not None
    assert m.value == pytest.approx(2**0.45, rel=0.03)
    assert m.value < 1.5


def test_linearity_drops_a_clipped_sample(tmp_path: Path) -> None:
    d = tmp_path / "lin"
    d.mkdir()
    for i, ms in enumerate([50, 100, 200, 400, 800]):
        level = min(255.0, 60.0 * ms / 100.0)
        write_capture(d / f"e{i}.jpg", np.full((300, 400), level), exposure_ns=int(ms * 1e6))
    m = linearity.measure(d)
    assert any("clipped" in n for n in m.notes)


def test_linearity_refuses_too_few_captures(tmp_path: Path) -> None:
    d = tmp_path / "lin"
    d.mkdir()
    write_capture(d / "a.jpg", np.full((300, 400), 100.0), exposure_ns=100_000_000)
    m = linearity.measure(d)
    assert not m.ok
    assert m.value is None


def test_linearity_refuses_steps_inside_the_noise_floor(tmp_path: Path) -> None:
    """The floor recorded by the aa test is enforced here, which is card 35's fourth line."""
    d = tmp_path / "lin"
    d.mkdir()
    rng = np.random.default_rng(5)
    for i, ms in enumerate([100, 101, 102, 103, 104]):
        pixels = rng.normal(100.0 + i * 0.05, 0.4, size=(300, 400))
        write_capture(d / f"e{i}.jpg", pixels, exposure_ns=int(ms * 1e6))
    NoiseFloor(levels_dn=5.0, relative=0.05, n_pixels=1000, measured_at="now").save(d)
    m = linearity.measure(d)
    assert not m.ok
    assert "noise floor" in str(m.reason)


# --------------------------------------------------------------- burst noise


def _burst(tmp_path: Path, frames: int, sigma: float) -> Path:
    d = tmp_path / "burst"
    d.mkdir()
    rng = np.random.default_rng(11)
    scene = np.full((256, 256), 110.0)
    for i in range(frames):
        write_capture(d / f"burst-{i:03d}.jpg", scene + rng.normal(0, sigma, scene.shape))
    return d


def test_burst_noise_matches_the_square_root_law(tmp_path: Path) -> None:
    """
    Independent noise averaged k ways falls by the square root of k.

    This is the test the first implementation would have failed. It reported 3.18x for an
    average of six, which is above the square root of six, because the numerator and the
    denominator were estimated in ways with different small-sample bias.
    """
    m = burstnoise.measure(_burst(tmp_path, 16, sigma=6.0), group=4)
    assert m.ok, m.reason
    assert m.value is not None
    assert m.value == pytest.approx(2.0, rel=0.12)
    assert m.interval is not None
    assert m.interval[0] < 2.0 < m.interval[1]


def test_burst_noise_cannot_beat_the_square_root(tmp_path: Path) -> None:
    m = burstnoise.measure(_burst(tmp_path, 16, sigma=6.0), group=9)
    if m.ok:
        assert m.value is not None
        assert m.value <= math.sqrt(9) * 1.15


def test_burst_noise_refuses_a_short_burst(tmp_path: Path) -> None:
    m = burstnoise.measure(_burst(tmp_path, 2, sigma=6.0))
    assert not m.ok
    assert m.value is None


def test_burst_noise_refuses_a_clipped_region(tmp_path: Path) -> None:
    d = tmp_path / "burst"
    d.mkdir()
    for i in range(8):
        write_capture(d / f"burst-{i:03d}.jpg", np.full((256, 256), 255.0))
    m = burstnoise.measure(d)
    assert not m.ok
    assert "clipped" in str(m.reason) or "floor" in str(m.reason)


# ------------------------------------------------------------------- aa test


def test_aatest_measures_a_known_noise_floor(tmp_path: Path) -> None:
    rng = np.random.default_rng(13)
    sigma = 4.0
    scene = np.full((300, 400), 120.0)
    a = write_capture(tmp_path / "a.jpg", scene + rng.normal(0, sigma, scene.shape))
    b = write_capture(tmp_path / "b.jpg", scene + rng.normal(0, sigma, scene.shape))
    m = aatest.measure(a, b, region=(0.5, 0.5, 0.9, 0.9))
    assert m.ok, m.reason
    assert m.value == pytest.approx(sigma, rel=0.15)


def test_aatest_refuses_captures_taken_differently(tmp_path: Path) -> None:
    scene = np.full((300, 400), 120.0)
    a = write_capture(tmp_path / "a.jpg", scene, exposure_ns=50_000_000)
    b = write_capture(tmp_path / "b.jpg", scene, exposure_ns=200_000_000)
    m = aatest.measure(a, b)
    assert not m.ok
    assert m.value is None
    assert "same settings" in str(m.reason)


def test_aatest_refuses_a_capture_with_no_sidecar(tmp_path: Path) -> None:
    scene = np.full((300, 400), 120.0)
    a = write_capture(tmp_path / "a.jpg", scene)
    b = tmp_path / "b.jpg"
    Image.fromarray(scene.astype(np.uint8)).save(b)
    m = aatest.measure(a, b)
    assert not m.ok
    assert "sidecar" in str(m.reason)


def test_aatest_warns_when_the_exposure_was_automatic(tmp_path: Path) -> None:
    rng = np.random.default_rng(17)
    scene = np.full((300, 400), 120.0)
    a = write_capture(tmp_path / "a.jpg", scene + rng.normal(0, 3, scene.shape), ae="auto")
    b = write_capture(tmp_path / "b.jpg", scene + rng.normal(0, 3, scene.shape), ae="auto")
    m = aatest.measure(a, b)
    assert any("automatic exposure" in n for n in m.notes)


def test_the_noise_floor_survives_a_round_trip(tmp_path: Path) -> None:
    floor = NoiseFloor(levels_dn=1.21, relative=0.012, n_pixels=27180, measured_at="now")
    floor.save(tmp_path)
    back = NoiseFloor.load(tmp_path)
    assert back is not None
    assert back.levels_dn == pytest.approx(1.21)


def test_no_noise_floor_is_not_an_error(tmp_path: Path) -> None:
    assert NoiseFloor.load(tmp_path) is None


# ----------------------------------------------------------------- distance


def with_scale(path: Path, **scale_block: object) -> Path:
    """Adds a scale block to a capture's sidecar, the way the CLI does when it writes one."""
    side = path.with_suffix(".json")
    doc = json.loads(side.read_text())
    doc["scale"] = scale_block
    side.write_text(json.dumps(doc))
    return path


def a_capture(tmp_path: Path, name: str = "shot.jpg") -> Path:
    return write_capture(tmp_path / name, np.full((300, 400), 120.0))


def test_a_distance_is_the_scale_and_nothing_else(tmp_path: Path) -> None:
    path = with_scale(a_capture(tmp_path), applies=True, px_per_mm=20.0)
    m = distance.measure(path, (100.0, 100.0), (300.0, 100.0))
    assert m.ok, m.reason
    assert m.value == pytest.approx(10.0)


def test_a_distance_is_measured_along_the_diagonal(tmp_path: Path) -> None:
    path = with_scale(a_capture(tmp_path), applies=True, px_per_mm=10.0)
    m = distance.measure(path, (0.0, 0.0), (30.0, 40.0))
    assert m.ok
    assert m.value == pytest.approx(5.0)


def test_a_distance_carries_the_scales_interval_the_right_way_round(tmp_path: Path) -> None:
    """More pixels per millimetre is a shorter distance, so the ends of the span swap."""
    path = with_scale(a_capture(tmp_path), applies=True, px_per_mm=20.0, interval=[19.0, 21.0])
    m = distance.measure(path, (0.0, 0.0), (200.0, 0.0))
    assert m.interval is not None
    lo, hi = m.interval
    assert lo == pytest.approx(200 / 21)
    assert hi == pytest.approx(200 / 19)
    assert lo < m.value < hi  # type: ignore[operator]


def test_a_distance_refuses_a_scale_that_no_longer_applies(tmp_path: Path) -> None:
    path = with_scale(a_capture(tmp_path), applies=False, why="the zoom changed")
    m = distance.measure(path, (0.0, 0.0), (10.0, 0.0))
    assert not m.ok
    assert m.value is None
    assert "the zoom changed" in str(m.reason)


def test_a_distance_refuses_a_capture_with_no_scale(tmp_path: Path) -> None:
    m = distance.measure(a_capture(tmp_path), (0.0, 0.0), (10.0, 0.0))
    assert not m.ok
    assert "no scale" in str(m.reason)


def test_a_distance_refuses_a_capture_with_no_sidecar(tmp_path: Path) -> None:
    lonely = tmp_path / "lonely.jpg"
    Image.fromarray(np.full((10, 10), 120, dtype=np.uint8)).save(lonely)
    m = distance.measure(lonely, (0.0, 0.0), (10.0, 0.0))
    assert not m.ok
    assert "no sidecar" in str(m.reason)


def test_a_point_is_x_comma_y(tmp_path: Path) -> None:
    assert distance.parse_point(" 12 , 34 ") == (12.0, 34.0)
    for bad in ("12", "12,34,56", "a,b", ""):
        with pytest.raises(ValueError):
            distance.parse_point(bad)


def test_the_scale_survives_a_round_trip(tmp_path: Path) -> None:
    record = Scale(
        px_per_mm=16.43,
        pitch_mm=1.0,
        width_px=2016,
        height_px=1512,
        settings={"zoom": 2.0},
        measured_at="2026-09-10T12:41:52+10:00",
        image="deskcam-1.jpg",
        interval=(16.40, 16.46),
    )
    record.save(tmp_path)
    back = Scale.load(tmp_path)
    assert back is not None
    assert back.px_per_mm == pytest.approx(16.43)
    assert back.settings["zoom"] == pytest.approx(2.0)


def test_no_recorded_scale_is_not_an_error(tmp_path: Path) -> None:
    assert Scale.load(tmp_path) is None
