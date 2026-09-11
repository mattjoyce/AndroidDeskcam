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
import struct
import zlib
from pathlib import Path

import numpy as np
import pytest
from analysis import (
    aatest,
    average,
    burstnoise,
    distance,
    hdr,
    images,
    linearity,
    png16,
    scale,
    stack,
)
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
    path: Path,
    pixels: np.ndarray,
    exposure_ns: int | None = None,
    awb_gains: list[float] | None = None,
    **overrides: object,
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
        "measured": {
            "exposure_ns": exposure_ns,
            "iso": settings.get("iso"),
            "awb_gains": awb_gains if awb_gains is not None else [2.0, 1.0, 1.0, 2.0],
        },
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


def test_linearity_reports_the_exponent_with_the_pedestal_removed(tmp_path: Path) -> None:
    """
    A linear sensor with a black-level offset must not be published as better than linear.
    The tool reports the raw fit, the pedestal, and the exponent after the pedestal is
    taken out, so a document can quote all three from one run.
    """
    d = tmp_path / "lin"
    d.mkdir()
    rng = np.random.default_rng(7)
    for i, ms in enumerate([50, 71, 100, 141, 200, 283]):
        level = 0.5 * ms - 2.4
        write_capture(
            d / f"e{i}.jpg", rng.normal(level, 0.3, size=(300, 400)), exposure_ns=int(ms * 1e6)
        )
    m = linearity.measure(d)
    assert m.ok, m.reason
    assert m.value is not None
    assert m.value > 2.03, "the raw fit should show the pedestal's bend"
    pedestal = next(n for n in m.notes if "pedestal of" in n)
    assert "-2.4" in pedestal or "-2.3" in pedestal, pedestal
    corrected = next(n for n in m.notes if "pedestal removed" in n)
    exponent = float(corrected.split("exponent is ")[1].split(" ")[0])
    assert exponent == pytest.approx(1.0, abs=0.01), corrected
    assert "95%" in corrected


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


# ------------------------------------------------------ white balance gains


def test_aatest_refuses_two_captures_taken_through_different_colour(tmp_path: Path) -> None:
    """
    Card 42. The gains live in `measured`, so two captures can agree on every setting,
    including awb and awb_lock, and still have been taken through different colour.
    """
    rng = np.random.default_rng(3)
    scene = np.full((300, 400), 120.0)
    a = write_capture(
        tmp_path / "a.jpg", scene + rng.normal(0, 2, scene.shape), awb_gains=[2.0, 1.0, 1.0, 2.0]
    )
    b = write_capture(
        tmp_path / "b.jpg", scene + rng.normal(0, 2, scene.shape), awb_gains=[2.4, 1.0, 1.0, 1.8]
    )
    m = aatest.measure(a, b)
    assert not m.ok
    assert m.value is None
    assert "white balance gains" in str(m.reason)
    assert "awbgains" in str(m.reason), "the refusal has to name the fix"


def test_aatest_allows_the_last_digit_to_wander(tmp_path: Path) -> None:
    """An automatic white balance moves a little between frames. That is not a defect."""
    rng = np.random.default_rng(4)
    scene = np.full((300, 400), 120.0)
    a = write_capture(
        tmp_path / "a.jpg",
        scene + rng.normal(0, 2, scene.shape),
        awb_gains=[2.000, 1.0, 1.0, 2.000],
    )
    b = write_capture(
        tmp_path / "b.jpg",
        scene + rng.normal(0, 2, scene.shape),
        awb_gains=[2.004, 1.0, 1.0, 1.997],
    )
    m = aatest.measure(a, b)
    assert m.ok, m.reason


def test_aatest_says_when_the_gains_were_found_and_not_chosen(tmp_path: Path) -> None:
    rng = np.random.default_rng(5)
    scene = np.full((300, 400), 120.0)
    a = write_capture(tmp_path / "a.jpg", scene + rng.normal(0, 2, scene.shape))
    b = write_capture(tmp_path / "b.jpg", scene + rng.normal(0, 2, scene.shape))
    m = aatest.measure(a, b)
    assert m.ok, m.reason
    assert any("another session will lock different ones" in n for n in m.notes)


def test_gains_of_reads_what_the_camera_applied(tmp_path: Path) -> None:
    path = write_capture(
        tmp_path / "a.jpg", np.full((10, 10), 120.0), awb_gains=[1.99, 1.0, 1.0, 2.07]
    )
    assert images.gains_of(path) == (1.99, 1.0, 1.0, 2.07)

    lonely = tmp_path / "lonely.jpg"
    Image.fromarray(np.full((10, 10), 120, dtype=np.uint8)).save(lonely)
    assert images.gains_of(lonely) is None


def test_linearity_warns_when_the_colour_changed_mid_series(tmp_path: Path) -> None:
    for i, ns in enumerate([4_000_000, 8_000_000, 16_000_000, 32_000_000]):
        level = 20.0 * (ns / 4_000_000)
        write_capture(
            tmp_path / f"shot{i}.jpg",
            np.full((200, 300), level),
            exposure_ns=ns,
            awb_gains=[2.0 + 0.3 * i, 1.0, 1.0, 2.0],
        )
    m = linearity.measure(tmp_path)
    assert any("white balance gains are not constant" in n for n in m.notes)


# --------------------------------------------------- the 16-bit PNG writer


def read_png16(path: Path) -> tuple[int, int, int, np.ndarray]:
    """Decodes a PNG by hand, because Pillow reads a 16-bit RGB one down to 8 bits."""
    raw = path.read_bytes()
    assert raw[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    chunks: dict[bytes, bytes] = {}
    while pos < len(raw):
        n = struct.unpack(">I", raw[pos : pos + 4])[0]
        kind, body = raw[pos + 4 : pos + 8], raw[pos + 8 : pos + 8 + n]
        crc = struct.unpack(">I", raw[pos + 8 + n : pos + 12 + n])[0]
        assert crc == zlib.crc32(kind + body) & 0xFFFFFFFF, f"CRC on {kind!r}"
        chunks[kind] = chunks.get(kind, b"") + body
        pos += 12 + n
    w, h, depth, colour = struct.unpack(">IIBB", chunks[b"IHDR"][:10])
    planes = 3 if colour == 2 else 1
    flat = np.frombuffer(zlib.decompress(chunks[b"IDAT"]), np.uint8).reshape(h, -1)
    assert (flat[:, 0] == 0).all(), "every row carries filter type 0"
    pixels = flat[:, 1:].reshape(h, w, planes * 2).view(">u2").reshape(h, w, planes)
    return w, h, depth, pixels.astype(np.uint16)


def test_a_16_bit_png_survives_the_values_8_bits_cannot_hold(tmp_path: Path) -> None:
    """The whole reason this writer exists: neighbouring values near the top of the range."""
    rgb = np.zeros((8, 8, 3), np.uint16)
    rgb[:, :, 0] = np.arange(64).reshape(8, 8) + 65000
    rgb[:, :, 1] = 1000
    rgb[:, :, 2] = 40000
    w, h, depth, back = read_png16(png16.write(tmp_path / "rgb.png", rgb))
    assert (w, h, depth) == (8, 8, 16)
    assert np.array_equal(back, rgb)


def test_a_16_bit_png_can_be_greyscale(tmp_path: Path) -> None:
    grey = (np.arange(64).reshape(8, 8) * 1000).astype(np.uint16)
    _, _, depth, back = read_png16(png16.write(tmp_path / "g.png", grey))
    assert depth == 16
    assert np.array_equal(back[:, :, 0], grey)
    # Pillow can read a 16-bit greyscale PNG, so this one round-trips through it too.
    assert np.array_equal(np.asarray(Image.open(tmp_path / "g.png")), grey)


def test_a_png_clips_rather_than_rescaling(tmp_path: Path) -> None:
    """Two files of one subject have to be comparable, so nothing is renormalised."""
    _, _, _, back = read_png16(png16.write(tmp_path / "c.png", np.array([[-5.0, 70000.0]])))
    assert back[0, 0, 0] == 0
    assert back[0, 1, 0] == 65535


def test_a_png_refuses_an_array_that_is_not_an_image(tmp_path: Path) -> None:
    for bad in (np.zeros((4, 4, 2)), np.zeros(4), np.zeros((0, 4))):
        with pytest.raises(ValueError):
            png16.write(tmp_path / "bad.png", bad)


# ------------------------------------------------------------ burst average


def test_averaging_a_burst_writes_16_bit_and_measures_what_it_bought(tmp_path: Path) -> None:
    rng = np.random.default_rng(21)
    scene = np.full((240, 320), 120.0)
    for i in range(12):
        write_capture(tmp_path / f"burst-{i:03d}.jpg", scene + rng.normal(0, 4, scene.shape))
    (tmp_path / "burst.json").write_text((tmp_path / "burst-000.json").read_text())
    for i in range(12):
        (tmp_path / f"burst-{i:03d}.json").unlink()

    m = average.measure(tmp_path)
    assert m.ok, m.reason
    # The improvement is measured by burstnoise, not predicted here.
    assert m.value == pytest.approx(math.sqrt(12 // 4), rel=0.3)
    _, _, depth, _ = read_png16(tmp_path / f"{tmp_path.name}-average.png")
    assert depth == 16


def test_averaging_refuses_frames_that_are_not_one_burst(tmp_path: Path) -> None:
    rng = np.random.default_rng(22)
    scene = np.full((120, 160), 120.0)
    write_capture(tmp_path / "a.jpg", scene + rng.normal(0, 3, scene.shape), zoom=1.0)
    write_capture(tmp_path / "b.jpg", scene + rng.normal(0, 3, scene.shape), zoom=4.0)
    m = average.measure(tmp_path)
    assert not m.ok
    assert "not taken alike" in str(m.reason)
    assert "zoom" in str(m.reason)


# -------------------------------------------------------------- the hdr merge


def a_bracket(directory: Path, exposures_ns: list[int], scale: float = 4e-7) -> None:
    """A bracket of one scene: a ramp of true radiance, sampled at several exposures."""
    _, x = np.mgrid[0:120, 0:200]
    radiance = 200.0 + x * 60.0  # DN per second, a wide range across the frame
    for ns in exposures_ns:
        level = np.clip(radiance * (ns * 1e-9) / scale * 4e-7, 0, 255)
        write_capture(directory / f"exposure-{ns}.jpg", level, exposure_ns=ns, measure=True)
        side = directory / f"exposure-{ns}.json"
        doc = json.loads(side.read_text())
        doc["pipeline"] = {"tonemap_points": 2, "tonemap_curve": "(0.0,0.0) (1.0,1.0)"}
        side.write_text(json.dumps(doc))
    (directory / "walk.json").write_text(
        json.dumps({"pipeline": {"tonemap_points": 2, "tonemap_curve": "(0.0,0.0) (1.0,1.0)"}})
    )


def test_a_merge_recovers_one_radiance_from_several_exposures(tmp_path: Path) -> None:
    a_bracket(tmp_path, [4_000_000, 8_000_000, 16_000_000, 32_000_000])
    m = hdr.measure(tmp_path)
    assert m.ok, m.reason
    merged = np.load(tmp_path / f"{tmp_path.name}-hdr.npy")
    assert merged.dtype == np.float32
    # The radiance of the ramp is recovered in proportion: twice as bright is twice the
    # number, whatever exposure measured it.
    row = merged[60, :, 0]
    assert row[150] / row[50] == pytest.approx((200 + 150 * 60) / (200 + 50 * 60), rel=0.05)


def test_a_merge_refuses_a_tone_mapped_bracket(tmp_path: Path) -> None:
    """value / exposure is only radiance when the response is linear. Refuse, do not guess."""
    a_bracket(tmp_path, [4_000_000, 8_000_000])
    (tmp_path / "walk.json").write_text(
        json.dumps({"pipeline": {"tonemap_points": 32, "tonemap_curve": "(0.0,0.0) (0.5,0.7)"}})
    )
    m = hdr.measure(tmp_path)
    assert not m.ok
    assert "tone map" in str(m.reason)
    assert "measure=1" in str(m.reason)


def test_a_merge_refuses_a_burst(tmp_path: Path) -> None:
    a_bracket(tmp_path, [8_000_000])
    write_capture(tmp_path / "same.jpg", np.full((120, 200), 100.0), exposure_ns=8_000_000)
    side = json.loads((tmp_path / "same.json").read_text())
    side["pipeline"] = {"tonemap_points": 2, "tonemap_curve": "(0.0,0.0) (1.0,1.0)"}
    (tmp_path / "same.json").write_text(json.dumps(side))
    m = hdr.measure(tmp_path)
    assert not m.ok
    assert "burst and not a bracket" in str(m.reason)


# ------------------------------------------------------------- the focus stack


def a_sweep(directory: Path, blurs: list[tuple[int, int]], mags: list[float]) -> None:
    """A sweep of a scene whose left and right halves are at different depths."""
    rng = np.random.default_rng(11)
    height, width = 400, 600
    scene = np.clip(rng.normal(128, 45, (height, width)), 0, 255)
    for i, ((left, right), mag) in enumerate(zip(blurs, mags, strict=True)):
        frame = np.zeros_like(scene)
        frame[:, : width // 2] = _blur(scene[:, : width // 2], left)
        frame[:, width // 2 :] = _blur(scene[:, width // 2 :], right)
        write_capture(directory / f"focus-{i:02d}.jpg", stack._resample(frame, mag))


def _blur(a: np.ndarray, k: int) -> np.ndarray:
    # Odd only. An even window leaves the result one row and one column larger, which is
    # the same off-by-one the tool's own box blur had.
    k = k | 1
    if k <= 1:
        return a
    pad = k // 2
    out = np.pad(a, pad, mode="edge")
    c = np.vstack([np.zeros((1, out.shape[1])), out.cumsum(0)])
    rows = (c[k:, :] - c[:-k, :]) / k
    c2 = np.hstack([np.zeros((rows.shape[0], 1)), rows.cumsum(1)])
    return (c2[:, k:] - c2[:, :-k]) / k


def test_a_stack_is_sharper_than_any_frame_that_went_into_it(tmp_path: Path) -> None:
    """Each frame is sharp in one half, so a stack of both must beat either. Card 6."""
    a_sweep(tmp_path, [(1, 5), (1, 3), (3, 3), (3, 1), (5, 1)], [1.0] * 5)
    m = stack.measure(tmp_path)
    assert m.ok, m.reason
    sharper = [n for n in m.notes if "times as sharp" in n]
    assert sharper and float(sharper[0].split()[3]) > 1.2, sharper


def test_a_stack_refuses_a_subject_that_did_not_need_one(tmp_path: Path) -> None:
    """A flat subject's best frame is already the answer; blending can only blur it."""
    a_sweep(tmp_path, [(3, 3), (2, 2), (1, 1), (2, 2), (3, 3)], [1.0] * 5)
    m = stack.measure(tmp_path)
    assert not m.ok
    assert "flat" in str(m.limit_justification)


def test_a_stack_measures_the_magnification_rather_than_assuming_it(tmp_path: Path) -> None:
    """Focus breathing, and the direction of the correction. Half a percent a step."""
    mags = [0.990, 0.995, 1.000, 1.005, 1.010]
    a_sweep(tmp_path, [(1, 5), (1, 3), (3, 3), (3, 1), (5, 1)], mags)
    m = stack.measure(tmp_path)
    assert m.ok, m.reason
    found = next(n for n in m.notes if "magnification against" in n)
    scales = [float(v) for v in found.split("reference ")[1].split(", ")]
    # A frame magnified by more than the reference is corrected by less than 1, and the
    # corrections walk one way across the sweep rather than wandering.
    assert scales == sorted(scales, reverse=True), scales
    assert max(scales) - min(scales) == pytest.approx(0.02, abs=0.01)


def test_a_box_blur_matches_a_window_mean() -> None:
    """The summed-area table had an off-by-one that only showed as a broadcast error."""
    rng = np.random.default_rng(1)
    for shape in ((9, 11), (32, 32), (17, 5)):
        for size in (1, 3, 5, 15):
            a = rng.normal(50, 10, shape)
            pad = size // 2
            p = np.pad(a, pad, mode="edge")
            want = np.array(
                [
                    [p[i : i + size, j : j + size].mean() for j in range(shape[1])]
                    for i in range(shape[0])
                ]
            )
            assert np.allclose(stack._box(a, size), want), (shape, size)
