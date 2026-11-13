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
import java.security.MessageDigest;

/**
 * Self-updater. Reads latest.json from the public aurelius7-releases repo and installs the newer APK
 * through PackageInstaller (Android still shows its own confirm dialog, and still enforces that the
 * APK is signed with the same key as the installed app).
 *
 * Hardening: HTTPS only, the APK must live under the releases repo, the download is size-capped and
 * must match the SHA-256 published in latest.json, and install results go to a non-exported receiver.
 */
public final class Updater {
    private Updater() {}

    private static final String HOST = "raw.githubusercontent.com";
    private static final String BASE = "https://" + HOST + "/Siddz-17/aurelius7-releases/main/";
    public static final String LATEST_URL = BASE + "latest.json";
    private static final String APK_PREFIX = BASE + "apk/";
    public static final String ACTION_STATUS = "com.aurelius7.wiggle.INSTALL_STATUS";
    private static final int MAX_JSON = 64 * 1024;
    private static final long MAX_APK = 64L * 1024 * 1024;

    public static class Info { public int code; public String name, url, notes, sha256; }

    public interface Progress { void onProgress(int pct); }

    private static HttpURLConnection open(String u) throws Exception {
        URL url = new URL(u);
        if (!"https".equals(url.getProtocol()) || !HOST.equals(url.getHost())) throw new SecurityException("Untrusted update host");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    /** Blocking; call off the UI thread. Returns null when there's nothing newer (or offline / invalid). */
    public static Info check(int currentCode) {
        HttpURLConnection c = null;
        try {
            c = open(LATEST_URL + "?t=" + System.currentTimeMillis());
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_JSON) { in.close(); return null; }
            }
            in.close();
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            Info i = new Info();
            i.code = j.getInt("versionCode");
            i.name = j.optString("versionName", "?").replaceAll("[^0-9A-Za-z.\\-]", "");
            if (i.name.length() > 16) i.name = i.name.substring(0, 16);
            i.url = j.getString("url");
            i.notes = j.optString("notes", "");
            i.sha256 = j.optString("sha256", "").toLowerCase(java.util.Locale.US);
            if (!i.url.startsWith(APK_PREFIX) || i.url.contains("..") || !i.url.endsWith(".apk")) return null;
            if (!i.sha256.matches("[0-9a-f]{64}")) return null;
            return i.code > currentCode ? i : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Blocking; downloads into an install session, verifies the checksum, then commits. */
    public static void install(Activity act, Info info, Progress p) throws Exception {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(act.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        HttpURLConnection c = null;
        boolean committed = false;
        try {
            c = open(info.url);
            if (c.getResponseCode() != 200) throw new RuntimeException("HTTP " + c.getResponseCode());
            long total = c.getContentLength();
            if (total > MAX_APK) throw new RuntimeException("Update too large");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = c.getInputStream();
            OutputStream out = s.openWrite("aurelius7.apk", 0, total > 0 ? total : -1);
            byte[] buf = new byte[1 << 16]; int n; long done = 0; int last = -1;
            try {
                while ((n = in.read(buf)) > 0) {
                    done += n;
                    if (done > MAX_APK) throw new RuntimeException("Update too large");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != last) { last = pct; p.onProgress(pct); }
                    }
                }
                s.fsync(out);
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b & 255));
            if (!hex.toString().equals(info.sha256)) throw new SecurityException("Checksum mismatch");

            Intent i = new Intent(act, InstallReceiver.class).setAction(ACTION_STATUS);
            // FLAG_UPDATE_CURRENT | FLAG_MUTABLE: the installer fills in the status extras. Explicit,
            // non-exported target, so no other app can deliver to it.
            PendingIntent pend = PendingIntent.getBroadcast(act, 77, i, PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000);
            s.commit(pend.getIntentSender());
            committed = true;
        } finally {
            if (!committed) { try { s.abandon(); } catch (Exception ignored) {} }
            try { s.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
        }
    }
}
