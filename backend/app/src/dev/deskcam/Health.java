package dev.deskcam;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the device says about its own condition, which until now it was never asked.
 *
 * The service holds a camera and a wake lock for hours on a phone bolted to a stand,
 * usually plugged in, usually with no screen on and nobody looking at it. A hot phone
 * gives fewer frames, and the watchdog cannot tell that from a healthy one because its
 * only test is whether frames arrive at all. Nothing sheds load: the stream asks for the
 * same rate as the device gets hotter, which is the one thing running here that makes it
 * worse. Card 44.
 *
 * Two different quantities, and they are worth keeping apart. The **thermal status** is
 * the platform's own judgement of how hot the device is, on the scale it throttles by, and
 * it is what the stream reacts to. The **battery temperature** is a real number in degrees,
 * from the only thermometer an ordinary app may read. They do not measure the same thing:
 * the battery is not the sensor and not the SoC, and it lags both. A phone can be
 * throttling hard with a battery that reads perfectly comfortable.
 */
public class Health {

    private final Context ctx;
    private final PowerManager power;

    /** The platform's level, kept current by a listener rather than polled per frame. */
    private volatile int status = Thermal.UNKNOWN;

    /** The last level that was logged, so a steady state does not fill the log. */
    private volatile int logged = Thermal.UNKNOWN;

    private PowerManager.OnThermalStatusChangedListener listener;

    public Health(Context ctx) {
        this.ctx = ctx;
        this.power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
    }

    public void start() {
        if (power == null) return;
        try {
            status = power.getCurrentThermalStatus();
            // A callback, not a poll. The stream reads this level once per frame, and a
            // binder call per frame at thirty a second is a cost this exists to avoid.
            listener = level -> {
                status = level;
                Log.i(CameraEngine.TAG, "thermal status is now " + Thermal.word(level));
            };
            power.addThermalStatusListener(listener);
        } catch (Exception e) {
            // A device that does not report it leaves the level unknown, which slows
            // nothing. Better than a service that will not start on such a device.
            Log.w(CameraEngine.TAG, "thermal status unavailable: " + e);
        }
    }

    public void stop() {
        if (power != null && listener != null) {
            try { power.removeThermalStatusListener(listener); } catch (Exception e) {
                Log.d(CameraEngine.TAG, "remove thermal listener: " + e);
            }
        }
        listener = null;
    }

    /** The platform's level right now. */
    public int thermalStatus() { return status; }

    /** How much longer a stream should wait between frames, right now. */
    public double slowdown() { return Thermal.slowdown(status); }

    /** The level as a word, for a stream's part headers. */
    public String word() { return Thermal.word(status); }

    /**
     * Logs a change of level once, and answers whether it was one.
     *
     * A stream asks per frame, so this has to be the thing that decides, or a phone
     * sitting at moderate writes a line thirty times a second.
     */
    public boolean levelChanged() {
        int now = status;
        if (now == logged) return false;
        logged = now;
        return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        int now = status;
        o.put("thermal", Thermal.word(now));
        o.put("thermal_level", now);
        o.put("throttling", Thermal.throttling(now));
        o.put("stream_slowdown", Thermal.slowdown(now));
        o.put("means", "the device is " + Thermal.means(now));

        Intent battery = batteryIntent();
        if (battery == null) {
            o.put("battery_available", false);
            return o;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) {
            o.put("battery_percent", Math.round(level * 100f / scale));
        }
        int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (tenths != Integer.MIN_VALUE) {
            o.put("battery_celsius", CamSettings.round2(tenths / 10.0));
        }
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        o.put("charging", plugged != 0);
        o.put("power_source", source(plugged));
        o.put("battery_means", "the battery temperature is the only thermometer an app of "
                + "this kind may read. It is not the sensor and not the processor, and it "
                + "lags both, so use 'thermal' above for how hot the device actually is "
                + "and this for how hot the thing in your hand would feel.");

        // The service is meant to run for hours. On a bench it is nearly always plugged
        // in, so a phone that is discharging at all is usually a cable that came out, and
        // that is worth saying long before the battery is empty.
        int percent = o.optInt("battery_percent", 100);
        if (plugged == 0 && percent <= 30) {
            o.put("warning", "the phone is on battery at " + percent + " percent and this "
                    + "service holds a wake lock, so it will not last. Check the cable.");
        } else if (plugged == 0) {
            o.put("note", "the phone is running on battery, not on the cable.");
        }
        return o;
    }

    /**
     * The battery state, from the sticky broadcast the system keeps for it.
     *
     * A null receiver reads the last one without subscribing to anything, which is the
     * documented way to ask, and means nothing has to be unregistered later.
     */
    private Intent batteryIntent() {
        try {
            return ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            Log.d(CameraEngine.TAG, "battery state unavailable: " + e);
            return null;
        }
    }

    private static String source(int plugged) {
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "ac";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "usb";
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "wireless";
        return "battery";
    }
}
