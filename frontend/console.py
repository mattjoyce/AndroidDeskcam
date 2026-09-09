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

        if path == "/api/roll":
            self.send_json({"captures": roll(st.shots)})
            return

        if path.startswith("/thumb/"):
            self.send_image(st, path[7:], thumb=True)
            return

        if path.startswith("/sidecar/"):
            name = os.path.basename(path[9:])
            f = (st.shots / name).with_suffix(".json")
            if f.is_file():
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
                with urllib.request.urlopen(url, timeout=8) as r:
                    self.send(200, "application/json", r.read())
            except Exception as e:
                self.send_json({"ok": False, "error": str(e)}, 502)
            return

        if path == "/api/newcode":
            st.new_nonce()
            self.send_json({"ok": True, "pair_url": pair_url(st), "pair_qr": pair_qr(st)})
            return

        self.send(404, "text/plain", "no such page")

    def send_image(self, st, name, thumb):
        # Only ever serve out of the shots directory, and never a path that climbs out.
        name = os.path.basename(name)
        f = st.shots / name
        if not f.is_file():
            self.send(404, "text/plain", "no such capture")
            return
        if not thumb:
            ctype = "image/jpeg" if f.suffix.lower() in (".jpg", ".jpeg") else "application/octet-stream"
            self.send(200, ctype, f.read_bytes())
            return
        try:
            from PIL import Image
            import io
            key = (str(f), f.stat().st_mtime)
            hit = THUMBS.get(key)
            if hit is None:
                im = Image.open(f)
                im.draft("RGB", (400, 400))       # cheap partial JPEG decode
                im.thumbnail((300, 300))
                buf = io.BytesIO()
                im.convert("RGB").save(buf, "JPEG", quality=80)
                hit = buf.getvalue()
                if len(THUMBS) > 400:
                    THUMBS.clear()
                THUMBS[key] = hit
            self.send(200, "image/jpeg", hit)
        except Exception as e:
            self.send(500, "text/plain", f"thumbnail failed: {e}")

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


THUMBS = {}


def roll(shots, limit=60):
    """
    The captures of this session, newest first.

    The sidecar written by the CLI is the only index. A directory listing plus those
    files is enough, so there is no database to keep in step with the files.
    """
    out = []
    try:
        files = sorted(shots.glob("*.jpg"), key=lambda f: f.stat().st_mtime, reverse=True)
    except OSError:
        return out
    for f in files[:limit]:
        item = {"name": f.name, "mtime": f.stat().st_mtime, "bytes": f.stat().st_size}
        side = f.with_suffix(".json")
        if side.is_file():
            try:
                d = json.loads(side.read_text())
                g = d.get("settings", {})
                m = d.get("measured", {})
                o = d.get("orientation", {})
                item["when"] = d.get("captured_at")
                item["summary"] = (f"zoom {g.get('zoom')}x  {g.get('cx')},{g.get('cy')}"
                                   + ("  measure" if g.get("measure") else ""))
                item["exposure"] = m.get("exposure_human")
                item["iso"] = m.get("iso")
                item["tilt"] = o.get("tilt_degrees")
                item["settings"] = g
            except Exception:
                pass
        out.append(item)
    return out


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
 header{padding:10px 18px;border-bottom:1px solid #21262d;display:flex;gap:14px;
        align-items:center;flex-wrap:wrap}
 header h1{margin:0;font-size:15px;font-weight:600}
 .dot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:6px}
 .on{background:#3fb950}.off{background:#f85149}
 .muted{color:#8b949e}
 main{display:grid;grid-template-columns:minmax(0,1fr) 330px;gap:16px;padding:16px;align-items:start}
 @media(max-width:900px){main{grid-template-columns:1fr}}
 .card{background:#161b22;border:1px solid #21262d;border-radius:10px;padding:14px}
 .card h2{margin:0 0 10px;font-size:11px;text-transform:uppercase;letter-spacing:.07em;
          color:#8b949e;font-weight:600}
 .view{position:relative;background:#000;border:1px solid #21262d;border-radius:8px;overflow:hidden}
 .view img{display:block;width:100%;height:auto;cursor:crosshair;user-select:none;-webkit-user-drag:none}
 #box{position:absolute;border:2px solid #2f81f7;background:rgba(47,129,247,.15);display:none;pointer-events:none}
 .hint{position:absolute;left:8px;bottom:8px;background:rgba(13,17,23,.85);border:1px solid #30363d;
       border-radius:5px;padding:3px 8px;font-size:11px;color:#8b949e;pointer-events:none}
 .btns{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}
 button{background:#21262d;color:#e6edf3;border:1px solid #30363d;border-radius:6px;
        padding:5px 11px;font-size:12px;cursor:pointer}
 button:hover{background:#30363d}
 button.p{background:#1f6feb;border-color:#1f6feb} button.p:hover{background:#388bfd}
 #roll{display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:10px;
       max-height:74vh;overflow:auto}
 .shot{background:#0d1117;border:1px solid #21262d;border-radius:7px;overflow:hidden;cursor:pointer}
 .shot img{display:block;width:100%;height:auto}
 .shot .m{padding:5px 7px;font:10px/1.35 ui-monospace,monospace;color:#8b949e}
 .shot:hover{border-color:#2f81f7}
 dialog{background:#161b22;color:#e6edf3;border:1px solid #30363d;border-radius:10px;
        padding:0;width:min(1150px,94vw);max-height:90vh;overflow:hidden}
 dialog::backdrop{background:rgba(0,0,0,.65)}
 .dlg{display:flex;flex-direction:column;max-height:90vh;min-height:0}
 .dlgmain{display:grid;grid-template-columns:minmax(0,1fr) 320px;min-height:0;flex:1}
 @media(max-width:820px){.dlgmain{grid-template-columns:1fr}}
 .frame{background:#0d1117;border-right:1px solid #21262d;padding:12px;
        display:flex;align-items:center;justify-content:center;min-height:0}
 /* contain keeps the whole picture inside the frame at any aspect ratio */
 .frame img{max-width:100%;max-height:62vh;object-fit:contain;display:block;border-radius:4px}
 .side{overflow:auto;padding:12px 14px;min-height:0;max-height:62vh}
 .side h3{margin:12px 0 5px;font-size:10px;text-transform:uppercase;letter-spacing:.07em;
          color:#8b949e;font-weight:600}
 .side h3:first-child{margin-top:0}
 .side table{width:100%;border-collapse:collapse;font:11px/1.5 ui-monospace,monospace}
 .side td{padding:1px 0;vertical-align:top;word-break:break-word}
 .side td:first-child{color:#8b949e;width:44%;padding-right:8px}
 .side .raw{width:100%;background:#0d1117;color:#8b949e;border:1px solid #21262d;
            border-radius:5px;font:10px/1.4 ui-monospace,monospace;padding:7px;
            height:150px;resize:vertical}
 .dlgbar{display:flex;gap:10px;align-items:center;padding:10px 14px;flex-wrap:wrap}
 .dlgbar .info{font:11px/1.5 ui-monospace,monospace;color:#8b949e;flex:1;min-width:0}
 code{font:11px ui-monospace,monospace;color:#79c0ff;word-break:break-all}
 details summary{cursor:pointer;color:#8b949e;font-size:12px}
 .qr img{width:100%;max-width:210px;display:block;margin:10px auto}
</style></head><body>
<header>
  <h1>DeskCam</h1>
  <span id="hdr" class="muted">loading...</span>
  <span id="meta" class="muted" style="margin-left:auto;font:12px ui-monospace,monospace"></span>
</header>
<main>
  <div>
    <div class="view" id="wrap">
      <img id="live" alt="live view">
      <div id="box"></div>
      <div class="hint">drag a box to frame it &middot; click to centre &middot; shift-click to reset</div>
    </div>
    <div class="btns">
      <button class="p" onclick="cam('zoom=1&cx=0.5&cy=0.5')">Full sensor</button>
      <button onclick="cam('zoomby=1.5')">Zoom in</button>
      <button onclick="cam('zoomby=0.667')">Zoom out</button>
      <button onclick="fetch('/api/cam?_=1').then(refresh)">Refresh</button>
      <button onclick="rot()">Rotate 180</button>
      <button onclick="restream()">Restart stream</button>
    </div>
  </div>

  <div>
    <div class="card">
      <h2>Captures</h2>
      <div id="roll"></div>
      <p id="empty" class="muted" style="font-size:12px">
        Nothing yet. Take one with <code>deskcam snap</code>.</p>
    </div>
    <div class="card" style="margin-top:14px">
      <details id="pairwrap">
        <summary>Pair a phone</summary>
        <div class="qr"><img id="qr" src="/qr.svg" alt="pairing code"></div>
        <p><code id="purl"></code></p>
        <button onclick="newcode()">New code</button>
      </details>
    </div>
  </div>
</main>
<dialog id="big">
  <div class="dlg">
    <div class="dlgmain">
      <div class="frame"><img id="bigimg" alt="capture"></div>
      <div class="side" id="side"></div>
    </div>
    <div class="dlgbar">
      <div class="info" id="biginfo"></div>
      <button onclick="recall()">Shoot this again</button>
      <button onclick="document.getElementById('big').close()">Close</button>
    </div>
  </div>
</dialog>

<script>
let S = {}, rotate = 180, streamUrl = '', ROLL = [];

async function cam(q){
  try{ await fetch('/api/cam?' + q); }catch(e){}
  refresh();
}

function restream(){
  if(!S.phone) return;
  const u = S.phone + '/api/stream?fps=10&rotate=' + rotate + '&t=' + Date.now();
  if(u !== streamUrl){ streamUrl = u; document.getElementById('live').src = u; }
}
function rot(){ rotate = (rotate + 180) % 360; streamUrl=''; restream(); }

async function refresh(){
  try{
    S = await (await fetch('/api/state')).json();
    const dot = S.online ? '<span class="dot on"></span>' : '<span class="dot off"></span>';
    document.getElementById('hdr').innerHTML = dot +
      (S.phone ? (S.online ? S.phone : 'paired, not answering') : 'no phone paired');
    document.getElementById('purl').textContent = S.pair_qr || '';
    if(!S.phone) document.getElementById('pairwrap').open = true;
    const g = S.settings || {}, m = S.measured || {};
    document.getElementById('meta').textContent =
      (g.zoom!==undefined ? 'zoom '+g.zoom+'x  '+g.cx+','+g.cy+'   ' : '') +
      (m.exposure_human||'') + (m.iso? '  iso '+m.iso : '') + (g.measure? '  measure':'');
    if(S.online) restream();
  }catch(e){}
}

async function loadRoll(){
  try{
    const r = await (await fetch('/api/roll')).json();
    const el = document.getElementById('roll');
    document.getElementById('empty').style.display = r.captures.length ? 'none' : 'block';
    ROLL = r.captures;
    el.innerHTML = r.captures.map((c, i) => `
      <div class="shot" onclick="show('${c.name}', ROLL[${i}])">
        <img loading="lazy" src="/thumb/${encodeURIComponent(c.name)}">
        <div class="m">${c.summary||c.name}<br>${c.exposure||''} ${c.iso?('iso '+c.iso):''}
        ${c.tilt!==undefined&&c.tilt!==null?('<br>tilt '+c.tilt+'&deg;'):''}</div>
      </div>`).join('');
  }catch(e){}
}

let shown = null;
function show(n, c){
  shown = c || null;
  document.getElementById('bigimg').src = '/img/' + encodeURIComponent(n);
  const bits = [n];
  if(c){
    if(c.summary) bits.push(c.summary);
    if(c.exposure) bits.push(c.exposure);
    if(c.iso) bits.push('iso ' + c.iso);
    if(c.tilt !== undefined && c.tilt !== null) bits.push('tilt ' + c.tilt + '\u00b0');
    if(c.bytes) bits.push((c.bytes/1024/1024).toFixed(1) + ' MB');
  }
  document.getElementById('biginfo').textContent = bits.join('   ');
  document.getElementById('side').innerHTML = '<p class="muted">loading sidecar...</p>';
  loadSidecar(n);
  document.getElementById('big').showModal();
}

function rows(obj){
  if(!obj || !Object.keys(obj).length) return '';
  return '<table>' + Object.entries(obj).map(([k, v]) => {
    if(v !== null && typeof v === 'object') v = JSON.stringify(v);
    if(v === null) v = 'null';
    return `<tr><td>${k}</td><td>${String(v)}</td></tr>`;
  }).join('') + '</table>';
}

async function loadSidecar(n){
  const el = document.getElementById('side');
  try{
    const d = await (await fetch('/sidecar/' + encodeURIComponent(n))).json();
    if(d.error){ el.innerHTML = '<p class="muted">' + d.error + '</p>'; return; }
    const top = {image: d.image, captured_at: d.captured_at, target: d.target,
                 bytes: d.bytes};
    el.innerHTML =
      '<h3>Capture</h3>' + rows(top) +
      (d.orientation && Object.keys(d.orientation).length ? '<h3>Orientation</h3>' + rows(d.orientation) : '') +
      '<h3>Measured</h3>' + rows(d.measured) +
      '<h3>Settings</h3>' + rows(d.settings) +
      (d.pipeline && Object.keys(d.pipeline).length ? '<h3>Pipeline</h3>' + rows(d.pipeline) : '') +
      (d.sensor && Object.keys(d.sensor).length ? '<h3>Sensor</h3>' + rows(d.sensor) : '') +
      '<h3>Raw sidecar</h3><textarea class="raw" readonly>' +
        JSON.stringify(d, null, 2).replace(/</g,'&lt;') + '</textarea>';
  }catch(e){
    el.innerHTML = '<p class="muted">could not read the sidecar: ' + e + '</p>';
  }
}

// Put the camera back to the settings of the capture on screen.
function recall(){
  if(!shown || !shown.settings) return;
  const g = shown.settings, q = [];
  q.push('reset=1', 'zoom=' + g.zoom, 'cx=' + g.cx, 'cy=' + g.cy, 'rotate=' + g.rotate);
  if(g.measure) q.push('measure=1');
  if(g.torch) q.push('torch=' + g.torch);
  if(g.focus_diopters !== null && g.focus_diopters !== undefined) q.push('focus=' + g.focus_diopters);
  if(g.ae === 'manual'){
    if(g.exposure_ns) q.push('exposure=' + g.exposure_ns);
    if(g.iso) q.push('iso=' + g.iso);
  }
  cam(q.join('&'));
  document.getElementById('big').close();
}

// drag a box on the live view to frame it
const live = document.getElementById('live'), box = document.getElementById('box');
let sx=0, sy=0, dragging=false;
function frac(e){
  const b = live.getBoundingClientRect();
  return [(e.clientX-b.left)/b.width, (e.clientY-b.top)/b.height];
}
live.addEventListener('mousedown', e => {
  if(e.shiftKey){ cam('zoom=1&cx=0.5&cy=0.5'); return; }
  [sx,sy] = frac(e); dragging = true;
  box.style.display='block'; box.style.left=(sx*100)+'%'; box.style.top=(sy*100)+'%';
  box.style.width='0'; box.style.height='0'; e.preventDefault();
});
window.addEventListener('mousemove', e => {
  if(!dragging) return;
  const [x,y] = frac(e);
  box.style.left = (Math.min(sx,x)*100)+'%'; box.style.top = (Math.min(sy,y)*100)+'%';
  box.style.width = (Math.abs(x-sx)*100)+'%'; box.style.height = (Math.abs(y-sy)*100)+'%';
});
window.addEventListener('mouseup', e => {
  if(!dragging) return;
  dragging = false; box.style.display='none';
  const [x,y] = frac(e);
  const g = S.settings || {};
  const z = Math.max(1, g.zoom || 1), w = 1/z;
  const left = Math.min(Math.max((g.cx??0.5) - w/2, 0), 1-w);
  const top  = Math.min(Math.max((g.cy??0.5) - w/2, 0), 1-w);
  const dx = Math.abs(x-sx), dy = Math.abs(y-sy);
  if(dx < 0.02 && dy < 0.02){            // a click, not a drag: centre here
    cam('cx='+(left+Math.min(sx,x)*w).toFixed(4)+'&cy='+(top+Math.min(sy,y)*w).toFixed(4));
    return;
  }
  // The view already shows the crop, so the box maps inside the CURRENT region.
  // Take the looser of the two axes so the whole box stays visible.
  const nz = Math.min(1/(dx*w), 1/(dy*w));
  const cx = left + (Math.min(sx,x) + dx/2) * w;
  const cy = top  + (Math.min(sy,y) + dy/2) * w;
  cam('zoom='+Math.min(nz,20).toFixed(2)+'&cx='+cx.toFixed(4)+'&cy='+cy.toFixed(4));
});
live.addEventListener('wheel', e => {
  e.preventDefault(); cam('zoomby=' + (e.deltaY<0 ? 1.25 : 0.8));
}, {passive:false});

async function newcode(){
  await fetch('/api/newcode');
  document.getElementById('qr').src = '/qr.svg?t=' + Date.now();
  refresh();
}

const dlg = document.getElementById('big');
dlg.addEventListener('click', e => { if(e.target === dlg) dlg.close(); });

refresh(); loadRoll();
setInterval(refresh, 2000);
setInterval(loadRoll, 3000);
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
