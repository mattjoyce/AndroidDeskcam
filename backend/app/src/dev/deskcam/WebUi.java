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
                + "sidecar and its EXIF. The 'device' block is how hot the phone is and how "
                + "much charge it has left: 'thermal' is the platform's own level, which is "
                + "what /api/stream slows down for, and 'battery_celsius' is the only real "
                + "thermometer an app may read and is not the same thing.");
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
                + "is refused, so one viewer cannot alter what another client captures. Each part "
                + "carries X-DeskCam-Fps and X-DeskCam-Thermal, and X-DeskCam-Shedding once the "
                + "phone is hot enough that the rate has been cut below what was asked for: a "
                + "stream is the continuous load on the device, so it is the thing that gives "
                + "way. Captures are never slowed.");
        ep.put("POST /api/script", "Runs a tape of verbs, one per line, as one operation. "
                + "The body is the tape as plain text; the answer is a multipart/mixed "
                + "stream of one JSON event per step, each capture's pixels following its "
                + "own event as the next part. The phone stores nothing. A script holds the "
                + "camera for its duration: a second script, or any request that would "
                + "change the camera, is refused with 409 while one runs. A step that fails "
                + "ends the script, the camera goes back to where the tape found it, and "
                + "the last event says what failed and what it was put back to. The verbs "
                + "are in 'script' below.");
        ep.put("GET /api/marks", "What somebody is pointing at. GET lists them; "
                + "mark=cx,cy or mark=cx,cy,w,h with label= adds one; unmark=ID or "
                + "unmark=all removes them. The coordinates are the ones you see, the same "
                + "as cx and focusbox, but a mark is STORED on the sensor and mapped back "
                + "through rotate every time it is read, so it stays on the part it names "
                + "when the phone is remounted. Each mark says whether it is in_crop, which "
                + "is how you know the person can see the one you just made. Marks do not "
                + "survive a restart, and this endpoint answers while a script holds the "
                + "camera, because a mark changes nothing about the camera.");
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
  /* A rule that sets display beats the browser's own [hidden] rule. Without this the paused
     overlay below was drawn from the moment the page loaded, dimmed the live view, and took
     every click and wheel meant for the picture. */
  [hidden] { display:none !important; }
  /* The window is the frame: the page does not scroll, the picture shrinks to fit, and the
     controls scroll on their own. A narrow screen stacks the two and scrolls instead. */
  html, body { height:100%; }
  body { margin:0; font:14px/1.45 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
         background:#0d1117; color:#e6edf3; display:flex; flex-direction:column; }
  header { flex:0 0 auto; padding:10px 16px; border-bottom:1px solid #21262d; display:flex; gap:16px; align-items:baseline; }
  header h1 { margin:0; font-size:15px; font-weight:600; letter-spacing:.02em; }
  header .meta { color:#8b949e; font-size:12px; font-family:ui-monospace,monospace; }
  /* What the deck is doing, always, in one place. The worst true thing wins. */
  header .deck { display:flex; align-items:center; gap:7px; font-size:12px; color:#e6edf3; }
  .dot { width:9px; height:9px; border-radius:50%; background:#8b949e; flex:0 0 auto; }
  .dot.live { background:#3fb950; }
  .dot.work { background:#d2a8ff; animation:pulse 1.1s ease-in-out infinite; }
  .dot.rest { background:#8b949e; }
  .dot.warm { background:#d29922; }
  .dot.bad  { background:#f85149; }
  @keyframes pulse { 0%,100% { opacity:1; } 50% { opacity:.35; } }
  main { flex:1 1 auto; min-height:0; display:grid; grid-template-columns:minmax(0,1fr) 320px;
         grid-template-rows:minmax(0,1fr); gap:16px; padding:16px; }
  .left { display:flex; flex-direction:column; min-height:0; }
  .viewport { position:relative; flex:1 1 auto; min-height:0; display:flex; align-items:center;
              justify-content:center; background:#000; border:1px solid #21262d; border-radius:8px; overflow:hidden; }
  .asleep { position:absolute; inset:0; display:flex; align-items:center; justify-content:center;
            background:rgba(1,4,9,0.82); color:#8b949e; font-size:13px; letter-spacing:0.02em; }
  /* The picture fills the space it is given. The stream's frames are smaller than the
     viewport, and max-width on its own only ever shrinks, so the live view sat at its
     natural size in the middle of a large black box. object-fit keeps it undistorted, and
     the gesture maths below works out where the picture actually landed inside the
     element. */
  .viewport img { display:block; width:100%; height:100%; object-fit:contain;
                  cursor:crosshair; user-select:none; -webkit-user-drag:none;
                  touch-action:none; }
  .viewport img.grab { cursor:grabbing; }
  /* A paused view has no src, and the browser draws the alt text through the overlay. */
  .viewport img:not([src]) { visibility:hidden; }
  @media (max-width:900px) {
    html, body { height:auto; }
    main { grid-template-columns:1fr; grid-template-rows:none; }
    .viewport { flex:none; min-height:12rem; }
    .viewport img { width:100%; height:auto; max-height:none; }
  }
  .hint { position:absolute; left:8px; bottom:8px; background:rgba(13,17,23,.82); border:1px solid #30363d;
          border-radius:5px; padding:4px 8px; font-size:11px; color:#8b949e; pointer-events:none; }
  /* Where the camera focused, and how far away it decided that was. A tap puts it where
     you tapped; the ring shrinks to a marker once it has an answer. Card 69. */
  .ring { position:absolute; width:66px; height:66px; margin:-33px 0 0 -33px; border-radius:50%;
          border:2px solid rgba(230,237,243,.92); pointer-events:none;
          transition:width .22s, height .22s, margin .22s, border-color .22s; }
  .ring.hunting { animation:ringpulse 900ms ease-in-out infinite; }
  .ring.ok { border-color:#3fb950; }
  .ring.bad { border-color:#f85149; }
  .ring.held { width:26px; height:26px; margin:-13px 0 0 -13px; border-color:#3fb950; opacity:.9; }
  .ring span { position:absolute; left:50%; top:100%; transform:translateX(-50%); margin-top:6px;
               white-space:nowrap; background:rgba(13,17,23,.85); border:1px solid #30363d;
               border-radius:4px; padding:2px 6px; font:11px ui-monospace,monospace; color:#e6edf3; }
  @keyframes ringpulse { 0%,100% { transform:scale(1); opacity:1; } 50% { transform:scale(.86); opacity:.72; } }
  .dragbox { position:absolute; border:2px solid #2f81f7; background:rgba(47,129,247,.15);
             border-radius:2px; pointer-events:none; }
  /* What somebody is pointing at. The phone keeps a mark where it sits on the sensor, so
     these stay on their part through any zoom, pan or remount. Card 71. */
  .marks { position:absolute; inset:0; pointer-events:none; }
  .mk { position:absolute; border:2px dashed #d2a8ff; border-radius:3px;
        background:rgba(210,168,255,.07); }
  .mkpt { position:absolute; width:16px; height:16px; margin:-8px 0 0 -8px; border-radius:50%;
          border:2px solid #d2a8ff; background:rgba(210,168,255,.18); }
  .mk.mine { border-style:solid; border-color:#2f81f7; background:rgba(47,129,247,.10); }
  .mkpt.mine { border-color:#2f81f7; background:rgba(47,129,247,.18); }
  .mklbl { position:absolute; left:-2px; bottom:100%; margin-bottom:3px; white-space:nowrap;
           background:rgba(13,17,23,.88); border:1px solid #30363d; border-radius:4px;
           padding:2px 6px; font:11px ui-monospace,monospace; color:#d2a8ff; }
  .mine .mklbl { color:#79c0ff; }
  .mkpt .mklbl { left:18px; bottom:auto; top:-5px; margin:0; }
  .offview { position:absolute; right:8px; top:8px; display:flex; gap:6px; align-items:center;
             background:rgba(13,17,23,.88); border:1px solid #30363d; border-radius:5px;
             padding:3px 5px 3px 8px; font:11px ui-monospace,monospace; color:#8b949e; }
  .mrow { display:flex; gap:7px; align-items:center; width:100%; margin-bottom:5px;
          background:#0d1117; border:1px solid #21262d; border-radius:5px; padding:4px 7px;
          font-size:12px; color:#e6edf3; cursor:pointer; }
  .mrow:hover { background:#161b22; border-color:#30363d; }
  .mrow .who { flex:0 0 auto; font:10px ui-monospace,monospace; border:1px solid #30363d;
               border-radius:3px; padding:1px 4px; color:#d2a8ff; }
  .mrow .who.mine { color:#79c0ff; }
  .mrow .txt { flex:1 1 auto; min-width:0; overflow:hidden; text-overflow:ellipsis;
               white-space:nowrap; }
  .mrow .where { flex:0 0 auto; font:10px ui-monospace,monospace; color:#8b949e; }
  .none { font-size:11px; line-height:1.45; color:#8b949e; margin:0 0 8px; }
  /* The roll. Every request you make appears here the moment it leaves, and the same line
     is completed when the answer lands, so "sent" and "received" are two different things
     you can see. The two second polls are deliberately not logged: a roll that fills with
     chatter tells you nothing about what you just did. */
  .logbar { flex:0 0 auto; height:15mm; display:flex; border-top:1px solid #21262d;
            background:#0d1117; }
  .log { flex:1 1 auto; overflow-y:auto; padding:3px 10px;
         font:11px/1.5 ui-monospace,monospace; }
  .logbar button { flex:0 0 auto; align-self:flex-start; margin:4px 6px; padding:4px 6px; }
  /* A press waits on the phone, so it says so while it waits. */
  .spin { display:inline-block; width:10px; height:10px; vertical-align:-1px;
          margin-right:6px; border:2px solid #30363d; border-top-color:#d2a8ff;
          border-radius:50%; animation:spin 700ms linear infinite; }
  @keyframes spin { to { transform:rotate(360deg); } }
  .working { position:absolute; left:50%; bottom:12px; transform:translateX(-50%);
             display:flex; align-items:center; background:rgba(13,17,23,.94);
             border:1px solid #30363d; border-radius:6px; padding:5px 10px;
             font-size:12px; color:#e6edf3; pointer-events:none; }
  .le { display:flex; gap:8px; white-space:nowrap; }
  .le .t { flex:0 0 auto; color:#6e7681; }
  .le .w { flex:0 0 auto; color:#e6edf3; }
  .le .r { flex:1 1 auto; min-width:0; overflow:hidden; text-overflow:ellipsis; color:#8b949e; }
  .le.pending .r { color:#d2a8ff; }
  .le.ok .r { color:#3fb950; }
  .le.bad .r { color:#f85149; }
  .msg { position:absolute; left:8px; right:8px; top:8px; background:rgba(13,17,23,.94);
         border:1px solid #30363d; border-left:3px solid #d2a8ff; border-radius:6px;
         padding:7px 10px; font-size:12px; color:#e6edf3; pointer-events:none; }
  .msg.bad { border-left-color:#f85149; }
  .panel { background:#161b22; border:1px solid #21262d; border-radius:8px; padding:14px;
           overflow-y:auto; min-height:0; }
  .panel h2 { margin:0 0 10px; font-size:12px; text-transform:uppercase; letter-spacing:.07em; color:#8b949e; font-weight:600; }
  .row { display:flex; align-items:center; gap:8px; margin-bottom:9px; }
  .row label { flex:0 0 74px; color:#8b949e; font-size:12px; }
  .row input[type=range] { flex:1; min-width:0; accent-color:#2f81f7; }
  .row input[type=text], .row select { flex:1; min-width:0; background:#0d1117; color:#e6edf3;
        border:1px solid #30363d; border-radius:5px; padding:4px 7px; font:12px ui-monospace,monospace; }
  .val { flex:0 0 62px; text-align:right; font:12px ui-monospace,monospace; color:#e6edf3; }
  .btns { display:flex; flex-wrap:wrap; gap:6px; margin-top:4px; }
  /* Every button looks the same. One of them used to be blue, which says "this is the one
     you want" about a button that is no more important than Autofocus. */
  button { background:#21262d; color:#e6edf3; border:1px solid #30363d; border-radius:5px;
           padding:5px 10px; font-size:12px; cursor:pointer;
           transition:background .06s, border-color .06s, transform .06s; }
  button:hover { background:#30363d; }
  /* A press has to be unmistakable. :active alone is too brief to see on a touch screen,
     so a class is held for a moment as well. */
  button:active, button.hit { background:#3d444d; border-color:#8b949e;
                              transform:translateY(1px); }
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
  <span class="deck"><span class="dot" id="dot"></span><span id="deckstate">connecting</span></span>
  <span class="meta" id="meta">connecting...</span>
</header>
<main>
  <div class="left">
    <div class="viewport">
      <img id="view" src="/api/stream?fps=12" alt="live view">
      <div class="asleep" id="asleep" hidden>live view paused &middot; move the mouse to wake it</div>
      <div class="marks" id="marks"></div>
      <div class="offview" id="offview" hidden></div>
      <div class="ring" id="ring" hidden><span id="ringlbl"></span></div>
      <div class="dragbox" id="dragbox" hidden></div>
      <div class="msg" id="msg" hidden></div>
      <div class="working" id="working" hidden></div>
      <div class="hint">double tap to focus &middot; drag a box to frame &middot; hold then drag
        to pan &middot; scroll to zoom</div>
    </div>
    <div class="btns" style="margin-top:10px">
      <button onclick="still()">Save full-res still</button>
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
    <div class="btns">
      <button onclick="api('/api/af')">Focus on the crop</button>
      <button onclick="api('/api/set?focusbox=off')">Clear the focus box</button>
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
    <h2>Marks</h2>
    <div id="marklist"></div>
    <div class="btns">
      <button onclick="markView()">Mark this view</button>
      <button onclick="fitMarks()">Fit all</button>
      <button onclick="clearMarks()">Clear</button>
    </div>

    <div class="sep"></div>
    <h2>Status</h2>
    <pre id="status">loading...</pre>
  </div>
</main>

<div class="logbar">
  <div class="log" id="log"></div>
  <button onclick="copyLog()" title="Copy the log">
    <svg class="ico" width="14" height="14"><rect x="4.6" y="4.6" width="8" height="8" rx="1.5"></rect><path d="M10 2.4H3.1A1.6 1.6 0 0 0 1.5 4v7"></path></svg>
  </button>
</div>

<script>
let busy = false;

/* The answer's status code decides what happens. This used to read the body and nothing
   else, so a 409 from a script holding the camera (D14) parsed as JSON with no settings in
   it, render() returned early, and the click disappeared with nothing said. Card 69. */
async function api(url) {
  if (busy) {
    // Dropping it in silence is how you end up not knowing whether you pressed anything.
    roll(url).done(0, 0, 'not sent, the last request has not answered yet');
    return null;
  }
  busy = true;
  const entry = roll(url);
  deck.busy = verbOf(url);
  paintDeck();
  working(true, verbOf(url));
  const t0 = Date.now();
  try {
    const r = await fetch(url);
    const j = await r.json();
    entry.done(r.status, Date.now() - t0, r.ok ? '' : (j.error || ''));
    if (!r.ok) { complain(r.status, j); return null; }
    msg.hidden = true;
    render(j);
    return j;
  } catch (e) {
    entry.done(0, Date.now() - t0, String(e));
    document.getElementById('meta').textContent = 'error: ' + e;
    return null;
  } finally {
    working(false);
    deck.busy = '';
    paintDeck();
    busy = false;
  }
}

/* A focus is 759 ms on this phone and almost all of it is the deliberate settle wait, so
   something has to say that the wait is the point and not a stall. It appears after 150 ms,
   which is long enough that the quick things never flicker it. */
var workTimer = 0, workDepth = 0;

function working(on, label) {
  const el = document.getElementById('working');
  if (!el) return;
  if (on) {
    workDepth++;
    clearTimeout(workTimer);
    workTimer = setTimeout(function () {
      el.textContent = '';
      const s = document.createElement('span');
      s.className = 'spin';
      const t = document.createElement('span');
      t.textContent = label;
      el.appendChild(s);
      el.appendChild(t);
      el.hidden = false;
    }, 150);
  } else {
    workDepth = Math.max(0, workDepth - 1);
    if (!workDepth) { clearTimeout(workTimer); el.hidden = true; }
  }
}

function verbOf(url) {
  if (url.indexOf('/api/af') === 0) return 'focusing';
  if (url.indexOf('/api/marks') === 0) return 'marking';
  if (url.indexOf('/api/set') === 0) return 'setting the camera';
  return 'working';
}

/* The log is the thing you paste to somebody, so it has to leave the page as text. This
   page is plain HTTP on a LAN and a browser hands navigator.clipboard only to a secure
   context, so the old execCommand path is the one that usually does the work here. */
async function copyLog() {
  const host = document.getElementById('log');
  const lines = [];
  Array.prototype.forEach.call(host.childNodes, function (n) {
    if (n.dataset && n.dataset.line) lines.push(n.dataset.line);
  });
  // The page lives in a Java text block, and a text block turns a lone backslash-n into a
  // real newline before the browser ever sees it. Spelled with one backslash, this line
  // arrived with a line break inside the string literal, which is a syntax error, and one
  // of those kills every line of script on the page. The backslash is doubled on purpose.
  // The same trap applies to a comment: do not write the escape out in one here either.
  const text = lines.reverse().join('\\n');
  var ok = false;
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      ok = true;
    }
  } catch (e) { ok = false; }
  if (!ok) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
  }
  say(ok ? (lines.length + (lines.length === 1 ? ' line copied.' : ' lines copied.'))
         : 'The browser would not let the page copy. Select the text instead.', !ok);
}

function setv(k, v) {
  api('/api/set?' + k + '=' + encodeURIComponent(v));
}

function render(j) {
  const s = j.settings || (j.status && j.status.settings);
  if (!s) return;
  // The crop every gesture measures itself against, kept fresh from whatever answered last.
  last = {zoom: Number(s.zoom), cx: Number(s.cx), cy: Number(s.cy), box: s.focus_box};
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
  /* The mode said manual over two empty boxes, so what it was manual AT was invisible.
     Left alone while you are typing in one of them. */
  const expEl = document.getElementById('exposure');
  const isoEl = document.getElementById('iso');
  if (document.activeElement !== expEl) expEl.value = s.exposure_human || '';
  if (document.activeElement !== isoEl) {
    isoEl.value = (s.iso === null || s.iso === undefined) ? '' : s.iso;
  }
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
    (s.focus_box ? '  focusbox' : '') +
    (m.exposure_human ? '  ' + m.exposure_human : '') +
    (m.iso ? '  iso ' + m.iso : '') +
    (m.af_state ? '  af:' + m.af_state : '');
}

/* What the deck is doing, in one line, all the time.

   The phone already reports every one of these and the page used to throw them away, so an
   asleep camera, a throttled one, a camera held by somebody else's script and a phone that
   had stopped answering all looked the same: a picture that was not moving. Latency is
   fine when you can see what is being waited on. */
var deck = {answered: false, busy: '', heldUntil: 0, status: null};

function paintDeck() {
  const dot = document.getElementById('dot');
  const text = document.getElementById('deckstate');
  if (!dot || !text) return;
  const j = deck.status || {};
  const cls = function (c, words) { dot.className = 'dot ' + c; text.textContent = words; };

  // Worst first. Anything below this line is only true if nothing above it is.
  if (!deck.answered) { cls('bad', 'the phone is not answering'); return; }
  if (j.state && j.state !== 'running') {
    cls('bad', j.state + (j.last_error ? ': ' + j.last_error : ''));
    return;
  }
  if (Date.now() < deck.heldUntil) { cls('work', 'a script holds the camera'); return; }
  if (deck.busy) { cls('work', deck.busy); return; }
  if (streamDead) { cls('bad', 'the live view has stopped, trying again'); return; }

  const dev = j.device || {};
  if (dev.throttling) {
    cls('warm', 'hot: ' + dev.thermal
        + (dev.stream_slowdown > 1 ? ', the stream is at 1/' + dev.stream_slowdown + ' rate' : ''));
    return;
  }
  if (viewAsleep) { cls('rest', 'you paused the view'); return; }
  const p = j.preview || {};
  if (p.idle) {
    // Not a fault. The sensor stops being read when nothing is watching, and the next
    // thing that needs a frame wakes it and waits for the exposure to settle.
    cls('rest', 'the sensor is asleep'
        + (p.idle_seconds ? ', ' + Math.round(p.idle_seconds) + ' s' : ''));
    return;
  }
  cls('live', 'live' + (j.stream_clients ? ', ' + j.stream_clients
      + (j.stream_clients === 1 ? ' viewer' : ' viewers') : ''));
}

async function refresh() {
  try {
    const r = await fetch('/api/status');
    const j = await r.json();
    deck.answered = r.ok;
    if (r.ok) deck.status = j;
    render(j);
  } catch (e) {
    // Keep the last good settings on a hiccup, but never keep claiming it is live.
    deck.answered = false;
  }
  paintDeck();
}

/* The gestures of a bench tool. Card 69.

   double tap           focus there, and leave the framing alone
   drag a box           that box becomes the crop, then focus on it
   hold, then drag      pan
   scroll               zoom      shift-click   back to the full sensor

   A single tap does nothing on purpose. It was the focus gesture for an afternoon and it
   fired while people were lining a part up, which moves the lens under their hand.

   Every one of them names a place in the picture you can SEE. cx, cy and focusbox are in
   those same coordinates, and Geom.roi maps them back through the rotation on the phone.
   So nothing here inverts anything at rotate=180: doing that would correct it twice.

   The view already shows the crop, so a gesture maps into the CURRENT roi, not the frame. */

const view = document.getElementById('view');
const ring = document.getElementById('ring');
const ringlbl = document.getElementById('ringlbl');
const dragbox = document.getElementById('dragbox');
const msg = document.getElementById('msg');

var last = {zoom: 1, cx: 0.5, cy: 0.5, box: null};
var g = null;
var holdTimer = 0, ringTimer = 0, msgTimer = 0;
var panSending = false, panQueued = null;

function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

/* The roll along the bottom.

   A line appears the moment a request leaves, saying so, and the same line is completed
   when the answer lands. Sent and received are different facts and the page used to show
   neither: a press that went nowhere and a press that worked looked identical. The two
   second polls for status and marks are not logged, because a roll full of chatter is a
   roll nobody reads. */
function clockNow() {
  const d = new Date();
  return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2)
       + ':' + ('0' + d.getSeconds()).slice(-2);
}

function roll(url) {
  const host = document.getElementById('log');
  const el = document.createElement('div');
  el.className = 'le pending';
  const t = document.createElement('span');
  t.className = 't';
  t.textContent = clockNow();
  const w = document.createElement('span');
  w.className = 'w';
  w.textContent = url.replace('/api/', '').replace('?', ' ').replace(/&/g, ' ');
  const r = document.createElement('span');
  r.className = 'r';
  const spin = document.createElement('span');
  spin.className = 'spin';
  r.appendChild(spin);
  r.appendChild(document.createTextNode('sending'));
  el.appendChild(t);
  el.appendChild(w);
  el.appendChild(r);
  // What the copy button copies. The spans carry no whitespace between them, so a line
  // read straight off the page comes out as one run of characters.
  el.dataset.line = t.textContent + '  ' + w.textContent + '  sending';
  // Newest at the top, so the line you are waiting on never moves off the strip.
  host.insertBefore(el, host.firstChild);
  while (host.childNodes.length > 60) host.removeChild(host.lastChild);
  return {
    done: function (status, ms, why) {
      const good = status >= 200 && status < 300;
      el.className = 'le ' + (good ? 'ok' : 'bad');
      r.textContent = (status ? status : 'no answer') + ' · ' + ms + ' ms'
        + (why ? ' · ' + String(why).slice(0, 90) : '');
      el.dataset.line = t.textContent + '  ' + w.textContent + '  ' + r.textContent;
    }
  };
}

/* A press is held visible for a moment. :active is too brief to see on a touch screen, and
   this catches every button including the ones the marks list builds. */
document.addEventListener('pointerdown', function (e) {
  const b = e.target && e.target.closest ? e.target.closest('button') : null;
  if (!b) return;
  b.classList.add('hit');
  setTimeout(function () { b.classList.remove('hit'); }, 220);
}, true);

function say(text, bad) {
  msg.textContent = text;
  msg.className = bad ? 'msg bad' : 'msg';
  msg.hidden = false;
  clearTimeout(msgTimer);
  msgTimer = setTimeout(function () { msg.hidden = true; }, 7000);
}

/* A 409 is the camera being held by a script, which is a moment and not a fault, so it is
   said in words rather than coloured like a failure. */
function complain(status, j) {
  const why = (j && j.error) ? j.error : ('HTTP ' + status);
  if (status === 409) {
    /* Nothing in /api/status says a tape is running yet (card 72), so a 409 is the only
       moment the page learns it. Holding the state for a few seconds beats forgetting it
       the instant the message is shown. */
    deck.heldUntil = Date.now() + 8000;
    paintDeck();
    say('A script holds the camera. ' + why, false);
    return;
  }
  if (status === 401) { say('Unauthorised. Open the bench link again.', true); return; }
  say(why, true);
}

function crop() {
  const z = Math.max(1, Number(last.zoom) || 1);
  const w = 1 / z;
  const cx = (last.cx === null || last.cx === undefined) ? 0.5 : Number(last.cx);
  const cy = (last.cy === null || last.cy === undefined) ? 0.5 : Number(last.cy);
  return {w: w, left: clamp(cx - w / 2, 0, 1 - w), top: clamp(cy - w / 2, 0, 1 - w)};
}

/* Where the picture actually is.

   The img element fills its box, and object-fit puts the frame inside it letterboxed, so
   the element's rectangle is not the picture's rectangle. Everything here works in the
   picture's: a click at the left edge of a wide element is not a click at the left edge of
   a 4:3 frame, and treating them as the same would send coordinates for a place the
   picture does not contain. */
function picture() {
  const b = view.getBoundingClientRect();
  const nw = view.naturalWidth || b.width || 1;
  const nh = view.naturalHeight || b.height || 1;
  const scale = Math.min(b.width / nw, b.height / nh);
  const w = nw * scale, h = nh * scale;
  return {left: b.left + (b.width - w) / 2, top: b.top + (b.height - h) / 2, w: w, h: h};
}

function frac(e) {
  const p = picture();
  return [clamp((e.clientX - p.left) / p.w, 0, 1),
          clamp((e.clientY - p.top) / p.h, 0, 1)];
}

/* The same rectangle, relative to the viewport, for placing an overlay on top of it. */
function imgBox() {
  const p = picture();
  const v = view.parentNode.getBoundingClientRect();
  return {ox: p.left - v.left, oy: p.top - v.top, w: p.w, h: p.h};
}

function showRing(fx, fy, cls, label) {
  const b = imgBox();
  ring.className = 'ring ' + cls;
  ring.style.left = (b.ox + fx * b.w) + 'px';
  ring.style.top = (b.oy + fy * b.h) + 'px';
  ringlbl.textContent = label;
  ring.hidden = false;
}

function hideRing() { ring.hidden = true; }

/* Three answers, not two.

   'inactive' and 'scanning' are not failures: the lens has no verdict yet, which is what a
   fixed focus or a sweep still running looks like. Painting those red said the camera had
   failed when it had not, and a red ring labelled "inactive" is worse than no ring. */
function ringResult(fx, fy, j) {
  const m = (j && j.measured) || {};
  const s = m.af_state || '';
  const where = m.focus_diopters
    ? m.focus_metres_approx + ' m · ' + m.focus_diopters + ' d' : '';
  const locked = (s === 'focused' || s === 'focused-locked');
  if (locked) {
    showRing(fx, fy, 'ok', where || 'focused');
  } else if (s === 'failed-locked' || s === 'unfocused') {
    showRing(fx, fy, 'bad', 'nothing to focus on here');
  } else {
    showRing(fx, fy, '', (s || 'no answer') + (where ? ' · ' + where : ''));
  }
  clearTimeout(ringTimer);
  ringTimer = setTimeout(function () {
    if (!ring.hidden && locked) ring.className = 'ring held';
  }, 1700);
}

/* Focus goes on the second tap and not the first. A single tap is too easy to make while
   you are lining something up, and moving the lens under somebody's hand is worse than
   doing nothing at all. A single tap therefore changes nothing. */
var DOUBLE_MS = 350;
var lastTap = {t: 0, x: 0, y: 0};

function tapped(fx, fy) {
  const now = Date.now();
  const near = Math.abs(fx - lastTap.x) < 0.04 && Math.abs(fy - lastTap.y) < 0.04;
  if (now - lastTap.t < DOUBLE_MS && near) {
    lastTap = {t: 0, x: 0, y: 0};
    focusAt(fx, fy);
    return;
  }
  lastTap = {t: now, x: fx, y: fy};
}

/* focusbox moves where focus is judged without moving the crop (D17), which is the whole
   reason a tap can focus on a detail while you keep the framing you wanted. */
async function focusAt(fx, fy) {
  const c = crop();
  const cx = c.left + fx * c.w, cy = c.top + fy * c.w;
  const size = (0.09 * c.w).toFixed(4);
  showRing(fx, fy, 'hunting', 'focusing');
  const j = await api('/api/af?focusbox=' + cx.toFixed(4) + ',' + cy.toFixed(4)
                      + ',' + size + ',' + size);
  if (!j) { hideRing(); return; }
  ringResult(fx, fy, j);
}

/* The framing goes first and on its own, then the focus follows it.

   Measured on the phone: /api/set is 43 ms and /api/af is 759 ms, and almost all of that
   759 is the deliberate settle wait before the answer. Sending both as one request made the
   picture sit still for three quarters of a second after a gesture that had already been
   decided. The crop now moves at once. The cost is that the two are no longer one
   operation, so another client could change the camera in between; for a person framing
   something at the bench that is worth 700 ms, and the log shows both lines.

   The focus box goes back to following the crop, because the crop is now the thing that was
   asked for. */
async function frameBox(gg) {
  const dx = Math.abs(gg.x - gg.sx), dy = Math.abs(gg.y - gg.sy);
  const z = Math.min(1 / (dx * gg.w0), 1 / (dy * gg.w0), 20);
  const cx = gg.left0 + (Math.min(gg.sx, gg.x) + dx / 2) * gg.w0;
  const cy = gg.top0 + (Math.min(gg.sy, gg.y) + dy / 2) * gg.w0;
  const framed = await api('/api/set?zoom=' + z.toFixed(2) + '&cx=' + cx.toFixed(4)
                           + '&cy=' + cy.toFixed(4) + '&focusbox=off');
  if (!framed) { hideRing(); return; }
  showRing(0.5, 0.5, 'hunting', 'focusing');
  const j = await api('/api/af');
  if (!j) { hideRing(); return; }
  ringResult(0.5, 0.5, j);
}

/* A pan sends absolute coordinates and keeps one request in flight, coalescing whatever
   arrives while it waits. dx and dy are relative moves (R8), so a dropped answer would
   compound instead of being overwritten. */
async function sendPan(cx, cy) {
  panQueued = [cx, cy];
  if (panSending) return;
  panSending = true;
  // One line for the whole drag, not one per frame of it.
  const entry = roll('/api/set?pan');
  const t0 = Date.now();
  var sends = 0, status = 0;
  while (panQueued) {
    const q = panQueued;
    panQueued = null;
    try {
      const r = await fetch('/api/set?cx=' + q[0].toFixed(4) + '&cy=' + q[1].toFixed(4));
      const j = await r.json();
      sends++;
      status = r.status;
      if (!r.ok) { complain(r.status, j); break; }
      render(j);
    } catch (e) { break; }
  }
  entry.done(status, Date.now() - t0, sends + (sends === 1 ? ' move' : ' moves'));
  panSending = false;
}

function drawBox(gg) {
  const b = imgBox();
  dragbox.style.left = (b.ox + Math.min(gg.sx, gg.x) * b.w) + 'px';
  dragbox.style.top = (b.oy + Math.min(gg.sy, gg.y) * b.h) + 'px';
  dragbox.style.width = (Math.abs(gg.x - gg.sx) * b.w) + 'px';
  dragbox.style.height = (Math.abs(gg.y - gg.sy) * b.h) + 'px';
  dragbox.hidden = false;
}

view.addEventListener('pointerdown', function (e) {
  usedTheView();
  if (e.button) return;
  if (g) return;   // a second finger does not start a second gesture
  /* No picture, no gesture. Aiming at a dead view drew a box of zero size and sent
     coordinates measured against nothing, which is worse than refusing. */
  const box = imgBox();
  if (!box.w || !box.h) {
    say('The live view is not running, so there is nothing to aim at. It is being retried.', true);
    return;
  }
  const p = frac(e);
  try { view.setPointerCapture(e.pointerId); } catch (err) { /* works without it */ }
  if (e.shiftKey) { api('/api/set?zoom=1&cx=0.5&cy=0.5&focusbox=off'); hideRing(); return; }
  const c = crop();
  g = {id: e.pointerId, sx: p[0], sy: p[1], x: p[0], y: p[1], mode: 'pending',
       w0: c.w, left0: c.left, top0: c.top,
       cx0: c.left + c.w / 2, cy0: c.top + c.w / 2};
  clearTimeout(holdTimer);
  holdTimer = setTimeout(function () {
    if (g && g.mode === 'pending') { g.mode = 'pan'; view.classList.add('grab'); }
  }, 420);
  e.preventDefault();
});

view.addEventListener('pointermove', function (e) {
  if (!g || e.pointerId !== g.id) return;
  const p = frac(e);
  g.x = p[0];
  g.y = p[1];
  if (g.mode === 'pending' && (Math.abs(p[0] - g.sx) > 0.02 || Math.abs(p[1] - g.sy) > 0.02)) {
    clearTimeout(holdTimer);
    g.mode = 'box';
  }
  if (g.mode === 'box') {
    drawBox(g);
  } else if (g.mode === 'pan') {
    sendPan(clamp(g.cx0 - (p[0] - g.sx) * g.w0, g.w0 / 2, 1 - g.w0 / 2),
            clamp(g.cy0 - (p[1] - g.sy) * g.w0, g.w0 / 2, 1 - g.w0 / 2));
  }
});

view.addEventListener('pointerup', function (e) {
  if (!g || e.pointerId !== g.id) return;
  clearTimeout(holdTimer);
  const gg = g;
  g = null;
  dragbox.hidden = true;
  view.classList.remove('grab');
  if (gg.mode === 'pan') return;
  const dx = Math.abs(gg.x - gg.sx), dy = Math.abs(gg.y - gg.sy);
  // Under 2% of the view is a tap and not a box, which is the console's rule.
  if (gg.mode === 'pending' || (dx < 0.02 && dy < 0.02)) tapped(gg.x, gg.y);
  else frameBox(gg);
});

/* A cancel is not an up. The pointer was taken away, so the gesture did not happen, and
   committing a crop or a focus here would be an instruction nobody gave. */
view.addEventListener('pointercancel', function (e) {
  if (g && e.pointerId !== g.id) return;
  clearTimeout(holdTimer);
  g = null;
  dragbox.hidden = true;
  view.classList.remove('grab');
});

view.addEventListener('wheel', function (e) {
  e.preventDefault();
  usedTheView();
  api('/api/set?zoomby=' + (e.deltaY < 0 ? 1.25 : 0.8));
}, { passive: false });

/* Marks: what a person or an agent is pointing at. Card 71.

   The phone keeps every mark where it sits on the sensor and hands it back in the
   coordinates of the picture it is showing, so all this has to do is map it into the
   current crop. A label is text that somebody else wrote, so every one of them is set with
   textContent and these rows are built with createElement. Nothing here takes markup from
   the other end of the wire. */

var MARKS = [];

async function loadMarks() {
  try {
    const r = await fetch('/api/marks');
    if (!r.ok) return;
    const j = await r.json();
    MARKS = j.marks || [];
    drawMarks();
    listMarks();
  } catch (e) { /* the next tick tries again */ }
}

function drawMarks() {
  const host = document.getElementById('marks');
  const chip = document.getElementById('offview');
  if (!host) return;
  const b = imgBox();
  const c = crop();
  host.textContent = '';
  var off = 0;
  MARKS.forEach(function (m) {
    if (!m.in_crop) { off++; return; }
    const x = (m.cx - c.left) / c.w, y = (m.cy - c.top) / c.w;
    const el = document.createElement('div');
    const mine = (m.by === 'you');
    if (m.kind === 'box') {
      const w = (m.w || 0) / c.w, h = (m.h || 0) / c.w;
      el.className = 'mk' + (mine ? ' mine' : '');
      el.style.left = (b.ox + (x - w / 2) * b.w) + 'px';
      el.style.top = (b.oy + (y - h / 2) * b.h) + 'px';
      el.style.width = (w * b.w) + 'px';
      el.style.height = (h * b.h) + 'px';
    } else {
      el.className = 'mkpt' + (mine ? ' mine' : '');
      el.style.left = (b.ox + x * b.w) + 'px';
      el.style.top = (b.oy + y * b.h) + 'px';
    }
    if (m.label) {
      const lbl = document.createElement('span');
      lbl.className = 'mklbl';
      lbl.textContent = m.label;
      el.appendChild(lbl);
    }
    host.appendChild(el);
  });
  /* A mark you cannot see is the failure case of the whole idea, so the ones outside the
     crop are counted where you are looking, with one press that brings them back. */
  chip.textContent = '';
  if (off) {
    chip.appendChild(document.createTextNode(
      off + (off === 1 ? ' mark off view ' : ' marks off view ')));
    const btn = document.createElement('button');
    btn.textContent = 'Fit';
    btn.onclick = fitMarks;
    chip.appendChild(btn);
  }
  chip.hidden = !off;
}

function listMarks() {
  const host = document.getElementById('marklist');
  if (!host) return;
  host.textContent = '';
  if (!MARKS.length) {
    const p = document.createElement('p');
    p.className = 'none';
    p.textContent = 'Nothing marked. An agent adds one with '
      + '/api/marks?mark=0.3,0.7&label=pin 1';
    host.appendChild(p);
    return;
  }
  MARKS.forEach(function (m) {
    const row = document.createElement('div');
    row.className = 'mrow';
    row.onclick = function () { goToMark(m); };
    const who = document.createElement('span');
    who.className = 'who' + (m.by === 'you' ? ' mine' : '');
    who.textContent = m.by || 'agent';
    const txt = document.createElement('span');
    txt.className = 'txt';
    txt.textContent = m.label || ('#' + m.id);
    const where = document.createElement('span');
    where.className = 'where';
    where.textContent = m.in_crop ? 'in view' : 'off view';
    row.appendChild(who);
    row.appendChild(txt);
    row.appendChild(where);
    host.appendChild(row);
  });
}

async function goToMark(m) {
  const size = (m.kind === 'box') ? Math.max(m.w, m.h) : 0.08;
  const z = clamp(0.62 / size, 1, 20);
  await api('/api/set?zoom=' + z.toFixed(2) + '&cx=' + m.cx + '&cy=' + m.cy);
  loadMarks();
}

/* The other direction: your framing is already a signal, because zoom, cx and cy are in
   /api/status, and this says the same thing out loud with a label on it. */
async function markView() {
  const c = crop();
  await api('/api/marks?by=you&label=' + encodeURIComponent('look here')
            + '&mark=' + (c.left + c.w / 2).toFixed(4) + ',' + (c.top + c.w / 2).toFixed(4)
            + ',' + c.w.toFixed(4) + ',' + c.w.toFixed(4));
  loadMarks();
}

async function fitMarks() {
  if (!MARKS.length) return;
  var x0 = 1, y0 = 1, x1 = 0, y1 = 0;
  MARKS.forEach(function (m) {
    const hw = ((m.kind === 'box') ? m.w : 0.04) / 2;
    const hh = ((m.kind === 'box') ? m.h : 0.04) / 2;
    x0 = Math.min(x0, m.cx - hw); x1 = Math.max(x1, m.cx + hw);
    y0 = Math.min(y0, m.cy - hh); y1 = Math.max(y1, m.cy + hh);
  });
  const z = clamp(Math.min(1 / (x1 - x0), 1 / (y1 - y0)) * 0.92, 1, 20);
  await api('/api/set?zoom=' + z.toFixed(2) + '&cx=' + ((x0 + x1) / 2).toFixed(4)
            + '&cy=' + ((y0 + y1) / 2).toFixed(4));
  loadMarks();
}

async function clearMarks() {
  await api('/api/marks?unmark=all');
  loadMarks();
}

function still() {
  window.open('/api/still?t=' + Date.now(), '_blank');
}

function restream() {
  streamStartedAt = Date.now();
  view.src = '/api/stream?fps=12&t=' + Date.now();
}

/* The page has to notice when its own picture has stopped.

   An <img> on an MJPEG stream whose server goes away does not announce it. Either the
   element collapses to nothing, or the browser keeps the last frame on screen and the
   picture simply stops being true. Both used to look like a working page, and everything
   downstream degraded in silence: the drag box is positioned against the image's rectangle,
   so with a collapsed image it was drawn zero pixels wide and a perfectly good gesture
   showed nothing at all. Restarting the service under an open page is enough to cause it,
   which is exactly what installing a new build does.

   The frozen case cannot be seen from the element, so the phone's own count of who is
   watching is the one that settles it. */
/* streamStartedAt begins at load and not at zero, because the first stream never goes
   through restream(): the img carries its src in the HTML. Starting at zero denied the
   opening stream the grace period and let the watchdog call it dead on its first tick. */
var streamDead = false, streamRetryAt = 0, streamStartedAt = Date.now();

function streamLooksDead() {
  if (viewAsleep || document.hidden) return false;
  if (!view.getAttribute('src')) return false;            // not asking for one
  if (Date.now() - streamStartedAt < 3000) return false;  // give it time to arrive
  if (view.naturalWidth === 0) return true;               // nothing arrived, or it collapsed
  const s = deck.status;
  return !!(s && s.stream_clients === 0);                 // the phone says nobody is watching
}

function checkStream() {
  const dead = streamLooksDead();
  if (dead !== streamDead) { streamDead = dead; paintDeck(); }
  if (!dead) return;
  if (Date.now() < streamRetryAt) return;
  streamRetryAt = Date.now() + 5000;
  restream();
}

view.addEventListener('error', function () {
  streamDead = true;
  paintDeck();
});
view.addEventListener('load', function () {
  if (streamDead) { streamDead = false; paintDeck(); }
});
setInterval(checkStream, 2000);

/* A page nobody is using must not hold the camera awake, and a page can be wide open on
   a second monitor with nobody in the room. Being visible is not the same as being
   watched, so the signal is whether anyone is doing anything.

   An <img> pointed at an MJPEG stream keeps its connection open for as long as the src is
   set, whether the tab is visible, buried behind twenty others, or on a laptop with its
   lid shut. A panel left open overnight was found holding this camera at full rate until
   morning. Cards 59 and 63. */
function stopView() {
  view.removeAttribute('src');
}

var VIEW_IDLE_MS = 30000;
var lastUse = Date.now();
var viewAsleep = false;

function paintAsleep() {
  document.getElementById('asleep').hidden = !viewAsleep;
  paintDeck();
}

function usedTheView() {
  lastUse = Date.now();
  if (viewAsleep) { viewAsleep = false; paintAsleep(); restream(); }
}

function checkViewIdle() {
  if (document.hidden || viewAsleep) return;
  if (Date.now() - lastUse > VIEW_IDLE_MS) {
    viewAsleep = true;
    stopView();
    paintAsleep();
  }
}

['pointermove', 'pointerdown', 'keydown', 'wheel', 'touchstart'].forEach(function (e) {
  document.addEventListener(e, usedTheView, { passive: true });
});
setInterval(checkViewIdle, 2000);

document.addEventListener('visibilitychange', function () {
  if (document.hidden) {
    stopView();
  } else {
    lastUse = Date.now();
    viewAsleep = false;
    paintAsleep();
    restream();
  }
});
window.addEventListener('pagehide', stopView);

refresh();
loadMarks();
setInterval(refresh, 2000);
setInterval(loadMarks, 2000);
// The overlays are placed against the picture, and the picture moves when the window does.
window.addEventListener('resize', drawMarks);
</script>
</body>
</html>
""";
}
