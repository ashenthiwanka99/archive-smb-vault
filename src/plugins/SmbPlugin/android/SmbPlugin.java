package com.mrdon.server.plugins;

import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Properties;

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

    private CIFSContext createContext(String username, String password) {
        try {
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.minVersion", "SMB202");
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
            props.setProperty("jcifs.smb.client.responseTimeout", "10000");
            props.setProperty("jcifs.smb.client.soTimeout", "15000");
            props.setProperty("jcifs.smb.client.dfs.disabled", "true");

            CIFSContext baseContext = new BaseContext(new PropertyConfiguration(props));

            if (username != null && !username.isEmpty()) {
                NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator("", username, password != null ? password : "");
                return baseContext.withCredentials(auth);
            } else {
                return baseContext.withGuestCrendentials();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create CIFS context", e);
            return SingletonContext.getInstance();
        }
    }

    private String buildSmbUrl(String host, String share, String path) {
        StringBuilder sb = new StringBuilder("smb://");
        sb.append(host).append("/").append(share).append("/");
        if (path != null && !path.isEmpty()) {
            sb.append(path);
            if (!path.endsWith("/")) sb.append("/");
        }
        return sb.toString();
    }

    private String buildSmbFileUrl(String host, String share, String path) {
        return "smb://" + host + "/" + share + "/" + path;
    }

    // ==================== LIST DIRECTORY ====================

    @PluginMethod
    public void listDirectory(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                String url = buildSmbUrl(host, share, path);
                SmbFile dir = new SmbFile(url, ctx);
                SmbFile[] files = dir.listFiles();
                JSArray fileArray = new JSArray();

                if (files != null) {
                    for (SmbFile file : files) {
                        JSObject fileObj = new JSObject();
                        String name = file.getName();
                        boolean isDir = file.isDirectory();
                        if (isDir && name.endsWith("/")) {
                            name = name.substring(0, name.length() - 1);
                        }
                        fileObj.put("name", name);
                        fileObj.put("isDirectory", isDir);
                        fileObj.put("size", file.length());
                        fileObj.put("modified", file.lastModified());
                        fileObj.put("type", getFileType(name, isDir));
                        fileArray.put(fileObj);
                    }
                }

                JSObject result = new JSObject();
                result.put("files", fileArray);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "listDirectory failed", e);
                call.reject("Failed to list directory: " + e.getMessage());
            }
        }).start();
    }

    // ==================== READ FILE ====================

    @PluginMethod
    public void readFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile file = new SmbFile(buildSmbFileUrl(host, share, path), ctx);
                InputStream is = new SmbFileInputStream(file);
                byte[] data = is.readAllBytes();
                is.close();

                JSObject result = new JSObject();
                result.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                result.put("mimeType", getMimeType(path));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "readFile failed", e);
                call.reject("Failed to read file: " + e.getMessage());
            }
        }).start();
    }

    // ==================== DOWNLOAD FILE ====================

    @PluginMethod
    public void downloadFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile(buildSmbFileUrl(host, share, path), ctx);
                long totalBytes = smbFile.length();

                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                String fileName = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
                File localFile = new File(downloadsDir, fileName);

                InputStream is = new SmbFileInputStream(smbFile);
                FileOutputStream fos = new FileOutputStream(localFile);

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
                fos.close();
                is.close();

                JSObject result = new JSObject();
                result.put("localPath", localFile.getAbsolutePath());
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "downloadFile failed", e);
                call.reject("Failed to download file: " + e.getMessage());
            }
        }).start();
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

        new Thread(() -> {
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

                OutputStream os = new SmbFileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalWritten = 0;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    totalWritten += bytesRead;
                }
                os.close();
                is.close();

                JSObject result = new JSObject();
                result.put("success", true);
                result.put("bytesWritten", totalWritten);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "uploadFile failed", e);
                call.reject("Failed to upload file: " + e.getMessage());
            }
        }).start();
    }

    // ==================== DELETE FILE ====================

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile file = new SmbFile(buildSmbFileUrl(host, share, path), ctx);
                file.delete();
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "deleteFile failed", e);
                call.reject("Failed to delete: " + e.getMessage());
            }
        }).start();
    }

    // ==================== CREATE DIRECTORY ====================

    @PluginMethod
    public void createDirectory(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile dir = new SmbFile(buildSmbUrl(host, share, path), ctx);
                dir.mkdirs();
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "createDirectory failed", e);
                call.reject("Failed to create directory: " + e.getMessage());
            }
        }).start();
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

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile oldFile = new SmbFile(buildSmbFileUrl(host, share, oldPath), ctx);
                SmbFile newFile = new SmbFile(buildSmbFileUrl(host, share, newPath), ctx);
                oldFile.renameTo(newFile);
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "rename failed", e);
                call.reject("Failed to rename: " + e.getMessage());
            }
        }).start();
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

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile src = new SmbFile(buildSmbFileUrl(host, share, sourcePath), ctx);
                SmbFile dst = new SmbFile(buildSmbFileUrl(host, share, destPath), ctx);

                InputStream is = new SmbFileInputStream(src);
                OutputStream os = new SmbFileOutputStream(dst);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.close();
                is.close();

                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "copyFile failed", e);
                call.reject("Failed to copy: " + e.getMessage());
            }
        }).start();
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

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile src = new SmbFile(buildSmbFileUrl(host, share, sourcePath), ctx);
                SmbFile dst = new SmbFile(buildSmbFileUrl(host, share, destPath), ctx);

                // Try rename first (same share, fast)
                try {
                    src.renameTo(dst);
                } catch (Exception e) {
                    // Fallback: copy then delete
                    InputStream is = new SmbFileInputStream(src);
                    OutputStream os = new SmbFileOutputStream(dst);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                    os.close();
                    is.close();
                    src.delete();
                }

                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "moveFile failed", e);
                call.reject("Failed to move: " + e.getMessage());
            }
        }).start();
    }

    // ==================== GET DIRECTORY SIZE ====================

    @PluginMethod
    public void getDirectorySize(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
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
                call.reject("Failed to calculate size: " + e.getMessage());
            }
        }).start();
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

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile file = new SmbFile(buildSmbFileUrl(host, share, path), ctx);
                InputStream is = new SmbFileInputStream(file);

                // Read up to 512KB for thumbnail
                int maxRead = 512 * 1024;
                byte[] data = new byte[maxRead];
                int totalRead = 0;
                int bytesRead;
                while (totalRead < maxRead && (bytesRead = is.read(data, totalRead, maxRead - totalRead)) != -1) {
                    totalRead += bytesRead;
                }
                is.close();

                byte[] trimmed = new byte[totalRead];
                System.arraycopy(data, 0, trimmed, 0, totalRead);

                JSObject result = new JSObject();
                result.put("data", android.util.Base64.encodeToString(trimmed, android.util.Base64.NO_WRAP));
                result.put("mimeType", getMimeType(path));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getThumbnail failed", e);
                call.reject("Failed to get thumbnail: " + e.getMessage());
            }
        }).start();
    }

    // ==================== GET STREAM URL ====================

    @PluginMethod
    public void getStreamUrl(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile(buildSmbFileUrl(host, share, path), ctx);

                // Download to cache
                File cacheDir = new File(getContext().getCacheDir(), "stream_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                String fileName = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
                File localFile = new File(cacheDir, fileName);

                // Skip if already cached and same size
                if (localFile.exists() && localFile.length() == smbFile.length()) {
                    Uri contentUri = FileProvider.getUriForFile(getContext(),
                            getContext().getPackageName() + ".fileprovider", localFile);
                    JSObject result = new JSObject();
                    result.put("localPath", contentUri.toString());
                    result.put("mimeType", getMimeType(path));
                    call.resolve(result);
                    return;
                }

                InputStream is = new SmbFileInputStream(smbFile);
                FileOutputStream fos = new FileOutputStream(localFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                fos.close();
                is.close();

                Uri contentUri = FileProvider.getUriForFile(getContext(),
                        getContext().getPackageName() + ".fileprovider", localFile);

                JSObject result = new JSObject();
                result.put("localPath", contentUri.toString());
                result.put("mimeType", getMimeType(path));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getStreamUrl failed", e);
                call.reject("Failed to stream: " + e.getMessage());
            }
        }).start();
    }

    // ==================== SHARE FILE ====================

    @PluginMethod
    public void shareFile(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String path = call.getString("path", "");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
            try {
                CIFSContext ctx = createContext(username, password);
                SmbFile smbFile = new SmbFile(buildSmbFileUrl(host, share, path), ctx);

                // Download to share cache
                File cacheDir = new File(getContext().getCacheDir(), "share_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();

                String fileName = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
                File localFile = new File(cacheDir, fileName);

                InputStream is = new SmbFileInputStream(smbFile);
                FileOutputStream fos = new FileOutputStream(localFile);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                fos.close();
                is.close();

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
                call.reject("Failed to share: " + e.getMessage());
            }
        }).start();
    }

    // ==================== GET SHARE INFO ====================

    @PluginMethod
    public void getShareInfo(PluginCall call) {
        String host = call.getString("host");
        String share = call.getString("share");
        String username = call.getString("username", "");
        String password = call.getString("password", "");

        new Thread(() -> {
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
                call.reject("Failed to get share info: " + e.getMessage());
            }
        }).start();
    }

    // ==================== PING ====================

    @PluginMethod
    public void ping(PluginCall call) {
        String host = call.getString("host");

        new Thread(() -> {
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
        }).start();
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
