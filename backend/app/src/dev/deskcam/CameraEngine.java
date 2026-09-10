package dev.deskcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the Camera2 device, the capture session, and all image production. */
public class CameraEngine {

    public static final String TAG = "DeskCam";

    /** How many still buffers the HAL may hold. This sets the practical burst rate. */
    private static final int BURST_READER_DEPTH = 6;

    /** An identity tone curve. Output equals input, so the JPEG stays proportional to light. */
    private static final TonemapCurve LINEAR_TONEMAP = new TonemapCurve(
            new float[]{0f, 0f, 1f, 1f},
            new float[]{0f, 0f, 1f, 1f},
            new float[]{0f, 0f, 1f, 1f});

    private final Context ctx;
    private final CameraManager cm;

    private HandlerThread camThread;
    private Handler camHandler;
    private Executor camExecutor;
    private Thread watchdogThread;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader previewReader, stillReader, rawReader;
    private CaptureRequest.Builder previewBuilder;

    private CameraCharacteristics chars;
    private CamSettings.Caps caps = new CamSettings.Caps();
    private Rect activeArray = new Rect(0, 0, 4032, 3024);
    private Size stillSize = new Size(4032, 3024);
    private Size rawSize = null;
    /**
     * Set only when a session refuses to configure WITH a RAW output.
     *
     * It used to be set by any exception out of openAndConfigure, so a camera that was
     * merely busy for five seconds disabled DNG for the life of the process and reported
     * a reason that was not true.
     */
    private boolean rawSessionFailed = false;
    /**
     * Some devices advertise the shading map mode and the map size but never put the map
     * itself in a capture result. The Pixel 6a is one of them. Check the result keys rather
     * than trust the mode list.
     */
    private boolean shadingMapSupported = false;

    private final Object lock = new Object();
    private CamSettings settings = new CamSettings();

    /** Most recent preview frame, already converted to NV21. Guarded by frameLock. */
    private final Object frameLock = new Object();
    private byte[] latestNv21;
    private int latestW, latestH;
    private long latestSeq = 0;
    private long latestAtMs = 0;

    /**
     * Every preview image the camera has handed over, converted or not.
     *
     * latestSeq only moves while something is asking for frames, so it cannot tell a
     * quiet camera from a dead one. This counter can, and it is what the watchdog reads.
     */
    private final AtomicLong framesSeen = new AtomicLong();

    /** Frames are only converted while something is actually asking for them. */
    private volatile long lastDemandMs = 0;
    private final AtomicInteger streamClients = new AtomicInteger();

    /**
     * The captures in flight. Each one owns the frames of its own requests.
     *
     * There used to be one queue for every capture path, so a still taken during a burst
     * called clear() on it and deleted the burst. Frames are now handed to the capture
     * that asked for them, matched by the sensor timestamp the HAL reports when the
     * exposure starts, and a frame nobody claims is dropped.
     */
    private final CopyOnWriteArrayList<Pending> pending = new CopyOnWriteArrayList<>();

    /**
     * A RAW capture needs the Image and the TotalCaptureResult of the same frame, because
     * DngCreator writes the sensor calibration out of the result. Both arrive on different
     * callbacks, so they are collected here and the capture waits for the pair.
     */
    private final Object rawLock = new Object();
    private final Object rawCaptureLock = new Object();
    private Image pendingRaw;
    private TotalCaptureResult pendingRawResult;
    /** The last result of the REPEATING preview request. Never used to describe a still. */
    private volatile TotalCaptureResult lastResult;
    private volatile String state = "stopped";
    private volatile String lastError = null;
    private final AtomicBoolean reopening = new AtomicBoolean(false);
    /** Set by stop(). Every wait and every retry checks it, so nothing resurrects. */
    private volatile boolean stopped = false;
    private volatile Thread reopenThread;
    /**
     * The camera id, readable without the lock.
     *
     * The availability callbacks run on the camera handler. If they took the lock they
     * would deadlock: a reopen thread holds the lock while it waits for onOpened, and
     * onOpened is delivered on that same handler. The callback must never block.
     */
    private volatile String activeCameraId = "0";
    private Sensors sensors;
    /**
     * A small copy of the last still, for the phone's own screen. Decoded with
     * inSampleSize, which reads a reduced image straight out of the JPEG rather than
     * decoding 12 megapixels and throwing most of it away.
     */
    private volatile Bitmap lastStillThumb;
    private volatile long lastStillAt = 0;

    public CameraEngine(Context ctx) {
        this.ctx = ctx;
        this.cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        // A first estimate, so the limits are honest even before a camera opens. The real
        // still size replaces the guess as soon as a session is configured.
        caps.sizeToHeap(Runtime.getRuntime().maxMemory(), 12_000_000L);
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Brings the camera up.
     *
     * A camera another app is holding is not a reason to refuse to start. The service
     * used to fail here, which took the HTTP server down with it, so a client saw a
     * refused connection and no reason at all, while a camera lost the same way one
     * second later recovered on its own within fifteen. The state goes into /api/status
     * instead and the watchdog keeps trying.
     */
    public void start() {
        stopped = false;
        camThread = new HandlerThread("deskcam-camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        camExecutor = camHandler::post;
        cm.registerAvailabilityCallback(availabilityCb, camHandler);
        try {
            synchronized (lock) { openAndConfigure(settings.clone()); }
        } catch (Exception e) {
            Log.w(TAG, "camera did not open at start, will keep trying: " + e);
            if (!"disconnected".equals(state)) state = "waiting";
            lastError = "camera not open yet: " + e;
        }
        startWatchdog();
    }

    /**
     * Only one app may hold a camera. Another app that opens it evicts us, and without
     * this callback DeskCam stays dead until somebody restarts it. A bench camera has to
     * come back by itself after you put the phone down.
     */
    private final CameraManager.AvailabilityCallback availabilityCb =
            new CameraManager.AvailabilityCallback() {
        @Override public void onCameraAvailable(String id) {
            if (!id.equals(activeCameraId)) return;
            if (device != null || stopped) return;
            reopenLater("camera " + id + " is free again");
        }
        @Override public void onCameraUnavailable(String id) {
            if (id.equals(activeCameraId) && device == null && !stopped) {
                state = "disconnected";
                lastError = "another app is using camera " + id;
            }
        }
    };

    /**
     * Reopens on its own thread, never on the camera handler. openAndConfigure waits for
     * the open callback, and that callback is delivered on the camera handler, so doing
     * this work there would make the thread wait for itself.
     */
    private void reopenLater(String why) {
        if (stopped) return;
        if (!reopening.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                // A few close attempts only. The other app may still be releasing the
                // device. If these fail, the next availability callback or the watchdog
                // will try again, so nothing is lost by giving up quickly here.
                long[] waits = {300, 1200, 3000};
                for (long w : waits) {
                    if (stopped || camThread == null) return;
                    try {
                        Thread.sleep(w);
                    } catch (InterruptedException ie) {
                        return;                                  // stop() wants us gone
                    }
                    if (stopped) return;
                    synchronized (lock) {
                        if (stopped) return;
                        if (device != null) return;              // already back
                        try {
                            Log.i(TAG, why + ", reopening");
                            openAndConfigure(settings.clone());
                            lastError = null;
                            Log.i(TAG, "camera recovered");
                            return;
                        } catch (Exception e) {
                            Log.w(TAG, "reopen failed: " + e);
                            lastError = "waiting for the camera: " + e;
                        }
                    }
                }
            } finally {
                reopening.set(false);
                reopenThread = null;
            }
        }, "deskcam-reopen");
        reopenThread = t;
        t.start();
    }

    /**
     * A slow safety net, on a thread of its own.
     *
     * It used to be posted to the camera handler, which is the same handler every camera
     * callback uses. A camera thread that wedged therefore stopped the watchdog too, and
     * that is precisely the fault the watchdog exists to find. Its test used to be
     * "device is null", which a camera that is open but delivering nothing passes
     * happily; that is what heat throttling looks like. The test is now that frames are
     * still arriving.
     */
    private void startWatchdog() {
        watchdogThread = new Thread(() -> {
            long lastSeen = framesSeen.get();
            while (!stopped) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    return;
                }
                if (stopped) return;
                long seen = framesSeen.get();
                boolean noDevice = device == null;
                boolean stalled = !noDevice && seen == lastSeen && "running".equals(state);
                lastSeen = seen;
                if ((noDevice || stalled) && !reopening.get()) {
                    if (stalled) {
                        lastError = "the camera is open but has produced no frame for 15 seconds";
                        Log.w(TAG, "watchdog: " + lastError);
                    }
                    reopenLater(noDevice ? "watchdog saw no camera" : "watchdog saw no frames");
                }
            }
        }, "deskcam-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    /**
     * Stops, quickly, and stays stopped.
     *
     * The flag goes up before anything else, so an open that is already waiting on a
     * callback gives up at its next check instead of holding the caller for the full
     * five second timeout twice over, and a reopen already in flight cannot bring the
     * camera back after this returns.
     */
    public void stop() {
        stopped = true;
        state = "stopped";
        Thread r = reopenThread;
        if (r != null) r.interrupt();
        Thread w = watchdogThread;
        if (w != null) w.interrupt();
        watchdogThread = null;
        try { cm.unregisterAvailabilityCallback(availabilityCb); } catch (Exception e) {
            Log.d(TAG, "unregister availability callback: " + e);
        }
        synchronized (lock) {
            closeSessionAndDevice();
            state = "stopped";
        }
        for (Pending p : pending) p.abandon();
        pending.clear();
        if (camThread != null) {
            camThread.quitSafely();
            try { camThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            camThread = null;
            camHandler = null;
        }
    }

    private void closeSessionAndDevice() {
        try { if (session != null) session.close(); } catch (Exception e) { Log.d(TAG, "session close: " + e); }
        session = null;
        try { if (device != null) device.close(); } catch (Exception e) { Log.d(TAG, "device close: " + e); }
        device = null;
        if (previewReader != null) { previewReader.close(); previewReader = null; }
        if (stillReader != null) { stillReader.close(); stillReader = null; }
        if (rawReader != null) { rawReader.close(); rawReader = null; }
        synchronized (rawLock) {
            if (pendingRaw != null) { pendingRaw.close(); pendingRaw = null; }
            pendingRawResult = null;
        }
        synchronized (frameLock) { latestNv21 = null; }
    }

    /**
     * Opens the requested camera and builds a session with a YUV preview reader and a
     * full-resolution JPEG still reader. Called again whenever the camera id or either
     * output size changes, since those are fixed at session-configuration time.
     */
    private void openAndConfigure(CamSettings s) throws Exception {
        boolean wantRaw = !rawSessionFailed && deviceHasRaw(s.cameraId);
        try {
            configureSession(s, wantRaw);
        } catch (SessionConfigurationException e) {
            if (!wantRaw) throw e;
            // A RAW output is a mandatory stream combination on paper. If this device
            // disagrees, fall back rather than leave the camera unusable. Only a refusal
            // to CONFIGURE says anything about RAW; a busy camera says nothing.
            Log.w(TAG, "session with RAW refused, retrying without it: " + e);
            rawSessionFailed = true;
            configureSession(s, false);
        }
    }

    /** Thrown only when the session itself refuses the outputs it was given. */
    private static class SessionConfigurationException extends Exception {
        private static final long serialVersionUID = 1L;

        SessionConfigurationException(String m) { super(m); }
    }

    private boolean deviceHasRaw(String cameraId) {
        try {
            CameraCharacteristics c = cm.getCameraCharacteristics(cameraId);
            int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities == null) return false;
            for (int x : capabilities) {
                if (x == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "raw capability probe", e);
        }
        return false;
    }

    /** True when the device really offers this camera id. */
    public boolean hasCamera(String id) {
        try {
            for (String known : cm.getCameraIdList()) {
                if (known.equals(id)) return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "camera id list", e);
        }
        return false;
    }

    private void configureSession(CamSettings s, boolean withRaw) throws Exception {
        closeSessionAndDevice();
        if (stopped) throw new IllegalStateException("service is stopping");
        state = "opening";
        lastError = null;

        activeCameraId = s.cameraId;
        chars = cm.getCameraCharacteristics(s.cameraId);
        Rect aa = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (aa != null) activeArray = aa;
        caps = CamSettings.Caps.from(chars, activeArray.width());
        shadingMapSupported = false;
        try {
            for (CaptureResult.Key<?> k : chars.getAvailableCaptureResultKeys()) {
                if (k.equals(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)) {
                    shadingMapSupported = true;
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "shading map key probe", e);
        }

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("no stream configuration map");

        stillSize = (s.stillW > 0 && s.stillH > 0)
                ? nearestSize(map.getOutputSizes(ImageFormat.JPEG), s.stillW, s.stillH)
                : largestSize(map.getOutputSizes(ImageFormat.JPEG));
        Size pv = nearestSize(map.getOutputSizes(ImageFormat.YUV_420_888), s.previewReqW, s.previewReqH);

        caps.sizeToHeap(Runtime.getRuntime().maxMemory(),
                (long) stillSize.getWidth() * stillSize.getHeight());

        // Deep enough that a burst keeps running while the server drains it.
        stillReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(),
                ImageFormat.JPEG, BURST_READER_DEPTH);
        stillReader.setOnImageAvailableListener(r -> {
            // acquireNextImage, not acquireLatestImage: a burst must keep every frame.
            try (Image img = r.acquireNextImage()) {
                if (img == null) return;
                long ts = img.getTimestamp();
                Pending owner = ownerOf(ts);
                // A frame nobody asked for is dropped here rather than copied into a
                // shared queue where the next capture would find it.
                if (owner == null) return;
                ByteBuffer b = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[b.remaining()];
                b.get(data);
                owner.image(ts, data);
            } catch (Exception e) {
                Log.w(TAG, "still reader", e);
            }
        }, camHandler);

        rawSize = null;
        if (withRaw) {
            Size rs = largestSize(map.getOutputSizes(ImageFormat.RAW_SENSOR));
            if (rs != null) {
                rawSize = rs;
                // One image only. A full frame is width * height * 2 bytes, about 24 MB here.
                rawReader = ImageReader.newInstance(rs.getWidth(), rs.getHeight(), ImageFormat.RAW_SENSOR, 1);
                rawReader.setOnImageAvailableListener(r -> {
                    Image img = r.acquireNextImage();
                    if (img == null) return;
                    synchronized (rawLock) {
                        if (pendingRaw != null) pendingRaw.close();
                        pendingRaw = img;
                        rawLock.notifyAll();
                    }
                }, camHandler);
            }
        }

        previewReader = ImageReader.newInstance(pv.getWidth(), pv.getHeight(), ImageFormat.YUV_420_888, 3);
        previewReader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            framesSeen.incrementAndGet();          // the watchdog reads this
            try {
                // Drain but do not pay for conversion when nobody is watching.
                boolean wanted = streamClients.get() > 0
                        || (System.currentTimeMillis() - lastDemandMs) < 2000;
                if (wanted) {
                    byte[] nv21 = yuv420ToNv21(img);
                    synchronized (frameLock) {
                        latestNv21 = nv21;
                        latestW = img.getWidth();
                        latestH = img.getHeight();
                        latestAtMs = System.currentTimeMillis();
                        latestSeq++;
                        frameLock.notifyAll();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "preview convert", e);
            } finally {
                img.close();
            }
        }, camHandler);

        final Object openLatch = new Object();
        final Exception[] openErr = new Exception[1];
        final boolean[] done = new boolean[1];

        cm.openCamera(s.cameraId, camExecutor, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice cd) {
                device = cd;
                synchronized (openLatch) { done[0] = true; openLatch.notifyAll(); }
            }
            @Override public void onDisconnected(CameraDevice cd) {
                cd.close();
                if (device == cd) device = null;
                state = "disconnected";
                lastError = "another app took the camera";
                synchronized (openLatch) { done[0] = true; openLatch.notifyAll(); }
                // No retry here. Retrying while the other app still holds the camera only
                // burns attempts. onCameraAvailable tells us when it is really free.
            }
            @Override public void onError(CameraDevice cd, int err) {
                cd.close();
                openErr[0] = new IllegalStateException("camera open error " + err);
                // ERROR_CAMERA_IN_USE means somebody else has it. That is the same
                // situation as an eviction, so report it the same way and wait.
                state = (err == ERROR_CAMERA_IN_USE || err == ERROR_MAX_CAMERAS_IN_USE)
                        ? "disconnected" : "error";
                lastError = (err == ERROR_CAMERA_IN_USE || err == ERROR_MAX_CAMERAS_IN_USE)
                        ? "another app is using the camera" : "camera open error " + err;
                synchronized (openLatch) { done[0] = true; openLatch.notifyAll(); }
            }
        });

        awaitLatch(openLatch, done, 5000);
        if (openErr[0] != null) throw openErr[0];
        if (device == null) throw new IllegalStateException("camera did not open in time");

        List<OutputConfiguration> outs = new ArrayList<>();
        outs.add(new OutputConfiguration(previewReader.getSurface()));
        outs.add(new OutputConfiguration(stillReader.getSurface()));
        if (rawReader != null) outs.add(new OutputConfiguration(rawReader.getSurface()));

        final Object sesLatch = new Object();
        final boolean[] sesDone = new boolean[1];
        final boolean[] sesRefused = new boolean[1];

        device.createCaptureSession(new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, camExecutor,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession cs) {
                        session = cs;
                        synchronized (sesLatch) { sesDone[0] = true; sesLatch.notifyAll(); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession cs) {
                        sesRefused[0] = true;
                        synchronized (sesLatch) { sesDone[0] = true; sesLatch.notifyAll(); }
                    }
                }));

        awaitLatch(sesLatch, sesDone, 5000);
        if (sesRefused[0]) throw new SessionConfigurationException("capture session configuration failed");
        if (session == null) throw new IllegalStateException("session did not configure in time");

        previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        previewBuilder.addTarget(previewReader.getSurface());
        applyTo(previewBuilder, s, false);
        session.setRepeatingRequest(previewBuilder.build(), resultCb, camHandler);

        settings = s.withoutPresentation();
        settings.previewW = pv.getWidth();
        settings.previewH = pv.getHeight();
        state = "running";
        Log.i(TAG, "camera " + s.cameraId + " running, preview " + pv + " still " + stillSize
                + (rawSize != null ? " raw " + rawSize : " raw unavailable"));
    }

    /** Waits for a camera callback, giving up early when the service is stopping. */
    private void awaitLatch(Object latch, boolean[] done, long timeoutMs) throws Exception {
        synchronized (latch) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                if (stopped) throw new IllegalStateException("service is stopping");
                latch.wait(200);
            }
        }
        if (stopped) throw new IllegalStateException("service is stopping");
    }

    private final CameraCaptureSession.CaptureCallback resultCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            lastResult = res;
        }
    };

    // ------------------------------------------------------- capture identity

    /** One frame of one capture: the bytes and the result of the same exposure. */
    private static final class Frame {
        byte[] jpeg;
        TotalCaptureResult result;

        boolean paired() { return jpeg != null && result != null; }
    }

    /**
     * One capture in flight.
     *
     * The HAL reports the sensor timestamp of a frame when its exposure starts, before
     * the image for that frame reaches the reader, and the same timestamp is in the
     * capture result. That is the frame's identity, so a capture can claim its own
     * frames and its own results without ever trusting the order things arrive in.
     */
    private static final class Pending {
        final int wanted;
        final LinkedHashMap<Long, Frame> byTimestamp = new LinkedHashMap<>();
        int failed = 0;
        boolean abandoned = false;

        Pending(int wanted) { this.wanted = wanted; }

        synchronized void started(long ts) {
            byTimestamp.put(ts, new Frame());
            notifyAll();
        }

        synchronized boolean claims(long ts) { return byTimestamp.containsKey(ts); }

        synchronized void image(long ts, byte[] data) {
            Frame f = byTimestamp.get(ts);
            if (f != null) { f.jpeg = data; notifyAll(); }
        }

        synchronized void result(long ts, TotalCaptureResult r) {
            Frame f = byTimestamp.get(ts);
            if (f == null) { f = new Frame(); byTimestamp.put(ts, f); }
            f.result = r;
            notifyAll();
        }

        synchronized void failure() { failed++; notifyAll(); }

        synchronized int failures() { return failed; }

        /** Drops whatever has been collected, so a capture that dies frees its frames. */
        synchronized void abandon() {
            abandoned = true;
            byTimestamp.clear();
            notifyAll();
        }

        synchronized int paired() {
            int n = 0;
            for (Frame f : byTimestamp.values()) if (f.paired()) n++;
            return n;
        }

        /** Waits for as many complete frames as were asked for, or until the deadline. */
        synchronized List<Frame> await(long deadline) throws InterruptedException {
            while (!abandoned && paired() + failed < wanted) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) break;
                wait(Math.min(wait, 200));
            }
            List<Frame> out = new ArrayList<>();
            for (Frame f : byTimestamp.values()) if (f.paired()) out.add(f);
            return out;
        }
    }

    private Pending ownerOf(long timestamp) {
        for (Pending p : pending) {
            if (p.claims(timestamp)) return p;
        }
        return null;
    }

    /** The callback that gives one capture its own frame identities and results. */
    private static CameraCaptureSession.CaptureCallback callbackFor(Pending p) {
        return new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r,
                                                   long timestamp, long frameNumber) {
                p.started(timestamp);
            }
            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r,
                                                     TotalCaptureResult res) {
                Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
                if (ts != null) p.result(ts, res);
            }
            @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                                                  CaptureFailure f) {
                Log.w(TAG, "capture failed, reason " + f.getReason());
                p.failure();
            }
        };
    }

    // ------------------------------------------------------------- settings

    /**
     * Applies new settings, rebuilding the session only when something structural changed.
     *
     * Only camera state is kept. The presentation parameters of the request that brought
     * these settings in are dropped, because they describe one picture and not the camera.
     */
    public CamSettings update(CamSettings next) throws Exception {
        if (!next.cameraId.equals(activeCameraId) && !hasCamera(next.cameraId)) {
            // Check before anything is closed. A bad id used to shut the working session
            // and then fail to open the new one, leaving the camera dark until the
            // watchdog came round fifteen seconds later.
            throw new IllegalArgumentException("no camera with id '" + next.cameraId
                    + "'; see /api/cameras");
        }
        synchronized (lock) {
            boolean structural = !next.cameraId.equals(settings.cameraId)
                    || next.previewReqW != settings.previewReqW
                    || next.previewReqH != settings.previewReqH
                    || next.stillW != settings.stillW || next.stillH != settings.stillH;
            if (structural || session == null) {
                CamSettings previous = settings.clone();
                try {
                    openAndConfigure(next);
                } catch (Exception e) {
                    // Put back what was working, so a rejected change does not also cost
                    // the caller the session they already had.
                    if (structural) {
                        try {
                            openAndConfigure(previous);
                        } catch (Exception restore) {
                            Log.w(TAG, "could not restore the previous session", restore);
                        }
                    }
                    throw e;
                }
            } else {
                settings = next.withoutPresentation();
                applyTo(previewBuilder, settings, false);
                session.setRepeatingRequest(previewBuilder.build(), resultCb, camHandler);
            }
            return settings.clone();
        }
    }

    /** No cross-channel mixing, for when the gains are chosen rather than found. */
    private static final android.hardware.camera2.params.ColorSpaceTransform IDENTITY_TRANSFORM =
            new android.hardware.camera2.params.ColorSpaceTransform(
                    new int[]{1, 1, 0, 1, 0, 1,
                              0, 1, 1, 1, 0, 1,
                              0, 1, 0, 1, 1, 1});

    private void applyTo(CaptureRequest.Builder b, CamSettings s, boolean forStill) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        // Focus
        if (s.focusDiopters != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, s.afMode);
        }

        // Exposure
        if (s.aeAuto) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.evSteps);
            b.set(CaptureRequest.CONTROL_AE_LOCK, s.aeLock);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            long exp = s.exposureNs != null ? s.exposureNs : 10_000_000L;
            int iso = s.iso != null ? s.iso : Math.max(caps.minIso, 100);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            // Frame duration must be at least the exposure or the request is silently clipped.
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(exp, 33_333_333L));
        }

        if (s.awbGains != null) {
            // Manual gains need the automatic white balance out of the way and the colour
            // correction told to use the matrix, or the values are accepted and ignored.
            // The transform goes to identity with them: a chosen gain under an unchosen
            // matrix is still a colour nobody wrote down.
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, false);
            trySet(b, CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            trySet(b, CaptureRequest.COLOR_CORRECTION_GAINS,
                    new android.hardware.camera2.params.RggbChannelVector(
                            s.awbGains[0], s.awbGains[1], s.awbGains[2], s.awbGains[3]));
            trySet(b, CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM);
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, s.awbLock);
        }

        // Torch. Level control needs API 35+; below that it is simply on or off.
        if (s.torch > 0) {
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            if (Build.VERSION.SDK_INT >= 35 && caps.flashMaxLevel > 1) {
                try {
                    b.set(CaptureRequest.FLASH_STRENGTH_LEVEL,
                            CamSettings.clampInt(s.torch, 1, caps.flashMaxLevel));
                } catch (IllegalArgumentException e) {
                    Log.d(TAG, "device rejected the torch level: " + e);
                }
            }
        } else {
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        }

        // Point focus and metering at whatever region we are cropped to, so zooming in
        // on a component actually focuses and exposes for that component.
        MeteringRectangle[] regions = meteringForRoi(s);
        if (regions != null) {
            Integer afRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer aeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            if (afRegions != null && afRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
            if (aeRegions != null && aeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
        }

        b.set(CaptureRequest.JPEG_QUALITY, (byte) s.jpegQuality);
        b.set(CaptureRequest.JPEG_ORIENTATION, 0);
        // Both branches below set the same keys. The request builder is reused between
        // updates, so a key that is set in only one branch stays at its old value when the
        // mode changes. Every mode must therefore state every key it cares about.
        if (!s.measure) {
            trySet(b, CaptureRequest.NOISE_REDUCTION_MODE, forStill
                    ? CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    : CaptureRequest.NOISE_REDUCTION_MODE_FAST);
            trySet(b, CaptureRequest.EDGE_MODE, forStill
                    ? CaptureRequest.EDGE_MODE_HIGH_QUALITY
                    : CaptureRequest.EDGE_MODE_FAST);
            trySet(b, CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST);
            trySet(b, CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST);
            trySet(b, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
            trySet(b, CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST);
            trySet(b, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            trySet(b, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, s.shadingMap
                    ? CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    : CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF);
        }

        // Each stage below is non-linear or varies across the frame. That is correct for a
        // photograph and wrong for a measurement.
        if (s.measure) {
            trySet(b, CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            trySet(b, CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
            trySet(b, CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
            // Lens shading correction off, so the frame shows the true optical response.
            // Correct it on the workstation with a measured flat field. This affects the
            // JPEG only; RAW is read before the ISP and never carries the correction.
            trySet(b, CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF);
            trySet(b, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
            trySet(b, CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
            trySet(b, CaptureRequest.TONEMAP_CURVE, LINEAR_TONEMAP);
            // OIS drifts on a fixed mount and adds blur instead of removing it.
            trySet(b, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            trySet(b, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            if (s.shadingMap) {
                // The map only holds real gains while correction runs. Let the caller ask
                // for the map even in measurement mode by turning correction back on.
                trySet(b, CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST);
            }
        }
    }

    /** Sets a key and keeps going if this device rejects it. */
    private static <T> void trySet(CaptureRequest.Builder b, CaptureRequest.Key<T> key, T value) {
        try {
            b.set(key, value);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "device rejected " + key.getName());
        }
    }

    /**
     * The lens shading map of the last frame, as a grid of gain factors.
     *
     * A gain above 1.0 marks a position where the lens delivers less light, so the corners
     * read high. Use it to check a measured flat field. It is only populated while
     * measurement mode is on.
     */
    public JSONObject shadingMap() throws JSONException {
        JSONObject o = new JSONObject();
        TotalCaptureResult r = lastResult;
        android.hardware.camera2.params.LensShadingMap m =
                r == null ? null : r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (m == null) {
            o.put("ok", false);
            o.put("supported", shadingMapSupported);
            o.put("error", shadingMapSupported
                    ? "no shading map yet; ask again with shadingmap=1 so a frame is taken with the map on"
                    : "this camera does not report a lens shading map. It lists the map mode and the "
                      + "map size, but android.statistics.lensShadingMap is not one of its result keys. "
                      + "Measure a flat field instead.");
            return o;
        }
        int rows = m.getRowCount(), cols = m.getColumnCount();
        o.put("ok", true);
        o.put("rows", rows);
        o.put("columns", cols);
        o.put("channels", 4);
        o.put("channel_order", "RGGB");
        float[] gains = new float[rows * cols * 4];
        m.copyGainFactors(gains, 0);
        JSONArray a = new JSONArray();
        for (float g : gains) a.put(CamSettings.round3(g));
        o.put("gains", a);
        return o;
    }

    private MeteringRectangle[] meteringForRoi(CamSettings s) {
        if (s.roiIsWholeFrame()) return null;
        Rect r = s.roiFor(activeArray.width(), activeArray.height());
        r.offset(activeArray.left, activeArray.top);
        return new MeteringRectangle[]{ new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX) };
    }

    /** One-shot autofocus sweep, useful after moving the bench or changing the subject. */
    public void triggerAf() throws CameraAccessException {
        synchronized (lock) {
            if (session == null || previewBuilder == null) return;
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(previewBuilder.build(), null, camHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(previewBuilder.build(), null, camHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(previewBuilder.build(), resultCb, camHandler);
        }
    }

    // ------------------------------------------------------------- capture

    /** A picture and the record of the frame it came from. */
    public static final class Shot {
        public final byte[] bytes;
        public final JSONObject provenance;

        Shot(byte[] bytes, JSONObject provenance) {
            this.bytes = bytes;
            this.provenance = provenance;
        }
    }

    /** A burst, with the count that was asked for so a short burst can say so. */
    public static final class Burst {
        public final List<byte[]> frames;
        public final int requested;
        public final JSONObject provenance;

        Burst(List<byte[]> frames, int requested, JSONObject provenance) {
            this.frames = frames;
            this.requested = requested;
            this.provenance = provenance;
        }

        public boolean complete() { return frames.size() >= requested; }
    }

    /** One frame of a walk, and what the camera reported for it. */
    public static final class SweepFrame {
        public final byte[] jpeg;
        public final JSONObject provenance;

        SweepFrame(byte[] jpeg, JSONObject provenance) {
            this.jpeg = jpeg;
            this.provenance = provenance;
        }
    }

    /**
     * Full-resolution still, cropped to the ROI.
     *
     * The request carries its own settings, including the presentation parameters of this
     * one request, and the returned provenance describes THIS frame. It used to describe
     * whatever preview frame happened to be the most recent, which is a different picture:
     * a still runs noise reduction and edge enhancement at high quality, the preview runs
     * them fast.
     */
    public Shot captureStill(CamSettings req, long timeoutMs) throws Exception {
        Pending p = new Pending(1);
        pending.add(p);
        try {
            synchronized (lock) {
                if (session == null || device == null) throw new IllegalStateException(notRunning());
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(stillReader.getSurface());
                applyTo(b, req, true);
                session.capture(b.build(), callbackFor(p), camHandler);
            }
            List<Frame> frames = p.await(System.currentTimeMillis() + timeoutMs);
            if (frames.isEmpty()) {
                throw new IllegalStateException(p.failures() > 0
                        ? "the camera refused the still capture"
                        : "still capture timed out after " + timeoutMs + "ms");
            }
            Frame f = frames.get(0);
            JSONObject prov = provenance(req, f.result);
            byte[] raw = f.jpeg;
            f.jpeg = null;
            byte[] finished = withExif(req.stillIsPristine() ? raw : cropJpeg(raw, req), prov);
            makeThumb(finished);
            return new Shot(finished, prov);
        } finally {
            pending.remove(p);
            p.abandon();                 // whatever it still holds is released here
        }
    }

    /**
     * Crops the ROI straight out of the encoded JPEG. BitmapRegionDecoder only decodes
     * the requested tile, so a deep zoom is cheaper than a full 12MP decode.
     */
    private byte[] cropJpeg(byte[] jpeg, CamSettings s) throws Exception {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, probe);
        int w = probe.outWidth, h = probe.outHeight;
        if (w <= 0 || h <= 0) throw new IllegalStateException("undecodable still");

        Rect roi = s.roiFor(w, h);
        BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length);
        Bitmap bmp;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = dec.decodeRegion(roi, o);
        } finally {
            dec.recycle();
        }
        if (bmp == null) throw new IllegalStateException("region decode failed");
        bmp = transform(bmp, s, caps);

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        bmp.compress(Bitmap.CompressFormat.JPEG, s.jpegQuality, out);
        bmp.recycle();
        return out.toByteArray();
    }

    /**
     * A full-sensor RAW frame, written as a DNG.
     *
     * The software ROI is deliberately NOT applied. A DNG holds the whole sensor array so
     * the workstation can demosaic and crop from linear data; the ROI is reported in the
     * response header instead. Every value needed for correct colour, including the black
     * and white levels and the calibration matrices, is written from the capture result.
     */
    public Shot captureRaw(CamSettings req, long timeoutMs) throws Exception {
        synchronized (rawCaptureLock) {
            CameraCharacteristics ch;
            synchronized (lock) {
                if (session == null || device == null) throw new IllegalStateException(notRunning());
                if (rawReader == null) {
                    throw new IllegalStateException(rawSessionFailed
                            ? "RAW disabled: the capture session refused a RAW output"
                            : "RAW is not available on camera " + settings.cameraId);
                }
                ch = chars;
                synchronized (rawLock) {
                    if (pendingRaw != null) { pendingRaw.close(); pendingRaw = null; }
                    pendingRawResult = null;
                }
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(rawReader.getSurface());
                applyTo(b, req, true);
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest rq,
                                                             TotalCaptureResult res) {
                        synchronized (rawLock) { pendingRawResult = res; rawLock.notifyAll(); }
                    }
                    @Override public void onCaptureFailed(CameraCaptureSession cs, CaptureRequest rq,
                                                          CaptureFailure f) {
                        Log.w(TAG, "raw capture failed, reason " + f.getReason());
                        synchronized (rawLock) { rawLock.notifyAll(); }
                    }
                }, camHandler);
            }

            Image img;
            TotalCaptureResult res;
            synchronized (rawLock) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (pendingRaw == null || pendingRawResult == null) {
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) {
                        if (pendingRaw != null) { pendingRaw.close(); pendingRaw = null; }
                        pendingRawResult = null;
                        throw new IllegalStateException("raw capture timed out after " + timeoutMs + "ms");
                    }
                    rawLock.wait(Math.min(wait, 200));
                }
                img = pendingRaw;
                res = pendingRawResult;
                pendingRaw = null;
                pendingRawResult = null;
            }

            // The result of this frame, not of the preview. It was already here and was
            // being thrown away.
            JSONObject prov = provenance(req, res);
            try (DngCreator dng = new DngCreator(ch, res)) {
                dng.setOrientation(exifOrientation(req.rotate));
                dng.setDescription(prov.toString());
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 23);
                dng.writeImage(out, img);
                return new Shot(out.toByteArray(), prov);
            } finally {
                img.close();
            }
        }
    }

    /**
     * Walks the camera through a list of settings, keeping one still at each.
     *
     * A burst is many frames of one setting; this is one frame each of many settings. The
     * focus sweep of card 5 and the exposure bracket of card 7 are both this, and the two
     * things they share are the two that are easy to get wrong: waiting for the camera to
     * arrive at each step, and putting the starting state back afterwards even when a step
     * fails. A walk is an excursion, not a change, and a tool that leaves the bench camera
     * somewhere else after an experiment is a tool people stop running.
     *
     * Each step goes through the repeating preview request rather than riding on the still
     * request, because that is the path the camera follows when a person sets a value and
     * waits, and it is the one that was measured to settle.
     */
    public List<SweepFrame> walk(List<CamSettings> steps, long settleMs, long timeoutMs)
            throws Exception {
        if (steps.size() < 2) {
            throw new IllegalArgumentException("a walk needs at least 2 steps; "
                    + "one frame at one setting is /api/still");
        }
        if (steps.size() > caps.maxBurst) {
            // The same reasoning as a burst. Every frame is held until the archive is
            // written, so the limit is the heap, and it is checked before the first
            // capture rather than part way through.
            throw new IllegalArgumentException(steps.size() + " frames will not fit in "
                    + "memory on this device; the most this heap can carry at "
                    + stillSize.getWidth() + "x" + stillSize.getHeight() + " is "
                    + caps.maxBurst);
        }
        CamSettings restore = snapshot();
        List<SweepFrame> out = new ArrayList<>(steps.size());
        try {
            for (CamSettings step : steps) {
                CamSettings applied = update(step);
                if (settleMs > 0) Thread.sleep(settleMs);
                Shot shot = captureStill(applied, timeoutMs);
                out.add(new SweepFrame(shot.bytes, shot.provenance));
            }
        } finally {
            try {
                update(restore);
            } catch (Exception e) {
                Log.w(TAG, "could not put the camera back after a walk", e);
            }
        }
        return out;
    }

    /**
     * The lens positions of a focus sweep, equal in diopters and never in millimetres.
     *
     * Depth of field is very nearly constant per diopter and wildly unequal per
     * millimetre: near the 98 mm closest focus of this lens one millimetre is about a
     * tenth of a diopter, and at half a metre it is four thousandths. A sweep spread
     * evenly in millimetres would crawl at one end and step over the subject at the other.
     *
     * The absolute figures are not trustworthy and do not need to be.
     * focusDistanceCalibration on this device is APPROXIMATE, so a diopter is a lens
     * position and not a distance. What a stack needs is that the positions are ordered
     * and evenly spread, and that is all this claims. Card 5.
     */
    public List<CamSettings> focusSteps(CamSettings base, float from, float to, int steps) {
        List<CamSettings> out = new ArrayList<>(Math.max(0, steps));
        for (int i = 0; i < steps; i++) {
            CamSettings step = base.clone();
            step.focusDiopters = Geom.clamp(Geom.sweepStep(from, to, i, steps),
                    0f, caps.minFocusDiopters);
            step.afMode = CamSettings.AF_OFF;
            out.add(step);
        }
        return out;
    }

    /**
     * The settings of a walk over one named parameter, one per value given.
     *
     * The values go through the same parser every other request uses, so a walk clamps,
     * refuses and implies exactly what `deskcam set` would for the same word. There is no
     * second idea here of what a parameter means. Card 31, card 55.
     */
    public List<CamSettings> valueSteps(CamSettings base, String name, List<String> values) {
        List<CamSettings> out = new ArrayList<>(values.size());
        for (String value : values) {
            CamSettings step = base.clone();
            String problem = Params.apply(step, name, value, caps);
            if (problem != null) throw new IllegalArgumentException(problem);
            out.add(step);
        }
        return out;
    }

    /**
     * The exposures of a bracket, each a whole multiple of the base period.
     *
     * Powers of two from one period, so every step is one stop apart AND every step
     * integrates a whole number of PWM cycles. A lit panel is not a steady source: it is
     * switched at some hundreds of hertz, and an exposure that is not a whole number of
     * its periods catches a different part of the duty cycle, so the frames of the bracket
     * disagree about the brightness of a panel that never changed. Arbitrary stops are
     * exactly the thing this must not do. Card 7.
     *
     * The ISO is not touched. Only the time changes, because two frames that differ in
     * both cannot be merged without knowing how the gain behaved.
     */
    public List<CamSettings> exposureSteps(CamSettings base, long baseNs, int stops) {
        List<CamSettings> out = new ArrayList<>(Math.max(0, stops));
        for (int i = 0; i < stops; i++) {
            CamSettings step = base.clone();
            step.exposureNs = Geom.clampLong(baseNs << i, caps.minExposureNs, caps.maxExposureNs);
            step.aeAuto = false;
            out.add(step);
        }
        return out;
    }

    /**
     * A burst of full-resolution JPEGs, all with the same settings.
     *
     * The frames go to the camera as one submission, so the HAL runs them back to back at
     * the fastest rate the sensor allows. That matters because the point of a burst is to
     * average the noise away, and every frame must see the same scene and the same
     * exposure for the average to mean anything.
     */
    public Burst captureBurst(CamSettings req, int n, long timeoutMs) throws Exception {
        if (n > caps.maxBurst) {
            // Before the capture, not part way through it. A burst of 64 full-resolution
            // frames is held three times over before the client sees any of it, and the
            // OutOfMemoryError that produced was an Error rather than an Exception, so it
            // walked straight past every handler and took the service with it.
            throw new IllegalArgumentException("n=" + n + " will not fit in memory on this "
                    + "device; the most this heap can carry at " + stillSize.getWidth() + "x"
                    + stillSize.getHeight() + " is n=" + caps.maxBurst);
        }
        synchronized (rawCaptureLock) {      // one multi-frame operation at a time
            Pending p = new Pending(n);
            pending.add(p);
            try {
                synchronized (lock) {
                    if (session == null || device == null) throw new IllegalStateException(notRunning());
                    List<CaptureRequest> reqs = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        CaptureRequest.Builder b =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        b.addTarget(stillReader.getSurface());
                        applyTo(b, req, true);
                        reqs.add(b.build());
                    }
                    session.captureBurst(reqs, callbackFor(p), camHandler);
                }

                List<Frame> got = p.await(System.currentTimeMillis() + timeoutMs);
                if (got.isEmpty()) throw new IllegalStateException("burst produced no frames");
                List<byte[]> frames = new ArrayList<>(got.size());
                for (Frame f : got) {
                    frames.add(req.stillIsPristine() ? f.jpeg : cropJpeg(f.jpeg, req));
                    f.jpeg = null;      // the cropped copy exists; let the original go
                }
                // The frames of a burst all share one exposure, so the first result
                // describes all of them.
                return new Burst(frames, n, provenance(req, got.get(0).result));
            } finally {
                pending.remove(p);
                p.abandon();
            }
        }
    }

    // ---------------------------------------------------------- provenance

    /**
     * A record of how a picture was taken, for the file itself and for the sidecar.
     *
     * The values come from the capture result of THIS frame. The CLI used to build the
     * sidecar from a second /api/status request made after the capture returned, which
     * described whatever the camera was doing by then.
     */
    private JSONObject provenance(CamSettings s, TotalCaptureResult r) {
        JSONObject o = new JSONObject();
        try {
            o.put("tool", "DeskCam");
            o.put("captured_at", java.time.OffsetDateTime.now().toString());
            o.put("capture_path", s.capturePath());
            o.put("settings", s.toJson());
            if (sensors != null) o.put("orientation", sensors.toJson());
            if (r != null) {
                o.put("measured", measuredJson(r));
                o.put("pipeline", pipelineJson(r));
            }
        } catch (Exception e) {
            Log.w(TAG, "provenance", e);
        }
        return o;
    }

    /** What the camera reports it actually did, out of one capture result. */
    private static JSONObject measuredJson(TotalCaptureResult r) throws JSONException {
        JSONObject m = new JSONObject();
        Long en = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (en != null) {
            m.put("exposure_ns", en);
            m.put("exposure_human", CamSettings.humanExposure(en));
        }
        putIf(m, "iso", r.get(CaptureResult.SENSOR_SENSITIVITY));
        Float fd = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (fd != null) {
            m.put("focus_diopters", CamSettings.round2(fd));
            // APPROXIMATE calibration, so this is indicative and not a measurement.
            if (fd > 0.001f) m.put("focus_metres_approx", CamSettings.round3(1f / fd));
        }
        Integer afs = r.get(CaptureResult.CONTROL_AF_STATE);
        if (afs != null) m.put("af_state", afStateName(afs));
        Integer aes = r.get(CaptureResult.CONTROL_AE_STATE);
        if (aes != null) m.put("ae_state", aes);
        Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts != null) m.put("sensor_timestamp", ts);

        // Measurement mode locks the white balance, which holds the gains at whatever
        // they happened to be. Two sessions can lock different gains, and without these
        // values nothing records why two captures of one subject differ in colour.
        RggbChannelVector g = r.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (g != null) {
            m.put("awb_gains", new JSONArray()
                    .put(CamSettings.round3(g.getRed()))
                    .put(CamSettings.round3(g.getGreenEven()))
                    .put(CamSettings.round3(g.getGreenOdd()))
                    .put(CamSettings.round3(g.getBlue())));
        }
        android.hardware.camera2.params.ColorSpaceTransform t =
                r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (t != null) {
            JSONArray a = new JSONArray();
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    a.put(CamSettings.round3(t.getElement(col, row).doubleValue()));
                }
            }
            m.put("colour_transform", a);
        }
        return m;
    }

    /** What the HAL applied, which is not always what was asked for. Rule R4. */
    private static JSONObject pipelineJson(TotalCaptureResult r) throws JSONException {
        JSONObject pipe = new JSONObject();
        putName(pipe, "noise_reduction", r.get(CaptureResult.NOISE_REDUCTION_MODE),
                new String[]{"off", "fast", "high_quality", "minimal", "zero_shutter_lag"});
        putName(pipe, "edge", r.get(CaptureResult.EDGE_MODE),
                new String[]{"off", "fast", "high_quality", "zero_shutter_lag"});
        putName(pipe, "tonemap", r.get(CaptureResult.TONEMAP_MODE),
                new String[]{"contrast_curve", "fast", "high_quality", "gamma_value", "preset_curve"});
        putName(pipe, "shading", r.get(CaptureResult.SHADING_MODE),
                new String[]{"off", "fast", "high_quality"});
        putName(pipe, "hot_pixel", r.get(CaptureResult.HOT_PIXEL_MODE),
                new String[]{"off", "fast", "high_quality"});
        putName(pipe, "aberration", r.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE),
                new String[]{"off", "fast", "high_quality"});
        putName(pipe, "ois", r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                new String[]{"off", "on"});
        TonemapCurve tc = r.get(CaptureResult.TONEMAP_CURVE);
        if (tc != null) {
            int n = tc.getPointCount(TonemapCurve.CHANNEL_GREEN);
            pipe.put("tonemap_points", n);
            // Two points from 0,0 to 1,1 is the identity curve we ask for.
            if (n == 2) {
                float[] pts = new float[4];
                tc.copyColorCurve(TonemapCurve.CHANNEL_GREEN, pts, 0);
                pipe.put("tonemap_curve", "(" + pts[0] + "," + pts[1] + ") ("
                        + pts[2] + "," + pts[3] + ")");
            }
        }
        return pipe;
    }

    /** Writes the provenance into the JPEG's EXIF UserComment. Pixels are untouched. */
    private byte[] withExif(byte[] jpeg, JSONObject prov) {
        java.io.File tmp = null;
        try {
            tmp = java.io.File.createTempFile("deskcam", ".jpg", ctx.getCacheDir());
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
                fo.write(jpeg);
            }
            ExifInterface ex = new ExifInterface(tmp.getAbsolutePath());
            ex.setAttribute(ExifInterface.TAG_USER_COMMENT, prov.toString());
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "DeskCam");
            ex.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            ex.saveAttributes();
            byte[] out = new byte[(int) tmp.length()];
            try (java.io.FileInputStream fi = new java.io.FileInputStream(tmp)) {
                int read = 0;
                while (read < out.length) {
                    int n = fi.read(out, read, out.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "could not embed exif, returning the plain image: " + e);
            return jpeg;
        } finally {
            if (tmp != null && !tmp.delete()) Log.d(TAG, "could not delete the exif temp file");
        }
    }

    public boolean rawAvailable() { return rawReader != null; }

    private void makeThumb(byte[] jpeg) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;               // a reduced read, not a full decode
            Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            if (b != null) {
                lastStillThumb = b;
                lastStillAt = System.currentTimeMillis();
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "thumbnail for the phone screen: " + e);
        }
    }

    public Bitmap lastStillThumb() { return lastStillThumb; }
    public long lastStillAt() { return lastStillAt; }
    public int streamClients() { return streamClients.get(); }

    private String notRunning() {
        return "disconnected".equals(state)
                ? "another app is using the camera; DeskCam reopens it automatically when it is free"
                : "camera not running (state " + state + ")";
    }

    /** The ROI the user is aimed at, as pixels of the RAW frame, for the response header. */
    public String rawRoiHeader(CamSettings s) {
        if (rawSize == null) return "";
        Rect r = s.roiFor(rawSize.getWidth(), rawSize.getHeight());
        return r.left + "," + r.top + "," + r.width() + "," + r.height();
    }

    private static int exifOrientation(int rotateDegrees) {
        switch (rotateDegrees) {
            case 90:  return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default:  return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * A single preview frame, cropped to the ROI. Much faster than a still.
     *
     * This always waits for a frame that arrives after the call. The buffered frame may
     * have been exposed before the caller changed a setting, and returning it would report
     * the previous exposure as though it were the new one. `skip` discards more frames,
     * because the camera keeps several requests in flight and the first new frame can
     * still carry the old settings.
     */
    public Shot grabFrame(CamSettings req, long timeoutMs, int skip) throws Exception {
        lastDemandMs = System.currentTimeMillis();
        long from = currentSeq() + Math.max(0, skip);
        byte[] jpeg = frameAfter(req, from, timeoutMs);
        return new Shot(jpeg, provenance(req, lastResult));
    }

    /** Blocks until a frame newer than afterSeq arrives, then returns it as JPEG. */
    public byte[] frameAfter(CamSettings req, long afterSeq, long timeoutMs) throws Exception {
        lastDemandMs = System.currentTimeMillis();
        byte[] nv21; int w, h;
        synchronized (frameLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (latestNv21 == null || latestSeq <= afterSeq) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) throw new IllegalStateException("no preview frame within " + timeoutMs + "ms");
                frameLock.wait(Math.min(wait, 250));
            }
            nv21 = latestNv21; w = latestW; h = latestH;
        }

        Rect roi = req.roiFor(w, h);
        // NV21 chroma is subsampled 2x2, so an odd crop origin shifts the colour planes.
        roi.left &= ~1; roi.top &= ~1;
        roi.right = Math.min(w, roi.left + (roi.width() & ~1));
        roi.bottom = Math.min(h, roi.top + (roi.height() & ~1));

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 18);
        new YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(roi, req.jpegQuality, out);
        byte[] jpeg = out.toByteArray();

        if (req.rotate == 0 && req.outW == null && req.outH == null) return jpeg;

        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return jpeg;
        bmp = transform(bmp, req, caps);
        ByteArrayOutputStream o2 = new ByteArrayOutputStream(1 << 18);
        bmp.compress(Bitmap.CompressFormat.JPEG, req.jpegQuality, o2);
        bmp.recycle();
        return o2.toByteArray();
    }

    public long currentSeq() {
        synchronized (frameLock) { return latestSeq; }
    }

    /**
     * How sharp the last converted preview frame was, over the region of interest.
     *
     * Measured here and not in the frame callback, so a phone nobody is watching pays
     * nothing for it. Measured lazily also means the answer can be old, which is why its
     * age is reported beside it and never left for the caller to assume. Card 9.
     *
     * Nothing here demands a frame. Decision D7 says the engine converts on demand, and
     * making a status poll count as demand would have the console converting every
     * preview frame at thirty a second for a page that is not showing video. An agent
     * closing a focus loop asks for a fresh one with sharpness=1, which is one request
     * and one frame.
     */
    public JSONObject sharpness() throws JSONException {
        byte[] nv21;
        int w, h;
        long at, seq;
        synchronized (frameLock) {
            nv21 = latestNv21;
            w = latestW;
            h = latestH;
            at = latestAtMs;
            seq = latestSeq;
        }
        if (nv21 == null) return null;

        // The luma plane is the first w*h bytes of NV21, which is why this costs nothing
        // beyond the arithmetic: no decode, no copy, no colour.
        CamSettings s = snapshot();
        Rect roi = s.roiFor(w, h);
        long t0 = System.nanoTime();
        double value = Sharp.focus(nv21, w, h, roi.left, roi.top, roi.width(), roi.height());
        double costMs = (System.nanoTime() - t0) / 1e6;
        if (value == Sharp.NOT_MEASURABLE) return null;

        JSONObject o = new JSONObject();
        o.put("value", CamSettings.round2(value));
        o.put("frame_age_ms", Math.max(0, System.currentTimeMillis() - at));
        o.put("frame_seq", seq);
        o.put("cost_ms", CamSettings.round2(costMs));
        o.put("region", roi.left + "," + roi.top + " " + roi.width() + "x" + roi.height());
        o.put("means", "variance of the Laplacian over the region of interest of a preview "
                + "frame. Higher is sharper. It is a comparison and not a measurement: it "
                + "moves with the subject, the region and the noise, so only compare values "
                + "taken with everything but the focus held still.");
        return o;
    }

    /**
     * The sharpness of the last converted frame as a bare number, for a loop that reads it
     * many times and does not want a JSON object each time.
     *
     * Returns Sharp.NOT_MEASURABLE when there is no frame, which is the same answer the
     * caller gets for a region too small to hold the kernel: nothing to compare.
     */
    private double sharpnessValue() {
        byte[] nv21;
        int w, h;
        synchronized (frameLock) {
            nv21 = latestNv21;
            w = latestW;
            h = latestH;
        }
        if (nv21 == null) return Sharp.NOT_MEASURABLE;
        Rect roi = snapshot().roiFor(w, h);
        return Sharp.focus(nv21, w, h, roi.left, roi.top, roi.width(), roi.height());
    }

    /**
     * Walks the lens and stops where the picture is sharpest, reporting the curve it saw.
     *
     * The one loop in this system that has to run on the phone. Every step depends on the
     * frame the last step produced, so driven from the workstation each decision costs a
     * round trip: the fourteen readings that found the peak by hand cost a set, a settle,
     * a fresh frame and a status apiece. Here they cost none. Card 56.
     *
     * Coarse then fine, because the curve has one maximum and a coarse pass finds which
     * part of the range holds it for a fraction of the readings a fine pass over the whole
     * range would need.
     *
     * This is the one walk that is a change rather than an excursion: when it chooses, it
     * leaves the lens where it decided, because deciding is the whole point of it.
     * /api/focussweep puts the focus back; this does not. When it refuses it puts the
     * focus back, because then it decided nothing and has no business moving the bench.
     */
    public JSONObject focusHunt(CamSettings base, float from, float to, int coarse, int fine,
                                long settleMs, int fresh, long timeoutMs) throws Exception {
        long started = System.currentTimeMillis();
        CamSettings restore = snapshot();
        float lo = Math.min(from, to);
        float hi = Math.max(from, to);
        List<Hunt.Reading> readings = new ArrayList<>();
        JSONArray walked = new JSONArray();

        sweepInto(base, from, to, coarse, settleMs, fresh, timeoutMs, readings, walked);
        Hunt.Verdict verdict = Hunt.judge(readings, lo, hi, caps.minFocusDiopters);

        // A fine pass is only worth its readings once a peak is known to be in the range.
        // Refining around the end of a flat curve measures the same nothing more closely.
        int coarseReadings = readings.size();
        if (verdict.chose() && fine >= 2) {
            float step = Math.abs(hi - lo) / Math.max(1, coarse - 1);
            sweepInto(base,
                    Geom.clamp(verdict.at - step, 0f, caps.minFocusDiopters),
                    Geom.clamp(verdict.at + step, 0f, caps.minFocusDiopters),
                    fine, settleMs, fresh, timeoutMs, readings, walked);
            verdict = Hunt.judge(readings, lo, hi, caps.minFocusDiopters);
        }

        JSONObject out = new JSONObject();
        out.put("ok", verdict.chose());
        out.put("diopters", CamSettings.round3(verdict.at));
        if (verdict.at > 0.001f) {
            out.put("focus_metres_approx", CamSettings.round3(1f / verdict.at));
        }
        out.put("sharpness", CamSettings.round2(verdict.sharpness));
        out.put("contrast", CamSettings.round3(verdict.contrast));
        out.put("from_diopters", CamSettings.round3(lo));
        out.put("to_diopters", CamSettings.round3(hi));
        out.put("coarse_readings", coarseReadings);
        out.put("fine_readings", readings.size() - coarseReadings);
        out.put("readings", readings.size());
        out.put("walked", walked);
        out.put("means", "sharpness is the variance of the Laplacian over the region of "
                + "interest. It is a comparison and not a measurement: it moves with the "
                + "subject, the region and the noise, so these values describe this hunt "
                + "and nothing else. contrast is how far the curve rose above its own "
                + "floor, and is what decides whether there was a peak at all.");
        if (base.aeAuto) {
            out.put("warning", "automatic exposure was on, so the exposure moved between "
                    + "readings and the sharpness moved with it. This hunt may have climbed "
                    + "the exposure loop rather than the lens. Fix exposure= and iso=.");
        }

        if (verdict.chose()) {
            CamSettings chosen = base.clone();
            chosen.focusDiopters = verdict.at;
            chosen.afMode = CamSettings.AF_OFF;
            update(chosen);
        } else {
            out.put("refused", verdict.refusal);
            out.put("reason", verdict.reason);
            update(restore);
            out.put("focus_restored_to", restore.focusDiopters == null
                    ? JSONObject.NULL : CamSettings.round3(restore.focusDiopters));
        }
        out.put("millis", System.currentTimeMillis() - started);
        return out;
    }

    /**
     * One pass of the hunt: set the lens, wait for a frame taken there, measure it.
     *
     * A position that could not be measured is left out of the curve rather than recorded
     * as zero. Zero is a reading of a flat field, and one of those in the middle of a
     * sweep would put a trough where there was only a missing frame.
     */
    private void sweepInto(CamSettings base, float from, float to, int steps, long settleMs,
                           int fresh, long timeoutMs, List<Hunt.Reading> readings,
                           JSONArray walked) throws Exception {
        for (int i = 0; i < steps; i++) {
            float at = Geom.clamp(steps < 2 ? from : Geom.sweepStep(from, to, i, steps),
                    0f, caps.minFocusDiopters);
            CamSettings step = base.clone();
            step.focusDiopters = at;
            step.afMode = CamSettings.AF_OFF;
            update(step);
            if (settleMs > 0) Thread.sleep(settleMs);
            // A fresh frame, or the reading describes the focus before this one.
            demandFrame(timeoutMs, fresh);
            double value = sharpnessValue();
            if (value == Sharp.NOT_MEASURABLE) continue;
            readings.add(new Hunt.Reading(at, value));
            walked.put(new JSONObject()
                    .put("diopters", CamSettings.round3(at))
                    .put("sharpness", CamSettings.round2(value)));
        }
    }

    /**
     * Converts one preview frame now, so the next sharpness reading describes this moment.
     *
     * The lens takes time to arrive, and the pipeline has several requests in flight, so a
     * frame that exists the instant after a focus change was very likely exposed before
     * it. skip discards that many first, for the same reason grabFrame does.
     */
    public void demandFrame(long timeoutMs, int skip) throws InterruptedException {
        lastDemandMs = System.currentTimeMillis();
        long from = currentSeq() + Math.max(0, skip);
        synchronized (frameLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (latestSeq <= from) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) return;   // say nothing; the age of the answer says it
                frameLock.wait(Math.min(wait, 100));
            }
        }
    }

    /**
     * The thread of the script that holds the camera, or null.
     *
     * One camera, one tape at a time. This is not about the engine's own thread safety,
     * which the session lock already covers. It is about a sequence: nothing stops the
     * console, which polls the state every two seconds and can post new settings, or a
     * second agent, from changing the camera between a SET and a SNAP. Every multi-step
     * sequence driven from outside is racy for that reason, and holding the camera for the
     * length of a tape is what a script adds over running the same steps by hand. Card 57.
     */
    private final java.util.concurrent.atomic.AtomicReference<Thread> scriptThread =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Takes the camera for this thread, or answers false because someone else has it. */
    public boolean holdForScript() {
        return scriptThread.compareAndSet(null, Thread.currentThread());
    }

    /** Gives it back. Safe to call when this thread never held it. */
    public void releaseScript() {
        scriptThread.compareAndSet(Thread.currentThread(), null);
    }

    /** Whether a script other than this thread's own holds the camera. */
    public boolean scriptHoldsCamera() {
        Thread t = scriptThread.get();
        return t != null && t != Thread.currentThread();
    }

    /**
     * Counts the clients watching a stream.
     *
     * It was a read then a write on a plain field, so two streams starting at once could
     * lose an update and leave the count above zero for ever. The engine then converted
     * every preview frame although nobody was watching, which is exactly what decision D7
     * exists to avoid.
     */
    public void addStreamClient(int delta) {
        streamClients.updateAndGet(v -> Math.max(0, v + delta));
    }

    /** Applies the requested resize and rotation, in that order. */
    private static Bitmap transform(Bitmap in, CamSettings s, CamSettings.Caps caps) {
        Bitmap b = in;
        if (s.outW != null || s.outH != null) {
            int tw, th;
            if (s.outW != null && s.outH != null) { tw = s.outW; th = s.outH; }
            else if (s.outW != null) { tw = s.outW; th = Math.max(1, Math.round(in.getHeight() * (s.outW / (float) in.getWidth()))); }
            else { th = s.outH; tw = Math.max(1, Math.round(in.getWidth() * (s.outH / (float) in.getHeight()))); }
            tw = Math.max(1, tw);
            th = Math.max(1, th);
            if ((long) tw * th > caps.maxOutputPixels) {
                throw new IllegalArgumentException("an output of " + tw + "x" + th
                        + " will not fit in memory; this device allows "
                        + caps.maxOutputPixels + " pixels");
            }
            Bitmap scaled = Bitmap.createScaledBitmap(b, tw, th, true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        if (s.rotate != 0) {
            Matrix m = new Matrix();
            m.postRotate(s.rotate);
            Bitmap rot = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (rot != b) b.recycle();
            b = rot;
        }
        return b;
    }

    // ---------------------------------------------------------------- yuv

    static byte[] yuv420ToNv21(Image image) {
        int w = image.getWidth(), h = image.getHeight();
        int ySize = w * h;
        byte[] nv21 = new byte[ySize + ySize / 2];
        Image.Plane[] p = image.getPlanes();

        ByteBuffer yBuf = p[0].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int pos = 0;
        if (yPix == 1 && yRow == w) {
            yBuf.get(nv21, 0, Math.min(ySize, yBuf.remaining()));
            pos = ySize;
        } else {
            for (int r = 0; r < h; r++) {
                int base = r * yRow;
                for (int c = 0; c < w; c++) nv21[pos++] = yBuf.get(base + c * yPix);
            }
        }

        ByteBuffer uBuf = p[1].getBuffer(), vBuf = p[2].getBuffer();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int cw = w / 2, ch = h / 2;
        for (int r = 0; r < ch; r++) {
            int vb = r * vRow, ub = r * uRow;
            for (int c = 0; c < cw; c++) {
                nv21[pos++] = vBuf.get(vb + c * vPix);
                nv21[pos++] = uBuf.get(ub + c * uPix);
            }
        }
        return nv21;
    }

    // -------------------------------------------------------------- status

    public CamSettings snapshot() {
        synchronized (lock) { return settings.clone(); }
    }

    public CamSettings.Caps caps() { return caps; }

    public String state() { return state; }

    public void setSensors(Sensors s) { this.sensors = s; }

    public JSONObject orientation() throws JSONException {
        return sensors == null ? new JSONObject().put("available", false) : sensors.toJson();
    }

    public JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("state", state);
        if (lastError != null) o.put("last_error", lastError);
        o.put("settings", snapshot().toJson());
        o.put("limits", caps.toJson());
        o.put("frames_seen", framesSeen.get());
        o.put("stream_clients", streamClients.get());

        JSONObject sensor = new JSONObject();
        sensor.put("active_array", activeArray.width() + "x" + activeArray.height());
        sensor.put("still_size", stillSize.getWidth() + "x" + stillSize.getHeight());
        CamSettings s = snapshot();
        Rect roi = s.roiFor(stillSize.getWidth(), stillSize.getHeight());
        sensor.put("still_roi", roi.left + "," + roi.top + " " + roi.width() + "x" + roi.height());
        sensor.put("still_roi_megapixels",
                CamSettings.round2(roi.width() * (double) roi.height() / 1e6));
        sensor.put("raw_available", rawReader != null);
        sensor.put("shading_map_supported", shadingMapSupported);
        if (rawSize != null) sensor.put("raw_size", rawSize.getWidth() + "x" + rawSize.getHeight());
        if (chars != null) {
            android.hardware.camera2.params.BlackLevelPattern blp =
                    chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            Integer wl = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            if (blp != null) {
                int[] v = new int[4];
                blp.copyTo(v, 0);
                sensor.put("raw_black_level", v[0]);
            }
            if (wl != null) sensor.put("raw_white_level", wl);
        }
        o.put("sensor", sensor);
        if (sensors != null) o.put("orientation", sensors.toJson());

        TotalCaptureResult r = lastResult;
        if (r != null) {
            // These describe the PREVIEW, because that is the only repeating request. A
            // still is a different picture and carries its own values in its sidecar and
            // its EXIF.
            o.put("measured", measuredJson(r));
            o.put("measured_from", "preview");
            o.put("pipeline", pipelineJson(r));
        }
        JSONObject sharp = sharpness();
        if (sharp != null) o.put("sharpness", sharp);
        return o;
    }

    private static void putIf(JSONObject o, String k, Object v) throws JSONException {
        if (v != null) o.put(k, v);
    }

    private static void putName(JSONObject o, String key, Integer v, String[] names) throws JSONException {
        if (v == null) return;
        o.put(key, (v >= 0 && v < names.length) ? names[v] : String.valueOf(v));
    }

    private static String afStateName(int s) {
        switch (s) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE: return "inactive";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN: return "scanning";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: return "focused";
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: return "scanning";
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: return "focused-locked";
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: return "failed-locked";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: return "unfocused";
            default: return String.valueOf(s);
        }
    }

    /** Enumerates every camera with the facts that matter for choosing one. */
    public JSONArray listCameras() throws Exception {
        JSONArray arr = new JSONArray();
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            JSONObject o = new JSONObject();
            o.put("id", id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            o.put("facing", f == null ? "?" : (f == CameraCharacteristics.LENS_FACING_BACK ? "back"
                    : f == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external"));
            Rect aa = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (aa != null) o.put("active_array", aa.width() + "x" + aa.height());
            StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (m != null) {
                Size big = largestSize(m.getOutputSizes(ImageFormat.JPEG));
                if (big != null) o.put("max_jpeg", big.getWidth() + "x" + big.getHeight());
            }
            Float mfd = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (mfd != null && mfd > 0) o.put("closest_focus_metres", CamSettings.round3(1.0 / mfd));
            float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (fl != null && fl.length > 0) o.put("focal_length_mm", fl[0]);
            int[] cap = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            JSONArray ca = new JSONArray();
            if (cap != null) for (int x : cap) ca.put(capName(x));
            o.put("capabilities", ca);
            arr.put(o);
        }
        return arr;
    }

    private static String capName(int c) {
        switch (c) {
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE: return "backward_compatible";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: return "manual_sensor";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: return "manual_post";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW: return "raw";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: return "burst";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: return "logical_multi";
            default: return "cap_" + c;
        }
    }

    private static Size largestSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private static Size nearestSize(Size[] sizes, int w, int h) {
        if (sizes == null || sizes.length == 0) return new Size(w, h);
        Size best = null;
        long want = (long) w * h;
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - want);
            if (diff < bestDiff) { bestDiff = diff; best = s; }
        }
        return best;
    }

    public List<String> previewSizeOptions() {
        List<String> out = new ArrayList<>();
        if (chars == null) return out;
        StreamConfigurationMap m = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (m == null) return out;
        for (Size s : m.getOutputSizes(ImageFormat.YUV_420_888)) out.add(s.getWidth() + "x" + s.getHeight());
        return out;
    }
}
