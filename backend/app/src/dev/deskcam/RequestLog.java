package dev.deskcam;

import java.util.ArrayList;
import java.util.List;

/**
 * A short history of what the network asked for.
 *
 * The phone sits on a stand with nobody watching the workstation, so the only place to
 * see that an agent is actually reaching the camera is the phone's own screen. This keeps
 * the last few requests, and the counts that say whether anything is connected.
 *
 * A ring buffer with a fixed size. It must never grow, because it runs for hours.
 */
public final class RequestLog {

    /** Small enough to draw every second, long enough to show what just happened. */
    private static final int CAPACITY = 40;

    public static final class Entry {
        public final long at;
        public final String path;
        public final int code;
        public final long millis;
        public final int bytes;

        Entry(long at, String path, int code, long millis, int bytes) {
            this.at = at;
            this.path = path;
            this.code = code;
            this.millis = millis;
            this.bytes = bytes;
        }
    }

    private static final Entry[] ring = new Entry[CAPACITY];
    private static int next = 0;
    /** Increases on every record, so a reader can tell whether anything changed. */
    private static volatile long sequence = 0;

    private static volatile int captures = 0;
    private static volatile int errors = 0;

    private RequestLog() { }

    public static synchronized void record(String path, int code, long millis, int bytes) {
        ring[next] = new Entry(System.currentTimeMillis(), path, code, millis, bytes);
        next = (next + 1) % CAPACITY;
        sequence++;
        if (code >= 400) errors++;
        if (path.startsWith("/api/still") || path.startsWith("/api/raw")
                || path.startsWith("/api/burst")) {
            captures++;
        }
    }

    /** Newest first. */
    public static synchronized List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>(Math.min(limit, CAPACITY));
        for (int i = 0; i < CAPACITY && out.size() < limit; i++) {
            Entry e = ring[(next - 1 - i + CAPACITY * 2) % CAPACITY];
            if (e != null) out.add(e);
        }
        return out;
    }

    public static long sequence() { return sequence; }
    public static int captures() { return captures; }
    public static int errors() { return errors; }

    public static synchronized void clear() {
        java.util.Arrays.fill(ring, null);
        next = 0;
        captures = 0;
        errors = 0;
        sequence++;
    }
}
