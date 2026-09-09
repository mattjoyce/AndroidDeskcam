package dev.deskcam;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The browser control panel and the machine-readable API description. */
public class WebUi {

    /** Structured API doc. An agent can GET /api/help and learn the whole surface. */
    public static JSONObject help() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", "DeskCam");
        o.put("summary", "HTTP-controlled bench camera. Every endpoint is a plain GET; "
                + "any control parameter may be supplied to any endpoint and is applied before the image is taken.");

        JSONObject ep = new JSONObject();
        ep.put("GET /api/status", "Current settings, sensor limits, and the last measured exposure/ISO/focus.");
        ep.put("GET /api/cameras", "List cameras with facing, resolution, closest focus and capabilities.");
        ep.put("GET /api/help", "This document.");
        ep.put("GET /api/set", "Apply control parameters, return the resulting settings.");
        ep.put("GET /api/reset", "Restore every setting to its default.");
        ep.put("GET /api/af", "Run one autofocus sweep. Optional wait=ms (default 700).");
        ep.put("GET /api/still", "Full-resolution JPEG cropped to the ROI. Optional timeout=ms, settle=ms.");
        ep.put("GET /api/frame", "Single preview-resolution JPEG cropped to the ROI. Much faster than /api/still.");
        ep.put("GET /api/stream", "MJPEG stream (multipart/x-mixed-replace). Optional fps (default 10), n=max frames.");
        o.put("endpoints", ep);

        JSONObject p = new JSONObject();
        p.put("camera", "Camera id, from /api/cameras. 0 is the rear camera.");
        p.put("zoom", "Software zoom, 1.0 = full sensor. Crops real pixels out of the full-resolution frame.");
        p.put("zoomby", "Multiply the current zoom, e.g. zoomby=2.");
        p.put("cx, cy", "Absolute ROI centre, 0..1 across the frame. 0.5,0.5 is centred.");
        p.put("dx, dy", "Relative pan, in fractions of the current ROI width. Same visual step at any zoom.");
        p.put("af", "off | auto | macro | continuous | video | edof");
        p.put("focus", "Manual focus in diopters (1/metres), or 'auto'. Implies af=off.");
        p.put("focusm", "Manual focus by distance in metres. Implies af=off.");
        p.put("ae", "on | off. Turning it off requires exposure and iso to be meaningful.");
        p.put("exposure", "Shutter time. Accepts 1/120, 8ms, 250us, 0.5s or raw nanoseconds. Implies ae=off.");
        p.put("iso", "Sensor sensitivity. Implies ae=off.");
        p.put("ev", "Exposure compensation in steps, only meaningful while ae=on.");
        p.put("aelock", "on | off. Freeze the auto exposure at its current value.");
        p.put("awb", "auto | off | incandescent | fluorescent | warmfluorescent | daylight | cloudy | twilight | shade");
        p.put("awblock", "on | off. Freeze auto white balance, which stops colour drifting between shots.");
        p.put("torch", "0 to torch_max_level, or off | on | max. The rear LED, useful as bench illumination.");
        p.put("jpegq", "JPEG quality 1..100, default 92.");
        p.put("rotate", "0 | 90 | 180 | 270, applied to the returned pixels.");
        p.put("w, h", "Resize the output after cropping. Give one to preserve aspect ratio.");
        p.put("previewsize", "Preview/stream capture size, e.g. 1280x960. Rebuilds the capture session.");
        p.put("stillsize", "Still capture size, e.g. 4032x3024. Rebuilds the capture session.");
        p.put("reset", "reset=1 clears every setting to default before applying the rest of this request.");
        p.put("settle", "Milliseconds to wait after applying settings before capturing. Defaults to 350 when auto exposure is on.");
        o.put("parameters", p);

        JSONArray notes = new JSONArray();
        notes.put("This sensor reports croppingType=CENTER_ONLY, so the hardware cannot pan. "
                + "Zoom and pan are therefore done by cropping the full-resolution frame in software, "
                + "which keeps every pixel a real sensor pixel rather than a HAL upscale.");
        notes.put("Focus and exposure metering regions follow the ROI, so zooming onto a component "
                + "makes the camera focus and expose for that component.");
        notes.put("Photographing an OLED or LCD: fix the exposure to a whole multiple of the panel "
                + "refresh period to remove PWM banding. At 60Hz try exposure=1/60, 1/30 or 16.67ms.");
        notes.put("Prefer /api/frame while aiming and /api/still once framed. /api/still returns the "
                + "untouched camera JPEG when zoom=1 with no rotate or resize.");
        o.put("notes", notes);

        JSONArray ex = new JSONArray();
        ex.put("curl -o board.jpg 'http://HOST:8080/api/still?zoom=4&cx=0.35&cy=0.6'");
        ex.put("curl -s 'http://HOST:8080/api/set?exposure=1/60&iso=200&awb=daylight' | jq .");
        ex.put("curl -o macro.jpg 'http://HOST:8080/api/still?focusm=0.12&torch=30&zoom=6'");
        ex.put("curl -s 'http://HOST:8080/api/set?dx=0.25' | jq .settings");
        o.put("examples", ex);
        return o;
    }

    public static String page() {
        return PAGE;
    }

    private static final String PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DeskCam</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.45 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
         background:#0d1117; color:#e6edf3; }
  header { padding:10px 16px; border-bottom:1px solid #21262d; display:flex; gap:16px; align-items:baseline; }
  header h1 { margin:0; font-size:15px; font-weight:600; letter-spacing:.02em; }
  header .meta { color:#8b949e; font-size:12px; font-family:ui-monospace,monospace; }
  main { display:grid; grid-template-columns:minmax(0,1fr) 320px; gap:16px; padding:16px; align-items:start; }
  @media (max-width:900px) { main { grid-template-columns:1fr; } }
  .viewport { position:relative; background:#000; border:1px solid #21262d; border-radius:8px; overflow:hidden; }
  .viewport img { display:block; width:100%; height:auto; cursor:crosshair; }
  .hint { position:absolute; left:8px; bottom:8px; background:rgba(13,17,23,.82); border:1px solid #30363d;
          border-radius:5px; padding:4px 8px; font-size:11px; color:#8b949e; pointer-events:none; }
  .panel { background:#161b22; border:1px solid #21262d; border-radius:8px; padding:14px; }
  .panel h2 { margin:0 0 10px; font-size:12px; text-transform:uppercase; letter-spacing:.07em; color:#8b949e; font-weight:600; }
  .row { display:flex; align-items:center; gap:8px; margin-bottom:9px; }
  .row label { flex:0 0 74px; color:#8b949e; font-size:12px; }
  .row input[type=range] { flex:1; min-width:0; accent-color:#2f81f7; }
  .row input[type=text], .row select { flex:1; min-width:0; background:#0d1117; color:#e6edf3;
        border:1px solid #30363d; border-radius:5px; padding:4px 7px; font:12px ui-monospace,monospace; }
  .val { flex:0 0 62px; text-align:right; font:12px ui-monospace,monospace; color:#e6edf3; }
  .btns { display:flex; flex-wrap:wrap; gap:6px; margin-top:4px; }
  button { background:#21262d; color:#e6edf3; border:1px solid #30363d; border-radius:5px;
           padding:5px 10px; font-size:12px; cursor:pointer; }
  button:hover { background:#30363d; }
  button.primary { background:#1f6feb; border-color:#1f6feb; }
  button.primary:hover { background:#388bfd; }
  .pad { display:grid; grid-template-columns:repeat(3,1fr); gap:5px; width:150px; margin:0 auto; }
  .pad button { padding:7px 0; }
  pre { margin:0; background:#0d1117; border:1px solid #21262d; border-radius:6px; padding:10px;
        font:11px/1.5 ui-monospace,monospace; color:#8b949e; max-height:260px; overflow:auto; }
  .sep { height:1px; background:#21262d; margin:12px -14px; }
</style>
</head>
<body>
<header>
  <h1>DeskCam</h1>
  <span class="meta" id="meta">connecting...</span>
</header>
<main>
  <div>
    <div class="viewport">
      <img id="view" src="/api/stream?fps=12" alt="live view">
      <div class="hint">click to centre &middot; scroll to zoom &middot; shift-click to reset</div>
    </div>
    <div class="btns" style="margin-top:10px">
      <button class="primary" onclick="still()">Save full-res still</button>
      <button onclick="api('/api/af')">Autofocus</button>
      <button onclick="api('/api/reset')">Reset all</button>
      <button onclick="restream()">Restart stream</button>
    </div>
  </div>

  <div class="panel">
    <h2>Framing</h2>
    <div class="row">
      <label>zoom</label>
      <input type="range" id="zoom" min="1" max="12" step="0.1" value="1" oninput="setv('zoom',this.value)">
      <span class="val" id="zoomv">1.0x</span>
    </div>
    <div class="pad">
      <span></span><button onclick="api('/api/set?dy=-0.2')">&uarr;</button><span></span>
      <button onclick="api('/api/set?dx=-0.2')">&larr;</button>
      <button onclick="api('/api/set?cx=0.5&amp;cy=0.5')">&middot;</button>
      <button onclick="api('/api/set?dx=0.2')">&rarr;</button>
      <span></span><button onclick="api('/api/set?dy=0.2')">&darr;</button><span></span>
    </div>

    <div class="sep"></div>
    <h2>Focus</h2>
    <div class="row">
      <label>mode</label>
      <select id="af" onchange="setv('af',this.value)">
        <option value="continuous">continuous</option>
        <option value="auto">auto</option>
        <option value="macro">macro</option>
        <option value="off">manual</option>
      </select>
    </div>
    <div class="row">
      <label>distance</label>
      <input type="range" id="focus" min="0" max="10" step="0.05" value="0" oninput="setv('focus',this.value)">
      <span class="val" id="focusv">auto</span>
    </div>

    <div class="sep"></div>
    <h2>Exposure</h2>
    <div class="row">
      <label>mode</label>
      <select id="ae" onchange="setv('ae',this.value)">
        <option value="on">auto</option>
        <option value="off">manual</option>
      </select>
    </div>
    <div class="row">
      <label>shutter</label>
      <input type="text" id="exposure" placeholder="1/60, 8ms, 250us"
             onchange="setv('exposure',this.value)">
    </div>
    <div class="row">
      <label>iso</label>
      <input type="text" id="iso" placeholder="56 - 7111" onchange="setv('iso',this.value)">
    </div>
    <div class="row">
      <label>ev</label>
      <input type="range" id="ev" min="-12" max="12" step="1" value="0" oninput="setv('ev',this.value)">
      <span class="val" id="evv">0</span>
    </div>
    <div class="row">
      <label>white bal</label>
      <select id="awb" onchange="setv('awb',this.value)">
        <option value="auto">auto</option>
        <option value="daylight">daylight</option>
        <option value="cloudy">cloudy</option>
        <option value="incandescent">incandescent</option>
        <option value="fluorescent">fluorescent</option>
        <option value="shade">shade</option>
      </select>
    </div>
    <div class="btns">
      <button onclick="setv('exposure','1/60')">1/60 anti-flicker</button>
      <button onclick="setv('exposure','1/120')">1/120</button>
    </div>

    <div class="sep"></div>
    <h2>Light</h2>
    <div class="row">
      <label>torch</label>
      <input type="range" id="torch" min="0" max="45" step="1" value="0" oninput="setv('torch',this.value)">
      <span class="val" id="torchv">off</span>
    </div>

    <div class="sep"></div>
    <h2>Status</h2>
    <pre id="status">loading...</pre>
  </div>
</main>

<script>
let busy = false;

async function api(url) {
  if (busy) return;
  busy = true;
  try {
    const r = await fetch(url);
    const j = await r.json();
    render(j);
  } catch (e) {
    document.getElementById('meta').textContent = 'error: ' + e;
  } finally {
    busy = false;
  }
}

function setv(k, v) {
  api('/api/set?' + k + '=' + encodeURIComponent(v));
}

function render(j) {
  const s = j.settings || (j.status && j.status.settings);
  if (!s) return;
  document.getElementById('status').textContent = JSON.stringify(j, null, 1);
  document.getElementById('zoom').value = s.zoom;
  document.getElementById('zoomv').textContent = Number(s.zoom).toFixed(1) + 'x';
  document.getElementById('evv').textContent = s.ev;
  document.getElementById('ev').value = s.ev;
  document.getElementById('torch').value = s.torch;
  document.getElementById('torchv').textContent = s.torch ? s.torch : 'off';
  document.getElementById('af').value = s.af;
  document.getElementById('ae').value = (s.ae === 'auto') ? 'on' : 'off';
  document.getElementById('awb').value = s.awb;
  if (s.focus_diopters === null) {
    document.getElementById('focusv').textContent = 'auto';
  } else {
    document.getElementById('focus').value = s.focus_diopters;
    document.getElementById('focusv').textContent =
      s.focus_diopters < 0.05 ? 'inf' : (1 / s.focus_diopters).toFixed(2) + 'm';
  }
  const m = j.measured || {};
  document.getElementById('meta').textContent =
    'zoom ' + Number(s.zoom).toFixed(1) + 'x  cx ' + s.cx + ' cy ' + s.cy +
    (m.exposure_human ? '  ' + m.exposure_human : '') +
    (m.iso ? '  iso ' + m.iso : '') +
    (m.af_state ? '  af:' + m.af_state : '');
}

async function refresh() {
  try {
    const r = await fetch('/api/status');
    render(await r.json());
  } catch (e) { /* keep the last good state on a hiccup */ }
}

const view = document.getElementById('view');

view.addEventListener('click', (e) => {
  const b = view.getBoundingClientRect();
  const fx = (e.clientX - b.left) / b.width;
  const fy = (e.clientY - b.top) / b.height;
  if (e.shiftKey) { api('/api/set?zoom=1&cx=0.5&cy=0.5'); return; }
  // The view already shows the crop, so a click maps into the CURRENT roi, not the frame.
  refreshThen((s) => {
    const z = Math.max(1, s.zoom);
    const w = 1 / z;
    const left = Math.min(Math.max(s.cx - w / 2, 0), 1 - w);
    const top = Math.min(Math.max(s.cy - w / 2, 0), 1 - w);
    api('/api/set?cx=' + (left + fx * w).toFixed(4) + '&cy=' + (top + fy * w).toFixed(4));
  });
});

view.addEventListener('wheel', (e) => {
  e.preventDefault();
  api('/api/set?zoomby=' + (e.deltaY < 0 ? 1.25 : 0.8));
}, { passive: false });

async function refreshThen(fn) {
  const r = await fetch('/api/status');
  const j = await r.json();
  fn(j.settings);
}

function still() {
  window.open('/api/still?t=' + Date.now(), '_blank');
}

function restream() {
  view.src = '/api/stream?fps=12&t=' + Date.now();
}

refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>
""";
}
