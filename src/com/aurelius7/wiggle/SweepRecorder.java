package com.aurelius7.wiggle;

import android.media.Image;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Records frames after the single tap while the user slides the phone.
 * Tracks the subject patch and the background separately; their difference is the parallax,
 * which is exactly what makes a wigglegram look 3D (pure rotation gives ~0 parallax).
 */
public class SweepRecorder implements CameraRig.FrameSink {

    public interface Listener {
        void onSweepProgress(float progress, int coach, float parallaxPx);
        void onSweepDone(SweepRecorder r);
    }

    public static final int COACH_NONE = 0, COACH_TURNING = 1, COACH_FAST = 2, COACH_EDGE = 3;

    public static class Frame {
        byte[] y, u, v, thumb, thumb8;
        float px, py;        // parallax so far (full-res px)
        float sfx, sfy;      // subject position (full-res px)
        long t;
    }

    public final int W, H, TW, TH;
    public final float target;
    public final ArrayList<Frame> frames = new ArrayList<Frame>();
    private final Listener listener;
    private int maxFrames;
    private final long maxMs;
    private static final long MIN_MS = 350;
    private final boolean orbit;
    private float vgX, vgY, vsX, vsY; // last per-frame motion (quarter-res px), used as prediction
    public int lostFrames;

    private float subjX, subjY, subj0X, subj0Y; // thumb coords
    private float gX, gY;                        // cumulative background shift, thumb px
    private long t0;
    private volatile boolean done;
    private byte[] scratch = new byte[0];
    public boolean lowDepth;
    private volatile boolean finishRequested;
    public void finishNow() { finishRequested = true; }

    public SweepRecorder(int w, int h, float focusX, float focusY, float targetFactor, boolean orbit, Listener l) {
        this.orbit = orbit;
        maxFrames = orbit ? 84 : 70;
        maxMs = orbit ? 3400 : 2600;
        W = w; H = h; TW = w / 4; TH = h / 4;
        listener = l;
        target = targetFactor * Math.max(w, h); // 0.02 -> ~26px at 1280: a pleasant wiggle without tearing
        subjX = subj0X = clamp(focusX / 4f, 20, TW - 21);
        subjY = subj0Y = clamp(focusY / 4f, 20, TH - 21);
        // Memory budget: each stored frame is W*H*1.5 bytes (+thumbs). Never plan to use more than
        // half of what the heap still has free, so a long sweep can't run the app out of memory.
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        long perFrame = (long) (w * (long) h * 1.6f) + 4096;
        long cap = (free / 2) / perFrame;
        if (cap < maxFrames) maxFrames = (int) Math.max(8, cap);
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public boolean isDone() { return done; }

    @Override public synchronized void onFrame(Image img) {
        if (done) return;
        try {
            process(img);
        } catch (OutOfMemoryError oom) {
            // Free a little headroom and finish with what we have instead of crashing.
            for (int i = 0; i < 3 && frames.size() > 4; i++) frames.remove(frames.size() - 1);
            done = true;
            if (frames.size() >= 4) listener.onSweepDone(this);
        }
    }

    private void process(Image img) {
        if (img.getWidth() != W || img.getHeight() != H) return;
        Frame f = new Frame();
        f.y = new byte[W * H];
        f.u = new byte[(W / 2) * (H / 2)];
        f.v = new byte[(W / 2) * (H / 2)];
        Image.Plane[] p = img.getPlanes();
        copyPlane(p[0], W, H, f.y);
        copyPlane(p[1], W / 2, H / 2, f.u);
        copyPlane(p[2], W / 2, H / 2, f.v);
        f.thumb = Track.down4(f.y, W, H);
        f.thumb8 = Track.down2(f.thumb, TW, TH);
        long now = SystemClock.uptimeMillis();
        f.t = now;

        int coach = COACH_NONE;
        if (frames.isEmpty()) {
            t0 = now;
        } else {
            Frame prev = frames.get(frames.size() - 1);
            int sx = Math.round(subjX), sy = Math.round(subjY);
            // background: whole frame minus the subject box, predicted from last motion
            float[] g = Track.track(prev.thumb, f.thumb, prev.thumb8, f.thumb8, TW, TH, vgX, vgY,
                    8, 8, TW - 8, TH - 8, 2, sx - 24, sy - 24, sx + 24, sy + 24);
            // subject patch
            float[] s = Track.track(prev.thumb, f.thumb, prev.thumb8, f.thumb8, TW, TH, vsX, vsY,
                    sx - 14, sy - 14, sx + 14, sy + 14, 1, 0, 0, 0, 0);
            boolean gOk = g[2] < 16f, sOk = s[2] < 16f;
            if (!gOk) { g[0] = vgX; g[1] = vgY; }
            if (!sOk) { s[0] = vsX; s[1] = vsY; lostFrames++; }
            vgX = g[0]; vgY = g[1]; vsX = s[0]; vsY = s[1];
            subjX += s[0]; subjY += s[1];
            gX += g[0]; gY += g[1];
            float stepPx = (float) Math.hypot(g[0], g[1]) * 4f;
            if (stepPx > (orbit ? 90 : 40)) coach = COACH_FAST;
        }
        f.px = ((subjX - subj0X) - gX) * 4f;
        f.py = ((subjY - subj0Y) - gY) * 4f;
        f.sfx = subjX * 4f + 2f;
        f.sfy = subjY * 4f + 2f;
        frames.add(f);

        long el = now - t0;
        float par = (float) Math.hypot(f.px, f.py);
        float glob = (float) Math.hypot(gX, gY) * 4f;
        if (coach == COACH_NONE && el > (orbit ? 900 : 500) && glob > 80 && par < (orbit ? 0.08f : 0.25f) * glob) coach = COACH_TURNING;
        boolean edge = subjX < 16 || subjX > TW - 17 || subjY < 16 || subjY > TH - 17;
        if (edge) coach = COACH_EDGE;

        float prog = Math.min(1f, par / target);
        listener.onSweepProgress(Math.max(prog, Math.min(0.15f, el / (float) maxMs)), coach, par);

        if ((finishRequested && frames.size() >= 4) || (par >= target && el > MIN_MS) || el > maxMs || frames.size() >= maxFrames || (edge && frames.size() >= 4)) {
            done = true;
            lowDepth = par < target * 0.35f;
            listener.onSweepDone(this);
        }
    }

    private void copyPlane(Image.Plane pl, int w, int h, byte[] out) {
        ByteBuffer buf = pl.getBuffer();
        int rs = pl.getRowStride(), ps = pl.getPixelStride();
        buf.position(0);
        int rem = buf.remaining();
        if (ps == 1 && rs == w && rem >= w * h) { buf.get(out, 0, w * h); return; }
        if (scratch.length < rem) scratch = new byte[rem];
        buf.get(scratch, 0, rem);
        for (int row = 0; row < h; row++) {
            int base = row * rs, o = row * w;
            for (int col = 0; col < w; col++) {
                int idx = base + col * ps;
                out[o + col] = idx < rem ? scratch[idx] : (byte) 128;
            }
        }
    }

    /** Stops recording and drops the frames. Safe to call from any thread. */
    public synchronized void release() { done = true; frames.clear(); }
}
