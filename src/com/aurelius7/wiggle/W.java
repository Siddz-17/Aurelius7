package com.aurelius7.wiggle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/** Dark-film widget kit: warm black, off-white text, one film-yellow accent, dot-matrix readouts. */
public final class W {
    private W() {}

    public static final int BG = 0xFF0E0D0B, SURF = 0xFF1B1A17, TXT = 0xFFF2EDE4, MUT = 0xFFA39C90;
    public static final int ACC = 0xFFFFC93C, INK = 0xFF14110A, EDGE = 0xFFE8873A, REC = 0xFFFF4D2E;
    public static final int LINE = 0x29F2EDE4, GLASS = 0x990E0D0B, FILM = 0xFF050505, HOLE = 0xFF2B2924;

    public static Typeface HEAD = Typeface.DEFAULT_BOLD, UI = Typeface.DEFAULT, UI_SEMI = Typeface.DEFAULT_BOLD, DOT = Typeface.MONOSPACE;

    public static void load(Context c) {
        try { HEAD = Typeface.createFromAsset(c.getAssets(), "fonts/head.ttf"); } catch (Exception ignored) {}
        try { UI = Typeface.createFromAsset(c.getAssets(), "fonts/ui.ttf"); } catch (Exception ignored) {}
        try { UI_SEMI = Typeface.createFromAsset(c.getAssets(), "fonts/ui_semi.ttf"); } catch (Exception ignored) {}
        try { DOT = Typeface.createFromAsset(c.getAssets(), "fonts/dot.ttf"); } catch (Exception ignored) {}
    }

    public static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
    public static int dpi(Context c, float v) { return Math.round(dp(c, v)); }
    public static float sp(Context c, float v) { return v * c.getResources().getDisplayMetrics().scaledDensity; }

    static void centerText(Canvas cv, Paint p, String t, float cx, float cy) {
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, p);
    }

    // ---------------------------------------------------------------------------------- icons
    public static final int IC_NONE = 0, IC_FLASH = 1, IC_GEAR = 2, IC_BACK = 3, IC_CAM = 4, IC_DOT = 5;

    static void icon(Canvas c, Paint p, int type, float cx, float cy, float s, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.09f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float h = s / 2f;
        Path path = new Path();
        switch (type) {
            case IC_FLASH:
                path.moveTo(cx + h * 0.1f, cy - h);
                path.lineTo(cx - h * 0.6f, cy + h * 0.15f);
                path.lineTo(cx - h * 0.05f, cy + h * 0.15f);
                path.lineTo(cx - h * 0.15f, cy + h);
                path.lineTo(cx + h * 0.65f, cy - h * 0.25f);
                path.lineTo(cx + h * 0.08f, cy - h * 0.25f);
                path.close();
                c.drawPath(path, p);
                break;
            case IC_GEAR:
                c.drawCircle(cx, cy, h * 0.3f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(cx + (float) Math.cos(a) * h * 0.58f, cy + (float) Math.sin(a) * h * 0.58f,
                            cx + (float) Math.cos(a) * h * 0.92f, cy + (float) Math.sin(a) * h * 0.92f, p);
                }
                break;
            case IC_BACK:
                path.moveTo(cx + h * 0.3f, cy - h * 0.6f);
                path.lineTo(cx - h * 0.3f, cy);
                path.lineTo(cx + h * 0.3f, cy + h * 0.6f);
                c.drawPath(path, p);
                break;
            case IC_CAM:
                c.drawRoundRect(new RectF(cx - h * 0.85f, cy - h * 0.45f, cx + h * 0.85f, cy + h * 0.7f), h * 0.2f, h * 0.2f, p);
                c.drawCircle(cx, cy + h * 0.12f, h * 0.3f, p);
                c.drawLine(cx - h * 0.3f, cy - h * 0.62f, cx + h * 0.3f, cy - h * 0.62f, p);
                break;
            case IC_DOT:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, h * 0.35f, p);
                break;
        }
        p.setStyle(Paint.Style.FILL);
    }

    // ---------------------------------------------------------------------------------- Pill
    /** Rounded pill: glass (over camera), outline (ghost), or solid accent (selected / CTA). */
    public static class Pill extends View {
        public static final int GLASS_STYLE = 0, GHOST = 1, SOLID = 2;
        private String text;
        private int icon, style;
        private boolean sel, dotFont, pressedNow;
        private float textSp;
        private Typeface font;
        private int textColorOverride = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Pill(Context c, String text, int icon, int style, float textSp, Typeface font) {
            super(c);
            this.text = text; this.icon = icon; this.style = style; this.textSp = textSp; this.font = font;
            setClickable(true);
        }
        public Pill dotFont() { dotFont = true; font = DOT; return this; }
        public void setText(String t) { text = t; requestLayout(); invalidate(); }
        public String getText() { return text; }
        public void setSel(boolean s) { sel = s; invalidate(); }
        public boolean isSel() { return sel; }
        public void setIcon(int i) { icon = i; requestLayout(); invalidate(); }
        public void setStyleKind(int s) { style = s; invalidate(); }
        public void setTextColorOverride(int c) { textColorOverride = c; invalidate(); }
        @Override public void setEnabled(boolean e) { super.setEnabled(e); invalidate(); }

        private float iconSize() { return dp(getContext(), 16); }

        @Override protected void onMeasure(int ws, int hs) {
            p.setTypeface(font); p.setTextSize(sp(getContext(), textSp));
            float tw = text.isEmpty() ? 0 : p.measureText(text);
            float iw = icon != IC_NONE ? iconSize() + (text.isEmpty() ? 0 : dp(getContext(), 6)) : 0;
            int w = Math.round(tw + iw + dp(getContext(), 28));
            int h = dpi(getContext(), 36);
            if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) w = MeasureSpec.getSize(ws);
            else if (MeasureSpec.getMode(ws) == MeasureSpec.AT_MOST) w = Math.min(w, MeasureSpec.getSize(ws));
            if (MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float a = isEnabled() ? 1f : 0.4f;
            r.set(dp(getContext(), 0.75f), dp(getContext(), 0.75f), w - dp(getContext(), 0.75f), h - dp(getContext(), 0.75f));
            float rad = h / 2f;
            boolean solid = sel || style == SOLID;
            int fg;
            if (solid) {
                p.setStyle(Paint.Style.FILL); p.setColor(ACC); p.setAlpha((int) (255 * a * (pressedNow ? 0.8f : 1f)));
                c.drawRoundRect(r, rad, rad, p);
                fg = INK;
            } else {
                if (style == GLASS_STYLE || pressedNow) {
                    p.setStyle(Paint.Style.FILL); p.setColor(pressedNow ? 0x33F2EDE4 : GLASS);
                    c.drawRoundRect(r, rad, rad, p);
                }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(getContext(), 1)); p.setColor(LINE);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.FILL);
                fg = TXT;
            }
            if (textColorOverride != 0 && !solid) fg = textColorOverride;
            p.setTypeface(font); p.setTextSize(sp(getContext(), textSp));
            float tw = text.isEmpty() ? 0 : p.measureText(text);
            float iw = icon != IC_NONE ? iconSize() + (text.isEmpty() ? 0 : dp(getContext(), 6)) : 0;
            float x = (w - tw - iw) / 2f;
            if (icon != IC_NONE) {
                Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
                icon(c, ip, icon, x + iconSize() / 2f, h / 2f, iconSize(), fg);
                if (icon == IC_DOT) icon(c, ip, icon, x + iconSize() / 2f, h / 2f, iconSize(), REC);
                x += iw;
            }
            p.setColor(fg); p.setAlpha((int) (255 * a));
            p.setTextAlign(Paint.Align.LEFT);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(text, x, h / 2f - (fm.ascent + fm.descent) / 2f, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled() || !isClickable()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: pressedNow = true; invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    boolean was = pressedNow; pressedNow = false; invalidate();
                    if (was && e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= -20 && e.getY() <= getHeight() + 20) {
                        performHapticFeedback(1); performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL: pressedNow = false; invalidate(); return true;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Round icon button
    public static class IconBtn extends View {
        private final int icon;
        private boolean pressedNow;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public IconBtn(Context c, int icon, String desc) { super(c); this.icon = icon; setClickable(true); setContentDescription(desc); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - dp(getContext(), 1);
            p.setStyle(Paint.Style.FILL); p.setColor(pressedNow ? 0x33F2EDE4 : GLASS);
            c.drawCircle(w / 2, h / 2, r, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(getContext(), 1)); p.setColor(LINE);
            c.drawCircle(w / 2, h / 2, r, p);
            icon(c, p, icon, w / 2, h / 2, dp(getContext(), 20), TXT);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: pressedNow = true; invalidate(); return true;
                case MotionEvent.ACTION_UP: pressedNow = false; invalidate(); performHapticFeedback(1); performClick(); return true;
                case MotionEvent.ACTION_CANCEL: pressedNow = false; invalidate(); return true;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Shutter
    public static class Shutter extends View {
        public static final int IDLE = 0, REC_STATE = 1, BUSY = 2;
        private int state;
        private boolean pressedNow;
        private float spin;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Shutter(Context c) { super(c); setClickable(true); setContentDescription("Shoot"); }
        public void setState(int s) { state = s; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2f - dp(getContext(), 2);
            float a = isEnabled() || state != IDLE ? 1f : 0.4f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(getContext(), 4)); p.setColor(ACC); p.setAlpha((int) (255 * a));
            if (state == BUSY) {
                c.drawArc(new RectF(cx - R, cy - R, cx + R, cy + R), spin, 270, false, p);
                spin = (spin + 12) % 360; postInvalidateDelayed(16);
            } else c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.FILL);
            if (state == REC_STATE) {
                p.setColor(REC); float s = R * 0.38f;
                c.drawRoundRect(new RectF(cx - s, cy - s, cx + s, cy + s), s * 0.3f, s * 0.3f, p);
            } else if (state == IDLE) {
                p.setColor(TXT); p.setAlpha((int) (255 * a));
                c.drawCircle(cx, cy, R * (pressedNow ? 0.70f : 0.80f), p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: pressedNow = true; invalidate(); return true;
                case MotionEvent.ACTION_UP: pressedNow = false; invalidate(); performClick(); return true;
                case MotionEvent.ACTION_CANCEL: pressedNow = false; invalidate(); return true;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Depth knob
    public static class Knob extends View {
        private String label, value;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Knob(Context c, String label) { super(c); this.label = label; value = ""; setClickable(true); }
        public void setValue(String v) { value = v; setContentDescription(label + " " + v); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - dp(getContext(), 1);
            p.setStyle(Paint.Style.FILL); p.setColor(SURF); c.drawCircle(w / 2, h / 2, r, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(getContext(), 1.2f)); p.setColor(LINE); c.drawCircle(w / 2, h / 2, r, p);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(UI); p.setTextSize(sp(getContext(), 8.5f)); p.setColor(MUT);
            centerText(c, p, label, w / 2, h / 2 - dp(getContext(), 7));
            p.setTypeface(DOT); p.setTextSize(sp(getContext(), 13)); p.setColor(ACC);
            centerText(c, p, value, w / 2, h / 2 + dp(getContext(), 8));
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) { performHapticFeedback(1); performClick(); }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Rounded thumbnail
    public static class Thumb extends View {
        private Bitmap bmp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        public Thumb(Context c) { super(c); setClickable(true); setContentDescription("Open contact sheet"); }
        public void setBitmap(Bitmap b) { bmp = b; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = dp(getContext(), 14);
            RectF rr = new RectF(0, 0, w, h);
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(SURF);
            c.drawRoundRect(rr, r, r, p);
            if (bmp != null && !bmp.isRecycled()) {
                BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float s = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(s, s);
                m.postTranslate((w - bmp.getWidth() * s) / 2f, (h - bmp.getHeight() * s) / 2f);
                sh.setLocalMatrix(m);
                p.setShader(sh);
                c.drawRoundRect(rr, r, r, p);
                p.setShader(null);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(getContext(), 1.5f)); p.setColor(LINE);
            c.drawRoundRect(rr, r, r, p);
            p.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Mode dial
    public static class ModeDial extends View {
        public interface OnMode { void onMode(boolean orbit); }
        public OnMode listener;
        private boolean orbit;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public ModeDial(Context c) { super(c); setClickable(true); }
        public void setOrbit(boolean o) { orbit = o; setContentDescription(o ? "Mode orbit" : "Mode slide"); invalidate(); }
        public boolean isOrbit() { return orbit; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float gap = dp(getContext(), 34), iw = dp(getContext(), 74);
            float x0 = w / 2 - gap / 2 - iw, x1 = w / 2 + gap / 2;
            drawItem(c, x0, iw, h, false, !orbit);
            drawItem(c, x1, iw, h, true, orbit);
        }
        private void drawItem(Canvas c, float x, float iw, float h, boolean arc, boolean on) {
            int a = on ? 255 : 115;
            float y = dp(getContext(), 14);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(getContext(), 1.6f));
            p.setColor(TXT); p.setAlpha(a);
            p.setPathEffect(new DashPathEffect(new float[]{dp(getContext(), 3), dp(getContext(), 4)}, 0));
            Path path = new Path();
            if (arc) { path.moveTo(x + 4, y + 8); path.quadTo(x + iw / 2, y - 18, x + iw - 4, y + 8); }
            else { path.moveTo(x + 4, y + 4); path.lineTo(x + iw - 4, y + 4); }
            c.drawPath(path, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL); p.setColor(ACC); p.setAlpha(a);
            c.drawCircle(x + 8, arc ? y + 5 : y + 4, dp(getContext(), 4), p);
            p.setTypeface(HEAD); p.setTextSize(sp(getContext(), 13)); p.setTextAlign(Paint.Align.CENTER);
            p.setLetterSpacing(0.14f);
            p.setColor(on ? ACC : TXT); p.setAlpha(a);
            c.drawText(arc ? "ORBIT" : "SLIDE", x + iw / 2, h - dp(getContext(), 6), p);
            p.setLetterSpacing(0f);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                boolean o = e.getX() > getWidth() / 2f;
                if (o != orbit) { setOrbit(o); performHapticFeedback(1); if (listener != null) listener.onMode(o); }
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Sweep HUD
    public static class SweepHud extends View {
        private float progress;
        private boolean arc, warn;
        private String label = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public SweepHud(Context c) { super(c); }
        public int lensCount() { return progress <= 0.001f ? 1 : Math.min(4, 1 + (int) Math.floor(progress * 3f + 0.001f)); }
        public void set(float prog, String lbl, boolean warning, boolean isArc) {
            progress = Math.max(0, Math.min(1, prog)); label = lbl; warn = warning; arc = isArc; invalidate();
        }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), dpi(getContext(), 170)); }
        @Override protected void onDraw(Canvas c) {
            Context ctx = getContext();
            float w = getWidth();
            // label
            p.setTypeface(HEAD); p.setTextSize(sp(ctx, 22)); p.setTextAlign(Paint.Align.CENTER);
            p.setShadowLayer(dp(ctx, 10), 0, dp(ctx, 2), 0x99000000);
            p.setColor(warn ? REC : TXT);
            centerText(c, p, label, w / 2, dp(ctx, 16));
            p.setShadowLayer(0, 0, 0, 0);
            // path
            float pw = Math.min(w - dp(ctx, 50), dp(ctx, 320)), x0 = (w - pw) / 2, x1 = x0 + pw;
            float yb = dp(ctx, 128), yt = dp(ctx, 44);
            Path path = new Path();
            if (arc) { path.moveTo(x0, yb); path.quadTo(w / 2, yt - (yb - yt), x1, yb); }
            else { path.moveTo(x0, (yb + yt) / 2 + dp(ctx, 16)); path.lineTo(x1, (yb + yt) / 2 + dp(ctx, 16)); }
            PathMeasure pm = new PathMeasure(path, false);
            float len = pm.getLength();
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(ctx, 2)); p.setColor(0x40F2EDE4);
            p.setPathEffect(new DashPathEffect(new float[]{dp(ctx, 2), dp(ctx, 6)}, 0));
            c.drawPath(path, p);
            p.setPathEffect(null);
            Path seg = new Path();
            pm.getSegment(0, len * progress, seg, true);
            p.setColor(ACC); p.setStrokeWidth(dp(ctx, 3)); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(seg, p);
            float[] pos = new float[2];
            int lit = lensCount();
            for (int i = 0; i < 4; i++) {
                pm.getPosTan(len * i / 3f, pos, null);
                if (i < lit) { p.setStyle(Paint.Style.FILL); p.setColor(ACC); }
                else { p.setStyle(Paint.Style.STROKE); p.setColor(TXT); p.setStrokeWidth(dp(ctx, 2)); }
                c.drawCircle(pos[0], pos[1], dp(ctx, 7), p);
            }
            pm.getPosTan(len * progress, pos, null);
            p.setStyle(Paint.Style.STROKE); p.setColor(ACC); p.setStrokeWidth(dp(ctx, 2));
            c.drawCircle(pos[0], pos[1], dp(ctx, 12), p);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(DOT); p.setTextSize(sp(ctx, 13)); p.setColor(ACC);
            centerText(c, p, "VIEW " + lit + " / 4", w / 2, dp(ctx, 156));
        }
    }

    // ---------------------------------------------------------------------------------- Focus brackets (camera)
    public static class Brackets extends View {
        public float u = 0.5f, v = 0.46f;
        public boolean show = true;
        public interface OnFocus { void onFocus(float u, float v); }
        public OnFocus listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Brackets(Context c) { super(c); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                u = Math.max(0.12f, Math.min(0.88f, e.getX() / getWidth()));
                v = Math.max(0.16f, Math.min(0.78f, e.getY() / getHeight()));
                invalidate();
                if (listener != null) listener.onFocus(u, v);
            }
            return true;
        }
        @Override protected void onDraw(Canvas c) { if (show) mark(c, p, getContext(), u * getWidth(), v * getHeight(), 1f); }
        static void mark(Canvas c, Paint p, Context ctx, float cx, float cy, float k) {
            float sx = dp(ctx, 70) * k, sy = dp(ctx, 90) * k, L = dp(ctx, 20) * k;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(ctx, 2)); p.setStrokeCap(Paint.Cap.SQUARE);
            p.setColor(0x55000000); p.setStrokeWidth(dp(ctx, 4));
            corners(c, p, cx, cy, sx, sy, L);
            p.setColor(ACC); p.setStrokeWidth(dp(ctx, 2));
            corners(c, p, cx, cy, sx, sy, L);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, dp(ctx, 3) * k, p);
        }
        private static void corners(Canvas c, Paint p, float cx, float cy, float sx, float sy, float L) {
            float l = cx - sx, r = cx + sx, t = cy - sy, b = cy + sy;
            c.drawLine(l, t, l + L, t, p); c.drawLine(l, t, l, t + L, p);
            c.drawLine(r, t, r - L, t, p); c.drawLine(r, t, r, t + L, p);
            c.drawLine(l, b, l + L, b, p); c.drawLine(l, b, l, b - L, p);
            c.drawLine(r, b, r - L, b, p); c.drawLine(r, b, r, b - L, p);
        }
    }

    // ---------------------------------------------------------------------------------- Film stage (result)
    static void sprockets(Canvas c, Paint p, Context ctx, float l, float r, float y, float h) {
        p.setColor(HOLE);
        float hw = dp(ctx, 9), hh = Math.min(dp(ctx, 6), h * 0.5f);
        int n = Math.max(4, (int) ((r - l) / dp(ctx, 22)));
        float step = (r - l - dp(ctx, 16) - hw) / (n - 1);
        for (int i = 0; i < n; i++) {
            float x = l + dp(ctx, 8) + i * step;
            c.drawRoundRect(new RectF(x, y + (h - hh) / 2, x + hw, y + (h + hh) / 2), dp(ctx, 1.5f), dp(ctx, 1.5f), p);
        }
    }

    public static class FilmStage extends View {
        public interface OnFocusDrag { void onFocusDrag(float bx, float by, boolean done); }
        public OnFocusDrag listener;
        private Bitmap frame;
        public float focusBx = -1, focusBy = -1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        public FilmStage(Context c) { super(c); }
        public void setFrame(Bitmap b) { frame = b; invalidate(); }
        private void layoutDst() {
            if (frame == null) return;
            Context ctx = getContext();
            float band = dp(ctx, 16), side = dp(ctx, 8);
            float aw = getWidth() - dp(ctx, 40) - 2 * side, ah = getHeight() - dp(ctx, 20) - 2 * band;
            float s = Math.min(aw / frame.getWidth(), ah / frame.getHeight());
            float dw = frame.getWidth() * s, dh = frame.getHeight() * s;
            float l = (getWidth() - dw) / 2f, t = (getHeight() - dh) / 2f;
            dst.set(l, t, l + dw, t + dh);
        }
        @Override protected void onDraw(Canvas c) {
            if (frame == null || frame.isRecycled()) return;
            layoutDst();
            Context ctx = getContext();
            float band = dp(ctx, 16), side = dp(ctx, 8);
            RectF film = new RectF(dst.left - side, dst.top - band, dst.right + side, dst.bottom + band);
            p.setColor(FILM);
            c.drawRoundRect(film, dp(ctx, 6), dp(ctx, 6), p);
            sprockets(c, p, ctx, film.left, film.right, film.top, band);
            sprockets(c, p, ctx, film.left, film.right, dst.bottom, band);
            c.drawBitmap(frame, null, dst, p);
            if (focusBx >= 0) {
                float s = dst.width() / frame.getWidth();
                Brackets.mark(c, p, ctx, dst.left + focusBx * s, dst.top + focusBy * s, 0.55f);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (frame == null || listener == null) return false;
            layoutDst();
            float s = dst.width() / frame.getWidth();
            float bx = Math.max(0, Math.min(frame.getWidth() - 1, (e.getX() - dst.left) / s));
            float by = Math.max(0, Math.min(frame.getHeight() - 1, (e.getY() - dst.top) / s));
            int a = e.getActionMasked();
            focusBx = bx; focusBy = by; invalidate();
            listener.onFocusDrag(bx, by, a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL);
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Slider
    public static class Slider extends View {
        public interface OnChange { void onChange(float v, boolean done); }
        public OnChange listener;
        private float value;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Slider(Context c) { super(c); }
        public void setValue(float v) { value = Math.max(0, Math.min(1, v)); invalidate(); }
        public float getValue() { return value; }
        @Override protected void onDraw(Canvas c) {
            Context ctx = getContext();
            float w = getWidth(), h = getHeight(), kr = dp(ctx, 9), l = kr, r = w - kr, cy = h / 2;
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(dp(ctx, 3));
            p.setColor(0x33F2EDE4); c.drawLine(l, cy, r, cy, p);
            float x = l + (r - l) * value;
            p.setColor(ACC); c.drawLine(l, cy, x, cy, p);
            c.drawCircle(x, cy, kr, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            float kr = dp(getContext(), 9);
            setValue((e.getX() - kr) / Math.max(1, getWidth() - 2 * kr));
            boolean done = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
            if (listener != null) listener.onChange(value, done);
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Segmented control
    public static class Segmented extends View {
        public interface OnPick { void onPick(int i); }
        public OnPick listener;
        private final String[] opts;
        private int sel;
        private final boolean dot;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Segmented(Context c, String[] opts, int sel, boolean dotFont) { super(c); this.opts = opts; this.sel = sel; this.dot = dotFont; setClickable(true); }
        public void setSel(int s) { sel = s; invalidate(); }
        private float[] widths() {
            p.setTypeface(dot ? DOT : UI_SEMI); p.setTextSize(sp(getContext(), 12));
            float[] ws = new float[opts.length];
            for (int i = 0; i < opts.length; i++) ws[i] = p.measureText(opts[i]) + dp(getContext(), 20);
            return ws;
        }
        @Override protected void onMeasure(int wsp, int hsp) {
            float tot = dp(getContext(), 6);
            for (float x : widths()) tot += x;
            int w = MeasureSpec.getMode(wsp) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(wsp) : Math.round(tot);
            setMeasuredDimension(w, dpi(getContext(), 34));
        }
        private float[] bounds() {
            float[] ws = widths();
            float tot = 0; for (float x : ws) tot += x;
            float inner = getWidth() - dp(getContext(), 6), k = inner / tot;
            float[] b = new float[opts.length + 1];
            b[0] = dp(getContext(), 3);
            for (int i = 0; i < opts.length; i++) b[i + 1] = b[i] + ws[i] * k;
            return b;
        }
        @Override protected void onDraw(Canvas c) {
            Context ctx = getContext();
            float w = getWidth(), h = getHeight();
            RectF r = new RectF(dp(ctx, 0.5f), dp(ctx, 0.5f), w - dp(ctx, 0.5f), h - dp(ctx, 0.5f));
            p.setStyle(Paint.Style.FILL); p.setColor(BG); c.drawRoundRect(r, h / 2, h / 2, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(ctx, 1)); p.setColor(LINE); c.drawRoundRect(r, h / 2, h / 2, p);
            p.setStyle(Paint.Style.FILL);
            float[] b = bounds();
            for (int i = 0; i < opts.length; i++) {
                boolean on = i == sel;
                if (on) { p.setColor(ACC); c.drawRoundRect(new RectF(b[i], dp(ctx, 3), b[i + 1], h - dp(ctx, 3)), h / 2, h / 2, p); }
                p.setColor(on ? INK : TXT); p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(dot ? DOT : UI_SEMI); p.setTextSize(sp(ctx, 12));
                centerText(c, p, opts[i], (b[i] + b[i + 1]) / 2, h / 2);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float[] b = bounds();
                for (int i = 0; i < opts.length; i++) if (e.getX() >= b[i] && e.getX() < b[i + 1] + 1) {
                    if (i != sel) { sel = i; invalidate(); performHapticFeedback(1); if (listener != null) listener.onPick(i); }
                }
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Switch
    public static class Toggle extends View {
        public interface OnToggle { void onToggle(boolean on); }
        public OnToggle listener;
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Toggle(Context c, boolean on) { super(c); this.on = on; setClickable(true); }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(dpi(getContext(), 50), dpi(getContext(), 30)); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(on ? ACC : 0xFF3A3833);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(on ? INK : TXT);
            float r = h / 2 - dp(getContext(), 3);
            c.drawCircle(on ? w - h / 2 : h / 2, h / 2, r, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                on = !on; invalidate(); performHapticFeedback(1);
                if (listener != null) listener.onToggle(on);
            }
            return true;
        }
    }

    // ---------------------------------------------------------------------------------- Live grain + leak overlays
    public static class GrainView extends View {
        private final Bitmap tile;
        private final Paint p = new Paint();
        private final Random rnd = new Random();
        private float strength;
        private boolean leak;
        private final Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        public GrainView(Context c) {
            super(c);
            int n = 192;
            int[] px = new int[n * n];
            Random r = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = r.nextInt(256);
                int a = Math.abs(v - 128) * 2;  // stronger when far from mid-grey
                px[i] = (a << 24) | (v << 16) | (v << 8) | v;
            }
            tile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }
        public void set(float s, boolean leakOn) { strength = s; leak = leakOn; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (leak) {
                lp.setShader(new RadialGradient(0, 0, Math.max(w, h) * 0.7f, new int[]{0x66FF7828, 0x00FF7828}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, lp);
                lp.setShader(new RadialGradient(w, h, Math.max(w, h) * 0.45f, new int[]{0x40FFC850, 0x00FFC850}, null, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, lp);
            }
            if (strength <= 0.01f) return;
            BitmapShader sh = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            android.graphics.Matrix m = new android.graphics.Matrix();
            float sc = dp(getContext(), 1.1f);
            m.setScale(sc, sc);
            m.postTranslate(rnd.nextInt(192), rnd.nextInt(192));
            sh.setLocalMatrix(m);
            p.setShader(sh);
            p.setAlpha((int) (255 * Math.min(1f, strength)));
            c.drawRect(0, 0, w, h, p);
            postInvalidateDelayed(55);
        }
    }

    // ---------------------------------------------------------------------------------- Contact-sheet strip
    public static class Strip extends View {
        public interface OnPick { void onPick(int i); }
        public OnPick listener;
        private final Bitmap[] thumbs = new Bitmap[3];
        private final String[] tags = new String[3];
        private int count;
        private String left = "", right = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        public Strip(Context c) { super(c); setClickable(true); }
        public void set(int n, String l, String r) { count = n; left = l; right = r; invalidate(); }
        public void setThumb(int i, Bitmap b, String tag) { thumbs[i] = b; tags[i] = tag; invalidate(); }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), dpi(getContext(), 182)); }
        private RectF cell(int i) {
            Context ctx = getContext();
            float pad = dp(ctx, 8), gap = dp(ctx, 6), band = dp(ctx, 16);
            float cw = (getWidth() - 2 * pad - 2 * gap) / 3f;
            float x = pad + i * (cw + gap);
            return new RectF(x, band, x + cw, band + dp(ctx, 128));
        }
        @Override protected void onDraw(Canvas c) {
            Context ctx = getContext();
            float w = getWidth(), band = dp(ctx, 16);
            p.setColor(FILM);
            c.drawRoundRect(new RectF(0, 0, w, getHeight()), dp(ctx, 6), dp(ctx, 6), p);
            sprockets(c, p, ctx, 0, w, 0, band);
            sprockets(c, p, ctx, 0, w, band + dp(ctx, 128), band);
            for (int i = 0; i < 3; i++) {
                RectF r = cell(i);
                if (i >= count) continue;
                p.setColor(0xFF1E1C19);
                c.drawRect(r, p);
                Bitmap b = thumbs[i];
                if (b != null && !b.isRecycled()) {
                    float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                    float sw = r.width() / s, shh = r.height() / s;
                    Rect src = new Rect((int) ((b.getWidth() - sw) / 2), (int) ((b.getHeight() - shh) / 2),
                            (int) ((b.getWidth() + sw) / 2), (int) ((b.getHeight() + shh) / 2));
                    c.drawBitmap(b, src, r, p);
                }
                if (tags[i] != null) {
                    p.setTypeface(DOT); p.setTextSize(sp(ctx, 9)); p.setTextAlign(Paint.Align.LEFT);
                    float tw = p.measureText(tags[i]);
                    p.setColor(ACC);
                    c.drawRoundRect(new RectF(r.right - tw - dp(ctx, 12), r.top + dp(ctx, 4), r.right - dp(ctx, 4), r.top + dp(ctx, 18)), dp(ctx, 3), dp(ctx, 3), p);
                    p.setColor(INK);
                    centerText(c, p, tags[i], r.right - tw - dp(ctx, 8), r.top + dp(ctx, 11));
                }
            }
            p.setTypeface(DOT); p.setTextSize(sp(ctx, 10)); p.setColor(EDGE);
            float ty = getHeight() - dp(ctx, 7);
            p.setTextAlign(Paint.Align.LEFT); c.drawText(left, dp(ctx, 10), ty, p);
            p.setTextAlign(Paint.Align.RIGHT); c.drawText(right, w - dp(ctx, 10), ty, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                for (int i = 0; i < count; i++) if (cell(i).contains(e.getX(), e.getY())) { performClick(); listener.onPick(i); }
            }
            return true;
        }
    }
}
