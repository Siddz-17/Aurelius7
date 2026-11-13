package com.aurelius7.wiggle;

import android.graphics.Bitmap;

/**
 * Holds the 4 upright views and re-aligns them on any focus point (coarse-to-fine SAD on luma),
 * then crops them to a common window in the chosen aspect ratio.
 */
public class Composer {
    public final Bitmap[] src;
    public final int W, H;
    private final int HW, HH, QW, QH;
    private final byte[][] half = new byte[4][], quarter = new byte[4][];
    private final float[][] seeds;
    public final int[] ox = new int[4], oy = new int[4];
    public float fu = 0.5f, fv = 0.5f;          // focus, normalized in view-0 coords
    public float aspect = 9f / 16f;             // output width / height
    public int cropX, cropY, cropW, cropH;      // in view-0 coords
    public float parallaxPx;
    public int sweepFrames;

    public Composer(Bitmap[] frames, float[][] seeds) {
        src = frames;
        W = frames[0].getWidth(); H = frames[0].getHeight();
        HW = W / 2; HH = H / 2; QW = HW / 2; QH = HH / 2;
        this.seeds = new float[4][2];
        int[] px = new int[W * H];
        for (int k = 0; k < 4; k++) {
            this.seeds[k][0] = seeds[k][0]; this.seeds[k][1] = seeds[k][1];
            frames[k].getPixels(px, 0, W, 0, 0, W, H);
            byte[] hb = new byte[HW * HH];
            for (int y = 0; y < HH; y++) {
                for (int x = 0; x < HW; x++) {
                    int s = 0;
                    for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
                        int c = px[(y * 2 + j) * W + x * 2 + i];
                        s += (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
                    }
                    hb[y * HW + x] = (byte) (s >> 2);
                }
            }
            byte[] qb = new byte[QW * QH];
            for (int y = 0; y < QH; y++) for (int x = 0; x < QW; x++) {
                int s = (hb[(2 * y) * HW + 2 * x] & 255) + (hb[(2 * y) * HW + 2 * x + 1] & 255)
                      + (hb[(2 * y + 1) * HW + 2 * x] & 255) + (hb[(2 * y + 1) * HW + 2 * x + 1] & 255);
                qb[y * QW + x] = (byte) (s >> 2);
            }
            half[k] = hb; quarter[k] = qb;
        }
    }

    private static long sad(byte[] a, int aw, int ax, int ay, byte[] b, int bx, int by, int size, long cap) {
        long s = 0;
        for (int j = 0; j < size; j++) {
            int ra = (ay + j) * aw + ax, rb = (by + j) * aw + bx;
            for (int i = 0; i < size; i++) {
                int d = (a[ra + i] & 255) - (b[rb + i] & 255);
                s += d < 0 ? -d : d;
            }
            if (s >= cap) return s;
        }
        return s;
    }

    /** Re-align all views so the point (u,v) of view 0 stays still. */
    public void align(float u, float v) {
        fu = Math.max(0.02f, Math.min(0.98f, u));
        fv = Math.max(0.02f, Math.min(0.98f, v));
        ox[0] = 0; oy[0] = 0;
        final int QP = 21, QR = 12;           // quarter-res patch + search radius
        final int HP = 41, HR = 2;            // half-res patch + refine radius
        for (int k = 1; k < 4; k++) {
            int sx = Math.round(seeds[k][0] / 4f), sy = Math.round(seeds[k][1] / 4f);
            int ax = clamp(Math.round(fu * QW) - QP / 2, 0, QW - QP);
            int ay = clamp(Math.round(fv * QH) - QP / 2, 0, QH - QP);
            long best = Long.MAX_VALUE; int bx = sx, by = sy;
            for (int dy = -QR; dy <= QR; dy++) for (int dx = -QR; dx <= QR; dx++) {
                int tx = ax + sx + dx, ty = ay + sy + dy;
                if (tx < 0 || ty < 0 || tx + QP > QW || ty + QP > QH) continue;
                long c = sad(quarter[0], QW, ax, ay, quarter[k], tx, ty, QP, best);
                // tiny bias toward the seed so flat areas don't wander
                c += (long) (Math.abs(dx) + Math.abs(dy)) * 2;
                if (c < best) { best = c; bx = sx + dx; by = sy + dy; }
            }
            // refine at half res
            int hx = clamp(Math.round(fu * HW) - HP / 2, 0, HW - HP);
            int hy = clamp(Math.round(fv * HH) - HP / 2, 0, HH - HP);
            long hb = Long.MAX_VALUE; int rx = bx * 2, ry = by * 2;
            for (int dy = -HR; dy <= HR; dy++) for (int dx = -HR; dx <= HR; dx++) {
                int cx = bx * 2 + dx, cy = by * 2 + dy;
                int tx = hx + cx, ty = hy + cy;
                if (tx < 0 || ty < 0 || tx + HP > HW || ty + HP > HH) continue;
                long c = sad(half[0], HW, hx, hy, half[k], tx, ty, HP, hb);
                if (c < hb) { hb = c; rx = cx; ry = cy; }
            }
            // Offsets beyond a quarter of the frame can't be real parallax; clamping also guarantees
            // the four views always share at least half the frame, so render() can't go out of bounds.
            ox[k] = clamp(rx * 2, -W / 4, W / 4); oy[k] = clamp(ry * 2, -H / 4, H / 4);
            // next refocus searches around the current alignment (shake is shared by all depths)
            seeds[k][0] = ox[k]; seeds[k][1] = oy[k];
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Crop all views to the shared window. Returns new bitmaps (callers may drop old ones). */
    public Bitmap[] render() {
        int L = 0, R = W, T = 0, B = H;
        for (int k = 0; k < 4; k++) {
            L = Math.max(L, -ox[k]); R = Math.min(R, W - ox[k]);
            T = Math.max(T, -oy[k]); B = Math.min(B, H - oy[k]);
        }
        if (R - L < 16 || B - T < 16) {            // defensive: should be impossible after the clamp
            java.util.Arrays.fill(ox, 0); java.util.Arrays.fill(oy, 0);
            L = 0; R = W; T = 0; B = H;
        }
        int iw = Math.max(2, R - L), ih = Math.max(2, B - T);
        int cw, ch;
        if (iw / (float) ih > aspect) { ch = ih; cw = Math.round(ih * aspect); }
        else { cw = iw; ch = Math.round(iw / aspect); }
        cw = Math.max(2, Math.min(cw, iw)) & ~1;
        ch = Math.max(2, Math.min(ch, ih)) & ~1;
        int x0 = clamp(Math.round(fu * W) - cw / 2, L, R - cw);
        int y0 = clamp(Math.round(fv * H) - ch / 2, T, B - ch);
        cropX = x0; cropY = y0; cropW = cw; cropH = ch;
        Bitmap[] out = new Bitmap[4];
        for (int k = 0; k < 4; k++) out[k] = Bitmap.createBitmap(src[k], x0 + ox[k], y0 + oy[k], cw, ch);
        return out;
    }
}
