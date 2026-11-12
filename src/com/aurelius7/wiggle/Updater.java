package com.aurelius7.wiggle;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-updater: reads latest.json from the public aurelius7-releases repo, and installs the newer APK
 * through PackageInstaller (Android still shows its own confirm dialog — that can't be skipped).
 */
public final class Updater {
    private Updater() {}

    public static final String LATEST_URL =
            "https://raw.githubusercontent.com/Siddz-17/aurelius7-releases/main/latest.json";
    public static final String ACTION_STATUS = "com.aurelius7.wiggle.INSTALL_STATUS";

    public static class Info { public int code; public String name, url, notes; }

    public interface Progress { void onProgress(int pct); }

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    /** Blocking; call off the UI thread. Returns null when there's nothing newer (or offline). */
    public static Info check(int currentCode) {
        HttpURLConnection c = null;
        try {
            c = open(LATEST_URL + "?t=" + System.currentTimeMillis());
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            Info i = new Info();
            i.code = j.getInt("versionCode");
            i.name = j.optString("versionName", "?");
            i.url = j.getString("url");
            i.notes = j.optString("notes", "");
            return i.code > currentCode ? i : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Blocking; downloads straight into an install session and commits it. */
    public static void install(Activity act, Info info, Progress p) throws Exception {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(act.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        HttpURLConnection c = null;
        try {
            c = open(info.url);
            if (c.getResponseCode() != 200) throw new RuntimeException("HTTP " + c.getResponseCode());
            int total = c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream out = s.openWrite("aurelius7.apk", 0, total > 0 ? total : -1);
            byte[] buf = new byte[1 << 16]; int n; long done = 0; int last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != last) { last = pct; p.onProgress(pct); }
                }
            }
            s.fsync(out);
            out.close();
            in.close();
            Intent i = new Intent(act, MainActivity.class).setAction(ACTION_STATUS);
            // FLAG_UPDATE_CURRENT | FLAG_MUTABLE (the installer fills in the status extras)
            PendingIntent pend = PendingIntent.getActivity(act, 77, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000);
            s.commit(pend.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
