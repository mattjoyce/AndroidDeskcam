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

    private void refresh() {
        boolean up = CamService.isRunning();
        status.setText(up ? "running" : "stopped");
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
