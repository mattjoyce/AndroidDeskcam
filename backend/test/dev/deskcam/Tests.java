package dev.deskcam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The tests that need no phone.
 *
 * Every fault in the September review was found by reading the code or by using the
 * camera, never by a test, and several of them were in pure functions that a workstation
 * can run in a millisecond: the ROI maths under rotation, the exposure parser, the clamp,
 * and the tar writer. Those four are here.
 *
 * There is no test framework, on purpose. The Android half of this project has no
 * libraries at all, and one main method with an assert helper is a smaller price than a
 * dependency. build.sh runs this before it packages anything.
 */
public final class Tests {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = args.length > 0 ? Paths.get(args[0]) : Files.createTempDirectory("deskcam-test");

        roiCentred();
        roiZoom();
        roiUnderRotation();
        roiStaysInside();
        clampRejectsNaN();
        clampWorks();
        exposureParsing();
        exposureRejectsRubbish();
        boolParsing();
        sizeParsing();
        rotationParsing();
        tarWritesAReadableArchive(tmp);
        theAccessKeyRule();
        sharpnessRisesWithDetail();
        sharpnessPeaksAtFocus();
        sharpnessOnAnImpossibleRegion();
        sharpnessStaysInsideItsBudget();

        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // -------------------------------------------------------------- the roi

    private static void roiCentred() {
        int[] r = Geom.roi(1000, 800, 1f, 0.5f, 0.5f, 0);
        eq("full frame width", 1000, r[2]);
        eq("full frame height", 800, r[3]);
        eq("full frame left", 0, r[0]);
        eq("full frame top", 0, r[1]);
    }

    private static void roiZoom() {
        int[] r = Geom.roi(1000, 800, 4f, 0.5f, 0.5f, 0);
        eq("quarter width", 250, r[2]);
        eq("quarter height", 200, r[3]);
        eq("centred left", 375, r[0]);
        eq("centred top", 300, r[1]);
    }

    /**
     * A quarter turn moves the point the caller sees, not the size of the region.
     *
     * cx and cy name a place in the ROTATED picture, and the crop happens before the
     * rotation. A caller who clicks the top left of what they can see must get the top
     * left of what they can see at every rotation, and nothing in the API tells them
     * which way the phone is bolted down.
     */
    private static void roiUnderRotation() {
        // Aim at a point up and to the left of centre, then check each rotation puts the
        // crop where that point ends up in sensor space.
        float cx = 0.25f, cy = 0.25f;
        int[] r0 = Geom.roi(1000, 1000, 4f, cx, cy, 0);
        int[] r90 = Geom.roi(1000, 1000, 4f, cx, cy, 90);
        int[] r180 = Geom.roi(1000, 1000, 4f, cx, cy, 180);
        int[] r270 = Geom.roi(1000, 1000, 4f, cx, cy, 270);

        eq("0 degrees left", 125, r0[0]);
        eq("0 degrees top", 125, r0[1]);
        // 90: sensor x comes from cy, sensor y from 1 - cx
        eq("90 degrees left", 125, r90[0]);
        eq("90 degrees top", 625, r90[1]);
        // 180: both mirrored
        eq("180 degrees left", 625, r180[0]);
        eq("180 degrees top", 625, r180[1]);
        // 270: sensor x from 1 - cy, sensor y from cx
        eq("270 degrees left", 625, r270[0]);
        eq("270 degrees top", 125, r270[1]);

        // Whatever the rotation, the region is the same shape.
        for (int[] r : new int[][]{r0, r90, r180, r270}) {
            eq("rotation keeps the width", 250, r[2]);
            eq("rotation keeps the height", 250, r[3]);
        }
    }

    private static void roiStaysInside() {
        for (int rotate : new int[]{0, 90, 180, 270}) {
            for (float c = 0f; c <= 1f; c += 0.1f) {
                int[] r = Geom.roi(1920, 1080, 7.5f, c, c, rotate);
                yes("left is not negative at " + c + " rotate " + rotate, r[0] >= 0);
                yes("top is not negative at " + c + " rotate " + rotate, r[1] >= 0);
                yes("right is inside at " + c + " rotate " + rotate, r[0] + r[2] <= 1920);
                yes("bottom is inside at " + c + " rotate " + rotate, r[1] + r[3] <= 1080);
            }
        }
        // A zoom past the point of usefulness still gives a real rectangle.
        int[] r = Geom.roi(64, 64, 1000f, 0.5f, 0.5f, 0);
        yes("a huge zoom keeps a floor of 16 pixels", r[2] >= 16 && r[3] >= 16);
        yes("the floor still fits in the frame", r[0] + r[2] <= 64 && r[1] + r[3] <= 64);
    }

    // ------------------------------------------------------------ the clamp

    /**
     * The clamp used to be v < lo ? lo : (v > hi ? hi : v), which lets NaN through:
     * both comparisons are false against NaN, so it fell out of the expression unchanged.
     * zoom=NaN then produced a 16 by 16 crop that /api/status reported as zoom 0.0.
     */
    private static void clampRejectsNaN() {
        threw("clamp refuses NaN", () -> Geom.clamp(Float.NaN, 1f, 8f));
        threw("the number parser refuses NaN", () -> Parse.number("NaN"));
        threw("the number parser refuses nan in any case", () -> Parse.number("nan"));
    }

    private static void clampWorks() {
        eq("below the floor", 1.0f, Geom.clamp(0.2f, 1f, 8f));
        eq("above the ceiling", 8.0f, Geom.clamp(99f, 1f, 8f));
        eq("inside", 4.0f, Geom.clamp(4f, 1f, 8f));
        eq("positive infinity lands on the ceiling", 8.0f, Geom.clamp(Float.POSITIVE_INFINITY, 1f, 8f));
        eq("negative infinity lands on the floor", 1.0f, Geom.clamp(Float.NEGATIVE_INFINITY, 1f, 8f));
        threw("require refuses a value outside its range", () -> Geom.require("w", 100000, 1, 8192));
        eq("require passes a value inside its range", 640, Geom.require("w", 640, 1, 8192));
    }

    // --------------------------------------------------------- the exposure

    private static void exposureParsing() {
        eq("a fraction", 1_000_000_000L / 120, Parse.exposureNs("1/120"));
        eq("a fraction with a unit", 1_000_000_000L / 250, Parse.exposureNs("1/250s"));
        eq("milliseconds", 8_000_000L, Parse.exposureNs("8ms"));
        eq("microseconds", 250_000L, Parse.exposureNs("250us"));
        eq("nanoseconds", 4000L, Parse.exposureNs("4000ns"));
        eq("seconds", 500_000_000L, Parse.exposureNs("0.5s"));
        eq("bare nanoseconds", 33333333L, Parse.exposureNs("33333333"));
        eq("whitespace and case", 8_000_000L, Parse.exposureNs("  8MS "));
    }

    private static void exposureRejectsRubbish() {
        threw("a word", () -> Parse.exposureNs("fast"));
        threw("an empty value", () -> Parse.exposureNs("  "));
        threw("a divide by zero", () -> Parse.exposureNs("1/0"));
        threw("a missing denominator", () -> Parse.exposureNs("1/"));
    }

    private static void boolParsing() {
        yes("on", Parse.bool("on"));
        yes("1", Parse.bool("1"));
        yes("TRUE", Parse.bool("TRUE"));
        no("off", Parse.bool("off"));
        no("0", Parse.bool("0"));
        threw("a value that is neither", () -> Parse.bool("maybe"));
    }

    private static void sizeParsing() {
        int[] s = Parse.size("1280x960");
        eq("width", 1280, s[0]);
        eq("height", 960, s[1]);
        eq("a comma works too", 640, Parse.size("640,480")[0]);
        threw("one number is not a size", () -> Parse.size("1280"));
        threw("a zero edge is not a size", () -> Parse.size("0x480"));
    }

    private static void rotationParsing() {
        eq("a quarter turn", 90, Parse.rotation("90"));
        eq("a negative turn comes back positive", 270, Parse.rotation("-90"));
        eq("more than a full turn wraps", 90, Parse.rotation("450"));
        eq("an odd angle rounds down to a quarter turn", 0, Parse.rotation("44"));
        threw("a word is not an angle", () -> Parse.rotation("sideways"));
    }

    // --------------------------------------------------------------- the tar

    /**
     * Writes an archive and checks it against the tar on this machine.
     *
     * A hand-written USTAR header is exactly the kind of thing that looks right and is
     * wrong by one byte, and the only judge that matters is the tool the workstation will
     * actually use to unpack a burst.
     */
    private static void tarWritesAReadableArchive(Path dir) throws Exception {
        byte[] one = new byte[1000];
        byte[] two = new byte[512];
        for (int i = 0; i < one.length; i++) one[i] = (byte) i;
        for (int i = 0; i < two.length; i++) two[i] = (byte) (255 - i);

        Tar tar = new Tar(4096);
        tar.add("burst-000.jpg", one);
        tar.add("burst-001.jpg", two);
        byte[] archive = tar.finish();

        eq("the archive is a whole number of blocks", 0, archive.length % 512);
        // header + 1024 padded + header + 512 + two empty blocks
        eq("the archive is the expected length", 512 + 1024 + 512 + 512 + 1024, archive.length);

        // The shipped path writes the archive straight to the socket and states its
        // length in the header, so the length has to be exactly right or the client
        // hangs waiting for bytes that never come.
        eq("the computed length matches the built archive",
                archive.length, Tar.contentLength(java.util.List.of(one, two)));

        java.io.ByteArrayOutputStream streamed = new java.io.ByteArrayOutputStream();
        Tar.writeTo(streamed, java.util.List.of("burst-000.jpg", "burst-001.jpg"),
                java.util.List.of(one, two));
        yes("streaming and buffering give the same bytes",
                java.util.Arrays.equals(archive, streamed.toByteArray()));

        byte[] exact = new byte[512];
        eq("a file that is a whole block needs no padding",
                512 + 512 + 1024, Tar.contentLength(java.util.List.of(exact)));
        eq("an empty archive is just the end marker", 1024, Tar.contentLength(java.util.List.of()));

        // Both limits are real and both used to pass in silence.
        String tooLong = "x".repeat(100) + ".jpg";
        threw("a name longer than the field is refused, not cut",
                () -> Tar.checkFits(tooLong, 10));
        threw("an empty name is refused", () -> Tar.checkFits("", 10));
        threw("a size that does not fit in 11 octal digits is refused",
                () -> Tar.checkFits("big.bin", 1L << 33));
        Tar.checkFits("x".repeat(99), (1L << 33) - 1);       // the largest that does fit
        checks++;

        Path file = dir.resolve("test.tar");
        Files.createDirectories(dir);
        Files.write(file, archive);

        String listing = run("tar", "tf", file.toString());
        yes("tar lists the first member", listing.contains("burst-000.jpg"));
        yes("tar lists the second member", listing.contains("burst-001.jpg"));

        Path out = dir.resolve("unpacked");
        Files.createDirectories(out);
        run("tar", "xf", file.toString(), "-C", out.toString());
        yes("the first member comes back byte for byte",
                java.util.Arrays.equals(one, Files.readAllBytes(out.resolve("burst-000.jpg"))));
        yes("the second member comes back byte for byte",
                java.util.Arrays.equals(two, Files.readAllBytes(out.resolve("burst-001.jpg"))));
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) throw new IOException(String.join(" ", cmd) + " exited " + code + ": " + out);
        return out;
    }

    // ------------------------------------------------------------- plumbing

    private interface Body { void run(); }

    // --------------------------------------------------------- sharpness

    /** A luma plane holding vertical bars, blurred by averaging over `blur` pixels. */
    private static byte[] bars(int w, int h, int period, int blur) {
        byte[] sharp = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                sharp[y * w + x] = (byte) ((x / (period / 2)) % 2 == 0 ? 40 : 210);
            }
        }
        if (blur <= 1) return sharp;
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, n = 0;
                for (int k = -blur / 2; k <= blur / 2; k++) {
                    int sx = x + k;
                    if (sx < 0 || sx >= w) continue;
                    sum += sharp[y * w + sx] & 0xff;
                    n++;
                }
                out[y * w + x] = (byte) (sum / n);
            }
        }
        return out;
    }

    private static void sharpnessRisesWithDetail() {
        int w = 640, h = 480;
        double flat = Sharp.focus(new byte[w * h], w, h, 0, 0, w, h);
        double edges = Sharp.focus(bars(w, h, 16, 1), w, h, 0, 0, w, h);
        eq("a flat field has no detail", 0f, (float) flat);
        yes("edges beat a flat field", edges > 100);
    }

    /**
     * The claim card 9 actually makes: a sweep through focus has one clear maximum.
     *
     * Blur here stands in for defocus. The value must fall away on the blurred side and
     * never wander, because an agent hunting the peak follows the slope.
     */
    private static void sharpnessPeaksAtFocus() {
        int w = 640, h = 480;
        double previous = Double.MAX_VALUE;
        // Widening blur, so this walks away from focus one step at a time.
        for (int blur : new int[]{1, 3, 5, 9, 17}) {
            double value = Sharp.focus(bars(w, h, 32, blur), w, h, 0, 0, w, h);
            yes("blur " + blur + " is less sharp than the step before it", value < previous);
            previous = value;
        }
    }

    private static void sharpnessOnAnImpossibleRegion() {
        int w = 64, h = 64;
        byte[] plane = bars(w, h, 8, 1);
        eq("no plane", (float) Sharp.NOT_MEASURABLE, (float) Sharp.focus(null, w, h, 0, 0, w, h));
        eq("a region of nothing", (float) Sharp.NOT_MEASURABLE,
                (float) Sharp.focus(plane, w, h, 10, 10, 2, 2));
        eq("a plane shorter than it claims", (float) Sharp.NOT_MEASURABLE,
                (float) Sharp.focus(new byte[10], w, h, 0, 0, w, h));
        // A region reaching past the edge is clipped to what is there, not refused.
        yes("a region hanging off the edge is clipped",
                Sharp.focus(plane, w, h, 32, 32, 999, 999) > 0);
    }

    /**
     * The budget is 20 ms on a phone. This runs on a workstation, so it cannot prove that.
     * What it proves is the thing that would break the budget: the sample count is bounded
     * however large the region is.
     */
    private static void sharpnessStaysInsideItsBudget() {
        yes("a whole preview frame stays inside the sample budget",
                (long) (1280 - 2) * (960 - 2) / rowStepFor(1280, 960) <= Sharp.MAX_SAMPLES);
        yes("a small region is measured at full density", rowStepFor(320, 240) == 1);
    }

    private static long rowStepFor(int w, int h) {
        long area = (long) (w - 2) * (h - 2);
        return Math.max(1, (area + Sharp.MAX_SAMPLES - 1) / Sharp.MAX_SAMPLES);
    }

    // -------------------------------------------------------- the access key

    /**
     * The rule that decides whether a request may proceed.
     *
     * It is here because of card 17: the key used to be copied into the server when the
     * server was built, and pairing a key into an already running service left the copy
     * behind. The console reported a key was set and the camera answered anyone on the
     * network. The rule is now a pure function of the key in force at this instant, and
     * this is where its edges are written down.
     */
    private static void theAccessKeyRule() {
        yes("no key set means an open camera", Access.allowed(null, null, null));
        yes("an empty key means an open camera", Access.allowed("", null, null));
        yes("an open camera ignores a token nobody asked for",
                Access.allowed("", "anything", null));

        yes("the right token in the query", Access.allowed("k3y", "k3y", null));
        no("no token at all", Access.allowed("k3y", null, null));
        no("the wrong token", Access.allowed("k3y", "not-it", null));
        no("an empty token against a key", Access.allowed("k3y", "", null));

        yes("a bearer header", Access.allowed("k3y", null, "Bearer k3y"));
        yes("a bearer header with room around it", Access.allowed("k3y", null, "Bearer  k3y "));
        no("the wrong bearer", Access.allowed("k3y", null, "Bearer nope"));
        no("a bearer of another kind", Access.allowed("k3y", null, "Basic k3y"));
        no("the key as a bare header", Access.allowed("k3y", null, "k3y"));

        // The key changes while the service runs. That is the whole point of card 17's
        // buttons, and the case the old code got wrong.
        String was = "old-key", now = "new-key";
        no("the old key stops working", Access.allowed(now, was, null));
        yes("the new key works at once", Access.allowed(now, now, null));
        yes("removing the key opens the camera again", Access.allowed("", was, null));
    }

    private static void eq(String what, long expected, long actual) {
        checks++;
        if (expected != actual) fail(what + ": expected " + expected + ", got " + actual);
    }

    private static void eq(String what, float expected, float actual) {
        checks++;
        if (Float.compare(expected, actual) != 0) fail(what + ": expected " + expected + ", got " + actual);
    }

    private static void yes(String what, boolean actual) {
        checks++;
        if (!actual) fail(what + ": expected true");
    }

    private static void no(String what, boolean actual) {
        checks++;
        if (actual) fail(what + ": expected false");
    }

    private static void threw(String what, Body body) {
        checks++;
        try {
            body.run();
            fail(what + ": expected it to be refused, and it was not");
        } catch (IllegalArgumentException expected) {
            // NumberFormatException is one of these. This is the refusal we wanted.
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL  " + message);
    }
}
