package com.aurelius7.wiggle;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Quick-settings tile: one tap from the notification shade straight to the viewfinder. */
public class CameraTile extends TileService {
    @Override public void onClick() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setAction(MainActivity.ACTION_CAMERA);
        if (Build.VERSION.SDK_INT >= 34) {
            // FLAG_IMMUTABLE
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, 0x04000000 | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
