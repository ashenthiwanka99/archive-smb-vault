package com.mrdon.server.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * Reconciles the local "already backed up" list against what's actually on the
 * SMB shelf.  Any media ID whose expected file is missing on the shelf is
 * dropped from the local list so that the next backup run re-uploads it.
 *
 * Used by the user-facing "Verify against shelf" button in Advanced options.
 * Safe to cancel mid-run via WorkManager — the update only commits at the end.
 *
 * This intentionally duplicates the path-mapping logic in BackupWorker
 * (relativeSourceFolder + DATE_TAKEN bucketing) so the two worker classes
 * stay decoupled.  Keep these in sync if you change the upload layout.
 */
public class VerifyWorker extends Worker {
    private static final String TAG = "VerifyWorker";
    private static final String PREFS_NAME = "backup_prefs";

    public VerifyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String host = prefs.getString("host", "");
        String shareName = prefs.getString("shareName", "");
        String backupPath = prefs.getString("backupPath", "/Backup/Phone");
        String username = prefs.getString("username", "");
        String password = prefs.getString("password", "");

        if (host.isEmpty() || shareName.isEmpty()) {
            publishProgress("error", "No backup target configured", 0, 0, 0);
            return Result.failure();
        }

        Set<String> backedUpIds = new HashSet<>(
            prefs.getStringSet("backedUpIds", new HashSet<>()));
        int initialSize = backedUpIds.size();
        if (backedUpIds.isEmpty()) {
            publishProgress("done", "Nothing to verify", 0, 0, 0);
            return Result.success();
        }

        publishProgress("scanning", "Reading phone library…", 0, 0, 0);

        ContentResolver resolver = getApplicationContext().getContentResolver();
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM", Locale.US);
        // Must use the same timezone as BackupWorker's bucketing (UTC); if
        // they disagreed, a photo taken near month-end would verify as
        // "missing" because we'd look in a different folder than it lives in.
        dateFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        // Match BackupWorker's URL construction exactly so we look in the same
        // place the files were uploaded to.
        String destBase = "smb://" + host + "/" + encodePathSegment(shareName)
                + "/" + encodePath(trimSlashes(backupPath)) + "/";

        // dirUrl -> list of [mediaId, filename]
        Map<String, List<String[]>> plan = new HashMap<>();

        collectExpected(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "photo_", backedUpIds, plan, dateFmt, destBase);
        if (isStopped()) return Result.retry();
        collectExpected(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "video_", backedUpIds, plan, dateFmt, destBase);
        if (isStopped()) return Result.retry();

        int plannedDirs = plan.size();
        if (plannedDirs == 0) {
            // Everything in backedUpIds was deleted from the phone too; nothing
            // we can verify.  Leave the set alone — those IDs are harmless.
            publishProgress("done", "No resolvable items on phone", 0, 0, 0);
            return Result.success();
        }

        publishProgress("connecting", "Connecting to shelf…", 0, 0, plannedDirs);

        try {
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.minVersion", "SMB202");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            props.setProperty("jcifs.smb.client.responseTimeout", "30000");
            props.setProperty("jcifs.smb.client.soTimeout", "35000");
            props.setProperty("jcifs.smb.client.dfs.disabled", "true");

            CIFSContext ctx = new BaseContext(new PropertyConfiguration(props));
            if (!username.isEmpty()) {
                ctx = ctx.withCredentials(
                    new NtlmPasswordAuthenticator("", username, password));
            } else {
                ctx = ctx.withGuestCrendentials();
            }

            Set<String> droppedIds = new HashSet<>();
            int dirIdx = 0;

            for (Map.Entry<String, List<String[]>> entry : plan.entrySet()) {
                if (isStopped()) return Result.retry();
                dirIdx++;
                String dirUrl = entry.getKey();
                publishProgress("checking", dirUrl, dirIdx, droppedIds.size(), plannedDirs);

                Set<String> present = new HashSet<>();
                try {
                    SmbFile dir = new SmbFile(dirUrl, ctx);
                    if (dir.exists() && dir.isDirectory()) {
                        String[] names = dir.list();
                        if (names != null) {
                            for (String n : names) present.add(n);
                        }
                    }
                    // If the dir doesn't exist, `present` stays empty and every
                    // expected file in this bucket is classed as missing.
                } catch (Exception ex) {
                    Log.w(TAG, "List failed for " + dirUrl + " — treating as empty", ex);
                }

                for (String[] pair : entry.getValue()) {
                    String id = pair[0];
                    String filename = pair[1];
                    if (!present.contains(filename)) {
                        droppedIds.add(id);
                    }
                }
            }

            backedUpIds.removeAll(droppedIds);
            prefs.edit()
                .putStringSet("backedUpIds", backedUpIds)
                .apply();

            int after = backedUpIds.size();
            publishProgress("done",
                "Dropped " + droppedIds.size() + " · " + after + " remain",
                plannedDirs, droppedIds.size(), plannedDirs);

            Log.i(TAG, "Verify complete: started=" + initialSize +
                ", remaining=" + after + ", dropped=" + droppedIds.size());
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "VerifyWorker failed", e);
            String msg = e.getMessage() == null ? "Verify failed" : e.getMessage();
            publishProgress("error", msg, 0, 0, 0);
            return Result.failure();
        }
    }

    /**
     * Iterates a MediaStore collection (photos or videos), resolving each row
     * whose ID is in `backedUpIds` into the expected SMB directory it should
     * live in.  Rows not present in `backedUpIds` are skipped cheaply.
     */
    private void collectExpected(
        ContentResolver resolver, Uri collection, String idPrefix,
        Set<String> backedUpIds, Map<String, List<String[]>> plan,
        SimpleDateFormat dateFmt, String destBase
    ) {
        String[] proj = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED
        };
        Cursor cursor = resolver.query(collection, proj, null, null, null);
        if (cursor == null) return;
        try {
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            int dateTakenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN);
            int dateModCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);

            while (cursor.moveToNext()) {
                String id = idPrefix + cursor.getLong(idCol);
                if (!backedUpIds.contains(id)) continue;

                String filePath = cursor.getString(pathCol);
                String name = cursor.getString(nameCol);
                long dateTaken = dateTakenCol >= 0 && !cursor.isNull(dateTakenCol)
                    ? cursor.getLong(dateTakenCol) : 0;
                long dateModMs = dateModCol >= 0 && !cursor.isNull(dateModCol)
                    ? cursor.getLong(dateModCol) * 1000L : 0;
                long eff = dateTaken > 0 ? dateTaken
                         : dateModMs > 0 ? dateModMs
                         : System.currentTimeMillis();
                String srcFolder = relativeSourceFolder(filePath);
                String dateFolder = dateFmt.format(new Date(eff));
                String dirUrl = destBase
                    + encodePath(srcFolder) + "/"
                    + encodePathSegment(dateFolder) + "/";

                List<String[]> bucket = plan.get(dirUrl);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    plan.put(dirUrl, bucket);
                }
                // We store the *raw* filename here — VerifyWorker compares
                // against SmbFile.list() results, which JCIFS returns in raw
                // (decoded) form, not URL-encoded.
                bucket.add(new String[]{id, name});
            }
        } finally {
            cursor.close();
        }
    }

    /** Mirrors BackupWorker.encodePathSegment — keep in sync. */
    private String encodePathSegment(String segment) {
        if (segment == null || segment.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(segment.length() + 4);
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '#' || c == '?' || c == '%') {
                sb.append(String.format("%%%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Mirrors BackupWorker.encodePath — keep in sync. */
    private String encodePath(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(encodePathSegment(parts[i]));
        }
        return sb.toString();
    }

    /** Mirrors BackupWorker.trimSlashes — keep in sync. */
    private String trimSlashes(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && (s.charAt(start) == '/' || s.charAt(start) == '\\')) start++;
        while (end > start && (s.charAt(end - 1) == '/' || s.charAt(end - 1) == '\\')) end--;
        return s.substring(start, end);
    }

    /** Mirrors BackupWorker.relativeSourceFolder — keep in sync. */
    private String relativeSourceFolder(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "Other";
        java.io.File f = new java.io.File(filePath);
        String parent = f.getParent();
        if (parent == null) return "Other";

        String rel = parent;
        if (rel.startsWith("/storage/emulated/0/")) {
            rel = rel.substring("/storage/emulated/0/".length());
        } else if (rel.startsWith("/sdcard/")) {
            rel = rel.substring("/sdcard/".length());
        } else if (rel.startsWith("/storage/")) {
            int nextSlash = rel.indexOf('/', "/storage/".length());
            rel = nextSlash >= 0 ? rel.substring(nextSlash + 1) : "";
        }

        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return rel.isEmpty() ? "Other" : rel;
    }

    private void publishProgress(String phase, String detail,
                                 int current, int dropped, int total) {
        try {
            setProgressAsync(new Data.Builder()
                .putString("phase", phase)
                .putString("detail", detail == null ? "" : detail)
                .putInt("current", current)
                .putInt("dropped", dropped)
                .putInt("total", total)
                .build());
        } catch (Exception ignore) { /* best-effort */ }
    }
}
