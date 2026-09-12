package dev.deskcam;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Two voices that say how level the mount is, so it can be set without watching the screen.
 *
 * Both hands are on the bracket and the phone is face up under them, which is the whole
 * reason this exists: while you turn a screw you are looking at the screw. One voice per
 * axis, a fifth apart, each beeping faster as its own axis closes in and holding a steady
 * tone once it is good. Two steady tones means stop.
 *
 * It is synthesised rather than played from a file. The cadence has to be continuous, from
 * about one beep a second to sixteen, and it has to change under your hands within a beep
 * or two; a set of canned clips would quantise exactly the part that carries the
 * information. One AudioTrack is used rather than one per axis, because the two rhythms
 * have to be mixed anyway and two tracks would drift against each other.
 *
 * The cadence itself is {@link Levelling#beepsPerSecond}, tested on the workstation. What
 * is here is the oscillator, the envelope and the thread.
 */
final class LevelTones {

    private static final int RATE = 22050;

    /** About 23 ms. The whole buffer is one cadence, so a change lands within a chunk. */
    private static final int CHUNK = 512;

    /** Two voices sound at once, so neither may be loud enough to clip on its own. */
    private static final double AMPLITUDE = 0.22;

    /** Attack and release, in seconds. Without them each beep starts with a click. */
    private static final double RAMP = 0.004;

    private volatile boolean running = false;
    private volatile boolean muted = false;
    private volatile boolean haveReading = false;
    private volatile float roll = 0f, pitch = 0f;
    private Thread thread;

    /** One axis's beep: its own oscillator phase and its own place in the current beep. */
    private static final class Voice {
        private final double hz;
        private double phase = 0;
        private double clock = 0;
        private boolean steady = false;

        Voice(double hz) { this.hz = hz; }
    }

    void mute(boolean quiet) { muted = quiet; }

    /** A fresh reading, in degrees. Until the first one arrives nothing sounds. */
    void set(float rollDeg, float pitchDeg) {
        roll = rollDeg;
        pitch = pitchDeg;
        haveReading = true;
    }

    void start() {
        if (thread != null) return;
        haveReading = false;
        running = true;
        thread = new Thread(this::run, "deskcam-level-tones");
        thread.start();
    }

    /**
     * Stops and waits.
     *
     * The wait is on the UI thread, which is worth a word: the loop blocks only on one
     * chunk of audio, so it returns in about the 23 ms it takes to drain. Leaving the
     * track alive instead would carry the beeping out of the screen it belongs to.
     */
    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t == null) return;
        try {
            t.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        AudioTrack track = null;
        try {
            track = build();
            final Voice r = new Voice(Levelling.ROLL_TONE_HZ);
            final Voice p = new Voice(Levelling.PITCH_TONE_HZ);
            final short[] buf = new short[CHUNK];
            track.play();
            while (running) {
                // Read the state once per chunk. The cadence maths is a log and a power,
                // and neither the ear nor the mount can tell 23 ms of staleness.
                final boolean quiet = muted || !haveReading;
                final float rollRate = Levelling.beepsPerSecond(roll);
                final float pitchRate = Levelling.beepsPerSecond(pitch);
                for (int i = 0; i < CHUNK; i++) {
                    double v = quiet ? 0 : sample(r, rollRate) + sample(p, pitchRate);
                    buf[i] = (short) Math.max(-32767, Math.min(32767, v * 32767));
                }
                track.write(buf, 0, CHUNK);
            }
        } catch (Exception e) {
            // A phone with no output, or a track the platform refuses. The level still
            // works; it just works silently, and the screen says everything the ear would.
            android.util.Log.w(CameraEngine.TAG, "level tones stopped", e);
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) { }
                track.release();
            }
        }
    }

    private static AudioTrack build() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, CHUNK * 2 * 4);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes)
                .build();
    }

    /**
     * One sample of one voice, advancing its clock.
     *
     * A rate of zero means good, which sounds like a steady tone. Crossing between beeping
     * and steady restarts the clock, so the change is heard as a new note rather than as a
     * beep of some arbitrary leftover length.
     */
    private static double sample(Voice v, float rate) {
        final double dt = 1.0 / RATE;
        final boolean steady = rate <= 0f;
        if (steady != v.steady) {
            v.steady = steady;
            v.clock = 0;
            v.phase = 0;
        }
        v.clock += dt;

        double gain;
        if (steady) {
            gain = Math.min(1.0, v.clock / RAMP);
        } else {
            final double period = 1.0 / rate;
            final double on = Levelling.beepMillis(rate) / 1000.0;
            if (v.clock >= period) v.clock = 0;
            if (v.clock >= on) {
                // Silence between beeps. The phase is parked so the next one starts clean.
                v.phase = 0;
                return 0;
            }
            gain = Math.min(1.0, Math.min(v.clock, on - v.clock) / RAMP);
        }

        v.phase += 2 * Math.PI * v.hz * dt;
        if (v.phase > 2 * Math.PI) v.phase -= 2 * Math.PI;
        return AMPLITUDE * gain * Math.sin(v.phase);
    }
}
