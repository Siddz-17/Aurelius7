package com.aurelius7.wiggle;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.Arrays;

/**
 * Thin Camera2 wrapper: preview on a TextureView + a YUV stream for analysis.
 *
 * All camera work (open, session, close) runs on ONE shared background thread that is never quit,
 * so a close always runs after the open it belongs to and a camera can never be left open by a
 * quick pause / flip (which would also block other apps from using the camera).
 */
public class CameraRig {

    public interface FrameSink { void onFrame(Image img); }
    public interface Callback {
        void onCameraReady(CameraRig rig, int w, int h, int sensorOrientation);
        void onCameraError(CameraRig rig, String msg);
    }

    private static HandlerThread THREAD;
    private static synchronized Handler sharedHandler() {
        if (THREAD == null || !THREAD.isAlive()) { THREAD = new HandlerThread("aurelius-cam"); THREAD.start(); }
        return new Handler(THREAD.getLooper());
    }

    private final Activity act;
    private final TextureView tv;
    private final Callback cb;
    public final boolean front;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private Surface previewSurface;
    private volatile boolean closed;

    public volatile int W, H, sensorOrientation;
    private boolean hasContinuousAf;
    public volatile boolean hasFlash;
    private Range<Integer> fpsRange;
    public volatile FrameSink sink;

    public CameraRig(Activity act, TextureView tv, Callback cb, boolean front) {
        this.act = act; this.tv = tv; this.cb = cb; this.front = front;
    }

    private void fail(String msg) { if (!closed) cb.onCameraError(this, msg); }

    public void start() {
        bg = sharedHandler();
        bg.post(new Runnable() { @Override public void run() {
            if (closed) return;
            try { open(); }
            catch (Throwable e) { fail("Camera open failed"); }
        }});
    }

    public void stop() {
        closed = true;
        sink = null;
        Handler h = bg;
        if (h == null) return;
        h.post(new Runnable() { @Override public void run() { closeAll(); } });
    }

    private void closeAll() {
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        try { if (device != null) device.close(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (previewSurface != null) previewSurface.release(); } catch (Throwable ignored) {}
        session = null; device = null; reader = null; previewSurface = null; builder = null;
    }

    private void open() throws Exception {
        CameraManager mgr = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
        String chosen = null;
        CameraCharacteristics chars = null;
        int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : mgr.getCameraIdList()) {
            CameraCharacteristics c = mgr.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == want) { chosen = id; chars = c; break; }
        }
        if (chosen == null) { fail(front ? "No selfie camera" : "No back camera"); return; }

        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so == null ? 90 : so;

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) { fail("Camera not supported"); return; }
        Size size = pickSize(map);
        W = size.getWidth(); H = size.getHeight();

        Boolean fl = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        hasFlash = fl != null && fl;
        int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        hasContinuousAf = false;
        if (afModes != null) for (int m : afModes) if (m == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) hasContinuousAf = true;

        // Prefer a fixed-ish 30fps range: caps exposure time, so fewer blurry frames while sliding.
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        fpsRange = null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (r.getUpper() == 30) {
                    if (fpsRange == null || r.getLower() > fpsRange.getLower()) fpsRange = r;
                }
            }
        }

        reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null || closed) return;
                    FrameSink s = sink;
                    if (s != null) s.onFrame(img);
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) { try { img.close(); } catch (Throwable ignored) {} }
                }
            }
        }, bg);

        mgr.openCamera(chosen, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) {
                if (closed) { d.close(); return; }   // stop() raced with the open: don't leak the camera
                device = d;
                createSession();
            }
            @Override public void onDisconnected(CameraDevice d) {
                try { d.close(); } catch (Throwable ignored) {}
                if (device == d) device = null;
                fail("Camera disconnected");
            }
            @Override public void onError(CameraDevice d, int error) {
                try { d.close(); } catch (Throwable ignored) {}
                if (device == d) device = null;
                fail("Camera error " + error);
            }
        }, bg);
    }

    private Size pickSize(StreamConfigurationMap map) {
        Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
        Size[] tex = map.getOutputSizes(SurfaceTexture.class);
        if (yuv == null) yuv = new Size[0];
        Size best = null;
        // Want 16:9, width <= 1280, dimensions divisible by 8 (the tracker downsamples 8x), preview-capable.
        for (Size s : yuv) {
            if (s.getWidth() > 1280 || s.getWidth() < 320) continue;
            if ((s.getWidth() & 7) != 0 || (s.getHeight() & 7) != 0) continue;
            if (!contains(tex, s)) continue;
            float ar = s.getWidth() / (float) s.getHeight();
            if (Math.abs(ar - 16f / 9f) > 0.02f) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        if (best == null) {
            for (Size s : yuv) {
                if (s.getWidth() > 1280 || s.getWidth() < 320 || !contains(tex, s)) continue;
                if ((s.getWidth() & 7) != 0 || (s.getHeight() & 7) != 0) continue;
                if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
            }
        }
        if (best == null) best = new Size(640, 480);
        return best;
    }

    private static boolean contains(Size[] arr, Size s) {
        if (arr == null) return false;
        for (Size a : arr) if (a.equals(s)) return true;
        return false;
    }

    private void createSession() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null) { fail("No preview surface"); return; }
            st.setDefaultBufferSize(W, H);
            previewSurface = new Surface(st);
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.addTarget(reader.getSurface());
            if (hasContinuousAf) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (fpsRange != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

            device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        if (closed || device == null) { try { s.close(); } catch (Throwable ignored) {} return; }
                        session = s;
                        try {
                            session.setRepeatingRequest(builder.build(), null, bg);
                            if (!closed) cb.onCameraReady(CameraRig.this, W, H, sensorOrientation);
                        } catch (Throwable e) {
                            fail("Preview failed");
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        fail("Camera session failed");
                    }
                }, bg);
        } catch (Throwable e) {
            fail("Camera session failed");
        }
    }

    private boolean live() { return !closed && session != null && builder != null; }

    /** Continuous light for the whole sweep (a single flash pop can't light ~40 frames). */
    public void setTorch(final boolean on) {
        if (bg == null || !hasFlash) return;
        bg.post(new Runnable() { @Override public void run() {
            if (!live()) return;
            try {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, on ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                session.setRepeatingRequest(builder.build(), null, bg);
            } catch (Throwable ignored) {}
        }});
    }

    /** Freeze focus, exposure and white balance so the 4 frames match. */
    public void lock() {
        if (bg == null) return;
        bg.post(new Runnable() { @Override public void run() {
            if (!live()) return;
            try {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                if (hasContinuousAf) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    session.capture(builder.build(), null, bg);
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
                session.setRepeatingRequest(builder.build(), null, bg);
            } catch (Throwable ignored) {}
        }});
    }

    public void unlock() {
        if (bg == null) return;
        bg.post(new Runnable() { @Override public void run() {
            if (!live()) return;
            try {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
                if (hasContinuousAf) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                    session.capture(builder.build(), null, bg);
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
                session.setRepeatingRequest(builder.build(), null, bg);
            } catch (Throwable ignored) {}
        }});
    }
}
