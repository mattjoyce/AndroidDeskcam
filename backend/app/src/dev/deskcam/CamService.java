package dev.deskcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.net.Inet4Address;

/**
 * Holds the camera and the HTTP server for as long as the bench camera is meant to be up.
 * Runs in the foreground with type "camera", which is what lets the app keep the sensor
 * open while the screen is off.
 */
public class CamService extends Service {

    private static final String TAG = CameraEngine.TAG;
    private static final String CHANNEL = "deskcam";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START = "dev.deskcam.START";
    public static final String ACTION_STOP = "dev.deskcam.STOP";
    public static final String PREFS = "deskcam";
    public static final String PREF_PORT = "port";
    public static final String PREF_TOKEN = "token";
    public static final String PREF_AUTOSTART = "autostart";

    private static volatile boolean running = false;
    private static volatile String statusLine = "stopped";

    private CameraEngine engine;
    private Sensors sensors;
    private HttpServer http;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return running; }
    public static String statusLine() { return statusLine; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int port = p.getInt(PREF_PORT, 8080);
        String token = p.getString(PREF_TOKEN, "");

        createChannel();
        try {
            startForeground(NOTIF_ID, buildNotification("starting on port " + port),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } catch (Exception e) {
            // Android refuses to start a camera-type foreground service from the
            // background, which is exactly the situation on BOOT_COMPLETED. Bounce
            // through the activity, which is allowed to start us, rather than let the
            // exception escape and put the service into a restart loop.
            Log.w(TAG, "foreground start refused, bouncing via activity: " + e);
            statusLine = "waiting for the app to come to the foreground";
            try {
                startActivity(new Intent(this, MainActivity.class)
                        .setAction(ACTION_START)
                        .putExtra("finish", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Log.w(TAG, "activity fallback refused too: " + e2);
                statusLine = "blocked: open DeskCam once to start it";
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deskcam:service");
            wakeLock.acquire();

            sensors = new Sensors(this);
            sensors.start();
            engine = new CameraEngine(this);
            engine.setSensors(sensors);
            engine.start();
            http = new HttpServer(engine, port, token);
            http.start();

            running = true;
            statusLine = url(port);
            updateNotification(statusLine);
            Log.i(TAG, "service up at " + statusLine);
        } catch (Exception e) {
            Log.e(TAG, "failed to start", e);
            statusLine = "failed: " + e;
            updateNotification(statusLine);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        statusLine = "stopped";
        if (http != null) { http.stop(); http = null; }
        if (engine != null) { engine.stop(); engine = null; }
        if (sensors != null) { sensors.stop(); sensors = null; }
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }
        Log.i(TAG, "service down");
        super.onDestroy();
    }

    // -------------------------------------------------------------- notice

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "DeskCam", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Bench camera HTTP service");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CamService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("DeskCam")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }

    // ------------------------------------------------------------ addresses

    private String url(int port) {
        String ip = localIpv4(this);
        return "http://" + (ip == null ? "<device-ip>" : ip) + ":" + port;
    }

    /** The address a desktop on the same network should actually connect to. */
    public static String localIpv4(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            Network active = cm.getActiveNetwork();
            if (active != null) {
                LinkProperties lp = cm.getLinkProperties(active);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                            return la.getAddress().getHostAddress();
                        }
                    }
                }
            }
            // Fall back to scanning interfaces when there is no active default network.
            for (java.util.Enumeration<java.net.NetworkInterface> e = java.net.NetworkInterface.getNetworkInterfaces();
                 e.hasMoreElements(); ) {
                java.net.NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.util.Enumeration<java.net.InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    java.net.InetAddress addr = a.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) return addr.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ip lookup", e);
        }
        return null;
    }
}
