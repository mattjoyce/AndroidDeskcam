package dev.deskcam;

import java.util.Locale;

/**
 * The value parsers, with no Android type in sight, so a workstation can test them.
 *
 * Every one of these throws NumberFormatException on a value it cannot read. Nothing here
 * falls back to a default: a bad value is a bad request, and rule R5 says the caller hears
 * about it rather than getting silence and the old setting.
 */
public final class Parse {

    private Parse() { }

    /**
     * Exposure accepts nanoseconds, or the forms photographers and datasheets actually
     * use: 1/120, 8ms, 250us, 0.5s.
     */
    public static long exposureNs(String v) {
        String s = v.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) throw new NumberFormatException("empty exposure");
        double ns;
        if (s.contains("/")) {
            String[] p = s.split("/", 2);
            double num = Double.parseDouble(p[0].trim());
            double den = Double.parseDouble(p[1].replaceAll("[^0-9.]", "").trim());
            if (den == 0) throw new NumberFormatException("exposure divided by zero");
            ns = num / den * 1e9;
        } else if (s.endsWith("ms")) {
            ns = Double.parseDouble(s.substring(0, s.length() - 2)) * 1e6;
        } else if (s.endsWith("us")) {
            ns = Double.parseDouble(s.substring(0, s.length() - 2)) * 1e3;
        } else if (s.endsWith("ns")) {
            ns = Double.parseDouble(s.substring(0, s.length() - 2));
        } else if (s.endsWith("s")) {
            ns = Double.parseDouble(s.substring(0, s.length() - 1)) * 1e9;
        } else {
            ns = Double.parseDouble(s);
        }
        if (Double.isNaN(ns) || Double.isInfinite(ns)) throw new NumberFormatException("exposure " + v);
        return Math.round(ns);
    }

    public static boolean bool(String v) {
        String s = v.trim().toLowerCase(Locale.US);
        switch (s) {
            case "on": case "true": case "1": case "yes": case "auto":
                return true;
            case "off": case "false": case "0": case "no": case "":
                return false;
            default:
                throw new NumberFormatException("expected on or off, got '" + v + "'");
        }
    }

    /** A float that is a real number. Float.parseFloat is happy to return NaN. */
    public static float number(String v) {
        float f = Float.parseFloat(v.trim());
        if (Float.isNaN(f)) throw new NumberFormatException("not a number: '" + v + "'");
        return f;
    }

    public static int integer(String v) {
        return Integer.parseInt(v.trim());
    }

    public static int[] size(String v) {
        String[] p = v.toLowerCase(Locale.US).split("[x,*]");
        if (p.length != 2) throw new NumberFormatException("size " + v);
        int w = Integer.parseInt(p[0].trim());
        int h = Integer.parseInt(p[1].trim());
        if (w <= 0 || h <= 0) throw new NumberFormatException("size " + v);
        return new int[]{w, h};
    }

    public static int torch(String v, int max) {
        String s = v.trim().toLowerCase(Locale.US);
        if (s.equals("off") || s.equals("false") || s.equals("no")) return 0;
        if (s.equals("on") || s.equals("true") || s.equals("yes")) return Math.max(1, max / 2);
        if (s.equals("max")) return max;
        return Integer.parseInt(s);
    }

    /**
     * Reads R,GE,GO,B gains, or the two words that stand for a whole vector.
     *
     * Four numbers and not three, because a Bayer sensor has two green photosites per
     * cell and Camera2 gives them separate gains. They are usually equal and are not
     * always: the two greens sit under different neighbours and can be trimmed apart.
     */
    /**
     * A focus box as cx,cy,w,h, or nothing at all.
     *
     * Nothing is the default and means the box follows the crop, which is what this
     * camera did before card 60 and is right whenever the thing you framed is the thing
     * you want sharp.
     */
    /**
     * A mark as cx,cy for a point or cx,cy,w,h for a box.
     *
     * The same coordinates and the same spelling as a focus box, because a second way to
     * write a rectangle in one API is how a measurement comes out wrong. Card 71.
     */
    public static float[] mark(String v) {
        String t = v.trim().toLowerCase(Locale.US);
        String[] parts = t.split("[,: ]+");
        if (parts.length != 2 && parts.length != 4) {
            throw new NumberFormatException("a mark is cx,cy for a point or cx,cy,w,h for a "
                    + "box, in fractions of the frame, e.g. 0.3,0.7 or 0.3,0.7,0.15,0.15; "
                    + "got '" + v + "'");
        }
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = number(parts[i]);
        if (out[0] < 0 || out[0] > 1 || out[1] < 0 || out[1] > 1) {
            throw new NumberFormatException("the centre of a mark is 0 to 1 across and down "
                    + "the frame, the same coordinates as cx and cy; got "
                    + out[0] + "," + out[1]);
        }
        if (out.length == 4 && (out[2] <= 0 || out[2] > 1 || out[3] <= 0 || out[3] > 1)) {
            throw new NumberFormatException("the width and height of a mark are fractions of "
                    + "the frame, above 0 and at most 1; got " + out[2] + "," + out[3]);
        }
        return out;
    }

    /** How long a mark's label may be. Enough for a sentence about one part. */
    public static final int LABEL_MAX = 80;

    /**
     * The words on a mark, made safe to keep and to draw.
     *
     * This is the only text in the project that one client writes and another reads, so it
     * is the only place a control character or a runaway length could arrive from outside
     * and be handed to somebody else's browser. The page draws it as text and never as
     * markup, and this is the belt beside that brace.
     */
    public static String label(String v) {
        if (v == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length() && b.length() < LABEL_MAX; i++) {
            char c = v.charAt(i);
            b.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return b.toString().trim();
    }

    public static float[] focusBox(String v) {
        String t = v.trim().toLowerCase(Locale.US);
        if (t.isEmpty() || t.equals("off") || t.equals("auto") || t.equals("roi")) return null;
        String[] parts = t.split("[,: ]+");
        if (parts.length != 4) {
            throw new NumberFormatException("a focus box is cx,cy,w,h in fractions of the "
                    + "frame, e.g. 0.3,0.7,0.15,0.15, or 'off' to follow the crop; got '"
                    + v + "'");
        }
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) out[i] = number(parts[i]);
        if (out[0] < 0 || out[0] > 1 || out[1] < 0 || out[1] > 1) {
            throw new NumberFormatException("the centre of a focus box is 0 to 1 across and "
                    + "down the frame, the same coordinates as cx and cy; got "
                    + out[0] + "," + out[1]);
        }
        if (out[2] <= 0 || out[2] > 1 || out[3] <= 0 || out[3] > 1) {
            throw new NumberFormatException("the size of a focus box is a fraction of the "
                    + "frame, above 0 and at most 1; got " + out[2] + "x" + out[3]);
        }
        return out;
    }

    public static float[] gains(String v) {
        String t = v.trim().toLowerCase(Locale.US);
        if (t.equals("auto") || t.isEmpty()) return null;
        if (t.equals("neutral") || t.equals("unity") || t.equals("1")) {
            return new float[]{1f, 1f, 1f, 1f};
        }
        String[] parts = t.split("[,: ]+");
        if (parts.length != 4) {
            throw new NumberFormatException("white balance gains are R,GE,GO,B, "
                    + "or 'neutral', or 'auto'; got '" + v + "'");
        }
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            out[i] = number(parts[i]);
            if (out[i] < 0.1f || out[i] > 16f) {
                throw new NumberFormatException("a white balance gain of " + out[i]
                        + " is outside 0.1 to 16");
            }
        }
        return out;
    }

    /**
     * A value turned into something that can be part of a filename.
     *
     * A walk names its frames after what they were taken at, and the values people type
     * are full of characters a path is not: 1/240, 0.5s, 1.99,1,1,2.07. Everything outside
     * a small safe set becomes a dash, so a name still reads as the value it came from.
     */
    public static String fileSafe(String value) {
        String out = value.trim().replaceAll("[^A-Za-z0-9._+-]", "-").replaceAll("-{2,}", "-");
        out = out.replaceAll("^-+|-+$", "");
        if (out.isEmpty()) out = "value";
        return out.length() > 24 ? out.substring(0, 24) : out;
    }

    /** Rounds a rotation onto a quarter turn in 0, 90, 180, 270. */
    public static int rotation(String v) {
        int deg = Integer.parseInt(v.trim());
        return ((deg % 360) + 360) % 360 / 90 * 90;
    }
}
