#!/usr/bin/env python3
"""
DeskCam local console.

A small web page on the workstation. It pairs the phone by QR code, then acts as the
human's window onto the camera. The agent keeps using the CLI and the HTTP contract;
this is the surface a person needs for aiming, reviewing, and key handling.

Pairing works without any change to the Android app. The QR holds an http address of
THIS machine. The phone browser opens it, and we read the phone address from the source
address of that request. The round trip also proves both directions of the network,
which is the exact thing that failed silently before.
"""

import argparse
import http.server
import io
import ipaddress
import json
import logging
import os
import re
import secrets
import socket
import socketserver
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

log = logging.getLogger("deskcam.console")

CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "deskcam"
URL_FILE = CONFIG_DIR / "url"
TOKEN_FILE = CONFIG_DIR / "token"

PHONE_PORT = 8080
NONCE_TTL = 600  # a pairing code is dead after ten minutes

# The console serves the captures it wrote and nothing else. It listens on every
# interface, because the phone has to reach it, so "a file in the working directory" is
# a file offered to the whole network.
SERVABLE = frozenset({".jpg", ".jpeg", ".dng", ".json"})


class State:
    """Everything the console knows. Guarded by a lock, mutated from request threads."""

    def __init__(self, port: int, shots: str) -> None:
        self.lock = threading.Lock()
        self.port = port
        self.shots = Path(shots).resolve()
        self.nonce: str | None = None
        self.nonce_born: float = 0.0
        self.phone = self.load_url()
        self.token = self.load_token()
        self.last_pair: dict[str, Any] | None = None
        self.last_error: str | None = None
        self.new_nonce()

    # ---------------------------------------------------------------- config

    @staticmethod
    def load_url() -> str | None:
        try:
            saved = URL_FILE.read_text().strip()
        except OSError as e:
            log.debug("no saved phone address: %s", e)
            return None
        if saved and not phone_url_ok(saved):
            # A saved file is not a trusted file. Everything downstream builds a URL out
            # of this, so it has to be a plain http address of a literal IP.
            log.warning("ignoring the saved phone address %r: not an http address of an IP", saved)
            return None
        return saved or None

    @staticmethod
    def load_token() -> str | None:
        try:
            return TOKEN_FILE.read_text().strip() or None
        except OSError as e:
            log.debug("no saved token: %s", e)
            return None

    def save_url(self, url: str) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        URL_FILE.write_text(url + "\n")
        self.phone = url

    # ----------------------------------------------------------------- nonce

    def new_nonce(self) -> str:
        with self.lock:
            self.nonce = secrets.token_urlsafe(9)
            self.nonce_born = time.time()
            return self.nonce

    def nonce_valid(self, n: str | None) -> bool:
        with self.lock:
            return bool(n and n == self.nonce and (time.time() - self.nonce_born) < NONCE_TTL)

    def spend_nonce(self, n: str | None) -> bool:
        """Checks a code and burns it in the same breath, so it works exactly once."""
        with self.lock:
            ok = bool(n and n == self.nonce and (time.time() - self.nonce_born) < NONCE_TTL)
            if ok:
                self.nonce = secrets.token_urlsafe(9)
                self.nonce_born = time.time()
            return ok

    def nonce_age(self) -> int:
        with self.lock:
            return int(time.time() - self.nonce_born)


def lan_address() -> str:
    """The address of this machine on the route out, not a docker bridge."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))  # no packet is sent, this only picks a route
        return str(s.getsockname()[0])
    except OSError as e:
        log.debug("no route out, falling back to loopback: %s", e)
        return "127.0.0.1"
    finally:
        s.close()


def ip_ok(host: str) -> bool:
    """True for a literal IP address. Names, schemes and paths are not addresses."""
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return False
    return True


def phone_url_ok(url: str) -> bool:
    """
    True for `http://IP:PORT` and nothing else.

    Every request the console makes to the phone is built from this string, so a value
    that reached it from anywhere but the source address of a paired request could point
    the console, and the token it carries, somewhere else. bandit reports the same thing
    from the other side as B310: urlopen will happily open file: or ftp: if it is given
    one.
    """
    return re.fullmatch(r"http://[0-9a-fA-F.:\[\]]+:\d{1,5}", url) is not None and (
        ip_ok(url.rsplit(":", 1)[0][len("http://") :].strip("[]"))
    )


def fetch_json(url: str, timeout: float) -> Any:
    """Opens an http URL the console built itself, and refuses any other scheme."""
    if not url.startswith("http://"):
        raise ValueError(f"refusing to open {url!r}: only plain http is allowed")
    # B310 asks whether this can be handed a file: or a custom scheme. It cannot: the
    # line above refuses anything but http, and every URL that reaches here was built by
    # this module out of an IP that ip_ok accepted and a port in range.
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as r:  # nosec B310
        return json.loads(r.read().decode())


def probe_phone(
    ip: str, token: str | None = None, timeout: float = 4, port: int = PHONE_PORT
) -> dict[str, Any] | None:
    """Ask a candidate address for its status. This confirms it really is DeskCam."""
    if not ip_ok(ip) or not 1 <= port <= 65535:
        log.warning("refusing to probe %r:%r, which is not an address and a port", ip, port)
        return None
    host = f"[{ip}]" if ":" in ip else ip
    url = f"http://{host}:{port}/api/status"
    if token:
        url += f"?token={urllib.parse.quote(token)}"
    try:
        result = fetch_json(url, timeout)
    except (urllib.error.URLError, OSError, ValueError, TimeoutError) as e:
        log.debug("no answer from %s: %s", url.split("?", 1)[0], e)
        return None
    return result if isinstance(result, dict) else None


class Handler(http.server.BaseHTTPRequestHandler):
    state: State  # set on the server class below, before serving

    def log_message(self, fmt: str, *args: Any) -> None:
        # The console is not a web server log, but a request that went wrong should not
        # vanish either. It goes to the logger at debug, where -v can find it.
        log.debug("%s %s", self.address_string(), fmt % args)

    # ------------------------------------------------------------- responses

    def send(
        self, code: int, ctype: str, body: bytes | str, extra: dict[str, str] | None = None
    ) -> None:
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, obj: Any, code: int = 200) -> None:
        self.send(code, "application/json", json.dumps(obj, indent=2))

    # --------------------------------------------------------------- routing

    def from_this_machine(self) -> bool:
        """True when the request came from the machine the console runs on."""
        host = self.client_address[0]
        if host.startswith("::ffff:"):
            host = host[7:]
        try:
            return ipaddress.ip_address(host).is_loopback
        except ValueError:
            return False

    # The capitals are BaseHTTPRequestHandler's naming, not a choice made here.
    def do_GET(self) -> None:
        st = self.state
        path = self.path.split("?", 1)[0]

        # Exactly one route is offered to the network: /p/, the callback the phone makes
        # to finish pairing. Everything else belongs to the operator's own browser.
        #
        # This server binds every interface because the phone has to reach /p/, and
        # /qr.svg renders the pairing code, which carries the access key and the live
        # nonce. Any machine on the network could fetch that image and decode both. Card
        # 29 moved the key out of the JSON and left it in the picture, same server,
        # different encoding, and a test asserting the key was not in the SVG as literal
        # text read as clearance.
        if not path.startswith("/p/") and not self.from_this_machine():
            self.send(
                403,
                "text/plain",
                "this console answers the browser on the machine it runs on; "
                "only pairing is offered to the network",
            )
            return

        if path == "/":
            self.send(200, "text/html; charset=utf-8", page(st))
            return

        if path == "/qr.svg":
            # The QR image may carry the token, because it is a picture on the operator's
            # own screen. The JSON of /api/state may not, because anyone on the network
            # can read that.
            import segno

            qr = segno.make(pair_qr(st), error="m")
            buf = io.BytesIO()  # segno writes bytes, not str
            qr.save(buf, kind="svg", scale=6, border=2, dark="#e6edf3", light=None)
            self.send(200, "image/svg+xml", buf.getvalue())
            return

        if path.startswith("/p/"):
            self.handle_pair(path[3:])
            return

        if path == "/api/state":
            self.send_json(console_state(st))
            return

        if path == "/api/roll":
            self.send_json({"captures": roll(st.shots)})
            return

        if path.startswith("/thumb/"):
            self.send_image(st, path[7:], thumb=True)
            return

        if path.startswith("/sidecar/"):
            name = os.path.basename(urllib.parse.unquote(path[9:]))
            f = in_shots(st, Path(name).with_suffix(".json").name)
            if f is not None:
                self.send(200, "application/json", f.read_bytes())
            else:
                self.send_json({"error": "no sidecar for " + name}, 404)
            return

        if path.startswith("/img/"):
            self.send_image(st, path[5:], thumb=False)
            return

        if path == "/api/cam":
            # Forward a control request to the phone, so the page only ever talks to
            # the console. The live stream still comes straight from the phone,
            # because relaying MJPEG would cost far more than it is worth.
            if not st.phone:
                self.send_json({"ok": False, "error": "no phone paired"}, 400)
                return
            q = self.path.split("?", 1)[1] if "?" in self.path else ""
            url = f"{st.phone}/api/set" + (f"?{q}" if q else "")
            if st.token:
                url += ("&" if "?" in url else "?") + f"token={st.token}"
            try:
                self.send_json(fetch_json(url, timeout=8))
            except (urllib.error.URLError, OSError, ValueError, TimeoutError) as e:
                log.warning("relaying to the phone failed: %s", e)
                self.send_json({"ok": False, "error": str(e)}, 502)
            return

        if path == "/api/newcode":
            # The reply says a new code exists. It does not say what the code is: this
            # endpoint has no password either, and the pairing text carries both the
            # nonce and the token. Read the code off the QR image.
            st.new_nonce()
            self.send_json({"ok": True, "code_age_seconds": st.nonce_age()})
            return

        self.send(404, "text/plain", "no such page")

    def send_image(self, st: State, name: str, thumb: bool) -> None:
        f = in_shots(st, urllib.parse.unquote(name))
        if f is None:
            self.send(404, "text/plain", "no such capture")
            return
        # The phone writes NAME.thumb.jpg beside the capture, so there is nothing to
        # decode here. A capture taken before that existed falls back to the full image.
        if thumb:
            t = f.with_suffix(".thumb.jpg")
            if t.is_file():
                self.send(200, "image/jpeg", t.read_bytes())
                return
        ctype = (
            "image/jpeg" if f.suffix.lower() in (".jpg", ".jpeg") else "application/octet-stream"
        )
        self.send(200, ctype, f.read_bytes())

    # --------------------------------------------------------------- pairing

    def handle_pair(self, nonce: str) -> None:
        """
        Turns a scanned code into a paired phone.

        The address comes from where the request came FROM, never from what it says
        about itself. It used to take an `addr` from the query, and it read that before
        it checked the code, so two requests could point the console at another machine,
        which then received the token through /api/cam. Only the port is taken from the
        caller, because only the app knows which port it bound.
        """
        st = self.state
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)

        # One code, one pairing. Spending it here also closes the window in which two
        # requests could race on the same code.
        if not st.spend_nonce(nonce):
            self.send(
                410,
                "text/html; charset=utf-8",
                phone_page("Code expired", "Load the console page again to get a new code.", False),
            )
            return

        ip = self.client_address[0]
        if ip.startswith("::ffff:"):
            ip = ip[7:]
        try:
            port = int(q.get("port", [str(PHONE_PORT)])[0] or PHONE_PORT)
        except ValueError:
            port = PHONE_PORT
        if not 1 <= port <= 65535:
            port = PHONE_PORT

        if not ip_ok(ip):
            st.last_error = f"the pairing request came from {ip!r}, which is not an address"
            log.warning("%s", st.last_error)
            self.send(
                400,
                "text/html; charset=utf-8",
                phone_page(
                    "Cannot pair",
                    "This workstation could not read the address you came from.",
                    False,
                ),
            )
            return

        status = probe_phone(ip, st.token, port=port)
        if status is None:
            st.last_error = (
                f"Saw the phone at {ip}, but could not reach "
                f"http://{ip}:{port}/api/status. Is DeskCam running?"
            )
            log.warning("%s", st.last_error)
            self.send(
                200,
                "text/html; charset=utf-8",
                phone_page(
                    "Almost",
                    f"This workstation saw you at {ip}, but the DeskCam service did not "
                    f"answer on port {port}. Open DeskCam and press Start, then scan "
                    f"the code again.",
                    False,
                ),
            )
            return

        host = f"[{ip}]" if ":" in ip else ip
        url = f"http://{host}:{port}"
        st.save_url(url)
        st.last_pair = {
            "at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "url": url,
            "camera": status.get("settings", {}).get("camera"),
            "state": status.get("state"),
        }
        st.last_error = None
        log.info("paired with %s", url)
        self.send(
            200,
            "text/html; charset=utf-8",
            phone_page(
                "Paired", f"This phone is now the camera at {url}. You can close this page.", True
            ),
        )


def in_shots(st: State, name: str) -> Path | None:
    """
    The path of a capture, or None.

    Only a file the console itself wrote, only in the shots directory, and only a name
    that stays inside it. The console listens on every interface so the phone can reach
    it, which makes "serve a file from the working directory by name" an offer to the
    whole network.
    """
    name = os.path.basename(name)
    if not name or name.startswith(".") or Path(name).suffix.lower() not in SERVABLE:
        return None
    f = (st.shots / name).resolve()
    if f.parent != st.shots or not f.is_file():
        return None
    return f


def roll(shots: Path, limit: int = 60) -> list[dict[str, Any]]:
    """
    The captures of this session, newest first.

    The sidecar written by the CLI is the only index. A directory listing plus those
    files is enough, so there is no database to keep in step with the files.
    """
    out: list[dict[str, Any]] = []
    try:
        files = sorted(
            (f for f in shots.glob("*.jpg") if not f.name.endswith(".thumb.jpg")),
            key=lambda f: f.stat().st_mtime,
            reverse=True,
        )
    except OSError as e:
        log.warning("cannot read the shots directory %s: %s", shots, e)
        return out
    for f in files[:limit]:
        item: dict[str, Any] = {
            "name": f.name,
            "mtime": f.stat().st_mtime,
            "bytes": f.stat().st_size,
        }
        side = f.with_suffix(".json")
        if side.is_file():
            try:
                d = json.loads(side.read_text())
            except (OSError, ValueError) as e:
                log.warning("unreadable sidecar %s: %s", side.name, e)
                d = {}
            g = d.get("settings", {})
            m = d.get("measured", {})
            o = d.get("orientation", {})
            item["when"] = d.get("captured_at")
            item["summary"] = f"zoom {g.get('zoom')}x  {g.get('cx')},{g.get('cy')}" + (
                "  measure" if g.get("measure") else ""
            )
            item["exposure"] = m.get("exposure_human")
            item["iso"] = m.get("iso")
            item["tilt"] = o.get("tilt_degrees")
            item["capture_path"] = g.get("capture_path") or d.get("capture_path")
            item["settings"] = g
        out.append(item)
    return out


def pair_url(st: State) -> str:
    """The callback the phone reports back to. Plain HTTP, called by the app, not a browser."""
    return f"http://{lan_address()}:{st.port}/p/{st.nonce}"


def pair_qr(st: State) -> str:
    """
    What the QR code holds.

    A custom scheme, not an http URL. Vanadium enforces HTTPS first and refuses to load a
    plain http address, so a browser cannot carry the pairing. This scheme opens DeskCam
    itself, which also skips the browser entirely.
    """
    q = {"cb": pair_url(st)}
    if st.token:
        q["token"] = st.token
    return "deskcam://pair?" + urllib.parse.urlencode(q)


def console_state(st: State) -> dict[str, Any]:
    """
    What the page needs to draw itself.

    It carries no secret. It used to return pair_qr, which holds the token, and
    pair_url, which holds the pairing nonce, from an endpoint with no password on a
    server bound to every interface. The QR is a picture at /qr.svg instead.
    """
    out: dict[str, Any] = {
        "workstation": lan_address(),
        "port": st.port,
        "code_age_seconds": st.nonce_age(),
        "phone": st.phone,
        "token_set": bool(st.token),
        "last_pair": st.last_pair,
        "last_error": st.last_error,
    }
    if st.phone:
        ip = st.phone.split("//", 1)[-1].split(":")[0]
        s = probe_phone(ip, st.token, timeout=2)
        out["online"] = s is not None
        if s:
            out["settings"] = s.get("settings", {})
            out["measured"] = s.get("measured", {})
            out["orientation"] = s.get("orientation", {})
            out["pipeline"] = s.get("pipeline", {})
            out["sensor"] = s.get("sensor", {})
            out["state"] = s.get("state")
    else:
        out["online"] = False
    return out


# ------------------------------------------------------------------- pages


def phone_page(title: str, body: str, ok: bool) -> str:
    tick = "&#10003;" if ok else "&#33;"
    colour = "#3fb950" if ok else "#d29922"
    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>DeskCam</title>
<style>
 body{{margin:0;font:16px/1.5 system-ui,sans-serif;background:#0d1117;color:#e6edf3;
      display:flex;align-items:center;justify-content:center;min-height:100vh;padding:24px}}
 .c{{max-width:420px;text-align:center}}
 .m{{font-size:56px;color:{colour};line-height:1}}
 h1{{font-size:22px;margin:16px 0 8px}} p{{color:#8b949e;margin:0}}
</style></head><body><div class="c">
<div class="m">{tick}</div><h1>{title}</h1><p>{body}</p>
</div></body></html>"""


def page(st: State) -> str:
    return PAGE


PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DeskCam console</title>
<style>
 :root{color-scheme:dark}
 *{box-sizing:border-box}
 html,body{height:100%;margin:0;overflow:hidden}      /* the page itself never scrolls */
 body{background:#0d1117;color:#e6edf3;display:flex;flex-direction:column;
      font:14px/1.5 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
 header{flex:0 0 auto;padding:7px 14px;border-bottom:1px solid #21262d;display:flex;
        gap:12px;align-items:center;flex-wrap:wrap}
 header h1{margin:0;font-size:14px;font-weight:600}
 .dot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:6px}
 .on{background:#3fb950}.off{background:#f85149}
 .muted{color:#8b949e}

 /* Three columns filling the window. Each scrolls on its own. */
 main{flex:1 1 auto;display:grid;gap:9px;padding:9px;min-height:0;
      grid-template-columns:166px 286px minmax(0,1fr)}
 @media(max-width:1000px){main{grid-template-columns:132px 240px minmax(0,1fr)}}
 @media(max-width:760px){main{grid-template-columns:1fr;overflow:auto}}
 .col{min-height:0;display:flex;flex-direction:column;background:#161b22;
      border:1px solid #21262d;border-radius:9px;overflow:hidden}
 .col>h2{flex:0 0 auto;margin:0;padding:7px 11px;font-size:10px;text-transform:uppercase;
         letter-spacing:.07em;color:#8b949e;font-weight:600;border-bottom:1px solid #21262d}
 .scroll{flex:1 1 auto;overflow-y:auto;overflow-x:hidden;min-height:0}

 #roll{display:flex;flex-direction:column;gap:7px;padding:8px}
 .shot{background:#0d1117;border:1px solid #21262d;border-radius:6px;overflow:hidden;
       cursor:pointer;flex:0 0 auto}
 .shot img{display:block;width:100%;height:auto}
 .shot .m{padding:3px 6px;font:9px/1.3 ui-monospace,monospace;color:#8b949e}
 .shot:hover,.shot.sel{border-color:#2f81f7}

 details.grp{border-bottom:1px solid #21262d}
 details.grp>summary{cursor:pointer;padding:6px 11px;font:11px ui-monospace,monospace;
        color:#8b949e;list-style:none;display:flex;justify-content:space-between;gap:8px}
 details.grp>summary::-webkit-details-marker{display:none}
 details.grp>summary::after{content:"+";color:#484f58}
 details.grp[open]>summary::after{content:"\\2212"}
 details.grp[open]>summary{color:#e6edf3;background:#0d1117}
 details.grp .body{padding:4px 11px 9px}
 table.kv{width:100%;border-collapse:collapse;font:11px/1.45 ui-monospace,monospace}
 table.kv td{padding:1px 0;vertical-align:top;word-break:break-word}
 table.kv td:first-child{color:#8b949e;width:46%;padding-right:6px}
 .raw{width:100%;background:#0d1117;color:#8b949e;border:1px solid #21262d;border-radius:5px;
      font:10px/1.35 ui-monospace,monospace;padding:6px;height:130px;resize:vertical}

 .view{position:relative;background:#000;flex:1 1 auto;display:flex;align-items:center;
       justify-content:center;min-height:0;overflow:hidden}
 .view img{max-width:100%;max-height:100%;object-fit:contain;display:block;
           cursor:crosshair;user-select:none;-webkit-user-drag:none}
 #box{position:absolute;border:2px solid #2f81f7;background:rgba(47,129,247,.15);
      display:none;pointer-events:none}
 .hint{position:absolute;left:8px;bottom:8px;background:rgba(13,17,23,.85);
       border:1px solid #30363d;border-radius:5px;padding:2px 7px;font-size:10px;
       color:#8b949e;pointer-events:none}
 .btns{flex:0 0 auto;display:flex;flex-wrap:wrap;gap:5px;padding:8px;
       border-top:1px solid #21262d}
 button{background:#21262d;color:#e6edf3;border:1px solid #30363d;border-radius:6px;
        padding:4px 10px;font-size:11px;cursor:pointer}
 button:hover{background:#30363d}
 button.p{background:#1f6feb;border-color:#1f6feb} button.p:hover{background:#388bfd}
 code{font:11px ui-monospace,monospace;color:#79c0ff;word-break:break-all}

 dialog{background:#161b22;color:#e6edf3;border:1px solid #30363d;border-radius:10px;
        padding:0;width:min(1150px,94vw);max-height:92vh;overflow:hidden}
 dialog::backdrop{background:rgba(0,0,0,.7)}
 .dlg{display:flex;flex-direction:column;max-height:92vh;min-height:0}
 .dlgmain{display:grid;grid-template-columns:296px minmax(0,1fr);min-height:0;flex:1}
 @media(max-width:820px){.dlgmain{grid-template-columns:1fr}}
 .dlgside{overflow:auto;min-height:0;max-height:66vh;border-right:1px solid #21262d}
 .frame{background:#0d1117;padding:10px;display:flex;align-items:center;
        justify-content:center;min-height:0}
 .frame img{max-width:100%;max-height:66vh;object-fit:contain;display:block;border-radius:4px}
 .dlgbar{display:flex;gap:8px;align-items:center;padding:8px 12px;flex-wrap:wrap;
         border-top:1px solid #21262d}
 .dlgbar .info{font:11px ui-monospace,monospace;color:#8b949e;flex:1;min-width:0}
 .qr img{width:100%;max-width:170px;display:block;margin:6px auto}
</style></head><body>
<header>
  <h1>DeskCam</h1>
  <span id="hdr" class="muted"></span>
  <span id="meta" class="muted" style="margin-left:auto;font:11px ui-monospace,monospace"></span>
</header>
<main>
  <div class="col">
    <h2>Captures</h2>
    <div class="scroll"><div id="roll"></div>
      <p id="empty" class="muted" style="font-size:11px;padding:0 10px">
        None yet. <code>deskcam snap</code></p></div>
  </div>
  <div class="col">
    <h2 id="sidetitle">Live</h2>
    <div class="scroll" id="side"></div>
  </div>
  <div class="col">
    <div class="view" id="wrap">
      <img id="live" alt="live view">
      <div id="box"></div>
      <div class="hint">drag a box to frame &middot; click to centre
        &middot; shift-click resets</div>
    </div>
    <div class="btns">
      <button class="p" onclick="cam('zoom=1&cx=0.5&cy=0.5')">Full sensor</button>
      <button onclick="cam('zoomby=1.5')">In</button>
      <button onclick="cam('zoomby=0.667')">Out</button>
      <button onclick="cam('measure=1')">Measure</button>
      <button onclick="cam('measure=0')">Normal</button>
      <button onclick="rot()">Rotate</button>
      <button onclick="restream()">Restream</button>
      <button onclick="document.getElementById('pair').showModal()">Pair</button>
    </div>
  </div>
</main>

<dialog id="big"><div class="dlg">
  <div class="dlgmain">
    <div class="dlgside scroll" id="dside"></div>
    <div class="frame"><img id="bigimg" alt="capture"></div>
  </div>
  <div class="dlgbar">
    <div class="info" id="biginfo"></div>
    <button onclick="recall()">Shoot this again</button>
    <button onclick="document.getElementById('big').close()">Close</button>
  </div>
</div></dialog>

<dialog id="pair"><div style="padding:16px;text-align:center">
  <div class="qr"><img id="qr" src="/qr.svg" alt="pairing code"></div>
  <p class="muted" style="font-size:11px">Scan this with the phone. The code is in the
     picture only: it carries the access key, so the console never sends it in JSON.</p>
  <button onclick="newcode()">New code</button>
  <button onclick="document.getElementById('pair').close()">Close</button>
</div></dialog>

<script>
let S={}, streamUrl='', ROLL=[], shown=null, selName=null;
// Which groups are open. Held here so the two second refresh does not shut them.
const OPEN={Framing:true, Exposure:true};

function esc(v){return String(v).replace(/[<>&]/g,c=>({'<':'&lt;','>':'&gt;','&':'&amp;'}[c]));}

function kv(o){
  const e=Object.entries(o||{}).filter(([,v])=>v!==undefined);
  if(!e.length) return '';
  return '<table class="kv">'+e.map(([k,v])=>{
    if(v!==null&&typeof v==='object') v=JSON.stringify(v);
    return '<tr><td>'+esc(k)+'</td><td>'+esc(v===null?'null':v)+'</td></tr>';
  }).join('')+'</table>';
}
function grp(n,inner){
  if(!inner) return '';
  return '<details class="grp" data-g="'+n+'"'+(OPEN[n]?' open':'')+'><summary>'+n+
         '</summary><div class="body">'+inner+'</div></details>';
}
/* One shape for the live state and for a capture, because they carry the same blocks.
   Grouped by what a person is deciding about, not by where the value came from. */
function sections(d,extra){
  const g=d.settings||{}, m=d.measured||{}, o=d.orientation||{};
  return (extra||'')
    + grp('Framing', kv({zoom:g.zoom, cx:g.cx, cy:g.cy, rotate:g.rotate,
                         out_w:g.out_w, out_h:g.out_h}))
    + grp('Exposure', kv({mode:g.ae, exposure:m.exposure_human||g.exposure_human,
                          iso:(m.iso!=null?m.iso:g.iso), ev:g.ev, ae_lock:g.ae_lock,
                          awb:g.awb, awb_lock:g.awb_lock, measure:g.measure}))
    + grp('Focus', kv({mode:g.af,
                       dioptres:(m.focus_diopters!=null?m.focus_diopters:g.focus_diopters),
                       metres_approx:g.focus_metres, af_state:m.af_state}))
    + grp('Light', kv({torch:g.torch, ambient_lux:o.ambient_lux}))
    + grp('Orientation', kv({tilt_degrees:o.tilt_degrees, aim:o.aim,
                             pitch:o.pitch_degrees, roll:o.roll_degrees, gravity:o.gravity}))
    + grp('Pipeline', kv(d.pipeline))
    + grp('Sensor', kv(d.sensor))
    + grp('Raw', '<textarea class="raw" readonly>'+esc(JSON.stringify(d,null,1))+'</textarea>');
}
document.addEventListener('toggle', e=>{
  const el=e.target;
  if(el.classList && el.classList.contains('grp')) OPEN[el.dataset.g]=el.open;
}, true);

async function cam(q){ try{ await fetch('/api/cam?'+q); }catch(e){} refresh(); }
/* The stream carries no camera parameter. A reconnect must not change what an agent
   is about to capture, and this URL is rebuilt on every reconnect. */
function restream(){
  if(!S.phone) return;
  const u=S.phone+'/api/stream?fps=10&t='+Date.now();
  if(u!==streamUrl){ streamUrl=u; document.getElementById('live').src=u; }
}
/* Rotation is how the phone is bolted down, so it is camera state and a deliberate
   press changes it. The stream then shows it because the camera has it. */
async function rot(){
  const now=(S.settings&&S.settings.rotate)||0;
  await cam('rotate='+((now+180)%360));
  streamUrl=''; restream();
}

async function refresh(){
  try{
    S=await (await fetch('/api/state')).json();
    const dot=S.online?'<span class="dot on"></span>':'<span class="dot off"></span>';
    document.getElementById('hdr').innerHTML=dot+
      (S.phone?(S.online?S.phone:'paired, not answering'):'no phone paired');
    const g=S.settings||{}, m=S.measured||{};
    document.getElementById('meta').textContent=
      (g.zoom!==undefined?'zoom '+g.zoom+'x  '+g.cx+','+g.cy+'   ':'')+
      (m.exposure_human||'')+(m.iso?'  iso '+m.iso:'')+(g.measure?'  measure':'');
    if(S.online) restream();
    if(!selName){
      document.getElementById('sidetitle').textContent='Live';
      document.getElementById('side').innerHTML = S.online ? sections(S)
        : '<p class="muted" style="padding:10px;font-size:11px">'+
          (S.phone?'paired, not answering':'no phone paired')+'</p>';
    }
  }catch(e){}
}

async function loadRoll(){
  try{
    ROLL=(await (await fetch('/api/roll')).json()).captures;
    document.getElementById('empty').style.display=ROLL.length?'none':'block';
    document.getElementById('roll').innerHTML=ROLL.map((c,i)=>
      '<div class="shot'+(c.name===selName?' sel':'')+'" onclick="pick('+i+')">'+
      '<img loading="lazy" src="/thumb/'+encodeURIComponent(c.name)+'">'+
      '<div class="m">'+esc(c.summary||c.name)+'<br>'+esc(c.exposure||'')+'</div></div>').join('');
  }catch(e){}
}
/* First click loads the capture into the middle column. A second opens it full size. */
function pick(i){
  const c=ROLL[i];
  if(selName===c.name){ show(c.name,c); return; }
  selName=c.name;
  document.getElementById('sidetitle').textContent='Capture';
  loadSidecar(c.name,'side');
  loadRoll();
}
function clearPick(){ selName=null; refresh(); loadRoll(); }

async function loadSidecar(n,target){
  const el=document.getElementById(target);
  el.innerHTML='<p class="muted" style="padding:10px;font-size:11px">loading...</p>';
  try{
    const d=await (await fetch('/sidecar/'+encodeURIComponent(n))).json();
    if(d.error){
      el.innerHTML='<p class="muted" style="padding:10px">'+esc(d.error)+'</p>';
      return;
    }
    const head=grp('Capture', kv({image:d.image, at:d.captured_at, bytes:d.bytes}));
    el.innerHTML=(target==='side'
      ? '<div style="padding:6px 10px"><button onclick="clearPick()">Back to live</button></div>'
      : '')+sections(d,head);
  }catch(e){ el.innerHTML='<p class="muted" style="padding:10px">no sidecar</p>'; }
}

function show(n,c){
  shown=c||null;
  document.getElementById('bigimg').src='/img/'+encodeURIComponent(n);
  const b=[n];
  if(c){ if(c.summary)b.push(c.summary); if(c.exposure)b.push(c.exposure);
         if(c.iso)b.push('iso '+c.iso); if(c.tilt!=null)b.push('tilt '+c.tilt+'\\u00b0'); }
  document.getElementById('biginfo').textContent=b.join('   ');
  loadSidecar(n,'dside');
  document.getElementById('big').showModal();
}
function recall(){
  if(!shown||!shown.settings) return;
  const g=shown.settings, q=['reset=1','zoom='+g.zoom,'cx='+g.cx,'cy='+g.cy,'rotate='+g.rotate];
  if(g.measure) q.push('measure=1');
  if(g.torch) q.push('torch='+g.torch);
  if(g.focus_diopters!=null) q.push('focus='+g.focus_diopters);
  if(g.ae==='manual'){ if(g.exposure_ns)q.push('exposure='+g.exposure_ns);
                       if(g.iso)q.push('iso='+g.iso); }
  cam(q.join('&'));
  document.getElementById('big').close();
}

const live=document.getElementById('live'), box=document.getElementById('box');
let sx=0, sy=0, dragging=false;
function frac(e){
  const b=live.getBoundingClientRect();
  return [(e.clientX-b.left)/b.width,(e.clientY-b.top)/b.height];
}
live.addEventListener('mousedown', e=>{
  if(e.shiftKey){ cam('zoom=1&cx=0.5&cy=0.5'); return; }
  [sx,sy]=frac(e); dragging=true;
  const b=live.getBoundingClientRect(), w=document.getElementById('wrap').getBoundingClientRect();
  box.style.display='block';
  box.dataset.ox=b.left-w.left; box.dataset.oy=b.top-w.top;
  box.dataset.bw=b.width; box.dataset.bh=b.height;
  e.preventDefault();
});
window.addEventListener('mousemove', e=>{
  if(!dragging) return;
  const [x,y]=frac(e), ox=+box.dataset.ox, oy=+box.dataset.oy,
        bw=+box.dataset.bw, bh=+box.dataset.bh;
  box.style.left=(ox+Math.min(sx,x)*bw)+'px';
  box.style.top=(oy+Math.min(sy,y)*bh)+'px';
  box.style.width=(Math.abs(x-sx)*bw)+'px';
  box.style.height=(Math.abs(y-sy)*bh)+'px';
});
window.addEventListener('mouseup', e=>{
  if(!dragging) return;
  dragging=false; box.style.display='none';
  const [x,y]=frac(e), g=S.settings||{};
  const z=Math.max(1,g.zoom||1), w=1/z;
  const left=Math.min(Math.max((g.cx!=null?g.cx:0.5)-w/2,0),1-w);
  const top =Math.min(Math.max((g.cy!=null?g.cy:0.5)-w/2,0),1-w);
  const dx=Math.abs(x-sx), dy=Math.abs(y-sy);
  if(dx<0.02&&dy<0.02){
    cam('cx='+(left+Math.min(sx,x)*w).toFixed(4)+'&cy='+(top+Math.min(sy,y)*w).toFixed(4));
    return;
  }
  const nz=Math.min(1/(dx*w),1/(dy*w));
  cam('zoom='+Math.min(nz,20).toFixed(2)+
      '&cx='+(left+(Math.min(sx,x)+dx/2)*w).toFixed(4)+
      '&cy='+(top+(Math.min(sy,y)+dy/2)*w).toFixed(4));
});
live.addEventListener('wheel', e=>{
  e.preventDefault(); cam('zoomby='+(e.deltaY<0?1.25:0.8));
},{passive:false});

async function newcode(){
  await fetch('/api/newcode');
  document.getElementById('qr').src='/qr.svg?t='+Date.now();
  refresh();
}
for(const id of ['big','pair']){
  const d=document.getElementById(id);
  d.addEventListener('click', e=>{ if(e.target===d) d.close(); });
}
refresh(); loadRoll();
setInterval(refresh,2000);
setInterval(loadRoll,3000);
</script>
</body></html>"""


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> None:
    ap = argparse.ArgumentParser(description="DeskCam local console")
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--shots", default=os.environ.get("DESKCAM_SHOTS", os.getcwd()))
    ap.add_argument("-v", "--verbose", action="store_true", help="log every request")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    Handler.state = State(args.port, args.shots)
    # Bound to every interface on purpose: the phone has to reach this to pair, and it is
    # not on the loopback. Nothing here answers with a secret, and the only files it
    # serves are the captures in the shots directory (bandit B104).
    srv = Server(("0.0.0.0", args.port), Handler)  # nosec B104
    url = f"http://{lan_address()}:{args.port}"
    print(f"DeskCam console on {url}")
    print(f"  local:  http://127.0.0.1:{args.port}")
    print(f"  pair:   {pair_qr(Handler.state)}")
    if Handler.state.phone:
        print(f"  phone:  {Handler.state.phone}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()
