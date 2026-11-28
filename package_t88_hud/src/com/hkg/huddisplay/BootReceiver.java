package com.hkg.huddisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Launches MainActivity automatically after boot when enabled in settings.
 * Requires SYSTEM_ALERT_WINDOW ("Display over other apps") on Android 10+ to bypass Background Activity Launch restrictions.
 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HudBoot";
    private static volatile long lastLaunchElapsedMs = 0L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean(MainActivity.KEY_AUTO_START, false);
        boolean canOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
        Log.i(TAG, "onReceive action=" + intent.getAction() + " autoStart=" + autoStart + " canOverlay=" + canOverlay);
        if (!autoStart) return;

        long now = SystemClock.elapsedRealtime();
        if (lastLaunchElapsedMs > 0L && (now - lastLaunchElapsedMs) < 5000L) {
            Log.i(TAG, "Skipping duplicate boot launch within 5s");
            return;
        }
        lastLaunchElapsedMs = now;

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(launch);
            Log.i(TAG, "Started MainActivity on boot");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to start MainActivity on boot", e);
        }
    }
}
