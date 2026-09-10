package dev.deskcam;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The browser control panel and the machine-readable API description. */
public class WebUi {

    /**
     * Structured API doc. An agent can GET /api/help and learn the whole surface.
     *
     * The parameter list is printed from Params, which is the same list the parser reads,
     * so the two cannot drift apart. There used to be a hand-written copy here that had
     * fallen five endpoints and several parameters behind the code (rule R6).
     */
    public static JSONObject help() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", "DeskCam");
        o.put("summary", "HTTP-controlled bench camera. Every endpoint is a plain GET. "
                + "Any control parameter may be supplied to any endpoint and is applied "
                + "before the image is taken, except on /api/stream, which is a view and "
                + "refuses parameters that would change the camera.");

        JSONObject ep = new JSONObject();
        ep.put("GET /api/status", "Current settings, sensor limits, and the last measured "
                + "exposure/ISO/focus of the PREVIEW. A still carries its own values in its "
                + "sidecar and its EXIF.");
        ep.put("GET /api/cameras", "List cameras with facing, resolution, closest focus and capabilities.");
        ep.put("GET /api/help", "This document.");
        ep.put("GET /api/set", "Apply control parameters, return the resulting state.");
        ep.put("GET /api/reset", "Restore every setting to its default.");
        ep.put("GET /api/af", "Run one autofocus sweep. Optional wait=ms (default 700).");
        ep.put("GET /api/still", "Full-resolution JPEG cropped to the ROI. The record of the "
                + "frame comes back in the X-DeskCam-Provenance header and in the EXIF "
                + "UserComment. Optional timeout=ms, settle=ms.");
        ep.put("GET /api/raw", "Full-sensor RAW frame as a DNG. The software ROI is NOT applied, "
                + "because a DNG carries the whole sensor array; the framing is reported in the "
                + "X-DeskCam-ROI response header instead. Use this for measurement work, since the "
                + "JPEG pipeline is not photometrically linear.");
        ep.put("GET /api/frame", "Single preview-resolution JPEG cropped to the ROI. Much faster than /api/still.");
        ep.put("GET /api/burst", "n frames with one set of settings, as a tar of JPEGs. Answers "
                + "206 rather than 200 when it produced fewer frames than were asked for. The "
                + "largest n this device can hold is limits.burst_max.");
        ep.put("GET /api/focussweep", "steps stills as the lens walks from=diopters to "
                + "to=diopters, spread equally in diopters, as a tar. Equal in diopters is "
                + "equal in depth of field; equal in millimetres is not. The archive holds "
                + "walk.json, which records the lens position asked for and reached at every "
                + "frame. For focus stacking. The largest steps this device can hold is "
                + "limits.burst_max.");
        ep.put("GET /api/focushunt", "Walks the lens between from= and to= diopters, "
                + "reads the sharpness of a frame at every position, and leaves the lens at "
                + "the peak. Answers the chosen position, its sharpness, and the whole "
                + "curve. No frame crosses the network. A coarse pass over the range then a "
                + "fine one around its best: coarse=9 and fine=5 by default. It refuses "
                + "rather than choosing when the curve is flat, which means nothing came "
                + "into focus anywhere in the range, or when the peak is at an end of the "
                + "range, which means the peak is outside it; both answer ok:false with the "
                + "reason and the curve, and put the focus back. Fix exposure= and iso= "
                + "first: with ae=auto the exposure moves between readings and the hunt "
                + "climbs the exposure loop instead of the lens.");
        ep.put("GET /api/walk", "One still at each of the values given, as a tar: "
                + "vary=NAME&values=A,B,C. Only camera state can be walked. This endpoint "
                + "knows no step rule and invents no values, which is why the two axes "
                + "whose step rule is knowledge have their own endpoints: /api/focussweep "
                + "steps in diopters and /api/bracket in whole PWM periods. Useful for "
                + "torch levels, ISO, and for dark and flat frames. Not for zoom: zoom is "
                + "a crop of the sensor, so walking it gains no resolution. The largest "
                + "number of values this device can hold is limits.burst_max.");
        ep.put("GET /api/bracket", "stops stills at doubling exposures from base, as a tar. "
                + "Powers of two from one period, so every frame is one stop from the next "
                + "AND a whole number of base periods: set base to one period of a lit "
                + "panel's PWM, or its frames read different parts of the duty cycle. The "
                + "ISO is not touched. Merge on the measured exposure of each frame and "
                + "never on the nominal stop; walk.json carries both.");
        ep.put("GET /api/stream", "MJPEG stream (multipart/x-mixed-replace). Takes presentation "
                + "parameters only: fps, n, w, h, jpegq. A parameter that would change the camera "
                + "is refused, so one viewer cannot alter what another client captures.");
        ep.put("POST /api/script", "Runs a tape of verbs, one per line, as one operation. "
                + "The body is the tape as plain text; the answer is a multipart/mixed "
                + "stream of one JSON event per step, each capture's pixels following its "
                + "own event as the next part. The phone stores nothing. A script holds the "
                + "camera for its duration: a second script, or any request that would "
                + "change the camera, is refused with 409 while one runs. A step that fails "
                + "ends the script, the camera goes back to where the tape found it, and "
                + "the last event says what failed and what it was put back to. The verbs "
                + "are in 'script' below.");
        ep.put("GET /api/orientation", "Gravity, tilt and ambient light from the phone sensors.");
        ep.put("GET /api/shadingmap", "The lens shading map of a frame taken with the map on.");
        ep.put("GET /api/nettest", "Diagnostic. Opens a TCP connection back to the address the "
                + "request came from, to prove the app has outbound network access.");
        o.put("endpoints", ep);

        // Printed from the same table the parser reads, for the same reason the parameters
        // are: a list written twice is a list that disagrees with itself.
        JSONObject script = new JSONObject();
        script.put("verbs", new JSONArray(Tape.verbs()));
        script.put("syntax", "One verb per line, then name=value words using the same "
                + "parameter names every endpoint takes. A line starting with '#' is a "
                + "comment. WAIT takes one bare number of milliseconds and is the only verb "
                + "that is not an endpoint; every capture verb has settle= for its own "
                + "waiting, so WAIT is for waiting on something that is not a capture.");
        script.put("not_a_language", "There is no branching, no variable, no label and no "
                + "arithmetic, and there will not be. The caller is the intelligence and "
                + "the tape is the execution record. A tape with no ranges and no step "
                + "rules cannot make a wrong step as easy to write as a right one: "
                + "BRACKET base=1/240 stops=4 leaves that knowledge in /api/bracket.");
        script.put("max_steps", Tape.MAX_STEPS);
        script.put("example", "# inspect the connector area\nSET zoom=4 cx=0.3 cy=0.7 "
                + "torch=30\nWAIT 500\nAF\nSNAP\nSET torch=0\nSNAP");
        o.put("script", script);

        // One list, three groups, printed from the same declarations the parser uses.
        JSONObject camera = new JSONObject();
        JSONObject presentation = new JSONObject();
        JSONObject router = new JSONObject();
        for (Params.P p : Params.all()) {
            switch (p.kind) {
                case CAMERA: camera.put(p.label(), p.help); break;
                case PRESENTATION: presentation.put(p.label(), p.help); break;
                default: router.put(p.label(), p.help); break;
            }
        }
        JSONObject params = new JSONObject();
        params.put("camera_state", camera);
        params.put("presentation", presentation);
        params.put("router", router);
        o.put("parameters", params);
        // Kept flat as well, because a client that only wants to know whether a name is
        // valid should not have to know which group it is in.
        JSONObject flat = new JSONObject();
        for (Params.P p : Params.all()) {
            for (String n : p.names) flat.put(n, p.help);
        }
        o.put("parameter_names", flat);

        JSONArray rules = new JSONArray();
        rules.put("Camera state persists until something changes it: zoom, cx, cy, focus, "
                + "exposure, iso, torch, awb, measure, rotate, camera, previewsize, stillsize.");
        rules.put("Presentation applies to the one request that names it and is then "
                + "forgotten: w, h, jpegq. This is decision D9.");
        rules.put("An unknown parameter, or a value that cannot be read, is an HTTP 400 on "
                + "every endpoint. Nothing falls back to a default in silence.");
        o.put("state_rules", rules);

        JSONArray notes = new JSONArray();
        notes.put("This sensor reports croppingType=CENTER_ONLY, so the hardware cannot pan. "
                + "Zoom and pan are therefore done by cropping the full-resolution frame in software, "
                + "which keeps every pixel a real sensor pixel rather than a HAL upscale.");
        notes.put("Focus and exposure metering regions follow the ROI, so zooming onto a component "
                + "makes the camera focus and expose for that component.");
        notes.put("Photographing an OLED or LCD: fix the exposure to a whole multiple of the panel "
                + "refresh period to remove PWM banding. At 60Hz try exposure=1/60, 1/30 or 16.67ms.");
        notes.put("Prefer /api/frame while aiming and /api/still once framed. A still at a zoom of "
                + CamSettings.ZOOM_PRISTINE_LIMIT + " or less, with no rotate and no resize, is the "
                + "untouched camera JPEG; above that limit it is decoded and encoded again. Each "
                + "capture records which of the two it was in settings.capture_path, because a "
                + "comparison across the limit measures the pipeline and not the subject.");
        o.put("notes", notes);

        JSONArray ex = new JSONArray();
        ex.put("curl -o board.jpg 'http://HOST:8080/api/still?zoom=4&cx=0.35&cy=0.6'");
        ex.put("curl -s 'http://HOST:8080/api/set?exposure=1/60&iso=200&awb=daylight' | jq .");
        ex.put("curl -o macro.jpg 'http://HOST:8080/api/still?focusm=0.12&torch=30&zoom=6'");
        ex.put("curl -s 'http://HOST:8080/api/set?dx=0.25' | jq .settings");
        ex.put("curl -o frame.dng 'http://HOST:8080/api/raw?exposure=1/120&iso=56'");
        ex.put("curl -D- -o burst.tar 'http://HOST:8080/api/burst?n=8&measure=1'");
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
