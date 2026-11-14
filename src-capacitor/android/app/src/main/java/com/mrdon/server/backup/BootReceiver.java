package com.mrdon.server.backup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Re-enqueue the periodic backup worker after the device reboots.
 *
 * WorkManager stores its queue in a database that survives reboots, but the
 * scheduling service itself needs a nudge from the app after cold boot. Without
 * this receiver, the "Automatic backup" toggle in Settings will appear ON while
 * actually doing nothing until the user manually flips it off/on again.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BackupBootReceiver";
    private static final String PREFS_NAME = "backup_prefs";
    private static final String WORK_NAME = "nas_backup_periodic";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
            && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enabled", false);
        if (!enabled) {
            Log.i(TAG, "boot: backup disabled, skipping");
            return;
        }

        boolean wifiOnly = prefs.getBoolean("wifiOnly", true);
        // Read the user's actual schedule interval; default 1h.  Earlier this
        // was hardcoded to 1 HOUR with policy=KEEP — which silently clobbered
        // "Daily" / "6 hr" selections after every reboot.
        long intervalMs = prefs.getLong("scheduleInterval", TimeUnit.HOURS.toMillis(1));
        if (intervalMs < TimeUnit.MINUTES.toMillis(15)) intervalMs = TimeUnit.MINUTES.toMillis(15);

        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                BackupWorker.class, intervalMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .build();

        // UPDATE so that if the user changed the interval while the app ran,
        // boot re-enqueue picks up the latest value.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);

        Log.i(TAG, "boot: periodic backup re-enqueued (every " + (intervalMs / 60000)
            + " min, wifiOnly=" + wifiOnly + ")");
    }
}
