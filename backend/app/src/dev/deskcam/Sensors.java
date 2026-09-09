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
 * Gravity gives the tilt of the optical axis. A top-down bench rig wants that tilt near
 * zero; anything else puts the subject plane at an angle to the sensor, which stretches
 * one side of the picture relative to the other and quietly corrupts any measurement of
 * size. The number belongs with each capture, so a later reader knows the geometry.
 *
 * This gives the ANGLE only. It cannot give the distance or the position, so a picture
 * still needs a scale reference in the frame to measure real sizes.
 */
public class Sensors implements SensorEventListener {

    private final SensorManager sm;
    private final Sensor gravity, light;

    private volatile float gx, gy, gz;
    private volatile boolean haveGravity = false;
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
                gx = e.values[0]; gy = e.values[1]; gz = e.values[2];
                haveGravity = true;
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
        if (!haveGravity) {
            o.put("available", false);
            o.put("note", "no gravity reading yet");
            if (lux >= 0) o.put("ambient_lux", CamSettings.round2(lux));
            return o;
        }
        double mag = Math.sqrt(gx * (double) gx + gy * (double) gy + gz * (double) gz);
        o.put("available", true);
        o.put("gravity", new JSONObject()
                .put("x", CamSettings.round2(gx))
                .put("y", CamSettings.round2(gy))
                .put("z", CamSettings.round2(gz)));

        // The rear camera looks along -z, so the screen faces +z. With the camera aimed
        // straight down at a level desk the screen faces straight up and gz is +9.81.
        double tilt = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, gz / mag))));
        o.put("tilt_degrees", CamSettings.round2(tilt));
        o.put("roll_degrees", CamSettings.round2(Math.toDegrees(Math.atan2(gx, gz))));
        o.put("pitch_degrees", CamSettings.round2(Math.toDegrees(Math.atan2(gy, gz))));
        o.put("aim", describe(tilt));
        if (lux >= 0) o.put("ambient_lux", CamSettings.round2(lux));
        return o;
    }

    private static String describe(double tilt) {
        if (tilt < 2) return "straight down, square to a level surface";
        if (tilt < 5) return "nearly straight down, off by a little";
        if (tilt < 20) return "tilted; a flat subject will be measurably skewed";
        if (tilt > 85 && tilt < 95) return "horizontal, looking across the bench";
        if (tilt > 170) return "pointing straight up";
        return "tilted " + Math.round(tilt) + " degrees from straight down";
    }
}
