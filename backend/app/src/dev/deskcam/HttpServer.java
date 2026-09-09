package dev.deskcam;

import android.util.Log;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small HTTP/1.1 server exposing the camera over the LAN.
 *
 * Everything is reachable with a plain GET and query parameters, because the intended
 * clients are curl one-liners and coding agents, not a browser form.
 */
public class HttpServer implements Runnable {

    private static final String TAG = CameraEngine.TAG;
    private static final String BOUNDARY = "deskcamframe";

    /**
     * The most requests served at once.
     *
     * The pool used to be unbounded, standing in front of a decode of about 48 MB. Enough
     * simultaneous requests would therefore exhaust the heap however carefully each one
     * was bounded on its own.
     */
    private static final int MAX_HANDLERS = 24;

    /** How many frames in a row a stream may fail to get before it gives up. */
    private static final int STREAM_FAILURE_LIMIT = 25;

    private final CameraEngine engine;
    private final int port;
    private final String token;

    private ServerSocket serverSocket;
    /** Connections being served right now, for the display on the phone. */
    private static final AtomicInteger LIVE = new AtomicInteger();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, MAX_HANDLERS, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    private volatile boolean running = false;

    /** Status and size of the response this thread sent, for the request log. */
    private static final ThreadLocal<int[]> SENT = ThreadLocal.withInitial(() -> new int[]{200, 0});

    public HttpServer(CameraEngine engine, int port, String token) {
        this.engine = engine;
        this.port = port;
        this.token = (token == null || token.isEmpty()) ? null : token;
    }

    /** A request that is wrong in a way the caller can fix. Always an HTTP 400. */
    static class BadRequest extends Exception {
        private static final long serialVersionUID = 1L;

        BadRequest(String m) { super(m); }
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
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {
            Log.d(TAG, "closing the listening socket: " + e);
        }
        pool.shutdownNow();
    }

    @Override
    public void run() {
        while (running) {
            Socket s = null;
            try {
                s = serverSocket.accept();
                final Socket accepted = s;
                pool.execute(() -> handleSafely(accepted));
            } catch (RejectedExecutionException busy) {
                refuse(s, 503, "too many requests at once; try again");
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept", e);
            } catch (Throwable t) {
                // The accept loop is the whole server. Nothing watches it, so anything it
                // lets through stops every future request with no message anywhere.
                Log.e(TAG, "accept loop", t);
                refuse(s, 500, "the server could not take this connection");
            }
        }
    }

    private void refuse(Socket s, int code, String why) {
        if (s == null) return;
        try (Socket doomed = s) {
            sendJson(new BufferedOutputStream(doomed.getOutputStream()), code, err(why));
        } catch (Exception e) {
            Log.d(TAG, "refusing a connection: " + e);
        }
    }

    private void handleSafely(Socket s) {
        LIVE.incrementAndGet();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(30000);
            handle(s);
        } catch (Exception e) {
            Log.d(TAG, "connection ended: " + e);
        } catch (Throwable t) {
            // OutOfMemoryError is an Error, not an Exception. One request asking for a
            // resize to 100000 by 100000 used to walk straight past every catch here and
            // stop the process. A bad request must cost the caller, not the service.
            Log.e(TAG, "request failed hard", t);
            try {
                sendJson(new BufferedOutputStream(s.getOutputStream()), 500, err(String.valueOf(t)));
            } catch (Throwable ignored) {
                Log.d(TAG, "could not report the failure to the client");
            }
        } finally {
            LIVE.decrementAndGet();
            try { s.close(); } catch (IOException e) { Log.d(TAG, "socket close: " + e); }
        }
    }

    public static int liveConnections() { return LIVE.get(); }

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
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
            catch (NumberFormatException e) { Log.d(TAG, "unreadable content-length"); }
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

        long t0 = System.currentTimeMillis();
        SENT.set(new int[]{200, 0});
        try {
            route(path, params, out, peerAddress(sock));
        } catch (BadRequest bad) {
            sendJson(out, 400, err(bad.getMessage()));
        } catch (IllegalArgumentException bad) {
            // The engine refuses a request it cannot serve: a camera id that does not
            // exist, a burst that will not fit, an output larger than the heap.
            sendJson(out, 400, err(String.valueOf(bad.getMessage())));
        } catch (Exception e) {
            Log.w(TAG, "handler " + path, e);
            try { sendJson(out, 500, err(e.toString())); } catch (Exception ignored) {
                Log.d(TAG, "could not send the error for " + path);
            }
        } finally {
            int[] s = SENT.get();
            RequestLog.record(path, s[0], System.currentTimeMillis() - t0, s[1]);
        }
    }

    private static String peerAddress(Socket s) {
        java.net.InetAddress a = s.getInetAddress();
        return a == null ? "127.0.0.1" : a.getHostAddress();
    }

    private boolean authorised(Map<String, String> params, Map<String, String> headers) {
        if (token.equals(params.get("token"))) return true;
        String a = headers.get("authorization");
        return a != null && a.startsWith("Bearer ") && token.equals(a.substring(7).trim());
    }

    private void route(String path, Map<String, String> params, BufferedOutputStream out,
                       String peer) throws Exception {
        switch (path) {
            case "/":
            case "/index.html":
                sendText(out, 200, "text/html; charset=utf-8", WebUi.page());
                return;

            case "/api/help":
                sendJson(out, 200, WebUi.help());
                return;

            case "/api/cameras": {
                apply(params);
                sendJson(out, 200, new JSONObject().put("cameras", engine.listCameras()));
                return;
            }

            case "/api/status": {
                apply(params);
                JSONObject o = engine.status();
                o.put("ok", true);
                sendJson(out, 200, o);
                return;
            }

            case "/api/set": {
                apply(params);
                JSONObject o = engine.status();
                o.put("ok", true);
                // /api/set has no picture to present, so a presentation parameter here
                // does nothing. Saying so beats leaving the caller to notice that the
                // value they set is not in the reply.
                String presented = presentationNames(params);
                if (!presented.isEmpty()) {
                    o.put("note", presented + " apply to the request that returns a picture, "
                            + "not to the camera. Send them to /api/still, /api/frame or "
                            + "/api/burst instead. See decision D9.");
                }
                sendJson(out, 200, o);
                return;
            }

            case "/api/reset": {
                params.put("reset", "1");
                apply(params);
                JSONObject o = engine.status();
                o.put("ok", true);
                sendJson(out, 200, o);
                return;
            }

            case "/api/af": {
                apply(params);
                engine.triggerAf();
                Thread.sleep(longParam(params, "wait", 700, 0, 5000));
                JSONObject o = engine.status();
                o.put("ok", true);
                sendJson(out, 200, o);
                return;
            }

            case "/api/still": {
                CamSettings req = apply(params);
                settle(params, req);
                CameraEngine.Shot shot = engine.captureStill(req, longParam(params, "timeout", 8000, 100, 60000));
                sendBytes(out, 200, "image/jpeg", shot.bytes, provenanceHeader(shot));
                return;
            }

            case "/api/frame": {
                CamSettings req = apply(params);
                long settled = settle(params, req);
                // The camera keeps requests in flight, so the next frame or two can still
                // carry the previous settings. Skip them after any settings change.
                int skip = (int) longParam(params, "fresh", settled > 0 ? 2 : 0, 0, 30);
                CameraEngine.Shot shot = engine.grabFrame(req,
                        longParam(params, "timeout", 8000, 100, 60000), skip);
                sendBytes(out, 200, "image/jpeg", shot.bytes, provenanceHeader(shot));
                return;
            }

            case "/api/raw": {
                CamSettings req = apply(params);
                settle(params, req);
                CameraEngine.Shot shot = engine.captureRaw(req,
                        longParam(params, "timeout", 12000, 100, 60000));
                // The DNG holds the whole sensor array, so tell the client where the user
                // was aimed rather than silently discarding the framing.
                sendBytes(out, 200, "image/x-adobe-dng", shot.bytes,
                        "X-DeskCam-ROI: " + engine.rawRoiHeader(req) + "\r\n" + provenanceHeader(shot));
                return;
            }

            case "/api/burst": {
                CamSettings req = apply(params);
                int maxBurst = engine.caps().maxBurst;
                int n = (int) longParam(params, "n", Math.min(8, maxBurst), 1, maxBurst);
                settle(params, req);
                long t0 = System.currentTimeMillis();
                CameraEngine.Burst burst = engine.captureBurst(req, n,
                        longParam(params, "timeout", 5000L + 1500L * n, 100, 300000));
                long ms = System.currentTimeMillis() - t0;
                java.util.List<String> names = new java.util.ArrayList<>(burst.frames.size());
                for (int i = 0; i < burst.frames.size(); i++) {
                    names.add(String.format(Locale.US, "burst-%03d.jpg", i));
                }
                // The archive goes straight to the socket. Its length is arithmetic, so
                // nothing needs to hold a second and a third copy of the burst.
                long length = Tar.contentLength(burst.frames);
                double fps = ms > 0 ? burst.frames.size() * 1000.0 / ms : 0;
                // A burst that ran out of time used to answer 200 with fewer frames than
                // were asked for, so a client had to compare a header against its own
                // request to notice. 206 says it in the status line.
                int code = burst.complete() ? 200 : 206;
                sendHead(out, code, "application/x-tar", length,
                        "X-DeskCam-Frames: " + burst.frames.size() + "\r\n"
                        + "X-DeskCam-Frames-Requested: " + burst.requested + "\r\n"
                        + "X-DeskCam-Millis: " + ms + "\r\n"
                        + String.format(Locale.US, "X-DeskCam-Fps: %.2f\r\n", fps)
                        + provenanceHeader(burst.provenance));
                Tar.writeTo(out, names, burst.frames);
                out.flush();
                return;
            }

            case "/api/orientation": {
                apply(params);
                sendJson(out, 200, engine.orientation());
                return;
            }

            case "/api/shadingmap": {
                // Turn the map on unless the caller said otherwise, then wait for a frame
                // that was actually taken with it on.
                if (!params.containsKey("shadingmap")) params.put("shadingmap", "1");
                CamSettings req = apply(params);
                try {
                    engine.grabFrame(req, longParam(params, "timeout", 8000, 100, 60000), 2);
                } catch (Exception e) {
                    Log.d(TAG, "shading map frame: " + e);
                }
                sendJson(out, 200, engine.shadingMap());
                return;
            }

            case "/api/nettest": {
                // Diagnostic: can this app reach the network outbound at all? It answered
                // that question, and it was also a port scanner that anyone on the network
                // could drive through the phone without a password. It now probes only the
                // address the request came from, which is the machine that wants to know.
                apply(params);
                int tport = (int) longParam(params, "port", 80, 1, 65535);
                JSONObject o = new JSONObject();
                o.put("target", peer + ":" + tport);
                o.put("note", "this endpoint only probes the address the request came from");
                long t0 = System.currentTimeMillis();
                try (Socket probe = new Socket()) {
                    probe.connect(new java.net.InetSocketAddress(peer, tport), 4000);
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
    private long settle(Map<String, String> params, CamSettings req) throws Exception {
        boolean changed = false;
        for (String k : params.keySet()) {
            Params.P p = Params.get(k);
            if (p != null && p.kind == Params.Kind.CAMERA) { changed = true; break; }
        }
        long dflt = changed ? (req.aeAuto ? 350 : 120) : 0;
        long ms = longParam(params, "settle", dflt, 0, 5000);
        if (ms > 0) Thread.sleep(ms);
        return ms;
    }

    /**
     * Reads the parameters of a request, keeps the camera ones, and hands back the
     * settings this one request should use.
     *
     * Camera state persists; presentation lives and dies with the request that named it.
     * That is decision D9. Every endpoint goes through here, so an unknown parameter or a
     * bad value is an error everywhere and not only on the seven endpoints that used to
     * check (rules R3 and R5).
     */
    private CamSettings apply(Map<String, String> params) throws Exception {
        CamSettings s = "1".equals(params.get("reset")) ? new CamSettings() : engine.snapshot();
        StringBuilder problems = new StringBuilder();
        s.apply(params, engine.caps(), problems);
        if (problems.length() > 0) throw new BadRequest(problems.toString().trim());

        if (!touchesCamera(params)) return s;
        CamSettings stored = engine.update(s);
        stored.outW = s.outW;
        stored.outH = s.outH;
        stored.jpegQuality = s.jpegQuality;
        return stored;
    }

    private static String presentationNames(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (String k : params.keySet()) {
            Params.P p = Params.get(k);
            if (p != null && p.kind == Params.Kind.PRESENTATION) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(k);
            }
        }
        return sb.toString();
    }

    private static boolean touchesCamera(Map<String, String> params) {
        if ("1".equals(params.get("reset"))) return true;
        for (String k : params.keySet()) {
            Params.P p = Params.get(k);
            if (p != null && p.kind == Params.Kind.CAMERA) return true;
        }
        return false;
    }

    private static String provenanceHeader(CameraEngine.Shot shot) {
        return provenanceHeader(shot.provenance);
    }

    /**
     * The record of the frame, on the response that carries the frame.
     *
     * The CLI used to build its sidecar from a second /api/status request made after the
     * capture came back, so the state could move between the two and the sidecar could
     * describe a different moment.
     */
    private static String provenanceHeader(JSONObject provenance) {
        if (provenance == null) return "";
        String json = provenance.toString().replaceAll("[\\r\\n]", " ");
        if (json.length() > 7000) return "";     // do not risk a header nobody can parse
        return "X-DeskCam-Provenance: " + json + "\r\n";
    }

    // -------------------------------------------------------------- stream

    /**
     * The live view.
     *
     * A stream is a window onto the camera, never a way to set it. Its parameters apply
     * to this stream alone; a camera parameter is refused with the endpoint that does
     * take it. Before this, a browser tab reconnecting its stream wrote its own rotation
     * into the shared state and quietly changed the next capture an agent took.
     */
    private void streamMjpeg(Map<String, String> params, BufferedOutputStream out) throws Exception {
        StringBuilder refused = new StringBuilder();
        for (String k : params.keySet()) {
            Params.P p = Params.get(k);
            if (p == null) {
                refused.append("unknown parameter '").append(k).append("'; ");
            } else if (p.kind == Params.Kind.CAMERA) {
                refused.append("'").append(k).append("' changes the camera, so /api/stream "
                        + "does not take it; send it to /api/set; ");
            }
        }
        if (refused.length() > 0) throw new BadRequest(refused.toString().trim());

        CamSettings req = engine.snapshot();
        StringBuilder problems = new StringBuilder();
        req.apply(params, engine.caps(), problems);
        if (problems.length() > 0) throw new BadRequest(problems.toString().trim());

        double fps = Geom.clampDouble(doubleParam(params, "fps", 10), 0.1, 30);
        long minIntervalMs = (long) (1000.0 / fps);
        long maxFrames = longParam(params, "n", Long.MAX_VALUE, 1, Long.MAX_VALUE);

        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                + "Pragma: no-cache\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        engine.addStreamClient(1);
        long streamed = 0;
        try {
            long seq = 0;
            long sent = 0;
            int failures = 0;
            while (running && sent < maxFrames) {
                long t0 = System.currentTimeMillis();
                byte[] jpeg;
                try {
                    jpeg = engine.frameAfter(req, seq, 5000);
                    seq = engine.currentSeq();
                    failures = 0;
                } catch (Exception e) {
                    // A stall of a second or two should not kill the connection. A camera
                    // that never comes back should, or the thread lives until the process
                    // does.
                    if (++failures >= STREAM_FAILURE_LIMIT) {
                        Log.w(TAG, "stream giving up after " + failures + " failures: " + e);
                        return;
                    }
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
                streamed += jpeg.length;

                long spent = System.currentTimeMillis() - t0;
                if (spent < minIntervalMs) Thread.sleep(minIntervalMs - spent);
            }
        } catch (IOException closed) {
            // A viewer closing the tab is how a stream normally ends. Letting it reach
            // the handler made every stream finish as a 500 and count as an error.
            Log.d(TAG, "stream client left after " + streamed + " bytes");
        } finally {
            engine.addStreamClient(-1);
            SENT.get()[1] = (int) Math.min(streamed, Integer.MAX_VALUE);
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
            } catch (Exception e2) {
                Log.d(TAG, "undecodable query pair: " + pair);
            }
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

    /** The head of a response whose body is written straight to the socket after it. */
    private static void sendHead(OutputStream out, int code, String type, long length,
                                 String extraHeaders) throws IOException {
        int[] s = SENT.get();
        s[0] = code;
        s[1] = (int) Math.min(length, Integer.MAX_VALUE);
        out.write(headerBlock(code, type, length, extraHeaders).getBytes(StandardCharsets.US_ASCII));
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body,
                                  String extraHeaders) throws IOException {
        int[] s = SENT.get();
        s[0] = code;
        s[1] = body.length;
        String head = headerBlock(code, type, body.length, extraHeaders);
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String headerBlock(int code, String type, long length, String extraHeaders) {
        return "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + extraHeaders
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type\r\n"
                + "Connection: close\r\n\r\n";
    }

    private static String reason(int c) {
        switch (c) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

    private static JSONObject err(String msg) {
        try { return new JSONObject().put("ok", false).put("error", msg); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * A router parameter, with its range.
     *
     * It used to return the default whenever the value could not be read, so `timeout=x`
     * looked exactly like no timeout at all. That is the opposite of rule R5: a value the
     * caller wrote and the server could not use is an error, not a silence.
     */
    private static long longParam(Map<String, String> p, String k, long dflt, long lo, long hi)
            throws BadRequest {
        String v = p.get(k);
        if (v == null || v.isEmpty()) return dflt;
        long parsed;
        try {
            parsed = Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new BadRequest("bad value for '" + k + "': '" + v + "' is not a whole number");
        }
        if (parsed < lo || parsed > hi) {
            throw new BadRequest(k + " must be between " + lo + " and " + hi + ", got " + parsed);
        }
        return parsed;
    }

    private static double doubleParam(Map<String, String> p, String k, double dflt) throws BadRequest {
        String v = p.get(k);
        if (v == null || v.isEmpty()) return dflt;
        try {
            double d = Double.parseDouble(v.trim());
            if (Double.isNaN(d)) throw new NumberFormatException("nan");
            return d;
        } catch (NumberFormatException e) {
            throw new BadRequest("bad value for '" + k + "': '" + v + "' is not a number");
        }
    }
}
