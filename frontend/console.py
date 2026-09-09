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
import json
import os
import secrets
import socket
import socketserver
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "deskcam"
URL_FILE = CONFIG_DIR / "url"
TOKEN_FILE = CONFIG_DIR / "token"

PHONE_PORT = 8080
NONCE_TTL = 600           # a pairing code is dead after ten minutes


class State:
    """Everything the console knows. Guarded by a lock, mutated from request threads."""

    def __init__(self, port, shots):
        self.lock = threading.Lock()
        self.port = port
        self.shots = Path(shots)
        self.nonce = None
        self.nonce_born = 0
        self.phone = self.load_url()
        self.token = self.load_token()
        self.last_pair = None
        self.last_error = None
        self.new_nonce()

    # ---------------------------------------------------------------- config

    @staticmethod
    def load_url():
        try:
            return URL_FILE.read_text().strip() or None
        except OSError:
            return None

    @staticmethod
    def load_token():
        try:
            return TOKEN_FILE.read_text().strip() or None
        except OSError:
            return None

    def save_url(self, url):
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        URL_FILE.write_text(url + "\n")
        self.phone = url

    # ----------------------------------------------------------------- nonce

    def new_nonce(self):
        with self.lock:
            self.nonce = secrets.token_urlsafe(9)
            self.nonce_born = time.time()
            return self.nonce

    def nonce_valid(self, n):
        with self.lock:
            return (n and n == self.nonce
                    and (time.time() - self.nonce_born) < NONCE_TTL)

    def nonce_age(self):
        with self.lock:
            return int(time.time() - self.nonce_born)


def lan_address():
    """The address of this machine on the route out, not a docker bridge."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))       # no packet is sent, this only picks a route
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def probe_phone(ip, token=None, timeout=4, port=PHONE_PORT):
    """Ask a candidate address for its status. This confirms it really is DeskCam."""
    url = f"http://{ip}:{port}/api/status"
    if token:
        url += f"?token={token}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        return None


class Handler(http.server.BaseHTTPRequestHandler):
    state: State = None          # set on the server instance below

    def log_message(self, fmt, *args):
        pass                     # the console is not a web server log

    # ------------------------------------------------------------- responses

    def send(self, code, ctype, body, extra=None):
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

    def send_json(self, obj, code=200):
        self.send(code, "application/json", json.dumps(obj, indent=2))

    # --------------------------------------------------------------- routing

    def do_GET(self):
        st = self.state
        path = self.path.split("?", 1)[0]

        if path == "/":
            self.send(200, "text/html; charset=utf-8", page(st))
            return

        if path == "/qr.svg":
            import io
            import segno
            qr = segno.make(pair_qr(st), error="m")
            buf = io.BytesIO()          # segno writes bytes, not str
            qr.save(buf, kind="svg", scale=6, border=2,
                    dark="#e6edf3", light=None)
            self.send(200, "image/svg+xml", buf.getvalue())
            return

        if path.startswith("/p/"):
            self.handle_pair(path[3:])
            return

        if path == "/api/state":
            self.send_json(console_state(st))
            return

        if path == "/api/newcode":
            st.new_nonce()
            self.send_json({"ok": True, "pair_url": pair_url(st), "pair_qr": pair_qr(st)})
            return

        self.send(404, "text/plain", "no such page")

    # --------------------------------------------------------------- pairing

    def handle_pair(self, nonce):
        st = self.state
        import urllib.parse as up
        q = up.parse_qs(up.urlparse(self.path).query)

        ip = self.client_address[0]
        if ip.startswith("::ffff:"):
            ip = ip[7:]
        # The app reports its own address, because only it knows which port it bound.
        # The source address stays as the fallback for a plain browser or curl.
        ip = (q.get("addr", [None])[0] or ip)
        port = int(q.get("port", [PHONE_PORT])[0] or PHONE_PORT)

        if not st.nonce_valid(nonce):
            self.send(410, "text/html; charset=utf-8", phone_page(
                "Code expired",
                "Load the console page again to get a new code.", False))
            return

        status = probe_phone(ip, st.token, port=port)
        if status is None:
            st.last_error = (f"Saw the phone at {ip}, but could not reach "
                             f"http://{ip}:{port}/api/status. Is DeskCam running?")
            self.send(200, "text/html; charset=utf-8", phone_page(
                "Almost",
                f"This workstation saw you at {ip}, but the DeskCam service did not "
                f"answer on port {port}. Open DeskCam and press Start, then scan "
                f"the code again.", False))
            return

        url = f"http://{ip}:{port}"
        st.save_url(url)
        st.last_pair = {
            "at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "url": url,
            "camera": status.get("settings", {}).get("camera"),
            "state": status.get("state"),
        }
        st.last_error = None
        st.new_nonce()            # a code is good for one pairing only
        self.send(200, "text/html; charset=utf-8", phone_page(
            "Paired", f"This phone is now the camera at {url}. "
                      f"You can close this page.", True))


def pair_url(st):
    """The callback the phone reports back to. Plain HTTP, called by the app, not a browser."""
    return f"http://{lan_address()}:{st.port}/p/{st.nonce}"


def pair_qr(st):
    """
    What the QR code holds.

    A custom scheme, not an http URL. Vanadium enforces HTTPS first and refuses to load a
    plain http address, so a browser cannot carry the pairing. This scheme opens DeskCam
    itself, which also skips the browser entirely.
    """
    import urllib.parse
    q = {"cb": pair_url(st)}
    if st.token:
        q["token"] = st.token
    return "deskcam://pair?" + urllib.parse.urlencode(q)


def console_state(st):
    out = {
        "workstation": lan_address(),
        "port": st.port,
        "pair_url": pair_url(st),
        "pair_qr": pair_qr(st),
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
    else:
        out["online"] = False
    return out


# ------------------------------------------------------------------- pages

def phone_page(title, body, ok):
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


def page(st):
    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DeskCam console</title>
<style>
 :root{color-scheme:dark}
 *{box-sizing:border-box}
 body{margin:0;background:#0d1117;color:#e6edf3;
      font:14px/1.5 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
 header{padding:12px 20px;border-bottom:1px solid #21262d;display:flex;gap:14px;align-items:baseline}
 header h1{margin:0;font-size:15px;font-weight:600}
 .dot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:6px}
 .on{background:#3fb950}.off{background:#f85149}
 main{padding:20px;display:grid;grid-template-columns:340px minmax(0,1fr);gap:20px;align-items:start}
 @media(max-width:820px){main{grid-template-columns:1fr}}
 .card{background:#161b22;border:1px solid #21262d;border-radius:10px;padding:18px}
 .card h2{margin:0 0 14px;font-size:12px;text-transform:uppercase;letter-spacing:.07em;
          color:#8b949e;font-weight:600}
 .qr{background:#0d1117;border:1px solid #30363d;border-radius:8px;padding:14px;
     display:flex;justify-content:center}
 .qr img{width:100%;max-width:260px;height:auto;display:block}
 code{font:12px ui-monospace,monospace;color:#79c0ff;word-break:break-all}
 button{background:#21262d;color:#e6edf3;border:1px solid #30363d;border-radius:6px;
        padding:6px 12px;font-size:12px;cursor:pointer;margin-top:10px}
 button:hover{background:#30363d}
 table{width:100%;border-collapse:collapse;font:12px ui-monospace,monospace}
 td{padding:3px 0;vertical-align:top} td:first-child{color:#8b949e;width:130px}
 .muted{color:#8b949e} .warn{color:#d29922}
 .steps{margin:12px 0 0;padding-left:18px;color:#8b949e;font-size:13px}
 .steps li{margin:4px 0}
</style></head><body>
<header>
  <h1>DeskCam console</h1>
  <span class="muted" id="hdr">loading...</span>
</header>
<main>
  <div class="card">
    <h2>Pair a phone</h2>
    <div class="qr"><img id="qr" src="/qr.svg" alt="pairing code"></div>
    <ol class="steps">
      <li>Open the camera on the phone and point it at this code.</li>
      <li>Tap the link that appears.</li>
      <li>The code opens DeskCam directly. It does not use the browser.</li>
    </ol>
    <p style="margin:12px 0 0"><code id="purl"></code></p>
    <button onclick="newcode()">New code</button>
  </div>

  <div class="card">
    <h2>Camera</h2>
    <table>
      <tr><td>address</td><td id="phone" class="muted">not paired</td></tr>
      <tr><td>state</td><td id="state" class="muted">-</td></tr>
      <tr><td>framing</td><td id="framing" class="muted">-</td></tr>
      <tr><td>exposure</td><td id="expo" class="muted">-</td></tr>
      <tr><td>access key</td><td id="tok" class="muted">-</td></tr>
      <tr><td>workstation</td><td id="ws" class="muted">-</td></tr>
    </table>
    <p id="err" class="warn" style="margin:12px 0 0"></p>
  </div>
</main>
<script>
async function refresh(){
  try{
    const s = await (await fetch('/api/state')).json();
    document.getElementById('purl').textContent = s.pair_qr;
    document.getElementById('ws').textContent = s.workstation + ':' + s.port;
    document.getElementById('tok').textContent = s.token_set ? 'set' : 'none';
    const dot = s.online ? '<span class="dot on"></span>' : '<span class="dot off"></span>';
    document.getElementById('hdr').innerHTML =
      dot + (s.phone ? (s.online ? 'connected to ' + s.phone : 'paired but not answering') : 'no phone paired');
    document.getElementById('phone').textContent = s.phone || 'not paired';
    document.getElementById('state').textContent = s.online ? 'running' : (s.phone ? 'not answering' : '-');
    if (s.settings){
      const g = s.settings;
      document.getElementById('framing').textContent =
        'zoom ' + g.zoom + 'x  at ' + g.cx + ',' + g.cy + (g.measure ? '  measure' : '');
      const m = s.measured || {};
      document.getElementById('expo').textContent =
        (m.exposure_human || '-') + '  iso ' + (m.iso ?? '-') + '  af ' + (g.af || '-');
    }
    document.getElementById('err').textContent = s.last_error || '';
  }catch(e){ document.getElementById('hdr').textContent = 'console error: ' + e; }
}
async function newcode(){
  await fetch('/api/newcode');
  document.getElementById('qr').src = '/qr.svg?t=' + Date.now();
  refresh();
}
refresh(); setInterval(refresh, 2000);
</script>
</body></html>"""


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    ap = argparse.ArgumentParser(description="DeskCam local console")
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--shots", default=os.environ.get("DESKCAM_SHOTS", os.getcwd()))
    args = ap.parse_args()

    Handler.state = State(args.port, args.shots)
    srv = Server(("0.0.0.0", args.port), Handler)
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
