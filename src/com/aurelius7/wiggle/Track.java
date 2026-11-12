package com.aurelius7.wiggle;

/** Small, dependency-free image tracking helpers (SAD block matching on 8-bit luma). */
public final class Track {
    private Track() {}

    /**
     * Finds (dx,dy) so that b(x+dx, y+dy) ~= a(x,y) over region [x0,x1)x[y0,y1), sampled every `step` px,
     * searching |dx|,|dy| <= R. Pixels inside the exclusion box [ex0,ex1)x[ey0,ey1) are ignored.
     * Returns {dx, dy, bestCostPerSample}. Sub-pixel refined with a parabola fit.
     */
    public static float[] shift(byte[] a, byte[] b, int w, int h,
                                int x0, int y0, int x1, int y1, int step, int R,
                                int ex0, int ey0, int ex1, int ey1) {
        x0 = Math.max(x0, R); y0 = Math.max(y0, R);
        x1 = Math.min(x1, w - R); y1 = Math.min(y1, h - R);
        if (x1 - x0 < 4 || y1 - y0 < 4) return new float[]{0, 0, Float.MAX_VALUE};
        int n = 2 * R + 1;
        long[] cost = new long[n * n];
        int samples = 0;
        boolean countDone = false;
        for (int dy = -R; dy <= R; dy++) {
            for (int dx = -R; dx <= R; dx++) {
                long s = 0;
                int cnt = 0;
                for (int y = y0; y < y1; y += step) {
                    int ra = y * w;
                    int rb = (y + dy) * w + dx;
                    boolean rowEx = y >= ey0 && y < ey1;
                    for (int x = x0; x < x1; x += step) {
                        if (rowEx && x >= ex0 && x < ex1) continue;
                        int d = (a[ra + x] & 255) - (b[rb + x] & 255);
                        s += d < 0 ? -d : d;
                        cnt++;
                    }
                }
                if (!countDone) { samples = cnt; countDone = true; }
                cost[(dy + R) * n + (dx + R)] = s;
            }
        }
        int bi = 0;
        for (int i = 1; i < cost.length; i++) if (cost[i] < cost[bi]) bi = i;
        int bx = bi % n, by = bi / n;
        float fx = bx - R, fy = by - R;
        if (bx > 0 && bx < n - 1) {
            float l = cost[by * n + bx - 1], c = cost[bi], r = cost[by * n + bx + 1];
            float den = l - 2 * c + r;
            if (den > 0) fx += 0.5f * (l - r) / den;
        }
        if (by > 0 && by < n - 1) {
            float u = cost[(by - 1) * n + bx], c = cost[bi], d = cost[(by + 1) * n + bx];
            float den = u - 2 * c + d;
            if (den > 0) fy += 0.5f * (u - d) / den;
        }
        float per = samples > 0 ? cost[bi] / (float) samples : Float.MAX_VALUE;
        return new float[]{fx, fy, per};
    }

    /** 4x4 box downsample of a luma plane. */
    public static byte[] down4(byte[] y, int w, int h) {
        int tw = w / 4, th = h / 4;
        byte[] out = new byte[tw * th];
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                int sum = 0;
                int base = (ty * 4) * w + tx * 4;
                for (int k = 0; k < 4; k++) {
                    int r = base + k * w;
                    sum += (y[r] & 255) + (y[r + 1] & 255) + (y[r + 2] & 255) + (y[r + 3] & 255);
                }
                out[ty * tw + tx] = (byte) (sum >> 4);
            }
        }
        return out;
    }

    /** Cheap sharpness score: mean absolute horizontal+vertical gradient in a box. */
    public static float sharpness(byte[] y, int w, int h, int cx, int cy, int half) {
        int x0 = Math.max(1, cx - half), x1 = Math.min(w - 1, cx + half);
        int y0 = Math.max(1, cy - half), y1 = Math.min(h - 1, cy + half);
        long s = 0; int n = 0;
        for (int yy = y0; yy < y1; yy += 2) {
            int r = yy * w;
            for (int xx = x0; xx < x1; xx += 2) {
                int c = y[r + xx] & 255;
                int gx = (y[r + xx + 1] & 255) - c;
                int gy = (y[r + w + xx] & 255) - c;
                s += (gx < 0 ? -gx : gx) + (gy < 0 ? -gy : gy);
                n++;
            }
        }
        return n == 0 ? 0 : s / (float) n;
    }
}
