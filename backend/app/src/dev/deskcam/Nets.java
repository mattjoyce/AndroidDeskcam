package dev.deskcam;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the phone's addresses a workstation should be told about.
 *
 * A phone holds several at once: Wi-Fi, a VPN such as Tailscale, mobile data. The app used
 * to report the first address on Android's "active" network, and with a VPN up the active
 * network is the VPN, so a Pixel 9 on Tailscale showed a 100.x address to a person who
 * wanted its Wi-Fi one. Which address to hand out is a choice the person can make; this
 * orders the candidates for when they have not, and honours them when they have.
 *
 * The server still listens on every interface. This decides what the app says, not what
 * it answers, so `adb forward` and a VPN keep working whatever is chosen.
 *
 * Pure, so the workstation tests hold it. The enumeration that needs Android is in
 * CamService.
 */
final class Nets {

    private Nets() { }

    /** The preference that means "choose for me". */
    static final String AUTO = "auto";

    enum Kind { WIFI, ETHERNET, VPN, OTHER, MOBILE }

    /** One IPv4 address, the interface that holds it, and what kind of network that is. */
    static final class Choice {
        final String iface;
        final String ip;
        final Kind kind;

        Choice(String iface, String ip, Kind kind) {
            this.iface = iface == null ? "?" : iface;
            this.ip = ip;
            this.kind = kind == null ? Kind.OTHER : kind;
        }

        /** What a person reads in the list: the kind first, because that is the decision. */
        String label() {
            return name(kind) + "   " + ip + "   " + iface;
        }
    }

    static String name(Kind k) {
        switch (k) {
            case WIFI: return "Wi-Fi";
            case ETHERNET: return "Ethernet";
            case VPN: return "VPN";
            case MOBILE: return "Mobile";
            default: return "Other";
        }
    }

    /**
     * How likely a workstation on the bench is to reach this kind of address.
     *
     * The LAN first, because that is where the workstation is. A VPN next: it reaches the
     * phone from anywhere on the tailnet, which is a real use, just not the usual one.
     * Mobile last, because a carrier address is almost never reachable from a desk.
     */
    static int rank(Kind k) {
        switch (k) {
            case WIFI: return 0;
            case ETHERNET: return 1;
            case VPN: return 2;
            case OTHER: return 3;
            default: return 4;
        }
    }

    /**
     * A guess at the kind from the interface name, for when the platform will not say.
     * Only used as a fallback; the capabilities of the network are the better witness.
     */
    static Kind kindFromName(String iface) {
        if (iface == null) return Kind.OTHER;
        String n = iface.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("wlan") || n.startsWith("ap")) return Kind.WIFI;
        if (n.startsWith("eth") || n.startsWith("usb")) return Kind.ETHERNET;
        if (n.startsWith("tun") || n.startsWith("wg") || n.startsWith("ppp")
                || n.startsWith("tailscale")) return Kind.VPN;
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")) return Kind.MOBILE;
        return Kind.OTHER;
    }

    /** The candidates best first, each interface and address once. Stable within a rank. */
    static List<Choice> ordered(List<Choice> all) {
        List<Choice> out = new ArrayList<>();
        for (Choice c : all) {
            boolean seen = false;
            for (Choice o : out) {
                if (o.iface.equals(c.iface) && o.ip.equals(c.ip)) { seen = true; break; }
            }
            if (!seen) out.add(c);
        }
        out.sort((a, b) -> Integer.compare(rank(a.kind), rank(b.kind)));
        return out;
    }

    /** The candidate on the named interface, or null when that interface is not up. */
    static Choice find(List<Choice> all, String iface) {
        if (iface == null) return null;
        for (Choice c : all) {
            if (c.iface.equals(iface)) return c;
        }
        return null;
    }

    /**
     * The address to report: the chosen interface when it is up, otherwise the best one.
     * Null only when the phone holds no IPv4 address at all.
     */
    static Choice pick(List<Choice> all, String preferred) {
        if (preferred != null && !AUTO.equals(preferred)) {
            Choice chosen = find(all, preferred);
            if (chosen != null) return chosen;
        }
        List<Choice> o = ordered(all);
        return o.isEmpty() ? null : o.get(0);
    }
}
