package com.aurelius7.wiggle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Neo-brutalist widget kit: flat yellow/black, thick borders, hard offset shadows, Archivo Black + Space Mono. */
public final class Ui {
    private Ui() {}

    public static final int YELLOW = 0xFFFFE600;
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int RED = 0xFFFF3B1F;

    public static Typeface DISPLAY = Typeface.DEFAULT_BOLD;
    public static Typeface MONO = Typeface.MONOSPACE;
    public static Typeface MONO_REG = Typeface.MONOSPACE;

    public static void loadFonts(Context c) {
        try { DISPLAY = Typeface.createFromAsset(c.getAssets(), "fonts/display.ttf"); } catch (Exception ignored) {}
        try { MONO = Typeface.createFromAsset(c.getAssets(), "fonts/mono_bold.ttf"); } catch (Exception ignored) {}
        try { MONO_REG = Typeface.createFromAsset(c.getAssets(), "fonts/mono.ttf"); } catch (Exception ignored) {}
    }

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    public static float sp(Context c, float v) { return v * c.getResources().getDisplayMetrics().scaledDensity; }

    static void drawLines(Canvas cv, Paint p, String text, float cx, float cy) {
        String[] lines = text.split("\n");
        Paint.FontMetrics fm = p.getFontMetrics();
        float lh = (fm.descent - fm.ascent) * 0.95f;
        float top = cy - lh * lines.length / 2f - fm.ascent;
        for (int i = 0; i < lines.length; i++) cv.drawText(lines[i], cx, top + i * lh, p);
    }

    // ------------------------------------------------------------------------------------------
    /** Chunky block button / chip. "Selected" swaps to black face. Presses down into its shadow. */
    public static class BrutalButton extends View {
        private String text;
        private int face, ink, selFace = BLACK, selInk = YELLOW;
        private boolean pressedNow, sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sh, bw;
        private final float textSp;
        private Typeface font;

        public BrutalButton(Context c, String text, int face, int ink, float textSp, Typeface font, float shadowDp, float borderDp) {
            super(c);
            this.text = text; this.face = face; this.ink = ink; this.textSp = textSp; this.font = font;
            sh = dp(c, shadowDp); bw = dp(c, borderDp);
            setClickable(true);
        }
        public BrutalButton(Context c, String text, int face, int ink, float textSp) {
            this(c, text, face, ink, textSp, DISPLAY, 6, 3);
        }

        public void setText(String t) { text = t; invalidate(); }
        public void setSel(boolean s) { sel = s; invalidate(); }
        public boolean isSel() { return sel; }

        @Override public void setEnabled(boolean e) { super.setEnabled(e); invalidate(); }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            if (MeasureSpec.getMode(ws) != MeasureSpec.EXACTLY) {
                p.setTypeface(font); p.setTextSize(sp(getContext(), textSp));
                float tw = 0;
                for (String l : text.split("\n")) tw = Math.max(tw, p.measureText(l));
                w = Math.round(tw) + dp(getContext(), 24) + sh + 2 * bw;
            }
            if (MeasureSpec.getMode(hs) != MeasureSpec.EXACTLY) h = dp(getContext(), 44);
            setMeasuredDimension(w, h);
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            int a = isEnabled() ? 255 : 90;
            int off = pressedNow ? sh : 0;
            p.setStyle(Paint.Style.FILL);
            p.setColor(BLACK); p.setAlpha(a);
            if (!pressedNow) cv.drawRect(sh, sh, w, h, p);
            cv.drawRect(off, off, off + w - sh, off + h - sh, p);
            p.setColor(sel ? selFace : face); p.setAlpha(a);
            cv.drawRect(off + bw, off + bw, off + w - sh - bw, off + h - sh - bw, p);
            p.setColor(sel ? selInk : ink); p.setAlpha(a);
            p.setTypeface(font);
            p.setTextSize(sp(getContext(), textSp));
            p.setTextAlign(Paint.Align.CENTER);
            drawLines(cv, p, text, off + (w - sh) / 2f, off + (h - sh) / 2f);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: pressedNow = true; invalidate(); return true;
                case MotionEvent.ACTION_MOVE:
                    boolean in = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
                    if (in != pressedNow) { pressedNow = in; invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean was = pressedNow; pressedNow = false; invalidate();
                    if (was) { performHapticFeedback(1); performClick(); }
                    return true;
                case MotionEvent.ACTION_CANCEL: pressedNow = false; invalidate(); return true;
            }
            return super.onTouchEvent(e);
        }
    }

    // ------------------------------------------------------------------------------------------
    /** Square thumbnail of the last wiggle. */
    public static class ThumbView extends View {
        private Bitmap bmp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private boolean pressedNow;
        public ThumbView(Context c) { super(c); setClickable(true); }
        public void setBitmap(Bitmap b) { bmp = b; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), sh = dp(getContext(), 5), bw = dp(getContext(), 3);
            int off = pressedNow ? sh : 0;
            p.setColor(BLACK);
            if (!pressedNow) cv.drawRect(sh, sh, w, h, p);
            cv.drawRect(off, off, off + w - sh, off + h - sh, p);
            Rect in = new Rect(off + bw, off + bw, off + w - sh - bw, off + h - sh - bw);
            if (bmp != null && !bmp.isRecycled()) {
                int s = Math.min(bmp.getWidth(), bmp.getHeight());
                Rect src = new Rect((bmp.getWidth() - s) / 2, (bmp.getHeight() - s) / 2,
                        (bmp.getWidth() + s) / 2, (bmp.getHeight() + s) / 2);
                cv.drawBitmap(bmp, src, in, p);
            } else {
                p.setColor(YELLOW); cv.drawRect(in, p);
                p.setColor(BLACK); p.setTypeface(MONO); p.setTextSize(sp(getContext(), 10));
                p.setTextAlign(Paint.Align.CENTER);
                drawLines(cv, p, "NO\nSHOTS", in.exactCenterX(), in.exactCenterY());
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: pressedNow = true; invalidate(); return true;
                case MotionEvent.ACTION_UP: pressedNow = false; invalidate(); performClick(); return true;
                case MotionEvent.ACTION_CANCEL: pressedNow = false; invalidate(); return true;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------
    /** Level indicator chip driven by phone roll. */
    public static class LevelView extends View {
        private float roll;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public LevelView(Context c) { super(c); }
        public void setRoll(float deg) { if (Math.abs(deg - roll) > 0.3f) { roll = deg; invalidate(); } }
        public boolean isLevel() { return Math.abs(roll) < 1.5f; }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), sh = dp(getContext(), 4), bw = dp(getContext(), 2.5f);
            boolean lvl = isLevel();
            p.setStyle(Paint.Style.FILL);
            p.setColor(BLACK);
            cv.drawRect(sh, sh, w, h, p);
            cv.drawRect(0, 0, w - sh, h - sh, p);
            p.setColor(lvl ? BLACK : YELLOW);
            cv.drawRect(bw, bw, w - sh - bw, h - sh - bw, p);
            float cx = (w - sh) / 2f, cy = (h - sh) / 2f;
            // horizon line
            p.setColor(lvl ? YELLOW : BLACK);
            p.setStrokeWidth(dp(getContext(), 3));
            float len = (w - sh) * 0.22f;
            float r = (float) Math.toRadians(-Math.max(-35, Math.min(35, roll)));
            float lx = cx - (w - sh) * 0.22f;
            cv.drawLine(lx - len * (float) Math.cos(r), cy - len * (float) Math.sin(r),
                        lx + len * (float) Math.cos(r), cy + len * (float) Math.sin(r), p);
            p.setTypeface(MONO); p.setTextSize(sp(getContext(), 11)); p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            String t = lvl ? "LEVEL" : Math.round(Math.abs(roll)) + "°";
            cv.drawText(t, cx + (w - sh) * 0.18f, cy - (fm.ascent + fm.descent) / 2f, p);
        }
    }

    // ------------------------------------------------------------------------------------------
    /** The sweep HUD: coach label, slide track with marker, and the 4 "virtual lenses". */
    public static class SweepHud extends View {
        private float progress;
        private String coach = "SLIDE";
        private boolean warn;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public SweepHud(Context c) { super(c); }
        public int lensCount() { return progress <= 0.001f ? 1 : Math.min(4, 1 + (int) Math.floor(progress * 3f + 0.001f)); }
        public void set(float prog, String coachText, boolean warning) { progress = prog; coach = coachText; warn = warning; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            int w = getWidth();
            float sh = dp(c, 5), bw = dp(c, 3);
            float y = 0;
            // coach label chip
            p.setTypeface(DISPLAY); p.setTextSize(sp(c, 28)); p.setTextAlign(Paint.Align.CENTER);
            float tw = p.measureText(coach) + dp(c, 28), th = dp(c, 52);
            float lx = (w - tw) / 2f;
            p.setColor(BLACK);
            cv.drawRect(lx + sh, y + sh, lx + tw + sh, y + th + sh, p);
            cv.drawRect(lx, y, lx + tw, y + th, p);
            p.setColor(warn ? RED : YELLOW);
            cv.drawRect(lx + bw, y + bw, lx + tw - bw, y + th - bw, p);
            p.setColor(BLACK);
            Paint.FontMetrics fm = p.getFontMetrics();
            cv.drawText(coach, w / 2f, y + th / 2f - (fm.ascent + fm.descent) / 2f, p);
            y += th + dp(c, 18);

            // 4 lenses
            int lit = lensCount();
            float ls = dp(c, 50), gap = dp(c, 12);
            float total = 4 * ls + 3 * gap;
            float x0 = (w - total) / 2f;
            p.setTextSize(sp(c, 16)); p.setTypeface(MONO);
            for (int i = 0; i < 4; i++) {
                float x = x0 + i * (ls + gap);
                boolean on = i < lit;
                p.setColor(BLACK);
                cv.drawRect(x + sh, y + sh, x + ls + sh, y + ls + sh, p);
                cv.drawRect(x, y, x + ls, y + ls, p);
                p.setColor(on ? YELLOW : BLACK);
                cv.drawRect(x + bw, y + bw, x + ls - bw, y + ls - bw, p);
                if (!on) { p.setColor(YELLOW); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(c, 2));
                    cv.drawRect(x + bw * 2, y + bw * 2, x + ls - bw * 2, y + ls - bw * 2, p); p.setStyle(Paint.Style.FILL); }
                p.setColor(on ? BLACK : YELLOW);
                fm = p.getFontMetrics();
                cv.drawText(String.valueOf(i + 1), x + ls / 2f, y + ls / 2f - (fm.ascent + fm.descent) / 2f, p);
            }
            y += ls + dp(c, 20);

            // slide track
            float tx0 = x0, tx1 = x0 + total, tH = dp(c, 18);
            p.setColor(BLACK);
            cv.drawRect(tx0 + sh, y + sh, tx1 + sh, y + tH + sh, p);
            cv.drawRect(tx0, y, tx1, y + tH, p);
            p.setColor(YELLOW);
            cv.drawRect(tx0 + bw, y + bw, tx0 + bw + (tx1 - tx0 - 2 * bw) * progress, y + tH - bw, p);
            for (int i = 1; i < 3; i++) {
                float tx = tx0 + (tx1 - tx0) * i / 3f;
                p.setColor(BLACK); cv.drawRect(tx - dp(c, 1.5f), y, tx + dp(c, 1.5f), y + tH, p);
            }
            float mx = tx0 + (tx1 - tx0) * progress, ms = dp(c, 30);
            p.setColor(BLACK);
            cv.drawRect(mx - ms / 2f, y - dp(c, 7), mx + ms / 2f, y + tH + dp(c, 7), p);
            p.setColor(YELLOW);
            cv.drawRect(mx - ms / 2f + bw, y - dp(c, 7) + bw, mx + ms / 2f - bw, y + tH + dp(c, 7) - bw, p);
        }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), dp(getContext(), 52 + 18 + 50 + 20 + 18 + 14));
        }
    }

    // ------------------------------------------------------------------------------------------
    /** Segmented brutal slider, value 0..1. */
    public static class BrutalSlider extends View {
        public interface OnChange { void onChange(float v, boolean done); }
        public OnChange listener;
        private float value = 0.5f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final int SEG = 12;
        public BrutalSlider(Context c) { super(c); }
        public void setValue(float v) { value = Math.max(0, Math.min(1, v)); invalidate(); }
        public float getValue() { return value; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            int w = getWidth(), h = getHeight();
            float sh = dp(c, 4), bw = dp(c, 2.5f), knob = dp(c, 26);
            float top = dp(c, 6), bot = h - sh - dp(c, 6);
            p.setColor(BLACK);
            cv.drawRect(sh, top + sh, w, bot + sh, p);
            cv.drawRect(0, top, w - sh, bot, p);
            p.setColor(YELLOW);
            cv.drawRect(bw, top + bw, w - sh - bw, bot - bw, p);
            float inner = w - sh - 2 * bw, gap = dp(c, 3), segW = (inner - gap * (SEG + 1)) / SEG;
            int filled = Math.round(value * SEG);
            p.setColor(BLACK);
            for (int i = 0; i < filled; i++) {
                float x = bw + gap + i * (segW + gap);
                cv.drawRect(x, top + bw + gap, x + segW, bot - bw - gap, p);
            }
            float kx = bw + (inner - knob) * value;
            p.setColor(BLACK);
            cv.drawRect(kx, 0, kx + knob, h - sh, p);
            p.setColor(YELLOW);
            cv.drawRect(kx + bw, bw, kx + knob - bw, h - sh - bw, p);
            p.setColor(BLACK);
            cv.drawRect(kx + knob / 2f - dp(c, 1.5f), dp(c, 8), kx + knob / 2f + dp(c, 1.5f), h - sh - dp(c, 8), p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            float knob = dp(getContext(), 26), inner = getWidth() - dp(getContext(), 4) - 2 * dp(getContext(), 2.5f);
            float v = (e.getX() - knob / 2f) / Math.max(1, inner - knob);
            setValue(v);
            boolean done = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
            if (listener != null) listener.onChange(value, done);
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------
    /** Result stage: draws the current loop frame (fit) + focus brackets; drag to refocus. */
    public static class StageView extends View {
        public interface OnFocusDrag { void onFocusDrag(float bx, float by, boolean done); }
        public OnFocusDrag listener;
        private Bitmap frame;
        public float focusBx = -1, focusBy = -1; // in bitmap px
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        public boolean showFocus = true;
        public StageView(Context c) { super(c); }
        public void setFrame(Bitmap b) { frame = b; invalidate(); }

        private void computeDst() {
            if (frame == null) return;
            float pad = dp(getContext(), 14), sh = dp(getContext(), 9);
            float aw = getWidth() - 2 * pad - sh, ah = getHeight() - 2 * pad - sh;
            float s = Math.min(aw / frame.getWidth(), ah / frame.getHeight());
            float dw = frame.getWidth() * s, dh = frame.getHeight() * s;
            float l = pad + (aw - dw) / 2f, t = pad + (ah - dh) / 2f;
            dst.set(l, t, l + dw, t + dh);
        }

        @Override protected void onDraw(Canvas cv) {
            cv.drawColor(YELLOW);
            if (frame == null || frame.isRecycled()) return;
            computeDst();
            float bw = dp(getContext(), 5), sh = dp(getContext(), 9);
            p.setColor(BLACK);
            cv.drawRect(dst.left - bw + sh, dst.top - bw + sh, dst.right + bw + sh, dst.bottom + bw + sh, p);
            cv.drawRect(dst.left - bw, dst.top - bw, dst.right + bw, dst.bottom + bw, p);
            cv.drawBitmap(frame, null, dst, p);
            if (showFocus && focusBx >= 0) {
                float s = dst.width() / frame.getWidth();
                float cx = dst.left + focusBx * s, cy = dst.top + focusBy * s;
                FocusOverlay.drawMark(cv, p, getContext(), cx, cy, 0.75f);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (frame == null || listener == null) return false;
            computeDst();
            float s = dst.width() / frame.getWidth();
            float bx = (e.getX() - dst.left) / s, by = (e.getY() - dst.top) / s;
            bx = Math.max(0, Math.min(frame.getWidth() - 1, bx));
            by = Math.max(0, Math.min(frame.getHeight() - 1, by));
            int a = e.getActionMasked();
            focusBx = bx; focusBy = by; invalidate();
            listener.onFocusDrag(bx, by, a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL);
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------
    /** Focus-point brackets over the live preview; tap or drag to move. */
    public static class FocusOverlay extends View {
        public float u = 0.5f, v = 0.5f;
        public boolean show = true;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public interface OnFocus { void onFocus(float u, float v); }
        public OnFocus listener;
        public FocusOverlay(Context c) { super(c); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                u = Math.max(0.12f, Math.min(0.88f, e.getX() / getWidth()));
                v = Math.max(0.15f, Math.min(0.80f, e.getY() / getHeight()));
                invalidate();
                if (listener != null) listener.onFocus(u, v);
            }
            return true;
        }

        @Override protected void onDraw(Canvas cv) {
            if (!show) return;
            drawMark(cv, p, getContext(), u * getWidth(), v * getHeight(), 1f);
        }

        static void drawMark(Canvas cv, Paint p, Context c, float cx, float cy, float k) {
            float s = dp(c, 40) * k, L = dp(c, 16) * k;
            brackets(cv, p, cx, cy, s, L, BLACK, dp(c, 8) * k);
            brackets(cv, p, cx, cy, s, L, YELLOW, dp(c, 3.5f) * k);
            p.setStyle(Paint.Style.FILL);
            float d = dp(c, 6) * k;
            p.setColor(BLACK); cv.drawRect(cx - d, cy - d, cx + d, cy + d, p);
            d = dp(c, 3.5f) * k;
            p.setColor(YELLOW); cv.drawRect(cx - d, cy - d, cx + d, cy + d, p);
        }

        private static void brackets(Canvas cv, Paint p, float cx, float cy, float s, float L, int color, float stroke) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.SQUARE);
            p.setStrokeWidth(stroke);
            p.setColor(color);
            float l = cx - s, r = cx + s, t = cy - s, b = cy + s;
            cv.drawLine(l, t, l + L, t, p); cv.drawLine(l, t, l, t + L, p);
            cv.drawLine(r, t, r - L, t, p); cv.drawLine(r, t, r, t + L, p);
            cv.drawLine(l, b, l + L, b, p); cv.drawLine(l, b, l, b - L, p);
            cv.drawLine(r, b, r - L, b, p); cv.drawLine(r, b, r, b - L, p);
        }
    }
}
