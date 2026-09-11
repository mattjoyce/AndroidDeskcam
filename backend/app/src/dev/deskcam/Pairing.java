package dev.deskcam;

/**
 * Whether a pairing link may be acted on, and what it will do. Card 64.
 *
 * A deskcam:// link opens this app from a QR code, from a web page, or from any other app,
 * and it used to be applied the moment it arrived: it set or cleared the access key,
 * started the camera, and sent this phone's address to whatever callback it named, a host
 * on the internet included. Now a callback that is not a console's pairing route on a
 * private address is refused outright, and anything else is shown to the person, who
 * accepts or cancels.
 *
 * Pure, so the workstation tests hold it.
 */
final class Pairing {

    private Pairing() { }

    /**
     * Why this callback is refused, or null when it is a console's pairing route on a
     * private address.
     *
     * The console always builds the same shape, http://ADDRESS:PORT/p/CODE, and nothing
     * else is accepted, so a link cannot aim the phone's report at some other page even on
     * the local network.
     */
    static String refuse(String cb) {
        if (cb == null || cb.isEmpty()) return "the link has no callback address";
        java.net.URI u;
        try {
            u = new java.net.URI(cb);
        } catch (java.net.URISyntaxException e) {
            return "the callback is not an address";
        }
        if (!"http".equals(u.getScheme())) return "the callback is not the plain http a console uses";
        if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
            return "the callback carries more than a pairing code";
        }
        String host = u.getHost();
        if (host == null || !privateIpv4(host)) {
            return "the callback " + (host == null ? "" : host + " ") + "is not a private address";
        }
        if (u.getPort() < 1 || u.getPort() > 65535) return "the callback names no port";
        String path = u.getRawPath();
        if (path == null || !path.matches("/p/[A-Za-z0-9_-]{8,128}")) {
            return "the callback is not a console's pairing code";
        }
        return null;
    }

    /**
     * The address ranges a console on the bench can have: the three private blocks, and
     * 100.64.0.0/10, the shared range where Tailscale puts its machines.
     */
    static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("\\d{1,3}")) return false;
            o[i] = Integer.parseInt(parts[i]);
            if (o[i] > 255) return false;
        }
        return o[0] == 10
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 100 && o[1] >= 64 && o[1] <= 127);
    }

    /**
     * What accepting the link does to the access key, in the words the dialog uses. An
     * absent token leaves the key alone; an empty one removes it, which is a different
     * instruction and has to stay distinguishable.
     */
    static String keyEffect(String token) {
        if (token == null) return "leave the access key as it is";
        if (token.isEmpty()) return "remove the access key, which leaves the camera open to the network";
        return "set a new access key";
    }
}
