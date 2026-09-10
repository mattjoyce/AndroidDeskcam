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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** The part separator of a script's answer. A different stream, a different word. */
    private static final String SCRIPT_BOUNDARY = "deskcamstep";

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
    private final Key key;

    private ServerSocket serverSocket;
    /** Connections being served right now, for the display on the phone. */
    private static final AtomicInteger LIVE = new AtomicInteger();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2, MAX_HANDLERS, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    private volatile boolean running = false;

    /** Status and size of the response this thread sent, for the request log. */
    private static final ThreadLocal<int[]> SENT = ThreadLocal.withInitial(() -> new int[]{200, 0});

    /**
     * Where the access key comes from, asked at every request rather than kept.
     *
     * It used to be a final String taken when the server was built. CamService returns
     * early from onStartCommand when it is already running, so pairing a key into a
     * running service wrote the key to the preferences and left this server enforcing
     * the one it started with. The console then said a key was set while the camera
     * still answered anyone on the network, which is the worst possible direction for
     * that mistake to run. The workstation console had the same fault for the same
     * reason, and card 29 fixed it the same way: one identity, read where it is used.
     */
    public interface Key {
        /** The key right now. Empty or null means the camera is open. */
        String current();
    }

    public HttpServer(CameraEngine engine, int port, Key key) {
        this.engine = engine;
        this.port = port;
        this.key = key;
    }

    /** A request that is wrong in a way the caller can fix. Always an HTTP 400. */
    static class BadRequest extends Exception {
        private static final long serialVersionUID = 1L;

        BadRequest(String m) { super(m); }
    }

    /**
     * A request that is fine, at a moment that is not. Always an HTTP 409.
     *
     * There is one camera, and a script holds it for its duration. A 400 would say the
     * caller wrote something wrong, which is the opposite of true here: the same request
     * a second later is correct. Card 57.
     */
    static class Busy extends Exception {
        private static final long serialVersionUID = 1L;

        Busy(String m) { super(m); }
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

        String body = "";
        if ("POST".equals(method)) {
            int len = 0;
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
            catch (NumberFormatException e) { Log.d(TAG, "unreadable content-length"); }
            if (len > 0 && len < 1 << 20) {
                byte[] raw = new byte[len];
                int read = 0;
                while (read < len) {
                    int r = in.read(raw, read, len - read);
                    if (r < 0) break;
                    read += r;
                }
                body = new String(raw, 0, read, StandardCharsets.UTF_8).trim();
                // A script is a plain-text tape of verbs, so its body is the request and
                // not a bag of parameters: it goes to the handler as it was written. Every
                // other POST accepts either a query string or a flat JSON object.
                String bodyStr = "/api/script".equals(path) ? "" : body;
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

        if (!Access.allowed(key.current(), params.get("token"), headers.get("authorization"))) {
            sendJson(out, 401, err("unauthorised: supply ?token=... or an Authorization: Bearer header"));
            return;
        }
        params.remove("token");

        long t0 = System.currentTimeMillis();
        SENT.set(new int[]{200, 0});
        try {
            route(path, params, body, out, peerAddress(sock));
        } catch (BadRequest bad) {
            sendJson(out, 400, err(bad.getMessage()));
        } catch (Busy busy) {
            sendJson(out, 409, err(busy.getMessage()));
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

    /**
     * The endpoints that are a connection rather than a value, and then everything else.
     *
     * A stream and a script own the socket for their duration: one writes frames until the
     * viewer leaves, the other writes an event and a picture per step. Every other endpoint
     * produces an Answer, and this decides how it is sent. That is what lets a script verb
     * call the same handler /api/still calls, instead of a second copy of it. Card 57.
     */
    private void route(String path, Map<String, String> params, String body,
                       BufferedOutputStream out, String peer) throws Exception {
        switch (path) {
            case "/":
            case "/index.html":
                sendText(out, 200, "text/html; charset=utf-8", WebUi.page());
                return;

            case "/api/stream":
                streamMjpeg(params, out);
                return;

            case "/api/script":
                runScript(body, params, out);
                return;

            default:
                Answer answer = answer(path, params, peer);
                if (answer == null) {
                    sendJson(out, 404, err("no such endpoint: " + path + " (try /api/help)"));
                    return;
                }
                send(out, answer);
        }
    }

    /**
     * Every endpoint that produces a value.
     *
     * One switch, read by the router and by a script's verb dispatcher, so a tape and a
     * URL cannot come to disagree about what SNAP means.
     */
    private Answer answer(String path, Map<String, String> params, String peer)
            throws Exception {
        switch (path) {
            case "/api/help":
                return Answer.json(WebUi.help());

            case "/api/cameras": {
                apply(params);
                return Answer.json(new JSONObject().put("cameras", engine.listCameras()));
            }

            case "/api/status": {
                apply(params);
                // sharpness=1 pays for one preview frame so the number describes now. The
                // focus loop of card 9 is: set the focus, ask for this, compare. Without
                // it the reading is whatever the last frame was, and its age says so.
                if (params.containsKey("sharpness") && Parse.bool(params.get("sharpness"))) {
                    engine.demandFrame(longParam(params, "timeout", 1500, 100, 60000),
                            (int) longParam(params, "fresh", 2, 0, 30));
                }
                JSONObject o = engine.status();
                o.put("ok", true);
                return Answer.json(o);
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
                return Answer.json(o);
            }

            case "/api/reset": {
                params.put("reset", "1");
                apply(params);
                JSONObject o = engine.status();
                o.put("ok", true);
                return Answer.json(o);
            }

            case "/api/af": {
                apply(params);
                engine.triggerAf();
                Thread.sleep(longParam(params, "wait", 700, 0, 5000));
                JSONObject o = engine.status();
                o.put("ok", true);
                return Answer.json(o);
            }

            case "/api/still": {
                CamSettings req = apply(params);
                settle(params, req);
                CameraEngine.Shot shot = engine.captureStill(req, longParam(params, "timeout", 8000, 100, 60000));
                return Answer.file("still.jpg", "image/jpeg", shot.bytes, shot.provenance,
                        provenanceHeader(shot));
            }

            case "/api/frame": {
                CamSettings req = apply(params);
                long settled = settle(params, req);
                // The camera keeps requests in flight, so the next frame or two can still
                // carry the previous settings. Skip them after any settings change.
                int skip = (int) longParam(params, "fresh", settled > 0 ? 2 : 0, 0, 30);
                CameraEngine.Shot shot = engine.grabFrame(req,
                        longParam(params, "timeout", 8000, 100, 60000), skip);
                return Answer.file("frame.jpg", "image/jpeg", shot.bytes, shot.provenance,
                        provenanceHeader(shot));
            }

            case "/api/raw": {
                CamSettings req = apply(params);
                settle(params, req);
                CameraEngine.Shot shot = engine.captureRaw(req,
                        longParam(params, "timeout", 12000, 100, 60000));
                // The DNG holds the whole sensor array, so tell the client where the user
                // was aimed rather than silently discarding the framing.
                return Answer.file("raw.dng", "image/x-adobe-dng", shot.bytes, shot.provenance,
                        "X-DeskCam-ROI: " + engine.rawRoiHeader(req) + "\r\n"
                        + provenanceHeader(shot));
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
                return Answer.archive(code, names, burst.frames, burst.provenance,
                        "X-DeskCam-Frames: " + burst.frames.size() + "\r\n"
                        + "X-DeskCam-Frames-Requested: " + burst.requested + "\r\n"
                        + "X-DeskCam-Millis: " + ms + "\r\n"
                        + String.format(Locale.US, "X-DeskCam-Fps: %.2f\r\n", fps)
                        + provenanceHeader(burst.provenance));
            }

            case "/api/focussweep": {
                CamSettings req = apply(params);
                CamSettings.Caps caps = engine.caps();
                float from = floatParam(params, "from", 0f, 0f, caps.minFocusDiopters);
                float to = floatParam(params, "to", caps.minFocusDiopters, 0f, caps.minFocusDiopters);
                int steps = (int) longParam(params, "steps", 12, 2, caps.maxBurst);

                JSONObject manifest = new JSONObject();
                manifest.put("walk", "focus");
                manifest.put("from_diopters", CamSettings.round3(from));
                manifest.put("to_diopters", CamSettings.round3(to));
                manifest.put("steps", steps);
                manifest.put("steps_are", "equal in diopters, which is equal in depth of "
                        + "field. The lens calibration is APPROXIMATE, so these are ordered "
                        + "positions and not distances.");

                java.util.List<String> labels = new java.util.ArrayList<>();
                java.util.List<JSONObject> asked = new java.util.ArrayList<>();
                for (int i = 0; i < steps; i++) {
                    float at = Geom.sweepStep(from, to, i, steps);
                    labels.add(String.format(Locale.US, "%.3fd", at));
                    asked.add(new JSONObject().put("focus_diopters_asked", CamSettings.round3(at)));
                }
                return walkAnswer(params, req, engine.focusSteps(req, from, to, steps),
                        manifest, "focus", labels, asked);
            }

            case "/api/focushunt": {
                CamSettings req = apply(params);
                CamSettings.Caps caps = engine.caps();
                float from = floatParam(params, "from", 0f, 0f, caps.minFocusDiopters);
                float to = floatParam(params, "to", caps.minFocusDiopters, 0f, caps.minFocusDiopters);
                if (Math.abs(to - from) < 0.05f) {
                    throw new BadRequest(String.format(Locale.US,
                            "a hunt needs a range to search, and from=%.3f to=%.3f is one "
                            + "position. To set the focus, use /api/set focus=%.3f.",
                            from, to, from));
                }
                // Nine and five. The curve of card 56 spans about four diopters at half
                // height, so nine over the whole ten-diopter range cannot step over it,
                // and five inside one coarse step land within a fortieth of a diopter of
                // the peak, which is finer than the lens repeats.
                int coarse = (int) longParam(params, "coarse", 9, 3, 30);
                int fine = (int) longParam(params, "fine", 5, 0, 30);
                // Not the settle() helper: that one sleeps once for the request, and this
                // needs the wait at every step of the walk. The lens settles well inside
                // 300 ms on this device, and the frames discarded after it cost time too.
                long settleMs = longParam(params, "settle", 150, 0, 5000);
                int fresh = (int) longParam(params, "fresh", 3, 0, 30);
                // A hunt that refuses answers 200 with ok:false and the curve it walked.
                // The request was good, the camera worked, and the curve is the useful
                // part of the answer: none of that is a 4xx, and an agent that only reads
                // the status line would throw away the reason it was refused.
                return Answer.json(engine.focusHunt(req, from, to, coarse, fine, settleMs,
                        fresh, longParam(params, "timeout", 2000, 100, 60000)));
            }

            case "/api/bracket": {
                CamSettings req = apply(params);
                CamSettings.Caps caps = engine.caps();
                long given;
                try {
                    given = Parse.exposureNs(params.getOrDefault("base", "1/240"));
                } catch (NumberFormatException e) {
                    throw new BadRequest("bad value for 'base': " + e.getMessage());
                }
                if (given < caps.minExposureNs || given > caps.maxExposureNs) {
                    throw new BadRequest("'base' must be between "
                            + CamSettings.humanExposure(caps.minExposureNs) + " and "
                            + CamSettings.humanExposure(caps.maxExposureNs) + ", got "
                            + CamSettings.humanExposure(given));
                }
                final long baseNs = given;
                int stops = (int) longParam(params, "stops", 4, 2, caps.maxBurst);
                long longest = baseNs << (stops - 1);
                if (longest > caps.maxExposureNs) {
                    throw new BadRequest("a bracket of " + stops + " stops from "
                            + CamSettings.humanExposure(baseNs) + " ends at "
                            + CamSettings.humanExposure(longest) + ", and this sensor stops at "
                            + CamSettings.humanExposure(caps.maxExposureNs)
                            + ". Use fewer stops or a shorter base.");
                }

                JSONObject manifest = new JSONObject();
                manifest.put("walk", "exposure");
                manifest.put("base_ns", baseNs);
                manifest.put("base_human", CamSettings.humanExposure(baseNs));
                manifest.put("stops", stops);
                manifest.put("steps_are", "powers of two from the base, so every frame is "
                        + "one stop from the next AND a whole number of base periods. Set "
                        + "the base to one period of the panel's PWM: an exposure that is "
                        + "not a whole number of periods reads a different part of the duty "
                        + "cycle, and the frames then disagree about a panel that never "
                        + "changed.");
                JSONArray warnings = new JSONArray();
                if (req.iso > caps.maxAnalogIso) {
                    warnings.put("iso " + req.iso + " is above this sensor's analogue limit of "
                            + caps.maxAnalogIso + ", so the extra gain is arithmetic on values "
                            + "already read. Drop the ISO and apply the gain on the "
                            + "workstation, where the numbers are in front of you.");
                }
                manifest.put("warnings", warnings);

                java.util.List<String> labels = new java.util.ArrayList<>();
                java.util.List<JSONObject> asked = new java.util.ArrayList<>();
                for (int i = 0; i < stops; i++) {
                    labels.add(Parse.fileSafe(CamSettings.humanExposure(baseNs << i)));
                    asked.add(new JSONObject()
                            .put("exposure_ns_asked", baseNs << i)
                            .put("base_periods_asked", 1L << i));
                }
                return walkAnswer(params, req, engine.exposureSteps(req, baseNs, stops),
                        manifest, "exposure", labels, asked);
            }

            case "/api/walk": {
                CamSettings req = apply(params);
                CamSettings.Caps caps = engine.caps();

                String vary = params.getOrDefault("vary", "").trim().toLowerCase(Locale.US);
                Params.P p = Params.get(vary);
                if (vary.isEmpty() || p == null) {
                    throw new BadRequest("'vary' names the camera parameter to walk, e.g. "
                            + "vary=torch. '" + vary + "' is not a parameter this build knows; "
                            + "see /api/help.");
                }
                if (p.kind != Params.Kind.CAMERA) {
                    // Presentation dies with the request that named it and Router is read
                    // by the router, so neither means anything across a set of frames.
                    throw new BadRequest("'" + vary + "' is a " + p.kind.name().toLowerCase(Locale.US)
                            + " parameter, and only camera state can be walked. "
                            + (p.kind == Params.Kind.PRESENTATION
                                ? "Presentation applies to the one request that names it and is "
                                  + "then forgotten, so it is the same camera in every frame."
                                : "The router reads this one; it never reaches the camera.")
                            + " See decision D9.");
                }

                String raw = params.getOrDefault("values", "").trim();
                if (raw.isEmpty()) {
                    throw new BadRequest("'values' is the list to walk, e.g. "
                            + "values=0,10,20,45. This endpoint knows no step rule and "
                            + "invents no values: /api/focussweep and /api/bracket are the "
                            + "ones that know where their steps belong.");
                }
                java.util.List<String> values = new java.util.ArrayList<>();
                for (String v : raw.split(",")) {
                    if (!v.trim().isEmpty()) values.add(v.trim());
                }
                if (values.size() < 2 || values.size() > caps.maxBurst) {
                    throw new BadRequest("a walk takes between 2 and " + caps.maxBurst
                            + " values on this device, and was given " + values.size()
                            + ". One frame at one value is /api/still.");
                }

                JSONObject manifest = new JSONObject();
                manifest.put("walk", vary);
                manifest.put("values", new JSONArray(values));
                manifest.put("steps_are", "the values given, in the order given. This "
                        + "endpoint knows no step rule: /api/focussweep steps in diopters "
                        + "and /api/bracket in whole PWM periods because those rules are "
                        + "knowledge, and a list you typed cannot hand you a wrong one.");

                java.util.List<String> labels = new java.util.ArrayList<>();
                java.util.List<JSONObject> asked = new java.util.ArrayList<>();
                for (String v : values) {
                    labels.add(Parse.fileSafe(v));
                    asked.add(new JSONObject().put("vary", vary).put("value_asked", v));
                }
                return walkAnswer(params, req, engine.valueSteps(req, vary, values),
                        manifest, vary, labels, asked);
            }

            case "/api/orientation": {
                apply(params);
                return Answer.json(engine.orientation());
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
                return Answer.json(engine.shadingMap());
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
                return Answer.json(o);
            }

            default:
                return null;
        }
    }

    /**
     * Sends an Answer as its HTTP response, in the shape its endpoint has always used.
     *
     * An archive goes to the socket a member at a time. Its length is arithmetic, so a
     * burst is never held a second and a third time only to find out how long it is.
     */
    private static void send(BufferedOutputStream out, Answer a) throws IOException {
        switch (a.shape) {
            case JSON:
                sendJson(out, a.status, a.record);
                return;
            case FILE:
                sendBytes(out, a.status, a.contentType, a.files.get(0), a.headers);
                return;
            case ARCHIVE:
            default:
                sendHead(out, a.status, "application/x-tar", Tar.contentLength(a.files),
                        a.headers);
                Tar.writeTo(out, a.names, a.files);
                out.flush();
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
        // A script holds the camera. Reading is still allowed, because an agent watching
        // the tape run is not interfering with it; changing the camera under a running
        // tape is what makes every multi-step sequence racy, and is the whole reason
        // /api/script exists. The script's own steps come through here on the script's
        // thread and are not refused.
        if (touchesCamera(params) && engine.scriptHoldsCamera()) {
            throw new Busy("a script is running and holds the camera. It will finish or "
                    + "fail on its own; this request would have changed the camera "
                    + "underneath it. Read /api/status while you wait.");
        }
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

    // -------------------------------------------------------------- script

    /**
     * Runs a tape and answers with one ordered stream of events and pictures.
     *
     * The case for this is atomicity and not speed. A round trip on this LAN is about
     * 100 ms against a settle and a capture of several hundred, so a seven step sweep run
     * from the shell loses well under a second to the network. What it does lose is the
     * guarantee that nothing moved: the console polls the state every two seconds and can
     * post new settings, and a second agent can do anything at all, so every multi-step
     * sequence driven from outside is racy between one step and the next. A script holds
     * the camera for its duration, which is what the walk endpoints already do for their
     * own sequences and what this generalises.
     *
     * The answer is multipart/mixed rather than server-sent events, and that follows from
     * a boundary the specification already draws: SPEC 4.1 says the backend does not store
     * old captures, and it stores none. An events-only stream would have to name a file on
     * the phone for each capture, which means storage, a cleanup policy, a listing and a
     * download endpoint. Instead the JSON events and the pixels travel in the same ordered
     * stream, on the same machinery the MJPEG stream already uses, and the phone still
     * stores nothing. Card 57.
     */
    private void runScript(String body, Map<String, String> params, BufferedOutputStream out)
            throws Exception {
        // Nothing is submitted to a camera until the whole tape reads. A typo on the last
        // line of a ten step script costs nothing, rather than nine steps of nothing.
        List<Tape.Step> steps;
        try {
            steps = Tape.parse(body, name -> Params.get(name) != null);
        } catch (IllegalArgumentException bad) {
            throw new BadRequest(String.valueOf(bad.getMessage()));
        }
        for (String k : params.keySet()) {
            if (!"t".equals(k) && !"_".equals(k)) {
                throw new BadRequest("'" + k + "' was sent to /api/script in the query "
                        + "string. A script takes its parameters on the lines of the tape, "
                        + "where they say which step they belong to.");
            }
        }
        if (!engine.holdForScript()) {
            throw new Busy("a script is already running and holds the camera. One tape at "
                    + "a time: two would interleave their steps and neither would be the "
                    + "sequence it asked for.");
        }

        long started = System.currentTimeMillis();
        // Where to put the camera back if a step fails. A tape that finishes leaves the
        // camera where it put it, because a SET in a tape is a change the caller asked
        // for. A tape that stops half way leaves it somewhere nobody asked for: `SET
        // torch=45`, a failed SNAP, and the `SET torch=0` that never ran.
        CamSettings restore = engine.snapshot();
        long bytes = 0;

        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/mixed; boundary=" + SCRIPT_BOUNDARY + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        SENT.get()[0] = 200;

        try {
            JSONArray plan = new JSONArray();
            for (Tape.Step step : steps) plan.put(step.verb);
            bytes += writeEvent(out, new JSONObject()
                    .put("started", true)
                    .put("steps", steps.size())
                    .put("verbs", plan));

            for (Tape.Step step : steps) {
                long t0 = System.currentTimeMillis();
                JSONObject event = new JSONObject()
                        .put("step", step.index)
                        .put("line", step.line)
                        .put("verb", step.verb);
                try {
                    Answer a = runStep(step);
                    event.put("ok", true);
                    event.put("millis", System.currentTimeMillis() - t0);
                    JSONArray names = new JSONArray();
                    for (String name : step.path == null ? Collections.<String>emptyList()
                            : a.names) {
                        names.put(partName(step, name));
                    }
                    if (names.length() > 0) event.put("files", names);
                    if (step.path == null) {
                        event.put("waited_ms", step.waitMs);
                    } else {
                        event.put("result", a.record);
                    }
                    bytes += writeEvent(out, event);
                    // The pixels follow their own event, so a reader that has the event
                    // already knows what is arriving and what to call it.
                    for (int i = 0; a != null && i < a.files.size(); i++) {
                        String name = partName(step, a.names.get(i));
                        bytes += writePart(out, contentTypeFor(name), name, a.files.get(i));
                    }
                } catch (Exception failed) {
                    String why = failed instanceof BadRequest || failed instanceof Busy
                            || failed instanceof IllegalArgumentException
                            ? String.valueOf(failed.getMessage()) : failed.toString();
                    event.put("ok", false);
                    event.put("millis", System.currentTimeMillis() - t0);
                    event.put("error", why);
                    bytes += writeEvent(out, event);
                    bytes += writeEvent(out, finish(steps, step, why, restore, started));
                    SENT.get()[1] = (int) Math.min(bytes, Integer.MAX_VALUE);
                    Log.w(TAG, "script failed at step " + step.index + ": " + why);
                    return;
                }
            }
            bytes += writeEvent(out, finish(steps, null, null, null, started));
        } catch (IOException gone) {
            // The caller hung up. Nothing to report to, and the finally below still puts
            // the camera back and releases it.
            Log.d(TAG, "script client left: " + gone);
        } finally {
            SENT.get()[1] = (int) Math.min(bytes, Integer.MAX_VALUE);
            engine.releaseScript();
            // The closing delimiter belongs here and not after the try. A tape that
            // stopped at a failed step leaves by a return, and a multipart stream that
            // ends without it reads as a truncated connection: the reader on the other
            // side reported "unexpected EOF" and threw away the final event that said
            // what had failed and what the camera was put back to.
            try {
                out.write(("--" + SCRIPT_BOUNDARY + "--\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException gone) {
                Log.d(TAG, "script client left before the end: " + gone);
            }
        }
    }

    /** One step: a wait, or the same handler the endpoint of that verb answers with. */
    private Answer runStep(Tape.Step step) throws Exception {
        if (step.path == null) {
            if (step.waitMs > 0) Thread.sleep(step.waitMs);
            return null;
        }
        Answer a = answer(step.path, new LinkedHashMap<>(step.params), null);
        if (a == null) throw new BadRequest("no handler for " + step.verb);
        // An answer that says ok:false is a failed step, not a step with a sad result.
        // FOCUSHUNT is the one endpoint that can answer 200 that way: it walked the lens
        // and found no peak to choose. Carrying on would take the next capture out of
        // focus and call it a success, which is the failure this whole card is about.
        if (!a.record.optBoolean("ok", true)) {
            String why = a.record.optString("reason", a.record.optString("error", "it refused"));
            throw new BadRequest(step.verb + " did not succeed: " + why);
        }
        return a;
    }

    /**
     * The last event, which says how it ended.
     *
     * A tape that stopped part way gets the camera put back first, and the event says what
     * it was put back to. Leaving the torch on because the line that turned it off never
     * ran is the kind of failure a person discovers an hour later.
     */
    private JSONObject finish(List<Tape.Step> steps, Tape.Step failed, String why,
                              CamSettings restore, long started) throws Exception {
        JSONObject done = new JSONObject()
                .put("done", true)
                .put("ok", failed == null)
                .put("steps", steps.size())
                .put("completed", failed == null ? steps.size() : failed.index)
                .put("millis", System.currentTimeMillis() - started);
        if (failed != null) {
            done.put("failed_at", failed.index);
            done.put("line", failed.line);
            done.put("verb", failed.verb);
            done.put("error", why);
            try {
                engine.update(restore);
                done.put("restored", engine.status().optJSONObject("settings"));
            } catch (Exception e) {
                Log.w(TAG, "could not put the camera back after a failed script", e);
                done.put("restored", JSONObject.NULL);
                done.put("restore_failed", e.toString());
            }
        }
        return done;
    }

    /** What a file of a step is called in the stream: its step, then its own name. */
    private static String partName(Tape.Step step, String name) {
        return String.format(Locale.US, "%03d-%s", step.index, name);
    }

    private static String contentTypeFor(String name) {
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".dng")) return "image/x-adobe-dng";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static long writeEvent(BufferedOutputStream out, JSONObject event) throws Exception {
        return writePart(out, "application/json; charset=utf-8", null,
                event.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** One part of the stream, flushed, so progress is live rather than at the end. */
    private static long writePart(BufferedOutputStream out, String contentType, String name,
                                  byte[] body) throws IOException {
        StringBuilder part = new StringBuilder("--").append(SCRIPT_BOUNDARY).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n");
        if (name != null) {
            part.append("Content-Disposition: attachment; filename=\"").append(name)
                    .append("\"\r\n");
        }
        part.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        out.write(part.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return body.length;
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
            case 409: return "Conflict";
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
    /**
     * Runs a walk and answers with the frames and one record of the set.
     *
     * The manifest travels inside the archive. A walk is the one capture where every frame
     * differs in the thing the capture exists to vary, so one record for the set would
     * lose exactly the information the set exists to carry: each frame's own provenance
     * goes in, and the CLI splits them into a sidecar apiece.
     */
    private Answer walkAnswer(Map<String, String> params, CamSettings req,
                              java.util.List<CamSettings> steps, JSONObject manifest,
                              String prefix, java.util.List<String> labels,
                              java.util.List<JSONObject> asked) throws Exception {
        // Longer than a burst by default. Each step moves the camera and waits for it, and
        // a frame taken before it arrives is a frame at the wrong setting wearing the
        // right label.
        long settleMs = longParam(params, "settle", 300, 0, 5000);
        // A capture cannot finish sooner than its own exposure, so the default waiting
        // time has to know what the longest step is about to ask for. Three times it,
        // because each step goes through the repeating preview request: at an 8.5 second
        // exposure the preview frames are 8.5 seconds each too, and the still queues
        // behind one of them. A bracket of twelve stops from 1/240 used to fail on a
        // timeout, which reads as a broken camera rather than as a slow one.
        long slowest = 0;
        for (CamSettings step : steps) {
            if (!step.aeAuto) slowest = Math.max(slowest, step.exposureNs);
        }
        long perFrame = longParam(params, "timeout",
                Math.max(8000, slowest / 1_000_000L * 3 + 4000), 100, 120000);

        long t0 = System.currentTimeMillis();
        java.util.List<CameraEngine.SweepFrame> frames = engine.walk(steps, settleMs, perFrame);
        long ms = System.currentTimeMillis() - t0;

        manifest.put("tool", "DeskCam");
        manifest.put("millis", ms);

        // A walk varies one thing on purpose. Anything the camera is still deciding for
        // itself varies alongside it, and the set stops being a controlled comparison.
        // Measured, the same torch walk at 0, 20 and 45 run twice. Under automatic
        // exposure the frames came out at 139, 173 and 141 DN, which is not even
        // monotone: the exposure loop gave back the light the torch added, and nothing in
        // the frames says so. With the exposure fixed the same walk read 32, 107 and 140,
        // which is the curve that was there all along.
        JSONArray warnings = manifest.optJSONArray("warnings");
        if (warnings == null) {
            warnings = new JSONArray();
            manifest.put("warnings", warnings);
        }
        if (req.aeAuto) {
            warnings.put("automatic exposure was on, so the camera changed the exposure "
                    + "between these frames as well as the thing being walked. Fix "
                    + "exposure= and iso= for a set that can be compared.");
        }
        if (req.awbGains == null) {
            warnings.put("the white balance was left to the camera, so the colour moved "
                    + "between these frames too. awbgains= holds it. Card 42.");
        }
        JSONArray entries = new JSONArray();
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<byte[]> files = new java.util.ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            CameraEngine.SweepFrame f = frames.get(i);
            // The setting is in the name as well as the manifest, because a frame that is
            // untarred on its own has to still say which one it is.
            String name = String.format(Locale.US, "%s-%02d-%s.jpg", prefix, i, labels.get(i));
            names.add(name);
            files.add(f.jpeg);
            JSONObject entry = f.provenance == null ? new JSONObject()
                    : new JSONObject(f.provenance.toString());
            entry.put("file", name);
            entry.put("step", i);
            // What this step was asked for, beside what the camera reported it did. The
            // two are not the same and the difference is the point of a walk: a lens
            // lands near a diopter, a sensor quantises an exposure. Card 5 wanted this
            // per frame and it was lost when the two endpoints were generalised into one.
            if (asked != null && i < asked.size()) {
                JSONObject want = asked.get(i);
                for (java.util.Iterator<String> k = want.keys(); k.hasNext();) {
                    String key = k.next();
                    entry.put(key, want.get(key));
                }
            }
            entries.put(entry);
        }
        manifest.put("frames", entries);
        names.add("walk.json");
        files.add(manifest.toString(2).getBytes(StandardCharsets.UTF_8));

        return Answer.archive(200, names, files, manifest,
                "X-DeskCam-Frames: " + frames.size() + "\r\n"
                + "X-DeskCam-Millis: " + ms + "\r\n");
    }

    private static float floatParam(Map<String, String> p, String k, float dflt, float lo, float hi)
            throws BadRequest {
        String v = p.get(k);
        if (v == null || v.isEmpty()) return dflt;
        float parsed;
        try {
            parsed = Parse.number(v);
        } catch (NumberFormatException e) {
            throw new BadRequest("bad value for '" + k + "': '" + v + "' is not a number");
        }
        if (parsed < lo || parsed > hi) {
            throw new BadRequest(String.format(Locale.US,
                    "'%s' must be between %.3g and %.3g, got %.3g", k, lo, hi, parsed));
        }
        return parsed;
    }

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
