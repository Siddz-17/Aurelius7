package com.aurelius7.wiggle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Film "development". Every stock is a small parametric Look: colour matrix (saturation / contrast / channel
 * gains), faded blacks, split-toning, vignette, halation glow, grain (amount + size) and an optional light leak.
 * Indices 0..4 are kept stable (saved filenames and prefs refer to them); new looks are appended.
 */
public final class Develop {
    private Develop() {}

    private static final class Look {
        final String name;
        float sat = 1f, con = 1f, bright = 0f, sepia = 0f;
        float r = 1f, g = 1f, b = 1f;
        float fade = 0f;                 // lifted blacks / softened whites, 0..0.3
        int sh = 0, hi = 0;              // split-tone offsets, packed r,g,b each -40..40 (see tone())
        float vig = 0f, halo = 0f;       // vignette strength, halation strength
        int grain = 0, grainSize = 1;
        boolean leak = false;
        Look(String n) { name = n; }
        Look sat(float v) { sat = v; return this; }
        Look con(float c, float br) { con = c; bright = br; return this; }
        Look gain(float rr, float gg, float bb) { r = rr; g = gg; b = bb; return this; }
        Look sepia(float v) { sepia = v; return this; }
        Look fade(float v) { fade = v; return this; }
        Look tone(int sr, int sg, int sb, int hr, int hg, int hb) { sh = pack(sr, sg, sb); hi = pack(hr, hg, hb); return this; }
        Look vig(float v) { vig = v; return this; }
        Look halo(float v) { halo = v; return this; }
        Look grain(int a, int size) { grain = a; grainSize = size; return this; }
        Look leak() { leak = true; return this; }
    }

    private static int pack(int r, int g, int b) { return ((r + 64) << 16) | ((g + 64) << 8) | (b + 64); }
    private static int ur(int p) { return ((p >> 16) & 255) - 64; }
    private static int ug(int p) { return ((p >> 8) & 255) - 64; }
    private static int ub(int p) { return (p & 255) - 64; }

    private static final Look[] LOOKS = {
            new Look("STD").grain(0, 1),
            new Look("PORTRA").sepia(0.18f).sat(1.12f).gain(1.04f, 1f, 0.93f).con(1.04f, 6).fade(0.05f).grain(28, 1),
            new Look("TRI-X").sat(0f).con(1.38f, 4).vig(0.18f).grain(50, 2),
            new Look("NIMSLO").sepia(0.38f).sat(0.85f).con(0.86f, 18).fade(0.04f).vig(0.12f).grain(34, 2).leak(),
            new Look("CINE").sat(1.25f).gain(1.07f, 0.99f, 1.04f).con(1.12f, -4).tone(-6, 2, 8, 8, 2, -6).grain(22, 1),
            // --- v0.6 ---
            new Look("EKTAR").sat(1.38f).con(1.18f, 0).gain(1.03f, 1f, 0.97f).vig(0.12f).grain(14, 1),
            new Look("KODA").sat(1.2f).con(1.15f, 0).gain(1.06f, 0.99f, 0.91f).vig(0.24f).grain(24, 1),
            new Look("FUJI").sat(1.16f).con(1.06f, 0).gain(0.97f, 1.03f, 1.04f).fade(0.04f).tone(-8, 4, 8, 4, 2, -2).grain(22, 1),
            new Look("GOLD").sat(1.2f).con(1.04f, 4).gain(1.08f, 1.02f, 0.86f).fade(0.06f).vig(0.2f).grain(30, 2),
            new Look("800T").sat(1.1f).con(1.1f, 0).gain(0.97f, 1f, 1.06f).tone(-10, 2, 12, 12, 0, -10).vig(0.2f).halo(0.9f).grain(40, 2),
            new Look("TEAL").sat(1.1f).con(1.16f, -2).tone(-16, 4, 14, 18, 4, -14).vig(0.25f).grain(18, 1),
            new Look("NOIR").sat(0f).con(1.5f, -4).fade(0.03f).vig(0.45f).grain(58, 2),
            new Look("ILFORD").sat(0f).con(1.12f, 6).fade(0.1f).vig(0.12f).grain(46, 3),
            new Look("MATTE").sat(0.85f).con(0.96f, 4).fade(0.22f).tone(-4, 3, 3, 3, 2, -2).grain(12, 1),
            new Look("FADED").sat(0.75f).con(0.94f, 8).gain(1.03f, 1f, 0.95f).fade(0.18f).vig(0.15f).grain(26, 2),
            new Look("POLA").sat(0.95f).con(1.02f, 6).gain(1.03f, 1f, 0.94f).fade(0.12f).tone(6, -2, 8, 10, 4, -8).vig(0.3f).halo(0.25f).grain(20, 2),
            new Look("HOUR").sat(1.18f).con(1.1f, 0).gain(1.08f, 1f, 0.9f).tone(10, -4, 12, 16, 6, -14).vig(0.2f).halo(0.35f).grain(18, 1),
            new Look("BLEACH").sat(0.5f).con(1.32f, -6).vig(0.3f).grain(42, 2),
    };

    public static final String[] STOCKS = new String[LOOKS.length];
    /** Default grain (0..100) each stock ships with. */
    public static final int[] STOCK_GRAIN = new int[LOOKS.length];
    static {
        for (int i = 0; i < LOOKS.length; i++) { STOCKS[i] = LOOKS[i].name; STOCK_GRAIN[i] = Math.round(LOOKS[i].grain * 0.4f); }
    }

    private static ColorMatrix contrast(float c, float bright) {
        float t = 128f * (1f - c) + bright;
        return new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0});
    }

    private static ColorMatrix scale(float r, float g, float b, float add) {
        return new ColorMatrix(new float[]{
                r, 0, 0, 0, add,
                0, g, 0, 0, add,
                0, 0, b, 0, add,
                0, 0, 0, 1, 0});
    }

    private static ColorMatrix sepia(float amt) {
        float a = 1 - amt;
        return new ColorMatrix(new float[]{
                a + amt * 0.393f, amt * 0.769f, amt * 0.189f, 0, 0,
                amt * 0.349f, a + amt * 0.686f, amt * 0.168f, 0, 0,
                amt * 0.272f, amt * 0.534f, a + amt * 0.131f, 0, 0,
                0, 0, 0, 1, 0});
    }

    private static Look look(int stock) { return LOOKS[stock < 0 || stock >= LOOKS.length ? 0 : stock]; }

    /** Colour part of a look (saturation, gains, contrast). Hardware-friendly, also used for the live preview. */
    private static ColorMatrix baseMatrix(Look l) {
        ColorMatrix m = new ColorMatrix();
        if (l.sepia > 0) m.postConcat(sepia(l.sepia));
        if (l.sat != 1f) { ColorMatrix s = new ColorMatrix(); s.setSaturation(l.sat); m.postConcat(s); }
        if (l.r != 1f || l.g != 1f || l.b != 1f) m.postConcat(scale(l.r, l.g, l.b, l.sepia > 0 ? 6 : 0));
        if (l.con != 1f || l.bright != 0f) m.postConcat(contrast(l.con, l.bright));
        return m;
    }

    /** Live-preview matrix: the colour part plus a cheap stand-in for the faded blacks. */
    public static ColorMatrix matrix(int stock) {
        Look l = look(stock);
        ColorMatrix m = baseMatrix(l);
        if (l.fade > 0) m.postConcat(scale(1f - l.fade * 0.25f, 1f - l.fade * 0.25f, 1f - l.fade * 0.25f, l.fade * 60f));
        return m;
    }

    public static boolean hasLeak(int stock) { return look(stock).leak; }

    public static Bitmap[] apply(Bitmap[] src, int stock, int grain, boolean dateStamp, android.graphics.Typeface dot) {
        return apply(src, stock, grain, 100, dateStamp, dot);
    }

    /** Returns new developed bitmaps; inputs untouched. strength 0..100 blends the look over the original. */
    public static Bitmap[] apply(Bitmap[] src, int stock, int grain, int strength, boolean dateStamp, android.graphics.Typeface dot) {
        Look l = look(stock);
        strength = Math.max(0, Math.min(100, strength));
        Bitmap[] out = new Bitmap[src.length];
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(baseMatrix(l)));
        String stamp = new SimpleDateFormat("''yy MM dd", Locale.US).format(new Date());
        for (int k = 0; k < src.length; k++) {
            int w = src[k].getWidth(), h = src[k].getHeight();
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawBitmap(src[k], 0, 0, p);
            if (l.halo > 0) drawHalation(c, b, l.halo);
            if (l.leak) drawLeak(c, w, h, k);
            grade(b, l, grain, 0x9E3779B9 * (k + 1));
            if (strength < 100) {
                Bitmap m = src[k].copy(Bitmap.Config.ARGB_8888, true);
                Paint ap = new Paint(Paint.FILTER_BITMAP_FLAG);
                ap.setAlpha(Math.round(strength * 2.55f));
                new Canvas(m).drawBitmap(b, 0, 0, ap);
                b.recycle();
                b = m;
                c = new Canvas(b);
            }
            if (dateStamp) drawStamp(c, w, h, stamp, dot);
            out[k] = b;
        }
        return out;
    }

    /** Red-orange bloom around bright highlights (what CineStill-style film does at light sources). */
    private static void drawHalation(Canvas c, Bitmap b, float amt) {
        int w = b.getWidth(), h = b.getHeight();
        int sw = Math.max(8, w / 10), sh = Math.max(8, h / 10);
        Bitmap small = Bitmap.createScaledBitmap(b, sw, sh, true);
        Bitmap tiny = Bitmap.createScaledBitmap(small, Math.max(4, sw / 3), Math.max(4, sh / 3), true);
        Paint t = new Paint(Paint.FILTER_BITMAP_FLAG);
        float th = 190f;
        t.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                5f, 0, 0, 0, -5f * th,
                0, 1.3f, 0, 0, -1.3f * th,
                0, 0, 0.45f, 0, -0.45f * th,
                0, 0, 0, 1, 0})));
        Bitmap glow = Bitmap.createBitmap(tiny.getWidth(), tiny.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(glow).drawBitmap(tiny, 0, 0, t);
        Paint s = new Paint(Paint.FILTER_BITMAP_FLAG);
        s.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        s.setAlpha(Math.round(Math.min(1f, amt) * 255));
        c.drawBitmap(glow, null, new android.graphics.Rect(0, 0, w, h), s);
        small.recycle(); tiny.recycle(); glow.recycle();
    }

    private static void drawLeak(Canvas c, int w, int h, int k) {
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        float wob = (k % 2 == 0 ? 1f : 0.92f);
        lp.setShader(new RadialGradient(0, 0, Math.max(w, h) * 0.75f * wob,
                new int[]{0x8CFF7828, 0x40FF7828, 0x00FF7828}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, lp);
        lp.setShader(new RadialGradient(w, h, Math.max(w, h) * 0.45f,
                new int[]{0x59FFC850, 0x00FFC850}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, lp);
    }

    /**
     * One integer pass: faded blacks, split-toning, vignette and grain (mid-tone weighted, with block size for
     * coarse grain; a different pattern per view so it moves in the loop).
     */
    private static void grade(Bitmap b, Look l, int grain, int seed) {
        boolean doTone = l.sh != 0 || l.hi != 0, doFade = l.fade > 0, doVig = l.vig > 0, doGrain = grain > 0;
        if (!doTone && !doFade && !doVig && !doGrain) return;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);

        int lo = Math.round(l.fade * 50f), span = 256 - lo - Math.round(l.fade * 14f); // c' = lo + c*span/256
        int[] shW = new int[256], hiW = new int[256];
        for (int i = 0; i < 256; i++) {
            float t = i / 255f;
            shW[i] = Math.round((1f - t) * (1f - t) * 256f);
            hiW[i] = Math.round(t * t * 256f);
        }
        int sr = ur(l.sh), sg = ug(l.sh), sb = ub(l.sh), hr = ur(l.hi), hg = ug(l.hi), hb = ub(l.hi);

        int[] vx = null, vy = null;
        if (doVig) {
            vx = new int[w]; vy = new int[h];
            float cx = (w - 1) / 2f, cy = (h - 1) / 2f;
            for (int x = 0; x < w; x++) { float d = (x - cx) / cx; vx[x] = Math.round(d * d * l.vig * 150f); }
            for (int y = 0; y < h; y++) { float d = (y - cy) / cy; vy[y] = Math.round(d * d * l.vig * 150f); }
        }

        int sh = Math.max(1, l.grainSize), bw = 0, bh = 0;
        int[] nz = null;
        int amp = Math.round(grain / 100f * 40f);
        if (doGrain) {
            bw = (w + sh - 1) / sh; bh = (h + sh - 1) / sh;
            nz = new int[bw * bh];
            int s = seed == 0 ? 1 : seed;
            for (int i = 0; i < nz.length; i++) {
                s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
                nz[i] = ((s & 0xFF) + ((s >>> 8) & 0xFF)) - 255; // triangle distribution
            }
        }

        for (int y = 0; y < h; y++) {
            int row = y * w;
            int nrow = (y / sh) * bw;
            int vyy = doVig ? vy[y] : 0;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                if (doFade) { r = lo + ((r * span) >> 8); g = lo + ((g * span) >> 8); bl = lo + ((bl * span) >> 8); }
                int lum = (r * 77 + g * 150 + bl * 29) >> 8;
                if (doTone) {
                    int ws = shW[lum], wh = hiW[lum];
                    r += (sr * ws + hr * wh) >> 8; g += (sg * ws + hg * wh) >> 8; bl += (sb * ws + hb * wh) >> 8;
                }
                if (doVig) {
                    int v = 256 - (vx[x] + vyy); if (v < 40) v = 40;
                    r = (r * v) >> 8; g = (g * v) >> 8; bl = (bl * v) >> 8;
                }
                if (doGrain) {
                    int mid = 255 - Math.abs(lum - 128) * 3 / 2;
                    int d = (nz[nrow + x / sh] * amp * mid) >> 16;
                    r += d; g += d; bl += d;
                }
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                bl = bl < 0 ? 0 : (bl > 255 ? 255 : bl);
                px[row + x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void drawStamp(Canvas c, int w, int h, String text, android.graphics.Typeface dot) {
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (dot != null) tp.setTypeface(dot);
        tp.setTextSize(Math.max(14f, w * 0.045f));
        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setColor(0xFFE8873A);
        tp.setShadowLayer(tp.getTextSize() * 0.45f, 0, 0, 0xCCE8873A);
        float pad = w * 0.04f;
        c.drawText(text, w - pad, h - pad, tp);
        tp.setShadowLayer(0, 0, 0, 0);
        tp.setColor(0xFFFFB27A);
        c.drawText(text, w - pad, h - pad, tp);
    }

    // ------------------------------------------------------------------ exports

    /** Wraps each view in a 35mm-style strip: sprocket bands, side margins, orange edge print. */
    public static Bitmap[] border(Bitmap[] src, String edgeLeft, String edgeRight, android.graphics.Typeface dot) {
        Bitmap[] out = new Bitmap[src.length];
        int w = src[0].getWidth(), h = src[0].getHeight();
        int band = Math.round(w * 0.075f), side = Math.round(w * 0.035f);
        int W = w + 2 * side, H = h + 2 * band;
        W += W & 1; H += H & 1;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        for (int k = 0; k < src.length; k++) {
            Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF050505);
            p.setColor(0xFF2B2924);
            float hw = band * 0.42f, hh = band * 0.32f;
            int n = Math.max(6, Math.round(W / (band * 0.9f)));
            float step = (W - band * 0.4f - hw) / (n - 1);
            for (int i = 0; i < n; i++) {
                float x = band * 0.2f + i * step;
                c.drawRoundRect(new android.graphics.RectF(x, band * 0.18f, x + hw, band * 0.18f + hh), hh * 0.25f, hh * 0.25f, p);
                c.drawRoundRect(new android.graphics.RectF(x, H - band * 0.18f - hh, x + hw, H - band * 0.18f), hh * 0.25f, hh * 0.25f, p);
            }
            c.drawBitmap(src[k], side, band, p);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (dot != null) tp.setTypeface(dot);
            tp.setColor(0xFFE8873A);
            tp.setTextSize(band * 0.3f);
            float ty = band * 0.62f + tp.getTextSize() * 0.35f;
            tp.setTextAlign(Paint.Align.LEFT);
            c.drawText(edgeLeft, side, band * 0.5f + band * 0.42f, tp);
            tp.setTextAlign(Paint.Align.RIGHT);
            c.drawText(edgeRight, W - side, band * 0.5f + band * 0.42f, tp);
            out[k] = b;
        }
        return out;
    }

    /**
     * Lenticular print sheet: the 4 views interlaced column by column (view order reversed, as lenses flip
     * the image). Printed at 240 dpi under a 60 LPI lenticular sheet, each lens covers exactly 4 pixels.
     */
    public static Bitmap lenticular(Bitmap[] views, int outW, android.graphics.Typeface dot) {
        int w = views[0].getWidth(), h = views[0].getHeight();
        outW -= outW % 4;
        int outH = Math.round(h * (outW / (float) w));
        int margin = Math.round(outW * 0.06f), footer = Math.round(outW * 0.07f);
        Bitmap sheet = Bitmap.createBitmap(outW + 2 * margin, outH + 2 * margin + footer, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        c.drawColor(0xFFFFFFFF);
        int[][] px = new int[views.length][];
        Bitmap[] sc = new Bitmap[views.length];
        for (int k = 0; k < views.length; k++) {
            sc[k] = Bitmap.createScaledBitmap(views[k], outW, outH, true);
            px[k] = new int[outW * outH];
            sc[k].getPixels(px[k], 0, outW, 0, 0, outW, outH);
        }
        int[] inter = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            int row = y * outW;
            for (int x = 0; x < outW; x++) inter[row + x] = px[3 - (x & 3)][row + x];
        }
        Bitmap ib = Bitmap.createBitmap(inter, outW, outH, Bitmap.Config.ARGB_8888);
        c.drawBitmap(ib, margin, margin, null);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (dot != null) tp.setTypeface(dot);
        tp.setColor(0xFF14110A);
        tp.setTextSize(footer * 0.32f);
        c.drawText("AURELIUS7 \u00B7 LENTICULAR \u00B7 PRINT AT 240 DPI \u00B7 60 LPI LENS \u00B7 LENSES VERTICAL", margin, outH + margin + footer * 0.75f, tp);
        for (Bitmap b : sc) b.recycle();
        ib.recycle();
        return sheet;
    }
}
