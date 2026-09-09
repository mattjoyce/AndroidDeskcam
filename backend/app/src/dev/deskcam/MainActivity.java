package dev.deskcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The screen on the phone.
 *
 * The phone sits on a stand and nobody watches it for long, so this answers three
 * questions at a glance and nothing else: is it running and reachable, is anything
 * actually talking to it, and what did it last see. The controls are set once and then
 * hidden.
 */
public class MainActivity extends Activity {

    static final String LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    private TextView status, url, thumbWhen;
    private TextView statConn, statStream, statShots, statErr, statCam;
    private View dot;
    private ImageView thumb;
    private LinearLayout log, setup;
    private ScrollView logScroll;
    private EditText port, token;
    private CheckBox autostart;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastLogSeq = -1;
    private long lastThumbAt = -1;
    private long holdStatusUntil = 0;
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
        dot = findViewById(R.id.dot);
        thumb = findViewById(R.id.thumb);
        thumbWhen = findViewById(R.id.thumbwhen);
        statConn = findViewById(R.id.statConn);
        statStream = findViewById(R.id.statStream);
        statShots = findViewById(R.id.statShots);
        statErr = findViewById(R.id.statErr);
        statCam = findViewById(R.id.statCam);
        log = findViewById(R.id.log);
        logScroll = findViewById(R.id.logscroll);
        setup = findViewById(R.id.setup);
        port = findViewById(R.id.port);
        token = findViewById(R.id.token);
        autostart = findViewById(R.id.autostart);

        SharedPreferences p = getSharedPreferences(CamService.PREFS, MODE_PRIVATE);
        port.setText(String.valueOf(p.getInt(CamService.PREF_PORT, 8080)));
        token.setText(p.getString(CamService.PREF_TOKEN, ""));
        autostart.setChecked(p.getBoolean(CamService.PREF_AUTOSTART, false));

        ((Button) findViewById(R.id.start)).setOnClickListener(v -> start());
        ((Button) findViewById(R.id.stop)).setOnClickListener(v ->
                startService(new Intent(this, CamService.class).setAction(CamService.ACTION_STOP)));
        ((Button) findViewById(R.id.settings)).setOnClickListener(v ->
                setup.setVisibility(setup.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));

        requestPermissions();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ---------------------------------------------------------------- screen

    private void refresh() {
        boolean up = CamService.isRunning();
        CameraEngine engine = CamService.engine();

        String camState = "-";
        if (engine != null) {
            try {
                camState = engine.status().optString("state", "-");
            } catch (Exception ignored) { }
        }
        boolean healthy = up && "running".equals(camState);

        if (System.currentTimeMillis() > holdStatusUntil) {
            status.setText(!up ? "stopped" : (healthy ? "running" : camState));
        }
        dot.getBackground().setTint(!up ? colour(R.color.bad)
                : (healthy ? colour(R.color.ok) : colour(R.color.warn)));

        if (up) {
            url.setText(CamService.statusLine());
        } else {
            String ip = CamService.localIpv4(this);
            url.setText(ip == null ? "no network" : "ready on " + ip);
        }

        statConn.setText(String.format(Locale.US, "clients  %d", HttpServer.liveConnections()));
        statStream.setText(String.format(Locale.US, "streams  %d",
                engine == null ? 0 : engine.streamClients()));
        statShots.setText(String.format(Locale.US, "captures %d", RequestLog.captures()));
        statErr.setText(String.format(Locale.US, "errors   %d", RequestLog.errors()));
        statErr.setTextColor(RequestLog.errors() > 0 ? colour(R.color.bad) : colour(R.color.text));
        statCam.setText("camera   " + camState);

        if (engine != null && engine.lastStillAt() != lastThumbAt) {
            Bitmap b = engine.lastStillThumb();
            if (b != null) {
                thumb.setImageBitmap(b);
                lastThumbAt = engine.lastStillAt();
                thumbWhen.setText(android.text.format.DateFormat.format("HH:mm:ss", lastThumbAt));
            }
        }

        if (RequestLog.sequence() != lastLogSeq) {
            lastLogSeq = RequestLog.sequence();
            drawLog();
        }
    }

    /**
     * Redraws the list only when something was recorded. Rebuilding forty rows every
     * second on a phone that runs for hours would be work for nothing.
     */
    private void drawLog() {
        List<RequestLog.Entry> entries = RequestLog.recent(40);
        log.removeAllViews();
        if (entries.isEmpty()) {
            log.addView(row("waiting for a request", colour(R.color.dim)));
            return;
        }
        for (RequestLog.Entry e : entries) {
            String when = android.text.format.DateFormat.format("HH:mm:ss", e.at).toString();
            String size = e.bytes > 1024 * 1024
                    ? String.format(Locale.US, "%.1fM", e.bytes / 1048576.0)
                    : (e.bytes > 0 ? String.format(Locale.US, "%dk", e.bytes / 1024) : "");
            String text = String.format(Locale.US, "%s  %-18s %3d  %4dms %6s",
                    when, trim(e.path), e.code, e.millis, size);
            log.addView(row(text, e.code >= 400 ? colour(R.color.bad) : colour(R.color.text)));
        }
        logScroll.post(() -> logScroll.scrollTo(0, 0));
    }

    private static String trim(String path) {
        String s = path.startsWith("/api/") ? path.substring(5) : path;
        return s.length() > 18 ? s.substring(0, 18) : s;
    }

    private TextView row(String text, int textColour) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(textColour);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4,
                getResources().getDisplayMetrics());
        t.setPadding(pad * 2, pad / 2, pad * 2, pad / 2);
        return t;
    }

    private int colour(int id) {
        return getResources().getColor(id, getTheme());
    }

    // ----------------------------------------------------------- permissions

    private void requestPermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Android 17 split local-network access out of INTERNET. Without it the HTTP
        // server is unreachable from the LAN even though the app can still reach the
        // internet.
        if (android.os.Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            need.add(LOCAL_NETWORK);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 7);
    }

    private void start() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            say("camera permission required");
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

    private void say(String text) {
        holdStatusUntil = System.currentTimeMillis() + 8000;
        status.setText(text);
    }

    // -------------------------------------------------------------- pairing

    private void handleIntent(Intent intent) {
        if (intent == null) return;

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
        if (intent.hasExtra("port")) port.setText(String.valueOf(intent.getIntExtra("port", 8080)));
        if (intent.hasExtra("token")) token.setText(intent.getStringExtra("token"));
        if (intent.hasExtra("autostart")) autostart.setChecked(intent.getBooleanExtra("autostart", false));
        if (wantStop) {
            startService(new Intent(this, CamService.class).setAction(CamService.ACTION_STOP));
        } else if (wantStart) {
            start();
        }
        if (intent.getBooleanExtra("finish", false)) moveTaskToBack(true);
    }

    /**
     * Completes a pairing started on the workstation. We start the service, then call the
     * address in the code and say where we are. The workstation could read our address
     * from the source of this request, but we send it as well, because the port is ours
     * to choose and only we know it.
     */
    private void handlePairing(android.net.Uri uri) {
        final String cb = uri.getQueryParameter("cb");
        final String tok = uri.getQueryParameter("token");
        if (tok != null) token.setText(tok);
        if (cb == null) {
            say("pairing link had no callback address");
            return;
        }
        say("pairing...");
        android.util.Log.i(CameraEngine.TAG, "pairing requested, cb=" + cb);
        start();

        new Thread(() -> {
            String result;
            try {
                for (int i = 0; i < 20 && !CamService.isRunning(); i++) Thread.sleep(250);
                int p = 8080;
                try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) { }
                String ip = CamService.localIpv4(this);
                String u = cb + (cb.contains("?") ? "&" : "?")
                        + "addr=" + (ip == null ? "" : ip) + "&port=" + p;
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.getInputStream().close();
                int code = c.getResponseCode();
                result = (code >= 200 && code < 300)
                        ? "paired with " + hostOf(cb) : "pairing refused, code " + code;
            } catch (Exception e) {
                result = "pairing failed: " + e;
                android.util.Log.w(CameraEngine.TAG, "pairing callback", e);
            }
            final String r = result;
            android.util.Log.i(CameraEngine.TAG, "pairing result: " + r);
            ui.post(() -> say(r));
        }, "deskcam-pair").start();
    }

    private static String hostOf(String u) {
        try { return new java.net.URL(u).getHost(); } catch (Exception e) { return u; }
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }
}
