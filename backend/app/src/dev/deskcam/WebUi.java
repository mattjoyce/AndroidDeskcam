package dev.deskcam;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

    /** Read once when the service starts. The APK holds the exact HTML tested on the host. */
    public static String page(Context context) throws IOException {
        try (InputStream in = context.getAssets().open("panel.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
