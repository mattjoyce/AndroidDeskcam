package dev.deskcam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
    private Button toggle, net;
    private Boolean toggleShowsStop = null;

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
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(),
                    PackageManager.PackageInfoFlags.of(0)).versionName;
            ((TextView) findViewById(R.id.version)).setText(v == null ? "" : "v" + v);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Our own package is always there; nothing to show if it somehow is not.
        }

        SharedPreferences p = getSharedPreferences(CamService.PREFS, MODE_PRIVATE);
        port.setText(String.valueOf(p.getInt(CamService.PREF_PORT, 8080)));
        token.setText(p.getString(CamService.PREF_TOKEN, ""));
        autostart.setChecked(p.getBoolean(CamService.PREF_AUTOSTART, false));

        toggle = findViewById(R.id.toggle);
        net = findViewById(R.id.net);
        // One button whose label is what pressing it does. The state is the dot and the
        // words beside it, in the same row, so the two can never be read apart.
        toggle.setOnClickListener(v -> {
            if (CamService.isRunning()) {
                startService(new Intent(this, CamService.class).setAction(CamService.ACTION_STOP));
                say("stopping");
            } else {
                start();
            }
        });
        net.setOnClickListener(v -> chooseNetwork());
        ((Button) findViewById(R.id.settings)).setOnClickListener(v ->
                setup.setVisibility(setup.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        // The leveller reads the accelerometer and nothing else, so it works with the
        // service stopped, which is the state a reboot leaves the phone in.
        ((Button) findViewById(R.id.level)).setOnClickListener(v ->
                startActivity(new Intent(this, LevelActivity.class)));

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
        // Stopped is a state somebody chose, not a fault, so it is grey; red is kept for
        // the errors counter, where it means something went wrong.
        dot.getBackground().setTint(!up ? colour(R.color.dim)
                : (healthy ? colour(R.color.ok) : colour(R.color.warn)));
        showToggle(up);

        List<Nets.Choice> nets = CamService.networks(this);
        String preferred = CamService.preferredNet(this);
        Nets.Choice chosen = Nets.pick(nets, preferred);
        int bound = CamService.boundPort();
        if (up && bound > 0) {
            url.setText(chosen == null ? "no network" : "http://" + chosen.ip + ":" + bound);
        } else if (up) {
            url.setText(CamService.statusLine());
        } else {
            url.setText(chosen == null ? "no network" : "ready on " + chosen.ip);
        }
        if (!Nets.AUTO.equals(preferred) && Nets.find(nets, preferred) == null) {
            net.setText(preferred + " down ▾");
        } else {
            net.setText(chosen == null ? "none ▾" : Nets.name(chosen.kind) + " ▾");
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

    /** Changes the button only when the state has, not every tick. */
    private void showToggle(boolean up) {
        if (toggleShowsStop != null && toggleShowsStop == up) return;
        toggleShowsStop = up;
        toggle.setText(up ? "Stop" : "Start");
        toggle.setBackgroundResource(up ? R.drawable.btn_quiet : R.drawable.btn);
        toggle.setTextColor(up ? colour(R.color.text) : Color.WHITE);
    }

    /**
     * Which address to show and report. The list is every IPv4 address the phone holds,
     * best first, with Auto on top saying what it would choose right now.
     */
    private void chooseNetwork() {
        List<Nets.Choice> nets = Nets.ordered(CamService.networks(this));
        String preferred = CamService.preferredNet(this);
        List<String> labels = new java.util.ArrayList<>();
        List<String> keys = new java.util.ArrayList<>();
        Nets.Choice best = nets.isEmpty() ? null : nets.get(0);
        labels.add("Auto" + (best == null ? "" : "   now " + Nets.name(best.kind) + "   " + best.ip));
        keys.add(Nets.AUTO);
        for (Nets.Choice c : nets) {
            labels.add(c.label());
            keys.add(c.iface);
        }
        if (!Nets.AUTO.equals(preferred) && Nets.find(nets, preferred) == null) {
            labels.add(preferred + "   not up now");
            keys.add(preferred);
        }
        int checked = Math.max(0, keys.indexOf(preferred));
        new AlertDialog.Builder(this)
                .setTitle("Address to show and report")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    getSharedPreferences(CamService.PREFS, MODE_PRIVATE).edit()
                            .putString(CamService.PREF_NET, keys.get(which)).apply();
                    if (CamService.isRunning()) {
                        startService(new Intent(this, CamService.class)
                                .setAction(CamService.ACTION_ADDRESS));
                    }
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        // The port, token and autostart extras are gone, card 64: the activity is exported,
        // so any app on the phone could send them and change the key without a word.
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
        // Card 64. A link used to be applied the moment it arrived. Now a callback that is
        // not a console's pairing route on a private address is refused outright, and
        // anything else is put to the person first.
        String refused = Pairing.refuse(cb);
        if (refused != null) {
            say("pairing refused: " + refused);
            android.util.Log.w(CameraEngine.TAG, "pairing refused, cb=" + cb + ": " + refused);
            return;
        }
        final String host = hostOf(cb);
        new AlertDialog.Builder(this)
                .setTitle("Pair with " + host + "?")
                .setMessage("This will " + Pairing.keyEffect(tok) + ", start the camera, and tell "
                        + host + " this phone's address.\n\nAccept only a code your own console is showing.")
                .setPositiveButton("Pair", (d, which) -> pair(cb, tok))
                .setNegativeButton("Cancel", (d, which) -> say("pairing cancelled"))
                .setOnCancelListener(d -> say("pairing cancelled"))
                .show();
    }

    private void pair(String cb, String tok) {
        // An absent token leaves the key alone. An empty one is the console saying to
        // remove it, which is a different instruction and has to stay distinguishable.
        if (tok != null) token.setText(tok);
        final String keyNote = tok == null ? ""
                : tok.isEmpty() ? ", access key removed, the camera is open"
                : ", access key set";
        say("pairing...");
        android.util.Log.i(CameraEngine.TAG, "pairing accepted, cb=" + cb);
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
                        ? "paired with " + hostOf(cb) + keyNote
                        : "pairing refused, code " + code;
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
