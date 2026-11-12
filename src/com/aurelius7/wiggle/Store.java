package com.aurelius7.wiggle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * App-private storage:
 *  - wiggles/<name>/k.jpg : the 4 developed views of each saved wiggle (for the tilt viewer + print sheet)
 *  - roll/<timestamp>/k.jpg + meta : undeveloped exposures in roll mode
 */
public final class Store {
    private Store() {}

    public static final int ROLL_SIZE = 24;

    public static File wiggleDir(Context c, String name) { return new File(new File(c.getFilesDir(), "wiggles"), name); }
    public static File rollRoot(Context c) { return new File(c.getFilesDir(), "roll"); }

    public static void saveFrames(File dir, Bitmap[] frames, int quality) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("mkdir failed");
        for (int k = 0; k < frames.length; k++) {
            FileOutputStream o = new FileOutputStream(new File(dir, k + ".jpg"));
            try { frames[k].compress(Bitmap.CompressFormat.JPEG, quality, o); } finally { o.close(); }
        }
    }

    public static Bitmap[] loadFrames(File dir) {
        Bitmap[] b = new Bitmap[4];
        for (int k = 0; k < 4; k++) {
            File f = new File(dir, k + ".jpg");
            if (!f.exists()) return null;
            b[k] = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b[k] == null) return null;
        }
        return b;
    }

    public static boolean hasFrames(File dir) { return new File(dir, "3.jpg").exists(); }

    public static void writeMeta(File dir, int stock) {
        try { FileWriter w = new FileWriter(new File(dir, "meta")); try { w.write(String.valueOf(stock)); } finally { w.close(); } }
        catch (Exception ignored) {}
    }

    public static int readMeta(File dir, int def) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(new File(dir, "meta")));
            try { return Integer.parseInt(r.readLine().trim()); } finally { r.close(); }
        } catch (Exception e) { return def; }
    }

    public static List<File> rollShots(Context c) {
        List<File> out = new ArrayList<File>();
        File[] fs = rollRoot(c).listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) if (f.isDirectory() && hasFrames(f)) out.add(f);
        return out;
    }

    public static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        d.delete();
    }
}
