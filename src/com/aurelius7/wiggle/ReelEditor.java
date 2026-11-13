package com.aurelius7.wiggle;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Reel editor: line up saved wiggles on a timeline, set loops / speed per clip, one shared film grade,
 * cut / crossfade / dip transitions, export one MP4 (9:16, 4:5 or 1:1).
 */
public final class ReelEditor {
    private static final class Clip {
        File dir; String name; Bitmap thumb; int loops = 2, fps = 9;
    }

    private final MainActivity a;
    private FrameLayout screen;
    private ImageView preview;
    private TextView sub, status, emptyHint;
    private LinearLayout timeline;
    private HorizontalScrollView tlScroll;
    private W.Slider loopsSl, fpsSl, grainSl, strengthSl;
    private TextView loopsVal, fpsVal, grainVal, strengthVal;
    private W.Pill[] stockPills;
    private HorizontalScrollView stockScroll;
    private W.Segmented transSeg, ratioSeg;
    private W.Pill exportBtn, shareBtn, moveL, moveR, removeBtn;
    private FrameLayout picker;
    private LinearLayout pickGrid;

    private final List<Clip> clips = new ArrayList<Clip>();
    private int sel = -1, stock, grain, strength = 100, trans = 1, aspect = 0, previewGen;
    private Bitmap[] pv;
    private int pvPos;
    private boolean exporting, loaded;
    private Uri lastUri;

    ReelEditor(MainActivity act) { a = act; }

    private int dp(float v) { return a.dp(v); }

    // ------------------------------------------------------------------ build
    View build() {
        screen = new FrameLayout(a);
        screen.setBackgroundColor(W.BG);
        screen.setClickable(true);

        LinearLayout s = a.col();
        s.setPadding(dp(16), dp(14), dp(16), dp(12));
        screen.addView(s, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = a.row();
        W.IconBtn back = new W.IconBtn(a, W.IC_BACK, "Back");
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onBack(); } });
        head.addView(back, a.lp(dp(44), dp(44)));
        LinearLayout tc = a.col();
        tc.addView(a.text("Reel editor", 20, W.TXT, W.HEAD));
        sub = a.text("", 11, W.MUT, W.DOT);
        LinearLayout.LayoutParams sl = a.lp(-2, -2); sl.topMargin = dp(3);
        tc.addView(sub, sl);
        LinearLayout.LayoutParams tcl = new LinearLayout.LayoutParams(0, -2, 1); tcl.leftMargin = dp(12);
        head.addView(tc, tcl);
        s.addView(head);

        FrameLayout pf = new FrameLayout(a);
        pf.setBackgroundColor(W.FILM);
        preview = new ImageView(a);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pf.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        emptyHint = a.text("Add clips from your contact sheet.\nEach one becomes a loop in the reel.", 13, W.MUT, W.UI);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setLineSpacing(0, 1.4f);
        pf.addView(emptyHint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout.LayoutParams pfl = new LinearLayout.LayoutParams(-1, 0, 1);
        pfl.topMargin = dp(12);
        s.addView(pf, pfl);

        tlScroll = new HorizontalScrollView(a);
        tlScroll.setHorizontalScrollBarEnabled(false);
        timeline = a.row();
        tlScroll.addView(timeline, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tll = a.lp(-1, dp(104)); tll.topMargin = dp(10);
        s.addView(tlScroll, tll);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = a.col();
        sv.addView(c, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1);
        svl.topMargin = dp(8);
        s.addView(sv, svl);

        // --- selected clip
        c.addView(label("CLIP"));
        LinearLayout acts = a.row();
        moveL = a.pill("Earlier", W.IC_NONE, W.Pill.GHOST, 12, false);
        moveR = a.pill("Later", W.IC_NONE, W.Pill.GHOST, 12, false);
        removeBtn = a.pill("Remove", W.IC_NONE, W.Pill.GHOST, 12, false);
        moveL.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { move(-1); } });
        moveR.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { move(1); } });
        removeBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { remove(); } });
        acts.addView(moveL, a.lpw(dp(34), 1));
        LinearLayout.LayoutParams m2 = a.lpw(dp(34), 1); m2.leftMargin = dp(8);
        acts.addView(moveR, m2);
        LinearLayout.LayoutParams m3 = a.lpw(dp(34), 1); m3.leftMargin = dp(8);
        acts.addView(removeBtn, m3);
        c.addView(acts, a.lp(-1, -2));

        loopsVal = a.text("2", 12, W.TXT, W.DOT);
        loopsSl = slider(c, "Loops", loopsVal, new W.Slider.OnChange() { @Override public void onChange(float v, boolean done) {
            Clip k = cur(); if (k == null) return;
            k.loops = 1 + Math.round(v * 7); loopsVal.setText(String.valueOf(k.loops));
            if (done) { persist(); refresh(false); } }});
        fpsVal = a.text("9FPS", 12, W.TXT, W.DOT);
        fpsSl = slider(c, "Speed", fpsVal, new W.Slider.OnChange() { @Override public void onChange(float v, boolean done) {
            Clip k = cur(); if (k == null) return;
            k.fps = 4 + Math.round(v * 12); fpsVal.setText(k.fps + "FPS");
            if (done) { persist(); refresh(false); } }});

        // --- grade
        c.addView(label("GRADE · applied over every clip"));
        LinearLayout pills = a.row();
        stockPills = new W.Pill[Develop.STOCKS.length];
        for (int i = 0; i < stockPills.length; i++) {
            final int idx = i;
            stockPills[i] = a.pill(i == 0 ? "NONE" : Develop.STOCKS[i], W.IC_NONE, W.Pill.GHOST, 11, true);
            stockPills[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                stock = idx; grain = idx == 0 ? 0 : Develop.STOCK_GRAIN[idx]; strength = 100; syncGrade(); persist(); applyPreviewGrade(); }});
            LinearLayout.LayoutParams p = a.lp(-2, dp(34)); if (i > 0) p.leftMargin = dp(6);
            pills.addView(stockPills[i], p);
        }
        stockScroll = new HorizontalScrollView(a);
        stockScroll.setHorizontalScrollBarEnabled(false);
        stockScroll.addView(pills, new FrameLayout.LayoutParams(-2, -2));
        c.addView(stockScroll, a.lp(-1, -2));
        grainVal = a.text("0", 12, W.TXT, W.DOT);
        grainSl = slider(c, "Grain", grainVal, new W.Slider.OnChange() { @Override public void onChange(float v, boolean done) {
            grain = Math.round(v * 100); grainVal.setText(String.valueOf(grain)); if (done) persist(); }});
        strengthVal = a.text("100%", 12, W.TXT, W.DOT);
        strengthSl = slider(c, "Strength", strengthVal, new W.Slider.OnChange() { @Override public void onChange(float v, boolean done) {
            strength = Math.round(v * 100); strengthVal.setText(strength + "%"); if (done) persist(); }});

        // --- transitions / frame
        c.addView(label("TRANSITION · FRAME"));
        LinearLayout segs = a.row();
        transSeg = new W.Segmented(a, new String[]{"CUT", "FADE", "DIP"}, trans, true);
        transSeg.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { trans = i; persist(); refresh(false); } };
        segs.addView(transSeg, new LinearLayout.LayoutParams(0, -2, 1));
        ratioSeg = new W.Segmented(a, new String[]{"9:16", "4:5", "1:1"}, aspect, true);
        ratioSeg.listener = new W.Segmented.OnPick() { @Override public void onPick(int i) { aspect = i; persist(); refreshPreviewRatio(); } };
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -2, 1); rl.leftMargin = dp(8);
        segs.addView(ratioSeg, rl);
        c.addView(segs, a.lp(-1, -2));

        // --- export bar
        status = a.text("", 11, W.MUT, W.DOT);
        LinearLayout.LayoutParams stl = a.lp(-1, -2); stl.topMargin = dp(8);
        s.addView(status, stl);
        LinearLayout bar = a.row();
        shareBtn = a.bigPill("Share", W.Pill.GHOST);
        shareBtn.setVisibility(View.GONE);
        shareBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (lastUri != null) a.share(lastUri, "video/mp4"); }});
        exportBtn = a.bigPill("Export reel", W.Pill.SOLID);
        exportBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { export(); } });
        bar.addView(shareBtn, a.lpw(dp(54), 1));
        LinearLayout.LayoutParams el = a.lpw(dp(54), 2); el.leftMargin = dp(10);
        bar.addView(exportBtn, el);
        LinearLayout.LayoutParams bl = a.lp(-1, -2); bl.topMargin = dp(8);
        s.addView(bar, bl);

        buildPicker();
        return screen;
    }

    private TextView label(String t) {
        TextView l = a.text(t, 10, W.MUT, W.DOT);
        LinearLayout.LayoutParams p = a.lp(-2, -2); p.topMargin = dp(14); p.bottomMargin = dp(8);
        l.setLayoutParams(p);
        return l;
    }

    private W.Slider slider(LinearLayout parent, String name, TextView val, W.Slider.OnChange l) {
        LinearLayout r = a.row();
        r.addView(a.text(name, 12, W.MUT, W.UI), a.lp(dp(64), -2));
        W.Slider s = new W.Slider(a);
        s.setContentDescription(name);
        s.listener = l;
        r.addView(s, a.lpw(dp(36), 1));
        val.setGravity(Gravity.END);
        r.addView(val, a.lp(dp(46), -2));
        parent.addView(r, a.lp(-1, -2));
        return s;
    }

    // ------------------------------------------------------------------ picker
    private void buildPicker() {
        picker = new FrameLayout(a);
        picker.setBackgroundColor(0xCC000000);
        picker.setClickable(true);
        picker.setVisibility(View.GONE);
        LinearLayout panel = a.col();
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(W.SURF);
        float r = dp(26);
        bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bgd);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout h = a.row();
        h.addView(a.text("Add clips", 20, W.TXT, W.HEAD), new LinearLayout.LayoutParams(0, -2, 1));
        W.Pill all = a.pill("Add all", W.IC_NONE, W.Pill.GHOST, 13, false);
        all.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { addAll(); } });
        h.addView(all, a.lp(-2, dp(36)));
        W.Pill done = a.pill("Done", W.IC_NONE, W.Pill.SOLID, 13, false);
        done.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { picker.setVisibility(View.GONE); } });
        LinearLayout.LayoutParams dl = a.lp(-2, dp(36)); dl.leftMargin = dp(8);
        h.addView(done, dl);
        panel.addView(h);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        pickGrid = a.col();
        sv.addView(pickGrid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svl = a.lp(-1, dp(420)); svl.topMargin = dp(12);
        panel.addView(sv, svl);
        picker.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        screen.addView(picker, new FrameLayout.LayoutParams(-1, -1));
    }

    private List<Clip> library() {
        List<Clip> out = new ArrayList<Clip>();
        File[] ds = new File(a.getFilesDir(), "wiggles").listFiles();
        if (ds == null) return out;
        Arrays.sort(ds);
        Collections.reverse(Arrays.asList(ds));
        for (File d : ds) {
            if (!d.isDirectory() || !Store.hasFrames(d)) continue;
            Clip k = new Clip();
            k.dir = d; k.name = d.getName();
            k.thumb = thumb(d);
            if (k.thumb != null) out.add(k);
        }
        return out;
    }

    private Bitmap thumb(File dir) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            File f = new File(dir, "1.jpg");
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 240) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) { return null; }
    }

    private List<Clip> libCache = new ArrayList<Clip>();

    private void openPicker() {
        picker.setVisibility(View.VISIBLE);
        pickGrid.removeAllViews();
        final TextView loading = a.text("Loading…", 13, W.MUT, W.UI);
        pickGrid.addView(loading);
        a.bgRun(new Runnable() { @Override public void run() {
            final List<Clip> lib = library();
            a.ui.post(new Runnable() { @Override public void run() { libCache = lib; fillPicker(); } });
        }});
    }

    private void fillPicker() {
        pickGrid.removeAllViews();
        if (libCache.isEmpty()) {
            TextView t = a.text("No saved wiggles yet.\nShoot one and save it — it shows up here.", 13, W.MUT, W.UI);
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams p = a.lp(-1, -2); p.topMargin = dp(40);
            pickGrid.addView(t, p);
            return;
        }
        int cell = (a.getResources().getDisplayMetrics().widthPixels - dp(32) - dp(16)) / 3;
        for (int i = 0; i < libCache.size(); i += 3) {
            LinearLayout r = a.row();
            for (int k = 0; k < 3; k++) {
                if (i + k >= libCache.size()) break;
                final Clip src = libCache.get(i + k);
                FrameLayout f = new FrameLayout(a);
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(src.thumb);
                iv.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }});
                iv.setClipToOutline(true);
                f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                final TextView badge = a.text(countOf(src.name) > 0 ? "×" + countOf(src.name) : "", 11, W.INK, W.DOT);
                badge.setBackgroundColor(W.ACC);
                badge.setPadding(dp(6), dp(3), dp(6), dp(3));
                f.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
                badge.setVisibility(countOf(src.name) > 0 ? View.VISIBLE : View.GONE);
                f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    addClip(src.dir, src.name, src.thumb);
                    int n = countOf(src.name);
                    badge.setText("×" + n); badge.setVisibility(View.VISIBLE);
                }});
                LinearLayout.LayoutParams p = a.lp(cell, Math.round(cell * 1.33f)); if (k > 0) p.leftMargin = dp(8);
                r.addView(f, p);
            }
            LinearLayout.LayoutParams rp = a.lp(-1, -2); rp.bottomMargin = dp(8);
            pickGrid.addView(r, rp);
        }
    }

    private int countOf(String name) {
        int n = 0;
        for (Clip c : clips) if (c.name.equals(name)) n++;
        return n;
    }

    private void addAll() {
        for (int i = libCache.size() - 1; i >= 0; i--) {     // oldest first
            Clip s = libCache.get(i);
            if (countOf(s.name) == 0) addClip(s.dir, s.name, s.thumb);
        }
        fillPicker();
    }

    // ------------------------------------------------------------------ model
    private Clip cur() { return sel >= 0 && sel < clips.size() ? clips.get(sel) : null; }

    private void addClip(File dir, String name, Bitmap thumb) {
        Clip k = new Clip();
        k.dir = dir; k.name = name; k.thumb = thumb;
        clips.add(k);
        sel = clips.size() - 1;
        persist();
        refresh(true);
    }

    private void remove() {
        if (cur() == null) return;
        clips.remove(sel);
        if (sel >= clips.size()) sel = clips.size() - 1;
        persist();
        refresh(true);
    }

    private void move(int d) {
        int j = sel + d;
        if (cur() == null || j < 0 || j >= clips.size()) return;
        Collections.swap(clips, sel, j);
        sel = j;
        persist();
        refresh(false);
    }

    private void persist() {
        try {
            FileWriter w = new FileWriter(new File(a.getFilesDir(), "reel.txt"));
            try { for (Clip c : clips) w.write(c.name + "|" + c.loops + "|" + c.fps + "\n"); } finally { w.close(); }
        } catch (Exception ignored) {}
        a.prefs.edit().putInt("reelStock", stock).putInt("reelGrain", grain).putInt("reelStrength", strength)
                .putInt("reelTrans", trans).putInt("reelAspect", aspect).apply();
    }

    private void restore() {
        stock = Math.min(Develop.STOCKS.length - 1, a.prefs.getInt("reelStock", 0));
        grain = a.prefs.getInt("reelGrain", 0);
        strength = a.prefs.getInt("reelStrength", 100);
        trans = a.prefs.getInt("reelTrans", 1);
        aspect = a.prefs.getInt("reelAspect", 0);
        transSeg.setSel(trans); ratioSeg.setSel(aspect);
        clips.clear();
        File root = new File(a.getFilesDir(), "wiggles");
        try {
            BufferedReader r = new BufferedReader(new FileReader(new File(a.getFilesDir(), "reel.txt")));
            try {
                String ln;
                while ((ln = r.readLine()) != null) {
                    String[] p = ln.split("\\|");
                    if (p.length < 3 || p[0].contains("/") || p[0].contains("..")) continue;   // names only, never paths
                    File d = new File(root, p[0]);
                    if (!Store.hasFrames(d)) continue;
                    Clip k = new Clip();
                    k.dir = d; k.name = p[0];
                    k.loops = Math.max(1, Math.min(8, Integer.parseInt(p[1])));
                    k.fps = Math.max(4, Math.min(16, Integer.parseInt(p[2])));
                    k.thumb = thumb(d);
                    if (k.thumb != null) clips.add(k);
                }
            } finally { r.close(); }
        } catch (Exception ignored) {}
        sel = clips.isEmpty() ? -1 : 0;
    }

    // ------------------------------------------------------------------ screen state
    void onShow() {
        if (!loaded) { loaded = true; restore(); }
        status.setText(""); shareBtn.setVisibility(View.GONE); lastUri = null;
        refresh(true);
        syncGrade();
        refreshPreviewRatio();
    }

    void onBack() {
        if (exporting) return;
        if (picker.getVisibility() == View.VISIBLE) { picker.setVisibility(View.GONE); return; }
        a.showGallery();
    }

    private boolean bounce() { return a.prefs.getInt("loop", 0) == 0; }

    private float totalSeconds() {
        int len = bounce() ? 6 : 4;
        float t = 0;
        for (Clip c : clips) t += c.loops * len / (float) c.fps;
        int gaps = Math.max(0, clips.size() - 1);
        if (trans == 1) t += gaps * 6 * 0.04f; else if (trans == 2) t += gaps * 8 * 0.04f;
        return t;
    }

    private void refresh(boolean reloadPreview) {
        boolean has = !clips.isEmpty();
        emptyHint.setVisibility(has ? View.GONE : View.VISIBLE);
        float t = totalSeconds();
        sub.setText((clips.size() + (clips.size() == 1 ? " CLIP · " : " CLIPS · ") + String.format(Locale.US, "%.1fS", t)
                + (has && t < 3f ? " · REELS WANT 3S+" : "")));
        exportBtn.setEnabled(has && !exporting);
        boolean one = cur() != null;
        moveL.setEnabled(one && sel > 0); moveR.setEnabled(one && sel < clips.size() - 1); removeBtn.setEnabled(one);
        loopsSl.setEnabled(one); fpsSl.setEnabled(one);
        if (one) {
            loopsSl.setValue((cur().loops - 1) / 7f); loopsVal.setText(String.valueOf(cur().loops));
            fpsSl.setValue((cur().fps - 4) / 12f); fpsVal.setText(cur().fps + "FPS");
        }
        buildTimeline();
        if (reloadPreview) loadPreview();
    }

    private void buildTimeline() {
        timeline.removeAllViews();
        for (int i = 0; i < clips.size(); i++) {
            final int idx = i;
            Clip c = clips.get(i);
            FrameLayout card = new FrameLayout(a);
            GradientDrawable g = new GradientDrawable();
            g.setColor(W.SURF);
            g.setCornerRadius(dp(12));
            g.setStroke(dp(i == sel ? 2.5f : 1), i == sel ? W.ACC : W.LINE);
            card.setBackground(g);
            card.setPadding(dp(3), dp(3), dp(3), dp(3));
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(c.thumb);
            iv.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(9)); }});
            iv.setClipToOutline(true);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            TextView b = a.text("×" + c.loops, 10, W.INK, W.DOT);
            b.setBackgroundColor(W.ACC);
            b.setPadding(dp(5), dp(2), dp(5), dp(2));
            card.addView(b, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
            card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sel = idx; refresh(true); } });
            LinearLayout.LayoutParams p = a.lp(dp(66), dp(98)); if (i > 0) p.leftMargin = dp(8);
            timeline.addView(card, p);
        }
        W.Pill add = a.pill("+ Add", W.IC_NONE, W.Pill.GHOST, 13, false);
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openPicker(); } });
        LinearLayout.LayoutParams ap = a.lp(dp(76), dp(98)); if (!clips.isEmpty()) ap.leftMargin = dp(8);
        timeline.addView(add, ap);
        if (sel >= 0 && sel < timeline.getChildCount() - 1) {
            final View cardv = timeline.getChildAt(sel);
            tlScroll.post(new Runnable() { @Override public void run() {
                tlScroll.smoothScrollTo(Math.max(0, cardv.getLeft() - dp(80)), 0); }});
        }
    }

    private void syncGrade() {
        for (int i = 0; i < stockPills.length; i++) stockPills[i].setSel(i == stock);
        grainSl.setValue(grain / 100f); grainVal.setText(String.valueOf(grain));
        strengthSl.setValue(strength / 100f); strengthVal.setText(strength + "%");
        final View t = stockPills[stock];
        stockScroll.post(new Runnable() { @Override public void run() {
            stockScroll.smoothScrollTo(Math.max(0, t.getLeft() - dp(60)), 0); }});
    }

    // ------------------------------------------------------------------ preview
    private void applyPreviewGrade() {
        preview.setColorFilter(stock == 0 ? null : new ColorMatrixColorFilter(Develop.matrix(stock)));
    }

    private void refreshPreviewRatio() { applyPreviewGrade(); }

    private void loadPreview() {
        final int gen = ++previewGen;
        final Clip c = cur();
        a.ui.removeCallbacks(tick);
        pv = null;
        if (c == null) { preview.setImageBitmap(null); return; }
        preview.setImageBitmap(c.thumb);
        applyPreviewGrade();
        a.bgRun(new Runnable() { @Override public void run() {
            final Bitmap[] f = new Bitmap[4];
            for (int k = 0; k < 4; k++) {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    f[k] = BitmapFactory.decodeFile(new File(c.dir, k + ".jpg").getAbsolutePath(), o);
                } catch (Throwable t) { f[k] = null; }
                if (f[k] == null) return;
            }
            a.ui.post(new Runnable() { @Override public void run() {
                if (gen != previewGen) return;
                pv = f; pvPos = 0;
                a.ui.removeCallbacks(tick);
                a.ui.post(tick);
            }});
        }});
    }

    private final Runnable tick = new Runnable() { @Override public void run() {
        Clip c = cur();
        if (pv == null || c == null || !a.isShowing(screen)) return;
        int[] sq = bounce() ? new int[]{0, 1, 2, 3, 2, 1} : new int[]{0, 1, 2, 3};
        preview.setImageBitmap(pv[sq[pvPos % sq.length]]);
        pvPos++;
        a.ui.postDelayed(this, 1000 / c.fps);
    }};

    // ------------------------------------------------------------------ export
    private void export() {
        if (exporting || clips.isEmpty()) return;
        exporting = true;
        exportBtn.setEnabled(false); exportBtn.setText("Rendering…");
        shareBtn.setVisibility(View.GONE);
        final List<Clip> cl = new ArrayList<Clip>(clips);
        final int st = stock, gr = grain, str = strength, tr = trans, asp = aspect;
        final boolean bnc = bounce();
        new Thread(new Runnable() { @Override public void run() {
            Uri u = null; String err = null;
            try { u = render(cl, st, gr, str, tr, asp, bnc); }
            catch (Throwable t) { err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(); }
            final Uri fu = u; final String fe = err;
            a.runOnUiThread(new Runnable() { @Override public void run() {
                exporting = false;
                exportBtn.setText("Export reel"); exportBtn.setEnabled(!clips.isEmpty());
                if (fu == null) { status.setText("EXPORT FAILED: " + fe); return; }
                lastUri = fu;
                status.setText("SAVED TO MOVIES/AURELIUS7");
                shareBtn.setVisibility(View.VISIBLE);
                a.buzz(25);
                a.loadGallery();
            }});
        }}, "aurelius-reel").start();
    }

    private void say(final String t) {
        a.runOnUiThread(new Runnable() { @Override public void run() { status.setText(t); } });
    }

    private Uri render(List<Clip> cl, int st, int gr, int str, int tr, int asp, boolean bnc) throws Exception {
        final int W0 = 720, H0 = asp == 0 ? 1280 : (asp == 1 ? 896 : 720);
        int[] sq = bnc ? new int[]{0, 1, 2, 3, 2, 1} : new int[]{0, 1, 2, 3};
        File tmp = new File(a.getCacheDir(), "reel_" + System.currentTimeMillis() + ".mp4");
        ReelEncoder enc = new ReelEncoder(tmp, W0, H0);
        Bitmap prevLast = null, scratch = null;
        try {
            int n = 0;
            for (Clip c : cl) {
                say("RENDERING " + (++n) + "/" + cl.size());
                Bitmap[] raw = Store.loadFrames(c.dir);
                if (raw == null) continue;
                Bitmap[] fit = new Bitmap[4];
                for (int k = 0; k < 4; k++) { fit[k] = fitTo(raw[k], W0, H0); raw[k].recycle(); }
                Bitmap[] fr = fit;
                if (st != 0 || gr > 0) {
                    fr = Develop.apply(fit, st, gr, str, false, null);
                    for (Bitmap b : fit) b.recycle();
                }
                if (prevLast != null && tr != 0) {
                    if (scratch == null) scratch = Bitmap.createBitmap(W0, H0, Bitmap.Config.ARGB_8888);
                    transition(enc, scratch, prevLast, fr[sq[0]], tr);
                }
                int ms = Math.max(30, 1000 / c.fps);
                for (int l = 0; l < c.loops; l++) for (int i : sq) enc.add(fr[i], ms);
                if (prevLast != null) prevLast.recycle();
                prevLast = fr[sq[sq.length - 1]].copy(Bitmap.Config.ARGB_8888, false);
                for (Bitmap b : fr) b.recycle();
            }
            if (prevLast != null) prevLast.recycle();
            if (scratch != null) scratch.recycle();
            say("FINISHING…");
            enc.finish();
            return publish(tmp);
        } catch (Throwable t) {
            enc.close();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t.getMessage() != null ? t.getMessage() : "Out of memory");
        } finally {
            tmp.delete();
        }
    }

    private static void transition(ReelEncoder enc, Bitmap scratch, Bitmap from, Bitmap to, int mode) {
        Canvas c = new Canvas(scratch);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (mode == 1) {
            int steps = 6;
            for (int i = 1; i <= steps; i++) {
                c.drawBitmap(from, 0, 0, null);
                p.setAlpha(Math.round(255f * i / (steps + 1)));
                c.drawBitmap(to, 0, 0, p);
                enc.add(scratch, 40);
            }
        } else {
            int steps = 4;
            for (int i = 1; i <= steps; i++) {
                c.drawBitmap(from, 0, 0, null);
                c.drawColor(Math.round(255f * i / (steps + 1)) << 24);
                enc.add(scratch, 40);
            }
            for (int i = steps; i >= 1; i--) {
                c.drawBitmap(to, 0, 0, null);
                c.drawColor(Math.round(255f * i / (steps + 1)) << 24);
                enc.add(scratch, 40);
            }
        }
    }

    /** Centre-crop to exactly w x h. */
    private static Bitmap fitTo(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float sa = src.getWidth() / (float) src.getHeight(), da = w / (float) h;
        Rect s;
        if (sa > da) { int cw = Math.round(src.getHeight() * da); int x = (src.getWidth() - cw) / 2; s = new Rect(x, 0, x + cw, src.getHeight()); }
        else { int ch = Math.round(src.getWidth() / da); int y = (src.getHeight() - ch) / 2; s = new Rect(0, y, src.getWidth(), y + ch); }
        new Canvas(out).drawBitmap(src, s, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private Uri publish(File tmp) throws Exception {
        ContentResolver cr = a.getContentResolver();
        String name = "AURELIUS7_REEL_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        cv.put("relative_path", "Movies/Aurelius7");
        cv.put("is_pending", 1);
        Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new RuntimeException("MediaStore insert failed");
        try {
            OutputStream os = cr.openOutputStream(uri);
            try {
                InputStream in = new FileInputStream(tmp);
                try { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n); }
                finally { in.close(); }
            } finally { os.close(); }
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (Exception e) {
            try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            throw e;
        }
    }
}
