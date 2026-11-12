package com.aurelius7.wiggle;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import java.util.ArrayList;

/**
 * Picks 4 evenly spaced views from a sweep and converts them to upright, uncropped bitmaps.
 * Alignment + cropping is done later by {@link Composer} so the user can refocus after the shot.
 */
public final class WiggleBuilder {
    private WiggleBuilder() {}

    public static class Result {
        public Bitmap[] frames;      // upright, full frame
        public float[][] seeds;      // predicted subject offset of each view vs view 0, upright px
        public float parallaxPx;
        public int sweepFrames;
        public String error;
    }

    public static Result build(SweepRecorder rec, int sensorOrientation, int outWidth) {
        Result res = new Result();
        ArrayList<SweepRecorder.Frame> fr = rec.frames;
        int n = fr.size();
        res.sweepFrames = n;
        if (n < 4) { res.error = "TOO FEW FRAMES. HOLD STEADY + SLIDE."; return res; }
        int W = rec.W, H = rec.H;

        // 1. Position of each frame along the sweep (projected parallax), fall back to time.
        SweepRecorder.Frame last = fr.get(n - 1);
        float L = (float) Math.hypot(last.px, last.py);
        float[] s = new float[n];
        if (L >= 3f) {
            float dx = last.px / L, dy = last.py / L;
            for (int i = 0; i < n; i++) s[i] = fr.get(i).px * dx + fr.get(i).py * dy;
        } else {
            for (int i = 0; i < n; i++) s[i] = i;
        }
        float smax = s[0];
        int imax = 0;
        for (int i = 1; i < n; i++) if (s[i] > smax) { smax = s[i]; imax = i; }
        int end = Math.max(3, imax);

        // 2. Pick 4 frames at 0, 1/3, 2/3, 1 of the way; prefer the sharpest neighbour.
        int[] pick = new int[4];
        int prevIdx = -1;
        float tol = Math.max(1.5f, smax * 0.06f);
        for (int k = 0; k < 4; k++) {
            float tgt = smax * k / 3f;
            int lo = prevIdx + 1, hi = end - (3 - k);
            if (hi < lo) hi = lo;
            int best = lo;
            for (int i = lo; i <= hi && i < n; i++) if (Math.abs(s[i] - tgt) < Math.abs(s[best] - tgt)) best = i;
            float bestErr = Math.abs(s[best] - tgt);
            int sharpest = best;
            float sharpScore = -1;
            for (int j = best - 1; j <= best + 1; j++) {
                if (j < lo || j > hi || j >= n) continue;
                if (Math.abs(s[j] - tgt) > bestErr + tol) continue;
                SweepRecorder.Frame f = fr.get(j);
                float sc = Track.sharpness(f.y, W, H, Math.round(f.sfx), Math.round(f.sfy), 80);
                if (sc > sharpScore) { sharpScore = sc; sharpest = j; }
            }
            pick[k] = sharpest;
            prevIdx = sharpest;
        }

        // 3. Convert the 4 views to upright bitmaps.
        boolean swap = sensorOrientation == 90 || sensorOrientation == 270;
        int rw = swap ? H : W;
        float scale = Math.min(1f, outWidth / (float) rw);
        res.frames = new Bitmap[4];
        res.seeds = new float[4][2];
        int[] px = new int[W * H];
        int cw2 = W / 2;
        SweepRecorder.Frame ref = fr.get(pick[0]);
        for (int k = 0; k < 4; k++) {
            SweepRecorder.Frame f = fr.get(pick[k]);
            for (int yy = 0; yy < H; yy++) {
                int rowY = yy * W, rowC = (yy >> 1) * cw2;
                for (int xx = 0; xx < W; xx++) {
                    int Y = f.y[rowY + xx] & 255;
                    int U = (f.u[rowC + (xx >> 1)] & 255) - 128;
                    int V = (f.v[rowC + (xx >> 1)] & 255) - 128;
                    int r = Y + ((91881 * V) >> 16);
                    int g = Y - ((22554 * U + 46802 * V) >> 16);
                    int b = Y + ((116130 * U) >> 16);
                    r = r < 0 ? 0 : (r > 255 ? 255 : r);
                    g = g < 0 ? 0 : (g > 255 ? 255 : g);
                    b = b < 0 ? 0 : (b > 255 ? 255 : b);
                    px[rowY + xx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            Bitmap raw = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
            Matrix m = new Matrix();
            m.postRotate(sensorOrientation);
            m.postScale(scale, scale);
            Bitmap up = Bitmap.createBitmap(raw, 0, 0, W, H, m, true);
            if (up != raw) raw.recycle();
            res.frames[k] = up;

            // subject offset vs view 0, rotated into upright coords
            float dx = f.sfx - ref.sfx, dy = f.sfy - ref.sfy, ux, uy;
            switch (sensorOrientation) {
                case 90:  ux = -dy; uy = dx; break;
                case 270: ux = dy;  uy = -dx; break;
                case 180: ux = -dx; uy = -dy; break;
                default:  ux = dx;  uy = dy;
            }
            res.seeds[k][0] = ux * scale;
            res.seeds[k][1] = uy * scale;
        }
        SweepRecorder.Frame f3 = fr.get(pick[3]);
        res.parallaxPx = (float) Math.hypot(f3.px - ref.px, f3.py - ref.py);
        return res;
    }
}
