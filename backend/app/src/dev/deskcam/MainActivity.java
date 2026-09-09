package dev.deskcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

/** Thin control panel. The real interface is the HTTP API. */
public class MainActivity extends Activity {

    static final String LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    private TextView status, url, hint;
    private EditText port, token;
    private CheckBox autostart;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Suppresses the status refresh so a pairing message stays readable. */
    private long holdStatusUntil = 0;
    /** The last pairing link acted on, so the same one is not used twice. */
    private String handledLink = null;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.main);

        status = findViewById(R.id.status);
        url = findViewById(R.id.url);
        hint = findViewById(R.id.hint);
        port = findViewById(R.id.port);
        token = findViewById(R.id.token);
        autostart = findViewById(R.id.autostart);

        SharedPreferences p = getSharedPreferences(CamService.PREFS, MODE_PRIVATE);
        port.setText(String.valueOf(p.getInt(CamService.PREF_PORT, 8080)));
        token.setText(p.getString(CamService.PREF_TOKEN, ""));
        autostart.setChecked(p.getBoolean(CamService.PREF_AUTOSTART, false));

        ((Button) findViewById(R.id.start)).setOnClickListener(v -> start());
        ((Button) findViewById(R.id.stop)).setOnClickListener(v -> {
            startService(new Intent(this, CamService.class).setAction(CamService.ACTION_STOP));
        });

        requestPermissions();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * Lets a script start the camera without touching the screen:
     *   adb shell am start -n dev.deskcam/.MainActivity -a dev.deskcam.START --ez finish true
     *
     * The service itself stays unexported. Android only permits a camera-type foreground
     * service to be started while the app is in the foreground, so routing the request
     * through this activity is what makes a headless start legal as well as convenient.
     */
    private void handleIntent(Intent intent) {
        if (intent == null) return;

        // deskcam://pair?cb=<url>&token=<t> arrives from a scanned QR code.
        android.net.Uri data = intent.getData();
        if (data != null && "deskcam".equals(data.getScheme())) {
            // onCreate and onNewIntent can both see the same launch intent, and pairing
            // twice consumes two codes and races the console. Handle each link once.
            String key = data.toString();
            if (key.equals(handledLink)) return;
            handledLink = key;
            handlePairing(data);
            return;
        }
        boolean wantStart = CamService.ACTION_START.equals(intent.getAction())
                || intent.getBooleanExtra("start", false);
        boolean wantStop = CamService.ACTION_STOP.equals(intent.getAction())
                || intent.getBooleanExtra("stop", false);
        if (intent.hasExtra("port")) {
            port.setText(String.valueOf(intent.getIntExtra("port", 8080)));
        }
        if (intent.hasExtra("token")) {
            token.setText(intent.getStringExtra("token"));
        }
        if (intent.hasExtra("autostart")) {
            autostart.setChecked(intent.getBooleanExtra("autostart", false));
        }
        if (wantStop) {
            startService(new Intent(this, CamService.class).setAction(CamService.ACTION_STOP));
        } else if (wantStart) {
            start();
        }
        if (intent.getBooleanExtra("finish", false)) {
            // Drop out of the way but leave the service running.
            moveTaskToBack(true);
        }
    }

    private void requestPermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Android 17 split local-network access out of INTERNET. Without it the HTTP server
        // is unreachable from the LAN even though the app can still reach the internet.
        if (android.os.Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            need.add(LOCAL_NETWORK);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 7);
    }

    private void start() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            status.setText("camera permission required");
            return;
        }
        int pnum = 8080;
        try { pnum = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) { }
        getSharedPreferences(CamService.PREFS, MODE_PRIVATE).edit()
                .putInt(CamService.PREF_PORT, pnum)
                .putString(CamService.PREF_TOKEN, token.getText().toString().trim())
                .putBoolean(CamService.PREF_AUTOSTART, autostart.isChecked())
                .apply();
        startForegroundService(new Intent(this, CamService.class).setAction(CamService.ACTION_START));
    }

    /**
     * Completes a pairing started on the workstation.
     *
     * The workstation shows a QR code holding its own callback address. We start the
     * service, then call that address and tell it where we are. The workstation could read
     * our address from the source address of this request, but we send it as well, because
     * the port is ours to choose and only we know it.
     */
    private void handlePairing(android.net.Uri uri) {
        final String cb = uri.getQueryParameter("cb");
        final String tok = uri.getQueryParameter("token");
        if (tok != null) token.setText(tok);
        if (cb == null) {
            status.setText("pairing link had no callback address");
            return;
        }
        status.setText("pairing...");
        android.util.Log.i(CameraEngine.TAG, "pairing requested, cb=" + cb);
        start();

        new Thread(() -> {
            String result;
            try {
                // Give the service a moment to bind its socket before we announce it.
                for (int i = 0; i < 20 && !CamService.isRunning(); i++) Thread.sleep(250);
                int p = 8080;
                try {
                    p = Integer.parseInt(port.getText().toString().trim());
                } catch (Exception ignored) { }
                String ip = CamService.localIpv4(this);
                String url = cb + (cb.contains("?") ? "&" : "?")
                        + "addr=" + (ip == null ? "" : ip) + "&port=" + p;
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.getInputStream().close();
                int code = c.getResponseCode();
                result = (code >= 200 && code < 300)
                        ? "paired with " + hostOf(cb)
                        : "pairing refused, code " + code;
            } catch (Exception e) {
                result = "pairing failed: " + e;
                android.util.Log.w(CameraEngine.TAG, "pairing callback", e);
            }
            final String r = result;
            android.util.Log.i(CameraEngine.TAG, "pairing result: " + r);
            ui.post(() -> {
                holdStatusUntil = System.currentTimeMillis() + 8000;
                status.setText(r);
            });
        }, "deskcam-pair").start();
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private void refresh() {
        boolean up = CamService.isRunning();
        if (System.currentTimeMillis() > holdStatusUntil) {
            status.setText(up ? "running" : "stopped");
        }
        url.setText(up ? CamService.statusLine() : "");
        if (up) {
            String base = CamService.statusLine();
            hint.setText("curl " + base + "/api/help\n"
                    + "curl -o shot.jpg '" + base + "/api/still?zoom=4'\n"
                    + "open " + base + "/ in a browser");
        } else {
            String ip = CamService.localIpv4(this);
            hint.setText(ip == null ? "no network address yet" : "device address: " + ip);
        }
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }
}
