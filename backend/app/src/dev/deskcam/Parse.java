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

    /** Rounds a rotation onto a quarter turn in 0, 90, 180, 270. */
    public static int rotation(String v) {
        int deg = Integer.parseInt(v.trim());
        return ((deg % 360) + 360) % 360 / 90 * 90;
    }
}
