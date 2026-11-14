package com.mrdon.server.plugins;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbFileOutputStream;

@CapacitorPlugin(name = "SmbPlugin")
public class SmbPlugin extends Plugin {

    private static final String TAG = "SmbPlugin";

    // Bounded pool so a burst of UI-triggered SMB calls can't spawn unbounded
    // threads (each jcifs thread holds non-trivial stack + socket state, which
    // OOMs low-end devices under thumbnail floods).  Core=2 to keep a warm
    // worker for interactive ops; max=8 covers thumbnail parallelism + user
    // actions; 30 s idle timeout reclaims threads when the user stops browsing.
    private static final ExecutorService EXECUTOR;
    static {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SmbPlugin-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                2, 8,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                factory);
        exec.allowCoreThreadTimeOut(true);
        EXECUTOR = exec;
    }

    private CIFSContext createContext(String username, String password) {
        try {
            Properties props = new Properties();
            // Allow SMB1 through SMB3.1.1 — older home-NAS setups sometimes can't
            // negotiate SMB2. We'll still prefer newer versions; this is just floor.
            props.setProperty("jcifs.smb.client.minVersion", "SMB1");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            props.setProperty("jcifs.smb.client.responseTimeout", "12000");
            props.setProperty("jcifs.smb.client.soTimeout", "15000");
            props.setProperty("jcifs.smb.client.connTimeout", "8000");
            props.setProperty("jcifs.smb.client.sessionTimeout", "15000");
            props.setProperty("jcifs.smb.client.dfs.disabled", "true");
            // Recovery after a server restart: don't keep cached NetBIOS
            // resolutions / pooled transports.  Stale entries kept the
            // client retrying against connections the server had already
            // dropped, so even after the NAS came back the app stayed in
            // "out of reach" until the OS cleared the socket cache.
            props.setProperty("jcifs.netbios.cachePolicy", "0");
            props.setProperty("jcifs.smb.client.transportPoolSize", "1");
            props.setProperty("jcifs.smb.client.maxRequestRetries", "2");
            // Some NAS setups require guest enumeration — without this JCIFS can
            // return an empty directory listing instead of throwing.
            props.setProperty("jcifs.smb.client.guestUsername", "guest");

            CIFSContext baseContext = new BaseContext(new PropertyConfiguration(props));

            if (username != null && !username.isEmpty()) {
                Log.i(TAG, "createContext: NTLM auth as user=" + username);
                NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator("", username, password != null ? password : "");
                return baseContext.withCredentials(auth);
            } else {
                Log.i(TAG, "createContext: guest/anonymous");
                return baseContext.withGuestCrendentials();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create CIFS context", e);
            return SingletonContext.getInstance();
        }
    }

    private String buildSmbUrl(String host, String share, String path) {
        assertSafePath(path);
        StringBuilder sb = new StringBuilder("smb://");
        sb.append(host).append("/").append(encodePathSegment(share)).append("/");
        if (path != null && !path.isEmpty()) {
            sb.append(encodePath(trimSlashes(path)));
            sb.append("/");
        }
        return sb.toString();
    }

    private String buildSmbFileUrl(String host, String share, String path) {
        assertSafePath(path);
        return "smb://" + host + "/" + encodePathSegment(share) + "/"
            + encodePath(trimSlashes(path == null ? "" : path));
    }

    /**
     * Minimal SMB path segment escape.  JCIFS-NG 2.1.10 uses its own URL
     * stream handler and does NOT decode %-encoded sequences back into
     * characters the server expects — so encoding a space produces a literal
     * `Season%202` on the wire, which doesn't match a folder named
     * `Season 2`.
     *
     * Pass raw characters through.  Only escape URL-structural chars
     * (`#`, `?`) and `%` itself (so filenames containing a literal `%`
     * still round-trip correctly).
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

    private String trimSlashes(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && (s.charAt(start) == '/' || s.charAt(start) == '\\')) start++;
        while (end > start && (s.charAt(end - 1) == '/' || s.charAt(end - 1) == '\\')) end--;
        return s.substring(start, end);
    }

    /**
     * Defense-in-depth path validation.  JS layer validates too, but never
     * trust the wire: reject traversal segments (`.`, `..`), absolute paths,
     * and NUL bytes that could confuse the SMB server or escape the share.
     * Throws IllegalArgumentException if the path is unsafe.
     */
    private void assertSafePath(String path) {
        if (path == null || path.isEmpty()) return;
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path contains NUL byte");
        }
        String trimmed = trimSlashes(path);
        if (trimmed.isEmpty()) return;
        for (String seg : trimmed.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("Path segment not allowed: '" + seg + "'");
            }
        }
    }

    /**
     * Scrub sensitive or noisy content out of an exception message before
     * surfacing it to the WebView.  Strips full `smb://` URLs (which leak
     * host/share/path/creds) and any `user=...` fragment.
     */
    private String safeErrorMessage(Throwable e) {
        if (e == null) return "";
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return e.getClass().getSimpleName();
        msg = msg.replaceAll("smb://[^ \\t\\r\\n'\\)]+", "<smb-path>");
        msg = msg.replaceAll("(?i)user(name)?\\s*=\\s*[^\\s,;]+", "user=<redacted>");
        msg = msg.replaceAll("(?i)password\\s*=\\s*[^\\s,;]+", "password=<redacted>");
        return msg;
    }

    // ==================== LIST DIRECTORY ====================

    @PluginMethod
    public void listDirectory(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            String url = buildSmbUrl(host, share, path);
            Log.i(TAG, "listDirectory: url=" + url + " user=" + (username != null && !username.isEmpty() ? username : "<guest>"));

            // Two attempts: a single stale-transport failure (typical after
            // the server restarted) recovers on the second try with a fresh
            // CIFSContext.  Auth/access errors don't retry — they'll fail
            // identically and we'd just be wasting a few seconds.
            Exception lastError = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    CIFSContext ctx = createContext(username, password);
                    SmbFile dir = new SmbFile(url, ctx);

                    boolean exists = dir.exists();
                    boolean isDir = dir.isDirectory();
                    Log.i(TAG, "listDirectory: attempt=" + attempt + " exists=" + exists + " isDir=" + isDir);

                    if (!exists) {
                        call.reject("Path not found: " + url);
                        return;
                    }
                    if (!isDir) {
                        call.reject("Path is not a directory: " + url);
                        return;
                    }

                    SmbFile[] files = dir.listFiles();
                    if (files == null) {
                        // JCIFS returns null when the server accepted the connection
                        // but refused to enumerate — typically a share-level
                        // permission issue.  Don't retry; the answer won't change.
                        Log.e(TAG, "listDirectory: listFiles() returned null (access denied or restricted enumeration)");
                        call.reject("Access denied — the server accepted the connection but refused to list " + share + ". Check the share's browse permissions.");
                        return;
                    }

                    Log.i(TAG, "listDirectory: listed " + files.length + " entries");

                    JSArray fileArray = new JSArray();
                    for (SmbFile file : files) {
                        JSObject fileObj = new JSObject();
                        String name = file.getName();
                        boolean entryIsDir = file.isDirectory();
                        if (entryIsDir && name.endsWith("/")) {
                            name = name.substring(0, name.length() - 1);
                        }
                        fileObj.put("name", name);
                        fileObj.put("isDirectory", entryIsDir);
                        fileObj.put("size", file.length());
                        fileObj.put("modified", file.lastModified());
                        fileObj.put("type", getFileType(name, entryIsDir));
                        fileArray.put(fileObj);
                    }

                    JSObject result = new JSObject();
                    result.put("files", fileArray);
                    call.resolve(result);
                    return;
                } catch (Exception e) {
                    lastError = e;
                    String msg = String.valueOf(e.getMessage()).toLowerCase();
                    boolean retryable = msg.contains("timed out") || msg.contains("timeout")
                            || msg.contains("connection") || msg.contains("transport")
                            || msg.contains("socket") || msg.contains("reset")
                            || msg.contains("broken pipe") || msg.contains("eof");
                    if (attempt < 2 && retryable) {
                        Log.w(TAG, "listDirectory transient failure (attempt " + attempt + "): " + e.getMessage() + " — retrying with fresh context");
                        try { Thread.sleep(800); } catch (InterruptedException ignore) {}
                        continue;
                    }
                    Log.e(TAG, "listDirectory failed for " + url, e);
                    String reason = e.getMessage();
                    if (reason == null) reason = e.getClass().getSimpleName();
                    call.reject("Failed to list directory: " + reason);
                    return;
                }
            }
            // Defensive — loop should always resolve/reject before exiting.
            if (lastError != null) {
                call.reject("Failed to list directory: " + lastError.getMessage());
            }
        });
    }

    // ==================== READ FILE ====================

    @PluginMethod
    public void readFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile file = new SmbFile(buildSmbFileUrl(host, share, path), ctx);
                // Bounded read: preview uses a data: URL; anything larger than
                // ~25 MB OOMs the WebView and the user can't see it anyway.
                final long MAX_INLINE_BYTES = 25L * 1024 * 1024;
                long size = 0;
                try { size = file.length(); } catch (Exception ignore) {}
                if (size > MAX_INLINE_BYTES) {
                    call.reject("File too large to preview (" + (size / (1024 * 1024)) + " MB). Download to view.");
                    return;
                }
                // readAllBytes() is API 33+; read in 8 KB chunks for compat.
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                try (InputStream is = new SmbFileInputStream(file)) {
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                }
                byte[] data = baos.toByteArray();

                JSObject result = new JSObject();
                result.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                result.put("mimeType", getMimeType(path));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "readFile failed", e);
                call.reject("Failed to read file: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== DOWNLOAD FILE ====================

    @PluginMethod
    public void downloadFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile(buildSmbFileUrl(host, share, path), ctx);
                long totalBytes = smbFile.length();
                String fileName = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;

                String savedLocation = saveToMediaStore(smbFile, fileName, totalBytes);

                JSObject result = new JSObject();
                result.put("localPath", savedLocation);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "downloadFile failed", e);
                call.reject("Failed to download file: " + safeErrorMessage(e));
            }
        });
    }

    /**
     * Save an SMB file to the correct public media collection so it shows up in
     * Gallery / Music / Files. Images → Pictures/Archive, videos → Movies/Archive,
     * audio → Music/Archive, everything else → Download/Archive.
     *
     * Uses MediaStore on API 29+ (scoped storage). Falls back to direct path
     * writes on older Android (WRITE_EXTERNAL_STORAGE is only granted there).
     */
    private String saveToMediaStore(SmbFile smbFile, String fileName, long totalBytes) throws Exception {
        String mimeType = getMimeType(fileName);
        String typeBucket = getFileType(fileName, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection;
            String relativePath;
            switch (typeBucket) {
                case "image":
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    relativePath = Environment.DIRECTORY_PICTURES + "/Archive";
                    break;
                case "video":
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    relativePath = Environment.DIRECTORY_MOVIES + "/Archive";
                    break;
                case "audio":
                    collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    relativePath = Environment.DIRECTORY_MUSIC + "/Archive";
                    break;
                default:
                    collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    relativePath = Environment.DIRECTORY_DOWNLOADS + "/Archive";
                    break;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri itemUri = getContext().getContentResolver().insert(collection, values);
            if (itemUri == null) throw new Exception("MediaStore insert returned null");

            try (InputStream is = new SmbFileInputStream(smbFile);
                 OutputStream os = getContext().getContentResolver().openOutputStream(itemUri)) {
                if (os == null) throw new Exception("Cannot open output stream for " + itemUri);
                byte[] buffer = new byte[8192];
                int bytesRead;
                long bytesWritten = 0;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    bytesWritten += bytesRead;
                    if (bytesWritten % (256 * 1024) < 8192) {
                        JSObject progress = new JSObject();
                        progress.put("bytesWritten", bytesWritten);
                        progress.put("totalBytes", totalBytes);
                        progress.put("progress", totalBytes > 0 ? (double) bytesWritten / totalBytes : 0);
                        notifyListeners("downloadProgress", progress);
                    }
                }
            }

            // Clear the pending flag so Gallery / other apps can see it.
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContext().getContentResolver().update(itemUri, done, null, null);

            return itemUri.toString();
        }

        // Pre-Android 10 path (legacy external-storage writes).
        String subdir;
        switch (typeBucket) {
            case "image": subdir = Environment.DIRECTORY_PICTURES; break;
            case "video": subdir = Environment.DIRECTORY_MOVIES; break;
            case "audio": subdir = Environment.DIRECTORY_MUSIC; break;
            default:      subdir = Environment.DIRECTORY_DOWNLOADS; break;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(subdir), "Archive");
        if (!dir.exists()) dir.mkdirs();
        File localFile = new File(dir, fileName);

        try (InputStream is = new SmbFileInputStream(smbFile);
             FileOutputStream fos = new FileOutputStream(localFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long bytesWritten = 0;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                bytesWritten += bytesRead;
                if (bytesWritten % (256 * 1024) < 8192) {
                    JSObject progress = new JSObject();
                    progress.put("bytesWritten", bytesWritten);
                    progress.put("totalBytes", totalBytes);
                    progress.put("progress", totalBytes > 0 ? (double) bytesWritten / totalBytes : 0);
                    notifyListeners("downloadProgress", progress);
                }
            }
        }

        // Ask MediaScanner to index the new file so Gallery picks it up.
        android.media.MediaScannerConnection.scanFile(
                getContext(),
                new String[]{ localFile.getAbsolutePath() },
                new String[]{ mimeType },
                null);

        return localFile.getAbsolutePath();
    }

    // ==================== UPLOAD FILE ====================

    @PluginMethod
    public void uploadFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String localUri = call.getString("localUri", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                String destUrl = buildSmbFileUrl(host, share, path);
                SmbFile destFile = new SmbFile(destUrl, ctx);

                Uri uri = Uri.parse(localUri);
                InputStream is = getContext().getContentResolver().openInputStream(uri);
                if (is == null) {
                    call.reject("Cannot read local file");
                    return;
                }

                long totalWritten = 0;
                try (InputStream in = is;
                     OutputStream os = new SmbFileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                        totalWritten += bytesRead;
                    }
                }

                // Integrity check: re-open the remote file and compare its
                // size to the bytes we streamed.  Catches silent truncation
                // (network cut, server disk full) before the caller marks
                // the file as "done."
                SmbFile verify = new SmbFile(destUrl, ctx);
                long remoteSize = verify.length();
                if (remoteSize != totalWritten) {
                    try { verify.delete(); } catch (Exception ignore) {}
                    call.reject("Upload truncated: wrote " + totalWritten
                        + " bytes but shelf has " + remoteSize + ". Please retry.");
                    return;
                }

                JSObject result = new JSObject();
                result.put("success", true);
                result.put("bytesWritten", totalWritten);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "uploadFile failed", e);
                call.reject("Failed to upload file: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== DELETE FILE ====================

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);

                // Probe the target as a file first.  If it's actually a
                // directory, JCIFS' delete() on a SmbFile whose URL lacks a
                // trailing slash fails with "cannot find the file specified"
                // on some servers — so we switch to a directory URL shape and
                // recurse.  Handles spaces / unicode via URL encoding too.
                String fileUrl = buildSmbFileUrl(host, share, path);
                SmbFile target = new SmbFile(fileUrl, ctx);
                boolean isDir = false;
                try { isDir = target.isDirectory(); } catch (Exception ignore) { /* assume file */ }

                if (isDir) {
                    String dirUrl = buildSmbUrl(host, share, path);  // has trailing slash
                    SmbFile dir = new SmbFile(dirUrl, ctx);
                    java.util.List<String> failures = new java.util.ArrayList<>();
                    recursiveDelete(dir, failures);
                    if (!failures.isEmpty()) {
                        // The top-level dir.delete() probably threw too; bubble
                        // a specific reject so the UI shows the failing paths.
                        String sample = failures.size() <= 3
                            ? String.join(", ", failures)
                            : String.join(", ", failures.subList(0, 3)) + " and " + (failures.size() - 3) + " more";
                        call.reject("Couldn't delete " + failures.size() + " item(s): " + sample);
                        return;
                    }
                } else {
                    target.delete();
                }
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "deleteFile failed for path=" + path, e);
                call.reject("Failed to delete: " + safeErrorMessage(e));
            }
        });
    }

    /**
     * Walks a directory tree deleting leaves first.  Collects per-entry
     * failures into `failures` instead of swallowing them silently — caller
     * can then report which files couldn't be removed (usually locked /
     * permission / in-use).
     */
    private void recursiveDelete(SmbFile dir, java.util.List<String> failures) throws Exception {
        if (!dir.exists()) return;
        if (dir.isDirectory()) {
            SmbFile[] children;
            try { children = dir.listFiles(); }
            catch (Exception e) { children = new SmbFile[0]; }
            if (children != null) {
                for (SmbFile child : children) {
                    try { recursiveDelete(child, failures); }
                    catch (Exception e) {
                        failures.add(child.getName());
                        Log.w(TAG, "recursiveDelete: failed child " + child.getName(), e);
                    }
                }
            }
        }
        try { dir.delete(); }
        catch (Exception e) {
            failures.add(dir.getName());
            throw e;
        }
    }

    // ==================== RECURSIVE SEARCH ====================
    // Iterative BFS walker with cancellation + streamed results.

    /** Live task tracker — id -> cancellation flag. */
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> searchTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Shared executor for search jobs so parallel searches don't each spawn threads. */
    private final java.util.concurrent.ExecutorService searchExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    /**
     * Start a recursive search. Returns immediately with { taskId }.  Matches
     * are streamed over the `searchResults` listener event in batches; a final
     * `searchComplete` event fires when the walk finishes or is cancelled.
     */
    @PluginMethod
    public void recursiveSearch(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String startPath = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");
        String query = call.getString("query", "");
        // Optional filter — comma-separated bucket names: "image,video"
        String typesCsv = call.getString("types", "");
        // Optional client-provided task id: lets JS register listeners *before*
        // calling, eliminating a race where the first result batch arrives
        // before JS has stored the id as "active."
        String clientTaskId = call.getString("taskId", "");

        // Empty query means "match every file" — used by folder-download to
        // enumerate a subtree.  The `files` filter still applies.
        final String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        final boolean matchAll = needle.isEmpty();

        final String taskId = (clientTaskId != null && !clientTaskId.isEmpty())
            ? clientTaskId
            : "s" + Long.toHexString(System.nanoTime());
        java.util.concurrent.atomic.AtomicBoolean cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        searchTasks.put(taskId, cancelFlag);

        JSObject ack = new JSObject();
        ack.put("taskId", taskId);
        call.resolve(ack);

        final java.util.Set<String> typeFilter = new java.util.HashSet<>();
        if (typesCsv != null && !typesCsv.isEmpty()) {
            for (String t : typesCsv.split(",")) {
                String tt = t.trim().toLowerCase(java.util.Locale.US);
                if (!tt.isEmpty()) typeFilter.add(tt);
            }
        }

        Log.i(TAG, "recursiveSearch: task=" + taskId + " needle='" + needle
            + "' path='" + startPath + "' typeFilter=" + typeFilter);

        searchExecutor.submit(() -> {
            long total = 0;
            boolean erroredOut = false;
            String errorMsg = null;
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile start = new SmbFile(buildSmbUrl(host, share, startPath), ctx);

                // BFS via a bounded deque; depth-limit 20 so pathological
                // trees don't run us into the ground.
                final int MAX_DEPTH = 20;
                java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
                queue.addLast(new Node(start, 0));

                JSArray pendingBatch = new JSArray();
                long lastFlush = System.currentTimeMillis();

                while (!queue.isEmpty()) {
                    if (cancelFlag.get()) break;
                    Node n = queue.pollFirst();
                    if (n.depth > MAX_DEPTH) continue;

                    SmbFile[] children;
                    try {
                        children = n.dir.listFiles();
                    } catch (Exception e) {
                        Log.w(TAG, "recursiveSearch: listFiles failed for " + n.dir.getURL() + " — " + e.getMessage());
                        continue;
                    }
                    Log.d(TAG, "recursiveSearch: " + (children == null ? 0 : children.length)
                        + " children at depth=" + n.depth + " in " + n.dir.getURL());
                    if (children == null) continue;

                    for (SmbFile child : children) {
                        if (cancelFlag.get()) break;
                        String name;
                        boolean isDir;
                        long size = 0, modified = 0;
                        try {
                            name = child.getName();
                            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                            isDir = child.isDirectory();
                            if (!isDir) { size = child.length(); modified = child.lastModified(); }
                        } catch (Exception e) { continue; }

                        String lowerName = name.toLowerCase(java.util.Locale.US);
                        boolean nameMatches = matchAll || lowerName.contains(needle);
                        String typeBucket = isDir ? "folder" : getFileType(name, false);
                        boolean typeOk = typeFilter.isEmpty() || typeFilter.contains(typeBucket);

                        if (nameMatches && typeOk) {
                            JSObject match = new JSObject();
                            match.put("name", name);
                            // Reconstruct the shelf-relative path by stripping
                            // "smb://host/share/" off the URL.
                            match.put("path", shelfRelativePath(host, share, child.getURL()));
                            match.put("isDirectory", isDir);
                            match.put("size", size);
                            match.put("modified", modified);
                            match.put("type", typeBucket);
                            pendingBatch.put(match);
                            total++;
                        }

                        if (isDir) {
                            queue.addLast(new Node(child, n.depth + 1));
                        }
                    }

                    // Flush batch: every 50 matches or every ~1.5 s, whichever comes first.
                    long now = System.currentTimeMillis();
                    if (pendingBatch.length() >= 50 || (pendingBatch.length() > 0 && now - lastFlush > 1500)) {
                        emitSearchResults(taskId, pendingBatch, total);
                        pendingBatch = new JSArray();
                        lastFlush = now;
                    }
                }

                if (pendingBatch.length() > 0) {
                    emitSearchResults(taskId, pendingBatch, total);
                }
            } catch (Exception e) {
                Log.e(TAG, "recursiveSearch failed", e);
                erroredOut = true;
                errorMsg = safeErrorMessage(e);
            } finally {
                JSObject done = new JSObject();
                done.put("taskId", taskId);
                done.put("totalMatches", total);
                done.put("canceled", cancelFlag.get());
                if (erroredOut) done.put("error", errorMsg == null ? "" : errorMsg);
                notifyListeners("searchComplete", done);
                searchTasks.remove(taskId);
            }
        });
    }

    @PluginMethod
    public void cancelSearch(PluginCall call) {
        String taskId = call.getString("taskId", "");
        if (taskId != null && !taskId.isEmpty()) {
            java.util.concurrent.atomic.AtomicBoolean flag = searchTasks.get(taskId);
            if (flag != null) flag.set(true);
        }
        call.resolve();
    }

    private void emitSearchResults(String taskId, JSArray matches, long totalSoFar) {
        JSObject payload = new JSObject();
        payload.put("taskId", taskId);
        payload.put("matches", matches);
        payload.put("total", totalSoFar);
        notifyListeners("searchResults", payload);
    }

    /** Decode the SmbFile URL back to a shelf-relative path (no leading `/`). */
    private String shelfRelativePath(String host, String share, java.net.URL url) {
        if (url == null) return "";
        String full = url.toString();  // e.g. smb://host/share/foo/bar/baz.jpg
        String prefix = "smb://" + host + "/" + share + "/";
        if (full.startsWith(prefix)) full = full.substring(prefix.length());
        // Strip trailing slash from directories and percent-decode path segments.
        while (full.endsWith("/")) full = full.substring(0, full.length() - 1);
        try { return java.net.URLDecoder.decode(full, "UTF-8"); }
        catch (Exception e) { return full; }
    }

    private static class Node {
        final SmbFile dir;
        final int depth;
        Node(SmbFile d, int depth) { this.dir = d; this.depth = depth; }
    }

    // ==================== DUPLICATE FINDER ====================

    /**
     * Active duplicate-scan jobs, keyed by client-supplied taskId.  Same
     * cancellation shape as the search executor.
     */
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> duplicateTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Skip files larger than this when hashing (default cap, overridable). */
    private static final long DEFAULT_DUP_MAX_BYTES = 512L * 1024L * 1024L;

    /**
     * Walks the subtree under `path`, first grouping by exact size (cheap),
     * then MD5-hashing only files whose size has a duplicate candidate.
     * Emits one `dupScanProgress` payload per walker tick (phase='walking' or
     * 'hashing'), a `dupScanGroup` payload per confirmed duplicate group, and
     * a terminal `dupScanDone` payload when finished or cancelled.
     */
    @PluginMethod
    public void findDuplicates(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String startPath = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");
        String clientTaskId = call.getString("taskId", "");
        long maxBytes = call.getLong("maxBytes", DEFAULT_DUP_MAX_BYTES);
        if (maxBytes <= 0) maxBytes = DEFAULT_DUP_MAX_BYTES;
        long minBytes = call.getLong("minBytes", 1L);
        if (minBytes < 1) minBytes = 1;
        final long effMaxBytes = maxBytes;
        final long effMinBytes = minBytes;

        final String taskId = (clientTaskId != null && !clientTaskId.isEmpty())
            ? clientTaskId
            : "d" + Long.toHexString(System.nanoTime());
        java.util.concurrent.atomic.AtomicBoolean cancelFlag =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        duplicateTasks.put(taskId, cancelFlag);

        JSObject ack = new JSObject();
        ack.put("taskId", taskId);
        call.resolve(ack);

        searchExecutor.submit(() -> {
            long walked = 0;
            long groupsFound = 0;
            long bytesWasted = 0;
            boolean erroredOut = false;
            String errorMsg = null;
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile start = new SmbFile(buildSmbUrl(host, share, startPath), ctx);

                // Phase 1 — walk + group by exact size.  A file that is unique
                // by size cannot be a duplicate of anything, so we avoid
                // hashing it entirely.  This cuts scan time on a typical shelf
                // by an order of magnitude — most files have unique sizes.
                final int MAX_DEPTH = 20;
                java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
                queue.addLast(new Node(start, 0));

                // Map of size -> list of candidates with that size.
                java.util.Map<Long, java.util.List<DupCandidate>> bySize = new java.util.HashMap<>();

                long walkProgressLastEmit = System.currentTimeMillis();

                while (!queue.isEmpty()) {
                    if (cancelFlag.get()) break;
                    Node n = queue.pollFirst();
                    if (n.depth > MAX_DEPTH) continue;

                    SmbFile[] children;
                    try { children = n.dir.listFiles(); }
                    catch (Exception e) {
                        Log.w(TAG, "findDuplicates: list fail " + n.dir.getURL() + " — " + e.getMessage());
                        continue;
                    }
                    if (children == null) continue;

                    for (SmbFile child : children) {
                        if (cancelFlag.get()) break;
                        try {
                            if (child.isDirectory()) {
                                queue.addLast(new Node(child, n.depth + 1));
                                continue;
                            }
                            long sz = child.length();
                            if (sz < effMinBytes || sz > effMaxBytes) continue;
                            String relPath = shelfRelativePath(host, share, child.getURL());
                            String nm = child.getName();
                            if (nm.endsWith("/")) nm = nm.substring(0, nm.length() - 1);
                            bySize.computeIfAbsent(sz, k -> new java.util.ArrayList<>())
                                  .add(new DupCandidate(nm, relPath, sz, child.lastModified(), child.getURL().toString()));
                            walked++;
                        } catch (Exception ignore) {
                            // Skip unreadable children; they can't dupe anyway.
                        }
                    }

                    long now = System.currentTimeMillis();
                    if (now - walkProgressLastEmit > 800) {
                        emitDupProgress(taskId, "walking", walked, groupsFound, bytesWasted);
                        walkProgressLastEmit = now;
                    }
                }

                // Phase 2 — for each size-bucket with ≥ 2 entries, hash each
                // file and group by hash.  A bucket with 5 same-size files
                // may produce multiple distinct hash groups, so we group per
                // bucket and emit each resulting cluster individually.
                int bucketsToHash = 0;
                int filesToHash = 0;
                for (java.util.List<DupCandidate> bucket : bySize.values()) {
                    if (bucket.size() >= 2) { bucketsToHash++; filesToHash += bucket.size(); }
                }
                int hashedSoFar = 0;

                for (java.util.Map.Entry<Long, java.util.List<DupCandidate>> ent : bySize.entrySet()) {
                    if (cancelFlag.get()) break;
                    java.util.List<DupCandidate> bucket = ent.getValue();
                    if (bucket.size() < 2) continue;

                    // Hash every candidate in this bucket.
                    java.util.Map<String, java.util.List<DupCandidate>> byHash = new java.util.HashMap<>();
                    for (DupCandidate c : bucket) {
                        if (cancelFlag.get()) break;
                        try {
                            String h = md5HashOfSmb(ctx, c.url, cancelFlag);
                            if (h == null) continue;  // cancelled or unreadable
                            byHash.computeIfAbsent(h, k -> new java.util.ArrayList<>()).add(c);
                        } catch (Exception e) {
                            Log.w(TAG, "findDuplicates: hash fail " + c.url + " — " + e.getMessage());
                        }
                        hashedSoFar++;
                        // Throttle hashing progress so we don't spam the bridge
                        // on fast connections — every ~400ms is plenty for UX.
                        long now = System.currentTimeMillis();
                        if (now - walkProgressLastEmit > 400) {
                            emitDupProgress(taskId, "hashing",
                                (long) hashedSoFar * 1000L + filesToHash,
                                groupsFound, bytesWasted);
                            walkProgressLastEmit = now;
                        }
                    }

                    for (java.util.Map.Entry<String, java.util.List<DupCandidate>> g : byHash.entrySet()) {
                        if (g.getValue().size() < 2) continue;
                        groupsFound++;
                        long size = g.getValue().get(0).size;
                        bytesWasted += (long) (g.getValue().size() - 1) * size;
                        emitDupGroup(taskId, g.getKey(), size, g.getValue());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "findDuplicates failed", e);
                erroredOut = true;
                errorMsg = safeErrorMessage(e);
            } finally {
                JSObject done = new JSObject();
                done.put("taskId", taskId);
                done.put("groupsFound", groupsFound);
                done.put("bytesWasted", bytesWasted);
                done.put("walked", walked);
                done.put("canceled", cancelFlag.get());
                if (erroredOut) done.put("error", errorMsg == null ? "" : errorMsg);
                notifyListeners("dupScanDone", done);
                duplicateTasks.remove(taskId);
            }
        });
    }

    @PluginMethod
    public void cancelDuplicateScan(PluginCall call) {
        String taskId = call.getString("taskId", "");
        if (taskId != null && !taskId.isEmpty()) {
            java.util.concurrent.atomic.AtomicBoolean flag = duplicateTasks.get(taskId);
            if (flag != null) flag.set(true);
        }
        call.resolve();
    }

    private void emitDupProgress(String taskId, String phase, long walked,
                                 long groupsFound, long bytesWasted) {
        JSObject p = new JSObject();
        p.put("taskId", taskId);
        p.put("phase", phase);
        p.put("walked", walked);
        p.put("groupsFound", groupsFound);
        p.put("bytesWasted", bytesWasted);
        notifyListeners("dupScanProgress", p);
    }

    private void emitDupGroup(String taskId, String hash, long size,
                              java.util.List<DupCandidate> files) {
        JSObject p = new JSObject();
        p.put("taskId", taskId);
        p.put("hash", hash);
        p.put("size", size);
        JSArray arr = new JSArray();
        for (DupCandidate c : files) {
            JSObject o = new JSObject();
            o.put("name", c.name);
            o.put("path", c.path);
            o.put("size", c.size);
            o.put("modified", c.modified);
            arr.put(o);
        }
        p.put("files", arr);
        notifyListeners("dupScanGroup", p);
    }

    /**
     * Streaming MD5 of an SMB file.  Returns null if the cancel flag trips
     * mid-hash so the caller knows not to record the partial value.  Buffer
     * sized to match what SMB2 round-trips comfortably.
     */
    private String md5HashOfSmb(CIFSContext ctx, String url,
                                java.util.concurrent.atomic.AtomicBoolean cancelFlag) throws Exception {
        SmbFile f = new SmbFile(url, ctx);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] buf = new byte[64 * 1024];
        try (InputStream is = new SmbFileInputStream(f)) {
            int n;
            while ((n = is.read(buf)) != -1) {
                if (cancelFlag.get()) return null;
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static class DupCandidate {
        final String name;
        final String path;
        final long size;
        final long modified;
        final String url;
        DupCandidate(String name, String path, long size, long modified, String url) {
            this.name = name; this.path = path; this.size = size;
            this.modified = modified; this.url = url;
        }
    }

    // ==================== CREATE DIRECTORY ====================

    @PluginMethod
    public void createDirectory(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile dir = new SmbFile(buildSmbUrl(host, share, path), ctx);
                dir.mkdirs();
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "createDirectory failed", e);
                call.reject("Failed to create directory: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== RENAME ====================

    @PluginMethod
    public void rename(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String oldPath = call.getString("oldPath", "");
        String newPath = call.getString("newPath", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile oldFile = new SmbFile(buildSmbFileUrl(host, share, oldPath), ctx);
                SmbFile newFile = new SmbFile(buildSmbFileUrl(host, share, newPath), ctx);
                oldFile.renameTo(newFile);
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "rename failed", e);
                call.reject("Failed to rename: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== COPY FILE ====================

    @PluginMethod
    public void copyFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String sourcePath = call.getString("sourcePath", "");
        String destPath = call.getString("destPath", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile src = new SmbFile(buildSmbFileUrl(host, share, sourcePath), ctx);
                SmbFile dst = new SmbFile(buildSmbFileUrl(host, share, destPath), ctx);

                try (InputStream is = new SmbFileInputStream(src);
                     OutputStream os = new SmbFileOutputStream(dst)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "copyFile failed", e);
                call.reject("Failed to copy: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== MOVE FILE ====================

    @PluginMethod
    public void moveFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String sourcePath = call.getString("sourcePath", "");
        String destPath = call.getString("destPath", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile src = new SmbFile(buildSmbFileUrl(host, share, sourcePath), ctx);
                SmbFile dst = new SmbFile(buildSmbFileUrl(host, share, destPath), ctx);

                // Try rename first (same share, fast)
                try {
                    src.renameTo(dst);
                } catch (Exception e) {
                    // Fallback: copy then delete
                    try (InputStream is = new SmbFileInputStream(src);
                         OutputStream os = new SmbFileOutputStream(dst)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                    src.delete();
                }

                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "moveFile failed", e);
                call.reject("Failed to move: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== GET DIRECTORY SIZE ====================

    @PluginMethod
    public void getDirectorySize(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                String url = buildSmbUrl(host, share, path);
                SmbFile dir = new SmbFile(url, ctx);
                long[] result = calculateDirSize(dir);

                JSObject res = new JSObject();
                res.put("size", result[0]);
                res.put("fileCount", (int) result[1]);
                call.resolve(res);
            } catch (Exception e) {
                Log.e(TAG, "getDirectorySize failed", e);
                call.reject("Failed to calculate size: " + safeErrorMessage(e));
            }
        });
    }

    /**
     * Return the direct children of `path` with recursive-total size + file count
     * per entry.  Feeds the storage-breakdown chart — a single call enumerates
     * the target directory once, then walks each subtree to sum bytes.  Files
     * at the target level are aggregated under the bucket "(files)" so tiny
     * top-level files don't drown the visualization.
     */
    @PluginMethod
    public void getFolderBreakdown(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                String url = buildSmbUrl(host, share, path);
                SmbFile dir = new SmbFile(url, ctx);
                SmbFile[] entries = dir.listFiles();
                com.getcapacitor.JSArray items = new com.getcapacitor.JSArray();
                long filesHereBytes = 0;
                int filesHereCount = 0;
                if (entries != null) {
                    for (SmbFile entry : entries) {
                        try {
                            if (entry.isDirectory()) {
                                long[] sub = calculateDirSize(entry);
                                JSObject it = new JSObject();
                                String nm = entry.getName();
                                if (nm.endsWith("/")) nm = nm.substring(0, nm.length() - 1);
                                it.put("name", nm);
                                it.put("isDirectory", true);
                                it.put("size", sub[0]);
                                it.put("fileCount", (int) sub[1]);
                                items.put(it);
                            } else {
                                filesHereBytes += entry.length();
                                filesHereCount++;
                            }
                        } catch (Exception ignore) {
                            // Skip individual entries we can't stat; a single
                            // bad file shouldn't blow up the whole listing.
                        }
                    }
                }
                // Aggregate bucket for files sitting at the target path.
                if (filesHereCount > 0) {
                    JSObject bucket = new JSObject();
                    bucket.put("name", "(files)");
                    bucket.put("isDirectory", false);
                    bucket.put("size", filesHereBytes);
                    bucket.put("fileCount", filesHereCount);
                    items.put(bucket);
                }
                JSObject res = new JSObject();
                res.put("items", items);
                call.resolve(res);
            } catch (Exception e) {
                Log.e(TAG, "getFolderBreakdown failed", e);
                call.reject("Failed to read breakdown: " + safeErrorMessage(e));
            }
        });
    }

    private long[] calculateDirSize(SmbFile dir) throws Exception {
        long totalSize = 0;
        long fileCount = 0;
        SmbFile[] files = dir.listFiles();
        if (files != null) {
            for (SmbFile f : files) {
                if (f.isDirectory()) {
                    long[] sub = calculateDirSize(f);
                    totalSize += sub[0];
                    fileCount += sub[1];
                } else {
                    totalSize += f.length();
                    fileCount++;
                }
            }
        }
        return new long[]{totalSize, fileCount};
    }

    // ==================== GET THUMBNAIL ====================

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");
        int maxSize = call.getInt("maxSize", 192);
        // JS passes mtime so we can build a cache key that invalidates when
        // the remote file is overwritten — avoids showing stale thumbs.
        Long mtimeHint = call.getLong("mtime");
        final long mtime = mtimeHint == null ? 0L : mtimeHint;

        EXECUTOR.execute(() -> {
            try {
                // -------- Cache lookup --------
                File thumbsDir = new File(getContext().getCacheDir(), "thumbs");
                if (!thumbsDir.exists()) thumbsDir.mkdirs();
                String cacheKey = thumbCacheKey(host, share, path, mtime, maxSize);
                File cacheFile = new File(thumbsDir, cacheKey + ".jpg");
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    // Touch for LRU.
                    cacheFile.setLastModified(System.currentTimeMillis());
                    JSObject result = new JSObject();
                    result.put("uri", Uri.fromFile(cacheFile).toString());
                    result.put("hit", true);
                    call.resolve(result);
                    return;
                }

                // -------- Fetch --------
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile(buildSmbFileUrl(host, share, path), ctx);
                long fileSize;
                try { fileSize = smbFile.length(); } catch (Exception e) { fileSize = -1; }

                String ext = "";
                int dot = path.lastIndexOf('.');
                if (dot >= 0) ext = path.substring(dot + 1).toLowerCase();

                byte[] imageBytes = null;

                // Fast path: JPEG EXIF embedded thumbnail.  Most camera photos
                // have one (~5–20 KB).  Read just the first 128 KB.
                if (ext.equals("jpg") || ext.equals("jpeg")) {
                    byte[] header = readPrefix(smbFile, 128 * 1024);
                    imageBytes = extractExifThumbnail(header);
                }

                // Fallback: full decode for small non-JPEG (PNG/WebP/BMP) or
                // JPEG without an embedded thumb.  Hard cap at 2 MB — past
                // that, return no thumb and the UI falls back to the type
                // icon.  HEIC isn't decodable by BitmapFactory so those
                // always fall back too.
                boolean decodable = ext.equals("jpg") || ext.equals("jpeg")
                        || ext.equals("png") || ext.equals("webp") || ext.equals("bmp")
                        || ext.equals("gif");
                if (imageBytes == null && decodable && fileSize > 0 && fileSize <= 2L * 1024 * 1024) {
                    imageBytes = readPrefix(smbFile, (int) fileSize);
                }

                if (imageBytes == null) {
                    JSObject result = new JSObject();
                    result.put("uri", "");
                    result.put("hit", false);
                    call.resolve(result);
                    return;
                }

                // -------- Decode + downscale --------
                // Use inSampleSize to avoid decoding the full bitmap into RAM
                // for a large source.
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
                int sample = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxSize * 2);

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap src = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, opts);
                if (src == null) {
                    JSObject result = new JSObject();
                    result.put("uri", "");
                    result.put("hit", false);
                    call.resolve(result);
                    return;
                }
                Bitmap thumb = scaleDown(src, maxSize);

                // -------- Persist + respond --------
                File tmp = new File(thumbsDir, cacheKey + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    thumb.compress(Bitmap.CompressFormat.JPEG, 82, fos);
                }
                // Atomic rename so we never read a partial file.
                if (!tmp.renameTo(cacheFile)) {
                    tmp.delete();
                    throw new IOException("could not finalize thumb cache file");
                }
                if (thumb != src) src.recycle();
                thumb.recycle();

                JSObject result = new JSObject();
                result.put("uri", Uri.fromFile(cacheFile).toString());
                result.put("hit", false);
                call.resolve(result);

                // Opportunistic LRU prune: ~1 in every 20 writes touches disk.
                if ((System.nanoTime() & 0x1F) == 0) pruneThumbCache(thumbsDir, 200L * 1024 * 1024);
            } catch (Exception e) {
                Log.e(TAG, "getThumbnail failed", e);
                call.reject("Failed to get thumbnail: " + safeErrorMessage(e));
            }
        });
    }

    /** Read up to `max` bytes from the start of an SMB file. */
    private byte[] readPrefix(SmbFile file, int max) throws IOException {
        byte[] buf = new byte[max];
        int total = 0;
        try (InputStream is = new SmbFileInputStream(file)) {
            int n;
            while (total < max && (n = is.read(buf, total, max - total)) != -1) total += n;
        }
        if (total == max) return buf;
        byte[] out = new byte[total];
        System.arraycopy(buf, 0, out, 0, total);
        return out;
    }

    /**
     * Extract the embedded thumbnail from JPEG EXIF, if present.
     * Returns null if there isn't one or the data isn't valid JPEG.
     */
    private byte[] extractExifThumbnail(byte[] header) {
        try {
            androidx.exifinterface.media.ExifInterface exif =
                new androidx.exifinterface.media.ExifInterface(new java.io.ByteArrayInputStream(header));
            byte[] thumb = exif.getThumbnailBytes();
            return (thumb != null && thumb.length > 0) ? thumb : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int calcInSampleSize(int srcW, int srcH, int targetEdge) {
        int sample = 1;
        int half = Math.min(srcW, srcH) / 2;
        while (half / sample >= targetEdge) sample *= 2;
        return Math.max(1, sample);
    }

    private Bitmap scaleDown(Bitmap src, int targetEdge) {
        int w = src.getWidth(), h = src.getHeight();
        if (Math.max(w, h) <= targetEdge) return src;
        float ratio = (float) targetEdge / Math.max(w, h);
        int dstW = Math.max(1, Math.round(w * ratio));
        int dstH = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, dstW, dstH, true);
    }

    /** Stable cache key covering everything that affects the thumb bytes. */
    private String thumbCacheKey(String host, String share, String path, long mtime, int size) {
        String raw = host + "|" + share + "|" + path + "|" + mtime + "|" + size;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /** LRU-evict the thumb cache until total size is under `capBytes`. */
    private void pruneThumbCache(File dir, long capBytes) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= capBytes) return;
            java.util.Arrays.sort(files, (a, b) ->
                Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= capBytes) break;
                long sz = f.length();
                if (f.delete()) total -= sz;
            }
        } catch (Exception ignore) {}
    }

    // ==================== GET STREAM URL ====================

    @PluginMethod
    public void getStreamUrl(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile(buildSmbFileUrl(host, share, path), ctx);

                // Register with the stream proxy so the SMB file is reachable
                // by id.  Two delivery paths are available for the same id:
                //   (1) WebView's shouldInterceptRequest hook at
                //       https://localhost/_smb_stream/<id> — same origin as
                //       the page, so no mixed-content / PNA block.
                //   (2) A 127.0.0.1 HTTP listener as a fallback for external
                //       callers (diagnostic / future use).
                // JS uses (1); we still ensureStarted() so the fallback is
                // available if anything pokes the http:// URL directly.
                com.mrdon.server.backup.SmbStreamServer server =
                        com.mrdon.server.backup.SmbStreamServer.getInstance();
                server.ensureStarted();
                String mime = getMimeType(path);
                String id = server.register(smbFile, mime);
                String url = "/_smb_stream/" + id;

                JSObject result = new JSObject();
                result.put("localPath", url);
                result.put("streamId", id);
                result.put("mimeType", mime);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getStreamUrl failed", e);
                call.reject("Failed to stream: " + safeErrorMessage(e));
            }
        });
    }

    /** Called by the UI when the preview is closed so we can drop the registration. */
    @PluginMethod
    public void releaseStream(PluginCall call) {
        String id = call.getString("streamId", "");
        if (id != null && !id.isEmpty()) {
            com.mrdon.server.backup.SmbStreamServer.getInstance().deregister(id);
        }
        call.resolve();
    }

    // ==================== SHARE FILE ====================

    @PluginMethod
    public void shareFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile(buildSmbFileUrl(host, share, path), ctx);

                // Download to share cache
                File cacheDir = new File(getContext().getCacheDir(), "share_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                String fileName = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
                File localFile = new File(cacheDir, fileName);

                try (InputStream is = new SmbFileInputStream(smbFile);
                     FileOutputStream fos = new FileOutputStream(localFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }

                Uri contentUri = FileProvider.getUriForFile(getContext(),
                        getContext().getPackageName() + ".fileprovider", localFile);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(getMimeType(path));
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Intent chooser = Intent.createChooser(shareIntent, "Share via");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(chooser);

                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "shareFile failed", e);
                call.reject("Failed to share: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== GET SHARE INFO ====================

    @PluginMethod
    public void getShareInfo(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        EXECUTOR.execute(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile("smb://" + host + "/" + share + "/", ctx);
                long freeSpace = smbFile.getDiskFreeSpace();

                JSObject result = new JSObject();
                result.put("totalSpace", freeSpace);
                result.put("freeSpace", freeSpace);
                result.put("usedSpace", 0);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getShareInfo failed", e);
                call.reject("Failed to get share info: " + safeErrorMessage(e));
            }
        });
    }

    // ==================== PING ====================

    @PluginMethod
    public void ping(PluginCall call) {
        String host = call.getString("host");

        EXECUTOR.execute(() -> {
            try {
                long start = System.currentTimeMillis();
                InetAddress address = InetAddress.getByName(host);
                boolean reachable = address.isReachable(5000);
                long responseTime = System.currentTimeMillis() - start;

                JSObject result = new JSObject();
                result.put("reachable", reachable);
                result.put("responseTime", reachable ? responseTime : -1);
                call.resolve(result);
            } catch (Exception e) {
                JSObject result = new JSObject();
                result.put("reachable", false);
                result.put("responseTime", -1);
                call.resolve(result);
            }
        });
    }

    // ==================== HELPERS ====================

    private String getFileType(String name, boolean isDirectory) {
        if (isDirectory) return "folder";
        String ext = "";
        int dotIndex = name.lastIndexOf(".");
        if (dotIndex > 0) ext = name.substring(dotIndex + 1).toLowerCase();

        switch (ext) {
            case "mp4": case "mkv": case "avi": case "mov": case "wmv":
            case "flv": case "webm": case "m4v": case "ts": case "mts": case "3gp":
                return "video";
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "svg": case "ico": case "tiff": case "raw": case "heic": case "heif":
                return "image";
            case "mp3": case "wav": case "flac": case "aac": case "ogg":
            case "wma": case "m4a": case "opus": case "aiff":
                return "audio";
            case "pdf": case "doc": case "docx": case "txt": case "md":
            case "rtf": case "xls": case "xlsx": case "ppt": case "pptx": case "csv":
                return "document";
            case "zip": case "rar": case "7z": case "tar": case "gz":
            case "bz2": case "xz": case "iso":
                return "archive";
            default:
                return "unknown";
        }
    }

    private String getMimeType(String path) {
        String ext = "";
        int dotIndex = path.lastIndexOf(".");
        if (dotIndex > 0) ext = path.substring(dotIndex + 1).toLowerCase();

        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "heic": case "heif": return "image/heic";
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "3gp": return "video/3gpp";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "aac": return "audio/aac";
            case "ogg": return "audio/ogg";
            case "m4a": return "audio/mp4";
            case "opus": return "audio/opus";
            case "aiff": case "aif": return "audio/aiff";
            case "wma": return "audio/x-ms-wma";
            case "mpg": case "mpeg": return "video/mpeg";
            case "m4v": return "video/mp4";
            case "ts": case "mts": return "video/mp2t";
            case "flv": return "video/x-flv";
            case "wmv": return "video/x-ms-wmv";
            case "pdf": return "application/pdf";
            case "txt": case "md": return "text/plain";
            case "json": return "application/json";
            case "html": return "text/html";
            case "xml": return "text/xml";
            case "zip": return "application/zip";
            case "rar": return "application/x-rar-compressed";
            case "7z": return "application/x-7z-compressed";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default: return "application/octet-stream";
        }
    }
}
