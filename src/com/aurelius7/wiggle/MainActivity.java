package com.aurelius7.wiggle;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.FaceDetector;
import android.view.KeyEvent;
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
        CameraRig.Callback, SweepRecorder.Listener, SensorEventListener {

    public static final String ACTION_CAMERA = "com.aurelius7.wiggle.CAMERA";

    private static final int ST_PREVIEW = 0, ST_CAPTURE = 1, ST_PROCESS = 2, ST_RESULT = 3;
    private static final int[] SEQ_BOUNCE = {0, 1, 2, 3, 2, 1}, SEQ_FWD = {0, 1, 2, 3};
    private static final String[] DEPTH_NAMES = {"LO", "MID", "HI"};
    private static final float[] DEPTH_FACTORS = {0.014f, 0.02f, 0.028f};
    private static final float[] ASPECTS = {9f / 16f, 4f / 5f, 1f};
    private static final String[] FLASH_NAMES = {"OFF", "AUTO", "ON"};
    private static final float[] GRAIN_LEVELS = {0f, 0.6f, 1f, 1.6f};

    final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    SharedPreferences prefs;
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
    private final W.Pill[] camStocks = new W.Pill[Develop.STOCKS.length];
    private final W.Pill[] setStocks = new W.Pill[Develop.STOCKS.length];
    private android.widget.HorizontalScrollView resStockScroll, setStockScroll;
    private android.widget.HorizontalScrollView camStockRow;
    private W.SweepHud hud;
    private W.ModeDial dial;
    private W.Shutter shutter;
    private W.Knob depthKnob;
    private W.Thumb thumb;
    private TextView camHint;

    // result screen
    private W.FilmStage stage;
    private TextView resSub, grainVal, speedVal;
    private final W.Pill[] resStocks = new W.Pill[Develop.STOCKS.length];
    private W.Slider grainSlider, speedSlider, strengthSlider;
    private TextView strengthVal;
    private int strength = 100;
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

    // v0.5
    private SoundFx sfx;
    private SensorManager sensors;
    private boolean sounds, volShutter, autoFaces, rollMode, borderOn, printFmt, tiltRes;
    private long lastManualAim;
    private float tiltAngle;
    private long lastGyroNs;
    private int tiltIdx = -1;
    private W.Segmented viewSeg;
    private W.Pill borderPill, rollPill;
    private View viewerScreen;
    private W.FilmStage viewerStage;
    private TextView viewerSub;
    private W.Segmented viewerSeg;
    private Bitmap[] viewerFrames;
    private Item viewerItem;
    private boolean viewerTilt;
    private int viewerPos;
    private volatile boolean rollBusy;
    private boolean front, screenLightOn, mirrorSelfie;
    private boolean sysMirror, permAsked, permHint;
    private int mirrorProbe, camRetries;
    private final java.util.HashMap<String, Bitmap> thumbCache = new java.util.HashMap<String, Bitmap>();

    /** Background work that must never crash the app if the activity is already gone. */
    void bgRun(Runnable r) {
        try { worker.execute(r); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }
    private W.ScreenLight screenLight;
    private ReelEditor editor;
    private View editorScreen;

    boolean isShowing(View v) { return currentScreen == v; }
    void showGallery() { show(galleryScreen); }

    void openEditor() {
        if (editor == null) {
            editor = new ReelEditor(this);
            editorScreen = editor.build();
            editorScreen.setVisibility(View.GONE);
            root.addView(editorScreen, new FrameLayout.LayoutParams(-1, -1));
        }
        show(editorScreen);
        editor.onShow();
    }
    private W.IconBtn flipBtn;

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

        sfx = new SoundFx(this);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        root = new FrameLayout(this);
        root.setBackgroundColor(W.BG);
        root.setFitsSystemWindows(true);
        cameraScreen = buildCamera();
        resultScreen = buildResult();
        galleryScreen = buildGallery();
        settingsScreen = buildSettings();
        welcomeScreen = buildWelcome();
        viewerScreen = buildViewer();
        View[] all = {cameraScreen, resultScreen, galleryScreen, settingsScreen, welcomeScreen, viewerScreen};
        for (View v : all) { v.setVisibility(View.GONE); root.addView(v, new FrameLayout.LayoutParams(-1, -1)); }
        screenLight = new W.ScreenLight(this);
        screenLight.setVisibility(View.GONE);
        root.addView(screenLight, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        applyLiveStock();
        setPreviewState();
        show(prefs.getBoolean("onboarded", false) ? cameraScreen : welcomeScreen);

        checkForUpdate();
        InstallReceiver.host = new java.lang.ref.WeakReference<MainActivity>(this);
        showUpdatedStampIfNeeded();
        loadGallery();
    }

    private void loadPrefs() {
        orbit = prefs.getInt("mode", 0) == 1;
        depthIdx = prefs.getInt("depth", 1);
        flashIdx = prefs.getInt("flash", 0);
        liveStock = prefs.getInt("stock", 0);
        dateStamp = prefs.getBoolean("date", true);
        mp4 = prefs.getInt("format", 0) == 1;
        bounce = prefs.getInt("loop", 0) == 0;
        haptics = prefs.getBoolean("haptics", true);
        seq = bounce ? SEQ_BOUNCE : SEQ_FWD;
        sounds = prefs.getBoolean("sounds", true);
        volShutter = prefs.getBoolean("volShutter", true);
        autoFaces = prefs.getBoolean("autoFaces", true);
        rollMode = prefs.getBoolean("roll", false);
        borderOn = prefs.getBoolean("border", false);
        front = prefs.getBoolean("front", false);
        mirrorSelfie = prefs.getBoolean("mirrorSelfie", false);
    }

    void put(String k, int v) { prefs.edit().putInt(k, v).apply(); }
    void put(String k, boolean v) { prefs.edit().putBoolean(k, v).apply(); }

    private int defaultGrain(int stock) {
        return Math.min(100, Math.round(Develop.STOCK_GRAIN[stock] * GRAIN_LEVELS[prefs.getInt("grainLevel", 1)]));
    }

    int dp(float v) { return W.dpi(this, v); }

    void show(View screen) {
        if (currentScreen != null) currentScreen.setVisibility(View.GONE);
        currentScreen = screen;
        screen.setVisibility(View.VISIBLE);
        if (screen == galleryScreen) loadGallery();
        updateSensors();
        if (screen == cameraScreen) scheduleFaceAim();
    }

    // ================================================================= tilt-to-view (gyro yaw -> view index)
    private boolean tiltWanted() {
        return resumed && ((currentScreen == resultScreen && tiltRes) || (currentScreen == viewerScreen && viewerTilt));
    }

    private void updateSensors() {
        if (sensors == null) return;
        sensors.unregisterListener(this);
        if (!tiltWanted()) return;
        tiltAngle = 0; lastGyroNs = 0; tiltIdx = -1;
        Sensor g = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (g == null) g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        showTiltFrame();
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastGyroNs != 0) {
                float dt = (e.timestamp - lastGyroNs) / 1e9f;
                tiltAngle += e.values[1] * dt;      // yaw around the phone's vertical axis
                tiltAngle *= 0.992f;                // slowly re-centres, so drift never sticks
            }
            lastGyroNs = e.timestamp;
        } else {
            tiltAngle = (float) Math.atan2(e.values[0], Math.max(0.1f, e.values[2])) * 0.6f;
        }
        showTiltFrame();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    private void showTiltFrame() {
        float t = Math.max(0f, Math.min(1f, (tiltAngle / 0.22f + 1f) / 2f));
        int idx = Math.round(t * 3f);
        if (idx == tiltIdx) return;
        tiltIdx = idx;
        if (currentScreen == resultScreen && tiltRes && outFrames != null) stage.setFrame(outFrames[idx]);
        else if (currentScreen == viewerScreen && viewerTilt && viewerFrames != null) viewerStage.setFrame(viewerFrames[idx]);
        if (haptics) stage.performHapticFeedback(1);
    }

    // ================================================================= auto-aim on faces
    private final Runnable faceAim = new Runnable() { @Override public void run() { aimAtFace(); } };

    private void scheduleFaceAim() {
        ui.removeCallbacks(faceAim);
        if (autoFaces) ui.postDelayed(faceAim, 900);
    }

    private void aimAtFace() {
        if (!autoFaces || !resumed || currentScreen != cameraScreen || state != ST_PREVIEW || !cameraStarted) return;
        if (SystemClock.uptimeMillis() - lastManualAim < 5000) { scheduleFaceAim(); return; }
        final int bw = 180, bh = Math.round(180 / contentAspect) & ~1;
        final Bitmap src;
        try { src = texture.getBitmap(bw, bh); } catch (Exception e) { scheduleFaceAim(); return; }
        if (src == null) { scheduleFaceAim(); return; }
        bgRun(new Runnable() { @Override public void run() {
            float[] hit = null;
            try {
                Bitmap b565 = src.copy(Bitmap.Config.RGB_565, false);
                FaceDetector.Face[] faces = new FaceDetector.Face[3];
                int n = new FaceDetector(bw, bh, 3).findFaces(b565, faces);
                float best = 0;
                for (int i = 0; i < n; i++) {
                    if (faces[i] == null || faces[i].confidence() < 0.4f) continue;
                    if (faces[i].eyesDistance() > best) {
                        best = faces[i].eyesDistance();
                        android.graphics.PointF m = new android.graphics.PointF();
                        faces[i].getMidPoint(m);
                        hit = new float[]{m.x / bw, (m.y + best * 0.6f) / bh};
                    }
                }
                b565.recycle();
            } catch (Throwable ignored) {}
            src.recycle();
            final float[] fh = hit;
            ui.post(new Runnable() { @Override public void run() {
                if (fh != null && state == ST_PREVIEW && SystemClock.uptimeMillis() - lastManualAim >= 5000) {
                    float fx = flipPreview() ? 1f - fh[0] : fh[0];
                    float u = 0.5f + (fx - 0.5f) * scaleX, v = 0.5f + (fh[1] - 0.5f) * scaleY;
                    brackets.u = Math.max(0.12f, Math.min(0.88f, u));
                    brackets.v = Math.max(0.16f, Math.min(0.78f, v));
                    brackets.invalidate();
                }
                scheduleFaceAim();
            }});
        }});
    }

    TextView text(String t, float sp, int color, android.graphics.Typeface tf) {
        TextView tv = new TextView(this);
        tv.setText(t); tv.setTextSize(sp); tv.setTextColor(color); tv.setTypeface(tf);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    W.Pill pill(String t, int icon, int style, float sp, boolean dot) {
        W.Pill p = new W.Pill(this, t, icon, style, sp, W.UI_SEMI);
        if (dot) p.dotFont();
        return p;
    }

    W.Pill bigPill(String t, int style) { return new W.Pill(this, t, W.IC_NONE, style, 16, W.HEAD); }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1)); return v; }
    LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    LinearLayout.LayoutParams lpw(int h, float weight) { return new LinearLayout.LayoutParams(0, h, weight); }

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
        brackets.listener = new W.Brackets.OnFocus() { @Override public void onFocus(float u, float v) { lastManualAim = SystemClock.uptimeMillis(); } };
        vp.addView(brackets, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = row();
        top.setPadding(dp(14), dp(14), dp(14), 0);
        flashPill = pill("OFF", W.IC_FLASH, W.Pill.GLASS_STYLE, 13, false);
        flashPill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (state != ST_PREVIEW) return;
            if (cam != null && cameraStarted && !cam.hasFlash && !front) { flashInfo("No flash on this camera"); return; }
            flashIdx = (flashIdx + 1) % 3; put("flash", flashIdx); updateFlashPill();
            if (flashIdx == 2) flashInfo(front ? "Screen lights your face during the sweep" : "Light stays on during the sweep");
            else if (flashIdx == 1) flashInfo("Light turns on when it's dark");
        }});
        top.addView(flashPill);
        expPill = pill("EXP 00", W.IC_NONE, W.Pill.GLASS_STYLE, 13, true);
        expPill.setTextColorOverride(W.ACC);
        expPill.setClickable(false);
        LinearLayout.LayoutParams elp = lp(-2, -2); elp.leftMargin = dp(8);
        top.addView(expPill, elp);
        top.addView(spacer());
        flipBtn = new W.IconBtn(this, W.IC_FLIP, "Switch camera");
        flipBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { flipCamera(); } });
        LinearLayout.LayoutParams fbl = lp(dp(44), dp(44)); fbl.rightMargin = dp(8);
        top.addView(flipBtn, fbl);
        W.IconBtn gear = new W.IconBtn(this, W.IC_GEAR, "Settings");
        gear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (state == ST_PREVIEW) show(settingsScreen); } });
        top.addView(gear, lp(dp(44), dp(44)));
        vp.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        infoPill = pill("", W.IC_NONE, W.Pill.SOLID, 11, false);
        infoPill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (!permHint) return;
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (Exception ignored) {}
        }});
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

        LinearLayout camPills = row();
        camPills.setPadding(dp(14), 0, dp(14), 0);
        for (int i = 0; i < camStocks.length; i++) {
            final int idx = i;
            camStocks[i] = pill(Develop.STOCKS[i], W.IC_NONE, W.Pill.GLASS_STYLE, 12, true);
            camStocks[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                liveStock = idx; put("stock", idx); applyLiveStock(); }});
            LinearLayout.LayoutParams p = lp(-2, dp(34)); if (i > 0) p.leftMargin = dp(6);
            camPills.addView(camStocks[i], p);
        }
        camStockRow = new android.widget.HorizontalScrollView(this);
        camStockRow.setHorizontalScrollBarEnabled(false);
        camStockRow.setOverScrollMode(View.OVER_SCROLL_NEVER);
        camStockRow.addView(camPills, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
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
        if (permHint) return;   // the permission hint stays up until access is granted
        infoPill.setText(t);
        infoPill.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 2400);
    }

    private void applyLiveStock() {
        for (int i = 0; i < camStocks.length; i++) { camStocks[i].setSel(i == liveStock); if (setStocks[i] != null) setStocks[i].setSel(i == liveStock); }
        scrollTo(camStockRow, camStocks[liveStock]);
        if (setStockScroll != null) scrollTo(setStockScroll, setStocks[liveStock]);
        Paint lp = new Paint();
        lp.setColorFilter(new ColorMatrixColorFilter(Develop.matrix(liveStock)));
        texture.setLayerPaint(lp);
        grainLive.set(defaultGrain(liveStock) / 100f * 0.7f, Develop.hasLeak(liveStock));
    }

    private void scrollTo(final android.widget.HorizontalScrollView sv, final View target) {
        if (sv == null || target == null) return;
        sv.post(new Runnable() { @Override public void run() {
            int x = target.getLeft() + (target.getWidth() - sv.getWidth()) / 2 + dp(14);
            sv.smoothScrollTo(Math.max(0, x), 0);
        }});
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
        if (rollMode) {
            int r = Store.rollShots(this).size();
            expPill.setText("ROLL " + (r < 10 ? "0" : "") + r + "/" + Store.ROLL_SIZE);
            return;
        }
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
        for (int i = 0; i < resStocks.length; i++) {
            final int idx = i;
            resStocks[i] = pill(Develop.STOCKS[i], W.IC_NONE, W.Pill.GHOST, 11, true);
            resStocks[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                resStock = idx; grain = defaultGrain(idx); syncResultControls(); markDirty(); develop(); }});
            LinearLayout.LayoutParams p = lp(-2, dp(34)); if (i > 0) p.leftMargin = dp(6);
            stocks.addView(resStocks[i], p);
        }
        resStockScroll = new android.widget.HorizontalScrollView(this);
        resStockScroll.setHorizontalScrollBarEnabled(false);
        resStockScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        resStockScroll.addView(stocks, new FrameLayout.LayoutParams(-2, -2));
        panel.addView(resStockScroll, lp(-1, -2));

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

        LinearLayout str = row();
        str.addView(text("Strength", 12, W.MUT, W.UI), lp(dp(64), -2));
        strengthSlider = new W.Slider(this);
        strengthSlider.setContentDescription("Look strength");
        strengthSlider.listener = new W.Slider.OnChange() { @Override public void onChange(float v, boolean done) {
            strength = Math.round(v * 100); strengthVal.setText(strength + "%");
            if (done) { markDirty(); develop(); } }};
        str.addView(strengthSlider, lpw(dp(36), 1));
        strengthVal = text("100%", 12, W.TXT, W.DOT);
        strengthVal.setGravity(Gravity.END);
        str.addView(strengthVal, lp(dp(46), -2));
        panel.addView(str, lp(-1, -2));

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
        formatSeg = new W.Segmented(this, new String[]{"GIF", "MP4", "PRINT"}, mp4 ? 1 : 0, true);
        formatSeg.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            printFmt = i == 2; if (i < 2) mp4 = i == 1; markDirty(); } };
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(0, -2, 1); fl.leftMargin = dp(8);
        segs.addView(formatSeg, fl);
        LinearLayout.LayoutParams sgl = lp(-1, -2); sgl.topMargin = dp(8);
        panel.addView(segs, sgl);

        LinearLayout vrow = row();
        viewSeg = new W.Segmented(this, new String[]{"LOOP", "TILT"}, 0, true);
        viewSeg.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            tiltRes = i == 1;
            if (tiltRes) { stopLoop(); updateSensors(); flashResultHint("Tilt the phone left / right"); }
            else { updateSensors(); startLoop(); } }};
        vrow.addView(viewSeg, new LinearLayout.LayoutParams(0, -2, 1.4f));
        borderPill = pill("FILM BORDER", W.IC_NONE, W.Pill.GHOST, 11, true);
        borderPill.setSel(borderOn);
        borderPill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            borderOn = !borderOn; put("border", borderOn); borderPill.setSel(borderOn); markDirty(); develop(); }});
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(0, dp(34), 1); bpl.leftMargin = dp(8);
        vrow.addView(borderPill, bpl);
        LinearLayout.LayoutParams vrl = lp(-1, -2); vrl.topMargin = dp(8);
        panel.addView(vrow, vrl);

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
        for (int i = 0; i < resStocks.length; i++) resStocks[i].setSel(i == resStock);
        scrollTo(resStockScroll, resStocks[resStock]);
        strengthSlider.setValue(strength / 100f);
        strengthVal.setText(strength + "%");
        grainSlider.setValue(grain / 100f);
        grainVal.setText(String.valueOf(grain));
        int fps = Math.round(1000f / frameMs);
        speedSlider.setValue((fps - 4) / 12f);
        speedVal.setText(fps + "FPS");
        ratioSeg.setSel(aspectIdx);
        formatSeg.setSel(printFmt ? 2 : (mp4 ? 1 : 0));
        viewSeg.setSel(tiltRes ? 1 : 0);
        borderPill.setSel(borderOn);
    }

    private void flashResultHint(String t) {
        String keep = resSub.getText().toString();
        resSub.setText(t.toUpperCase(Locale.US));
        final String k = keep;
        ui.postDelayed(new Runnable() { @Override public void run() { if (resSub.getText().toString().startsWith("TILT")) resSub.setText(k); } }, 2200);
    }

    // ================================================================= GALLERY ("Contact sheet")
    private static class Item { Uri uri; String mime; long date; String stock; String name; Bitmap thumb; }

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
        f.addView(spacer());
        W.Pill reelPill = pill("Reel editor", W.IC_NONE, W.Pill.SOLID, 13, false);
        reelPill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openEditor(); } });
        f.addView(reelPill, lp(-2, dp(36)));
        LinearLayout.LayoutParams fl = lp(-1, -2); fl.topMargin = dp(16); fl.bottomMargin = dp(14);
        s.addView(f, fl);

        rollPill = pill("", W.IC_NONE, W.Pill.SOLID, 13, false);
        rollPill.setVisibility(View.GONE);
        rollPill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { developRoll(); } });
        LinearLayout.LayoutParams rpl = lp(-1, dp(44)); rpl.bottomMargin = dp(14);
        s.addView(rollPill, rpl);

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

    void loadGallery() {
        final ContentResolver cr = getContentResolver();
        bgRun(new Runnable() { @Override public void run() {
            final List<Item> items = new ArrayList<Item>();
            boolean ok = queryInto(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/Aurelius7%", items);
            ok &= queryInto(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/Aurelius7%", items);
            Collections.sort(items, new Comparator<Item>() { @Override public int compare(Item a, Item b) { return Long.compare(b.date, a.date); } });
            // Thumbnails are cached and only decoded for the newest 150, so a big library stays fast and small.
            java.util.HashSet<String> live = new java.util.HashSet<String>();
            java.util.HashSet<String> names = new java.util.HashSet<String>();
            int idx = 0;
            for (Item it : items) {
                if (it.name != null) names.add(it.name.replaceAll("\\.(gif|mp4|png)$", ""));
                if (idx++ >= 150) continue;
                String key = it.uri.toString();
                live.add(key);
                Bitmap t = thumbCache.get(key);
                if (t == null) { t = loadThumb(cr, it); if (t != null) thumbCache.put(key, t); }
                it.thumb = t;
            }
            thumbCache.keySet().retainAll(live);
            if (ok) pruneSidecars(names);
            ui.post(new Runnable() { @Override public void run() {
                galleryItems = items;
                if (!items.isEmpty() && items.get(0).thumb != null && outFrames == null) thumb.setBitmap(items.get(0).thumb);
                if (state == ST_PREVIEW) updateExp();
                renderStrips();
            }});
        }});
    }

    /** Deletes the private 4-view copies of wiggles the user has since removed from their gallery. */
    private void pruneSidecars(java.util.Set<String> liveNames) {
        try {
            File[] dirs = new File(getFilesDir(), "wiggles").listFiles();
            if (dirs == null) return;
            long now = System.currentTimeMillis();
            for (File d : dirs) {
                if (liveNames.contains(d.getName())) continue;
                if (now - d.lastModified() < 10 * 60 * 1000L) continue;   // may belong to an export in flight
                Store.deleteDir(d);
            }
        } catch (Exception ignored) {}
    }

    private static String tagOf(Item it) {
        if (it.mime == null) return "GIF";
        if (it.mime.startsWith("video")) return "MP4";
        return it.mime.contains("png") ? "PRINT" : "GIF";
    }

    private boolean queryInto(ContentResolver cr, Uri coll, String pathLike, List<Item> out) {
        Cursor c = null;
        try {
            c = cr.query(coll, new String[]{"_id", "date_added", "mime_type", "_display_name"},
                    "relative_path LIKE ?", new String[]{pathLike}, null);
            if (c == null) return false;
            while (c.moveToNext()) {
                Item it = new Item();
                it.uri = ContentUris.withAppendedId(coll, c.getLong(0));
                it.date = c.getLong(1);
                it.mime = c.getString(2);
                String name = c.getString(3);
                it.name = name;
                it.stock = "";
                if (name != null) for (String st : Develop.STOCKS) if (name.contains("_" + st.replace("-", "") + ".")) it.stock = st;
                out.add(it);
            }
            return true;
        } catch (Exception e) {
            return false;
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
            boolean gif = !vid && !"PRINT".equals(tagOf(it));
            if (galFilter == 0 || (galFilter == 1 && gif) || (galFilter == 2 && vid)) list.add(it);
        }
        galCount.setText(galleryItems.size() + " EXP");
        int rolled = Store.rollShots(this).size();
        if (rollBusy) { rollPill.setVisibility(View.VISIBLE); }
        else if (rolled > 0) {
            rollPill.setText("Roll in camera \u00B7 " + rolled + "/" + Store.ROLL_SIZE + " \u00B7 Develop now");
            rollPill.setEnabled(true);
            rollPill.setVisibility(View.VISIBLE);
        } else rollPill.setVisibility(View.GONE);
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
                st.setThumb(k, it.thumb, tagOf(it));
            }
            st.listener = new W.Strip.OnPick() { @Override public void onPick(int k) {
                Item it = list.get(base + k);
                if (openViewer(it)) return;
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
        setRow(g, "Haptic ticks", "A tap for each of the 4 views", hap, false);
        W.Toggle snd = new W.Toggle(this, sounds);
        snd.setContentDescription("Shutter sounds");
        snd.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { sounds = on; put("sounds", on); if (on) sfx.shutter(); } };
        setRow(g, "Shutter sounds", "Mechanical clack + film advance (silent mode mutes)", snd, false);
        W.Toggle vol = new W.Toggle(this, volShutter);
        vol.setContentDescription("Volume button shutter");
        vol.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { volShutter = on; put("volShutter", on); } };
        setRow(g, "Volume-button shutter", "Steadier sweeps: both hands on the phone", vol, false);
        W.Toggle fac = new W.Toggle(this, autoFaces);
        fac.setContentDescription("Auto-aim on faces");
        fac.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { autoFaces = on; put("autoFaces", on); scheduleFaceAim(); } };
        setRow(g, "Auto-aim on faces", "Brackets jump to the nearest face", fac, false);
        W.Toggle mir = new W.Toggle(this, mirrorSelfie);
        mir.setContentDescription("Mirror selfies");
        mir.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) {
            mirrorSelfie = on; put("mirrorSelfie", on); applyTransform(); } };
        setRow(g, "Mirror selfies", "Off = true view, like others see you", mir, true);

        g = group(s, "FLASH");
        W.Segmented fls = new W.Segmented(this, FLASH_NAMES, flashIdx, false);
        fls.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { flashIdx = i; put("flash", i); updateFlashPill(); } };
        setRow(g, "Flash", "In a sweep the light stays on the whole time", fls, true);

        g = group(s, "FILM");
        LinearLayout stp = row();
        for (int i = 0; i < setStocks.length; i++) {
            final int idx = i;
            setStocks[i] = pill(Develop.STOCKS[i], W.IC_NONE, W.Pill.GHOST, 11, true);
            setStocks[i].setSel(i == liveStock);
            setStocks[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                liveStock = idx; put("stock", idx); applyLiveStock(); }});
            LinearLayout.LayoutParams p = lp(-2, dp(34)); if (i > 0) p.leftMargin = dp(6);
            stp.addView(setStocks[i], p);
        }
        setStockScroll = new android.widget.HorizontalScrollView(this);
        setStockScroll.setHorizontalScrollBarEnabled(false);
        setStockScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setStockScroll.addView(stp, new FrameLayout.LayoutParams(-2, -2));
        setRowStacked(g, "Default stock", setStockScroll);
        W.Segmented gl = new W.Segmented(this, new String[]{"Off", "Fine", "Med", "Heavy"}, prefs.getInt("grainLevel", 1), false);
        gl.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { put("grainLevel", i); applyLiveStock(); } };
        setRow(g, "Grain", null, gl, false);
        W.Toggle ds = new W.Toggle(this, dateStamp);
        ds.setContentDescription("Date stamp");
        ds.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { dateStamp = on; put("date", on); } };
        setRow(g, "Date stamp", "Orange '26 11 13 in the corner", ds, true);

        g = group(s, "ROLL OF 24");
        W.Toggle rm = new W.Toggle(this, rollMode);
        rm.setContentDescription("Roll mode");
        rm.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { rollMode = on; put("roll", on); updateExp(); } };
        setRow(g, "Roll mode", "Shoot now, see them later: develops after 24 exposures", rm, false);
        W.Pill dev = pill("Develop", W.IC_NONE, W.Pill.GHOST, 13, false);
        dev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { show(galleryScreen); developRoll(); } });
        setRow(g, "Develop roll now", "Don't wait for 24", dev, true);

        g = group(s, "EXPORT");
        W.Segmented fm = new W.Segmented(this, new String[]{"GIF", "MP4"}, mp4 ? 1 : 0, false);
        fm.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { mp4 = i == 1; put("format", i); formatSeg.setSel(i); } };
        setRow(g, "Format", null, fm, false);
        W.Segmented lpp = new W.Segmented(this, new String[]{"Bounce", "Loop"}, bounce ? 0 : 1, false);
        lpp.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            bounce = i == 0; put("loop", i); seq = bounce ? SEQ_BOUNCE : SEQ_FWD; }};
        setRow(g, "Loop style", null, lpp, false);
        W.Toggle bo = new W.Toggle(this, borderOn);
        bo.setContentDescription("Film border");
        bo.listener = new W.Toggle.OnToggle() { @Override public void onToggle(boolean on) { borderOn = on; put("border", on); } };
        setRow(g, "Film border", "Sprocket holes + edge print around exports", bo, true);

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
            // Ask once per launch. Asking on every resume re-opened the dialog forever after a denial.
            if (!permAsked) { permAsked = true; requestPermissions(new String[]{Manifest.permission.CAMERA}, 7); }
            else showPermHint();
        } else { hidePermHint(); maybeStartCamera(); }
        onInstallConfirmReady();
        if (state == ST_RESULT && !tiltRes) startLoop();
        updateSensors();
        if (currentScreen == cameraScreen) scheduleFaceAim();
    }

    @Override protected void onDestroy() {
        if (sfx != null) sfx.release();
        worker.shutdown();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (volShutter && currentScreen == cameraScreen && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getRepeatCount() == 0) {
                if (state == ST_PREVIEW) startSweep();
                else if (state == ST_CAPTURE && recorder != null) recorder.finishNow();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (volShutter && currentScreen == cameraScreen && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override protected void onPause() {
        resumed = false;
        if (sensors != null) sensors.unregisterListener(this);
        ui.removeCallbacks(faceAim);
        stopLoop();
        stopTimer();
        if (state == ST_CAPTURE || state == ST_PROCESS) {
            // Mid-sweep: drop it. Mid-build: the builder thread owns the frames and releases them itself.
            if (state == ST_CAPTURE && recorder != null) recorder.release();
            recorder = null;
            setPreviewState();
        }
        if (cam != null) { cam.sink = null; cam.stop(); cam = null; }
        torchOn = false;
        screenLight(false);
        cameraStarted = false;
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != 7) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) { hidePermHint(); maybeStartCamera(); }
        else showPermHint();
    }

    private void showPermHint() {
        permHint = true;
        ui.removeCallbacks(hideInfo);
        infoPill.setText("Camera access needed \u00B7 tap to open Settings");
        infoPill.setVisibility(View.VISIBLE);
    }

    private void hidePermHint() {
        if (!permHint) return;
        permHint = false;
        infoPill.setVisibility(View.GONE);
    }

    private void maybeStartCamera() {
        if (!resumed || !surfaceReady || cam != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cam = new CameraRig(this, texture, this, front);
        cam.start();
    }

    @Override public void onCameraReady(final CameraRig rig, final int w, final int h, final int orient) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (rig != cam) return;            // a rig that was already replaced (flip / pause)
            cameraStarted = true;
            camRetries = 0;
            mirrorProbe = 45;
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
        m.setScale(flipPreview() ? -scaleX : scaleX, scaleY, vw / 2f, vh / 2f);
        texture.setTransform(m);
    }

    @Override public void onCameraError(final CameraRig rig, final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (rig != cam) return;
            if (state == ST_CAPTURE) abortSweep(null);
            cameraStarted = false;
            shutter.setEnabled(false);
            cam.stop(); cam = null;
            if (front && msg.startsWith("No selfie")) { front = false; put("front", false); flashInfo(msg); maybeStartCamera(); return; }
            flashInfo(msg);
            // Another app may have grabbed the camera for a moment: retry a couple of times.
            if (camRetries++ < 2) ui.postDelayed(new Runnable() { @Override public void run() { maybeStartCamera(); } }, 1500);
        }});
    }

    /** Whether we must flip the preview ourselves so that what is shown matches the mirror setting. */
    private boolean flipPreview() { return sysMirror != mirrored(); }

    private void abortSweep(String why) {
        SweepRecorder r = recorder;
        recorder = null;
        if (cam != null) { cam.sink = null; cam.unlock(); if (torchOn) cam.setTorch(false); }
        torchOn = false;
        screenLight(false);
        if (r != null) r.release();
        setPreviewState();
        if (why != null) flashInfo(why);
        scheduleFaceAim();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { surfaceReady = true; maybeStartCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { applyTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { surfaceReady = false; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {
        // Some phones mirror the front-camera preview at the system level. Detect it from the texture
        // transform (a horizontal flip changes the sign of its determinant) so the preview, the focus
        // point and the saved wiggle all agree with the "Mirror selfies" setting on every device.
        if (mirrorProbe <= 0) return;
        mirrorProbe--;
        try {
            float[] m = new float[16];
            s.getTransformMatrix(m);
            boolean mir = m[0] * m[5] - m[4] * m[1] > 0;
            if (mir != sysMirror) { sysMirror = mir; applyTransform(); }
        } catch (Exception ignored) {}
    }

    // ================================================================= selfie camera
    /** True view by default; mirror only if the user turned on "Mirror selfies". */
    private boolean mirrored() { return front && mirrorSelfie; }

    private void flipCamera() {
        if (state != ST_PREVIEW) return;
        front = !front;
        put("front", front);
        if (cam != null) { cam.stop(); cam = null; }
        cameraStarted = false;
        shutter.setEnabled(false);
        brackets.u = 0.5f; brackets.v = 0.42f; brackets.invalidate();
        lastManualAim = 0;
        flipBtn.animate().rotationBy(180).setDuration(250).start();
        flashInfo(front ? "Selfie \u00B7 arc the phone around your face" : "Back camera");
        maybeStartCamera();
    }

    private void screenLight(boolean on) {
        if (screenLightOn == on) return;
        screenLightOn = on;
        screenLight.setVisibility(on ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams a = getWindow().getAttributes();
        a.screenBrightness = on ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(a);
    }

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
        float[] fs = contentToSensor(mirrored() ? 1f - shotU : shotU, shotV);
        recorder = new SweepRecorder(cam.W, cam.H, fs[0], fs[1], DEPTH_FACTORS[depthIdx], orbit, this);
        boolean wantLight = flashIdx == 2 || (flashIdx == 1 && sceneIsDark());
        torchOn = !front && cam.hasFlash && wantLight;
        if (front && wantLight) screenLight(true);
        brackets.setEnabled(false);
        dial.setEnabled(false);
        camStockRow.setVisibility(View.GONE);
        shutter.setState(W.Shutter.REC_STATE);
        if (torchOn || screenLightOn) { flashPill.setText("LIGHT"); flashPill.setSel(true); }
        lastLens = 1;
        hud.set(0, orbit ? "Keep curving →" : "Keep sliding →", false, orbit);
        hud.setVisibility(View.VISIBLE);
        buzz(25);
        if (sounds) sfx.shutter();
        ui.removeCallbacks(faceAim);
        captureStart = SystemClock.uptimeMillis();
        startTimer();
        final SweepRecorder r = recorder;
        if (torchOn) cam.setTorch(true);
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (state == ST_CAPTURE && recorder == r && cam != null) cam.lock();
        }}, (torchOn || screenLightOn) ? 420 : 0);
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (state == ST_CAPTURE && recorder == r && cam != null) cam.sink = r;
        }}, (torchOn || screenLightOn) ? 600 : 180);
        // Watchdog: the sweep normally ends itself from the frame callback. If frames stop arriving
        // (camera taken by another app, driver hiccup) it would otherwise hang on "recording" forever.
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (state == ST_CAPTURE && recorder == r && !r.isDone()) abortSweep("Camera stopped responding, try again");
        }}, (orbit ? 3400 : 2600) + 2600);
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
            screenLight(false);
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
            final boolean mirror = mirrored();
            if (rollMode) { exposeToRoll(r, orient, u, v, targetAspect, stock, mirror); return; }
            new Thread(new Runnable() { @Override public void run() {
                String err = null; Composer comp = null; Bitmap[] base = null, dev = null;
                boolean low = r.lowDepth;
                try {
                    WiggleBuilder.Result res = WiggleBuilder.build(r, orient, 720, mirror);
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
                    resStock = stock; grain = gr; strength = 100;
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
        if (tiltRes) { stopLoop(); showTiltFrameForce(); } else startLoop();
    }

    private void showTiltFrameForce() { tiltIdx = -1; showTiltFrame(); }

    // ================================================================= roll of 24
    private void exposeToRoll(final SweepRecorder r, final int orient, final float u, final float v, final float aspect, final int stock, final boolean mirror) {
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            try {
                WiggleBuilder.Result res = WiggleBuilder.build(r, orient, 720, mirror);
                if (res.error != null) err = res.error;
                else {
                    Composer comp = new Composer(res.frames, res.seeds);
                    comp.aspect = aspect;
                    comp.align(u, v);
                    Bitmap[] base = comp.render();
                    java.io.File dir = new java.io.File(Store.rollRoot(MainActivity.this), String.valueOf(System.currentTimeMillis()));
                    Store.saveFrames(dir, base, 92);
                    Store.writeMeta(dir, stock);
                }
            } catch (Throwable t) { err = "Exposure failed: " + t.getClass().getSimpleName(); }
            r.release();
            final String fe = err;
            runOnUiThread(new Runnable() { @Override public void run() {
                recorder = null;
                setPreviewState();
                if (fe != null) { flashInfo(fe); return; }
                if (sounds) sfx.advance();
                int n = Store.rollShots(MainActivity.this).size();
                showStamp("EXP " + (n < 10 ? "0" : "") + n + "/" + Store.ROLL_SIZE);
                scheduleFaceAim();
                if (n >= Store.ROLL_SIZE) { show(galleryScreen); developRoll(); }
            }});
        }}, "aurelius-roll").start();
    }

    private void developRoll() {
        if (rollBusy) return;
        final List<java.io.File> shots = Store.rollShots(this);
        if (shots.isEmpty()) { flashInfo("Roll is empty"); return; }
        rollBusy = true;
        rollPill.setEnabled(false);
        rollPill.setVisibility(View.VISIBLE);
        rollPill.setText("Developing 0/" + shots.size() + "\u2026");
        final int grLevel = prefs.getInt("grainLevel", 1);
        final boolean stamp = dateStamp, asMp4 = mp4, bord = borderOn;
        final int[] sq = seq; final int fms = frameMs;
        new Thread(new Runnable() { @Override public void run() {
            int done = 0;
            for (java.io.File dir : shots) {
                try {
                    Bitmap[] base = Store.loadFrames(dir);
                    if (base != null) {
                        int stock = Store.readMeta(dir, 1);
                        int gr = Math.min(100, Math.round(Develop.STOCK_GRAIN[stock] * GRAIN_LEVELS[grLevel]));
                        Bitmap[] dev = Develop.apply(base, stock, gr, stamp, W.DOT);
                        exportBlocking(dev, sq, fms, asMp4, bord, Develop.STOCKS[stock].replace("-", ""), done + 1);
                    }
                    Store.deleteDir(dir);
                } catch (Throwable ignored) {}
                done++;
                final int d = done;
                runOnUiThread(new Runnable() { @Override public void run() { rollPill.setText("Developing " + d + "/" + shots.size() + "\u2026"); }});
            }
            runOnUiThread(new Runnable() { @Override public void run() {
                rollBusy = false;
                if (sounds) sfx.advance();
                showStamp("ROLL DEVELOPED");
                updateExp();
                loadGallery();
            }});
        }}, "aurelius-develop-roll").start();
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
        try {
            composer.align(u, v);
            baseFrames = composer.render();
        } catch (Throwable t) { return; }
        syncStageFocus();
        markDirty();
        develop();
    }

    private void develop() {
        if (baseFrames == null) return;
        final int gen = ++devGen;
        final Bitmap[] base = baseFrames;
        final int stock = resStock, gr = grain, str = strength;
        final boolean stamp = dateStamp;
        bgRun(new Runnable() { @Override public void run() {
            if (gen != devGen) return;
            final Bitmap[] dev = Develop.apply(base, stock, gr, str, stamp, W.DOT);
            ui.post(new Runnable() { @Override public void run() {
                if (gen != devGen) return;
                outFrames = dev;
                if (state == ST_RESULT) stage.setFrame(tiltRes ? outFrames[Math.max(0, tiltIdx)] : outFrames[seq[loopPos % seq.length]]);
            }});
        }});
    }

    private final Runnable loopTick = new Runnable() { @Override public void run() {
        if (outFrames == null || state != ST_RESULT || tiltRes) return;
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
        if (currentScreen == viewerScreen) { closeViewer(); return; }
        if (editorScreen != null && currentScreen == editorScreen) { editor.onBack(); return; }
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
        saveBtn.setText(printFmt ? "PRINT\u2026" : (mp4 ? "MP4\u2026" : "GIF\u2026"));
        final Bitmap[] frames = outFrames;
        final int[] sq = seq;
        final int fms = frameMs;
        final boolean asMp4 = mp4, asPrint = printFmt, bord = borderOn;
        final String stockTag = Develop.STOCKS[resStock].replace("-", "");
        final int exp = galleryItems.size() + 1;
        new Thread(new Runnable() { @Override public void run() {
            Uri uri = null; String err = null;
            try {
                uri = asPrint ? exportPrint(frames) : exportBlocking(frames, sq, fms, asMp4, bord, stockTag, exp);
            } catch (Throwable t) {
                err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final Uri fu = uri; final String fe = err;
            final String mime = asPrint ? "image/png" : (asMp4 ? "video/mp4" : "image/gif");
            runOnUiThread(new Runnable() { @Override public void run() {
                saving = false;
                retakeBtn.setEnabled(true); shareBtn.setEnabled(true); saveBtn.setEnabled(true);
                if (fu == null) { saveBtn.setText("Save"); resSub.setText("SAVE FAILED: " + fe); return; }
                savedUri = fu; savedMime = mime;
                saveBtn.setText("Saved \u2713");
                showStamp(asPrint ? "PRINT READY" : "SAVED");
                buzz(25);
                if (sounds) sfx.advance();
                loadGallery();
                if (thenShare) share(fu, mime);
            }});
        }}, "aurelius-export").start();
    }

    /** Writes a GIF/MP4 to MediaStore (+ the 4 views as a private sidecar for tilt/print). Blocking. */
    private Uri exportBlocking(Bitmap[] frames, int[] sq, int fms, boolean asMp4, boolean bord, String stockTag, int exp) throws Exception {
        ContentResolver cr = getContentResolver();
        String stampName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String base = "AURELIUS7_" + stampName + "_" + (System.nanoTime() % 1000) + "_" + stockTag;
        Bitmap[] outF = bord ? Develop.border(frames, "AURELIUS7 400", "\u25B8 " + exp, W.DOT) : frames;
        File tmp = null;
        Uri uri = null;
        try {
            ContentValues cv = new ContentValues();
            Uri coll;
            if (asMp4) {
                tmp = new File(getCacheDir(), base + ".mp4");
                Mp4Encoder.encode(outF, sq, fms, 4000, tmp);
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, base + ".mp4");
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                cv.put("relative_path", "Movies/Aurelius7");
                coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else {
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, base + ".gif");
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
                    GifEncoder.encode(outF, sq, Math.max(2, fms / 10), bo);
                    bo.flush();
                }
            } finally { os.close(); }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            cr.update(uri, done, null, null);
            try { Store.saveFrames(Store.wiggleDir(this, base), frames, 88); } catch (Exception ignored) {}
            return uri;
        } catch (Exception e) {
            if (uri != null) { try { cr.delete(uri, null, null); } catch (Exception ignored) {} }
            throw e;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** Lenticular print sheet PNG (240 dpi, 60 LPI). Blocking. */
    private Uri exportPrint(Bitmap[] frames) throws Exception {
        ContentResolver cr = getContentResolver();
        Bitmap sheet = Develop.lenticular(frames, 1200, W.DOT);
        String name = "AURELIUS7_PRINT_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        cv.put("relative_path", "Pictures/Aurelius7/Prints");
        cv.put("is_pending", 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new RuntimeException("MediaStore insert failed");
        try {
            OutputStream os = cr.openOutputStream(uri);
            try { sheet.compress(Bitmap.CompressFormat.PNG, 100, os); } finally { os.close(); }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (Exception e) {
            try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            throw e;
        } finally { sheet.recycle(); }
    }

    // ================================================================= in-app viewer (tilt / loop / print)
    private View buildViewer() {
        LinearLayout s = col();
        s.setBackgroundColor(W.BG);
        s.setClickable(true);
        LinearLayout head = row();
        head.setPadding(dp(16), dp(14), dp(16), 0);
        W.IconBtn back = new W.IconBtn(this, W.IC_BACK, "Back to contact sheet");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeViewer(); } });
        head.addView(back, lp(dp(44), dp(44)));
        LinearLayout tc = col();
        tc.addView(text("Exposure", 18, W.TXT, W.HEAD));
        viewerSub = text("", 11, W.MUT, W.DOT);
        LinearLayout.LayoutParams sl = lp(-2, -2); sl.topMargin = dp(3);
        tc.addView(viewerSub, sl);
        LinearLayout.LayoutParams tcl = lp(-2, -2); tcl.leftMargin = dp(12);
        head.addView(tc, tcl);
        s.addView(head);

        viewerStage = new W.FilmStage(this);
        s.addView(viewerStage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout panel = col();
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(W.SURF);
        float r = W.dp(this, 26);
        bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bgd);
        panel.setPadding(dp(16), dp(16), dp(16), dp(18));
        s.addView(panel, lp(-1, -2));
        viewerSeg = new W.Segmented(this, new String[]{"LOOP", "TILT"}, 0, true);
        viewerSeg.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) {
            viewerTilt = i == 1; updateSensors(); if (!viewerTilt) startViewerLoop(); }};
        panel.addView(viewerSeg, lp(-1, -2));
        LinearLayout btns = row();
        W.Pill open = bigPill("Open", W.Pill.GHOST);
        final W.Pill print = bigPill("Print", W.Pill.GHOST);
        W.Pill sh = bigPill("Share", W.Pill.SOLID);
        btns.addView(open, lpw(dp(56), 1));
        LinearLayout.LayoutParams b2 = lpw(dp(56), 1); b2.leftMargin = dp(10);
        btns.addView(print, b2);
        LinearLayout.LayoutParams b3 = lpw(dp(56), 1.2f); b3.leftMargin = dp(10);
        btns.addView(sh, b3);
        open.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (viewerItem == null) return;
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(viewerItem.uri, viewerItem.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(i); } catch (Exception ignored) {}
        }});
        sh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (viewerItem != null) share(viewerItem.uri, viewerItem.mime); } });
        print.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (viewerFrames == null) return;
            final Bitmap[] fr = viewerFrames;
            print.setText("\u2026");
            new Thread(new Runnable() { @Override public void run() {
                Uri u = null;
                try { u = exportPrint(fr); } catch (Throwable ignored) {}
                final Uri fu = u;
                runOnUiThread(new Runnable() { @Override public void run() {
                    print.setText("Print");
                    if (fu != null) { showStamp("PRINT READY"); share(fu, "image/png"); }
                    else viewerSub.setText("PRINT FAILED");
                }});
            }}).start();
        }});
        LinearLayout.LayoutParams bl = lp(-1, -2); bl.topMargin = dp(14);
        panel.addView(btns, bl);
        return s;
    }

    private boolean openViewer(Item it) {
        if (it.name == null) return false;
        String base = it.name.replaceAll("\\.(gif|mp4)$", "");
        final java.io.File dir = Store.wiggleDir(this, base);
        if (!Store.hasFrames(dir)) return false;
        Bitmap[] fr = Store.loadFrames(dir);
        if (fr == null) return false;
        viewerItem = it;
        viewerFrames = fr;
        viewerTilt = false;
        viewerSeg.setSel(0);
        viewerSub.setText((it.stock.isEmpty() ? "AURELIUS7" : it.stock) + " \u00B7 "
                + new SimpleDateFormat("MMM dd", Locale.US).format(new Date(it.date * 1000)).toUpperCase(Locale.US)
                + " \u00B7 " + (it.mime != null && it.mime.startsWith("video") ? "MP4" : "GIF"));
        show(viewerScreen);
        startViewerLoop();
        return true;
    }

    private final Runnable viewerTick = new Runnable() { @Override public void run() {
        if (viewerFrames == null || currentScreen != viewerScreen || viewerTilt) return;
        viewerStage.setFrame(viewerFrames[seq[viewerPos % seq.length]]);
        viewerPos++;
        ui.postDelayed(this, frameMs);
    }};
    private void startViewerLoop() { ui.removeCallbacks(viewerTick); viewerPos = 0; ui.post(viewerTick); }

    private void closeViewer() {
        ui.removeCallbacks(viewerTick);
        viewerTilt = false;
        viewerFrames = null;
        show(galleryScreen);
    }

    void share(Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(android.content.ClipData.newRawUri("wiggle", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Share wiggle")); } catch (Exception ignored) {}
    }

    void showStamp(String t) {
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

    void buzz(int ms) {
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
        if (intent != null && ACTION_CAMERA.equals(intent.getAction()) && state == ST_PREVIEW && prefs.getBoolean("onboarded", false)) show(cameraScreen);
    }

    /** Called by InstallReceiver (any thread) and from onResume: show Android's install confirmation. */
    void onInstallConfirmReady() {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (!resumed) return;                       // shown on the next resume instead
            Intent confirm = InstallReceiver.pendingConfirm;
            if (confirm == null) return;
            InstallReceiver.pendingConfirm = null;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(confirm); } catch (Exception e) { onInstallFailed(); }
        }});
    }

    void onInstallFailed() {
        runOnUiThread(new Runnable() { @Override public void run() {
            updating = false;
            updatePill.setEnabled(true);
            updatePill.setText("Install cancelled, tap to retry");
        }});
    }
}
