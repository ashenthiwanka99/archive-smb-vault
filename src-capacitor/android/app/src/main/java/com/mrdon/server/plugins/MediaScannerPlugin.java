package com.mrdon.server.plugins;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@CapacitorPlugin(name = "MediaScanner")
public class MediaScannerPlugin extends Plugin {
    private static final String TAG = "MediaScanner";

    @PluginMethod
    public void getMediaFolders(PluginCall call) {
        new Thread(() -> {
            try {
                ContentResolver resolver = getContext().getContentResolver();
                Map<String, int[]> folderCounts = new HashMap<>(); // path -> [photoCount, videoCount]

                // Scan image folders
                scanFolders(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Images.Media.DATA, folderCounts, 0);

                // Scan video folders
                scanFolders(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.DATA, folderCounts, 1);

                JSArray folders = new JSArray();
                for (Map.Entry<String, int[]> entry : folderCounts.entrySet()) {
                    String folderPath = entry.getKey();
                    int[] counts = entry.getValue();
                    String folderName = new File(folderPath).getName();

                    JSObject folder = new JSObject();
                    folder.put("path", folderPath);
                    folder.put("name", folderName);
                    folder.put("photoCount", counts[0]);
                    folder.put("videoCount", counts[1]);
                    folder.put("totalCount", counts[0] + counts[1]);
                    folders.put(folder);
                }

                JSObject result = new JSObject();
                result.put("folders", folders);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getMediaFolders failed", e);
                call.reject("Failed to scan folders: " + e.getMessage());
            }
        }).start();
    }

    private void scanFolders(ContentResolver resolver, Uri collectionUri, String dataColumn,
                             Map<String, int[]> folderCounts, int typeIndex) {
        String[] projection = { dataColumn };
        Cursor cursor = resolver.query(collectionUri, projection, null, null, null);
        if (cursor != null) {
            int dataCol = cursor.getColumnIndexOrThrow(dataColumn);
            while (cursor.moveToNext()) {
                String filePath = cursor.getString(dataCol);
                if (filePath != null) {
                    String folderPath = new File(filePath).getParent();
                    if (folderPath != null) {
                        int[] counts = folderCounts.computeIfAbsent(folderPath, k -> new int[]{0, 0});
                        counts[typeIndex]++;
                    }
                }
            }
            cursor.close();
        }
    }

    @PluginMethod
    public void getPhotos(PluginCall call) {
        long sinceTimestamp = call.getLong("since", 0L);
        long sinceSec = sinceTimestamp / 1000;
        // Optional folder filter
        JSArray folderFilter = call.getArray("folders", null);
        Set<String> allowedFolders = jsonArrayToSet(folderFilter);

        new Thread(() -> {
            try {
                ContentResolver resolver = getContext().getContentResolver();
                JSArray results = new JSArray();

                Uri imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE
                };
                String selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
                String[] args = { String.valueOf(sinceSec) };

                Cursor cursor = resolver.query(imageUri, projection, selection, args,
                        MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA));

                        // Filter by folder if specified
                        if (!allowedFolders.isEmpty() && filePath != null) {
                            String folder = new File(filePath).getParent();
                            if (!allowedFolders.contains(folder)) continue;
                        }

                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                        JSObject item = new JSObject();
                        item.put("id", String.valueOf(id));
                        item.put("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)));
                        item.put("path", filePath);
                        item.put("uri", "content://media/external/images/media/" + id);
                        item.put("size", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)));
                        item.put("dateAdded", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000);
                        item.put("mimeType", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)));
                        item.put("type", "photo");
                        results.put(item);
                    }
                    cursor.close();
                }

                JSObject result = new JSObject();
                result.put("media", results);
                result.put("count", results.length());
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getPhotos failed", e);
                call.reject("Failed to scan photos: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void getVideos(PluginCall call) {
        long sinceTimestamp = call.getLong("since", 0L);
        long sinceSec = sinceTimestamp / 1000;
        JSArray folderFilter = call.getArray("folders", null);
        Set<String> allowedFolders = jsonArrayToSet(folderFilter);

        new Thread(() -> {
            try {
                ContentResolver resolver = getContext().getContentResolver();
                JSArray results = new JSArray();

                Uri videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                String[] projection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.MIME_TYPE
                };
                String selection = MediaStore.Video.Media.DATE_ADDED + " > ?";
                String[] args = { String.valueOf(sinceSec) };

                Cursor cursor = resolver.query(videoUri, projection, selection, args,
                        MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String filePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA));

                        if (!allowedFolders.isEmpty() && filePath != null) {
                            String folder = new File(filePath).getParent();
                            if (!allowedFolders.contains(folder)) continue;
                        }

                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                        JSObject item = new JSObject();
                        item.put("id", String.valueOf(id));
                        item.put("name", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)));
                        item.put("path", filePath);
                        item.put("uri", "content://media/external/video/media/" + id);
                        item.put("size", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)));
                        item.put("dateAdded", cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)) * 1000);
                        item.put("mimeType", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)));
                        item.put("type", "video");
                        results.put(item);
                    }
                    cursor.close();
                }

                JSObject result = new JSObject();
                result.put("media", results);
                result.put("count", results.length());
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getVideos failed", e);
                call.reject("Failed to scan videos: " + e.getMessage());
            }
        }).start();
    }

    private Set<String> jsonArrayToSet(JSArray arr) {
        Set<String> set = new HashSet<>();
        if (arr != null) {
            try {
                for (int i = 0; i < arr.length(); i++) {
                    set.add(arr.getString(i));
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing folder filter", e);
            }
        }
        return set;
    }
}
