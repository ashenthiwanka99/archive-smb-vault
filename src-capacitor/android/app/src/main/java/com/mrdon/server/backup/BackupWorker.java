package com.mrdon.server.backup;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileOutputStream;

public class BackupWorker extends Worker {
    private static final String TAG = "BackupWorker";
    private static final String CHANNEL_ID = "backup_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS_NAME = "backup_prefs";

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        boolean enabled = prefs.getBoolean("enabled", false);
        if (!enabled) return Result.success();

        // Stamp the wall-clock start time so the history record can compute
        // duration; bytes accumulator builds across the upload loop so the
        // per-run record carries a real transferred-bytes number, not just
        // a file count (useful for the sparkline).
        final long runStartedAt = System.currentTimeMillis();
        long runBytes = 0;

        String host = prefs.getString("host", "");
        String shareName = prefs.getString("shareName", "");
        String backupPath = prefs.getString("backupPath", "/Backup/Phone");
        String username = prefs.getString("username", "");
        String password = prefs.getString("password", "");
        boolean includePhotos = prefs.getBoolean("includePhotos", true);
        boolean includeVideos = prefs.getBoolean("includeVideos", true);
        boolean homeNetworkOnly = prefs.getBoolean("homeNetworkOnly", false);
        long lastBackup = prefs.getLong("lastBackupTimestamp", 0);
        Set<String> selectedFolders = prefs.getStringSet("selectedFolders", new HashSet<>());
        Set<String> excludePatterns = prefs.getStringSet("excludePatterns", new HashSet<>());
        // Compile user-supplied glob patterns to regex once per run.
        final java.util.List<java.util.regex.Pattern> excludeRegex =
            compileExcludePatterns(excludePatterns);
        // Folder layout on the shelf: "source" (default) keeps per-source-folder
        // subtrees; "year-month" ignores the source folder and buckets by date;
        // "flat" dumps everything at the backup root.
        String backupOrganization = prefs.getString("backupOrganization", "source");

        // No source folders picked = nothing to back up.  Exit before we
        // even open the SMB connection.
        if (selectedFolders.isEmpty()) {
            Log.i(TAG, "BackupWorker: no source folders selected, skipping run");
            return Result.success();
        }

        // User-initiated runNow() requests set bypassHomeCheck so a "Back up now"
        // tap on a foreign network still tries (and fails loudly) instead of
        // silently skipping.  Same input-data flag also bypasses the time window
        // check below — if the user explicitly taps the button at 3pm, they've
        // opted in.
        boolean bypassHomeCheck = getInputData().getBoolean("bypassHomeCheck", false);

        // Time-window: if configured, scheduled runs outside the window return
        // success (not retry) so WorkManager uses the normal periodic interval
        // rather than retry-backoff — the next hourly tick will re-check.
        boolean windowEnabled = prefs.getBoolean("windowEnabled", false);
        if (windowEnabled && !bypassHomeCheck) {
            int startH = prefs.getInt("windowStartHour", 2);
            int endH = prefs.getInt("windowEndHour", 6);
            if (!isInWindow(startH, endH)) {
                Log.i(TAG, "Outside backup window (" + startH + "h–" + endH + "h), skipping");
                return Result.success();
            }
        }

        if (homeNetworkOnly && !bypassHomeCheck && !host.isEmpty()) {
            // Quick TCP probe to the NAS's SMB port.  A 3-second window is
            // plenty on any working home network; on café/mobile Wi-Fi the
            // connect fails fast and we exit without advancing state.  This
            // replaces the "SSID matching" pattern, which would require
            // location permission on Android 10+ just to read the Wi-Fi name.
            if (!isHostReachable(host, 445, 3000)) {
                Log.i(TAG, "Home network only: " + host + ":445 unreachable, skipping run");
                // Result.success() (not retry) so WorkManager uses the normal
                // periodic interval rather than backoff — the backoff timer
                // would waste battery reprobing every few minutes.
                return Result.success();
            }
        }

        Set<String> backedUpIds = prefs.getStringSet("backedUpIds", new HashSet<>());
        Set<String> newBackedUpIds = new HashSet<>(backedUpIds);

        if (host.isEmpty() || shareName.isEmpty()) {
            Log.w(TAG, "BackupWorker aborted: host or shareName empty");
            return Result.failure();
        }

        createNotificationChannel();
        // Show a notification immediately so the user sees the worker started,
        // even before we know how many files we'll be uploading.
        setForegroundAsync(createScanningForegroundInfo());
        publishProgress("scanning", "", 0, 0);

        try {
            // Create CIFS context
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.minVersion", "SMB202");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            props.setProperty("jcifs.smb.client.responseTimeout", "30000");
            props.setProperty("jcifs.smb.client.soTimeout", "35000");
            props.setProperty("jcifs.smb.client.dfs.disabled", "true");

            CIFSContext ctx = new BaseContext(new PropertyConfiguration(props));
            if (username != null && !username.isEmpty()) {
                ctx = ctx.withCredentials(new NtlmPasswordAuthenticator("", username, password != null ? password : ""));
            } else {
                ctx = ctx.withGuestCrendentials();
            }

            // Files are placed under <backupPath>/<source-folder>/<YYYY-MM>/<name>
            //  - source-folder mirrors the device path relative to the storage root
            //    (so DCIM/Camera stays DCIM/Camera, Pictures/Screenshots stays
            //    Pictures/Screenshots) — makes photos findable on the NAS by the
            //    same mental model as the phone's gallery.
            //  - YYYY-MM uses the file's own DATE_TAKEN (or DATE_MODIFIED fallback)
            //    so a photo from March 2024 lands in .../2024-03/ even when the
            //    backup runs months later.
            //
            // Every path segment is URL-encoded; JCIFS' SmbFile(String, ctx)
            // constructor expects a strict URL and silently parses garbage when
            // it sees unescaped spaces or unicode — which is how "Pictures/
            // WhatsApp Images" and similar folders were failing before.
            String destBase = "smb://" + host + "/" + encodePathSegment(shareName)
                    + "/" + encodePath(trimSlashes(backupPath)) + "/";
            SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM", Locale.US);
            // Pin bucketing to UTC so a device-timezone change never causes a
            // photo to be re-classed into a different month folder between
            // backup and verify runs.
            dateFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

            // Upfront health check: make sure the backup root exists (or can be
            // created).  Failing loudly here prevents the "uploaded 5/5 but the
            // folder doesn't exist on the server" class of silent failure.
            try {
                SmbFile rootDir = new SmbFile(destBase, ctx);
                if (!rootDir.exists()) rootDir.mkdirs();
                Log.i(TAG, "Backup root ready: " + destBase);
            } catch (Exception rootEx) {
                Log.e(TAG, "Cannot open backup root at " + destBase, rootEx);
                NotificationManager nm = (NotificationManager) getApplicationContext()
                        .getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationCompat.Builder b = new NotificationCompat.Builder(
                        getApplicationContext(), CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Backup failed")
                    .setContentText("Could not reach backup folder on shelf.")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "Could not create or access " + backupPath + " on the shelf. "
                        + (rootEx.getMessage() == null ? "" : rootEx.getMessage())))
                    .setContentIntent(pendingOpenIntent("/gallery"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);
                nm.notify(NOTIFICATION_ID + 3, b.build());
                return Result.retry();
            }

            // Directories created during this run — avoids round-trip existence
            // checks for every file in the same bucket.
            Set<String> createdDirs = new HashSet<>();

            // Scan for new media
            List<MediaItem> newMedia = new ArrayList<>();
            ContentResolver resolver = getApplicationContext().getContentResolver();
            long sinceSec = lastBackup / 1000;

            if (includePhotos) {
                scanMedia(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.DATE_MODIFIED
                    },
                    MediaStore.Images.Media.DATE_ADDED, sinceSec, backedUpIds, newMedia, "photo",
                    selectedFolders, excludeRegex);
            }
            if (includeVideos) {
                scanMedia(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_TAKEN,
                        MediaStore.Video.Media.DATE_MODIFIED
                    },
                    MediaStore.Video.Media.DATE_ADDED, sinceSec, backedUpIds, newMedia, "video",
                    selectedFolders, excludeRegex);
            }

            if (newMedia.isEmpty()) {
                Log.i(TAG, "No new media to backup (lastBackup=" + lastBackup + ", selectedFolders=" + selectedFolders.size() + ")");
                // Tell the user explicitly — otherwise the worker appears to do
                // nothing and they can't tell if it even ran.
                NotificationManager nm = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationCompat.Builder b = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_upload)
                    .setContentTitle("Backup: nothing new")
                    .setContentText("No new photos or videos since last intake.")
                    .setContentIntent(pendingOpenIntent("/gallery"))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true);
                nm.notify(NOTIFICATION_ID + 2, b.build());
                return Result.success();
            }

            Log.i(TAG, "Found " + newMedia.size() + " new media files to backup");

            // Pre-scan destination directories so we can skip files that
            // already exist on the shelf with a matching size.  This avoids
            // re-uploading after a "Reset backup history" when the files
            // themselves are still on the NAS.  One SMB list-dir per unique
            // bucket, much cheaper than one exists() per file.
            java.util.Map<String, java.util.Map<String, Long>> dirInventory = new java.util.HashMap<>();
            java.util.Set<String> uniqueDirs = new java.util.HashSet<>();
            for (MediaItem it : newMedia) {
                String srcF = relativeSourceFolder(it.path);
                long eff = it.dateTaken > 0 ? it.dateTaken
                         : it.dateModifiedMs > 0 ? it.dateModifiedMs
                         : System.currentTimeMillis();
                uniqueDirs.add(buildItemDirUrl(destBase, srcF, eff, dateFmt, backupOrganization));
            }
            int dirIdx = 0;
            for (String dirUrl : uniqueDirs) {
                if (isStopped()) return Result.retry();
                dirIdx++;
                // Emit progress so the UI doesn't look frozen during the
                // multi-second pre-scan on a large library.  Phase "scanning"
                // lets the UI show "Checking X of Y on shelf…" instead of
                // stuck at 0/N.
                publishProgress("scanning", "shelf " + dirIdx + "/" + uniqueDirs.size(),
                    dirIdx, uniqueDirs.size());
                try {
                    SmbFile d = new SmbFile(dirUrl, ctx);
                    if (!d.exists() || !d.isDirectory()) continue;
                    SmbFile[] children = d.listFiles();
                    if (children == null) continue;
                    java.util.Map<String, Long> nameSize = new java.util.HashMap<>();
                    for (SmbFile c : children) {
                        try {
                            String nm = c.getName();
                            if (nm.endsWith("/")) continue;
                            nameSize.put(nm, c.length());
                        } catch (Exception ignore) {}
                    }
                    dirInventory.put(dirUrl, nameSize);
                } catch (Exception e) {
                    // Missing dir is fine — means nothing to skip.
                    Log.d(TAG, "pre-scan miss " + dirUrl + " — " + e.getMessage());
                }
            }

            int total = newMedia.size();
            int uploaded = 0;  // successful writes only
            int failed = 0;
            int skippedDup = 0;  // already on shelf with matching size
            int idx = 0;

            for (MediaItem item : newMedia) {
                if (isStopped()) return Result.retry();

                idx++;
                setForegroundAsync(createForegroundInfo(item.name, idx, total));
                publishProgress("uploading", item.name, idx, total);

                try {
                    // Build the destination directory for this specific file,
                    // URL-encoding every segment so spaces / unicode in folder
                    // names don't break JCIFS URL parsing.
                    String srcFolderRaw = relativeSourceFolder(item.path);
                    long effectiveDate = item.dateTaken > 0 ? item.dateTaken
                                       : item.dateModifiedMs > 0 ? item.dateModifiedMs
                                       : System.currentTimeMillis();
                    String itemDirUrl = buildItemDirUrl(destBase, srcFolderRaw,
                            effectiveDate, dateFmt, backupOrganization);

                    // Pre-scan shortcut: if the inventory tells us this file
                    // is already on the shelf with the exact size, mark it
                    // catalogued and skip the transfer entirely.  Saves a full
                    // re-upload after a reset when the files themselves are
                    // still there.
                    java.util.Map<String, Long> inv = dirInventory.get(itemDirUrl);
                    if (inv != null) {
                        Long existingSize = inv.get(item.name);
                        if (existingSize != null && existingSize == item.size) {
                            skippedDup++;
                            newBackedUpIds.add(item.id);
                            Log.d(TAG, "pre-scan: skip already-on-shelf " + item.name);
                            continue;
                        }
                    }

                    if (!createdDirs.contains(itemDirUrl)) {
                        SmbFile dir = new SmbFile(itemDirUrl, ctx);
                        if (!dir.exists()) dir.mkdirs();
                        createdDirs.add(itemDirUrl);
                    }

                    // Name-collision handling: if a file with the same name
                    // exists but a different size, overwrite it.  Previous
                    // behavior renamed with the media id (`IMG__42.jpg`) but
                    // then VerifyWorker + subsequent runs both looked at the
                    // original name → treated it as missing → infinite
                    // re-upload loop.  Overwrite is the simplest stable
                    // resolution and matches what a sync-style tool should do.
                    String finalName = encodePathSegment(item.name);
                    SmbFile destFile = new SmbFile(itemDirUrl + finalName, ctx);

                    Uri contentUri = Uri.parse(item.uri);
                    // Stream via try-with-resources so a read/write exception
                    // mid-transfer doesn't leak the file handle + SMB socket.
                    // Also poll isStopped() every buffer so big videos can
                    // actually be cancelled via the Stop button.
                    long bytesWritten = 0;
                    try (InputStream is = resolver.openInputStream(contentUri);
                         OutputStream os = new SmbFileOutputStream(destFile)) {
                        if (is == null) {
                            failed++;
                            Log.w(TAG, "Skipped (no input stream): " + item.name);
                            continue;
                        }
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            if (isStopped()) {
                                // Delete the partial so the next run doesn't
                                // see a half-written file as "done."
                                try { destFile.delete(); } catch (Exception ignore) {}
                                return Result.retry();
                            }
                            os.write(buffer, 0, bytesRead);
                            bytesWritten += bytesRead;
                        }
                    }

                    // Integrity check: re-open the file with a fresh handle
                    // (JCIFS caches lengths per-handle) and compare to what
                    // we wrote.  Mismatch → treat as failure so the next
                    // run retries instead of accepting a corrupt upload.
                    SmbFile verify = new SmbFile(itemDirUrl + finalName, ctx);
                    long remoteSize = verify.length();
                    if (remoteSize != bytesWritten) {
                        failed++;
                        Log.w(TAG, "Integrity mismatch on " + item.name
                            + " wrote=" + bytesWritten + " remote=" + remoteSize);
                        try { verify.delete(); } catch (Exception ignore) {}
                        continue;
                    }

                    uploaded++;
                    runBytes += bytesWritten;
                    newBackedUpIds.add(item.id);
                    Log.i(TAG, "Backed up: " + itemDirUrl + item.name);

                    // Safe delete-from-phone: if the user opted in, queue this
                    // successfully-backed-up file for later deletion.  We only
                    // enqueue when the setting is on AND the file passed the
                    // integrity check above — never uploads that failed verify.
                    boolean autoCleanup = prefs.getBoolean("autoCleanup", false);
                    if (autoCleanup) {
                        long graceDays = prefs.getLong("cleanupGraceDays", 7L);
                        if (graceDays < 0) graceDays = 0L;
                        appendCleanupQueueEntry(prefs, item, graceDays);
                    }

                    // Persist backedUpIds every 3 successful uploads so the UI's
                    // poll picks up live growth.  NOTE: we do NOT write
                    // `lastBackupCount` here — that field is the result of a
                    // completed run.  Persisting it mid-run caused the status
                    // card to show a stale "last backup ran successfully"
                    // after an OS-killed partial run.
                    if (uploaded % 3 == 0) {
                        prefs.edit()
                            .putStringSet("backedUpIds", newBackedUpIds)
                            .apply();
                    }
                } catch (Exception e) {
                    failed++;
                    Log.e(TAG, "Failed to backup: " + item.name, e);
                }
            }

            // Update prefs.  IMPORTANT: only advance lastBackupTimestamp when
            // at least one file actually uploaded — otherwise a run where every
            // upload failed would hide those files forever (the next scan's
            // `date_added > cutoff` filter would skip them).
            SharedPreferences.Editor editor = prefs.edit();
            if (uploaded > 0) {
                editor.putLong("lastBackupTimestamp", System.currentTimeMillis());
            }
            editor.putStringSet("backedUpIds", newBackedUpIds);
            editor.putInt("lastBackupCount", uploaded);
            editor.apply();

            // Record a history entry for this run.  Feeds the timeline + the
            // bytes-per-day sparkline on the backup card.  `trigger` is
            // inferred from the same input-data flag that drives the home-
            // network bypass — user taps always set bypassHomeCheck=true.
            boolean wasManual = getInputData().getBoolean("bypassHomeCheck", false);
            appendHistoryRecord(prefs, runStartedAt, uploaded, failed, skippedDup,
                    runBytes, wasManual ? "manual" : "scheduled", null);

            // Final notification — split success vs failure so a silent all-
            // failure run surfaces clearly to the user.
            NotificationManager nm = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            StringBuilder summary = new StringBuilder();
            summary.append(uploaded).append(" uploaded");
            if (skippedDup > 0) summary.append(" · ").append(skippedDup).append(" already on shelf");
            if (failed > 0) summary.append(" · ").append(failed).append(" failed");

            NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(failed > 0 ? android.R.drawable.stat_notify_error
                                         : android.R.drawable.ic_menu_upload)
                .setContentTitle(failed == 0
                    ? "Backup complete"
                    : (uploaded == 0 ? "Backup failed" : "Backup finished with errors"))
                .setContentText(summary.toString())
                .setContentIntent(pendingOpenIntent("/gallery"))
                .setPriority(failed > 0 ? NotificationCompat.PRIORITY_DEFAULT
                                        : NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);
            nm.notify(NOTIFICATION_ID + 1, builder.build());

            // If every attempted upload failed, surface as a retry so the worker
            // runs again instead of pretending success.
            if (failed > 0 && uploaded == 0) return Result.retry();
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "BackupWorker failed", e);
            return Result.retry();
        }
    }

    private void scanMedia(ContentResolver resolver, Uri collectionUri, String[] projection,
                          String dateColumn, long sinceSec, Set<String> existingIds,
                          List<MediaItem> results, String type, Set<String> selectedFolders,
                          java.util.List<java.util.regex.Pattern> excludeRegex) {
        // Scan the entire collection; the `existingIds` check below is the
        // authoritative dedup.  A date-based pre-filter looks like a nice
        // optimization, but it breaks as soon as the user adds a previously-
        // un-selected folder whose photos are older than the last run's
        // timestamp — those files would then be silently skipped forever.
        String sort = dateColumn + " DESC";
        Cursor cursor = resolver.query(collectionUri, projection, null, null, sort);
        if (cursor != null) {
            int idCol = cursor.getColumnIndexOrThrow(projection[0]);
            int nameCol = cursor.getColumnIndexOrThrow(projection[1]);
            int pathCol = cursor.getColumnIndexOrThrow(projection[2]);
            int sizeCol = cursor.getColumnIndexOrThrow(projection[3]);
            // These two are new — guarded with indexOf so a missing column is
            // non-fatal on OEMs that don't expose them.
            int dateTakenCol = cursor.getColumnIndex(projection[4]);
            int dateModCol   = cursor.getColumnIndex(projection[5]);

            while (cursor.moveToNext()) {
                String id = type + "_" + cursor.getLong(idCol);
                if (existingIds.contains(id)) continue;

                String filePath = cursor.getString(pathCol);
                // Explicit opt-in: if the user has not picked any folders,
                // we skip everything.  Previous behavior treated empty as
                // "back up the entire camera roll" which was surprising
                // after a fresh install / reset and caused unwanted uploads.
                if (filePath == null) continue;
                java.io.File f = new java.io.File(filePath);
                String parentDir = f.getParent();
                if (selectedFolders.isEmpty()) continue;
                if (parentDir == null || !selectedFolders.contains(parentDir)) continue;

                // Filter by user-supplied exclude patterns.  Matches against
                // the full shelf-relative path ("DCIM/Camera/IMG_001.jpg")
                // AND the bare filename ("IMG_001.jpg") so users can write
                // simple globs like "*.tmp" or path-qualified ones.
                if (!excludeRegex.isEmpty() && filePath != null) {
                    String rel = relativeSourceFolder(filePath);
                    String pathKey = (rel.equals("Other") ? "" : rel + "/")
                                   + new java.io.File(filePath).getName();
                    String nameOnly = new java.io.File(filePath).getName();
                    boolean skip = false;
                    for (java.util.regex.Pattern p : excludeRegex) {
                        if (p.matcher(pathKey).matches() || p.matcher(nameOnly).matches()) {
                            skip = true; break;
                        }
                    }
                    if (skip) continue;
                }

                MediaItem item = new MediaItem();
                item.id = id;
                item.name = cursor.getString(nameCol);
                item.path = filePath;
                item.uri = collectionUri.toString() + "/" + cursor.getLong(idCol);
                item.size = cursor.getLong(sizeCol);
                item.type = type;
                // DATE_TAKEN is in milliseconds, DATE_MODIFIED is in seconds.
                item.dateTaken = dateTakenCol >= 0 && !cursor.isNull(dateTakenCol)
                    ? cursor.getLong(dateTakenCol) : 0;
                item.dateModifiedMs = dateModCol >= 0 && !cursor.isNull(dateModCol)
                    ? cursor.getLong(dateModCol) * 1000L : 0;
                results.add(item);
            }
            cursor.close();
        }
    }

    /**
     * Convert a list of user-supplied glob patterns (e.g. "*.tmp",
     * ".thumbnails/*", "screenshot_*.png") into compiled case-insensitive
     * regexes.  Supported globs:
     *   *   any sequence of chars except '/'
     *   **  any sequence of chars including '/'  (shorthand: "**" only)
     *   ?   any single non-'/' char
     *   /   directory separator
     * Anything else is escaped.  Empty / invalid patterns are skipped.
     */
    private java.util.List<java.util.regex.Pattern> compileExcludePatterns(Set<String> patterns) {
        java.util.List<java.util.regex.Pattern> out = new java.util.ArrayList<>();
        if (patterns == null) return out;
        for (String glob : patterns) {
            if (glob == null || glob.trim().isEmpty()) continue;
            try {
                StringBuilder re = new StringBuilder();
                re.append("^");
                for (int i = 0; i < glob.length(); i++) {
                    char c = glob.charAt(i);
                    if (c == '*') {
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                            re.append(".*");
                            i++;
                        } else {
                            re.append("[^/]*");
                        }
                    } else if (c == '?') {
                        re.append("[^/]");
                    } else if ("\\.^$+{}[]|()".indexOf(c) >= 0) {
                        re.append('\\').append(c);
                    } else {
                        re.append(c);
                    }
                }
                re.append("$");
                out.add(java.util.regex.Pattern.compile(re.toString(),
                    java.util.regex.Pattern.CASE_INSENSITIVE));
            } catch (Exception ignore) {
                Log.w(TAG, "skip invalid exclude pattern: " + glob);
            }
        }
        return out;
    }

    /**
     * Fast-fail TCP reachability probe.  Used as a "home network only" guard
     * so scheduled runs on café/airport/mobile networks exit in ~3s instead
     * of waiting on a 30-second JCIFS connection timeout.
     */
    /**
     * Append a successfully-uploaded + integrity-verified item to the cleanup
     * queue.  Persisted in a JSON array under `cleanupQueue` in backup_prefs;
     * BackupPlugin reads and mutates the same key.  Grace window starts now —
     * `eligibleAt` is what the UI uses to decide when the entry becomes
     * actionable.  Idempotent on the id so a re-run doesn't duplicate entries.
     */
    private static final String CLEANUP_KEY = "cleanupQueue";
    private static final int CLEANUP_MAX = 10_000;
    private void appendCleanupQueueEntry(SharedPreferences prefs, MediaItem item, long graceDays) {
        try {
            String raw = prefs.getString(CLEANUP_KEY, "[]");
            JSONArray arr;
            try { arr = new JSONArray(raw); } catch (JSONException e) { arr = new JSONArray(); }
            // De-dup by id.  A repeat run that somehow re-uploads the same
            // MediaItem would otherwise queue it twice.
            for (int i = 0; i < arr.length(); i++) {
                try {
                    if (item.id.equals(arr.getJSONObject(i).optString("id"))) return;
                } catch (Exception ignore) {}
            }
            org.json.JSONObject rec = new org.json.JSONObject();
            rec.put("id", item.id);
            rec.put("name", item.name);
            rec.put("path", item.path);
            rec.put("uri", item.uri);
            rec.put("size", item.size);
            rec.put("type", item.type);
            rec.put("dateModifiedMs", item.dateModifiedMs);
            long now = System.currentTimeMillis();
            rec.put("queuedAt", now);
            rec.put("eligibleAt", now + (graceDays * 86_400_000L));
            arr.put(rec);
            // Bound the queue — very defensive, but a runaway backup on a
            // million-file library shouldn't pathologically explode prefs.
            while (arr.length() > CLEANUP_MAX) arr.remove(0);
            prefs.edit().putString(CLEANUP_KEY, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "appendCleanupQueueEntry failed", e);
        }
    }

    /**
     * Append a one-shot history record for this run.  Kept at the tail of a
     * JSON array in prefs, capped at HISTORY_MAX so the Settings blob stays
     * bounded.  Non-fatal on JSON errors — history is advisory, not
     * load-bearing on any upload path.
     */
    private static final int HISTORY_MAX = 50;
    private static final String HISTORY_KEY = "backupHistory";
    private void appendHistoryRecord(SharedPreferences prefs, long startedAt,
                                     int uploaded, int failed, int skippedDup,
                                     long bytes, String trigger, String error) {
        try {
            String raw = prefs.getString(HISTORY_KEY, "[]");
            JSONArray arr;
            try { arr = new JSONArray(raw); } catch (JSONException e) { arr = new JSONArray(); }
            org.json.JSONObject rec = new org.json.JSONObject();
            rec.put("startedAt", startedAt);
            rec.put("finishedAt", System.currentTimeMillis());
            rec.put("uploaded", uploaded);
            rec.put("failed", failed);
            rec.put("skippedDup", skippedDup);
            rec.put("bytes", bytes);
            rec.put("trigger", trigger != null ? trigger : "scheduled");
            if (error != null) rec.put("error", error);
            arr.put(rec);
            // Drop oldest entries to keep the list bounded.  Cheap enough —
            // HISTORY_MAX is small and this only runs at end-of-run.
            while (arr.length() > HISTORY_MAX) arr.remove(0);
            prefs.edit().putString(HISTORY_KEY, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "appendHistoryRecord failed", e);
        }
    }

    /**
     * Build the per-file directory URL according to the user's organization
     * preference.  All three layouts live under `destBase`; only the subtree
     * structure differs.
     *
     * - "source" (default): <destBase>/<source-folder>/<YYYY-MM>/
     * - "year-month":       <destBase>/<YYYY>/<MM>/
     * - "flat":             <destBase>/
     *
     * `dateFmt` is the shared `yyyy-MM` formatter (UTC) used in "source" mode.
     */
    private String buildItemDirUrl(String destBase, String srcFolderRaw,
                                   long effectiveDate, SimpleDateFormat dateFmt,
                                   String orgMode) {
        if ("flat".equals(orgMode)) {
            return destBase;
        }
        if ("year-month".equals(orgMode)) {
            SimpleDateFormat y = new SimpleDateFormat("yyyy", Locale.US);
            SimpleDateFormat m = new SimpleDateFormat("MM", Locale.US);
            y.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            m.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            Date d = new Date(effectiveDate);
            return destBase + encodePathSegment(y.format(d)) + "/"
                    + encodePathSegment(m.format(d)) + "/";
        }
        // Default: mirror source folder + YYYY-MM bucket.
        String dateFolder = dateFmt.format(new Date(effectiveDate));
        return destBase + encodePath(srcFolderRaw) + "/"
                + encodePathSegment(dateFolder) + "/";
    }

    /**
     * Returns true when the current local hour is inside [startH, endH).
     * endH ≤ startH is interpreted as a window that wraps past midnight, e.g.
     * start=22, end=6 → active from 22:00–05:59.  start == end disables the
     * window (treated as "always in window") so a mis-configured UI doesn't
     * silently never back up.
     */
    private boolean isInWindow(int startH, int endH) {
        if (startH == endH) return true;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (startH < endH) return hour >= startH && hour < endH;
        return hour >= startH || hour < endH;
    }

    private boolean isHostReachable(String host, int port, int timeoutMs) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * Minimal SMB path escape.  JCIFS-NG does not decode %20 back to space —
     * so we leave spaces and special chars raw and only escape characters
     * that would structurally break URL parsing (`#`, `?`, `%`).
     */
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

    /** Encode every segment of a multi-part path, preserving `/` separators. */
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

    /** Strip leading + trailing slashes so the resulting URL has no doubles. */
    private String trimSlashes(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && (s.charAt(start) == '/' || s.charAt(start) == '\\')) start++;
        while (end > start && (s.charAt(end - 1) == '/' || s.charAt(end - 1) == '\\')) end--;
        return s.substring(start, end);
    }

    /**
     * Map a device file path to a relative folder for placement on the shelf.
     *   /storage/emulated/0/DCIM/Camera/IMG_1.jpg    -> DCIM/Camera
     *   /sdcard/Pictures/Screenshots/a.png           -> Pictures/Screenshots
     *   /storage/XXXX-XXXX/DCIM/Camera/b.jpg         -> DCIM/Camera  (SD-card volume stripped)
     *   null / unknown shape                         -> Other
     * Result never starts or ends with a slash and is safe to concatenate.
     */
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
            // /storage/<volume>/<rest> — drop the volume segment.
            int nextSlash = rel.indexOf('/', "/storage/".length());
            rel = nextSlash >= 0 ? rel.substring(nextSlash + 1) : "";
        }

        // Normalize: strip leading/trailing slashes, drop empties.
        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return rel.isEmpty() ? "Other" : rel;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Backup Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows backup progress");
            NotificationManager nm = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * Build a PendingIntent that opens MainActivity and tells the Vue router
     * to push `path`.  MainActivity reads the `open` extra in routeIntent().
     */
    private android.app.PendingIntent pendingOpenIntent(String path) {
        android.content.Intent intent = new android.content.Intent(getApplicationContext(),
            com.mrdon.server.MainActivity.class);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                      | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(com.mrdon.server.MainActivity.EXTRA_OPEN_PATH, path);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        }
        return android.app.PendingIntent.getActivity(
            getApplicationContext(), 1000, intent, flags);
    }

    @NonNull
    private ForegroundInfo createForegroundInfo(String fileName, int current, int total) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Backing up to NAS")
            .setContentText(fileName + " (" + current + "/" + total + ")")
            .setProgress(total, current, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, builder.build());
    }

    private void publishProgress(String phase, String fileName, int current, int total) {
        try {
            setProgressAsync(new Data.Builder()
                .putString("phase", phase)
                .putString("fileName", fileName == null ? "" : fileName)
                .putInt("current", current)
                .putInt("total", total)
                .build());
        } catch (Exception ignore) { /* best-effort telemetry */ }
    }

    @NonNull
    private ForegroundInfo createScanningForegroundInfo() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Scanning for new media…")
            .setContentText("Archive backup is running.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(NOTIFICATION_ID, builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, builder.build());
    }

    static class MediaItem {
        String id;
        String name;
        String path;
        String uri;
        long size;
        String type;
        long dateTaken;        // ms since epoch, 0 when unknown
        long dateModifiedMs;   // ms since epoch, 0 when unknown
    }
}
