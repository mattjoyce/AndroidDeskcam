package dev.deskcam;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/**
 * Every knob the camera exposes, plus the region-of-interest maths.
 *
 * Zoom and pan are deliberately NOT handed to the camera HAL. This sensor reports
 * android.scaler.croppingType = CENTER_ONLY, so it discards the offset of any crop
 * rectangle we give it and can only ever zoom about the centre. We therefore always
 * ask the sensor for its full active array and crop the ROI ourselves, which also
 * keeps every zoomed pixel a real sensor pixel instead of a HAL upscale.
 *
 * An instance is a value. The engine keeps one as the camera state and hands a clone to
 * every caller; a request that carries presentation parameters gets its own clone and
 * that clone dies with the request. See decision D9 in the specification.
 */
public class CamSettings implements Cloneable {

    public static final int AF_OFF = CaptureRequest.CONTROL_AF_MODE_OFF;
    public static final int AWB_OFF = CaptureRequest.CONTROL_AWB_MODE_OFF;
    public static final int AWB_AUTO = CaptureRequest.CONTROL_AWB_MODE_AUTO;

    public String cameraId = "0";

    /** Software zoom. 1.0 is the full sensor; 4.0 is a quarter-width crop. */
    public float zoom = 1.0f;
    /** Normalised centre of the ROI across the active array. 0.5,0.5 is dead centre. */
    public float cx = 0.5f;
    public float cy = 0.5f;

    public int afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
    /** Manual focus in diopters (1/metres). null means leave focus to afMode. */
    public Float focusDiopters = null;

    public boolean aeAuto = true;
    public Long exposureNs = null;
    public Integer iso = null;
    public int evSteps = 0;
    public boolean aeLock = false;

    public int awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    public boolean awbLock = false;

    /**
     * The white balance gains to apply, as red, green-even, green-odd, blue. Null leaves
     * them to the camera.
     *
     * A lock holds the gains at whatever they happened to be when it was taken, which is
     * different in every session, so two sets of captures of one subject differ in colour
     * for a reason nothing chose. Setting them is what makes a session repeatable, and
     * 1,1,1,1 is the only value that is the same everywhere: no white balance at all, so
     * the channel ratios are the sensor's own. Card 42.
     */
    public float[] awbGains = null;

    /**
     * Where focus is judged, when that is not simply what was framed.
     *
     * null means the crop, which is what one rectangle used to mean for everything. As
     * cx, cy, w, h in fractions of the seen picture. Card 60.
     */
    public float[] focusBox = null;

    /** 0 = torch off, otherwise 1..flashMaxLevel. */
    public int torch = 0;

    /** Clockwise degrees applied to the returned pixels: 0, 90, 180 or 270. */
    public int rotate = 0;

    /**
     * Measurement mode. Stops every stage that makes an image look good at the cost of a
     * known relation between light and pixel value.
     */
    public boolean measure = false;

    /**
     * Ask the HAL to report its lens shading map. This is separate from measurement mode,
     * because the map only holds real gains while shading correction is actually running.
     */
    public boolean shadingMap = false;

    // ------------------------------------------------------- presentation
    // These three describe how ONE picture comes back. They are not properties of the
    // camera, and the engine drops them from the state it keeps. When they persisted they
    // silently rescaled the next capture and disabled the untouched-JPEG path for ever.

    public int jpegQuality = 92;
    /** Optional output resize after cropping. null keeps the native crop size. */
    public Integer outW = null;
    public Integer outH = null;

    // -------------------------------------------------------- session sizes

    /** The preview size that was asked for. */
    public int previewReqW = 1280, previewReqH = 960;
    /** The preview size the device actually gave, written by the engine. */
    public int previewW = 1280, previewH = 960;
    /** The still size asked for. 0,0 means "largest the sensor offers". */
    public int stillW = 0, stillH = 0;

    @Override
    public CamSettings clone() {
        try {
            return (CamSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /** A copy with the presentation parameters back at their defaults. */
    public CamSettings withoutPresentation() {
        CamSettings c = clone();
        c.outW = null;
        c.outH = null;
        c.jpegQuality = 92;
        return c;
    }

    // ---------------------------------------------------------------- ROI

    /**
     * The crop rectangle for a frame of the given pixel dimensions. Because we crop in
     * image space rather than sensor space this works for both the full-res still and
     * the smaller preview frames, with the same normalised coordinates.
     */
    public Rect roiFor(int w, int h) {
        int[] r = Geom.roi(w, h, zoom, cx, cy, rotate);
        return new Rect(r[0], r[1], r[0] + r[2], r[1] + r[3]);
    }

    /** The focus box in the pixels of a w by h frame, or null when it follows the crop. */
    public Rect focusBoxFor(int w, int h) {
        if (focusBox == null) return null;
        int[] b = Geom.box(w, h, focusBox[0], focusBox[1], focusBox[2], focusBox[3], rotate);
        return new Rect(b[0], b[1], b[0] + b[2], b[1] + b[3]);
    }

    /**
     * The region a sharpness reading describes.
     *
     * The focus box where there is one, and the crop where there is not. Clipped to the
     * crop either way: a number measured outside the picture would describe something the
     * capture does not contain, which is worse than no number.
     */
    public Rect sharpnessRegionFor(int w, int h) {
        Rect roi = roiFor(w, h);
        Rect box = focusBoxFor(w, h);
        if (box == null) return roi;
        Rect clipped = new Rect(box);
        return clipped.intersect(roi) ? clipped : roi;
    }

    /** Whether the focus box names a place the picture actually contains. */
    public boolean focusBoxOverlapsRoi(int w, int h) {
        Rect box = focusBoxFor(w, h);
        return box == null || Rect.intersects(box, roiFor(w, h));
    }

    /**
     * True when the ROI is the whole frame.
     *
     * The limit is a zoom of 1.0001 and it decides which of two pipelines makes a still:
     * at or below it the camera JPEG comes back untouched, above it the frame is decoded
     * and encoded again. Two captures on opposite sides of the limit are different kinds
     * of image, so each capture records which path it took.
     */
    public boolean roiIsWholeFrame() {
        return zoom <= ZOOM_PRISTINE_LIMIT;
    }

    public static final float ZOOM_PRISTINE_LIMIT = 1.0001f;

    /** True when the still path can hand back the camera JPEG untouched. */
    public boolean stillIsPristine() {
        return roiIsWholeFrame() && rotate == 0 && outW == null && outH == null;
    }

    /** Which of the two still pipelines a capture with these settings goes through. */
    public String capturePath() {
        return stillIsPristine() ? "camera_jpeg" : "decoded_and_reencoded";
    }

    // ------------------------------------------------------------- parsing

    /**
     * Applies query parameters onto this instance.
     *
     * Every name comes from Params, so the parser and /api/help cannot drift apart. An
     * unknown name and a bad value are both reported, because a typo in an agent's URL
     * must surface as an error rather than silently doing nothing (rule R5).
     */
    public void apply(Map<String, String> q, Caps caps, StringBuilder problems) {
        for (Map.Entry<String, String> e : q.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.US);
            String problem = Params.apply(this, k, e.getValue(), caps);
            if (problem != null) problems.append(problem).append("; ");
        }
    }

    static int parseAf(String v) {
        switch (v.trim().toLowerCase(Locale.US)) {
            case "off": case "manual":     return CaptureRequest.CONTROL_AF_MODE_OFF;
            case "auto":                   return CaptureRequest.CONTROL_AF_MODE_AUTO;
            case "macro":                  return CaptureRequest.CONTROL_AF_MODE_MACRO;
            case "continuous": case "cont":
            case "picture":                return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            case "video":                  return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            case "edof":                   return CaptureRequest.CONTROL_AF_MODE_EDOF;
            default: throw new NumberFormatException("af mode " + v);
        }
    }

    static int parseAwb(String v) {
        switch (v.trim().toLowerCase(Locale.US)) {
            case "off": case "manual":  return CaptureRequest.CONTROL_AWB_MODE_OFF;
            case "auto":                return CaptureRequest.CONTROL_AWB_MODE_AUTO;
            case "incandescent":        return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
            case "fluorescent":         return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
            case "warmfluorescent":     return CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT;
            case "daylight":            return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
            case "cloudy":              return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            case "twilight":            return CaptureRequest.CONTROL_AWB_MODE_TWILIGHT;
            case "shade":               return CaptureRequest.CONTROL_AWB_MODE_SHADE;
            default: throw new NumberFormatException("awb mode " + v);
        }
    }

    /** Kept for the CLI and for anything that still reads the old name. */
    public static long parseExposureNs(String v) { return Parse.exposureNs(v); }

    // ---------------------------------------------------------------- json

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("camera", cameraId);
        o.put("zoom", round2(zoom));
        o.put("cx", round3(cx));
        o.put("cy", round3(cy));
        o.put("af", afName(afMode));
        o.put("focus_diopters", focusDiopters == null ? JSONObject.NULL : round2(focusDiopters));
        if (focusDiopters != null && focusDiopters > 0.001f) {
            o.put("focus_metres", round3(1f / focusDiopters));
        }
        o.put("ae", aeAuto ? "auto" : "manual");
        o.put("exposure_ns", exposureNs == null ? JSONObject.NULL : exposureNs);
        if (exposureNs != null) o.put("exposure_human", humanExposure(exposureNs));
        o.put("iso", iso == null ? JSONObject.NULL : iso);
        o.put("ev", evSteps);
        o.put("ae_lock", aeLock);
        o.put("awb", awbName(awbMode));
        o.put("awb_lock", awbLock);
        if (awbGains == null) {
            o.put("awb_gains_set", JSONObject.NULL);
        } else {
            JSONArray g = new JSONArray();
            for (float v : awbGains) g.put(round3(v));
            o.put("awb_gains_set", g);
        }
        if (focusBox == null) {
            o.put("focus_box", JSONObject.NULL);
        } else {
            JSONArray fb = new JSONArray();
            for (float v : focusBox) fb.put(round3(v));
            o.put("focus_box", fb);
        }
        o.put("torch", torch);
        o.put("measure", measure);
        o.put("shading_map", shadingMap);
        o.put("jpeg_quality", jpegQuality);
        o.put("rotate", rotate);
        o.put("out_w", outW == null ? JSONObject.NULL : outW);
        o.put("out_h", outH == null ? JSONObject.NULL : outH);
        o.put("capture_path", capturePath());
        o.put("preview_size", previewW + "x" + previewH);
        o.put("preview_size_requested", previewReqW + "x" + previewReqH);
        if (previewW != previewReqW || previewH != previewReqH) {
            o.put("preview_size_note", "the device does not offer "
                    + previewReqW + "x" + previewReqH + ", so it gave the nearest size it has");
        }
        o.put("still_size", (stillW == 0 ? "max" : stillW + "x" + stillH));
        return o;
    }

    public static String humanExposure(long ns) {
        if (ns >= 1_000_000_000L) return String.format(Locale.US, "%.3fs", ns / 1e9);
        if (ns >= 1_000_000L) return String.format(Locale.US, "%.2fms (1/%.0f)", ns / 1e6, 1e9 / ns);
        return String.format(Locale.US, "%.1fus (1/%.0f)", ns / 1e3, 1e9 / ns);
    }

    public static String afName(int m) {
        switch (m) {
            case CaptureRequest.CONTROL_AF_MODE_OFF: return "off";
            case CaptureRequest.CONTROL_AF_MODE_AUTO: return "auto";
            case CaptureRequest.CONTROL_AF_MODE_MACRO: return "macro";
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO: return "video";
            case CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE: return "continuous";
            case CaptureRequest.CONTROL_AF_MODE_EDOF: return "edof";
            default: return String.valueOf(m);
        }
    }

    public static String awbName(int m) {
        switch (m) {
            case CaptureRequest.CONTROL_AWB_MODE_OFF: return "off";
            case CaptureRequest.CONTROL_AWB_MODE_AUTO: return "auto";
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT: return "incandescent";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT: return "fluorescent";
            case CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT: return "warmfluorescent";
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT: return "daylight";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "cloudy";
            case CaptureRequest.CONTROL_AWB_MODE_TWILIGHT: return "twilight";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE: return "shade";
            default: return String.valueOf(m);
        }
    }

    // --------------------------------------------------------------- utils

    static float clamp(float v, float lo, float hi) { return Geom.clamp(v, lo, hi); }
    static int clampInt(int v, int lo, int hi) { return Geom.clampInt(v, lo, hi); }
    static long clampLong(long v, long lo, long hi) { return Geom.clampLong(v, lo, hi); }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /** Static limits read once from CameraCharacteristics, used to clamp incoming values. */
    public static class Caps {
        public float maxSoftZoom = 8f;
        public float minFocusDiopters = 10f;
        public long minExposureNs = 100_000L, maxExposureNs = 100_000_000L;
        public int minIso = 100, maxIso = 3200;
        /**
         * The highest ISO the sensor reaches with analogue gain.
         *
         * Above it the extra gain is arithmetic on values the sensor already read, so it
         * multiplies the noise with the signal and buys nothing a workstation could not do
         * afterwards with the numbers in front of it. It matters to any measurement that
         * compares frames at different exposures. Card 7.
         */
        public int maxAnalogIso = 3200;
        public int evMin = -12, evMax = 12;
        public double evStep = 1.0 / 6.0;
        public int flashMaxLevel = 1;

        /**
         * The longest output edge a request may ask for, and the longest burst.
         *
         * Both come from the heap this process was given, not from a fixed number. A
         * resize to 100000 by 100000 asks for 40 GB and used to reach
         * Bitmap.createScaledBitmap, where OutOfMemoryError is an Error rather than an
         * Exception and took the whole service down with it.
         */
        public int maxOutputEdge = 8192;
        public int maxBurst = 16;
        public long maxOutputPixels = 16_000_000L;

        public static Caps from(CameraCharacteristics c, int sensorW) {
            Caps caps = new Caps();
            android.util.Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (exp != null) { caps.minExposureNs = exp.getLower(); caps.maxExposureNs = exp.getUpper(); }
            android.util.Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (iso != null) { caps.minIso = iso.getLower(); caps.maxIso = iso.getUpper(); }
            Integer analog = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
            caps.maxAnalogIso = analog != null ? analog : caps.maxIso;
            android.util.Range<Integer> ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (ev != null) { caps.evMin = ev.getLower(); caps.evMax = ev.getUpper(); }
            android.util.Rational evs = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (evs != null) caps.evStep = evs.doubleValue();
            Float mfd = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (mfd != null && mfd > 0) caps.minFocusDiopters = mfd;
            Integer fl = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            caps.flashMaxLevel = (fl != null && fl > 0) ? fl : 1;
            // Cropping below ~64px of sensor width stops being useful, so cap zoom there.
            caps.maxSoftZoom = Math.max(2f, sensorW / 64f);
            return caps;
        }

        /**
         * Works out what this heap can carry.
         *
         * A resize holds the source bitmap and the destination at four bytes a pixel, so
         * a quarter of the heap is the honest ceiling for one output. A burst holds the
         * frame bytes, then the tar, then the copy the tar makes when it finishes, which
         * is three times the frame bytes.
         */
        public void sizeToHeap(long maxHeapBytes, long stillPixels) {
            long budget = Math.max(8L << 20, maxHeapBytes / 4);
            maxOutputPixels = Math.max(1_000_000L, budget / 8);          // source + destination
            maxOutputEdge = (int) Math.min(16384, Math.max(1024, Math.sqrt((double) maxOutputPixels)));
            // A full-resolution JPEG of a bench scene runs about a third of a byte per
            // pixel at quality 92. The archive is written straight to the socket, so the
            // frames themselves are the only copy that has to fit, and half the heap is
            // the budget for them.
            long perFrame = Math.max(1L << 20, stillPixels * 35 / 100);
            maxBurst = (int) Geom.clampLong(maxHeapBytes / 2 / perFrame, 4, 64);
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("max_zoom", round2(maxSoftZoom));
            o.put("min_focus_diopters", round2(minFocusDiopters));
            o.put("closest_focus_metres", round3(1.0 / minFocusDiopters));
            o.put("exposure_ns_range", minExposureNs + ".." + maxExposureNs);
            o.put("exposure_human_range", humanExposure(minExposureNs) + " .. " + humanExposure(maxExposureNs));
            o.put("iso_range", minIso + ".." + maxIso);
            o.put("max_analog_iso", maxAnalogIso);
            o.put("ev_range", evMin + ".." + evMax);
            o.put("ev_step", round3(evStep));
            o.put("torch_max_level", flashMaxLevel);
            o.put("max_output_edge", maxOutputEdge);
            o.put("max_output_pixels", maxOutputPixels);
            o.put("burst_max", maxBurst);
            return o;
        }
    }
}
