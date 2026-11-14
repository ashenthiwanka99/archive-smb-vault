package com.mrdon.server.upload;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileOutputStream;

/**
 * Drains a persisted upload queue.  Each queue entry is a JSON object with
 * {id, localUri, host, share, path, name, size, username, password, status,
 *  error, progress, addedAt}.  Queue is stored in SharedPreferences under
 * key "uploadQueue" as a JSON array string so WorkManager can restart us
 * after crashes / reboots and pick up exactly where we left off.
 *
 * <p>Per-file flow: status transitions queued -> uploading -> (removed on
 * success, or marked failed on error).  On worker start we first reset any
 * "uploading" entries back to "queued" — those are crash-recoveries.
 */
public class UploadWorker extends Worker {
    private static final String TAG = "UploadWorker";
    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final String PREFS_NAME = "upload_prefs";
    public static final String QUEUE_KEY = "uploadQueue";

    /**
     * Single lock guarding every read/write to the upload queue JSON, held
     * by both the worker loop AND BackupPlugin's enqueue/remove/retry
     * methods.  Previously `synchronized` on different instance monitors
     * meant the plugin thread could clobber the worker's in-flight flip.
     */
    public static final Object QUEUE_LOCK = new Object();

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Crash recovery: anything left in "uploading" state wasn't finished.
        // Reset to queued so we retry it.
        recoverCrashed(prefs);

        createNotificationChannel();
        setForegroundAsync(foregroundInfo("Preparing upload…", "", 0, 0));
        publishProgress("scanning", "", "", 0, 0, 0, 0, 0L, 0L);

        int uploaded = 0;
        int failed = 0;

        // Cache CIFS contexts per (host|user) so back-to-back uploads to the
        // same shelf reuse one set of SMB socket factories / thread pool
        // instead of paying context construction on every file.
        java.util.Map<String, CIFSContext> ctxCache = new java.util.HashMap<>();

        while (!isStopped()) {
            JSONObject entry = popFirstQueued(prefs);
            if (entry == null) break;

            String id = entry.optString("id", "");
            String name = entry.optString("name", id);
            String host = entry.optString("host", "");
            String share = entry.optString("share", "");
            String path = entry.optString("path", "");
            String localUri = entry.optString("localUri", "");
            String username = entry.optString("username", "");
            String password = entry.optString("password", "");
            long size = entry.optLong("size", 0);

            // Recompute total every iteration so items enqueued mid-run are
            // reflected in "N of M" progress instead of overflowing past a
            // frozen snapshot taken when the worker started.
            int remainingInQueue = countRemaining(prefs);
            int total = uploaded + failed + remainingInQueue;
            int currentIdx = uploaded + failed + 1;
            setForegroundAsync(foregroundInfo("Uploading", name, currentIdx, total));
            publishProgress("uploading", id, name, currentIdx, total, uploaded, failed, 0, size);

            try {
                String ctxKey = host + "|" + username;
                CIFSContext cifs = ctxCache.get(ctxKey);
                if (cifs == null) {
                    cifs = createCifs(username, password);
                    ctxCache.put(ctxKey, cifs);
                }
                String destUrl = "smb://" + host + "/" + encodePathSegment(share)
                        + "/" + encodePath(trimSlashes(path));
                SmbFile destFile = new SmbFile(destUrl, cifs);
                // Ensure parent exists.
                try {
                    String parentUrl = destUrl.substring(0, destUrl.lastIndexOf('/') + 1);
                    SmbFile parent = new SmbFile(parentUrl, cifs);
                    if (!parent.exists()) parent.mkdirs();
                } catch (Exception ignore) { /* mkdirs will throw below if truly missing */ }

                ContentResolver cr = ctx.getContentResolver();
                long bytesWritten = 0;
                long lastProgressEmit = 0;
                try (InputStream in = cr.openInputStream(Uri.parse(localUri));
                     OutputStream out = new SmbFileOutputStream(destFile)) {
                    if (in == null) throw new java.io.IOException("cannot open local uri");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (isStopped()) {
                            try { destFile.delete(); } catch (Exception ignore) {}
                            return Result.retry();
                        }
                        out.write(buf, 0, n);
                        bytesWritten += n;
                        // Emit progress every ~256KB so the UI's 2s poll reads
                        // a fresh byte count without being spammy.
                        if (bytesWritten - lastProgressEmit >= 256 * 1024) {
                            lastProgressEmit = bytesWritten;
                            publishProgress("uploading", id, name,
                                currentIdx, total, uploaded, failed, bytesWritten, size);
                        }
                    }
                }

                // Integrity check.  Respect a pending cancellation before
                // the blocking metadata call.
                if (isStopped()) return Result.retry();
                SmbFile verify = new SmbFile(destUrl, cifs);
                long remoteSize = verify.length();
                if (remoteSize != bytesWritten) {
                    try { verify.delete(); } catch (Exception ignore) {}
                    throw new java.io.IOException("size mismatch: wrote=" + bytesWritten
                        + " remote=" + remoteSize);
                }

                // Success — remove the entry.
                removeEntry(prefs, id);
                uploaded++;
                Log.i(TAG, "uploaded " + name + " (" + bytesWritten + " bytes)");
            } catch (Exception e) {
                failed++;
                markFailed(prefs, id, safeErrorMessage(e));
                Log.e(TAG, "upload failed " + name + ": " + e.getMessage());
            }
        }

        // Final notification.
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (uploaded > 0 || failed > 0) {
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(failed > 0 ? android.R.drawable.stat_notify_error
                                         : android.R.drawable.stat_sys_upload_done)
                .setContentTitle(failed == 0 ? "Upload complete"
                               : (uploaded == 0 ? "Upload failed" : "Uploaded with errors"))
                .setContentText(uploaded + " uploaded"
                    + (failed > 0 ? " · " + failed + " failed" : ""))
                .setContentIntent(pendingOpenIntent("/downloads"))
                .setPriority(failed > 0 ? NotificationCompat.PRIORITY_DEFAULT
                                        : NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID + 1, b.build());
        }

        publishProgress("done", "", "", uploaded + failed, uploaded + failed,
            uploaded, failed, 0, 0);

        if (failed > 0 && uploaded == 0) return Result.retry();
        return Result.success();
    }

    // ============================================================
    // Queue management
    // ============================================================

    private void recoverCrashed(SharedPreferences prefs) {
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray arr = readQueue(prefs);
                boolean changed = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if ("uploading".equals(o.optString("status"))) {
                        o.put("status", "queued");
                        changed = true;
                    }
                }
                if (changed) writeQueue(prefs, arr);
            } catch (Exception ignore) {}
        }
    }

    private int countQueued(SharedPreferences prefs) {
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray arr = readQueue(prefs);
                int n = 0;
                for (int i = 0; i < arr.length(); i++) {
                    if ("queued".equals(arr.getJSONObject(i).optString("status"))) n++;
                }
                return n;
            } catch (Exception e) { return 0; }
        }
    }

    private int countRemaining(SharedPreferences prefs) {
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray arr = readQueue(prefs);
                int n = 0;
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.getJSONObject(i).optString("status");
                    if ("queued".equals(s) || "uploading".equals(s)) n++;
                }
                return n;
            } catch (Exception e) { return 0; }
        }
    }

    /** Atomically flip the first queued entry to uploading and return it. */
    private JSONObject popFirstQueued(SharedPreferences prefs) {
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray arr = readQueue(prefs);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if ("queued".equals(o.optString("status"))) {
                        o.put("status", "uploading");
                        writeQueue(prefs, arr);
                        return o;
                    }
                }
            } catch (Exception ignore) {}
            return null;
        }
    }

    private void removeEntry(SharedPreferences prefs, String id) {
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray arr = readQueue(prefs);
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (!id.equals(o.optString("id"))) out.put(o);
                }
                writeQueue(prefs, out);
            } catch (Exception ignore) {}
        }
    }

    private void markFailed(SharedPreferences prefs, String id, String msg) {
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray arr = readQueue(prefs);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (id.equals(o.optString("id"))) {
                        o.put("status", "failed");
                        o.put("error", msg == null ? "unknown" : msg);
                    }
                }
                writeQueue(prefs, arr);
            } catch (Exception ignore) {}
        }
    }

    private JSONArray readQueue(SharedPreferences prefs) throws Exception {
        String raw = prefs.getString(QUEUE_KEY, "[]");
        return new JSONArray(raw);
    }

    private void writeQueue(SharedPreferences prefs, JSONArray arr) {
        prefs.edit().putString(QUEUE_KEY, arr.toString()).commit();
    }

    // ============================================================
    // SMB + URL helpers (mirror BackupWorker's — keep in sync)
    // ============================================================

    private CIFSContext createCifs(String username, String password) throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.minVersion", "SMB202");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        props.setProperty("jcifs.smb.client.responseTimeout", "30000");
        props.setProperty("jcifs.smb.client.soTimeout", "35000");
        props.setProperty("jcifs.smb.client.dfs.disabled", "true");
        CIFSContext ctx = new BaseContext(new PropertyConfiguration(props));
        if (username != null && !username.isEmpty()) {
            ctx = ctx.withCredentials(new NtlmPasswordAuthenticator("", username,
                password == null ? "" : password));
        } else {
            ctx = ctx.withGuestCrendentials();
        }
        return ctx;
    }

    private String encodePathSegment(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '#' || c == '?' || c == '%') sb.append(String.format("%%%02X", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private String encodePath(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(encodePathSegment(p));
        }
        return sb.toString();
    }

    private String trimSlashes(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == '/' || s.charAt(a) == '\\')) a++;
        while (b > a && (s.charAt(b - 1) == '/' || s.charAt(b - 1) == '\\')) b--;
        return s.substring(a, b);
    }

    private String safeErrorMessage(Throwable e) {
        if (e == null) return "";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        m = m.replaceAll("smb://[^ \\t\\r\\n'\\)]+", "<smb-path>");
        m = m.replaceAll("(?i)user(name)?\\s*=\\s*[^\\s,;]+", "user=<redacted>");
        m = m.replaceAll("(?i)password\\s*=\\s*[^\\s,;]+", "password=<redacted>");
        return m;
    }

    // ============================================================
    // Notifications + progress
    // ============================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Upload queue",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows upload progress");
            NotificationManager nm = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    private ForegroundInfo foregroundInfo(String title, String detail, int cur, int total) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(detail.isEmpty() ? "" : (detail + (total > 0 ? " (" + cur + "/" + total + ")" : "")))
            .setProgress(Math.max(total, 1), Math.max(cur, 0), total == 0)
            .setContentIntent(pendingOpenIntent("/downloads"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, b.build(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, b.build());
    }

    private void publishProgress(String phase, String currentId, String currentName,
                                 int cur, int total, int uploaded, int failed,
                                 long currentBytes, long currentSize) {
        try {
            setProgressAsync(new Data.Builder()
                .putString("phase", phase)
                .putString("currentId", currentId == null ? "" : currentId)
                .putString("currentName", currentName == null ? "" : currentName)
                .putInt("currentIndex", cur)
                .putInt("totalFiles", total)
                .putInt("uploadedCount", uploaded)
                .putInt("failedCount", failed)
                .putLong("currentBytes", currentBytes)
                .putLong("currentSize", currentSize)
                .build());
        } catch (Exception ignore) {}
    }

    private PendingIntent pendingOpenIntent(String path) {
        Intent intent = new Intent(getApplicationContext(),
            com.mrdon.server.MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(com.mrdon.server.MainActivity.EXTRA_OPEN_PATH, path);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(getApplicationContext(), 2000, intent, flags);
    }
}
