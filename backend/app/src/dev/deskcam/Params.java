package dev.deskcam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one list of parameters.
 *
 * The parser reads this list, /api/help is printed from this list, and nothing else
 * declares a parameter name. The contract used to live in three copies that disagreed:
 * a switch in CamSettings, a hand-written map in WebUi, and tables in the README. A
 * parameter added to one of them was missing from the others, so rule R6 (an agent can
 * learn the whole surface from /api/help) was false.
 */
public final class Params {

    private Params() { }

    /** Where a parameter takes effect. This is decision D9 in the specification. */
    public enum Kind {
        /** Camera state. Persists until something changes it again. */
        CAMERA,
        /** Presentation. Applies to the one request that names it, then is forgotten. */
        PRESENTATION,
        /** Read by the router or the transport, never by the settings. */
        ROUTER
    }

    /** Applies one parameter value onto a settings object. */
    public interface Setter {
        void set(CamSettings s, String value, CamSettings.Caps caps);
    }

    public static final class P {
        public final String[] names;
        public final Kind kind;
        public final String help;
        final Setter setter;

        P(String[] names, Kind kind, String help, Setter setter) {
            this.names = names;
            this.kind = kind;
            this.help = help;
            this.setter = setter;
        }

        /** The name or names as they appear in the help, e.g. "exposure, shutter". */
        public String label() { return String.join(", ", names); }
    }

    private static final Map<String, P> BY_NAME = new LinkedHashMap<>();
    private static final List<P> ALL = new ArrayList<>();

    private static void add(Kind kind, String help, Setter setter, String... names) {
        // Every parameter carries its own help, so /api/help cannot fall behind the
        // parser: there is nowhere for a name to be declared without one.
        if (help == null || help.isEmpty()) throw new IllegalStateException("no help for " + names[0]);
        P p = new P(names, kind, help, setter);
        ALL.add(p);
        for (String n : names) {
            if (BY_NAME.put(n, p) != null) throw new IllegalStateException("duplicate parameter " + n);
        }
    }

    private static void camera(String help, Setter setter, String... names) {
        add(Kind.CAMERA, help, setter, names);
    }

    private static void presentation(String help, Setter setter, String... names) {
        add(Kind.PRESENTATION, help, setter, names);
    }

    private static void router(String help, String... names) {
        add(Kind.ROUTER, help, null, names);
    }

    static {
        camera("Camera id, from /api/cameras. 0 is the rear camera.",
                (s, v, c) -> s.cameraId = v.trim(), "camera", "cam");

        camera("Software zoom, 1.0 = full sensor. Crops real pixels out of the "
                        + "full-resolution frame rather than asking the HAL to upscale.",
                (s, v, c) -> s.zoom = Geom.clamp(Parse.number(v), 1f, c.maxSoftZoom), "zoom");
        camera("Multiply the current zoom, e.g. zoomby=2.",
                (s, v, c) -> s.zoom = Geom.clamp(s.zoom * Parse.number(v), 1f, c.maxSoftZoom), "zoomby");
        camera("Absolute ROI centre across the frame, 0..1. 0.5,0.5 is centred.",
                (s, v, c) -> s.cx = Geom.clamp(Parse.number(v), 0f, 1f), "cx");
        camera("Absolute ROI centre down the frame, 0..1.",
                (s, v, c) -> s.cy = Geom.clamp(Parse.number(v), 0f, 1f), "cy");
        // Relative pan is expressed in fractions of the CURRENT roi width, so a given dx
        // nudges by the same visual amount at any zoom level.
        camera("Relative pan across, in fractions of the current ROI width. The same "
                        + "visual step at any zoom.",
                (s, v, c) -> s.cx = Geom.clamp(s.cx + Parse.number(v) / Math.max(1f, s.zoom), 0f, 1f), "dx");
        camera("Relative pan down, in fractions of the current ROI height.",
                (s, v, c) -> s.cy = Geom.clamp(s.cy + Parse.number(v) / Math.max(1f, s.zoom), 0f, 1f), "dy");

        camera("off | auto | macro | continuous | video | edof",
                (s, v, c) -> {
                    s.afMode = CamSettings.parseAf(v);
                    if (s.afMode >= 0) s.focusDiopters = null;
                }, "af");
        camera("Manual focus in diopters (1/metres), or 'auto'. Implies af=off.",
                (s, v, c) -> {
                    if (v.trim().equalsIgnoreCase("auto")) { s.focusDiopters = null; return; }
                    s.focusDiopters = Geom.clamp(Parse.number(v), 0f, c.minFocusDiopters);
                    s.afMode = CamSettings.AF_OFF;
                }, "focus");
        camera("Manual focus by distance in metres, friendlier for bench work. Implies af=off.",
                (s, v, c) -> {
                    float m = Parse.number(v);
                    s.focusDiopters = Geom.clamp(m <= 0 ? 0f : 1f / m, 0f, c.minFocusDiopters);
                    s.afMode = CamSettings.AF_OFF;
                }, "focusm");

        camera("on | off. Turning it off requires exposure and iso to be meaningful.",
                (s, v, c) -> s.aeAuto = Parse.bool(v), "ae");
        camera("Shutter time. Accepts 1/120, 8ms, 250us, 0.5s or raw nanoseconds. Implies ae=off.",
                (s, v, c) -> {
                    s.exposureNs = Geom.clampLong(Parse.exposureNs(v), c.minExposureNs, c.maxExposureNs);
                    s.aeAuto = false;
                }, "exposure", "shutter");
        camera("Sensor sensitivity. Implies ae=off.",
                (s, v, c) -> {
                    s.iso = Geom.clampInt(Parse.integer(v), c.minIso, c.maxIso);
                    s.aeAuto = false;
                }, "iso", "sensitivity");
        camera("Exposure compensation in steps, only meaningful while ae=on.",
                (s, v, c) -> s.evSteps = Geom.clampInt(Parse.integer(v), c.evMin, c.evMax), "ev");
        camera("on | off. Freeze the auto exposure at its current value.",
                (s, v, c) -> s.aeLock = Parse.bool(v), "aelock");

        camera("auto | off | incandescent | fluorescent | warmfluorescent | daylight | "
                        + "cloudy | twilight | shade",
                (s, v, c) -> s.awbMode = CamSettings.parseAwb(v), "awb");
        camera("on | off. Freeze auto white balance, which stops colour drifting between shots.",
                (s, v, c) -> s.awbLock = Parse.bool(v), "awblock");

        camera("0 to torch_max_level, or off | on | max. The rear LED, useful as bench light.",
                (s, v, c) -> s.torch = Geom.clampInt(Parse.torch(v, c.flashMaxLevel), 0, c.flashMaxLevel),
                "torch");

        camera("on | off. Measurement mode. Stops every stage that makes an image look "
                        + "good at the cost of a known relation between light and pixel value, "
                        + "and locks the white balance so two shots of one subject agree.",
                (s, v, c) -> {
                    s.measure = Parse.bool(v);
                    if (s.measure) s.awbLock = true;
                }, "measure");
        camera("on | off. Ask the HAL to report its lens shading map in /api/shadingmap.",
                (s, v, c) -> s.shadingMap = Parse.bool(v), "shadingmap");

        camera("0 | 90 | 180 | 270, applied to the returned pixels. This is camera state, "
                        + "because it describes how the phone is bolted down. A stream shows "
                        + "the picture rotated but cannot change the setting.",
                (s, v, c) -> s.rotate = Parse.rotation(v), "rotate");

        camera("Preview and stream capture size, e.g. 1280x960. Rebuilds the capture session. "
                        + "The device picks the nearest size it offers; /api/status reports both.",
                (s, v, c) -> {
                    int[] wh = Parse.size(v);
                    s.previewReqW = Geom.require("preview width", wh[0], 16, 8192);
                    s.previewReqH = Geom.require("preview height", wh[1], 16, 8192);
                }, "previewsize");
        camera("Still capture size, e.g. 4032x3024, or 'max'. Rebuilds the capture session.",
                (s, v, c) -> {
                    if (v.trim().equalsIgnoreCase("max")) { s.stillW = 0; s.stillH = 0; return; }
                    int[] wh = Parse.size(v);
                    s.stillW = Geom.require("still width", wh[0], 16, 16384);
                    s.stillH = Geom.require("still height", wh[1], 16, 16384);
                }, "stillsize");

        presentation("Resize the output width after cropping. Give one of w or h to keep "
                        + "the aspect ratio. Applies to this request only.",
                (s, v, c) -> s.outW = Geom.require("w", Parse.integer(v), 1, c.maxOutputEdge), "w");
        presentation("Resize the output height after cropping. Applies to this request only.",
                (s, v, c) -> s.outH = Geom.require("h", Parse.integer(v), 1, c.maxOutputEdge), "h");
        presentation("JPEG quality 1..100, default 92. Applies to this request only.",
                (s, v, c) -> s.jpegQuality = Geom.require("jpegq", Parse.integer(v), 1, 100),
                "jpegq", "quality");

        router("reset=1 clears every setting to default before applying the rest of this request.",
                "reset");
        router("Milliseconds to wait after applying settings before capturing. Defaults to "
                + "350 while auto exposure is on, 120 otherwise. 0..5000.", "settle");
        router("Milliseconds to wait for the capture itself. 100..60000.", "timeout");
        router("How many preview frames to discard before returning one, so a frame exposed "
                + "under the previous settings is never returned as the new one. 0..30.", "fresh");
        router("Number of frames: burst length, or the frame limit of a stream. 1..burst_max.", "n");
        router("Stream rate in frames per second, 0.1..30.", "fps");
        router("format=raw makes the CLI take a burst of DNG frames one at a time.", "format");
        router("Milliseconds to wait after an autofocus sweep, 0..5000.", "wait");
        router("Port for /api/nettest, 1..65535.", "port");
        router("sharpness=1 makes /api/status convert one fresh preview frame first, so "
                + "the sharpness it reports describes now rather than the last frame "
                + "anything asked for. Costs one frame, and takes fresh and timeout. "
                + "Card 9.", "sharpness");
        router("/api/focussweep: the focus of the first frame, in diopters. Defaults to 0, "
                + "which is as far away as the lens goes.", "from");
        router("/api/focussweep: the focus of the last frame, in diopters. Defaults to the "
                + "closest the lens focuses, from min_focus_diopters in limits.", "to");
        router("/api/focussweep: how many frames, spread equally in diopters. 2..burst_max.",
                "steps");
        router("A cache buster. Ignored.", "t", "_");
    }

    public static P get(String name) {
        return BY_NAME.get(name.trim().toLowerCase(Locale.US));
    }

    public static List<P> all() { return Collections.unmodifiableList(ALL); }

    /**
     * Applies one parameter, or reports that nothing knows the name.
     *
     * @return null when it applied, otherwise the reason it did not.
     */
    public static String apply(CamSettings s, String name, String value, CamSettings.Caps caps) {
        P p = get(name);
        if (p == null) return "unknown parameter '" + name + "'";
        if (p.setter == null) return null;              // the router reads this one
        try {
            p.setter.set(s, value, caps);
            return null;
        } catch (IllegalArgumentException e) {   // NumberFormatException included
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            return "bad value for '" + name + "': '" + value + "' (" + why + ")";
        }
    }
}
