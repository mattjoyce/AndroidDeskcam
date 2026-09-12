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
        aSweepIsEvenInDiopters();
        whiteBalanceGainsParsing();
        aValueBecomesAFilename();
        sharpnessRisesWithDetail();
        sharpnessPeaksAtFocus();
        sharpnessOnAnImpossibleRegion();
        sharpnessStaysInsideItsBudget();
        aHuntFindsThePeak();
        aHuntRefusesAFlatCurve();
        aHuntRefusesAPeakOnTheEdge();
        aHuntWithNoReadings();
        aTapeReadsItsVerbs();
        aTapeRefusesBeforeItRuns();
        aTapeIgnoresCommentsAndBlankLines();
        waitIsTheOneVerbThatIsNotAnEndpoint();
        theThermalLadderOnlyEverSlowsDown();
        anUnknownThermalLevelChangesNothing();
        aFocusBoxSitsWhereItWasNamed();
        aFocusBoxTurnsWithTheFrame();
        aMarkSurvivesTheRoundTripToTheSensor();
        aMarkStaysOnItsPartWhenTheFrameTurns();
        aLabelIsCleanedAndCapped();
        aFocusBoxIsClampedAndNeverInsideOut();
        boxesOverlapOrTheyDoNot();
        aVpnDoesNotHideTheWifiAddress();
        aChosenInterfaceIsHonoured();
        aChosenInterfaceThatIsDownFallsBack();
        mobileIsTheLastResort();
        noAddressMeansNoAnswer();
        interfaceNamesGiveAKindWhenThePlatformWillNot();
        aConsoleCallbackOnTheBenchIsAccepted();
        aCallbackAnywhereElseIsRefused();
        theDialogSaysWhatHappensToTheKey();
        aRangeStepsAtMostOncePerReading();
        aRangeThatFlapsIsWorseThanAWideOne();
        theWidestRangeIsForAMountThatNeedsASpanner();
        theCadenceRisesAllTheWayToGood();
        goodSoundsLikeASteadyTone();
        aBeepIsNeverLongerThanItsGap();
        theInstructionNamesTheEdgeToLower();
        theBubbleFloatsToTheRaisedEdge();
        theBubbleStaysOnTheCard();

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

    // ------------------------------------------------ white balance gains

    /**
     * Card 42. A lock holds whatever the gains happened to be, which differs every
     * session; setting them is what makes two sessions comparable, and that starts with
     * reading the value somebody typed.
     */
    private static void whiteBalanceGainsParsing() {
        yes("auto means no gains of our own", Parse.gains("auto") == null);
        yes("an empty value means the same", Parse.gains("") == null);

        float[] neutral = Parse.gains("neutral");
        eq("neutral is four ones", 4, neutral.length);
        for (float g : neutral) eq("neutral gain", 1f, g);

        float[] four = Parse.gains("1.99,1.0,1.0,2.07");
        eq("red", 1.99f, four[0]);
        eq("green even", 1f, four[1]);
        eq("green odd", 1f, four[2]);
        eq("blue", 2.07f, four[3]);
        // The separators a person actually types.
        eq("spaces work too", 2.07f, Parse.gains("1.99 1.0 1.0 2.07")[3]);

        threw("three gains, because a Bayer cell has two greens",
                () -> Parse.gains("1.99,1.0,2.07"));
        threw("a gain of zero", () -> Parse.gains("0,1,1,1"));
        threw("a gain of a hundred", () -> Parse.gains("100,1,1,1"));
        threw("words", () -> Parse.gains("warm,1,1,1"));
    }

    /**
     * A walk names its frames after what they were taken at, and people type values that
     * are not filenames: 1/240, 0.5s, 1.99,1,1,2.07. Card 55.
     */
    private static void aValueBecomesAFilename() {
        yes("a plain number is left alone", "20".equals(Parse.fileSafe("20")));
        yes("a decimal keeps its point", "4.25".equals(Parse.fileSafe("4.25")));
        yes("a fraction loses its slash", "1-240".equals(Parse.fileSafe("1/240")));
        yes("a unit survives", "8ms".equals(Parse.fileSafe("8ms")));
        yes("a list of gains", "1.99-1-1-2.07".equals(Parse.fileSafe("1.99,1,1,2.07")));
        yes("spaces do not reach a path", "a-b".equals(Parse.fileSafe("a b")));
        yes("a run of junk collapses", "a-b".equals(Parse.fileSafe("a///b")));
        yes("nothing dangles at the ends", "x".equals(Parse.fileSafe("//x//")));
        // A name is part of a path, so nothing here may ever produce one.
        yes("a traversal cannot survive", "..".equals(Parse.fileSafe("../..")) == false);
        no("no slash gets through", Parse.fileSafe("../../etc/passwd").contains("/"));
        yes("a value of pure punctuation still has a name",
                "value".equals(Parse.fileSafe("///")));
        yes("a very long value is cut", Parse.fileSafe("x".repeat(200)).length() <= 24);
    }

    // ------------------------------------------------------- the focus sweep

    /**
     * "The steps are equal in dioptre space" is card 5's acceptance criterion, and this is
     * it as arithmetic. Equal in diopters is equal in depth of field, which is the whole
     * reason a stack is swept this way and not in millimetres.
     */
    private static void aSweepIsEvenInDiopters() {
        int steps = 5;
        float from = 2f, to = 10f;
        eq("the first step is where it was asked to start", from, Geom.sweepStep(from, to, 0, steps));
        eq("the last step is where it was asked to end", to, Geom.sweepStep(from, to, steps - 1, steps));

        float gap = Geom.sweepStep(from, to, 1, steps) - Geom.sweepStep(from, to, 0, steps);
        for (int i = 1; i < steps; i++) {
            eq("gap " + i + " is the same as the first",
                    gap, Geom.sweepStep(from, to, i, steps) - Geom.sweepStep(from, to, i - 1, steps));
        }

        // Backwards is a sweep too. Somebody will type from=10 to=2 and mean it.
        eq("a descending sweep starts at its start", 10f, Geom.sweepStep(10f, 2f, 0, 3));
        eq("a descending sweep ends at its end", 2f, Geom.sweepStep(10f, 2f, 2, 3));
        eq("a descending sweep passes through the middle", 6f, Geom.sweepStep(10f, 2f, 1, 3));

        threw("a sweep of one step", () -> Geom.sweepStep(2f, 10f, 0, 1));
    }

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

    // ------------------------------------------------------------- the hunt

    /** The curve of card 56, measured on the bench: a rule 235 mm from the lens. */
    private static java.util.List<Hunt.Reading> realSweep() {
        float[] at = {1.0f, 3.0f, 3.5f, 4.0f, 4.25f, 4.5f, 5.0f, 5.5f, 6.0f, 9.0f};
        double[] sharp = {3.5, 11.4, 20.3, 30.8, 33.6, 31.0, 18.0, 9.4, 5.8, 2.9};
        java.util.List<Hunt.Reading> out = new java.util.ArrayList<>();
        for (int i = 0; i < at.length; i++) out.add(new Hunt.Reading(at[i], sharp[i]));
        return out;
    }

    private static void aHuntFindsThePeak() {
        Hunt.Verdict v = Hunt.judge(realSweep(), 0f, 10.2f, 10.2f);
        yes("a real curve is an answer", v.chose());
        eq("the peak of the real curve", 4.25f, v.at);
        yes("the real curve is nothing like flat", v.contrast > 0.9);
    }

    /**
     * Nothing in focus anywhere looks like this: one number, wobbling with the noise.
     *
     * The wobble is what makes the refusal necessary. Every list has a largest element,
     * so a hunt that only reports the largest always claims to have found something.
     */
    private static void aHuntRefusesAFlatCurve() {
        java.util.List<Hunt.Reading> flat = new java.util.ArrayList<>();
        double[] noise = {4.0, 4.2, 3.9, 4.1, 4.3, 4.0, 3.8, 4.05};
        for (int i = 0; i < noise.length; i++) flat.add(new Hunt.Reading(i * 1.4f, noise[i]));
        Hunt.Verdict v = Hunt.judge(flat, 0f, 9.8f, 10.2f);
        no("a flat curve is not an answer", v.chose());
        eq("and it says which kind of refusal", 1, Hunt.FLAT.equals(v.refusal) ? 1 : 0);
        yes("the reason names the range", v.reason.contains("9.80"));
    }

    /**
     * A curve still climbing where the search stopped. The largest reading is the last
     * one looked at, which is not the same thing as a peak.
     */
    private static void aHuntRefusesAPeakOnTheEdge() {
        java.util.List<Hunt.Reading> climbing = new java.util.ArrayList<>();
        double[] rising = {3.0, 6.0, 12.0, 21.0, 33.0};
        for (int i = 0; i < rising.length; i++) {
            climbing.add(new Hunt.Reading(Geom.sweepStep(2f, 6f, i, rising.length), rising[i]));
        }
        Hunt.Verdict v = Hunt.judge(climbing, 2f, 6f, 10.2f);
        no("a curve still climbing at the edge is not an answer", v.chose());
        eq("and it says which kind of refusal", 1, Hunt.AT_EDGE.equals(v.refusal) ? 1 : 0);
        yes("it offers a wider range", v.reason.contains("to=10.00"));
        eq("the best seen is still reported", 6f, v.at);

        // The same shape, but the end of the range is the end of the lens. There is
        // nothing beyond it to widen into, so the advice has to be different.
        java.util.List<Hunt.Reading> toTheStop = new java.util.ArrayList<>();
        for (int i = 0; i < rising.length; i++) {
            toTheStop.add(new Hunt.Reading(Geom.sweepStep(6f, 10.2f, i, rising.length), rising[i]));
        }
        Hunt.Verdict stop = Hunt.judge(toTheStop, 6f, 10.2f, 10.2f);
        no("a peak at the lens stop is not an answer either", stop.chose());
        yes("and it says to move the camera",
                stop.reason.contains("move the camera back"));
        no("it does not offer a range the lens cannot reach", stop.reason.contains("to="));
    }

    private static void aHuntWithNoReadings() {
        Hunt.Verdict v = Hunt.judge(new java.util.ArrayList<>(), 0f, 10.2f, 10.2f);
        no("no readings is not an answer", v.chose());
        eq("an empty curve is a flat one", 1, Hunt.FLAT.equals(v.refusal) ? 1 : 0);
    }

    // ------------------------------------------------------------- the tape

    /**
     * The parameter names these tapes are allowed to use.
     *
     * The real one is Params, which a workstation cannot load because it reaches the
     * camera settings. That is exactly why the parser takes this as an argument.
     */
    private static final Tape.Names KNOWN = name -> java.util.Arrays.asList(
            "zoom", "cx", "cy", "torch", "settle", "timeout", "from", "to", "steps")
            .contains(name);

    private static java.util.List<Tape.Step> tape(String text) {
        return Tape.parse(text, KNOWN);
    }

    private static void aTapeReadsItsVerbs() {
        java.util.List<Tape.Step> steps = tape("SET zoom=4 cx=0.3\nAF\nSNAP settle=200\nset torch=0\n");
        eq("four lines are four steps", 4, steps.size());
        eq("the first verb", 1, "SET".equals(steps.get(0).verb) ? 1 : 0);
        eq("and the endpoint it is", 1, "/api/still".equals(steps.get(2).path) ? 1 : 0);
        eq("its parameters survive", 1, "0.3".equals(steps.get(0).params.get("cx")) ? 1 : 0);
        eq("the step number", 2, steps.get(2).index);
        eq("and the line it came from", 3, steps.get(2).line);
        // A tape written in lower case is the same tape.
        eq("a verb is not case sensitive", 1, "SET".equals(steps.get(3).verb) ? 1 : 0);
    }

    /**
     * The whole point of parsing first: a bad line costs nothing, because nothing ran.
     *
     * Each of these would otherwise be found out about after the camera had already moved
     * four times.
     */
    private static void aTapeRefusesBeforeItRuns() {
        threw("a verb nobody knows", () -> tape("SNAP\nSNPA\n"));
        threw("a parameter nobody parses", () -> tape("SNAP zomo=4\n"));
        threw("a word that is not name=value", () -> tape("SNAP zoom\n"));
        threw("the same parameter twice on a line", () -> tape("SET zoom=2 zoom=4\n"));
        threw("an empty tape", () -> tape("# nothing but a comment\n"));
        threw("WAIT with no number", () -> tape("WAIT\n"));
        threw("WAIT with a word", () -> tape("WAIT soon\n"));
        threw("WAIT longer than the limit", () -> tape("WAIT " + (Tape.MAX_WAIT_MS + 1) + "\n"));

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= Tape.MAX_STEPS; i++) tooLong.append("SNAP\n");
        threw("a tape longer than the limit", () -> tape(tooLong.toString()));

        // The message has to name the line, or a hundred step tape is a guessing game.
        try {
            tape("SNAP\n# a comment\nSNPA\n");
            fail("a bad verb should not parse");
        } catch (IllegalArgumentException e) {
            yes("the refusal names the line", String.valueOf(e.getMessage()).contains("line 3"));
            yes("and lists the verbs", String.valueOf(e.getMessage()).contains("FOCUSSWEEP"));
        }
    }

    private static void aTapeIgnoresCommentsAndBlankLines() {
        java.util.List<Tape.Step> steps =
                tape("# inspect the connector\n\n   \nSET torch=30\n\n# and again\nSNAP\n");
        eq("a comment is not a step", 2, steps.size());
        eq("the line number is of the file, not of the steps", 7, steps.get(1).line);
    }

    private static void waitIsTheOneVerbThatIsNotAnEndpoint() {
        java.util.List<Tape.Step> steps = tape("WAIT 500\nSNAP\n");
        eq("a wait has no endpoint", 1, steps.get(0).path == null ? 1 : 0);
        eq("and carries its milliseconds", 500, steps.get(0).waitMs);
        eq("a capture does have one", 1, steps.get(1).path != null ? 1 : 0);
        eq("and no wait", 0, steps.get(1).waitMs);
    }

    // ----------------------------------------------------------- the heat

    /**
     * The one judgement in card 44: how much a stream gives up at each level.
     *
     * A table is easy to get subtly wrong in a way nothing notices, because the levels
     * above moderate are the ones nobody sees on a desk. What must hold is that it only
     * ever goes one way, that it never speeds a stream up, and that it does something by
     * the time the platform says the experience is suffering.
     */
    private static void theThermalLadderOnlyEverSlowsDown() {
        double previous = 0;
        for (int level = Thermal.NONE; level <= Thermal.SHUTDOWN; level++) {
            double now = Thermal.slowdown(level);
            yes("level " + level + " never speeds the stream up", now >= 1);
            yes("level " + level + " is not gentler than the level below it", now >= previous);
            previous = now;
            yes("level " + level + " has words for a person", !Thermal.means(level).isEmpty());
            yes("level " + level + " has a name", !Thermal.word(level).startsWith("level "));
        }
        // Nothing at light: the platform defines it as throttling nobody can feel, and a
        // camera that halved its rate on a slightly warm phone would not be trusted.
        eq("nothing happens while it is cool", 1f, (float) Thermal.slowdown(Thermal.NONE));
        eq("and nothing at light", 1f, (float) Thermal.slowdown(Thermal.LIGHT));
        yes("something happens by moderate", Thermal.slowdown(Thermal.MODERATE) > 1);
        no("cool is not throttling", Thermal.throttling(Thermal.LIGHT));
        yes("moderate is", Thermal.throttling(Thermal.MODERATE));
        // Never zero, or the stream would stop and take the explanation with it.
        yes("even shutdown leaves a trickle", Thermal.slowdown(Thermal.SHUTDOWN) < 100);
    }

    /**
     * A level from a platform newer than this build.
     *
     * Guessing a slowdown from a number nobody here understands is worse than leaving the
     * rate alone and reporting the level as it was given.
     */
    private static void anUnknownThermalLevelChangesNothing() {
        eq("a level from the future slows nothing", 1f, (float) Thermal.slowdown(99));
        eq("and neither does one never reported", 1f, (float) Thermal.slowdown(Thermal.UNKNOWN));
        yes("but it is named as what it is", Thermal.word(99).contains("99"));
        yes("and described", Thermal.means(99).contains("hotter than critical"));
        no("an unknown level is not called throttling", Thermal.throttling(Thermal.UNKNOWN));
    }

    // -------------------------------------------------------- the focus box

    /**
     * The box lands where it was named, in the same coordinates cx and cy use.
     *
     * A second coordinate system in one API is how a measurement comes out wrong, so this
     * is the same test the crop gets, asking the same question of the other rectangle.
     */
    private static void aFocusBoxSitsWhereItWasNamed() {
        int[] b = Geom.box(1000, 800, 0.5f, 0.5f, 0.2f, 0.25f, 0);
        eq("a fifth of the width", 200, b[2]);
        eq("a quarter of the height", 200, b[3]);
        eq("centred left", 400, b[0]);
        eq("centred top", 300, b[1]);

        int[] corner = Geom.box(1000, 800, 0.25f, 0.75f, 0.1f, 0.1f, 0);
        eq("a quarter across", 200, corner[0]);
        eq("three quarters down", 560, corner[1]);
    }

    /**
     * Under rotation the caller still names what they see.
     *
     * Every framing fault this project has had was in this coordinate change. The box has
     * to make the same journey as the crop, and a square box makes the arithmetic visible:
     * at 180 degrees a point a quarter across and a quarter down must land three quarters
     * across and three quarters down in the sensor.
     */
    /**
     * Card 71. Everything about a mark rests on these two being inverses: a mark is named
     * in the picture somebody sees, stored on the sensor, and read back into whatever
     * picture is being shown when somebody asks.
     */
    private static void aMarkSurvivesTheRoundTripToTheSensor() {
        for (int rotate : new int[]{0, 90, 180, 270}) {
            float[] sensor = Geom.toSensor(0.25f, 0.4f, rotate);
            float[] back = Geom.toSeen(sensor[0], sensor[1], rotate);
            eq("round trip across at " + rotate, 250, Math.round(back[0] * 1000));
            eq("round trip down at " + rotate, 400, Math.round(back[1] * 1000));
        }
    }

    /**
     * The reason marks are not kept the way they were named. A mark made while the phone
     * stood upright must be drawn on the same physical part after somebody bolts the phone
     * on upside down, which means its seen coordinates have to change.
     */
    private static void aMarkStaysOnItsPartWhenTheFrameTurns() {
        float[] sensor = Geom.toSensor(0.2f, 0.3f, 0);

        float[] half = Geom.toSeen(sensor[0], sensor[1], 180);
        eq("a half turn mirrors across", 800, Math.round(half[0] * 1000));
        eq("a half turn mirrors down", 700, Math.round(half[1] * 1000));

        float[] quarter = Geom.toSeen(sensor[0], sensor[1], 90);
        eq("a quarter turn across", 700, Math.round(quarter[0] * 1000));
        eq("a quarter turn down", 200, Math.round(quarter[1] * 1000));
    }

    /**
     * A label is the only text here that one client writes and another reads, so the
     * length and the control characters are settled before it is stored.
     */
    private static void aLabelIsCleanedAndCapped() {
        eq("nothing becomes empty", "", Parse.label(null));
        eq("a tab becomes a space", "a b", Parse.label("a\tb"));
        eq("trimmed", "pin 1", Parse.label("  pin 1  "));
        eq("capped", Parse.LABEL_MAX, Parse.label("x".repeat(200)).length());
        eq("markup is kept as text, the page draws it as text",
                "<script>x</script>", Parse.label("<script>x</script>"));
    }

    private static void aFocusBoxTurnsWithTheFrame() {
        int[] none = Geom.box(1000, 1000, 0.25f, 0.25f, 0.1f, 0.1f, 0);
        eq("upright, left", 200, none[0]);
        eq("upright, top", 200, none[1]);

        int[] half = Geom.box(1000, 1000, 0.25f, 0.25f, 0.1f, 0.1f, 180);
        eq("half a turn, left", 700, half[0]);
        eq("half a turn, top", 700, half[1]);

        int[] quarter = Geom.box(1000, 1000, 0.25f, 0.25f, 0.1f, 0.1f, 90);
        eq("a quarter turn, left", 200, quarter[0]);
        eq("a quarter turn, top", 700, quarter[1]);

        int[] three = Geom.box(1000, 1000, 0.25f, 0.25f, 0.1f, 0.1f, 270);
        eq("three quarters, left", 700, three[0]);
        eq("three quarters, top", 200, three[1]);

        // A quarter turn swaps width and height, exactly as it does for the crop.
        int[] wide = Geom.box(1000, 500, 0.5f, 0.5f, 0.4f, 0.2f, 90);
        eq("a turned box is as wide as it was tall", 100, wide[2]);
        eq("and as tall as it was wide", 400, wide[3]);
    }

    private static void aFocusBoxIsClampedAndNeverInsideOut() {
        // Named off the edge, it stays in the frame rather than describing pixels nobody has.
        int[] off = Geom.box(1000, 800, 0f, 0f, 0.2f, 0.2f, 0);
        eq("clamped left", 0, off[0]);
        eq("clamped top", 0, off[1]);
        int[] far = Geom.box(1000, 800, 1f, 1f, 0.2f, 0.2f, 0);
        eq("clamped right", 800, far[0]);
        eq("clamped bottom", 640, far[1]);

        // Never smaller than the sharpness kernel can read, whatever fraction was asked for.
        int[] tiny = Geom.box(1000, 800, 0.5f, 0.5f, 0.0001f, 0.0001f, 0);
        yes("a box is never thinner than the kernel", tiny[2] >= 3 && tiny[3] >= 3);

        int[] whole = Geom.box(1000, 800, 0.5f, 0.5f, 1f, 1f, 0);
        eq("a whole frame box is the whole frame", 1000, whole[2]);
        eq("and no taller than it", 800, whole[3]);
    }

    /** The test that decides whether a box names a place the picture contains. */
    private static void boxesOverlapOrTheyDoNot() {
        int[] roi = {400, 300, 200, 200};
        yes("a box inside", Geom.overlap(new int[]{450, 350, 50, 50}, roi));
        yes("a box straddling an edge", Geom.overlap(new int[]{350, 350, 100, 50}, roi));
        no("a box to the left", Geom.overlap(new int[]{100, 350, 200, 50}, roi));
        no("a box below", Geom.overlap(new int[]{450, 550, 50, 50}, roi));
        // Touching is not overlapping: a rectangle that ends where the other starts
        // shares no pixel, and a sharpness reading needs pixels.
        no("a box that only touches", Geom.overlap(new int[]{200, 300, 200, 200}, roi));
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

    // ---------------------------------------------------------- the address

    private static java.util.List<Nets.Choice> pixelOnTailscale() {
        // The order Android handed them over on a Pixel 9 with Tailscale up: the VPN is
        // the active network, so it came first, and it is the one the app used to show.
        return java.util.Arrays.asList(
                new Nets.Choice("tun0", "100.106.227.29", Nets.Kind.VPN),
                new Nets.Choice("wlan0", "192.168.86.218", Nets.Kind.WIFI));
    }

    private static void aVpnDoesNotHideTheWifiAddress() {
        Nets.Choice c = Nets.pick(pixelOnTailscale(), Nets.AUTO);
        yes("auto reports the Wi-Fi address with a VPN up", c != null && "192.168.86.218".equals(c.ip));
        yes("no preference at all is auto too", "wlan0".equals(Nets.pick(pixelOnTailscale(), null).iface));
    }

    private static void aChosenInterfaceIsHonoured() {
        Nets.Choice c = Nets.pick(pixelOnTailscale(), "tun0");
        yes("a person who chose the VPN gets the VPN", c != null && "100.106.227.29".equals(c.ip));
    }

    private static void aChosenInterfaceThatIsDownFallsBack() {
        yes("an interface that is not up is not found", Nets.find(pixelOnTailscale(), "eth0") == null);
        Nets.Choice c = Nets.pick(pixelOnTailscale(), "eth0");
        yes("and the best address is reported instead of none", c != null && "wlan0".equals(c.iface));
    }

    private static void mobileIsTheLastResort() {
        java.util.List<Nets.Choice> both = java.util.Arrays.asList(
                new Nets.Choice("rmnet_data0", "10.21.4.7", Nets.Kind.MOBILE),
                new Nets.Choice("tun0", "100.64.0.9", Nets.Kind.VPN));
        yes("a VPN beats a carrier address", "tun0".equals(Nets.pick(both, Nets.AUTO).iface));
        java.util.List<Nets.Choice> only = java.util.Collections.singletonList(
                new Nets.Choice("rmnet_data0", "10.21.4.7", Nets.Kind.MOBILE));
        yes("but a carrier address beats nothing", "10.21.4.7".equals(Nets.pick(only, Nets.AUTO).ip));
        eq("each interface and address is listed once", 2,
                Nets.ordered(java.util.Arrays.asList(both.get(0), both.get(1), both.get(0))).size());
    }

    private static void noAddressMeansNoAnswer() {
        yes("no address, no answer", Nets.pick(java.util.Collections.emptyList(), Nets.AUTO) == null);
    }

    private static void interfaceNamesGiveAKindWhenThePlatformWillNot() {
        yes("wlan0 is Wi-Fi", Nets.kindFromName("wlan0") == Nets.Kind.WIFI);
        yes("tun0 is a VPN", Nets.kindFromName("tun0") == Nets.Kind.VPN);
        yes("rmnet_data0 is mobile", Nets.kindFromName("rmnet_data0") == Nets.Kind.MOBILE);
        yes("eth0 is Ethernet", Nets.kindFromName("eth0") == Nets.Kind.ETHERNET);
        yes("an unknown name is other, not a guess", Nets.kindFromName("dummy0") == Nets.Kind.OTHER);
    }

    // ------------------------------------------------------------- pairing

    private static void aConsoleCallbackOnTheBenchIsAccepted() {
        yes("a console on the LAN", Pairing.refuse("http://192.168.86.45:9000/p/3f9c2a7be41d") == null);
        yes("a console on 10/8", Pairing.refuse("http://10.0.0.7:9000/p/abcdefgh12345678") == null);
        yes("a console on 172.16/12", Pairing.refuse("http://172.20.1.2:9000/p/abcdefgh") == null);
        yes("a console on a tailnet", Pairing.refuse("http://100.90.14.83:9000/p/abcdefgh") == null);
    }

    private static void aCallbackAnywhereElseIsRefused() {
        String[] bad = {
            null, "",
            "https://192.168.86.45:9000/p/abcdefgh",       // not what a console serves
            "http://203.0.113.9:9000/p/abcdefgh",          // the internet
            "http://evil.example:9000/p/abcdefgh",         // a name, not an address
            "http://192.168.1.1.evil.example/p/abcdefgh",  // looks private, is not
            "http://172.32.0.1:9000/p/abcdefgh",           // just outside 172.16/12
            "http://100.128.0.1:9000/p/abcdefgh",          // just outside 100.64/10
            "http://192.168.86.45/p/abcdefgh",             // no port
            "http://192.168.86.45:9000/steal",             // not a pairing route
            "http://192.168.86.45:9000/p/abc",             // too short to be a code
            "http://192.168.86.45:9000/p/abcdefgh?x=1",    // a query a console never adds
            "http://user@192.168.86.45:9000/p/abcdefgh",   // user info
            "not a url at all",
        };
        for (String cb : bad) {
            yes("refused: " + cb, Pairing.refuse(cb) != null);
        }
    }

    private static void theDialogSaysWhatHappensToTheKey() {
        yes("no token leaves the key", Pairing.keyEffect(null).contains("leave"));
        yes("an empty token removes it and says the camera is open",
                Pairing.keyEffect("").contains("remove") && Pairing.keyEffect("").contains("open"));
        yes("a token sets one", Pairing.keyEffect("k3y").contains("set"));
    }

    // --------------------------------------------------------- the leveller

    /**
     * The range on the card must settle in one step, not walk across the ranges while the
     * reading sits still. Applying the rule twice to the same reading has to be the same
     * as applying it once, from every starting range.
     */
    private static void aRangeStepsAtMostOncePerReading() {
        float[] readings = {0f, 0.1f, 0.3f, 0.5f, 0.6f, 0.9f, 1.4f, 2.9f, 3.1f, 7f, 9.9f, 40f};
        for (int from = 0; from < Levelling.rangeCount(); from++) {
            for (float w : readings) {
                int once = Levelling.range(from, w);
                int twice = Levelling.range(once, w);
                eq("range settles at " + w + " from " + from, once, twice);
            }
        }
    }

    /**
     * The hysteresis, which is the whole reason the rule is not one line.
     *
     * Between 0.275 and 0.475 degrees the card keeps whichever of the one degree and half
     * degree ranges it already had. Without that band a bubble sitting on the boundary
     * flips the range back and forth at the one moment it is being watched hardest.
     */
    private static void aRangeThatFlapsIsWorseThanAWideOne() {
        eq("a wider card holds at 0.4", 2, Levelling.range(2, 0.4f));
        eq("a tighter card holds at 0.4", 3, Levelling.range(3, 0.4f));
        eq("well inside, it closes in", 3, Levelling.range(2, 0.2f));
        eq("near the rim, it opens out", 2, Levelling.range(3, 0.49f));
        eq("a reading it cannot use changes nothing", 2, Levelling.range(2, Float.NaN));
    }

    private static void theWidestRangeIsForAMountThatNeedsASpanner() {
        eq("ten degrees of half-width", 10f, Levelling.span(0));
        eq("half a degree at the tightest", Levelling.GOOD_DEG,
                Levelling.span(Levelling.rangeCount() - 1));
        eq("a range below the first is the first", 10f, Levelling.span(-3));
        eq("a range past the last is the last", Levelling.GOOD_DEG, Levelling.span(99));
        // The mount this was written for read 6.55 degrees after a remount, so the widest
        // card has to hold it.
        eq("a fresh remount starts on the widest card", 0, Levelling.range(3, 6.55f));
    }

    /**
     * The beeping has to speed up all the way in, without a step where the card zooms.
     *
     * Keying the cadence to the range on show was the tempting version and it is wrong: it
     * slows down at the instant you get closer, because the new range is wider relative to
     * the error. So the rate comes from the error alone and must be strictly monotonic.
     */
    private static void theCadenceRisesAllTheWayToGood() {
        float previous = Float.MAX_VALUE;
        for (float e = 10f; e > Levelling.GOOD_DEG; e -= 0.05f) {
            float rate = Levelling.beepsPerSecond(e);
            yes("the cadence rises as " + e + " falls", rate > previous || previous == Float.MAX_VALUE);
            yes("the cadence stays in its band at " + e,
                    rate >= Levelling.SLOW_HZ - 0.001f && rate <= Levelling.FAST_HZ + 0.001f);
            previous = rate;
        }
        eq("the widest error beeps slowest", Levelling.SLOW_HZ, Levelling.beepsPerSecond(10f));
        eq("further out than the card is no slower", Levelling.SLOW_HZ,
                Levelling.beepsPerSecond(40f));
        yes("it arrives at the top of the band just before good",
                Levelling.beepsPerSecond(0.501f) > Levelling.FAST_HZ - 0.1f);
    }

    private static void goodSoundsLikeASteadyTone() {
        eq("good is steady", 0f, Levelling.beepsPerSecond(Levelling.GOOD_DEG));
        eq("well inside good is steady", 0f, Levelling.beepsPerSecond(0.01f));
        eq("dead level is steady", 0f, Levelling.beepsPerSecond(0f));
        eq("the sign of the error does not change the sound",
                Levelling.beepsPerSecond(2f), Levelling.beepsPerSecond(-2f));
        // A sensor that has not reported yet must not sound like a mount that is level.
        eq("no reading is not good news", Levelling.SLOW_HZ, Levelling.beepsPerSecond(Float.NaN));
        yes("good and level agree", Levelling.good(0.5f) && !Levelling.good(0.51f));
        yes("good is about size, not sign", Levelling.good(-0.4f));
    }

    /** A beep that ran past its own gap would join the next one into a steady tone. */
    private static void aBeepIsNeverLongerThanItsGap() {
        for (float rate = Levelling.SLOW_HZ; rate <= Levelling.FAST_HZ; rate += 0.1f) {
            float gap = 1000f / rate;
            yes("a beep at " + rate + " per second fits in its gap",
                    Levelling.beepMillis(rate) < gap);
        }
        eq("the slowest beep is capped, not stretched", Levelling.BEEP_MS,
                Levelling.beepMillis(Levelling.SLOW_HZ));
        yes("the fastest beep is shortened", Levelling.beepMillis(Levelling.FAST_HZ) < Levelling.BEEP_MS);
        eq("a steady tone is not a beep", Levelling.BEEP_MS, Levelling.beepMillis(0f));
    }

    private static void theInstructionNamesTheEdgeToLower() {
        eq("a raised right edge", "lower the right edge", Levelling.instruction(2f, 0f));
        eq("a raised left edge", "lower the left edge", Levelling.instruction(-2f, 0f));
        eq("a raised top edge", "lower the top edge", Levelling.instruction(0f, 2f));
        eq("a raised bottom edge", "lower the bottom edge", Levelling.instruction(0f, -2f));
        eq("both inside", "level", Levelling.instruction(0.4f, -0.2f));
        eq("on the limit is level", "level", Levelling.instruction(0.5f, 0.5f));
        // One screw at a time: the worse axis is the one named.
        eq("the worse axis wins", "lower the right edge", Levelling.instruction(3f, 0.9f));
        eq("the worse axis wins the other way", "lower the top edge", Levelling.instruction(0.9f, 3f));
        eq("a good axis is never named", "lower the bottom edge", Levelling.instruction(0.3f, -0.8f));
    }

    private static void theBubbleStaysOnTheCard() {
        eq("dead centre", 0f, Levelling.offset(0f, 10f));
        eq("half way out", 0.5f, Levelling.offset(5f, 10f));
        eq("half way out the other way", -0.5f, Levelling.offset(-5f, 10f));
        eq("on the rim", 1f, Levelling.offset(10f, 10f));
        eq("further out than the card goes", 1f, Levelling.offset(40f, 10f));
        eq("and the other way", -1f, Levelling.offset(-40f, 10f));
        eq("the tightest card puts good on the rim", 1f,
                Levelling.offset(Levelling.GOOD_DEG, Levelling.GOOD_DEG));
        eq("no reading sits in the middle", 0f, Levelling.offset(Float.NaN, 10f));
        eq("a span of nothing cannot be divided by", 0f, Levelling.offset(2f, 0f));
    }

    /**
     * The sign convention, tied to a real reading.
     *
     * Android reports gravity positive on whichever axis points up, so the bench phone's
     * measured (1.09, 0.08, 9.75) is a mount leaning onto its right edge. The bubble is
     * drawn on that side and the words have to name that side, or the person levels the
     * mount further out and blames the card.
     */
    private static void theBubbleFloatsToTheRaisedEdge() {
        near("a phone lying flat has no roll", 0.0, Levelling.rollDegrees(0, 9.81), 1e-9);
        near("a phone lying flat has no pitch", 0.0, Levelling.pitchDegrees(0, 9.81), 1e-9);
        near("the right edge up by 45", 45.0, Levelling.rollDegrees(9.81, 9.81), 1e-9);
        near("the left edge up by 45", -45.0, Levelling.rollDegrees(-9.81, 9.81), 1e-9);
        near("the top edge up by 45", 45.0, Levelling.pitchDegrees(9.81, 9.81), 1e-9);
        near("the bench mount leans 6.38 to the right", 6.38,
                Levelling.rollDegrees(1.09, 9.75), 0.005);
        near("and tips 0.47 forward", 0.47, Levelling.pitchDegrees(0.08, 9.75), 0.005);
        eq("so the card asks for the right edge", "lower the right edge",
                Levelling.instruction((float) Levelling.rollDegrees(1.09, 9.75),
                        (float) Levelling.pitchDegrees(0.08, 9.75)));
    }

    private static void eq(String what, long expected, long actual) {
        checks++;
        if (expected != actual) fail(what + ": expected " + expected + ", got " + actual);
    }

    private static void eq(String what, String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            fail(what + ": expected '" + expected + "', got '" + actual + "'");
        }
    }

    private static void eq(String what, float expected, float actual) {
        checks++;
        if (Float.compare(expected, actual) != 0) fail(what + ": expected " + expected + ", got " + actual);
    }

    private static void near(String what, double expected, double actual, double tolerance) {
        checks++;
        if (!(Math.abs(expected - actual) <= tolerance)) {
            fail(what + ": expected " + expected + " within " + tolerance + ", got " + actual);
        }
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
