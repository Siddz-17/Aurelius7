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
        byte[] y, u, v, thumb;
        float px, py;        // parallax so far (full-res px)
        float sfx, sfy;      // subject position (full-res px)
        long t;
    }

    public final int W, H, TW, TH;
    public final float target;
    public final ArrayList<Frame> frames = new ArrayList<Frame>();
    private final Listener listener;
    private static final int MAX_FRAMES = 70;
    private static final long MAX_MS = 2600, MIN_MS = 350;

    private float subjX, subjY, subj0X, subj0Y; // thumb coords
    private float gX, gY;                        // cumulative background shift, thumb px
    private long t0;
    private volatile boolean done;
    private byte[] scratch = new byte[0];
    public boolean lowDepth;

    public SweepRecorder(int w, int h, float focusX, float focusY, float targetFactor, Listener l) {
        W = w; H = h; TW = w / 4; TH = h / 4;
        listener = l;
        target = targetFactor * Math.max(w, h); // 0.02 -> ~26px at 1280: a pleasant wiggle without tearing
        subjX = subj0X = clamp(focusX / 4f, 20, TW - 21);
        subjY = subj0Y = clamp(focusY / 4f, 20, TH - 21);
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public boolean isDone() { return done; }

    @Override public void onFrame(Image img) {
        if (done) return;
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
        long now = SystemClock.uptimeMillis();
        f.t = now;

        int coach = COACH_NONE;
        if (frames.isEmpty()) {
            t0 = now;
        } else {
            Frame prev = frames.get(frames.size() - 1);
            int sx = Math.round(subjX), sy = Math.round(subjY);
            float[] s = Track.shift(prev.thumb, f.thumb, TW, TH, sx - 12, sy - 12, sx + 12, sy + 12, 1, 6, 0, 0, 0, 0);
            float[] g = Track.shift(prev.thumb, f.thumb, TW, TH, 8, 8, TW - 8, TH - 8, 2, 6,
                    sx - 24, sy - 24, sx + 24, sy + 24);
            subjX += s[0]; subjY += s[1];
            gX += g[0]; gY += g[1];
            float stepPx = (float) Math.hypot(g[0], g[1]) * 4f;
            if (stepPx > 28) coach = COACH_FAST;
        }
        f.px = ((subjX - subj0X) - gX) * 4f;
        f.py = ((subjY - subj0Y) - gY) * 4f;
        f.sfx = subjX * 4f + 2f;
        f.sfy = subjY * 4f + 2f;
        frames.add(f);

        long el = now - t0;
        float par = (float) Math.hypot(f.px, f.py);
        float glob = (float) Math.hypot(gX, gY) * 4f;
        if (coach == COACH_NONE && el > 500 && glob > 80 && par < 0.25f * glob) coach = COACH_TURNING;
        boolean edge = subjX < 16 || subjX > TW - 17 || subjY < 16 || subjY > TH - 17;
        if (edge) coach = COACH_EDGE;

        float prog = Math.min(1f, par / target);
        listener.onSweepProgress(Math.max(prog, Math.min(0.15f, el / (float) MAX_MS)), coach, par);

        if ((par >= target && el > MIN_MS) || el > MAX_MS || frames.size() >= MAX_FRAMES || (edge && frames.size() >= 4)) {
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

    public void release() { frames.clear(); }
}
