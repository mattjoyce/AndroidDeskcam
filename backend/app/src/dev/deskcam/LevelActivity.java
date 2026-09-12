package dev.deskcam;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

/**
 * Levelling the mount, on the phone, with both hands free.
 *
 * A remount leaves the camera a few degrees off square and nothing on the workstation can
 * help: the person is at the bench with a hex key, and the phone is the only screen within
 * reach. So this is a screen on the phone, reachable in one touch from the app, and it
 * needs nothing running. That last part is the point of it being an Activity and not a
 * page served by the camera: Android refuses to let a camera foreground service start
 * itself after a reboot, so the state this is most needed in is the state where the
 * service is stopped.
 *
 * It touches no camera and asks for no permission. The accelerometer is not a gated sensor
 * and the speaker needs no grant to play into.
 */
public class LevelActivity extends Activity implements SensorEventListener {

    /** Where the sound choice is remembered, in the camera's own settings file. */
    private static final String PREF_SOUND = "level_sound";

    /**
     * Samples in the average.
     *
     * {@link Sensors} averages 32 readings at SENSOR_DELAY_UI on purpose, because a camera
     * on a stand does not move and that steadiness is why /api/orientation reports an
     * unwavering figure. Levelling by hand is the opposite problem: the mount is moving
     * because you are moving it, and a two second average would smear the nudge you just
     * made across the next two seconds of the display. Six samples at SENSOR_DELAY_GAME is
     * about 120 ms, short enough to feel like the mount and long enough to kill the jitter.
     * This is why it does not reuse Sensors, which exposes nothing but its JSON anyway.
     */
    private static final int WINDOW = 6;

    private SensorManager sensors;
    private Sensor gravity;
    private Level card;
    private TextView instruction, numbers, note;
    private Button sound;
    private final LevelTones tones = new LevelTones();

    private final float[] sx = new float[WINDOW], sy = new float[WINDOW], sz = new float[WINDOW];
    private int samples = 0, next = 0;
    private boolean wantSound = true;
    private String saidInstruction = "", saidNumbers = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.level);
        card = findViewById(R.id.levelcard);
        instruction = findViewById(R.id.instruction);
        numbers = findViewById(R.id.numbers);
        note = findViewById(R.id.note);
        sound = findViewById(R.id.sound);

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (g == null && sensors != null) {
            // TYPE_GRAVITY is fused and already free of the shake of a hand on the bench.
            // A raw accelerometer is the fallback, and the average here is what stands in
            // for the fusion.
            g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        gravity = g;

        wantSound = getSharedPreferences(CamService.PREFS, MODE_PRIVATE)
                .getBoolean(PREF_SOUND, true);
        showSound();
        sound.setOnClickListener(v -> {
            wantSound = !wantSound;
            getSharedPreferences(CamService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_SOUND, wantSound).apply();
            showSound();
            tones.mute(!wantSound);
        });
        ((Button) findViewById(R.id.done)).setOnClickListener(v -> finish());

        if (gravity == null) {
            instruction.setText("no gravity sensor");
            note.setText("This phone reports neither a gravity nor an accelerometer sensor, "
                    + "so it cannot tell you which way is down.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gravity == null) return;
        samples = 0;
        next = 0;
        sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME);
        tones.mute(!wantSound);
        tones.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Both of these outlive the screen if they are not stopped here: a sensor at GAME
        // rate is a battery drain on a phone that sits on a stand for hours, and a beep
        // that follows you out of the screen is worse than no beep at all.
        if (gravity != null) sensors.unregisterListener(this);
        tones.stop();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        sx[next] = e.values[0];
        sy[next] = e.values[1];
        sz[next] = e.values[2];
        next = (next + 1) % WINDOW;
        if (samples < WINDOW) samples++;

        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < samples; i++) {
            ax += sx[i];
            ay += sy[i];
            az += sz[i];
        }
        final float roll = (float) Levelling.rollDegrees(ax / samples, az / samples);
        final float pitch = (float) Levelling.pitchDegrees(ay / samples, az / samples);

        card.update(roll, pitch);
        tones.set(roll, pitch);

        // The words change far less often than the reading does, and a TextView that is
        // set fifty times a second relayouts fifty times a second for nothing.
        final String says = Levelling.instruction(roll, pitch);
        if (!says.equals(saidInstruction)) {
            saidInstruction = says;
            instruction.setText(says);
            instruction.setTextColor(getColor(card.level() ? R.color.ok : R.color.text));
        }
        final String shows = String.format(Locale.US, "roll %+.2f°   pitch %+.2f°   card ±%s°",
                roll, pitch, trim(card.rangeDegrees()));
        if (!shows.equals(saidNumbers)) {
            saidNumbers = shows;
            numbers.setText(shows);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) { }

    private void showSound() {
        sound.setText(wantSound ? "Sound on" : "Sound off");
        sound.setTextColor(getColor(wantSound ? R.color.text : R.color.dim));
    }

    private static String trim(float v) {
        return v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
    }
}
