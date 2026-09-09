package dev.deskcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Brings the camera back after a reboot, when the user has opted in.
 *
 * Android forbids starting a camera-type foreground service from the background, so the
 * direct service start is expected to fail on current releases. We try it anyway and fall
 * back to launching the activity, which is allowed to start the service itself.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences p = ctx.getSharedPreferences(CamService.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(CamService.PREF_AUTOSTART, false)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, CamService.class).setAction(CamService.ACTION_START));
        } catch (Exception e) {
            Log.w(CameraEngine.TAG, "boot service start refused, launching activity instead: " + e);
            try {
                ctx.startActivity(new Intent(ctx, MainActivity.class)
                        .setAction(CamService.ACTION_START)
                        .putExtra("finish", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Log.w(CameraEngine.TAG, "boot activity launch refused too: " + e2);
            }
        }
    }
}
