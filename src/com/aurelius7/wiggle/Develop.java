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

/** Film "development": stock colour matrix, light leak, moving grain, date stamp. */
public final class Develop {
    private Develop() {}

    public static final String[] STOCKS = {"STD", "PORTRA", "TRI-X", "NIMSLO", "CINE"};
    /** Default grain (0..100) each stock ships with. */
    public static final int[] STOCK_GRAIN = {0, 28, 50, 34, 22};

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

    public static ColorMatrix matrix(int stock) {
        ColorMatrix m = new ColorMatrix();
        switch (stock) {
            case 1: { // PORTRA: warm, soft, lifted blacks
                m.postConcat(sepia(0.18f));
                ColorMatrix s = new ColorMatrix(); s.setSaturation(1.12f); m.postConcat(s);
                m.postConcat(scale(1.04f, 1.0f, 0.93f, 6));
                m.postConcat(contrast(1.04f, 6));
                break;
            }
            case 2: { // TRI-X: punchy black & white
                ColorMatrix s = new ColorMatrix(); s.setSaturation(0f); m.postConcat(s);
                m.postConcat(contrast(1.38f, 4));
                break;
            }
            case 3: { // NIMSLO: faded 80s print
                m.postConcat(sepia(0.38f));
                ColorMatrix s = new ColorMatrix(); s.setSaturation(0.85f); m.postConcat(s);
                m.postConcat(contrast(0.86f, 18));
                break;
            }
            case 4: { // CINE: warm highs, cool shadows-ish, saturated
                ColorMatrix s = new ColorMatrix(); s.setSaturation(1.25f); m.postConcat(s);
                m.postConcat(scale(1.07f, 0.99f, 1.04f, -4));
                m.postConcat(contrast(1.12f, 0));
                break;
            }
            default: break;
        }
        return m;
    }

    public static boolean hasLeak(int stock) { return stock == 3; }

    /** Returns new developed bitmaps; inputs untouched. */
    public static Bitmap[] apply(Bitmap[] src, int stock, int grain, boolean dateStamp, android.graphics.Typeface dot) {
        Bitmap[] out = new Bitmap[src.length];
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(matrix(stock)));
        String stamp = new SimpleDateFormat("''yy MM dd", Locale.US).format(new Date());
        for (int k = 0; k < src.length; k++) {
            int w = src[k].getWidth(), h = src[k].getHeight();
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawBitmap(src[k], 0, 0, p);
            if (hasLeak(stock)) drawLeak(c, w, h, k);
            if (grain > 0) addGrain(b, grain, 0x9E3779B9 * (k + 1));
            if (dateStamp) drawStamp(c, w, h, stamp, dot);
            out[k] = b;
        }
        return out;
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

    /** Monochrome grain, strongest in the mid-tones, different per view so it "moves" in the loop. */
    private static void addGrain(Bitmap b, int grain, int seed) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int amp = Math.round(grain / 100f * 56f);
        int s = seed == 0 ? 1 : seed;
        for (int i = 0; i < px.length; i++) {
            s ^= s << 13; s ^= s >>> 17; s ^= s << 5;
            // two uniform samples -> triangle distribution (softer than pure uniform)
            int n = ((s & 0xFF) + ((s >>> 8) & 0xFF)) - 255;
            int c = px[i];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
            int lum = (r * 77 + g * 150 + bl * 29) >> 8;
            int mid = 255 - Math.abs(lum - 128) * 3 / 2; // weight 63..255
            int d = (n * amp * mid) >> 16;
            r += d; g += d; bl += d;
            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            bl = bl < 0 ? 0 : (bl > 255 ? 255 : bl);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
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
}
