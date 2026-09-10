package dev.deskcam;

/**
 * Who may use the camera.
 *
 * One rule, in one place, with no Android in it, so the workstation tests can run it.
 * The key it is given is the key in force at that instant and never a copy taken
 * earlier: HttpServer.Key explains what that mistake cost.
 */
final class Access {

    private Access() { }

    /**
     * Whether a request carrying these credentials may proceed under this key.
     *
     * A null or empty key means the camera is open, which is the default and has to stay
     * the default. This is a bench tool on a home network; the key is for the days it is
     * somewhere else.
     */
    static boolean allowed(String key, String supplied, String authHeader) {
        if (key == null || key.isEmpty()) return true;
        if (key.equals(supplied)) return true;
        return authHeader != null && authHeader.startsWith("Bearer ")
                && key.equals(authHeader.substring(7).trim());
    }
}
