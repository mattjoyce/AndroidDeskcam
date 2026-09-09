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
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Owns the Camera2 device, the capture session, and all image production. */
public class CameraEngine {

    public static final String TAG = "DeskCam";

    private final Context ctx;
    private final CameraManager cm;

    private HandlerThread camThread;
    private Handler camHandler;
    private Executor camExecutor;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader previewReader, stillReader;
    private CaptureRequest.Builder previewBuilder;

    private CameraCharacteristics chars;
    private CamSettings.Caps caps = new CamSettings.Caps();
    private Rect activeArray = new Rect(0, 0, 4032, 3024);
    private Size stillSize = new Size(4032, 3024);

    private final Object lock = new Object();
    private CamSettings settings = new CamSettings();

    /** Most recent preview frame, already converted to NV21. Guarded by frameLock. */
    private final Object frameLock = new Object();
    private byte[] latestNv21;
    private int latestW, latestH;
    private long latestSeq = 0;

    /** Frames are only converted while something is actually asking for them. */
    private volatile long lastDemandMs = 0;
    private volatile int streamClients = 0;

    private final ArrayBlockingQueue<byte[]> stillQueue = new ArrayBlockingQueue<>(1);
    private volatile TotalCaptureResult lastResult;
    private volatile String state = "stopped";
    private volatile String lastError = null;

    public CameraEngine(Context ctx) {
        this.ctx = ctx;
        this.cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    // ------------------------------------------------------------ lifecycle

    public void start() throws Exception {
        camThread = new HandlerThread("deskcam-camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        camExecutor = camHandler::post;
        openAndConfigure(settings.clone());
    }

    public void stop() {
        synchronized (lock) {
            closeSessionAndDevice();
            state = "stopped";
        }
        if (camThread != null) {
            camThread.quitSafely();
            try { camThread.join(1500); } catch (InterruptedException ignored) { }
            camThread = null;
            camHandler = null;
        }
    }

    private void closeSessionAndDevice() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        device = null;
        if (previewReader != null) { previewReader.close(); previewReader = null; }
        if (stillReader != null) { stillReader.close(); stillReader = null; }
        synchronized (frameLock) { latestNv21 = null; }
    }

    /**
     * Opens the requested camera and builds a session with a YUV preview reader and a
     * full-resolution JPEG still reader. Called again whenever the camera id or either
     * output size changes, since those are fixed at session-configuration time.
     */
    private void openAndConfigure(CamSettings s) throws Exception {
        closeSessionAndDevice();
        state = "opening";
        lastError = null;

        chars = cm.getCameraCharacteristics(s.cameraId);
        Rect aa = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (aa != null) activeArray = aa;
        caps = CamSettings.Caps.from(chars, activeArray.width());

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("no stream configuration map");

        stillSize = (s.stillW > 0 && s.stillH > 0)
                ? nearestSize(map.getOutputSizes(ImageFormat.JPEG), s.stillW, s.stillH)
                : largestSize(map.getOutputSizes(ImageFormat.JPEG));
        Size pv = nearestSize(map.getOutputSizes(ImageFormat.YUV_420_888), s.previewW, s.previewH);

        stillReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(), ImageFormat.JPEG, 2);
        stillReader.setOnImageAvailableListener(r -> {
            try (Image img = r.acquireLatestImage()) {
                if (img == null) return;
                ByteBuffer b = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[b.remaining()];
                b.get(data);
                stillQueue.offer(data);
            } catch (Exception e) {
                Log.w(TAG, "still reader", e);
            }
        }, camHandler);

        previewReader = ImageReader.newInstance(pv.getWidth(), pv.getHeight(), ImageFormat.YUV_420_888, 3);
        previewReader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            try {
                // Drain but do not pay for conversion when nobody is watching.
                boolean wanted = streamClients > 0
                        || (System.currentTimeMillis() - lastDemandMs) < 2000;
                if (wanted) {
                    byte[] nv21 = yuv420ToNv21(img);
                    synchronized (frameLock) {
                        latestNv21 = nv21;
                        latestW = img.getWidth();
                        latestH = img.getHeight();
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
                synchronized (openLatch) { done[0] = true; openLatch.notifyAll(); }
            }
            @Override public void onError(CameraDevice cd, int err) {
                cd.close();
                openErr[0] = new IllegalStateException("camera open error " + err);
                state = "error";
                lastError = "camera open error " + err;
                synchronized (openLatch) { done[0] = true; openLatch.notifyAll(); }
            }
        });

        synchronized (openLatch) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!done[0] && System.currentTimeMillis() < deadline) openLatch.wait(200);
        }
        if (openErr[0] != null) throw openErr[0];
        if (device == null) throw new IllegalStateException("camera did not open in time");

        List<OutputConfiguration> outs = Arrays.asList(
                new OutputConfiguration(previewReader.getSurface()),
                new OutputConfiguration(stillReader.getSurface()));

        final Object sesLatch = new Object();
        final boolean[] sesDone = new boolean[1];
        final Exception[] sesErr = new Exception[1];

        device.createCaptureSession(new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, camExecutor,
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession cs) {
                        session = cs;
                        synchronized (sesLatch) { sesDone[0] = true; sesLatch.notifyAll(); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession cs) {
                        sesErr[0] = new IllegalStateException("capture session configuration failed");
                        synchronized (sesLatch) { sesDone[0] = true; sesLatch.notifyAll(); }
                    }
                }));

        synchronized (sesLatch) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!sesDone[0] && System.currentTimeMillis() < deadline) sesLatch.wait(200);
        }
        if (sesErr[0] != null) throw sesErr[0];
        if (session == null) throw new IllegalStateException("session did not configure in time");

        previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        previewBuilder.addTarget(previewReader.getSurface());
        applyTo(previewBuilder, s, false);
        session.setRepeatingRequest(previewBuilder.build(), resultCb, camHandler);

        settings = s;
        settings.previewW = pv.getWidth();
        settings.previewH = pv.getHeight();
        state = "running";
        Log.i(TAG, "camera " + s.cameraId + " running, preview " + pv + " still " + stillSize);
    }

    private final CameraCaptureSession.CaptureCallback resultCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            lastResult = res;
        }
    };

    // ------------------------------------------------------------- settings

    /** Applies new settings, rebuilding the session only when something structural changed. */
    public CamSettings update(CamSettings next) throws Exception {
        synchronized (lock) {
            boolean structural = !next.cameraId.equals(settings.cameraId)
                    || next.previewW != settings.previewW || next.previewH != settings.previewH
                    || next.stillW != settings.stillW || next.stillH != settings.stillH;
            if (structural || session == null) {
                openAndConfigure(next);
            } else {
                settings = next;
                applyTo(previewBuilder, next, false);
                session.setRepeatingRequest(previewBuilder.build(), resultCb, camHandler);
            }
            return settings.clone();
        }
    }

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

        b.set(CaptureRequest.CONTROL_AWB_MODE, s.awbMode);
        b.set(CaptureRequest.CONTROL_AWB_LOCK, s.awbLock);

        // Torch. Level control needs API 35+; below that it is simply on or off.
        if (s.torch > 0) {
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            if (Build.VERSION.SDK_INT >= 35 && caps.flashMaxLevel > 1) {
                try {
                    b.set(CaptureRequest.FLASH_STRENGTH_LEVEL,
                            CamSettings.clampInt(s.torch, 1, caps.flashMaxLevel));
                } catch (IllegalArgumentException ignored) { }
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
        if (forStill) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
        }
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

    /** Full-resolution still, cropped to the ROI. */
    public byte[] captureStill(long timeoutMs) throws Exception {
        CamSettings s;
        synchronized (lock) {
            if (session == null || device == null) throw new IllegalStateException("camera not running");
            s = settings.clone();
            stillQueue.clear();
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(stillReader.getSurface());
            applyTo(b, s, true);
            session.capture(b.build(), null, camHandler);
        }
        byte[] jpeg = stillQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (jpeg == null) throw new IllegalStateException("still capture timed out after " + timeoutMs + "ms");
        if (s.stillIsPristine()) return jpeg;
        return cropJpeg(jpeg, s);
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
        bmp = transform(bmp, s);

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        bmp.compress(Bitmap.CompressFormat.JPEG, s.jpegQuality, out);
        bmp.recycle();
        return out.toByteArray();
    }

    /** A single preview frame, cropped to the ROI. Much faster than a still. */
    public byte[] grabFrame(long timeoutMs) throws Exception {
        lastDemandMs = System.currentTimeMillis();
        return frameAfter(0, timeoutMs);
    }

    /** Blocks until a frame newer than afterSeq arrives, then returns it as JPEG. */
    public byte[] frameAfter(long afterSeq, long timeoutMs) throws Exception {
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
        CamSettings s;
        synchronized (lock) { s = settings.clone(); }

        Rect roi = s.roiFor(w, h);
        // NV21 chroma is subsampled 2x2, so an odd crop origin shifts the colour planes.
        roi.left &= ~1; roi.top &= ~1;
        roi.right = Math.min(w, roi.left + (roi.width() & ~1));
        roi.bottom = Math.min(h, roi.top + (roi.height() & ~1));

        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 18);
        new YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(roi, s.jpegQuality, out);
        byte[] jpeg = out.toByteArray();

        if (s.rotate == 0 && s.outW == null && s.outH == null) return jpeg;

        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return jpeg;
        bmp = transform(bmp, s);
        ByteArrayOutputStream o2 = new ByteArrayOutputStream(1 << 18);
        bmp.compress(Bitmap.CompressFormat.JPEG, s.jpegQuality, o2);
        bmp.recycle();
        return o2.toByteArray();
    }

    public long currentSeq() {
        synchronized (frameLock) { return latestSeq; }
    }

    public void addStreamClient(int delta) { streamClients = Math.max(0, streamClients + delta); }

    /** Applies the requested resize and rotation, in that order. */
    private static Bitmap transform(Bitmap in, CamSettings s) {
        Bitmap b = in;
        if (s.outW != null || s.outH != null) {
            int tw, th;
            if (s.outW != null && s.outH != null) { tw = s.outW; th = s.outH; }
            else if (s.outW != null) { tw = s.outW; th = Math.max(1, Math.round(in.getHeight() * (s.outW / (float) in.getWidth()))); }
            else { th = s.outH; tw = Math.max(1, Math.round(in.getWidth() * (s.outH / (float) in.getHeight()))); }
            Bitmap scaled = Bitmap.createScaledBitmap(b, Math.max(1, tw), Math.max(1, th), true);
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

    public JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("state", state);
        if (lastError != null) o.put("last_error", lastError);
        o.put("settings", snapshot().toJson());
        o.put("limits", caps.toJson());

        JSONObject sensor = new JSONObject();
        sensor.put("active_array", activeArray.width() + "x" + activeArray.height());
        sensor.put("still_size", stillSize.getWidth() + "x" + stillSize.getHeight());
        CamSettings s = snapshot();
        Rect roi = s.roiFor(stillSize.getWidth(), stillSize.getHeight());
        sensor.put("still_roi", roi.left + "," + roi.top + " " + roi.width() + "x" + roi.height());
        sensor.put("still_roi_megapixels",
                CamSettings.round2(roi.width() * (double) roi.height() / 1e6));
        o.put("sensor", sensor);

        TotalCaptureResult r = lastResult;
        if (r != null) {
            JSONObject m = new JSONObject();
            putIf(m, "exposure_ns", r.get(CaptureResult.SENSOR_EXPOSURE_TIME));
            Long en = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (en != null) m.put("exposure_human", CamSettings.humanExposure(en));
            putIf(m, "iso", r.get(CaptureResult.SENSOR_SENSITIVITY));
            putIf(m, "focus_diopters", r.get(CaptureResult.LENS_FOCUS_DISTANCE));
            Integer afs = r.get(CaptureResult.CONTROL_AF_STATE);
            if (afs != null) m.put("af_state", afStateName(afs));
            Integer aes = r.get(CaptureResult.CONTROL_AE_STATE);
            if (aes != null) m.put("ae_state", aes);
            o.put("measured", m);
        }
        return o;
    }

    private static void putIf(JSONObject o, String k, Object v) throws JSONException {
        if (v != null) o.put(k, v);
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
