package dev.deskcam;

import java.util.List;
import java.util.Locale;

/**
 * What a focus curve says, kept apart from the camera that produced it.
 *
 * The reading is the camera's job and the decision is this one's. Splitting them is what
 * lets the decision be tested on a workstation with no phone: the shapes that matter, a
 * clean peak, a flat field, and a peak sitting on the end of the range, are three lists of
 * numbers, and the rule that tells them apart is arithmetic. Card 56.
 *
 * A hunt is allowed to refuse. `af_state: focused` with nothing behind it is what this
 * exists to improve on, so an answer of "there is no peak here" has to be available, or
 * the endpoint is just a slower autofocus that always claims to have worked.
 */
final class Hunt {

    private Hunt() { }

    /**
     * How far the curve must rise above its own floor before it counts as a peak.
     *
     * A fraction of the highest reading: 0.25 means the lowest reading is at most three
     * quarters of the highest. The real sweep in card 56, of a rule 235 mm from the lens,
     * ran from 2.9 to 33.6 and scores 0.91, and a sweep with nothing in focus anywhere
     * scores near zero because every reading is the same noise. Repeat readings at one
     * position on this bench move by a few percent, so a quarter is far above the noise
     * and far below any curve worth climbing.
     */
    static final double MIN_CONTRAST = 0.25;

    /** The curve was flat: nothing was in focus anywhere in the range. */
    static final String FLAT = "flat";

    /** The best reading was at an end of the range, so the real peak is outside it. */
    static final String AT_EDGE = "peak_at_edge";

    /** One position of the lens and the sharpness that was measured there. */
    static final class Reading {
        final float at;
        final double sharpness;

        Reading(float at, double sharpness) {
            this.at = at;
            this.sharpness = sharpness;
        }
    }

    /** What the curve says, and whether that is an answer. */
    static final class Verdict {
        /** The sharpest position seen, in diopters. Meaningful even when it refused. */
        final float at;
        /** The sharpness there. */
        final double sharpness;
        /** (highest - lowest) / highest over every reading. Zero for a flat field. */
        final double contrast;
        /** null when it chose, otherwise {@link #FLAT} or {@link #AT_EDGE}. */
        final String refusal;
        /** Why it refused, in prose the caller can act on. null when it chose. */
        final String reason;

        Verdict(float at, double sharpness, double contrast, String refusal, String reason) {
            this.at = at;
            this.sharpness = sharpness;
            this.contrast = contrast;
            this.refusal = refusal;
            this.reason = reason;
        }

        boolean chose() { return refusal == null; }
    }

    /**
     * Reads a curve and either chooses a focus or says why it will not.
     *
     * @param readings every position measured, in any order, from any number of passes
     * @param lo       the low end of the range that was searched, in diopters
     * @param hi       the high end of it
     * @param closest  the closest the lens focuses, which is the hard end of its travel
     */
    static Verdict judge(List<Reading> readings, float lo, float hi, float closest) {
        if (readings == null || readings.isEmpty()) {
            return new Verdict(lo, 0, 0, FLAT,
                    "no reading could be taken: every frame was too small to measure, or "
                    + "none arrived inside the timeout.");
        }

        int best = 0;
        double highest = readings.get(0).sharpness;
        double lowest = readings.get(0).sharpness;
        for (int i = 1; i < readings.size(); i++) {
            double v = readings.get(i).sharpness;
            if (v > highest) { highest = v; best = i; }
            if (v < lowest) lowest = v;
        }
        float at = readings.get(best).at;
        double contrast = highest > 0 ? (highest - lowest) / highest : 0;

        if (contrast < MIN_CONTRAST) {
            return new Verdict(at, highest, contrast, FLAT, String.format(Locale.US,
                    "the sharpness moved by %.0f%% across %.2f to %.2f diopters, and a peak "
                    + "moves it by far more. Nothing in the region of interest came into "
                    + "focus anywhere in this range. Check that the region holds an edge to "
                    + "focus on, that there is enough light, and that the subject is inside "
                    + "the range.", contrast * 100, lo, hi));
        }

        // The ends of the range are the two positions whose neighbourhood was only half
        // measured, so a maximum there is the largest of what was looked at and not a peak.
        boolean atLow = at <= lo + EDGE;
        boolean atHigh = at >= hi - EDGE;
        if (atLow || atHigh) {
            StringBuilder why = new StringBuilder(String.format(Locale.US,
                    "the sharpest reading, %.2f diopters, is the %s end of the range that "
                    + "was searched, so the curve was still climbing where the search "
                    + "stopped and the peak is not known to be inside it. ",
                    at, atLow ? "far" : "near"));
            if (atLow && lo <= EDGE) {
                why.append("That end is the lens at infinity and it does not travel past it, "
                        + "so there is nothing beyond to measure. A subject far enough away "
                        + "peaks here and this may be the true answer: set focus=0 to take it.");
            } else if (atHigh && hi >= closest - EDGE) {
                why.append(String.format(Locale.US,
                        "That end is the closest this lens focuses, about %.0f mm, so there "
                        + "is nothing beyond to measure. The subject is probably nearer than "
                        + "that: move the camera back rather than the lens.",
                        1000f / Math.max(closest, 1e-3f)));
            } else {
                float width = Math.abs(hi - lo);
                why.append(atLow
                        ? String.format(Locale.US, "Widen the range and hunt again, e.g. from=%.2f",
                                Math.max(0f, lo - width))
                        : String.format(Locale.US, "Widen the range and hunt again, e.g. to=%.2f",
                                Math.min(closest, hi + width)));
            }
            return new Verdict(at, highest, contrast, AT_EDGE, why.toString());
        }

        return new Verdict(at, highest, contrast, null, null);
    }

    /**
     * How near an end counts as being on it.
     *
     * The positions are computed by {@link Geom#sweepStep}, whose first and last are the
     * ends exactly, so this only has to survive the rounding of that arithmetic in float.
     */
    private static final float EDGE = 1e-4f;
}
