package com.mrdon.server.plugins;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import com.mrdon.server.backup.BackupWorker;
import com.mrdon.server.backup.VerifyWorker;
import com.mrdon.server.upload.UploadWorker;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
    name = "BackupPlugin",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_MEDIA_IMAGES }, alias = "mediaImages"),
        @Permission(strings = { Manifest.permission.READ_MEDIA_VIDEO }, alias = "mediaVideo"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "storageLegacy")
    }
)
public class BackupPlugin extends Plugin {
    private static final String TAG = "BackupPlugin";
    private static final String PREFS_NAME = "backup_prefs";
    private static final String WORK_NAME = "nas_backup_periodic";
    private static final String WORK_ONETIME = "nas_backup_onetime";
    private static final String WORK_TAG = "nas_backup";
    private static final String VERIFY_ONETIME = "nas_verify_onetime";
    private static final String VERIFY_TAG = "nas_verify";
    private static final String UPLOAD_ONETIME = "nas_upload_onetime";
    private static final String UPLOAD_TAG = "nas_upload";
    private static final String UPLOAD_PREFS = "upload_prefs";

    @PluginMethod
    public void configure(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (call.hasOption("enabled")) editor.putBoolean("enabled", call.getBoolean("enabled", false));
        if (call.hasOption("host")) editor.putString("host", call.getString("host", ""));
        if (call.hasOption("shareName")) editor.putString("shareName", call.getString("shareName", ""));
        if (call.hasOption("backupPath")) editor.putString("backupPath", call.getString("backupPath", "/Backup/Phone"));
        if (call.hasOption("username")) editor.putString("username", call.getString("username", ""));
        if (call.hasOption("password")) editor.putString("password", call.getString("password", ""));
        if (call.hasOption("includePhotos")) editor.putBoolean("includePhotos", call.getBoolean("includePhotos", true));
        if (call.hasOption("includeVideos")) editor.putBoolean("includeVideos", call.getBoolean("includeVideos", true));
        if (call.hasOption("wifiOnly")) editor.putBoolean("wifiOnly", call.getBoolean("wifiOnly", true));
        if (call.hasOption("homeNetworkOnly")) editor.putBoolean("homeNetworkOnly", call.getBoolean("homeNetworkOnly", false));
        if (call.hasOption("chargerOnly")) editor.putBoolean("chargerOnly", call.getBoolean("chargerOnly", false));
        if (call.hasOption("autoCleanup")) editor.putBoolean("autoCleanup", call.getBoolean("autoCleanup", false));
        if (call.hasOption("cleanupGraceDays")) {
            long days = call.getLong("cleanupGraceDays", 7L);
            if (days < 0) days = 0L;
            if (days > 365) days = 365L;
            editor.putLong("cleanupGraceDays", days);
        }
        if (call.hasOption("backupOrganization")) {
            String mode = call.getString("backupOrganization", "source");
            if (!"source".equals(mode) && !"year-month".equals(mode) && !"flat".equals(mode)) {
                mode = "source";
            }
            editor.putString("backupOrganization", mode);
        }
        // Time-window: 0 in both fields (or window disabled) means "any time".
        // Crossing midnight is allowed (e.g. 22→6) — the worker handles wrap.
        if (call.hasOption("windowEnabled")) editor.putBoolean("windowEnabled", call.getBoolean("windowEnabled", false));
        if (call.hasOption("windowStartHour")) editor.putInt("windowStartHour", clampHour(call.getInt("windowStartHour", 2)));
        if (call.hasOption("windowEndHour")) editor.putInt("windowEndHour", clampHour(call.getInt("windowEndHour", 6)));
        if (call.hasOption("scheduleInterval")) {
            // Clamp to WorkManager's 15-minute floor.
            long ms = call.getLong("scheduleInterval", TimeUnit.HOURS.toMillis(1));
            if (ms < TimeUnit.MINUTES.toMillis(15)) ms = TimeUnit.MINUTES.toMillis(15);
            editor.putLong("scheduleInterval", ms);
        }

        // Selected folders for backup
        if (call.hasOption("selectedFolders")) {
            try {
                com.getcapacitor.JSArray foldersArr = call.getArray("selectedFolders");
                Set<String> folders = new HashSet<>();
                if (foldersArr != null) {
                    for (int i = 0; i < foldersArr.length(); i++) {
                        folders.add(foldersArr.getString(i));
                    }
                }
                editor.putStringSet("selectedFolders", folders);
            } catch (Exception e) {
                Log.w(TAG, "Error parsing selectedFolders", e);
            }
        }

        // Exclude patterns — glob-style strings like "*.tmp", ".thumbnails/*"
        if (call.hasOption("excludePatterns")) {
            try {
                com.getcapacitor.JSArray arr = call.getArray("excludePatterns");
                Set<String> patterns = new HashSet<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String p = arr.getString(i);
                        if (p != null && !p.trim().isEmpty()) patterns.add(p.trim());
                    }
                }
                editor.putStringSet("excludePatterns", patterns);
            } catch (Exception e) {
                Log.w(TAG, "Error parsing excludePatterns", e);
            }
        }

        editor.apply();

        boolean enabled = call.getBoolean("enabled", prefs.getBoolean("enabled", false));
        boolean wifiOnly = call.getBoolean("wifiOnly", prefs.getBoolean("wifiOnly", true));
        long intervalMs = prefs.getLong("scheduleInterval", TimeUnit.HOURS.toMillis(1));
        if (intervalMs < TimeUnit.MINUTES.toMillis(15)) intervalMs = TimeUnit.MINUTES.toMillis(15);

        // IMPORTANT: only re-enqueue the periodic when something that
        // actually affects scheduling changed.  `configure` gets called on
        // every Pinia-watcher tick (store hydration, minor field tweaks),
        // and a naive `UPDATE` policy *cancels the running worker and
        // restarts it*, which was triggering phantom uploads the user
        // never asked for.
        boolean chargerOnly = prefs.getBoolean("chargerOnly", false);
        boolean lastEnabled = prefs.getBoolean("_schedEnabled", false);
        long lastInterval = prefs.getLong("_schedInterval", -1);
        boolean lastWifi = prefs.getBoolean("_schedWifi", true);
        boolean lastCharger = prefs.getBoolean("_schedCharger", false);
        boolean scheduleChanged = (enabled != lastEnabled)
            || (intervalMs != lastInterval)
            || (wifiOnly != lastWifi)
            || (chargerOnly != lastCharger);

        if (!enabled) {
            if (lastEnabled) cancelBackup();
        } else if (scheduleChanged) {
            scheduleBackup(wifiOnly, chargerOnly);
        }
        // else: enabled & unchanged — leave the existing schedule alone.

        prefs.edit()
            .putBoolean("_schedEnabled", enabled)
            .putLong("_schedInterval", intervalMs)
            .putBoolean("_schedWifi", wifiOnly)
            .putBoolean("_schedCharger", chargerOnly)
            .apply();

        call.resolve();
    }

    @PluginMethod
    public void runNow(PluginCall call) {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean wifiOnly = prefs.getBoolean("wifiOnly", true);

            Constraints.Builder c = new Constraints.Builder()
                .setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED);

            // User-initiated runs should bypass the homeNetworkOnly TCP probe
            // — if the user explicitly taps "Back up now", they've decided
            // they want to try.  Scheduled runs still honor the check.
            androidx.work.Data inputData = new androidx.work.Data.Builder()
                .putBoolean("bypassHomeCheck", true)
                .build();

            OneTimeWorkRequest.Builder b = new OneTimeWorkRequest.Builder(BackupWorker.class)
                .addTag(WORK_TAG)
                .setInputData(inputData)
                .setConstraints(c.build());
            // Expedited — user-initiated, run immediately. Falls back to a
            // normal request if the app is out of expedited quota.
            b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);

            WorkManager.getInstance(getContext())
                .enqueueUniqueWork(WORK_ONETIME, androidx.work.ExistingWorkPolicy.REPLACE, b.build());
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to start backup: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getWorkStatus(PluginCall call) {
        JSObject res = new JSObject();
        boolean running = false;
        boolean queued = false;
        String phase = "";
        String fileName = "";
        int current = 0;
        int total = 0;
        try {
            // We have to look in two places: WORK_TAG covers user-initiated
            // one-off runs, and WORK_NAME (via unique work) covers the
            // periodic schedule.  Without the second lookup, progress never
            // appears for a scheduled run — which is exactly the "real-time
            // data doesn't update" bug.
            WorkManager wm = WorkManager.getInstance(getContext());
            java.util.List<androidx.work.WorkInfo> tagged = wm.getWorkInfosByTag(WORK_TAG).get();
            java.util.List<androidx.work.WorkInfo> periodic = wm.getWorkInfosForUniqueWork(WORK_NAME).get();

            java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
            java.util.List<androidx.work.WorkInfo> combined = new java.util.ArrayList<>();
            if (tagged != null) for (androidx.work.WorkInfo i : tagged) {
                if (seen.add(i.getId())) combined.add(i);
            }
            if (periodic != null) for (androidx.work.WorkInfo i : periodic) {
                if (seen.add(i.getId())) combined.add(i);
            }

            for (androidx.work.WorkInfo info : combined) {
                androidx.work.WorkInfo.State s = info.getState();
                if (s == androidx.work.WorkInfo.State.RUNNING) {
                    running = true;
                    androidx.work.Data prog = info.getProgress();
                    String p = prog.getString("phase");
                    if (p != null && !p.isEmpty()) {
                        phase = p;
                        fileName = prog.getString("fileName");
                        if (fileName == null) fileName = "";
                        current = prog.getInt("current", 0);
                        total = prog.getInt("total", 0);
                    }
                } else if (s == androidx.work.WorkInfo.State.ENQUEUED
                        || s == androidx.work.WorkInfo.State.BLOCKED) {
                    queued = true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getWorkStatus failed", e);
        }
        res.put("running", running);
        res.put("queued", queued);
        res.put("active", running || queued);
        res.put("phase", phase);
        res.put("currentFile", fileName);
        res.put("currentIndex", current);
        res.put("totalFiles", total);
        call.resolve(res);
    }

    @PluginMethod
    public void stopNow(PluginCall call) {
        try {
            WorkManager wm = WorkManager.getInstance(getContext());
            // 1. Kill the one-time "Back up now" run if active.
            wm.cancelUniqueWork(WORK_ONETIME);
            // 2. Kill anything tagged as a user-initiated run.
            wm.cancelAllWorkByTag(WORK_TAG);
            // 3. Kill the currently-executing periodic run by ID.  We can't
            //    use cancelUniqueWork on the periodic name — that would also
            //    wipe the schedule and future runs.  Cancel-by-id cancels
            //    only this execution; WorkManager re-fires on the next tick.
            try {
                java.util.List<androidx.work.WorkInfo> infos =
                    wm.getWorkInfosForUniqueWork(WORK_NAME).get();
                if (infos != null) {
                    for (androidx.work.WorkInfo info : infos) {
                        if (info.getState() == androidx.work.WorkInfo.State.RUNNING
                         || info.getState() == androidx.work.WorkInfo.State.ENQUEUED) {
                            wm.cancelWorkById(info.getId());
                        }
                    }
                }
            } catch (Exception ignore) { /* best-effort */ }
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to stop backup: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getNetworkStatus(PluginCall call) {
        JSObject res = new JSObject();
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities cap = net != null ? cm.getNetworkCapabilities(net) : null;
            boolean connected = cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean wifi = cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean metered = cap == null || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            res.put("connected", connected);
            res.put("wifi", wifi);
            res.put("metered", metered);
        } catch (Exception e) {
            res.put("connected", false);
            res.put("wifi", false);
            res.put("metered", true);
        }
        call.resolve(res);
    }

    @PluginMethod
    public void checkMediaPermissions(PluginCall call) {
        call.resolve(mediaPermissionState());
    }

    @PluginMethod
    public void requestMediaPermissions(PluginCall call) {
        // On Android 13+ we need READ_MEDIA_IMAGES / READ_MEDIA_VIDEO. On older
        // Android the legacy READ_EXTERNAL_STORAGE alias is what matters.
        String[] aliases = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? new String[] { "mediaImages", "mediaVideo" }
            : new String[] { "storageLegacy" };
        requestPermissionForAliases(aliases, call, "mediaPermsCallback");
    }

    @PermissionCallback
    private void mediaPermsCallback(PluginCall call) {
        call.resolve(mediaPermissionState());
    }

    private JSObject mediaPermissionState() {
        JSObject res = new JSObject();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean imgOk = getPermissionState("mediaImages") == PermissionState.GRANTED;
            boolean vidOk = getPermissionState("mediaVideo") == PermissionState.GRANTED;
            res.put("images", imgOk);
            res.put("videos", vidOk);
            res.put("granted", imgOk || vidOk);
        } else {
            boolean legacyOk = getPermissionState("storageLegacy") == PermissionState.GRANTED;
            res.put("images", legacyOk);
            res.put("videos", legacyOk);
            res.put("granted", legacyOk);
        }
        return res;
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSObject result = new JSObject();
        result.put("enabled", prefs.getBoolean("enabled", false));
        result.put("lastBackupTimestamp", prefs.getLong("lastBackupTimestamp", 0));
        result.put("lastBackupCount", prefs.getInt("lastBackupCount", 0));
        // size() is safe-ish without copy, but copying avoids a CME if the
        // worker is mid-flush.
        result.put("backedUpCount", new HashSet<>(prefs.getStringSet("backedUpIds", new HashSet<>())).size());
        call.resolve(result);
    }

    @PluginMethod
    public void verifyNow(PluginCall call) {
        try {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(VerifyWorker.class)
                .addTag(VERIFY_TAG)
                .setConstraints(constraints)
                .build();
            WorkManager.getInstance(getContext())
                .enqueueUniqueWork(VERIFY_ONETIME,
                    androidx.work.ExistingWorkPolicy.REPLACE, request);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to start verify: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopVerify(PluginCall call) {
        try {
            WorkManager wm = WorkManager.getInstance(getContext());
            wm.cancelUniqueWork(VERIFY_ONETIME);
            wm.cancelAllWorkByTag(VERIFY_TAG);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to stop verify: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getVerifyStatus(PluginCall call) {
        JSObject res = new JSObject();
        boolean running = false;
        boolean queued = false;
        String phase = "";
        String detail = "";
        int current = 0;
        int dropped = 0;
        int total = 0;
        try {
            java.util.List<androidx.work.WorkInfo> infos =
                WorkManager.getInstance(getContext()).getWorkInfosByTag(VERIFY_TAG).get();
            if (infos != null) {
                for (androidx.work.WorkInfo info : infos) {
                    androidx.work.WorkInfo.State s = info.getState();
                    if (s == androidx.work.WorkInfo.State.RUNNING) {
                        running = true;
                        androidx.work.Data prog = info.getProgress();
                        String p = prog.getString("phase");
                        if (p != null && !p.isEmpty()) {
                            phase = p;
                            detail = prog.getString("detail");
                            if (detail == null) detail = "";
                            current = prog.getInt("current", 0);
                            dropped = prog.getInt("dropped", 0);
                            total = prog.getInt("total", 0);
                        }
                    } else if (s == androidx.work.WorkInfo.State.ENQUEUED
                            || s == androidx.work.WorkInfo.State.BLOCKED) {
                        queued = true;
                    } else if (s == androidx.work.WorkInfo.State.SUCCEEDED
                            || s == androidx.work.WorkInfo.State.FAILED
                            || s == androidx.work.WorkInfo.State.CANCELLED) {
                        // Terminal — expose the final progress if present.
                        androidx.work.Data prog = info.getProgress();
                        String p = prog.getString("phase");
                        if (p != null && !p.isEmpty() && phase.isEmpty()) {
                            phase = p;
                            detail = prog.getString("detail");
                            if (detail == null) detail = "";
                            current = prog.getInt("current", 0);
                            dropped = prog.getInt("dropped", 0);
                            total = prog.getInt("total", 0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getVerifyStatus failed", e);
        }
        res.put("running", running);
        res.put("queued", queued);
        res.put("active", running || queued);
        res.put("phase", phase);
        res.put("detail", detail);
        res.put("current", current);
        res.put("dropped", dropped);
        res.put("total", total);
        call.resolve(res);
    }

    // ==================== UPLOAD QUEUE ====================

    @PluginMethod
    public void enqueueUpload(PluginCall call) {
        com.getcapacitor.JSArray items = call.getArray("items");
        if (items == null || items.length() == 0) {
            call.reject("No items to upload");
            return;
        }

        SharedPreferences prefs = getContext().getSharedPreferences(UPLOAD_PREFS, Context.MODE_PRIVATE);
        // Hold the shared queue lock for the full read-modify-write so we
        // can't clobber an in-flight worker flip or another plugin mutation.
        synchronized (UploadWorker.QUEUE_LOCK) {
            try {
                String raw = prefs.getString(UploadWorker.QUEUE_KEY, "[]");
                org.json.JSONArray queue = new org.json.JSONArray(raw);

                for (int i = 0; i < items.length(); i++) {
                    com.getcapacitor.JSObject item = com.getcapacitor.JSObject.fromJSONObject(items.getJSONObject(i));
                    org.json.JSONObject entry = new org.json.JSONObject();
                    // Random UUID — nanoTime IDs can collide across rapid
                    // enqueueUpload calls when Android's clock tick granularity
                    // is low and the batch index resets per call.
                    String id = "u_" + java.util.UUID.randomUUID().toString();
                    entry.put("id", id);
                    entry.put("localUri", item.getString("localUri", ""));
                    entry.put("host", item.getString("host", ""));
                    entry.put("share", item.getString("share", ""));
                    entry.put("path", item.getString("path", ""));
                    entry.put("name", item.getString("name", id));
                    Long sz = item.has("size") ? item.getLong("size") : null;
                    entry.put("size", sz == null ? 0L : sz.longValue());
                    entry.put("username", item.getString("username", ""));
                    entry.put("password", item.getString("password", ""));
                    entry.put("status", "queued");
                    entry.put("addedAt", System.currentTimeMillis());
                    queue.put(entry);
                }
                prefs.edit().putString(UploadWorker.QUEUE_KEY, queue.toString()).commit();
            } catch (Exception e) {
                call.reject("Failed to enqueue: " + e.getMessage());
                return;
            }
        }

        // Kick the worker if not already running.  Uses REPLACE so spamming
        // enqueue doesn't pile up multiple runners.
        try {
            Constraints c = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .addTag(UPLOAD_TAG)
                .setConstraints(c)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
            WorkManager.getInstance(getContext())
                .enqueueUniqueWork(UPLOAD_ONETIME,
                    androidx.work.ExistingWorkPolicy.KEEP, req);
        } catch (Exception e) {
            call.reject("Failed to start upload worker: " + e.getMessage());
            return;
        }
        call.resolve();
    }

    @PluginMethod
    public void getUploadStatus(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(UPLOAD_PREFS, Context.MODE_PRIVATE);
        JSObject res = new JSObject();
        com.getcapacitor.JSArray outQueue = new com.getcapacitor.JSArray();
        try {
            org.json.JSONArray queue = new org.json.JSONArray(prefs.getString(UploadWorker.QUEUE_KEY, "[]"));
            for (int i = 0; i < queue.length(); i++) {
                org.json.JSONObject o = queue.getJSONObject(i);
                // Strip credentials before sending to JS.
                o.remove("username");
                o.remove("password");
                outQueue.put(JSObject.fromJSONObject(o));
            }
        } catch (Exception ignore) {}
        res.put("queue", outQueue);

        boolean running = false;
        boolean queued = false;
        String phase = "", currentId = "", currentName = "";
        int currentIndex = 0, totalFiles = 0, uploadedCount = 0, failedCount = 0;
        long currentBytes = 0, currentSize = 0;
        try {
            java.util.List<androidx.work.WorkInfo> infos =
                WorkManager.getInstance(getContext()).getWorkInfosByTag(UPLOAD_TAG).get();
            if (infos != null) {
                for (androidx.work.WorkInfo info : infos) {
                    androidx.work.WorkInfo.State s = info.getState();
                    if (s == androidx.work.WorkInfo.State.RUNNING) {
                        running = true;
                        androidx.work.Data p = info.getProgress();
                        if (p.getString("phase") != null) {
                            phase = p.getString("phase");
                            currentId = p.getString("currentId");
                            currentName = p.getString("currentName");
                            currentIndex = p.getInt("currentIndex", 0);
                            totalFiles = p.getInt("totalFiles", 0);
                            uploadedCount = p.getInt("uploadedCount", 0);
                            failedCount = p.getInt("failedCount", 0);
                            currentBytes = p.getLong("currentBytes", 0);
                            currentSize = p.getLong("currentSize", 0);
                        }
                    } else if (s == androidx.work.WorkInfo.State.ENQUEUED
                            || s == androidx.work.WorkInfo.State.BLOCKED) {
                        queued = true;
                    }
                }
            }
        } catch (Exception ignore) {}
        res.put("running", running);
        res.put("queued", queued);
        res.put("phase", phase == null ? "" : phase);
        res.put("currentId", currentId == null ? "" : currentId);
        res.put("currentName", currentName == null ? "" : currentName);
        res.put("currentIndex", currentIndex);
        res.put("totalFiles", totalFiles);
        res.put("uploadedCount", uploadedCount);
        res.put("failedCount", failedCount);
        res.put("currentBytes", currentBytes);
        res.put("currentSize", currentSize);
        call.resolve(res);
    }

    @PluginMethod
    public void stopUpload(PluginCall call) {
        try {
            WorkManager wm = WorkManager.getInstance(getContext());
            wm.cancelUniqueWork(UPLOAD_ONETIME);
            wm.cancelAllWorkByTag(UPLOAD_TAG);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to stop upload: " + e.getMessage());
        }
    }

    @PluginMethod
    public void removeUpload(PluginCall call) {
        String id = call.getString("id", "");
        if (id == null || id.isEmpty()) { call.resolve(); return; }
        SharedPreferences prefs = getContext().getSharedPreferences(UPLOAD_PREFS, Context.MODE_PRIVATE);
        synchronized (UploadWorker.QUEUE_LOCK) {
            try {
                org.json.JSONArray queue = new org.json.JSONArray(prefs.getString(UploadWorker.QUEUE_KEY, "[]"));
                org.json.JSONArray out = new org.json.JSONArray();
                for (int i = 0; i < queue.length(); i++) {
                    org.json.JSONObject o = queue.getJSONObject(i);
                    // Skip the target id unless it's currently uploading — in
                    // that case the worker owns it and will clean up.
                    if (id.equals(o.optString("id")) && !"uploading".equals(o.optString("status"))) continue;
                    out.put(o);
                }
                prefs.edit().putString(UploadWorker.QUEUE_KEY, out.toString()).commit();
            } catch (Exception ignore) {}
        }
        call.resolve();
    }

    @PluginMethod
    public void retryUpload(PluginCall call) {
        String id = call.getString("id", "");
        SharedPreferences prefs = getContext().getSharedPreferences(UPLOAD_PREFS, Context.MODE_PRIVATE);
        try {
            synchronized (UploadWorker.QUEUE_LOCK) {
                org.json.JSONArray queue = new org.json.JSONArray(prefs.getString(UploadWorker.QUEUE_KEY, "[]"));
                for (int i = 0; i < queue.length(); i++) {
                    org.json.JSONObject o = queue.getJSONObject(i);
                    if (id.equals(o.optString("id")) && "failed".equals(o.optString("status"))) {
                        o.put("status", "queued");
                        o.remove("error");
                    }
                }
                prefs.edit().putString(UploadWorker.QUEUE_KEY, queue.toString()).commit();
            }
            // Kick the worker.
            Constraints c = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .addTag(UPLOAD_TAG)
                .setConstraints(c)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
            WorkManager.getInstance(getContext())
                .enqueueUniqueWork(UPLOAD_ONETIME,
                    androidx.work.ExistingWorkPolicy.KEEP, req);
        } catch (Exception e) {
            call.reject("Failed to retry: " + e.getMessage());
            return;
        }
        call.resolve();
    }

    @PluginMethod
    public void getBackedUpIds(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Copy into a private HashSet before iterating.  SharedPreferences
        // returns a live reference; concurrent mutation from the worker's
        // incremental-save path throws ConcurrentModificationException
        // during a backup run.
        Set<String> ids = new HashSet<>(prefs.getStringSet("backedUpIds", new HashSet<>()));
        com.getcapacitor.JSArray arr = new com.getcapacitor.JSArray();
        for (String id : ids) arr.put(id);
        JSObject res = new JSObject();
        res.put("ids", arr);
        res.put("count", ids.size());
        call.resolve(res);
    }

    @PluginMethod
    public void getBackupHistory(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString("backupHistory", "[]");
        JSObject res = new JSObject();
        try {
            // Parse and re-serialise so the client always gets a clean JSArray.
            org.json.JSONArray parsed = new org.json.JSONArray(raw);
            com.getcapacitor.JSArray out = new com.getcapacitor.JSArray();
            for (int i = 0; i < parsed.length(); i++) out.put(parsed.getJSONObject(i));
            res.put("records", out);
        } catch (Exception e) {
            Log.w(TAG, "getBackupHistory parse failure, returning empty", e);
            res.put("records", new com.getcapacitor.JSArray());
        }
        call.resolve(res);
    }

    @PluginMethod
    public void clearBackupHistory(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove("backupHistory").apply();
        call.resolve();
    }

    // ==================== CLEANUP QUEUE (safe delete-from-phone) ====================

    private static final String CLEANUP_KEY = "cleanupQueue";
    private static final int REQ_CLEANUP_DELETE = 0x5A51;
    /**
     * Callback id of the PluginCall currently awaiting the system delete
     * prompt.  Only one cleanup flow active at a time — if the user fires
     * another while one is pending, the old one is replaced (reject before
     * releasing the reference).
     */
    private String pendingCleanupCallbackId = null;
    /** Ids being deleted in the current flow; popped from the queue on RESULT_OK. */
    private java.util.List<String> pendingCleanupIds = new java.util.ArrayList<>();

    @PluginMethod
    public void getCleanupQueue(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(CLEANUP_KEY, "[]");
        JSObject res = new JSObject();
        try {
            org.json.JSONArray parsed = new org.json.JSONArray(raw);
            com.getcapacitor.JSArray out = new com.getcapacitor.JSArray();
            long now = System.currentTimeMillis();
            int eligible = 0;
            long totalBytes = 0;
            long eligibleBytes = 0;
            for (int i = 0; i < parsed.length(); i++) {
                org.json.JSONObject rec = parsed.getJSONObject(i);
                long elig = rec.optLong("eligibleAt", 0L);
                long sz = rec.optLong("size", 0L);
                totalBytes += sz;
                if (elig <= now) { eligible++; eligibleBytes += sz; }
                out.put(rec);
            }
            res.put("entries", out);
            res.put("total", parsed.length());
            res.put("eligible", eligible);
            res.put("totalBytes", totalBytes);
            res.put("eligibleBytes", eligibleBytes);
        } catch (Exception e) {
            Log.w(TAG, "getCleanupQueue parse failure, returning empty", e);
            res.put("entries", new com.getcapacitor.JSArray());
            res.put("total", 0);
            res.put("eligible", 0);
            res.put("totalBytes", 0L);
            res.put("eligibleBytes", 0L);
        }
        call.resolve(res);
    }

    @PluginMethod
    public void removeCleanupEntries(PluginCall call) {
        com.getcapacitor.JSArray ids = call.getArray("ids");
        if (ids == null) { call.reject("ids required"); return; }
        java.util.Set<String> toRemove = new java.util.HashSet<>();
        try {
            for (int i = 0; i < ids.length(); i++) toRemove.add(ids.getString(i));
        } catch (Exception e) {
            call.reject("Invalid ids array");
            return;
        }
        int removed = removeIdsFromCleanupQueue(toRemove);
        JSObject res = new JSObject();
        res.put("removed", removed);
        call.resolve(res);
    }

    @PluginMethod
    public void clearCleanupQueue(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(CLEANUP_KEY).apply();
        call.resolve();
    }

    /**
     * Fire Android's MediaStore delete prompt for the given (or all eligible)
     * queued items.  On R+ the OS shows a consent dialog — the user has to
     * approve the batch.  On pre-R we fall back to a direct ContentResolver
     * delete which is permitted for MediaStore entries the app can write.
     *
     * Input options:
     *   - ids: array of entry ids to delete; if omitted or empty we pull all
     *          entries whose eligibleAt has passed.
     *   - onlyEligible: when `ids` is provided, skip ones still in grace.
     *                   Default true.
     */
    @PluginMethod
    public void executeCleanup(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(CLEANUP_KEY, "[]");
        org.json.JSONArray arr;
        try { arr = new org.json.JSONArray(raw); }
        catch (Exception e) { call.reject("Cleanup queue unreadable"); return; }

        java.util.Set<String> requested = null;
        com.getcapacitor.JSArray idsArg = call.getArray("ids");
        if (idsArg != null && idsArg.length() > 0) {
            requested = new java.util.HashSet<>();
            try {
                for (int i = 0; i < idsArg.length(); i++) requested.add(idsArg.getString(i));
            } catch (Exception e) {
                call.reject("Invalid ids"); return;
            }
        }
        boolean onlyEligible = call.getBoolean("onlyEligible", true);
        long now = System.currentTimeMillis();

        java.util.List<Uri> uris = new java.util.ArrayList<>();
        java.util.List<String> pickedIds = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                org.json.JSONObject rec = arr.getJSONObject(i);
                String id = rec.optString("id");
                if (id == null || id.isEmpty()) continue;
                if (requested != null && !requested.contains(id)) continue;
                long elig = rec.optLong("eligibleAt", 0L);
                if (onlyEligible && elig > now) continue;
                String uriStr = rec.optString("uri");
                if (uriStr == null || uriStr.isEmpty()) {
                    // Fall back to reconstructing the MediaStore uri from the
                    // id suffix (format "image_<mediaStoreId>" or "video_...").
                    String[] parts = id.split("_", 2);
                    if (parts.length != 2) continue;
                    long msId;
                    try { msId = Long.parseLong(parts[1]); } catch (Exception e) { continue; }
                    Uri base = "video".equals(parts[0])
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    uris.add(ContentUris.withAppendedId(base, msId));
                } else {
                    uris.add(Uri.parse(uriStr));
                }
                pickedIds.add(id);
            } catch (Exception ignore) {}
        }

        if (uris.isEmpty()) {
            JSObject res = new JSObject();
            res.put("deleted", 0);
            res.put("accepted", true);
            res.put("reason", "nothing-to-delete");
            call.resolve(res);
            return;
        }

        // API 30+: system-mediated consent dialog.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ContentResolver resolver = getContext().getContentResolver();
                PendingIntent pi = MediaStore.createDeleteRequest(resolver, uris);
                pendingCleanupIds = pickedIds;
                pendingCleanupCallbackId = call.getCallbackId();
                bridge.saveCall(call);
                getActivity().startIntentSenderForResult(
                        pi.getIntentSender(), REQ_CLEANUP_DELETE, null, 0, 0, 0);
                // Result handled in handleOnActivityResult below.
            } catch (Exception e) {
                Log.e(TAG, "executeCleanup: failed to launch delete prompt", e);
                pendingCleanupCallbackId = null;
                pendingCleanupIds = new java.util.ArrayList<>();
                call.reject("Failed to launch delete prompt: " + e.getMessage());
            }
            return;
        }

        // Pre-R: try a direct delete.  Works when the app owns the rows; for
        // shared media the user should grant legacy storage permission first.
        int deleted = 0;
        int failed = 0;
        ContentResolver resolver = getContext().getContentResolver();
        for (Uri u : uris) {
            try {
                int n = resolver.delete(u, null, null);
                if (n > 0) deleted++;
                else failed++;
            } catch (Exception e) {
                failed++;
                Log.w(TAG, "Direct delete failed for " + u, e);
            }
        }
        if (deleted > 0) {
            removeIdsFromCleanupQueue(new java.util.HashSet<>(pickedIds));
        }
        JSObject res = new JSObject();
        res.put("deleted", deleted);
        res.put("failed", failed);
        res.put("accepted", true);
        call.resolve(res);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CLEANUP_DELETE) return;
        if (pendingCleanupCallbackId == null) return;
        PluginCall saved = bridge.getSavedCall(pendingCleanupCallbackId);
        try {
            boolean accepted = (resultCode == android.app.Activity.RESULT_OK);
            int deleted = 0;
            if (accepted && !pendingCleanupIds.isEmpty()) {
                deleted = removeIdsFromCleanupQueue(new java.util.HashSet<>(pendingCleanupIds));
            }
            if (saved != null) {
                JSObject res = new JSObject();
                res.put("accepted", accepted);
                res.put("deleted", deleted);
                res.put("failed", 0);
                saved.resolve(res);
            }
        } finally {
            if (saved != null) bridge.releaseCall(saved);
            pendingCleanupCallbackId = null;
            pendingCleanupIds = new java.util.ArrayList<>();
        }
    }

    private int removeIdsFromCleanupQueue(java.util.Set<String> idsToRemove) {
        if (idsToRemove == null || idsToRemove.isEmpty()) return 0;
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(CLEANUP_KEY, "[]");
        org.json.JSONArray arr;
        try { arr = new org.json.JSONArray(raw); } catch (Exception e) { return 0; }
        org.json.JSONArray out = new org.json.JSONArray();
        int removed = 0;
        for (int i = 0; i < arr.length(); i++) {
            try {
                org.json.JSONObject rec = arr.getJSONObject(i);
                if (idsToRemove.contains(rec.optString("id"))) { removed++; continue; }
                out.put(rec);
            } catch (Exception ignore) {}
        }
        prefs.edit().putString(CLEANUP_KEY, out.toString()).apply();
        return removed;
    }

    @PluginMethod
    public void resetHistory(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putLong("lastBackupTimestamp", 0)
            .putStringSet("backedUpIds", new HashSet<>())
            .putInt("lastBackupCount", 0)
            .apply();
        call.resolve();
    }

    private static int clampHour(int h) {
        if (h < 0) return 0;
        if (h > 23) return 23;
        return h;
    }

    private void scheduleBackup(boolean wifiOnly, boolean chargerOnly) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long intervalMs = prefs.getLong("scheduleInterval", TimeUnit.HOURS.toMillis(1));
        // WorkManager floors at 15 minutes — anything lower would be silently
        // clamped upstream.
        if (intervalMs < TimeUnit.MINUTES.toMillis(15)) intervalMs = TimeUnit.MINUTES.toMillis(15);

        Constraints.Builder cb = new Constraints.Builder()
            .setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true);
        if (chargerOnly) cb.setRequiresCharging(true);

        // IMPORTANT: don't add WORK_TAG here. That tag is used by stopNow() to
        // kill the user-initiated one-time run; if the periodic job shared it,
        // stopping a manual upload would also permanently cancel the schedule.
        // The periodic schedule is owned by its unique name (WORK_NAME) only.
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                BackupWorker.class, intervalMs, TimeUnit.MILLISECONDS)
            .setConstraints(cb.build())
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build();

        WorkManager.getInstance(getContext())
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);

        Log.i(TAG, "Backup scheduled (every " + (intervalMs / 60000) + " min, wifiOnly=" + wifiOnly
                + ", chargerOnly=" + chargerOnly + ")");
    }

    private void cancelBackup() {
        WorkManager.getInstance(getContext()).cancelUniqueWork(WORK_NAME);
        Log.i(TAG, "Backup cancelled");
    }
}
