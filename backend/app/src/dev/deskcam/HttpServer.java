package dev.deskcam;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small HTTP/1.1 server exposing the camera over the LAN.
 *
 * Everything is reachable with a plain GET and query parameters, because the intended
 * clients are curl one-liners and coding agents, not a browser form.
 */
public class HttpServer implements Runnable {

    private static final String TAG = CameraEngine.TAG;
    private static final String BOUNDARY = "deskcamframe";

    private final CameraEngine engine;
    private final int port;
    private final String token;

    private ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public HttpServer(CameraEngine engine, int port, String token) {
        this.engine = engine;
        this.port = port;
        this.token = (token == null || token.isEmpty()) ? null : token;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress("0.0.0.0", port));
        running = true;
        new Thread(this, "deskcam-http").start();
        Log.i(TAG, "http listening on 0.0.0.0:" + port);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                pool.execute(() -> handleSafely(s));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept", e);
            }
        }
    }

    private void handleSafely(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(30000);
            handle(s);
        } catch (Exception e) {
            Log.d(TAG, "connection ended: " + e);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------- routing

    private void handle(Socket sock) throws Exception {
        InputStream in = sock.getInputStream();
        OutputStream rawOut = sock.getOutputStream();
        BufferedOutputStream out = new BufferedOutputStream(rawOut, 1 << 16);

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) { sendText(out, 400, "text/plain", "bad request line"); return; }
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];

        Map<String, String> headers = new LinkedHashMap<>();
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            int c = h.indexOf(':');
            if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
        }

        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

        if ("POST".equals(method)) {
            int len = 0;
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (Exception ignored) { }
            if (len > 0 && len < 1 << 20) {
                byte[] body = new byte[len];
                int read = 0;
                while (read < len) {
                    int r = in.read(body, read, len - read);
                    if (r < 0) break;
                    read += r;
                }
                String bodyStr = new String(body, 0, read, StandardCharsets.UTF_8).trim();
                // Accept either a query string or a flat JSON object as the body.
                if (bodyStr.startsWith("{")) {
                    JSONObject j = new JSONObject(bodyStr);
                    StringBuilder sb = new StringBuilder(query);
                    for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (sb.length() > 0) sb.append('&');
                        sb.append(k).append('=').append(java.net.URLEncoder.encode(j.get(k).toString(), "UTF-8"));
                    }
                    query = sb.toString();
                } else if (!bodyStr.isEmpty()) {
                    query = query.isEmpty() ? bodyStr : query + "&" + bodyStr;
                }
            }
        }

        Map<String, String> params = parseQuery(query);

        if ("OPTIONS".equals(method)) { sendText(out, 204, "text/plain", ""); return; }

        if (token != null && !authorised(params, headers)) {
            sendJson(out, 401, err("unauthorised: supply ?token=... or an Authorization: Bearer header"));
            return;
        }
        params.remove("token");

        try {
            route(path, params, out);
        } catch (Exception e) {
            Log.w(TAG, "handler " + path, e);
            try { sendJson(out, 500, err(e.toString())); } catch (Exception ignored) { }
        }
    }

    private boolean authorised(Map<String, String> params, Map<String, String> headers) {
        if (token.equals(params.get("token"))) return true;
        String a = headers.get("authorization");
        return a != null && a.startsWith("Bearer ") && token.equals(a.substring(7).trim());
    }

    private void route(String path, Map<String, String> params, BufferedOutputStream out) throws Exception {
        switch (path) {
            case "/":
            case "/index.html":
                sendText(out, 200, "text/html; charset=utf-8", WebUi.page());
                return;

            case "/api/help":
                sendJson(out, 200, WebUi.help());
                return;

            case "/api/cameras":
                sendJson(out, 200, new JSONObject().put("cameras", engine.listCameras()));
                return;

            case "/api/status": {
                JSONObject o = engine.status();
                o.put("ok", true);
                sendJson(out, 200, o);
                return;
            }

            case "/api/set": {
                JSONObject o = applyParams(params);
                sendJson(out, o.optBoolean("ok", true) ? 200 : 400, o);
                return;
            }

            case "/api/reset": {
                engine.update(new CamSettings());
                JSONObject o = engine.status();
                o.put("ok", true);
                sendJson(out, 200, o);
                return;
            }

            case "/api/af": {
                engine.triggerAf();
                Thread.sleep(clampLong(longParam(params, "wait", 700), 0, 5000));
                JSONObject o = engine.status();
                o.put("ok", true);
                sendJson(out, 200, o);
                return;
            }

            case "/api/still": {
                JSONObject applied = applyParams(params);
                if (!applied.optBoolean("ok", true)) { sendJson(out, 400, applied); return; }
                long settle = longParam(params, "settle", defaultSettle(params));
                if (settle > 0) Thread.sleep(clampLong(settle, 0, 5000));
                byte[] jpeg = engine.captureStill(longParam(params, "timeout", 8000));
                sendBytes(out, 200, "image/jpeg", jpeg);
                return;
            }

            case "/api/frame": {
                JSONObject applied = applyParams(params);
                if (!applied.optBoolean("ok", true)) { sendJson(out, 400, applied); return; }
                long settle = longParam(params, "settle", defaultSettle(params));
                if (settle > 0) Thread.sleep(clampLong(settle, 0, 5000));
                // The camera keeps requests in flight, so the next frame or two can still
                // carry the previous settings. Skip them after any settings change.
                int skip = (int) longParam(params, "fresh", settle > 0 ? 2 : 0);
                byte[] jpeg = engine.grabFrame(longParam(params, "timeout", 8000), skip);
                sendBytes(out, 200, "image/jpeg", jpeg);
                return;
            }

            case "/api/raw": {
                JSONObject applied = applyParams(params);
                if (!applied.optBoolean("ok", true)) { sendJson(out, 400, applied); return; }
                long settle = longParam(params, "settle", defaultSettle(params));
                if (settle > 0) Thread.sleep(clampLong(settle, 0, 5000));
                byte[] dng = engine.captureRaw(longParam(params, "timeout", 12000));
                // The DNG holds the whole sensor array, so tell the client where the user
                // was aimed rather than silently discarding the framing.
                sendBytes(out, 200, "image/x-adobe-dng", dng,
                        "X-DeskCam-ROI: " + engine.rawRoiHeader() + "\r\n");
                return;
            }

            case "/api/burst": {
                JSONObject applied = applyParams(params);
                if (!applied.optBoolean("ok", true)) { sendJson(out, 400, applied); return; }
                int n = (int) clampLong(longParam(params, "n", 8), 1, 64);
                long settle = longParam(params, "settle", defaultSettle(params));
                if (settle > 0) Thread.sleep(clampLong(settle, 0, 5000));
                long t0 = System.currentTimeMillis();
                java.util.List<byte[]> frames =
                        engine.captureBurst(n, longParam(params, "timeout", 5000L + 1500L * n));
                long ms = System.currentTimeMillis() - t0;
                Tar tar = new Tar(frames.size() * 2_000_000);
                for (int i = 0; i < frames.size(); i++) {
                    tar.add(String.format(java.util.Locale.US, "burst-%03d.jpg", i), frames.get(i));
                }
                byte[] body = tar.finish();
                double fps = ms > 0 ? frames.size() * 1000.0 / ms : 0;
                sendBytes(out, 200, "application/x-tar", body,
                        "X-DeskCam-Frames: " + frames.size() + "\r\n"
                        + "X-DeskCam-Millis: " + ms + "\r\n"
                        + String.format(java.util.Locale.US, "X-DeskCam-Fps: %.2f\r\n", fps));
                return;
            }

            case "/api/orientation": {
                sendJson(out, 200, engine.orientation());
                return;
            }

            case "/api/shadingmap": {
                // Turn the map on unless the caller said otherwise, then wait for a frame
                // that was actually taken with it on.
                if (!params.containsKey("shadingmap")) params.put("shadingmap", "1");
                JSONObject applied = applyParams(params);
                if (!applied.optBoolean("ok", true)) { sendJson(out, 400, applied); return; }
                try {
                    engine.grabFrame(longParam(params, "timeout", 8000), 2);
                } catch (Exception e) {
                    Log.d(TAG, "shading map frame: " + e);
                }
                sendJson(out, 200, engine.shadingMap());
                return;
            }

            case "/api/nettest": {
                // Diagnostic: can this app reach the network outbound at all?
                String host = params.getOrDefault("host", "192.168.86.1");
                int tport = (int) longParam(params, "port", 80);
                JSONObject o = new JSONObject();
                o.put("target", host + ":" + tport);
                long t0 = System.currentTimeMillis();
                try (java.net.Socket probe = new java.net.Socket()) {
                    probe.connect(new java.net.InetSocketAddress(host, tport), 4000);
                    o.put("ok", true);
                    o.put("connected_ms", System.currentTimeMillis() - t0);
                    o.put("local_address", String.valueOf(probe.getLocalAddress()));
                } catch (Exception e) {
                    o.put("ok", false);
                    o.put("failed_after_ms", System.currentTimeMillis() - t0);
                    o.put("error", e.toString());
                }
                sendJson(out, 200, o);
                return;
            }

            case "/api/stream":
                streamMjpeg(params, out);
                return;

            default:
                sendJson(out, 404, err("no such endpoint: " + path + " (try /api/help)"));
        }
    }

    /** Settings changes need a moment to take effect when the AE loop is still running. */
    private long defaultSettle(Map<String, String> params) {
        boolean changed = false;
        for (String k : params.keySet()) {
            if (!k.equals("timeout") && !k.equals("settle") && !k.equals("t") && !k.equals("_")) {
                changed = true;
                break;
            }
        }
        if (!changed) return 0;
        return engine.snapshot().aeAuto ? 350 : 120;
    }

    private JSONObject applyParams(Map<String, String> params) throws Exception {
        JSONObject o = new JSONObject();
        if (params.isEmpty()) {
            o.put("ok", true);
            o.put("settings", engine.snapshot().toJson());
            return o;
        }
        CamSettings s = "1".equals(params.get("reset")) ? new CamSettings() : engine.snapshot();
        StringBuilder problems = new StringBuilder();
        s.apply(params, engine.caps(), problems);
        if (problems.length() > 0) {
            o.put("ok", false);
            o.put("error", problems.toString().trim());
            o.put("settings", engine.snapshot().toJson());
            return o;
        }
        CamSettings applied = engine.update(s);
        o.put("ok", true);
        o.put("settings", applied.toJson());
        return o;
    }

    // -------------------------------------------------------------- stream

    private void streamMjpeg(Map<String, String> params, BufferedOutputStream out) throws Exception {
        JSONObject applied = applyParams(params);
        if (!applied.optBoolean("ok", true)) { sendJson(out, 400, applied); return; }

        double fps = clampDouble(doubleParam(params, "fps", 10), 0.1, 30);
        long minIntervalMs = (long) (1000.0 / fps);
        long maxFrames = longParam(params, "n", Long.MAX_VALUE);

        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: multipart/x-mixed-replace; boundary=").append(BOUNDARY).append("\r\n")
            .append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            .append("Pragma: no-cache\r\n")
            .append("Connection: close\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        engine.addStreamClient(1);
        try {
            long seq = 0;
            long sent = 0;
            while (running && sent < maxFrames) {
                long t0 = System.currentTimeMillis();
                byte[] jpeg;
                try {
                    jpeg = engine.frameAfter(seq, 5000);
                    seq = engine.currentSeq();
                } catch (Exception e) {
                    // A stalled camera should not kill the connection; keep the client waiting.
                    Thread.sleep(200);
                    continue;
                }
                String part = "--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + jpeg.length + "\r\n\r\n";
                out.write(part.getBytes(StandardCharsets.US_ASCII));
                out.write(jpeg);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();     // throws once the client goes away, ending the stream
                sent++;

                long spent = System.currentTimeMillis() - t0;
                if (spent < minIntervalMs) Thread.sleep(minIntervalMs - spent);
            }
        } finally {
            engine.addStreamClient(-1);
        }
    }

    // ------------------------------------------------------------ plumbing

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int e = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(e < 0 ? pair : pair.substring(0, e), "UTF-8");
                String v = e < 0 ? "" : URLDecoder.decode(pair.substring(e + 1), "UTF-8");
                m.put(k.trim().toLowerCase(Locale.US), v.trim());
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static void sendText(OutputStream out, int code, String type, String body) throws IOException {
        sendBytes(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(OutputStream out, int code, JSONObject body) throws IOException {
        String text;
        try {
            text = body.toString(2);
        } catch (org.json.JSONException e) {
            text = body.toString();
        }
        sendBytes(out, code, "application/json; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body) throws IOException {
        sendBytes(out, code, type, body, "");
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body,
                                  String extraHeaders) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + extraHeaders
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String reason(int c) {
        switch (c) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }

    private static JSONObject err(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static long longParam(Map<String, String> p, String k, long dflt) {
        try { return p.containsKey(k) ? Long.parseLong(p.get(k)) : dflt; }
        catch (Exception e) { return dflt; }
    }

    private static double doubleParam(Map<String, String> p, String k, double dflt) {
        try { return p.containsKey(k) ? Double.parseDouble(p.get(k)) : dflt; }
        catch (Exception e) { return dflt; }
    }

    private static long clampLong(long v, long lo, long hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double clampDouble(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
