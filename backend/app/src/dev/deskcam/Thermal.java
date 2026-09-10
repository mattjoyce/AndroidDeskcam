package dev.deskcam;

/**
 * What the platform's thermal status means for a bench camera, apart from the platform.
 *
 * Android's own judgement of how hot the device is, on a scale it also acts on, so this
 * has no thermometer of its own and wants none: a number from a sensor would need a
 * threshold per device, and the system already made that decision with far more to go on.
 *
 * The one decision here is how much a stream should slow down at each level. It is a
 * table, and it is here rather than in Health so a workstation can check it: monotone, at
 * least one everywhere, and defined for a level this build has never heard of. Card 44.
 */
final class Thermal {

    private Thermal() { }

    // The values of PowerManager.THERMAL_STATUS_*, which Health passes straight through.
    // They are platform constants and cannot move; naming them is for reading this table.
    static final int NONE = 0;
    static final int LIGHT = 1;
    static final int MODERATE = 2;
    static final int SEVERE = 3;
    static final int CRITICAL = 4;
    static final int EMERGENCY = 5;
    static final int SHUTDOWN = 6;

    /** What the platform reports before it has told us anything. */
    static final int UNKNOWN = -1;

    /**
     * How much longer to wait between stream frames at this level.
     *
     * A multiplier on the interval the client asked for, never a cap, so a stream already
     * running slowly sheds as much of its share as a fast one. Encoding a preview frame is
     * the cost, and it is per frame, so halving the rate halves the work.
     *
     * Nothing happens at LIGHT because the platform defines it as throttling the user
     * cannot feel, and a bench camera that halved its frame rate every time a phone warmed
     * up slightly would be a camera nobody trusts. MODERATE is where the platform says the
     * experience is starting to suffer, and that is where this starts helping.
     *
     * It never stops the stream, even at SHUTDOWN. The stream is the channel carrying the
     * reason the rate fell, and closing it takes away the explanation at the moment it
     * matters most. Half a frame a second is close enough to nothing.
     */
    static double slowdown(int status) {
        switch (status) {
            case MODERATE: return 2;
            case SEVERE: return 4;
            case CRITICAL: return 8;
            case EMERGENCY:
            case SHUTDOWN: return 20;
            case NONE:
            case LIGHT:
            case UNKNOWN:
            default:
                // A level this build does not know is treated as no throttling. The
                // platform only ever added levels above the top of this table, and a
                // future one would be hotter, but guessing a slowdown from a number
                // nobody here understands is worse than leaving the rate to the caller
                // and reporting the level as it was given.
                return 1;
        }
    }

    /** Whether this level is one the platform is acting on. */
    static boolean throttling(int status) {
        return status >= MODERATE;
    }

    /** The level as a word, for a header and for /api/status. */
    static String word(int status) {
        switch (status) {
            case NONE: return "none";
            case LIGHT: return "light";
            case MODERATE: return "moderate";
            case SEVERE: return "severe";
            case CRITICAL: return "critical";
            case EMERGENCY: return "emergency";
            case SHUTDOWN: return "shutdown";
            case UNKNOWN: return "unknown";
            default: return "level " + status;
        }
    }

    /** What this level means for the device, in the words the platform defines it in. */
    static String means(int status) {
        switch (status) {
            case NONE: return "not throttling.";
            case LIGHT: return "throttling lightly, which the platform defines as not "
                    + "affecting what anyone can feel. The stream rate is untouched.";
            case MODERATE: return "throttling moderately. The stream rate is halved.";
            case SEVERE: return "throttling severely, and the platform says the experience "
                    + "is largely affected. The stream rate is a quarter of what was asked "
                    + "for. Captures are not slowed: they are one-off work you asked for.";
            case CRITICAL: return "throttling as hard as it can. The stream rate is an "
                    + "eighth of what was asked for. Take the camera off the stand or give "
                    + "it air; a measurement taken now is being taken by a hot sensor.";
            case EMERGENCY: return "at an emergency level, with parts of it shut down. The "
                    + "stream is down to a trickle so that this reading can still reach "
                    + "you. Stop the session.";
            case SHUTDOWN: return "about to shut itself down. Stop the session.";
            case UNKNOWN: return "not reported yet, so nothing is being slowed.";
            default: return "at a level this build does not know, so nothing is being "
                    + "slowed. It is above every level this build knows about, which "
                    + "means hotter than critical.";
        }
    }
}
