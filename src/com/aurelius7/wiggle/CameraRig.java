package com.aurelius7.wiggle;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
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

/** Thin Camera2 wrapper: back camera, preview on a TextureView + a YUV stream for analysis. */
public class CameraRig {

    public interface FrameSink { void onFrame(Image img); }
    public interface Callback { void onCameraReady(int w, int h, int sensorOrientation); void onCameraError(String msg); }

    private final Activity act;
    private final TextureView tv;
    private final Callback cb;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder builder;
    private Surface previewSurface;

    public int W, H, sensorOrientation;
    private boolean hasContinuousAf;
    private Range<Integer> fpsRange;
    public volatile FrameSink sink;

    public CameraRig(Activity act, TextureView tv, Callback cb) {
        this.act = act; this.tv = tv; this.cb = cb;
    }

    public void start() {
        thread = new HandlerThread("aurelius-cam");
        thread.start();
        bg = new Handler(thread.getLooper());
        try {
            open();
        } catch (Exception e) {
            cb.onCameraError("CAMERA OPEN FAILED: " + e.getMessage());
        }
    }

    public void stop() {
        sink = null;
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (device != null) device.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null; device = null; reader = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    private void open() throws CameraAccessException {
        CameraManager mgr = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
        String chosen = null;
        CameraCharacteristics chars = null;
        for (String id : mgr.getCameraIdList()) {
            CameraCharacteristics c = mgr.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { chosen = id; chars = c; break; }
        }
        if (chosen == null) { cb.onCameraError("NO BACK CAMERA"); return; }

        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = so == null ? 90 : so;

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = pickSize(map);
        W = size.getWidth(); H = size.getHeight();

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
                    if (img == null) return;
                    FrameSink s = sink;
                    if (s != null) s.onFrame(img);
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }
        }, bg);

        mgr.openCamera(chosen, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) { device = d; createSession(); }
            @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; }
            @Override public void onError(CameraDevice d, int error) {
                d.close(); device = null; cb.onCameraError("CAMERA ERROR " + error);
            }
        }, bg);
    }

    private Size pickSize(StreamConfigurationMap map) {
        Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
        Size[] tex = map.getOutputSizes(SurfaceTexture.class);
        Size best = null;
        // Want 16:9, width <= 1280, also usable for the preview surface.
        for (Size s : yuv) {
            if (s.getWidth() > 1280) continue;
            if (!contains(tex, s)) continue;
            float ar = s.getWidth() / (float) s.getHeight();
            if (Math.abs(ar - 16f / 9f) > 0.02f) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        if (best == null) {
            for (Size s : yuv) {
                if (s.getWidth() > 1280 || !contains(tex, s)) continue;
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
            if (st == null) { cb.onCameraError("NO PREVIEW SURFACE"); return; }
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
                        session = s;
                        try {
                            session.setRepeatingRequest(builder.build(), null, bg);
                            cb.onCameraReady(W, H, sensorOrientation);
                        } catch (Exception e) {
                            cb.onCameraError("PREVIEW FAILED: " + e.getMessage());
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        cb.onCameraError("SESSION CONFIG FAILED");
                    }
                }, bg);
        } catch (Exception e) {
            cb.onCameraError("SESSION FAILED: " + e.getMessage());
        }
    }

    /** Freeze focus, exposure and white balance so the 4 frames match. */
    public void lock() {
        if (bg == null) return;
        bg.post(new Runnable() { @Override public void run() {
            if (session == null || builder == null) return;
            try {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                if (hasContinuousAf) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    session.capture(builder.build(), null, bg);
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
                session.setRepeatingRequest(builder.build(), null, bg);
            } catch (Exception ignored) {}
        }});
    }

    public void unlock() {
        if (bg == null) return;
        bg.post(new Runnable() { @Override public void run() {
            if (session == null || builder == null) return;
            try {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
                if (hasContinuousAf) {
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                    session.capture(builder.build(), null, bg);
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
                session.setRepeatingRequest(builder.build(), null, bg);
            } catch (Exception ignored) {}
        }});
    }
}
