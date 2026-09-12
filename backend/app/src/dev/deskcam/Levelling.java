package dev.deskcam;

/**
 * The decisions a leveller makes, with no Android in them.
 *
 * The bubble card and the tones are both untestable on a workstation: one needs a Canvas
 * and the other needs a speaker. Every judgement they make is here instead, where
 * Tests.java runs it in a millisecond with no phone, because these are exactly the kind of
 * small numeric rules that go wrong silently. A range that flaps, a cadence that runs
 * backwards and an instruction that names the wrong edge all look fine in a screenshot.
 *
 * The gravity to angle conversion lives here too, and {@link Sensors} calls it, so the
 * leveller on the phone and the roll and pitch on /api/orientation cannot drift apart.
 *
 * <p><b>Why 0.5 degrees is level.</b> The lens covers about 1.23 times the working
 * distance across the frame, from the documented 120.7 mm field at the 98 mm minimum
 * focus. A tilt of t skews a flat subject's scale across the frame by roughly
 * 1.23 * sin(t): 14% at the 6.5 degrees this mount showed after a remount, 2.2% at one
 * degree, 1.1% at half a degree. Half a degree is where the error falls under one percent
 * at full frame, and it is about where a phone accelerometer stops being honest, so
 * refining past it is chasing the sensor rather than the mount.
 */
public final class Levelling {

    /** Level enough. Below this the scale error across a full frame is under one percent. */
    public static final float GOOD_DEG = 0.5f;

    /**
     * The ranges the card steps through, in degrees of half-width.
     *
     * A fixed scale is useless at both ends: ten degrees of range cannot show the last
     * half degree, and half a degree of range sends the bubble off the card the moment you
     * start. The widest is 10 because a mount this far out needs a spanner, not a nudge.
     */
    private static final float[] RANGES = {10f, 3f, 1f, 0.5f};

    /**
     * Closing in is harder than opening out.
     *
     * Without that gap a bubble sitting on a boundary flips the range back and forth and
     * the card becomes unreadable at the one moment you are watching it hardest. Opening
     * out happens just before the bubble would reach the rim; closing in waits until the
     * error is well inside the next range.
     */
    private static final float CLOSE_IN_AT = 0.55f;
    private static final float OPEN_OUT_AT = 0.95f;

    /** Beeps per second at the widest error, and at the edge of good. */
    public static final float SLOW_HZ = 1.5f;
    public static final float FAST_HZ = 16f;

    /** One beep is this long, or this share of the gap between beeps, whichever is less. */
    public static final float BEEP_MS = 70f;
    private static final float BEEP_DUTY = 0.35f;

    /**
     * A voice per axis, a fifth apart.
     *
     * Two axes converge at different times and the ear has to tell which one it is hearing
     * without looking, so they are far enough apart to name and consonant enough to listen
     * to for a minute while both sound at once.
     */
    public static final float ROLL_TONE_HZ = 523.25f;
    public static final float PITCH_TONE_HZ = 784.0f;

    private Levelling() { }

    public static int rangeCount() { return RANGES.length; }

    /** The half-width of a range, in degrees. */
    public static float span(int range) {
        return RANGES[range < 0 ? 0 : (range >= RANGES.length ? RANGES.length - 1 : range)];
    }

    /** True when this axis is close enough to stop adjusting it. */
    public static boolean good(float error) {
        return Math.abs(error) <= GOOD_DEG;
    }

    /**
     * The range to show next, given the one showing now and the worse of the two errors.
     *
     * Called on every sample, so it has to be stable: the same reading must never step the
     * range twice in the same direction, and a reading that sits still must not step it at
     * all.
     */
    public static int range(int showing, float worst) {
        int r = showing < 0 ? 0 : (showing >= RANGES.length ? RANGES.length - 1 : showing);
        if (Float.isNaN(worst)) return r;
        float w = Math.abs(worst);
        while (r > 0 && w > RANGES[r] * OPEN_OUT_AT) r--;
        while (r < RANGES.length - 1 && w < RANGES[r + 1] * CLOSE_IN_AT) r++;
        return r;
    }

    /**
     * How often this axis beeps, in beeps per second. Zero means hold a steady tone,
     * which is what good sounds like.
     *
     * The cadence is geometric in the error and keyed to the fixed journey from the widest
     * range down to good, not to the range on show. Keying it to the range would make the
     * beeping slow down at the instant the card zoomed in, which reads as going backwards
     * at the moment you are getting closer.
     */
    public static float beepsPerSecond(float error) {
        float e = Math.abs(error);
        if (Float.isNaN(e)) return SLOW_HZ;
        if (e <= GOOD_DEG) return 0f;
        float widest = RANGES[0];
        if (e >= widest) return SLOW_HZ;
        double u = Math.log(e / GOOD_DEG) / Math.log(widest / GOOD_DEG);
        return (float) (FAST_HZ * Math.pow(SLOW_HZ / FAST_HZ, u));
    }

    /** How long one beep of that cadence lasts, in milliseconds. */
    public static float beepMillis(float beepsPerSecond) {
        if (beepsPerSecond <= 0f) return BEEP_MS;
        float period = 1000f / beepsPerSecond;
        return Math.min(BEEP_MS, period * BEEP_DUTY);
    }

    /**
     * What to do about it, naming an edge of the screen.
     *
     * The worse axis only. You turn one screw at a time, and a card that asks for two
     * corrections at once gets read as one and a half.
     */
    public static String instruction(float roll, float pitch) {
        boolean rollGood = good(roll), pitchGood = good(pitch);
        if (rollGood && pitchGood) return "level";
        boolean rollWorse = Math.abs(roll) >= Math.abs(pitch);
        if (rollWorse || pitchGood) return roll > 0 ? "lower the right edge" : "lower the left edge";
        return pitch > 0 ? "lower the top edge" : "lower the bottom edge";
    }

    /**
     * Where the bubble sits on the card, as a fraction of the radius from the middle.
     *
     * Clamped to the rim on purpose. A bubble drawn off the card would be the one state
     * that tells you nothing about which way to turn the bracket, and it is the normal
     * state for a second or two every time somebody overshoots.
     */
    public static float offset(float error, float span) {
        if (Float.isNaN(error) || span <= 0f) return 0f;
        float f = error / span;
        return f < -1f ? -1f : (f > 1f ? 1f : f);
    }

    /**
     * The sideways lean, in degrees, from a gravity reading.
     *
     * Android reports gravity as positive on whichever axis points UP, so a positive roll
     * means the phone's +x, the right-hand edge of the screen in its natural orientation,
     * is the raised one. The bubble is drawn on that side and {@link #instruction} names
     * that edge, which is how a spirit level behaves: the bubble floats to the high side.
     */
    public static double rollDegrees(double gx, double gz) {
        return Math.toDegrees(Math.atan2(gx, gz));
    }

    /** The forward tip, in degrees. Positive means the top edge of the screen is raised. */
    public static double pitchDegrees(double gy, double gz) {
        return Math.toDegrees(Math.atan2(gy, gz));
    }
}
