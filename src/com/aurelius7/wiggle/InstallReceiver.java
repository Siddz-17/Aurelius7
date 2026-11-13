package com.aurelius7.wiggle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.lang.ref.WeakReference;

/**
 * Receives PackageInstaller results. Declared android:exported="false", so only the system (through
 * our own PendingIntent) can reach it. This replaces handling the status in the exported launcher
 * activity, where any app could have supplied a forged "confirm" intent for us to launch.
 */
public class InstallReceiver extends BroadcastReceiver {
    static volatile WeakReference<MainActivity> host = new WeakReference<MainActivity>(null);
    static volatile Intent pendingConfirm;

    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Updater.ACTION_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        MainActivity a = host.get();
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            pendingConfirm = confirm;
            if (a != null) a.onInstallConfirmReady();
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            pendingConfirm = null;
            if (a != null) a.onInstallFailed();
        }
    }
}
