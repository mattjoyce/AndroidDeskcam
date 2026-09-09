package dev.deskcam;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;

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
 */
public class CamSettings implements Cloneable {

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

    /** 0 = torch off, otherwise 1..flashMaxLevel. */
    public int torch = 0;

    public int jpegQuality = 92;
    /** Clockwise degrees applied to the returned pixels: 0, 90, 180 or 270. */
    public int rotate = 0;
    /** Optional output resize after cropping. null keeps native crop size. */
    public Integer outW = null;
    public Integer outH = null;

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

    public int previewW = 1280, previewH = 960;
    public int stillW = 0, stillH = 0;   // 0,0 means "largest the sensor offers"

    @Override
    public CamSettings clone() {
        try {
            return (CamSettings) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- ROI

    /**
     * The crop rectangle for a frame of the given pixel dimensions. Because we crop in
     * image space rather than sensor space this works for both the full-res still and
     * the smaller preview frames, with the same normalised coordinates.
     */
    public Rect roiFor(int w, int h) {
        float z = Math.max(1.0f, zoom);
        int rw = Math.max(16, Math.round(w / z));
        int rh = Math.max(16, Math.round(h / z));

        // cx and cy name a point in the picture the caller SEES, which is the rotated
        // output. The crop happens before the rotation, in sensor space, so the centre
        // must be mapped back. Without this, a rotated camera needs inverted coordinates
        // and nobody can guess that.
        //
        // The size needs no change. A quarter turn swaps both the frame and the region,
        // so a w/z by h/z rectangle stays a w/z by h/z rectangle.
        float sx, sy;
        switch (rotate) {
            case 90:  sx = cy;      sy = 1f - cx; break;
            case 180: sx = 1f - cx; sy = 1f - cy; break;
            case 270: sx = 1f - cy; sy = cx;      break;
            default:  sx = cx;      sy = cy;      break;
        }

        int left = Math.round(sx * w - rw / 2f);
        int top = Math.round(sy * h - rh / 2f);
        left = clampInt(left, 0, w - rw);
        top = clampInt(top, 0, h - rh);
        return new Rect(left, top, left + rw, top + rh);
    }

    public boolean roiIsWholeFrame() {
        return zoom <= 1.0001f;
    }

    /** True when the still path can hand back the camera JPEG untouched. */
    public boolean stillIsPristine() {
        return roiIsWholeFrame() && rotate == 0 && outW == null && outH == null;
    }

    // ------------------------------------------------------------- parsing

    /**
     * Applies query parameters onto this instance. Unknown keys are reported so a
     * typo in an agent's URL surfaces as an error instead of silently doing nothing.
     */
    public void apply(Map<String, String> q, Caps caps, StringBuilder problems) {
        for (Map.Entry<String, String> e : q.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.US);
            String v = e.getValue();
            try {
                switch (k) {
                    case "camera": case "cam":   cameraId = v; break;

                    case "zoom":  zoom = clamp(Float.parseFloat(v), 1f, caps.maxSoftZoom); break;
                    case "cx":    cx = clamp(Float.parseFloat(v), 0f, 1f); break;
                    case "cy":    cy = clamp(Float.parseFloat(v), 0f, 1f); break;
                    // Relative pan, expressed in fractions of the CURRENT roi width, so a
                    // given dx nudges by the same visual amount at any zoom level.
                    case "dx":    cx = clamp(cx + Float.parseFloat(v) / Math.max(1f, zoom), 0f, 1f); break;
                    case "dy":    cy = clamp(cy + Float.parseFloat(v) / Math.max(1f, zoom), 0f, 1f); break;
                    case "zoomby": zoom = clamp(zoom * Float.parseFloat(v), 1f, caps.maxSoftZoom); break;

                    case "af":    afMode = parseAf(v); if (afMode >= 0) focusDiopters = null; break;
                    case "focus": {
                        if (v.equalsIgnoreCase("auto")) { focusDiopters = null; break; }
                        float d = Float.parseFloat(v);
                        focusDiopters = clamp(d, 0f, caps.minFocusDiopters);
                        afMode = CaptureRequest.CONTROL_AF_MODE_OFF;
                        break;
                    }
                    case "focusm": {   // focus by distance in metres, friendlier for bench work
                        float m = Float.parseFloat(v);
                        focusDiopters = clamp(m <= 0 ? 0f : 1f / m, 0f, caps.minFocusDiopters);
                        afMode = CaptureRequest.CONTROL_AF_MODE_OFF;
                        break;
                    }

                    case "ae":    aeAuto = parseBool(v); break;
                    case "exposure": case "shutter": {
                        exposureNs = clampLong(parseExposureNs(v), caps.minExposureNs, caps.maxExposureNs);
                        aeAuto = false;
                        break;
                    }
                    case "iso": case "sensitivity":
                        iso = clampInt(Integer.parseInt(v), caps.minIso, caps.maxIso);
                        aeAuto = false;
                        break;
                    case "ev":      evSteps = clampInt(Integer.parseInt(v), caps.evMin, caps.evMax); break;
                    case "aelock":  aeLock = parseBool(v); break;

                    case "awb":     awbMode = parseAwb(v); break;
                    case "awblock": awbLock = parseBool(v); break;

                    case "torch":   torch = clampInt(parseTorch(v, caps.flashMaxLevel), 0, caps.flashMaxLevel); break;

                    case "measure": {
                        measure = parseBool(v);
                        // A moving white balance invents colour differences between two
                        // shots of the same subject, so lock it with the rest.
                        if (measure) awbLock = true;
                        break;
                    }

                    case "shadingmap": shadingMap = parseBool(v); break;

                    case "jpegq": case "quality":
                        jpegQuality = clampInt(Integer.parseInt(v), 1, 100); break;
                    case "rotate":  rotate = ((Integer.parseInt(v) % 360) + 360) % 360 / 90 * 90; break;
                    case "w":       outW = Integer.parseInt(v); break;
                    case "h":       outH = Integer.parseInt(v); break;

                    case "previewsize": { int[] s = parseSize(v); previewW = s[0]; previewH = s[1]; break; }
                    case "stillsize":   { int[] s = parseSize(v); stillW = s[0]; stillH = s[1]; break; }

                    // Consumed by the router or by the transport, not by the settings.
                    // Any name the router reads must appear here, or rule R5 rejects a
                    // request that is in fact valid.
                    case "t": case "_": case "format": case "reset":
                    case "fps": case "n":
                    case "settle": case "timeout": case "wait": case "fresh":
                    case "host": case "port":
                        break;
                    default:
                        problems.append("unknown parameter '").append(k).append("'; ");
                }
            } catch (NumberFormatException nfe) {
                problems.append("bad value for '").append(k).append("': '").append(v).append("'; ");
            }
        }
    }

    /**
     * Exposure accepts nanoseconds, or the forms photographers and datasheets actually
     * use: 1/120, 8ms, 250us, 0.5s.
     */
    public static long parseExposureNs(String v) {
        String s = v.trim().toLowerCase(Locale.US);
        if (s.contains("/")) {
            String[] p = s.split("/", 2);
            double num = Double.parseDouble(p[0].trim());
            double den = Double.parseDouble(p[1].replaceAll("[^0-9.]", "").trim());
            return Math.round(num / den * 1e9);
        }
        if (s.endsWith("ms"))  return Math.round(Double.parseDouble(s.substring(0, s.length() - 2)) * 1e6);
        if (s.endsWith("us"))  return Math.round(Double.parseDouble(s.substring(0, s.length() - 2)) * 1e3);
        if (s.endsWith("ns"))  return Math.round(Double.parseDouble(s.substring(0, s.length() - 2)));
        if (s.endsWith("s"))   return Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1e9);
        return Math.round(Double.parseDouble(s));
    }

    private static int parseTorch(String v, int max) {
        if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false")) return 0;
        if (v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true")) return Math.max(1, max / 2);
        if (v.equalsIgnoreCase("max")) return max;
        return Integer.parseInt(v);
    }

    private static int parseAf(String v) {
        switch (v.toLowerCase(Locale.US)) {
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

    private static int parseAwb(String v) {
        switch (v.toLowerCase(Locale.US)) {
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

    private static boolean parseBool(String v) {
        return v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true")
                || v.equals("1") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("auto");
    }

    private static int[] parseSize(String v) {
        String[] p = v.toLowerCase(Locale.US).split("[x,*]");
        if (p.length != 2) throw new NumberFormatException("size " + v);
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
    }

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
        o.put("torch", torch);
        o.put("measure", measure);
        o.put("shading_map", shadingMap);
        o.put("jpeg_quality", jpegQuality);
        o.put("rotate", rotate);
        o.put("out_w", outW == null ? JSONObject.NULL : outW);
        o.put("out_h", outH == null ? JSONObject.NULL : outH);
        o.put("preview_size", previewW + "x" + previewH);
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

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static long clampLong(long v, long lo, long hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    /** Static limits read once from CameraCharacteristics, used to clamp incoming values. */
    public static class Caps {
        public float maxSoftZoom = 8f;
        public float minFocusDiopters = 10f;
        public long minExposureNs = 100_000L, maxExposureNs = 100_000_000L;
        public int minIso = 100, maxIso = 3200;
        public int evMin = -12, evMax = 12;
        public double evStep = 1.0 / 6.0;
        public int flashMaxLevel = 1;

        public static Caps from(CameraCharacteristics c, int sensorW) {
            Caps caps = new Caps();
            android.util.Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (exp != null) { caps.minExposureNs = exp.getLower(); caps.maxExposureNs = exp.getUpper(); }
            android.util.Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (iso != null) { caps.minIso = iso.getLower(); caps.maxIso = iso.getUpper(); }
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

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("max_zoom", round2(maxSoftZoom));
            o.put("min_focus_diopters", round2(minFocusDiopters));
            o.put("closest_focus_metres", round3(1.0 / minFocusDiopters));
            o.put("exposure_ns_range", minExposureNs + ".." + maxExposureNs);
            o.put("exposure_human_range", humanExposure(minExposureNs) + " .. " + humanExposure(maxExposureNs));
            o.put("iso_range", minIso + ".." + maxIso);
            o.put("ev_range", evMin + ".." + evMax);
            o.put("ev_step", round3(evStep));
            o.put("torch_max_level", flashMaxLevel);
            return o;
        }
    }
}
