package dev.deskcam;

/**
 * The framing maths, with no Android type in sight.
 *
 * It lives apart from CamSettings so a workstation can run it. Every framing fault this
 * project has had was in the coordinate change under rotation or in the clamp, and both
 * are pure functions of a few numbers. See Tests.
 */
public final class Geom {

    private Geom() { }

    /**
     * The crop rectangle for a frame of w by h pixels, as left, top, width, height.
     *
     * cx and cy name a point in the picture the caller SEES, which is the rotated output.
     * The crop happens before the rotation, in sensor space, so the centre is mapped back.
     * Without this a rotated camera needs inverted coordinates and nobody can guess that.
     *
     * The size needs no change. A quarter turn swaps both the frame and the region, so a
     * w/z by h/z rectangle stays a w/z by h/z rectangle.
     */
    public static int[] roi(int w, int h, float zoom, float cx, float cy, int rotate) {
        float z = Math.max(1.0f, zoom);
        int rw = Math.min(w, Math.max(16, Math.round(w / z)));
        int rh = Math.min(h, Math.max(16, Math.round(h / z)));

        float sx, sy;
        switch (rotate) {
            case 90:  sx = cy;      sy = 1f - cx; break;
            case 180: sx = 1f - cx; sy = 1f - cy; break;
            case 270: sx = 1f - cy; sy = cx;      break;
            default:  sx = cx;      sy = cy;      break;
        }

        int left = clampInt(Math.round(sx * w - rw / 2f), 0, w - rw);
        int top = clampInt(Math.round(sy * h - rh / 2f), 0, h - rh);
        return new int[]{left, top, rw, rh};
    }

    /**
     * A named rectangle inside a frame of w by h pixels, as left, top, width, height.
     *
     * The same coordinates {@link #roi} uses: cx and cy name a point in the picture the
     * caller SEES, and the mapping back through the rotation is the same one, because a
     * second coordinate system in one API is how a measurement comes out wrong. bw and bh
     * are fractions of the frame, and a quarter turn swaps them for the same reason the
     * crop's do not need to change.
     *
     * Clamped to the frame, never larger than it, and never smaller than the 3 by 3 the
     * sharpness kernel needs a neighbour on every side of. Card 60.
     */
    public static int[] box(int w, int h, float cx, float cy, float bw, float bh, int rotate) {
        float fw = clamp(bw, 0f, 1f);
        float fh = clamp(bh, 0f, 1f);
        int rw, rh;
        if (rotate == 90 || rotate == 270) {
            rw = Math.round(h * fh);
            rh = Math.round(w * fw);
        } else {
            rw = Math.round(w * fw);
            rh = Math.round(h * fh);
        }
        rw = clampInt(rw, 3, w);
        rh = clampInt(rh, 3, h);

        float sx, sy;
        switch (rotate) {
            case 90:  sx = cy;      sy = 1f - cx; break;
            case 180: sx = 1f - cx; sy = 1f - cy; break;
            case 270: sx = 1f - cy; sy = cx;      break;
            default:  sx = cx;      sy = cy;      break;
        }
        int left = clampInt(Math.round(sx * w - rw / 2f), 0, w - rw);
        int top = clampInt(Math.round(sy * h - rh / 2f), 0, h - rh);
        return new int[]{left, top, rw, rh};
    }

    /**
     * A point in the picture the caller SEES, mapped onto the sensor.
     *
     * {@link #roi} and {@link #box} do this inline for a rectangle they are about to place.
     * A mark needs it on its own, because a mark is kept rather than used and thrown away:
     * it names a part of the thing on the bench, so it is stored on the sensor and mapped
     * back into the seen picture every time it is read. Keep it the way it was named and
     * the day somebody remounts the phone and sets rotate=180, every mark slides off the
     * part it names. Card 71.
     */
    public static float[] toSensor(float cx, float cy, int rotate) {
        switch (rotate) {
            case 90:  return new float[]{cy, 1f - cx};
            case 180: return new float[]{1f - cx, 1f - cy};
            case 270: return new float[]{1f - cy, cx};
            default:  return new float[]{cx, cy};
        }
    }

    /** The inverse of {@link #toSensor}: a point on the sensor, in the picture as seen. */
    public static float[] toSeen(float sx, float sy, int rotate) {
        switch (rotate) {
            case 90:  return new float[]{1f - sy, sx};
            case 180: return new float[]{1f - sx, 1f - sy};
            case 270: return new float[]{sy, 1f - sx};
            default:  return new float[]{sx, sy};
        }
    }

    /** Whether two rectangles, each as left, top, width, height, share any pixel. */
    public static boolean overlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
                && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /**
     * Clamps a float, and refuses NaN.
     *
     * The old form was {@code v < lo ? lo : (v > hi ? hi : v)}, which passes NaN straight
     * through because both comparisons are false. A NaN zoom then reached the ROI maths
     * and gave a 16 by 16 crop that the status reported as zoom 0.0.
     */
    public static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) throw new NumberFormatException("not a number");
        if (Float.isInfinite(v)) return v > 0 ? hi : lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double clampDouble(double v, double lo, double hi) {
        if (Double.isNaN(v)) throw new NumberFormatException("not a number");
        if (Double.isInfinite(v)) return v > 0 ? hi : lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static long clampLong(long v, long lo, long hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /**
     * Rejects a value outside its range rather than quietly moving it inside.
     *
     * A clamp is right for a nudge like dx, where the caller means "as far as you can go".
     * It is wrong for a size or a timeout, where a value of 100000 is a mistake and
     * answering with 4096 hides it.
     */
    public static int require(String name, int v, int lo, int hi) {
        if (v < lo || v > hi) {
            throw new NumberFormatException(name + " must be between " + lo + " and " + hi);
        }
        return v;
    }

    public static long requireLong(String name, long v, long lo, long hi) {
        if (v < lo || v > hi) {
            throw new NumberFormatException(name + " must be between " + lo + " and " + hi);
        }
        return v;
    }

    /**
     * The i'th of `steps` positions spread evenly from `from` to `to`, both ends included.
     *
     * A focus sweep uses this in diopters and never in millimetres. Depth of field is very
     * nearly constant per diopter and wildly unequal per millimetre: near the 98 mm closest
     * focus of this lens one millimetre is about a tenth of a diopter, and at half a metre
     * it is four thousandths. A sweep spread evenly in millimetres would crawl at one end
     * and step over the subject at the other. Card 5.
     */
    public static float sweepStep(float from, float to, int i, int steps) {
        if (steps < 2) throw new NumberFormatException("a sweep needs at least 2 steps");
        return from + (to - from) * i / (steps - 1);
    }
}
