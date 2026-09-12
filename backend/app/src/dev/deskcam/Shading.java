package dev.deskcam;

/**
 * What to believe about this camera's lens shading map, and what to say when there is none.
 *
 * This device taught the project a lesson and then taught it the inverse. A mode list says
 * a control is settable, not that the result arrives; that much was already written down.
 * The inverse is this file: <b>a result-key list does not tell you the result will not
 * arrive.</b> The Pixel 6a leaves android.statistics.lensShadingMap out of
 * getAvailableCaptureResultKeys() and then puts a full 25 by 33 RGGB map in every capture
 * result taken with the mode on.
 *
 * So there are two separate facts, and the engine now holds both: what the camera
 * advertises, and what has actually turned up. Only the second is evidence.
 *
 * The three ways to have no map in your hand are the rest of it. They were one message
 * before, and that message named the wrong cause for two of them, which is worse than
 * saying nothing: it tells a caller to go and measure a flat field by hand when all that
 * happened is that the mode was off.
 */
public final class Shading {

    private Shading() { }

    /**
     * Whether a map can be had from this camera.
     *
     * A map that has arrived is proof. The advertised key is only a claim, but it is the
     * only thing to go on before the first frame with the mode on, so it still counts.
     */
    public static boolean available(boolean keyAdvertised, boolean seen) {
        return keyAdvertised || seen;
    }

    /**
     * Why there is no map, in the words the caller gets.
     *
     * modeOn is the mode this camera is set to now; seen is whether a map has ever arrived
     * since the camera was opened. The endpoint waits for a frame before asking, so a mode
     * that is on and a map that has never arrived is the camera refusing, not a race.
     */
    public static String absent(boolean modeOn, boolean seen, boolean keyAdvertised) {
        if (!modeOn) {
            return "the lens shading map mode is off, so no frame carried one. Ask "
                    + "/api/shadingmap and it turns the mode on for you, or set shadingmap=1 "
                    + "yourself. Nothing here says this camera cannot deliver a map.";
        }
        if (seen) {
            return "the mode is on and this camera has delivered a map since it was opened, "
                    + "but the last frame did not carry one. Ask again.";
        }
        return "the mode is on and a frame was taken with it on, and that frame carried no "
                + "map, so this camera does not deliver one"
                + (keyAdvertised
                        ? ". It advertises the map in its capture result keys nonetheless. "
                        : ", which is also what its capture result keys say. ")
                + "Measure a flat field instead.";
    }
}
