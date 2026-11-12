package com.aurelius7.wiggle;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener,
        CameraRig.Callback, SweepRecorder.Listener, SensorEventListener {

    private String VERSION = "v?";
    private int versionCode;
    private static final int ST_PREVIEW = 0, ST_CAPTURE = 1, ST_PROCESS = 2, ST_RESULT = 3;
    private static final int[] SEQ_BOUNCE = {0, 1, 2, 3, 2, 1}, SEQ_FWD = {0, 1, 2, 3};
    private static final String[] DEPTH_NAMES = {"LO", "MID", "HI"};
    private static final float[] DEPTH_FACTORS = {0.014f, 0.02f, 0.028f};
    private static final String[] ASPECT_NAMES = {"9:16", "4:5", "1:1"};
    private static final float[] ASPECTS = {9f / 16f, 4f / 5f, 1f};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int state = ST_PREVIEW;

    // camera layer
    private FrameLayout camLayer;
    private TextureView texture;
    private Ui.FocusOverlay focus;
    private Ui.LevelView level;
    private Ui.BrutalButton hintChip, shutter, depthBtn, updateBtn;
    private Updater.Info pendingUpdate;
    private volatile boolean updating;
    private Ui.ThumbView thumb;
    private Ui.SweepHud hud;
    // result layer
    private LinearLayout resultLayer;
    private Ui.StageView stage;
    private Ui.BrutalButton statsChip, bounceChip, fwdChip, gifChip, mp4Chip, saveBtn, shareBtn, retakeBtn;
    private Ui.BrutalButton[] aspectChips = new Ui.BrutalButton[3];
    private Ui.BrutalSlider speed;
    private TextView speedLabel, stamp;

    private CameraRig cam;
    private boolean resumed, surfaceReady, cameraStarted;
    private float scaleX = 1, scaleY = 1, contentAspect = 9f / 16f;
    private SweepRecorder recorder;
    private int lastLens;
    private float shotU = 0.5f, shotV = 0.5f;

    private SensorManager sensors;
    private float rollF;

    // result / settings
    private Composer composer;
    private Bitmap[] outFrames;
    private int[] seq = SEQ_BOUNCE;
    private int loopPos, frameMs = 110, depthIdx = 1, aspectIdx = 0;
    private boolean mp4;
    private Uri savedUri;
    private String savedMime;
    private volatile boolean saving;
    private long lastAlign;

    // ================================================================== UI construction
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.loadFonts(this);
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            VERSION = "v" + pi.versionName; versionCode = pi.versionCode;
        } catch (Exception ignored) {}
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setNavigationBarColor(Ui.BLACK);
        setContentView(buildUi());
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        setPreviewState();
        checkForUpdate();
        handleInstallIntent(getIntent());
        try {
            android.content.SharedPreferences sp = getSharedPreferences("aurelius7", MODE_PRIVATE);
            int seen = sp.getInt("seenVersion", 0);
            PackageInfo pk = getPackageManager().getPackageInfo(getPackageName(), 0);
            boolean wasUpdated = pk.lastUpdateTime > pk.firstInstallTime;
            if (seen < versionCode && wasUpdated) {
                ui.postDelayed(new Runnable() { @Override public void run() { showStamp("UPDATED → " + VERSION); } }, 600);
            }
            sp.edit().putInt("seenVersion", versionCode).apply();
        } catch (Exception ignored) {}
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private Ui.BrutalButton chip(String t, int face, int ink, float sp, boolean display) {
        Ui.BrutalButton b = new Ui.BrutalButton(this, t, face, ink, sp, display ? Ui.DISPLAY : Ui.MONO, 4, 2.5f);
        return b;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(Ui.BLACK);
        return v;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BLACK);

        // ---------------- camera layer
        camLayer = new FrameLayout(this);
        root.addView(camLayer, new FrameLayout.LayoutParams(-1, -1));

        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        camLayer.addView(texture, new FrameLayout.LayoutParams(-1, -1));

        focus = new Ui.FocusOverlay(this);
        camLayer.addView(focus, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(14), dp(14), 0);
        Ui.BrutalButton title = chip("AURELIUS7", Ui.YELLOW, Ui.BLACK, 19, true);
        title.setClickable(false);
        top.addView(title, new LinearLayout.LayoutParams(-2, dp(46)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        level = new Ui.LevelView(this);
        top.addView(level, new LinearLayout.LayoutParams(dp(104), dp(40)));
        Ui.BrutalButton ver = chip(VERSION, Ui.BLACK, Ui.YELLOW, 12, false);
        ver.setClickable(false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, dp(40));
        vlp.leftMargin = dp(8);
        top.addView(ver, vlp);
        camLayer.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        updateBtn = chip("", Ui.BLACK, Ui.YELLOW, 13, false);
        updateBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startUpdate(); } });
        updateBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, dp(46), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ulp.topMargin = dp(74);
        camLayer.addView(updateBtn, ulp);

        // bottom bar: black rule + yellow bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(rule(), new LinearLayout.LayoutParams(-1, dp(5)));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.YELLOW);
        bar.setPadding(dp(18), dp(16), dp(18), dp(20));
        thumb = new Ui.ThumbView(this);
        thumb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { reopenLast(); } });
        bar.addView(thumb, new LinearLayout.LayoutParams(dp(78), dp(78)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        shutter = new Ui.BrutalButton(this, "SHOOT", Ui.BLACK, Ui.YELLOW, 26, Ui.DISPLAY, 7, 3);
        shutter.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startSweep(); } });
        bar.addView(shutter, new LinearLayout.LayoutParams(dp(164), dp(90)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        depthBtn = new Ui.BrutalButton(this, "", Ui.YELLOW, Ui.BLACK, 12, Ui.MONO, 5, 3);
        depthBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            depthIdx = (depthIdx + 1) % 3; updateDepthBtn(); }});
        updateDepthBtn();
        bar.addView(depthBtn, new LinearLayout.LayoutParams(dp(78), dp(78)));
        bottom.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        camLayer.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        hintChip = chip("TAP TO AIM · SHOOT · SLIDE ←→", Ui.YELLOW, Ui.BLACK, 11, false);
        hintChip.setClickable(false);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, dp(40), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(150);
        camLayer.addView(hintChip, hlp);

        hud = new Ui.SweepHud(this);
        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hudLp.bottomMargin = dp(148);
        camLayer.addView(hud, hudLp);

        // ---------------- result layer
        resultLayer = new LinearLayout(this);
        resultLayer.setOrientation(LinearLayout.VERTICAL);
        resultLayer.setBackgroundColor(Ui.YELLOW);
        root.addView(resultLayer, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout rHead = new LinearLayout(this);
        rHead.setOrientation(LinearLayout.HORIZONTAL);
        rHead.setGravity(Gravity.CENTER_VERTICAL);
        rHead.setPadding(dp(14), dp(14), dp(14), 0);
        Ui.BrutalButton rt = chip("WIGGLE READY", Ui.BLACK, Ui.YELLOW, 17, true);
        rt.setClickable(false);
        rHead.addView(rt, new LinearLayout.LayoutParams(-2, dp(46)));
        rHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        statsChip = chip("", Ui.YELLOW, Ui.BLACK, 10, false);
        statsChip.setClickable(false);
        rHead.addView(statsChip, new LinearLayout.LayoutParams(-2, dp(46)));
        resultLayer.addView(rHead);

        stage = new Ui.StageView(this);
        stage.listener = new Ui.StageView.OnFocusDrag() {
            @Override public void onFocusDrag(float bx, float by, boolean done) { refocus(bx, by, done); }
        };
        resultLayer.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        resultLayer.addView(rule(), new LinearLayout.LayoutParams(-1, dp(5)));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(18));
        resultLayer.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        TextView tip = new TextView(this);
        tip.setText("↑ DRAG ON THE PHOTO TO PICK WHAT STAYS STILL");
        tip.setTypeface(Ui.MONO); tip.setTextSize(11); tip.setTextColor(Ui.BLACK);
        panel.addView(tip);

        LinearLayout sRow = new LinearLayout(this);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        sRow.setGravity(Gravity.CENTER_VERTICAL);
        speedLabel = new TextView(this);
        speedLabel.setTypeface(Ui.MONO); speedLabel.setTextSize(12); speedLabel.setTextColor(Ui.BLACK);
        sRow.addView(speedLabel, new LinearLayout.LayoutParams(dp(92), -2));
        speed = new Ui.BrutalSlider(this);
        speed.setValue(0.65f);
        speed.listener = new Ui.BrutalSlider.OnChange() { @Override public void onChange(float v, boolean done) {
            frameMs = Math.round(230 - 180 * v); updateSpeedLabel(); markDirty(); }};
        sRow.addView(speed, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.topMargin = dp(10);
        panel.addView(sRow, sLp);
        frameMs = Math.round(230 - 180 * 0.65f);
        updateSpeedLabel();

        LinearLayout cRow = new LinearLayout(this);
        cRow.setOrientation(LinearLayout.HORIZONTAL);
        bounceChip = chip("BOUNCE", Ui.YELLOW, Ui.BLACK, 12, false);
        fwdChip = chip("LOOP→", Ui.YELLOW, Ui.BLACK, 12, false);
        addChip(cRow, bounceChip, 0, 1.25f);
        addChip(cRow, fwdChip, 6, 1.1f);
        bounceChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setStyle(true); } });
        fwdChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setStyle(false); } });
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            aspectChips[i] = chip(ASPECT_NAMES[i], Ui.YELLOW, Ui.BLACK, 12, false);
            addChip(cRow, aspectChips[i], i == 0 ? 14 : 6, 0.9f);
            aspectChips[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setAspect(idx); } });
        }
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, dp(44));
        cLp.topMargin = dp(12);
        panel.addView(cRow, cLp);

        LinearLayout fRow = new LinearLayout(this);
        fRow.setOrientation(LinearLayout.HORIZONTAL);
        fRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView fl = new TextView(this);
        fl.setText("EXPORT");
        fl.setTypeface(Ui.MONO); fl.setTextSize(12); fl.setTextColor(Ui.BLACK);
        fRow.addView(fl, new LinearLayout.LayoutParams(dp(92), -2));
        gifChip = chip("GIF", Ui.YELLOW, Ui.BLACK, 12, false);
        mp4Chip = chip("MP4", Ui.YELLOW, Ui.BLACK, 12, false);
        addChip(fRow, gifChip, 0, 1);
        addChip(fRow, mp4Chip, 6, 1);
        fRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1.5f));
        gifChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setFormat(false); } });
        mp4Chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setFormat(true); } });
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-1, dp(44));
        fLp.topMargin = dp(10);
        panel.addView(fRow, fLp);

        LinearLayout bRow = new LinearLayout(this);
        bRow.setOrientation(LinearLayout.HORIZONTAL);
        retakeBtn = new Ui.BrutalButton(this, "RETAKE", Ui.YELLOW, Ui.BLACK, 16);
        saveBtn = new Ui.BrutalButton(this, "SAVE", Ui.WHITE, Ui.BLACK, 16);
        shareBtn = new Ui.BrutalButton(this, "SHARE", Ui.BLACK, Ui.YELLOW, 16);
        Ui.BrutalButton[] bs = {retakeBtn, saveBtn, shareBtn};
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) lp.leftMargin = dp(8);
            bRow.addView(bs[i], lp);
        }
        retakeBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { retake(); } });
        saveBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(false); } });
        shareBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(true); } });
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, dp(70));
        bLp.topMargin = dp(16);
        panel.addView(bRow, bLp);

        setStyle(true); setAspectChips(); setFormat(false);

        // ---------------- stamp
        stamp = new TextView(this);
        stamp.setTypeface(Ui.DISPLAY);
        stamp.setTextSize(40);
        stamp.setTextColor(Ui.BLACK);
        stamp.setBackgroundColor(Ui.YELLOW);
        stamp.setPadding(dp(22), dp(10), dp(22), dp(10));
        stamp.setRotation(-8);
        stamp.setVisibility(View.GONE);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Ui.YELLOW); gd.setStroke(dp(6), Ui.BLACK);
        stamp.setBackground(gd);
        root.addView(stamp, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return root;
    }

    private void addChip(LinearLayout row, View v, int leftDp, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, weight);
        lp.leftMargin = dp(leftDp);
        row.addView(v, lp);
    }

    private void updateDepthBtn() { depthBtn.setText("DEPTH\n" + DEPTH_NAMES[depthIdx]); }

    private void updateSpeedLabel() {
        speedLabel.setText("SPEED\n" + Math.round(1000f / frameMs) + " FPS");
    }

    private void setStyle(boolean bounce) {
        seq = bounce ? SEQ_BOUNCE : SEQ_FWD;
        bounceChip.setSel(bounce); fwdChip.setSel(!bounce);
        markDirty();
    }

    private void setAspectChips() { for (int i = 0; i < 3; i++) aspectChips[i].setSel(i == aspectIdx); }

    private void setAspect(int idx) {
        aspectIdx = idx; setAspectChips();
        if (composer != null) { composer.aspect = ASPECTS[idx]; outFrames = composer.render(); syncStageFocus(); }
        markDirty();
    }

    private void setFormat(boolean m) {
        mp4 = m; gifChip.setSel(!m); mp4Chip.setSel(m);
        markDirty();
    }

    private void markDirty() {
        savedUri = null;
        if (saveBtn != null && !saving) { saveBtn.setText("SAVE"); saveBtn.setEnabled(true); }
    }

    private void setPreviewState() {
        state = ST_PREVIEW;
        stopLoop();
        resultLayer.setVisibility(View.GONE);
        camLayer.setVisibility(View.VISIBLE);
        focus.show = true; focus.setEnabled(true); focus.invalidate();
        hud.setVisibility(View.GONE);
        hintChip.setVisibility(View.VISIBLE);
        hintChip.setText("TAP TO AIM · SHOOT · SLIDE ←→");
        shutter.setText("SHOOT");
        shutter.setEnabled(cameraStarted);
        depthBtn.setEnabled(true);
        thumb.setEnabled(true);
    }

    private void toast(String s) {
        hintChip.setText(s);
        hintChip.requestLayout();
        hintChip.setVisibility(View.VISIBLE);
    }

    // ================================================================== lifecycle / camera / sensors
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("CAMERA PERMISSION NEEDED");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
        } else {
            maybeStartCamera();
        }
        if (state == ST_RESULT) startLoop();
    }

    @Override protected void onPause() {
        resumed = false;
        sensors.unregisterListener(this);
        stopLoop();
        if (state == ST_CAPTURE || state == ST_PROCESS) {
            if (recorder != null) recorder.release();
            recorder = null;
            setPreviewState();
        }
        if (cam != null) { cam.stop(); cam = null; }
        cameraStarted = false;
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) maybeStartCamera();
        else toast("NO CAMERA PERMISSION · ENABLE IN SETTINGS");
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

    /** Center-crop the preview so it fills the whole screen. */
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
        runOnUiThread(new Runnable() { @Override public void run() { toast(msg); }});
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { surfaceReady = true; maybeStartCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { applyTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { surfaceReady = false; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    @Override public void onSensorChanged(SensorEvent e) {
        float roll = (float) Math.toDegrees(Math.atan2(e.values[0], e.values[1]));
        rollF = rollF * 0.8f + roll * 0.2f;
        level.setRoll(rollF);
    }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    // ================================================================== capture
    private float[] focusContent() {
        float cu = 0.5f + (focus.u - 0.5f) / scaleX;
        float cv = 0.5f + (focus.v - 0.5f) / scaleY;
        return new float[]{Math.max(0.05f, Math.min(0.95f, cu)), Math.max(0.05f, Math.min(0.95f, cv))};
    }

    private float[] contentToSensor(float u, float v) {
        int W = cam.W, H = cam.H;
        switch (cam.sensorOrientation) {
            case 90:  return new float[]{v * W, (1 - u) * H};
            case 270: return new float[]{(1 - v) * W, u * H};
            case 180: return new float[]{(1 - u) * W, (1 - v) * H};
            default:  return new float[]{u * W, v * H};
        }
    }

    private void startSweep() {
        if (state != ST_PREVIEW || cam == null || !cameraStarted) return;
        state = ST_CAPTURE;
        float[] fc = focusContent();
        shotU = fc[0]; shotV = fc[1];
        float[] fs = contentToSensor(shotU, shotV);
        recorder = new SweepRecorder(cam.W, cam.H, fs[0], fs[1], DEPTH_FACTORS[depthIdx], this);
        cam.lock();
        focus.setEnabled(false);
        shutter.setEnabled(false);
        shutter.setText("SLIDE");
        depthBtn.setEnabled(false);
        thumb.setEnabled(false);
        hintChip.setVisibility(View.GONE);
        lastLens = 1;
        hud.set(0, "SLIDE ←→", false);
        hud.setVisibility(View.VISIBLE);
        buzz(25);
        final SweepRecorder r = recorder;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (state == ST_CAPTURE && recorder == r && cam != null) cam.sink = r;
        }}, 180);
    }

    @Override public void onSweepProgress(final float progress, final int coach, final float parallax) {
        runOnUiThread(new Runnable() { @Override public void run() {
            if (state != ST_CAPTURE) return;
            String t; boolean warn = false;
            switch (coach) {
                case SweepRecorder.COACH_TURNING: t = "DON'T TURN · SLIDE"; warn = true; break;
                case SweepRecorder.COACH_FAST: t = "SLOWER!"; warn = true; break;
                case SweepRecorder.COACH_EDGE: t = "KEEP IT IN FRAME"; warn = true; break;
                default: t = "SLIDE ←→";
            }
            hud.set(progress, t, warn);
            int lens = hud.lensCount();
            if (lens > lastLens) { lastLens = lens; buzz(18); }
        }});
    }

    @Override public void onSweepDone(final SweepRecorder r) {
        if (cam != null) cam.sink = null;
        runOnUiThread(new Runnable() { @Override public void run() {
            if (state != ST_CAPTURE || recorder != r) return;
            if (cam != null) cam.unlock();
            buzz(45);
            state = ST_PROCESS;
            hud.set(1, "STITCHING…", false);
            shutter.setText("· · ·");
            final int orient = cam != null ? cam.sensorOrientation : 90;
            final float u = shotU, v = shotV;
            final float targetAspect = ASPECTS[aspectIdx];
            new Thread(new Runnable() { @Override public void run() {
                String err = null;
                Composer comp = null;
                Bitmap[] out = null;
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
                        out = comp.render();
                    }
                } catch (Throwable t) {
                    err = "BUILD FAILED: " + t.getClass().getSimpleName();
                }
                r.release();
                final String fe = err; final Composer fc = comp; final Bitmap[] fo = out; final boolean fl = low;
                runOnUiThread(new Runnable() { @Override public void run() {
                    recorder = null;
                    if (state != ST_PROCESS) return;
                    if (fe != null) { setPreviewState(); toast("✕ " + fe); return; }
                    composer = fc; outFrames = fo;
                    thumb.setBitmap(fo[0]);
                    showResult(fl);
                }});
            }}, "aurelius-build").start();
        }});
    }

    // ================================================================== result
    private void showResult(boolean lowDepth) {
        state = ST_RESULT;
        camLayer.setVisibility(View.GONE);
        resultLayer.setVisibility(View.VISIBLE);
        String st = "4 VIEWS · " + Math.round(composer.parallaxPx) + "PX DEPTH";
        if (lowDepth) st = "LOW DEPTH · GET CLOSER";
        statsChip.setText(st);
        statsChip.requestLayout();
        markDirty();
        syncStageFocus();
        startLoop();
    }

    private void reopenLast() {
        if (state != ST_PREVIEW) return;
        if (composer == null) { toast("NO SHOTS YET · HIT SHOOT"); return; }
        outFrames = composer.render();
        showResult(false);
    }

    private void syncStageFocus() {
        if (composer == null) return;
        stage.focusBx = composer.fu * composer.W - composer.cropX;
        stage.focusBy = composer.fv * composer.H - composer.cropY;
        stage.invalidate();
    }

    private void refocus(float bx, float by, boolean done) {
        if (composer == null || state != ST_RESULT) return;
        long now = SystemClock.uptimeMillis();
        if (!done && now - lastAlign < 120) return;
        lastAlign = now;
        float u = (composer.cropX + bx) / composer.W, v = (composer.cropY + by) / composer.H;
        composer.align(u, v);
        outFrames = composer.render();
        if (done) syncStageFocus();
        markDirty();
    }

    private final Runnable loopTick = new Runnable() { @Override public void run() {
        if (outFrames == null || state != ST_RESULT) return;
        stage.setFrame(outFrames[seq[loopPos % seq.length]]);
        loopPos++;
        ui.postDelayed(this, frameMs);
    }};

    private void startLoop() { ui.removeCallbacks(loopTick); loopPos = 0; ui.post(loopTick); }
    private void stopLoop() { ui.removeCallbacks(loopTick); }

    private void retake() {
        if (saving) return;
        setPreviewState();
    }

    @Override public void onBackPressed() {
        if (state == ST_RESULT) { retake(); return; }
        super.onBackPressed();
    }

    // ================================================================== self-update
    private void checkForUpdate() {
        new Thread(new Runnable() { @Override public void run() {
            final Updater.Info info = Updater.check(versionCode);
            if (info == null) return;
            runOnUiThread(new Runnable() { @Override public void run() {
                pendingUpdate = info;
                updateBtn.setText("▲ UPDATE → v" + info.name);
                updateBtn.requestLayout();
                updateBtn.setVisibility(View.VISIBLE);
            }});
        }}, "aurelius-update-check").start();
    }

    private void startUpdate() {
        if (pendingUpdate == null || updating) return;
        updating = true;
        updateBtn.setEnabled(false);
        updateBtn.setText("DOWNLOADING 0%");
        final Updater.Info info = pendingUpdate;
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            try {
                Updater.install(MainActivity.this, info, new Updater.Progress() {
                    @Override public void onProgress(final int pct) {
                        runOnUiThread(new Runnable() { @Override public void run() {
                            updateBtn.setText("DOWNLOADING " + pct + "%"); updateBtn.requestLayout(); }});
                    }
                });
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String fe = err;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (fe != null) {
                    updating = false;
                    updateBtn.setEnabled(true);
                    updateBtn.setText("✕ RETRY UPDATE");
                } else {
                    updateBtn.setText("INSTALLING…");
                }
                updateBtn.requestLayout();
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
            updateBtn.setEnabled(true);
            updateBtn.setText("✕ INSTALL CANCELLED · RETRY");
            updateBtn.requestLayout();
        }
    }

    // ================================================================== save / share
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
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "AURELIUS7_" + stampName + ".mp4");
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                    cv.put("relative_path", "Movies/Aurelius7");
                    coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else {
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "AURELIUS7_" + stampName + ".gif");
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/gif");
                    cv.put("relative_path", "Pictures/Aurelius7");
                    coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                cv.put("is_pending", 1);
                uri = cr.insert(coll, cv);
                if (uri == null) throw new RuntimeException("MEDIASTORE INSERT FAILED");
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
                if (fu == null) {
                    saveBtn.setText("SAVE");
                    statsChip.setText("✕ SAVE FAILED: " + fe);
                    statsChip.requestLayout();
                    return;
                }
                savedUri = fu; savedMime = mime;
                saveBtn.setText("SAVED ✓");
                showStamp("SAVED ✓");
                buzz(25);
                if (thenShare) share(fu, mime);
            }});
        }}, "aurelius-export").start();
    }

    private void showStamp(String t) {
        stamp.setText(t);
        stamp.setVisibility(View.VISIBLE);
        stamp.setScaleX(1.8f); stamp.setScaleY(1.8f); stamp.setAlpha(0f);
        stamp.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
        ui.postDelayed(new Runnable() { @Override public void run() {
            stamp.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
                @Override public void run() { stamp.setVisibility(View.GONE); } }).start();
        }}, 850);
    }

    private void share(Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "SHARE WIGGLE"));
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Exception ignored) {}
    }
}
