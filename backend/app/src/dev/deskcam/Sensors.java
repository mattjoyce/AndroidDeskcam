package dev.deskcam;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reads the phone's own sensors to describe how the camera is held.
 *
 * Gravity gives the tilt of the optical axis AGAINST GRAVITY. That is the quantity, and
 * it is not quite the quantity a measurement usually wants. Skew of a flat subject comes
 * from the angle between the camera and the PLANE OF THE SUBJECT, and the two are equal
 * only when the subject lies on a level surface. On a tilted jig they can differ by the
 * tilt of the jig, so a small tilt reading is evidence of square framing only when the
 * bench is known to be level.
 *
 * The angle is averaged over the samples held here rather than taken from one reading.
 * One unfiltered sample of an accelerometer carries the noise of the sensor and of the
 * bench, and quoting it to three figures claims a precision it does not have.
 *
 * This gives the ANGLE only. It cannot give the distance or the position, so a picture
 * still needs a scale reference in the frame to measure real sizes.
 */
public class Sensors implements SensorEventListener {

    private final SensorManager sm;
    private final Sensor gravity, light;

    /** The last few gravity samples, averaged before anything is reported. */
    private static final int WINDOW = 32;
    private final float[] sx = new float[WINDOW], sy = new float[WINDOW], sz = new float[WINDOW];
    private int samples = 0;
    private int next = 0;
    private volatile float lux = -1;

    public Sensors(Context ctx) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (g == null) g = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gravity = g;
        light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    public void start() {
        // Slow rates. A camera on a stand does not move, and this must not cost battery.
        if (gravity != null) sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_UI);
        if (light != null) sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void stop() {
        try { sm.unregisterListener(this); } catch (Exception ignored) { }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_ACCELEROMETER:
                synchronized (this) {
                    sx[next] = e.values[0];
                    sy[next] = e.values[1];
                    sz[next] = e.values[2];
                    next = (next + 1) % WINDOW;
                    if (samples < WINDOW) samples++;
                }
                break;
            case Sensor.TYPE_LIGHT:
                lux = e.values[0];
                break;
            default:
                break;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        float gx, gy, gz;
        int n;
        synchronized (this) {
            n = samples;
            if (n == 0) {
                o.put("available", false);
                o.put("note", "no gravity reading yet");
                if (lux >= 0) o.put("ambient_lux", CamSettings.round2(lux));
                return o;
            }
            double ax = 0, ay = 0, az = 0;
            for (int i = 0; i < n; i++) { ax += sx[i]; ay += sy[i]; az += sz[i]; }
            gx = (float) (ax / n);
            gy = (float) (ay / n);
            gz = (float) (az / n);
        }
        double mag = Math.sqrt(gx * (double) gx + gy * (double) gy + gz * (double) gz);
        o.put("available", true);
        o.put("samples", n);
        o.put("gravity", new JSONObject()
                .put("x", CamSettings.round2(gx))
                .put("y", CamSettings.round2(gy))
                .put("z", CamSettings.round2(gz)));

        // The rear camera looks along -z, so the screen faces +z. With the camera aimed
        // straight down at a level desk the screen faces straight up and gz is +9.81.
        double tilt = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, gz / mag))));
        o.put("tilt_degrees", CamSettings.round2(tilt));
        // Levelling owns these two formulas, and the leveller on the phone calls the same
        // pair, so the screen a person levels by and the figure an agent reads cannot
        // disagree about which way the mount leans.
        o.put("roll_degrees", CamSettings.round2(Levelling.rollDegrees(gx, gz)));
        o.put("pitch_degrees", CamSettings.round2(Levelling.pitchDegrees(gy, gz)));
        o.put("aim", describe(tilt));
        o.put("measures", "the angle between the optical axis and gravity, averaged over "
                + n + " samples. It equals the angle to a flat subject only when the "
                + "subject lies on a level surface.");
        if (lux >= 0) o.put("ambient_lux", CamSettings.round2(lux));
        return o;
    }

    /**
     * Words for the angle, with the angle in them.
     *
     * The bands are a reading aid and nothing more. Cutting a continuous quantity at hard
     * limits made 4.99 and 5.01 degrees read as different states, so every phrase now
     * carries the number it came from and a reader can see how close to a limit it sits.
     */
    private static String describe(double tilt) {
        String band;
        if (tilt < 2) band = "straight down, square to a level surface";
        else if (tilt < 5) band = "nearly straight down";
        else if (tilt < 20) band = "tilted; a flat subject on a level surface will be measurably skewed";
        else if (tilt > 85 && tilt < 95) band = "horizontal, looking across the bench";
        else if (tilt > 170) band = "pointing straight up";
        else band = "tilted well off straight down";
        return String.format(java.util.Locale.US, "%s (%.2f degrees from gravity)", band, tilt);
    }
}
