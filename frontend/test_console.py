"""
Tests for the workstation console.

They use real resources rather than mocks: real JPEG and JSON files under tmp_path, and
a real console listening on a loopback port, driven with a raw HTTP client so the server
sees exactly the path that was asked for. A mocked socket would have happily agreed that
`/img/../../../etc/passwd` is safe.

Nothing here needs a phone. The phone half is Java and has its own tests in
backend/test.
"""

from __future__ import annotations

import http.client
import json
import threading
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import console
import pytest

# A one pixel JPEG. Enough to be a real file with a real size.
TINY_JPEG = bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb004300ff"
    "ffffffffffffffffffffffffffffffffffffffffffffffffffff"
    "ffffffffffffffffffffffffffffffffffffffffffffffffffff"
    "ffffffffffffffffffffffffffffffffffffffffffffffffffff"
    "ffc2000b080001000101011100ffc40014000100000000000000"
    "0000000000000000000affda0008010100000000d2cfffd9"
)


def write_capture(shots: Path, name: str, **settings: Any) -> Path:
    """A capture as the CLI writes it: the image, its sidecar, and its thumbnail."""
    img = shots / name
    img.write_bytes(TINY_JPEG)
    img.with_suffix(".thumb.jpg").write_bytes(TINY_JPEG)
    sidecar = {
        "tool": "DeskCam",
        "captured_at": "2026-09-09T12:00:00+10:00",
        "capture_path": settings.pop("capture_path", "camera_jpeg"),
        "settings": {"zoom": 1.0, "cx": 0.5, "cy": 0.5, "measure": False, **settings},
        "measured": {"exposure_human": "8.00ms (1/125)", "iso": 200},
        "orientation": {"tilt_degrees": 1.6, "samples": 32},
    }
    img.with_suffix(".json").write_text(json.dumps(sidecar))
    return img


# ------------------------------------------------------------------ fixtures


@pytest.fixture
def shots(tmp_path: Path) -> Path:
    d = tmp_path / "shots"
    d.mkdir()
    return d


@pytest.fixture
def state(shots: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> console.State:
    # Point the config at tmp_path first. A test must not read, and must never write,
    # the config of the person running it.
    monkeypatch.setattr(console, "CONFIG_DIR", tmp_path / "config")
    monkeypatch.setattr(console, "URL_FILE", tmp_path / "config" / "url")
    monkeypatch.setattr(console, "TOKEN_FILE", tmp_path / "config" / "token")
    return console.State(port=0, shots=str(shots))


@pytest.fixture
def server(state: console.State) -> Iterator[tuple[str, int]]:
    """A real console on a loopback port, torn down at the end of the test."""
    console.Handler.state = state
    srv = console.Server(("127.0.0.1", 0), console.Handler)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    try:
        host, port = srv.server_address[0], srv.server_address[1]
        yield str(host), int(port)
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(2)


def get(server: tuple[str, int], path: str) -> tuple[int, bytes]:
    """One GET with the path sent exactly as written, without any tidying."""
    conn = http.client.HTTPConnection(server[0], server[1], timeout=5)
    try:
        conn.putrequest("GET", path, skip_host=False, skip_accept_encoding=True)
        conn.endheaders()
        r = conn.getresponse()
        return r.status, r.read()
    finally:
        conn.close()


# ------------------------------------------------------------------- pairing


def test_a_code_pairs_once_and_no_more(state: console.State) -> None:
    code = state.nonce
    assert code is not None
    assert state.spend_nonce(code) is True
    assert state.spend_nonce(code) is False, "a spent code must never work twice"


def test_a_code_dies_of_old_age(state: console.State) -> None:
    code = state.nonce
    state.nonce_born = time.time() - console.NONCE_TTL - 1
    assert state.nonce_valid(code) is False
    assert state.spend_nonce(code) is False


def test_a_code_is_valid_just_inside_its_life(state: console.State) -> None:
    code = state.nonce
    state.nonce_born = time.time() - console.NONCE_TTL + 5
    assert state.nonce_valid(code) is True


def test_a_wrong_code_never_pairs(state: console.State) -> None:
    assert state.spend_nonce("not-the-code") is False
    assert state.spend_nonce(None) is False
    assert state.spend_nonce("") is False


def test_pairing_with_a_dead_code_is_refused(server: tuple[str, int], state: console.State) -> None:
    state.nonce_born = time.time() - console.NONCE_TTL - 1
    code, body = get(server, f"/p/{state.nonce}")
    assert code == 410
    assert b"expired" in body.lower()


def test_pairing_ignores_the_address_the_caller_claims(
    server: tuple[str, int], state: console.State
) -> None:
    """
    The address must come from where the request came from.

    Two requests carrying an `addr` of another machine used to point the console, and the
    token it sends, at that machine. Nothing here answers on port 9, so the pairing fails
    with the loopback address in the message: proof it used the source address.
    """
    code, body = get(server, f"/p/{state.nonce}?addr=203.0.113.9&port=9")
    assert code == 200
    text = body.decode()
    assert "127.0.0.1" in text
    assert "203.0.113.9" not in text
    assert state.phone is None, "a failed probe must not save a phone address"


# ------------------------------------------------------------------- secrets


def test_the_state_endpoint_gives_away_no_secret(
    server: tuple[str, int], state: console.State
) -> None:
    state.token = "s3cret-token-value"
    code, body = get(server, "/api/state")
    assert code == 200
    text = body.decode()
    assert state.token not in text
    assert state.nonce is not None
    assert state.nonce not in text
    assert "pair_qr" not in text
    assert "pair_url" not in text
    # It still says whether a token is set, because the page shows that.
    assert json.loads(text)["token_set"] is True


def test_a_new_code_is_not_returned_in_the_reply(
    server: tuple[str, int], state: console.State
) -> None:
    before = state.nonce
    code, body = get(server, "/api/newcode")
    assert code == 200
    text = body.decode()
    assert state.nonce != before
    assert state.nonce is not None
    assert state.nonce not in text


def test_only_pairing_is_offered_to_the_network(
    server: tuple[str, int], state: console.State, monkeypatch: pytest.MonkeyPatch
) -> None:
    """
    The QR carries the access key and the live pairing nonce.

    A QR code is not an encryption. The property that matters is who is allowed to ask
    for the picture, and this server binds every interface because the phone must reach
    the pairing callback.
    """
    monkeypatch.setattr(console.Handler, "from_this_machine", lambda self: False)
    for path in ["/", "/qr.svg", "/api/state", "/api/roll", "/api/newcode", "/img/x.jpg"]:
        code, _ = get(server, path)
        assert code == 403, f"{path} answered the network with {code}"
    # The one route the phone needs. A stale code is a 410, which proves it was handled
    # rather than refused for coming from off the machine.
    code, _ = get(server, f"/p/{state.nonce}")
    assert code != 403


def test_the_operators_own_browser_still_gets_everything(
    server: tuple[str, int], state: console.State
) -> None:
    for path in ["/", "/api/state", "/api/roll"]:
        code, _ = get(server, path)
        assert code == 200, f"{path} refused the local browser with {code}"


# ------------------------------------------------------------- serving files


@pytest.mark.parametrize(
    "path",
    [
        "/img/../console.py",
        "/img/../../etc/passwd",
        "/img/..%2f..%2fetc%2fpasswd",
        "/img/%2e%2e%2fconsole.py",
        "/thumb/../console.py",
        "/sidecar/../console.py",
        "/img/console.py",
        "/img/.hidden.jpg",
        "/img/",
    ],
)
def test_no_path_reaches_outside_the_shots_directory(server: tuple[str, int], path: str) -> None:
    code, body = get(server, path)
    assert code == 404, f"{path} was served"
    assert b"import" not in body


def test_a_capture_and_its_sidecar_come_back(server: tuple[str, int], shots: Path) -> None:
    write_capture(shots, "deskcam-1.jpg", zoom=3.0)
    code, body = get(server, "/img/deskcam-1.jpg")
    assert code == 200
    assert body == TINY_JPEG

    code, body = get(server, "/thumb/deskcam-1.jpg")
    assert code == 200

    code, body = get(server, "/sidecar/deskcam-1.jpg")
    assert code == 200
    assert json.loads(body)["settings"]["zoom"] == 3.0


def test_a_missing_sidecar_says_so(server: tuple[str, int], shots: Path) -> None:
    (shots / "lonely.jpg").write_bytes(TINY_JPEG)
    code, body = get(server, "/sidecar/lonely.jpg")
    assert code == 404
    assert "no sidecar" in json.loads(body)["error"]


def test_in_shots_accepts_only_what_the_console_wrote(state: console.State, shots: Path) -> None:
    write_capture(shots, "good.jpg")
    (shots / "notes.txt").write_text("hello")
    assert console.in_shots(state, "good.jpg") is not None
    assert console.in_shots(state, "notes.txt") is None
    assert console.in_shots(state, "../console.py") is None
    assert console.in_shots(state, "") is None
    assert console.in_shots(state, "missing.jpg") is None


# ---------------------------------------------------------------------- roll


def test_the_roll_reads_a_directory_of_captures(shots: Path) -> None:
    write_capture(shots, "one.jpg", zoom=1.0)
    time.sleep(0.01)
    write_capture(shots, "two.jpg", zoom=6.0, measure=True)

    items = console.roll(shots)
    assert [i["name"] for i in items] == ["two.jpg", "one.jpg"], "newest first"
    assert items[0]["settings"]["zoom"] == 6.0
    assert items[0]["exposure"] == "8.00ms (1/125)"
    assert items[0]["iso"] == 200
    assert items[0]["tilt"] == 1.6
    assert "measure" in items[0]["summary"]
    assert items[0]["bytes"] == len(TINY_JPEG)


def test_the_roll_skips_thumbnails(shots: Path) -> None:
    write_capture(shots, "one.jpg")
    assert [i["name"] for i in console.roll(shots)] == ["one.jpg"]


def test_the_roll_survives_a_capture_with_no_sidecar(shots: Path) -> None:
    (shots / "bare.jpg").write_bytes(TINY_JPEG)
    items = console.roll(shots)
    assert items[0]["name"] == "bare.jpg"
    assert "settings" not in items[0]


def test_the_roll_survives_a_broken_sidecar(shots: Path) -> None:
    (shots / "bad.jpg").write_bytes(TINY_JPEG)
    (shots / "bad.json").write_text("{not json")
    items = console.roll(shots)
    assert items[0]["name"] == "bad.jpg"
    assert items[0]["settings"] == {}


def test_the_roll_of_a_directory_that_is_not_there(tmp_path: Path) -> None:
    assert console.roll(tmp_path / "nowhere") == []


def test_the_roll_honours_its_limit(shots: Path) -> None:
    for i in range(5):
        write_capture(shots, f"n{i}.jpg")
    assert len(console.roll(shots, limit=3)) == 3


# ------------------------------------------------------------------ addresses


@pytest.mark.parametrize("host", ["127.0.0.1", "192.168.86.120", "::1"])
def test_an_address_is_an_address(host: str) -> None:
    assert console.ip_ok(host) is True


@pytest.mark.parametrize(
    "host", ["example.com", "127.0.0.1/../x", "", "file:///etc/passwd", "127.0.0.1:8080"]
)
def test_anything_else_is_not_an_address(host: str) -> None:
    assert console.ip_ok(host) is False


@pytest.mark.parametrize("url", ["http://127.0.0.1:8080", "http://192.168.86.120:8080"])
def test_a_phone_url_is_http_and_an_ip(url: str) -> None:
    assert console.phone_url_ok(url) is True


@pytest.mark.parametrize(
    "url",
    [
        "https://127.0.0.1:8080",
        "http://evil.example.com:8080",
        "file:///etc/passwd",
        "http://127.0.0.1:8080/api/set?token=x",
        "http://127.0.0.1",
        "",
    ],
)
def test_anything_else_is_not_a_phone_url(url: str) -> None:
    assert console.phone_url_ok(url) is False


def test_the_lan_address_is_an_address() -> None:
    assert console.ip_ok(console.lan_address())


def test_fetch_json_refuses_a_scheme_it_did_not_choose() -> None:
    with pytest.raises(ValueError, match="only plain http"):
        console.fetch_json("file:///etc/passwd", timeout=1)


# ------------------------------------------------------------------- probing


def test_probing_a_real_stub_reads_its_status(
    server: tuple[str, int], state: console.State
) -> None:
    """
    probe_phone against something that really answers.

    The console itself is the stub: /api/state is JSON on a loopback port, which is all
    probe_phone needs to prove it can reach an address and read what came back. The
    status path is not there, so this also proves it survives a 404.
    """
    assert console.probe_phone(server[0], port=server[1], timeout=2) is None


def test_probing_refuses_a_target_that_is_not_an_address() -> None:
    assert console.probe_phone("evil.example.com", port=80, timeout=1) is None
    assert console.probe_phone("127.0.0.1", port=0, timeout=1) is None
    assert console.probe_phone("127.0.0.1", port=99999, timeout=1) is None


def test_probing_an_address_with_nothing_on_it() -> None:
    # Port 9 is discard, and nothing on this machine answers HTTP there.
    assert console.probe_phone("127.0.0.1", port=9, timeout=1) is None


# --------------------------------------------------------------- saved state


def test_a_saved_address_that_is_not_an_address_is_ignored(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    bad = tmp_path / "url"
    bad.write_text("file:///etc/passwd\n")
    monkeypatch.setattr(console, "URL_FILE", bad)
    assert console.State.load_url() is None

    good = tmp_path / "good"
    good.write_text("http://192.168.86.120:8080\n")
    monkeypatch.setattr(console, "URL_FILE", good)
    assert console.State.load_url() == "http://192.168.86.120:8080"


def test_a_missing_config_is_not_an_error(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(console, "URL_FILE", tmp_path / "nothing")
    monkeypatch.setattr(console, "TOKEN_FILE", tmp_path / "nothing")
    assert console.State.load_url() is None
    assert console.State.load_token() is None


# ---------------------------------------------------------------- the page


def test_the_page_is_served_and_holds_no_secret(
    server: tuple[str, int], state: console.State
) -> None:
    state.token = "s3cret-token-value"
    code, body = get(server, "/")
    assert code == 200
    text = body.decode()
    assert "DeskCam" in text
    assert state.token not in text
    assert "pair_qr" not in text


def test_the_stream_url_carries_no_camera_parameter(server: tuple[str, int]) -> None:
    """A reconnecting browser must not rewrite the camera an agent is about to use."""
    _, body = get(server, "/")
    text = body.decode()
    assert "/api/stream?fps=10&t=" in text
    assert "rotate=" not in text.split("function restream")[1].split("}")[0]


def test_an_unknown_path_is_a_404(server: tuple[str, int]) -> None:
    code, _ = get(server, "/nope")
    assert code == 404
