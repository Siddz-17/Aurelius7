package com.aurelius7.wiggle;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener,
        CameraRig.Callback, SweepRecorder.Listener {

    private static final int ST_PREVIEW = 0, ST_CAPTURE = 1, ST_PROCESS = 2, ST_RESULT = 3;
    private static final int[] SEQ_BOUNCE = {0, 1, 2, 3, 2, 1}, SEQ_FWD = {0, 1, 2, 3};
    private static final String[] DEPTH_NAMES = {"LO", "MID", "HI"};
    private static final float[] DEPTH_FACTORS = {0.014f, 0.02f, 0.028f};
    private static final float[] ASPECTS = {9f / 16f, 4f / 5f, 1f};
    private static final String[] FLASH_NAMES = {"OFF", "AUTO", "ON"};
    private static final float[] GRAIN_LEVELS = {0f, 0.6f, 1f, 1.6f};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String VERSION = "v?";
    private int versionCode;
    private int state = ST_PREVIEW;

    private FrameLayout root;
    private View welcomeScreen, cameraScreen, resultScreen, galleryScreen, settingsScreen, currentScreen;

    // camera screen
    private TextureView texture;
    private W.GrainView grainLive;
    private W.Brackets brackets;
    private W.Pill flashPill, expPill, infoPill, updatePill;
    private final W.Pill[] camStocks = new W.Pill[5];
    private LinearLayout camStockRow;
    private W.SweepHud hud;
    private W.ModeDial dial;
    private W.Shutter shutter;
    private W.Knob depthKnob;
    private W.Thumb thumb;
    private TextView camHint;

    // result screen
    private W.FilmStage stage;
    private TextView resSub, grainVal, speedVal;
    private final W.Pill[] resStocks = new W.Pill[5];
    private W.Slider grainSlider, speedSlider;
    private W.Segmented ratioSeg, formatSeg;
    private W.Pill saveBtn, shareBtn, retakeBtn;

    // gallery + settings
    private LinearLayout stripsCol;
    private TextView galCount, galEmpty, updLabel;
    private final W.Pill[] galFilters = new W.Pill[3];
    private int galFilter;
    private List<Item> galleryItems = new ArrayList<Item>();

    // camera state
    private CameraRig cam;
    private boolean resumed, surfaceReady, cameraStarted, torchOn;
    private float scaleX = 1, scaleY = 1, contentAspect = 9f / 16f;
    private SweepRecorder recorder;
    private int lastLens;
    private float shotU = 0.5f, shotV = 0.5f;
    private long captureStart;

    // settings / result state
    private int depthIdx, flashIdx, liveStock, resStock, grain, aspectIdx, frameMs = 111;
    private boolean orbit, mp4, dateStamp, haptics, bounce = true;
    private Composer composer;
    private Bitmap[] baseFrames, outFrames;
    private int[] seq = SEQ_BOUNCE;
    private int loopPos;
    private volatile int devGen;
    private Uri savedUri;
    private String savedMime;
    private volatile boolean saving, updating;
    private Updater.Info pendingUpdate;

    // ================================================================= setup
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        W.load(this);
        prefs = getSharedPreferences("aurelius7", MODE_PRIVATE);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            VERSION = "v" + pi.versionName; versionCode = pi.versionCode;
        } catch (Exception ignored) {}
        loadPrefs();
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(W.BG);
        w.setNavigationBarColor(W.BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(W.BG);
        root.setFitsSystemWindows(true);
        cameraScreen = buildCamera();
        resultScreen = buildResult();
        galleryScreen = buildGallery();
        settingsScreen = buildSettings();
        welcomeScreen = buildWelcome();
        View[] all = {cameraScreen, resultScreen, galleryScreen, settingsScreen, welcomeScreen};
        for (View v : all) { v.setVisibility(View.GONE); root.addView(v, new FrameLayout.LayoutParams(-1, -1)); }
        setContentView(root);

        applyLiveStock();
        setPreviewState();
        show(prefs.getBoolean("onboarded", false) ? cameraScreen : welcomeScreen);

        checkForUpdate();
        handleInstallIntent(getIntent());
        showUpdatedStampIfNeeded();
        loadGallery();
    }

    private void loadPrefs() {
        orbit = prefs.getInt("mode", 0) == 1;
        depthIdx = prefs.getInt("depth", 1);
        flashIdx = prefs.getInt("flash", 0);
        liveStock = prefs.getInt("stock", 1);
        dateStamp = prefs.getBoolean("date", true);
        mp4 = prefs.getInt("format", 0) == 1;
        bounce = prefs.getInt("loop", 0) == 0;
        haptics = prefs.getBoolean("haptics", true);
        seq = bounce ? SEQ_BOUNCE : SEQ_FWD;
    }

    private void put(String k, int v) { prefs.edit().putInt(k, v).apply(); }
    private void put(String k, boolean v) { prefs.edit().putBoolean(k, v).apply(); }

    private int defaultGrain(int stock) {
        return Math.min(100, Math.round(Develop.STOCK_GRAIN[stock] * GRAIN_LEVELS[prefs.getInt("grainLevel", 2)]));
    }

    private int dp(float v) { return W.dpi(this, v); }

    private void show(View screen) {
        if (currentScreen != null) currentScreen.setVisibility(View.GONE);
        currentScreen = screen;
        screen.setVisibility(View.VISIBLE);
        if (screen == galleryScreen) loadGallery();
    }

    private TextView text(String t, float sp, int color, android.graphics.Typeface tf) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(sp); tv.setTextColor(color); tv.setTypeface(tf);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private W.Pill pill(String t, int icon, int style, float sp, boolean dot) {
        W.Pill p = new W.Pill(this, t, icon, style, sp, W.UI_SEMI);
        if (dot) p.dotFont();
        return p;
    }

    private W.Pill bigPill(String t, int style) { return new W.Pill(this, t, W.IC_NONE, style, 16, W.HEAD); }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1)); return v; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams lpw(int h, float weight) { return new LinearLayout.LayoutParams(0, h, weight); }

    // ================================================================= WELCOME
    private View buildWelcome() {
        LinearLayout s = col();
        s.setBackgroundColor(W.BG);
        s.setPadding(dp(22), dp(26), dp(22), dp(26));
        s.setClickable(true);

        LinearLayout top = row();
        top.addView(text("3D WIGGLE CAMERA", 12, W.MUT, W.DOT));
        top.addView(spacer());
        top.addView(text(VERSION.toUpperCase(Locale.US), 12, W.ACC, W.DOT));
        s.addView(top);

        TextView title = text("", 50, W.TXT, W.HEAD);
        title.setLetterSpacing(-0.03f);
        android.text.SpannableString sp = new android.text.SpannableString("Aurelius7");
        sp.setSpan(new android.text.style.ForegroundColorSpan(W.ACC), 8, 9, 0);
        title.setText(sp);
        LinearLayout.LayoutParams tlp = lp(-2, -2); tlp.topMargin = dp(28);
        s.addView(title, tlp);
        TextView tag = text("One tap, four views, a loop that steps out of the screen.", 15, W.MUT, W.UI);
        tag.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams glp = lp(-1, -2); glp.topMargin = dp(12);
        s.addView(tag, glp);

        View film = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            int lit = 0;
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), band = W.dp(getContext(), 16), pad = W.dp(getContext(), 8), gap = W.dp(getContext(), 6);
                p.setColor(W.FILM); c.drawRoundRect(new RectF(0, 0, w, h), W.dp(getContext(), 6), W.dp(getContext(), 6), p);
                W.sprockets(c, p, getContext(), 0, w, 0, band);
                float fh = h - 2 * band - W.dp(getContext(), 16);
                W.sprockets(c, p, getContext(), 0, w, band + fh, band);
                float fw = (w - 2 * pad - 3 * gap) / 4f;
                for (int k = 0; k < 4; k++) {
                    float x = pad + k * (fw + gap), y = band;
                    c.save(); c.clipRect(x, y, x + fw, y + fh);
                    p.setColor(0xFF6B5747); c.drawRect(x, y, x + fw, y + fh, p);
                    p.setColor(0xFF4A3123); c.drawRect(x, y + fh * 0.62f, x + fw, y + fh, p);
                    float sh = (k - 1.5f) * W.dp(getContext(), 3);
                    p.setColor(0xFFE9B949); c.drawRect(x + fw * 0.62f + sh, y + fh * 0.12f, x + fw * 0.9f + sh, y + fh * 0.36f, p);
                    p.setColor(0xFFE9E1D3); c.drawRoundRect(new RectF(x + fw * 0.36f, y + fh * 0.36f, x + fw * 0.64f, y + fh * 0.82f), 8, 8, p);
                    p.setColor(0xFF1F6F8B); c.drawCircle(x + fw * 0.2f - sh * 1.4f, y + fh * 0.8f, fw * 0.16f, p);
                    c.restore();
                    if (k == lit) {
                        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(W.dp(getContext(), 2)); p.setColor(W.ACC);
                        c.drawRect(x + 1, y + 1, x + fw - 1, y + fh - 1, p); p.setStyle(Paint.Style.FILL);
                    }
                    p.setTypeface(W.DOT); p.setTextSize(W.sp(getContext(), 10)); p.setColor(W.EDGE); p.setTextAlign(Paint.Align.LEFT);
                    c.drawText(String.valueOf(k + 1), x + 5, y + fh - 5, p);
                }
                p.setTypeface(W.DOT); p.setTextSize(W.sp(getContext(), 10)); p.setColor(W.EDGE);
                p.setTextAlign(Paint.Align.LEFT); c.drawText("AURELIUS7 400", W.dp(getContext(), 10), h - W.dp(getContext(), 6), p);
                p.setTextAlign(Paint.Align.RIGHT); c.drawText("▸1 ▸2 ▸3 ▸4", w - W.dp(getContext(), 10), h - W.dp(getContext(), 6), p);
                lit = (lit + 1) % 4;
                if (getVisibility() == VISIBLE) postInvalidateDelayed(260);
            }
        };
        LinearLayout.LayoutParams flp = lp(-1, dp(168)); flp.topMargin = dp(24);
        s.addView(film, flp);

        String[][] steps = {{"01", "Aim", "Tap the thing that should stay still."},
                {"02", "Slide or orbit", "Move a few cm sideways, or curve around it."},
                {"03", "Develop", "Pick a film stock, add grain, export a loop."}};
        LinearLayout list = col();
        LinearLayout.LayoutParams slp0 = lp(-1, -2); slp0.topMargin = dp(18);
        s.addView(list, slp0);
        for (String[] st : steps) {
            View rule = new View(this); rule.setBackgroundColor(W.LINE);
            list.addView(rule, lp(-1, dp(1)));
            LinearLayout r = row();
            r.setGravity(Gravity.TOP);
            r.setPadding(0, dp(13), 0, dp(13));
            r.addView(text(st[0], 15, W.ACC, W.DOT), lp(dp(36), -2));
            LinearLayout c = col();
            c.addView(text(st[1], 17, W.TXT, W.HEAD));
            LinearLayout.LayoutParams dl = lp(-2, -2); dl.topMargin = dp(4);
            c.addView(text(st[2], 13, W.MUT, W.UI), dl);
            r.addView(c);
            list.addView(r);
        }
        s.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        W.Pill go = bigPill("Load film", W.Pill.SOLID);
        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            put("onboarded", true); show(cameraScreen); }});
        s.addView(go, lp(-1, dp(56)));
        return s;
    }

    // ================================================================= CAMERA
    private View buildCamera() {
        LinearLayout s = col();
        s.setBackgroundColor(W.BG);
        s.setClickable(true);

        FrameLayout vp = new FrameLayout(this);
        vp.setBackgroundColor(0xFF000000);
        vp.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                float r = W.dp(v.getContext(), 26);
                o.setRoundRect(0, (int) -r, v.getWidth(), v.getHeight(), r);
            }
        });
        vp.setClipToOutline(true);
        s.addView(vp, new LinearLayout.LayoutParams(-1, 0, 1));

        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        vp.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        grainLive = new W.GrainView(this);
        vp.addView(grainLive, new FrameLayout.LayoutParams(-1, -1));
        brackets = new W.Brackets(this);
        vp.addView(brackets, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = row();
        top.setPadding(dp(14), dp(14), dp(14), 0);
        flashPill = pill("OFF", W.IC_FLASH, W.Pill.GLASS_STYLE, 13, false);
        flashPill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (state != ST_PREVIEW) return;
            if (cam != null && cameraStarted && !cam.hasFlash) { flashInfo("No flash on this camera"); return; }
            flashIdx = (flashIdx + 1) % 3; put("flash", flashIdx); updateFlashPill();
            if (flashIdx == 2) flashInfo("Light stays on during the sweep");
            else if (flashIdx == 1) flashInfo("Light turns on when it's dark");
        }});
        top.addView(flashPill);
        expPill = pill("EXP 00", W.IC_NONE, W.Pill.GLASS_STYLE, 13, true);
        expPill.setTextColorOverride(W.ACC);
        expPill.setClickable(false);
        LinearLayout.LayoutParams elp = lp(-2, -2); elp.leftMargin = dp(8);
        top.addView(expPill, elp);
        top.addView(spacer());
        W.IconBtn gear = new W.IconBtn(this, W.IC_GEAR, "Settings");
        gear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (state == ST_PREVIEW) show(settingsScreen); } });
        top.addView(gear, lp(dp(44), dp(44)));
        vp.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        infoPill = pill("", W.IC_NONE, W.Pill.SOLID, 11, false);
        infoPill.setClickable(false);
        infoPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ilp.topMargin = dp(66);
        vp.addView(infoPill, ilp);

        updatePill = pill("", W.IC_NONE, W.Pill.SOLID, 13, false);
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startUpdate(); } });
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ulp.topMargin = dp(102);
        vp.addView(updatePill, ulp);

        camStockRow = row();
        camStockRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            camStocks[i] = pill(Develop.STOCKS[i], W.IC_NONE, W.Pill.GLASS_STYLE, 12, true);
            camStocks[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                liveStock = idx; put("stock", idx); applyLiveStock(); }});
            LinearLayout.LayoutParams p = lp(-2, dp(34)); if (i > 0) p.leftMargin = dp(6);
            camStockRow.addView(camStocks[i], p);
        }
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.bottomMargin = dp(14);
        vp.addView(camStockRow, slp);

        hud = new W.SweepHud(this);
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hlp.bottomMargin = dp(10);
        vp.addView(hud, hlp);

        LinearLayout ctr = col();
        ctr.setPadding(dp(20), dp(14), dp(20), dp(18));
        dial = new W.ModeDial(this);
        dial.setOrbit(orbit);
        dial.listener = new W.ModeDial.OnMode() { @Override public void onMode(boolean o) {
            orbit = o; put("mode", o ? 1 : 0); updateCamHint(); }};
        ctr.addView(dial, lp(-1, dp(52)));

        LinearLayout r = row();
        thumb = new W.Thumb(this);
        thumb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (state != ST_PREVIEW) return;
            if (composer != null && outFrames != null && !prefs.getBoolean("thumbToGallery", false)) { reopenLast(); return; }
            show(galleryScreen);
        }});
        r.addView(thumb, lp(dp(54), dp(54)));
        r.addView(spacer());
        shutter = new W.Shutter(this);
        shutter.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (state == ST_PREVIEW) startSweep();
            else if (state == ST_CAPTURE && recorder != null) recorder.finishNow();
        }});
        r.addView(shutter, lp(dp(84), dp(84)));
        r.addView(spacer());
        depthKnob = new W.Knob(this, "DEPTH");
        depthKnob.setValue(DEPTH_NAMES[depthIdx]);
        depthKnob.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (state != ST_PREVIEW) return;
            depthIdx = (depthIdx + 1) % 3; put("depth", depthIdx); depthKnob.setValue(DEPTH_NAMES[depthIdx]); }});
        r.addView(depthKnob, lp(dp(54), dp(54)));
        LinearLayout.LayoutParams rlp = lp(-1, -2); rlp.topMargin = dp(10);
        ctr.addView(r, rlp);

        camHint = text("", 12, W.MUT, W.UI);
        camHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = lp(-1, -2); hl.topMargin = dp(14);
        ctr.addView(camHint, hl);
        s.addView(ctr, lp(-1, -2));
        updateCamHint();
        return s;
    }

    private void updateCamHint() {
        camHint.setText(orbit ? "Curve around your subject · keep it in the brackets"
                : "Slide 3–5 cm sideways · don’t just turn");
    }

    private void updateFlashPill() {
        flashPill.setIcon(W.IC_FLASH);
        flashPill.setText(FLASH_NAMES[flashIdx]);
        flashPill.setSel(flashIdx == 2);
    }

    private final Runnable hideInfo = new Runnable() { @Override public void run() { infoPill.setVisibility(View.GONE); } };

    private void flashInfo(String t) {
        infoPill.setText(t);
        infoPill.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 2400);
    }

    private void applyLiveStock() {
        for (int i = 0; i < 5; i++) camStocks[i].setSel(i == liveStock);
        Paint lp = new Paint();
        lp.setColorFilter(new ColorMatrixColorFilter(Develop.matrix(liveStock)));
        texture.setLayerPaint(lp);
        grainLive.set(defaultGrain(liveStock) / 100f * 0.7f, Develop.hasLeak(liveStock));
    }

    private void setPreviewState() {
        state = ST_PREVIEW;
        stopLoop();
        stopTimer();
        brackets.show = true; brackets.setEnabled(true); brackets.invalidate();
        hud.setVisibility(View.GONE);
        camStockRow.setVisibility(View.VISIBLE);
        shutter.setState(W.Shutter.IDLE);
        shutter.setEnabled(cameraStarted);
        dial.setEnabled(true);
        updateExp();
        updateFlashPill();
    }

    private void updateExp() {
        expPill.setIcon(W.IC_NONE);
        int n = galleryItems.size();
        expPill.setText("EXP " + (n < 10 ? "0" : "") + n);
    }

    // ================================================================= RESULT ("Developed")
    private View buildResult() {
        LinearLayout s = col();
        s.setBackgroundColor(W.BG);
        s.setClickable(true);

        LinearLayout head = row();
        head.setPadding(dp(16), dp(14), dp(16), 0);
        W.IconBtn back = new W.IconBtn(this, W.IC_BACK, "Back to camera");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { retake(); } });
        head.addView(back, lp(dp(44), dp(44)));
        LinearLayout tc = col();
        tc.addView(text("Developed", 18, W.TXT, W.HEAD));
        resSub = text("", 11, W.MUT, W.DOT);
        LinearLayout.LayoutParams sl = lp(-2, -2); sl.topMargin = dp(3);
        tc.addView(resSub, sl);
        LinearLayout.LayoutParams tcl = lp(-2, -2); tcl.leftMargin = dp(12);
        head.addView(tc, tcl);
        s.addView(head);

        stage = new W.FilmStage(this);
        stage.listener = new W.FilmStage.OnFocusDrag() {
            @Override public void onFocusDrag(float bx, float by, boolean done) { if (done) refocus(bx, by); }
        };
        s.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView tip = text("Drag on the photo to choose what stays still", 11, W.MUT, W.UI);
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tipl = lp(-1, -2); tipl.bottomMargin = dp(10);
        s.addView(tip, tipl);

        LinearLayout panel = col();
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(W.SURF);
        float r = W.dp(this, 26);
        bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bgd);
        panel.setPadding(dp(16), dp(16), dp(16), dp(18));
        s.addView(panel, lp(-1, -2));

        LinearLayout stocks = row();
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            resStocks[i] = pill(Develop.STOCKS[i], W.IC_NONE, W.Pill.GHOST, 11, true);
            resStocks[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                resStock = idx; grain = defaultGrain(idx); syncResultControls(); markDirty(); develop(); }});
            LinearLayout.LayoutParams p = lpw(dp(34), 1); if (i > 0) p.leftMargin = dp(5);
            stocks.addView(resStocks[i], p);
        }
        panel.addView(stocks, lp(-1, -2));

        LinearLayout gr = row();
        gr.addView(text("Grain", 12, W.MUT, W.UI), lp(dp(64), -2));
        grainSlider = new W.Slider(this);
        grainSlider.setContentDescription("Grain");
        grainSlider.listener = new W.Slider.OnChange() { @Override public void onChange(float v, boolean done) {
            grain = Math.round(v * 100); grainVal.setText(String.valueOf(grain));
            if (done) { markDirty(); develop(); } }};
        gr.addView(grainSlider, lpw(dp(36), 1));
        grainVal = text("0", 12, W.TXT, W.DOT);
        grainVal.setGravity(Gravity.END);
        gr.addView(grainVal, lp(dp(46), -2));
        LinearLayout.LayoutParams grl = lp(-1, -2); grl.topMargin = dp(10);
        panel.addView(gr, grl);

        LinearLayout spr = row();
        spr.addView(text("Speed", 12, W.MUT, W.UI), lp(dp(64), -2));
        speedSlider = new W.Slider(this);
        speedSlider.setContentDescription("Speed");
        speedSlider.listener = new W.Slider.OnChange() { @Override public void onChange(float v, boolean done) {
            int fps = 4 + Math.round(v * 12); frameMs = 1000 / fps; speedVal.setText(fps + "FPS");
            if (done) markDirty(); }};
        spr.addView(speedSlider, lpw(dp(36), 1));
        speedVal = text("9FPS", 12, W.TXT, W.DOT);
        speedVal.setGravity(Gravity.END);
        spr.addView(speedVal, lp(dp(46), -2));
        panel.addView(spr, lp(-1, -2));

        LinearLayout segs = row();
        ratioSeg = new W.Segmented(this, new String[]{"9:16", "4:5", "1:1"}, 0, true);
        ratioSeg.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            aspectIdx = i;
            if (composer != null) { composer.aspect = ASPECTS[i]; baseFrames = composer.render(); syncStageFocus(); develop(); }
            markDirty(); }};
        segs.addView(ratioSeg, new LinearLayout.LayoutParams(0, -2, 1.4f));
        formatSeg = new W.Segmented(this, new String[]{"GIF", "MP4"}, mp4 ? 1 : 0, true);
        formatSeg.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { mp4 = i == 1; markDirty(); } };
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, -2, 1); fl.leftMargin = dp(8);
        segs.addView(formatSeg, fl);
        LinearLayout.LayoutParams sgl = lp(-1, -2); sgl.topMargin = dp(8);
        panel.addView(segs, sgl);

        LinearLayout btns = row();
        retakeBtn = bigPill("Retake", W.Pill.GHOST);
        saveBtn = bigPill("Save", W.Pill.GHOST);
        shareBtn = bigPill("Share", W.Pill.SOLID);
        btns.addView(retakeBtn, lpw(dp(56), 1));
        LinearLayout.LayoutParams b2 = lpw(dp(56), 1); b2.leftMargin = dp(10);
        btns.addView(saveBtn, b2);
        LinearLayout.LayoutParams b3 = lpw(dp(56), 1.2f); b3.leftMargin = dp(10);
        btns.addView(shareBtn, b3);
        retakeBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { retake(); } });
        saveBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(false); } });
        shareBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(true); } });
        LinearLayout.LayoutParams bl = lp(-1, -2); bl.topMargin = dp(16);
        panel.addView(btns, bl);
        return s;
    }

    private void syncResultControls() {
        for (int i = 0; i < 5; i++) resStocks[i].setSel(i == resStock);
        grainSlider.setValue(grain / 100f);
        grainVal.setText(String.valueOf(grain));
        int fps = Math.round(1000f / frameMs);
        speedSlider.setValue((fps - 4) / 12f);
        speedVal.setText(fps + "FPS");
        ratioSeg.setSel(aspectIdx);
        formatSeg.setSel(mp4 ? 1 : 0);
    }

    // ================================================================= GALLERY ("Contact sheet")
    private static class Item { Uri uri; String mime; long date; String stock; Bitmap thumb; }

    private View buildGallery() {
        LinearLayout s = col();
        s.setBackgroundColor(W.BG);
        s.setClickable(true);
        s.setPadding(dp(16), dp(14), dp(16), 0);
        LinearLayout head = row();
        W.IconBtn camBtn = new W.IconBtn(this, W.IC_CAM, "Back to camera");
        camBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { show(cameraScreen); } });
        head.addView(camBtn, lp(dp(44), dp(44)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1); tl.leftMargin = dp(12);
        head.addView(text("Contact sheet", 26, W.TXT, W.HEAD), tl);
        galCount = text("", 12, W.ACC, W.DOT);
        head.addView(galCount);
        s.addView(head);

        LinearLayout f = row();
        String[] fn = {"All", "GIF", "MP4"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            galFilters[i] = pill(fn[i], W.IC_NONE, W.Pill.GHOST, 13, false);
            galFilters[i].setSel(i == 0);
            galFilters[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                galFilter = idx; for (int k = 0; k < 3; k++) galFilters[k].setSel(k == idx); renderStrips(); }});
            LinearLayout.LayoutParams p = lp(-2, dp(36)); if (i > 0) p.leftMargin = dp(8);
            f.addView(galFilters[i], p);
        }
        LinearLayout.LayoutParams fl = lp(-1, -2); fl.topMargin = dp(16); fl.bottomMargin = dp(14);
        s.addView(f, fl);

        galEmpty = text("No exposures yet.\nShoot a wiggle and save it — it lands here.", 14, W.MUT, W.UI);
        galEmpty.setGravity(Gravity.CENTER);
        galEmpty.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams el = lp(-1, -2); el.topMargin = dp(60);
        s.addView(galEmpty, el);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        stripsCol = col();
        stripsCol.setPadding(0, 0, 0, dp(20));
        sv.addView(stripsCol, new FrameLayout.LayoutParams(-1, -2));
        s.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return s;
    }

    private void loadGallery() {
        final ContentResolver cr = getContentResolver();
        worker.execute(new Runnable() { @Override public void run() {
            final List<Item> items = new ArrayList<Item>();
            queryInto(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/Aurelius7%", items);
            queryInto(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/Aurelius7%", items);
            Collections.sort(items, new Comparator<Item>() { @Override public int compare(Item a, Item b) { return Long.compare(b.date, a.date); } });
            for (Item it : items) it.thumb = loadThumb(cr, it);
            ui.post(new Runnable() { @Override public void run() {
                galleryItems = items;
                if (!items.isEmpty() && items.get(0).thumb != null && outFrames == null) thumb.setBitmap(items.get(0).thumb);
                if (state == ST_PREVIEW) updateExp();
                renderStrips();
            }});
        }});
    }

    private void queryInto(ContentResolver cr, Uri coll, String pathLike, List<Item> out) {
        Cursor c = null;
        try {
            c = cr.query(coll, new String[]{"_id", "date_added", "mime_type", "_display_name"},
                    "relative_path LIKE ?", new String[]{pathLike}, null);
            if (c == null) return;
            while (c.moveToNext()) {
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(coll, c.getLong(0));
                it.date = c.getLong(1);
                it.mime = c.getString(2);
                String name = c.getString(3);
                it.stock = "";
                if (name != null) for (String st : Develop.STOCKS) if (name.contains("_" + st.replace("-", "") + ".")) it.stock = st;
                out.add(it);
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
    }

    private Bitmap loadThumb(ContentResolver cr, Item it) {
        try {
            Bitmap b;
            if (it.mime != null && it.mime.startsWith("video")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try { r.setDataSource(this, it.uri); b = r.getFrameAtTime(0); } finally { r.release(); }
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 3;
                InputStream in = cr.openInputStream(it.uri);
                try { b = BitmapFactory.decodeStream(in, null, o); } finally { if (in != null) in.close(); }
            }
            if (b == null) return null;
            float s = 260f / Math.max(b.getWidth(), b.getHeight());
            if (s < 1) {
                Bitmap sc = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
                if (sc != b) b.recycle();
                b = sc;
            }
            return b;
        } catch (Exception e) { return null; }
    }

    private void renderStrips() {
        stripsCol.removeAllViews();
        final List<Item> list = new ArrayList<Item>();
        for (Item it : galleryItems) {
            boolean vid = it.mime != null && it.mime.startsWith("video");
            if (galFilter == 0 || (galFilter == 1 && !vid) || (galFilter == 2 && vid)) list.add(it);
        }
        galCount.setText(galleryItems.size() + " EXP");
        galEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        SimpleDateFormat df = new SimpleDateFormat("MMM dd", Locale.US);
        for (int i = 0; i < list.size(); i += 3) {
            W.Strip st = new W.Strip(this);
            final int base = i;
            int n = Math.min(3, list.size() - i);
            Item first = list.get(i);
            String stock = first.stock.isEmpty() ? "AURELIUS7 400" : first.stock + " 400";
            st.set(n, stock, df.format(new Date(first.date * 1000)).toUpperCase(Locale.US));
            for (int k = 0; k < n; k++) {
                Item it = list.get(i + k);
                st.setThumb(k, it.thumb, it.mime != null && it.mime.startsWith("video") ? "MP4" : "GIF");
            }
            st.listener = new W.Strip.OnPick() { @Override public void onPick(int k) {
                Item it = list.get(base + k);
                Intent v = new Intent(Intent.ACTION_VIEW);
                v.setDataAndType(it.uri, it.mime);
                v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(v); } catch (Exception ignored) {}
            }};
            LinearLayout.LayoutParams p = lp(-1, -2); p.bottomMargin = dp(16);
            stripsCol.addView(st, p);
        }
    }

    // ================================================================= SETTINGS
    private View buildSettings() {
        LinearLayout outer = col();
        outer.setBackgroundColor(W.BG);
        outer.setClickable(true);
        outer.setPadding(dp(16), dp(14), dp(16), 0);
        LinearLayout head = row();
        W.IconBtn back = new W.IconBtn(this, W.IC_BACK, "Back to camera");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { show(cameraScreen); } });
        head.addView(back, lp(dp(44), dp(44)));
        LinearLayout.LayoutParams tl = lp(-2, -2); tl.leftMargin = dp(12);
        head.addView(text("Settings", 26, W.TXT, W.HEAD), tl);
        outer.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout s = col();
        s.setPadding(0, 0, 0, dp(24));
        sv.addView(s, new FrameLayout.LayoutParams(-1, -2));
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout g = group(s, "CAPTURE");
        W.Segmented mode = new W.Segmented(this, new String[]{"Slide", "Orbit"}, orbit ? 1 : 0, false);
        mode.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            orbit = i == 1; put("mode", i); dial.setOrbit(orbit); updateCamHint(); }};
        setRow(g, "Default mode", null, mode, false);
        W.Segmented dep = new W.Segmented(this, new String[]{"Lo", "Mid", "Hi"}, depthIdx, false);
        dep.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            depthIdx = i; put("depth", i); depthKnob.setValue(DEPTH_NAMES[i]); }};
        setRow(g, "Depth", "How far to move before it stops", dep, false);
        W.Toggle hap = new W.Toggle(this, haptics);
        hap.setContentDescription("Haptic ticks");
        hap.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { haptics = on; put("haptics", on); } };
        setRow(g, "Haptic ticks", "A tap for each of the 4 views", hap, true);

        g = group(s, "FLASH");
        W.Segmented fls = new W.Segmented(this, FLASH_NAMES, flashIdx, false);
        fls.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { flashIdx = i; put("flash", i); updateFlashPill(); } };
        setRow(g, "Flash", "In a sweep the light stays on the whole time", fls, true);

        g = group(s, "FILM");
        W.Segmented st = new W.Segmented(this, new String[]{"STD", "PORTRA", "TRI-X", "NIMSLO", "CINE"}, liveStock, true);
        st.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { liveStock = i; put("stock", i); applyLiveStock(); } };
        setRowStacked(g, "Default stock", st);
        W.Segmented gl = new W.Segmented(this, new String[]{"Off", "Fine", "Med", "Heavy"}, prefs.getInt("grainLevel", 2), false);
        gl.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { put("grainLevel", i); applyLiveStock(); } };
        setRow(g, "Grain", null, gl, false);
        W.Toggle ds = new W.Toggle(this, dateStamp);
        ds.setContentDescription("Date stamp");
        ds.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { dateStamp = on; put("date", on); } };
        setRow(g, "Date stamp", "Orange '26 11 13 in the corner", ds, true);

        g = group(s, "EXPORT");
        W.Segmented fm = new W.Segmented(this, new String[]{"GIF", "MP4"}, mp4 ? 1 : 0, false);
        fm.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { mp4 = i == 1; put("format", i); formatSeg.setSel(i); } };
        setRow(g, "Format", null, fm, false);
        W.Segmented lpp = new W.Segmented(this, new String[]{"Bounce", "Loop"}, bounce ? 0 : 1, false);
        lpp.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            bounce = i == 0; put("loop", i); seq = bounce ? SEQ_BOUNCE : SEQ_FWD; }};
        setRow(g, "Loop style", null, lpp, true);

        g = group(s, "APP");
        final W.Pill chk = pill("Check updates", W.IC_NONE, W.Pill.GHOST, 13, false);
        updLabel = text("", 12, W.MUT, W.UI);
        chk.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (pendingUpdate != null) { show(cameraScreen); startUpdate(); return; }
            updLabel.setText("Checking…");
            new Thread(new Runnable() { @Override public void run() {
                final Updater.Info info = Updater.check(versionCode);
                ui.post(new Runnable() { @Override public void run() {
                    if (info == null) updLabel.setText("Up to date");
                    else { onUpdateFound(info); updLabel.setText("v" + info.name + " available"); chk.setText("Install"); }
                }});
            }}).start();
        }});
        LinearLayout ar = setRow(g, "Aurelius7 " + VERSION, null, chk, true);
        LinearLayout.LayoutParams ul = lp(-2, -2); ul.topMargin = dp(3);
        ((LinearLayout) ar.getChildAt(0)).addView(updLabel, ul);
        return outer;
    }

    private LinearLayout group(LinearLayout parent, String title) {
        TextView t = text(title, 11, W.MUT, W.DOT);
        LinearLayout.LayoutParams tl = lp(-2, -2); tl.topMargin = dp(20); tl.bottomMargin = dp(8); tl.leftMargin = dp(4);
        parent.addView(t, tl);
        LinearLayout g = col();
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(W.SURF); gd.setCornerRadius(W.dp(this, 16));
        g.setBackground(gd);
        parent.addView(g, lp(-1, -2));
        return g;
    }

    private LinearLayout setRow(LinearLayout g, String title, String sub, View ctrl, boolean last) {
        LinearLayout r = row();
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout tc = col();
        tc.addView(text(title, 14, W.TXT, W.UI_SEMI));
        if (sub != null) {
            LinearLayout.LayoutParams sl = lp(-2, -2); sl.topMargin = dp(3);
            tc.addView(text(sub, 12, W.MUT, W.UI), sl);
        }
        r.addView(tc, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams cl = lp(-2, -2); cl.leftMargin = dp(10);
        r.addView(ctrl, cl);
        g.addView(r);
        if (!last) { View d = new View(this); d.setBackgroundColor(W.LINE); g.addView(d, lp(-1, 1)); }
        return r;
    }

    private void setRowStacked(LinearLayout g, String title, View ctrl) {
        LinearLayout r = col();
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        r.addView(text(title, 14, W.TXT, W.UI_SEMI));
        LinearLayout.LayoutParams cl = lp(-1, -2); cl.topMargin = dp(10);
        r.addView(ctrl, cl);
        g.addView(r);
        View d = new View(this); d.setBackgroundColor(W.LINE); g.addView(d, lp(-1, 1));
    }

    // ================================================================= lifecycle / camera
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
        } else maybeStartCamera();
        if (state == ST_RESULT) startLoop();
    }

    @Override protected void onPause() {
        resumed = false;
        stopLoop();
        stopTimer();
        if (state == ST_CAPTURE || state == ST_PROCESS) {
            if (recorder != null) recorder.release();
            recorder = null;
            setPreviewState();
        }
        if (cam != null) { cam.stop(); cam = null; }
        torchOn = false;
        cameraStarted = false;
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) maybeStartCamera();
        else flashInfo("Camera permission needed · enable it in Settings");
    }

    private void maybeStartCamera() {
        if (!resumed || !surfaceReady || cam != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cam = new CameraRig(this, texture, this);
        cam.start();
    }

    @Override public void onCameraReady(final int w, final int h, final int orient) {
        runOnUiThread(new Runnable() { @Override public void run() {
            cameraStarted = true;
            boolean swap = orient == 90 || orient == 270;
            contentAspect = swap ? h / (float) w : w / (float) h;
            applyTransform();
            if (state == ST_PREVIEW) shutter.setEnabled(true);
        }});
    }

    private void applyTransform() {
        int vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0) return;
        float va = vw / (float) vh;
        if (va < contentAspect) { scaleX = (vh * contentAspect) / vw; scaleY = 1; }
        else { scaleX = 1; scaleY = (vw / contentAspect) / vh; }
        Matrix m = new Matrix();
        m.setScale(scaleX, scaleY, vw / 2f, vh / 2f);
        texture.setTransform(m);
    }

    @Override public void onCameraError(final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() { flashInfo(msg); }});
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { surfaceReady = true; maybeStartCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { applyTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { surfaceReady = false; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    // ================================================================= capture
    private float[] focusContent() {
        float cu = 0.5f + (brackets.u - 0.5f) / scaleX;
        float cv = 0.5f + (brackets.v - 0.5f) / scaleY;
        return new float[]{Math.max(0.05f, Math.min(0.95f, cu)), Math.max(0.05f, Math.min(0.95f, cv))};
    }

    private float[] contentToSensor(float u, float v) {
        int Wd = cam.W, H = cam.H;
        switch (cam.sensorOrientation) {
            case 90:  return new float[]{v * Wd, (1 - u) * H};
            case 270: return new float[]{(1 - v) * Wd, u * H};
            case 180: return new float[]{(1 - u) * Wd, (1 - v) * H};
            default:  return new float[]{u * Wd, v * H};
        }
    }

    private boolean sceneIsDark() {
        try {
            Bitmap b = texture.getBitmap(32, 18);
            if (b == null) return false;
            int[] px = new int[32 * 18];
            b.getPixels(px, 0, 32, 0, 0, 32, 18);
            long s = 0;
            for (int c : px) s += (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
            b.recycle();
            return s / px.length < 55;
        } catch (Exception e) { return false; }
    }

    private void startSweep() {
        if (state != ST_PREVIEW || cam == null || !cameraStarted) return;
        state = ST_CAPTURE;
        float[] fc = focusContent();
        shotU = fc[0]; shotV = fc[1];
        float[] fs = contentToSensor(shotU, shotV);
        recorder = new SweepRecorder(cam.W, cam.H, fs[0], fs[1], DEPTH_FACTORS[depthIdx], this);
        torchOn = cam.hasFlash && (flashIdx == 2 || (flashIdx == 1 && sceneIsDark()));
        brackets.setEnabled(false);
        dial.setEnabled(false);
        camStockRow.setVisibility(View.GONE);
        shutter.setState(W.Shutter.REC_STATE);
        if (torchOn) { flashPill.setText("LIGHT"); flashPill.setSel(true); }
        lastLens = 1;
        hud.set(0, orbit ? "Keep curving →" : "Keep sliding →", false, orbit);
        hud.setVisibility(View.VISIBLE);
        buzz(25);
        captureStart = SystemClock.uptimeMillis();
        startTimer();
        final SweepRecorder r = recorder;
        if (torchOn) cam.setTorch(true);
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (state == ST_CAPTURE && recorder == r && cam != null) cam.lock();
        }}, torchOn ? 420 : 0);
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (state == ST_CAPTURE && recorder == r && cam != null) cam.sink = r;
        }}, torchOn ? 600 : 180);
    }

    private final Runnable timerTick = new Runnable() { @Override public void run() {
        if (state != ST_CAPTURE) return;
        long ms = SystemClock.uptimeMillis() - captureStart;
        expPill.setIcon(W.IC_DOT);
        expPill.setText(String.format(Locale.US, "00:%02d.%d", ms / 1000, (ms / 100) % 10));
        ui.postDelayed(this, 100);
    }};
    private void startTimer() { ui.removeCallbacks(timerTick); ui.post(timerTick); }
    private void stopTimer() { ui.removeCallbacks(timerTick); }

    @Override public void onSweepProgress(final float progress, final int coach, final float parallax) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (state != ST_CAPTURE) return;
            String t; boolean warn = false;
            switch (coach) {
                case SweepRecorder.COACH_TURNING: t = "Move, don’t just turn"; warn = true; break;
                case SweepRecorder.COACH_FAST: t = "Slower"; warn = true; break;
                case SweepRecorder.COACH_EDGE: t = "Keep it in frame"; warn = true; break;
                default: t = orbit ? "Keep curving →" : "Keep sliding →";
            }
            hud.set(progress, t, warn, orbit);
            int lens = hud.lensCount();
            if (lens > lastLens) { lastLens = lens; buzz(18); }
        }});
    }

    @Override public void onSweepDone(final SweepRecorder r) {
        if (cam != null) cam.sink = null;
        runOnUiThread(new Runnable() { @Override public void run() {
            if (state != ST_CAPTURE || recorder != r) return;
            if (cam != null) { cam.unlock(); if (torchOn) cam.setTorch(false); }
            torchOn = false;
            buzz(45);
            stopTimer();
            state = ST_PROCESS;
            hud.set(1, "Developing…", false, orbit);
            shutter.setState(W.Shutter.BUSY);
            final int orient = cam != null ? cam.sensorOrientation : 90;
            final float u = shotU, v = shotV;
            final float targetAspect = ASPECTS[aspectIdx];
            final int stock = liveStock, gr = defaultGrain(liveStock);
            final boolean stamp = dateStamp;
            new Thread(new Runnable() { @Override public void run() {
                String err = null; Composer comp = null; Bitmap[] base = null, dev = null;
                boolean low = r.lowDepth;
                try {
                    WiggleBuilder.Result res = WiggleBuilder.build(r, orient, 720);
                    if (res.error != null) err = res.error;
                    else {
                        comp = new Composer(res.frames, res.seeds);
                        comp.parallaxPx = res.parallaxPx;
                        comp.sweepFrames = res.sweepFrames;
                        comp.aspect = targetAspect;
                        comp.align(u, v);
                        base = comp.render();
                        dev = Develop.apply(base, stock, gr, stamp, W.DOT);
                    }
                } catch (Throwable t) {
                    err = "Build failed: " + t.getClass().getSimpleName();
                }
                r.release();
                final String fe = err; final Composer fc = comp; final Bitmap[] fb = base, fd = dev; final boolean fl = low;
                runOnUiThread(new Runnable() { @Override public void run() {
                    recorder = null;
                    if (state != ST_PROCESS) return;
                    if (fe != null) { setPreviewState(); flashInfo(fe); return; }
                    composer = fc; baseFrames = fb; outFrames = fd;
                    resStock = stock; grain = gr;
                    thumb.setBitmap(fd[0]);
                    showResult(fl);
                }});
            }}, "aurelius-build").start();
        }});
    }

    // ================================================================= result
    private void showResult(boolean lowDepth) {
        state = ST_RESULT;
        resetCameraChrome();
        String sub = "4 VIEWS · " + Math.round(composer.parallaxPx) + "PX DEPTH · " + (orbit ? "ORBIT" : "SLIDE");
        if (lowDepth) sub = "LOW DEPTH · GET CLOSER";
        resSub.setText(sub);
        syncResultControls();
        markDirty();
        syncStageFocus();
        show(resultScreen);
        startLoop();
    }

    private void reopenLast() {
        if (composer == null || outFrames == null) { show(galleryScreen); return; }
        showResult(false);
    }

    private void resetCameraChrome() {
        stopTimer();
        hud.setVisibility(View.GONE);
        camStockRow.setVisibility(View.VISIBLE);
        shutter.setState(W.Shutter.IDLE);
        brackets.setEnabled(true);
        dial.setEnabled(true);
        updateFlashPill();
        updateExp();
    }

    private void syncStageFocus() {
        if (composer == null) return;
        stage.focusBx = composer.fu * composer.W - composer.cropX;
        stage.focusBy = composer.fv * composer.H - composer.cropY;
        stage.invalidate();
    }

    private void refocus(float bx, float by) {
        if (composer == null || state != ST_RESULT) return;
        float u = (composer.cropX + bx) / composer.W, v = (composer.cropY + by) / composer.H;
        composer.align(u, v);
        baseFrames = composer.render();
        syncStageFocus();
        markDirty();
        develop();
    }

    private void develop() {
        if (baseFrames == null) return;
        final int gen = ++devGen;
        final Bitmap[] base = baseFrames;
        final int stock = resStock, gr = grain;
        final boolean stamp = dateStamp;
        worker.execute(new Runnable() { @Override public void run() {
            if (gen != devGen) return;
            final Bitmap[] dev = Develop.apply(base, stock, gr, stamp, W.DOT);
            ui.post(new Runnable() { @Override public void run() {
                if (gen != devGen) return;
                outFrames = dev;
                if (state == ST_RESULT) stage.setFrame(outFrames[seq[loopPos % seq.length]]);
            }});
        }});
    }

    private final Runnable loopTick = new Runnable() { @Override public void run() {
        if (outFrames == null || state != ST_RESULT) return;
        stage.setFrame(outFrames[seq[loopPos % seq.length]]);
        loopPos++;
        ui.postDelayed(this, frameMs);
    }};
    private void startLoop() { ui.removeCallbacks(loopTick); loopPos = 0; ui.post(loopTick); }
    private void stopLoop() { ui.removeCallbacks(loopTick); }

    private void markDirty() {
        savedUri = null;
        if (saveBtn != null && !saving) { saveBtn.setText("Save"); saveBtn.setEnabled(true); }
    }

    private void retake() {
        if (saving) return;
        setPreviewState();
        show(cameraScreen);
    }

    @Override public void onBackPressed() {
        if (currentScreen == resultScreen) { retake(); return; }
        if (currentScreen == galleryScreen || currentScreen == settingsScreen) { show(cameraScreen); return; }
        if (state == ST_CAPTURE && recorder != null) { recorder.finishNow(); return; }
        super.onBackPressed();
    }

    // ================================================================= save / share
    private void save(final boolean thenShare) {
        if (outFrames == null || saving) return;
        if (savedUri != null) { if (thenShare) share(savedUri, savedMime); return; }
        saving = true;
        saveBtn.setEnabled(false); shareBtn.setEnabled(false); retakeBtn.setEnabled(false);
        saveBtn.setText(mp4 ? "MP4…" : "GIF…");
        final Bitmap[] frames = outFrames;
        final int[] sq = seq;
        final int fms = frameMs;
        final boolean asMp4 = mp4;
        final String stockTag = Develop.STOCKS[resStock].replace("-", "");
        new Thread(new Runnable() { @Override public void run() {
            Uri uri = null; String err = null;
            ContentResolver cr = getContentResolver();
            String stampName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File tmp = null;
            try {
                ContentValues cv = new ContentValues();
                Uri coll;
                if (asMp4) {
                    tmp = new File(getCacheDir(), "wiggle_" + stampName + ".mp4");
                    Mp4Encoder.encode(frames, sq, fms, 4000, tmp);
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "AURELIUS7_" + stampName + "_" + stockTag + ".mp4");
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                    cv.put("relative_path", "Movies/Aurelius7");
                    coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else {
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "AURELIUS7_" + stampName + "_" + stockTag + ".gif");
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/gif");
                    cv.put("relative_path", "Pictures/Aurelius7");
                    coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                cv.put("is_pending", 1);
                uri = cr.insert(coll, cv);
                if (uri == null) throw new RuntimeException("MediaStore insert failed");
                OutputStream os = cr.openOutputStream(uri);
                try {
                    if (asMp4) {
                        InputStream in = new FileInputStream(tmp);
                        try { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n); }
                        finally { in.close(); }
                    } else {
                        BufferedOutputStream bo = new BufferedOutputStream(os, 1 << 16);
                        GifEncoder.encode(frames, sq, Math.max(2, fms / 10), bo);
                        bo.flush();
                    }
                } finally { os.close(); }
                ContentValues done = new ContentValues();
                done.put("is_pending", 0);
                cr.update(uri, done, null, null);
            } catch (Throwable t) {
                err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                if (uri != null) { try { cr.delete(uri, null, null); } catch (Exception ignored) {} }
                uri = null;
            } finally {
                if (tmp != null) tmp.delete();
            }
            final Uri fu = uri; final String fe = err;
            final String mime = asMp4 ? "video/mp4" : "image/gif";
            runOnUiThread(new Runnable() { @Override public void run() {
                saving = false;
                retakeBtn.setEnabled(true); shareBtn.setEnabled(true); saveBtn.setEnabled(true);
                if (fu == null) { saveBtn.setText("Save"); resSub.setText("SAVE FAILED: " + fe); return; }
                savedUri = fu; savedMime = mime;
                saveBtn.setText("Saved ✓");
                showStamp("SAVED");
                buzz(25);
                loadGallery();
                if (thenShare) share(fu, mime);
            }});
        }}, "aurelius-export").start();
    }

    private void share(Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share wiggle"));
    }

    private void showStamp(String t) {
        final TextView st = text(t, 34, W.EDGE, W.DOT);
        st.setShadowLayer(W.dp(this, 10), 0, 0, 0xCCE8873A);
        st.setRotation(-6);
        st.setGravity(Gravity.CENTER);
        root.addView(st, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        st.setScaleX(1.6f); st.setScaleY(1.6f); st.setAlpha(0f);
        st.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start();
        ui.postDelayed(new Runnable() { @Override public void run() {
            st.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override public void run() { root.removeView(st); } }).start();
        }}, 900);
    }

    private void buzz(int ms) {
        if (!haptics) return;
        try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.vibrate(ms); } catch (Exception ignored) {}
    }

    // ================================================================= self-update
    private void showUpdatedStampIfNeeded() {
        try {
            int seen = prefs.getInt("seenVersion", 0);
            PackageInfo pk = getPackageManager().getPackageInfo(getPackageName(), 0);
            boolean wasUpdated = pk.lastUpdateTime > pk.firstInstallTime;
            if (seen < versionCode && wasUpdated) {
                ui.postDelayed(new Runnable() { @Override public void run() { showStamp("UPDATED " + VERSION.toUpperCase(Locale.US)); } }, 700);
            }
            prefs.edit().putInt("seenVersion", versionCode).apply();
        } catch (Exception ignored) {}
    }

    private void checkForUpdate() {
        new Thread(new Runnable() { @Override public void run() {
            final Updater.Info info = Updater.check(versionCode);
            if (info == null) return;
            ui.post(new Runnable() { @Override public void run() { onUpdateFound(info); } });
        }}, "aurelius-update-check").start();
    }

    private void onUpdateFound(Updater.Info info) {
        pendingUpdate = info;
        updatePill.setText("Update → v" + info.name);
        updatePill.setVisibility(View.VISIBLE);
    }

    private void startUpdate() {
        if (pendingUpdate == null || updating) return;
        updating = true;
        updatePill.setEnabled(false);
        updatePill.setText("Downloading 0%");
        final Updater.Info info = pendingUpdate;
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            try {
                Updater.install(MainActivity.this, info, new Updater.Progress() {
                    @Override public void onProgress(final int pct) {
                        runOnUiThread(new Runnable() { @Override public void run() { updatePill.setText("Downloading " + pct + "%"); }});
                    }
                });
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String fe = err;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (fe != null) { updating = false; updatePill.setEnabled(true); updatePill.setText("Retry update"); }
                else updatePill.setText("Installing…");
            }});
        }}, "aurelius-update").start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleInstallIntent(intent);
    }

    private void handleInstallIntent(Intent intent) {
        if (intent == null || !Updater.ACTION_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(confirm); } catch (Exception ignored) {}
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            updating = false;
            updatePill.setEnabled(true);
            updatePill.setText("Install cancelled · retry");
        }
    }
}
