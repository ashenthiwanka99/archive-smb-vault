package com.mrdon.server.plugins;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Receives ACTION_SEND / ACTION_SEND_MULTIPLE intents delivered to
 * MainActivity and forwards them to the WebView as a plugin event.
 *
 * <p>MainActivity stashes the incoming intent so the plugin can pick it up
 * either on first load (when the app wasn't running) or immediately via
 * {@link #deliver(Intent)} when the process was already alive.  A small
 * queue inside the plugin holds events that arrive before any JS listener
 * has attached — the first `addListener('files')` call drains it.
 */
@CapacitorPlugin(name = "ShareTarget")
public class ShareTargetPlugin extends Plugin {
    private static final String TAG = "ShareTarget";

    private static ShareTargetPlugin INSTANCE;
    // Pending payloads that arrived before any JS listener was registered.
    private final List<JSObject> pending = new ArrayList<>();
    private boolean listenerAttached = false;

    @Override
    public void load() {
        INSTANCE = this;
        // If MainActivity captured an intent before the bridge was ready, flush
        // it here so it's waiting when JS listens.
        Intent held = MainActivityHolder.takePendingShare();
        if (held != null) handleIncoming(held);
    }

    /** Called from MainActivity.onNewIntent when the app is already running. */
    public static void deliver(Intent intent) {
        if (INSTANCE == null) {
            MainActivityHolder.setPendingShare(intent);
            return;
        }
        INSTANCE.handleIncoming(intent);
    }

    @PluginMethod
    public void getPending(PluginCall call) {
        // JS can poll this on boot as a fallback in case the event fired
        // before any `addListener` was attached.
        JSArray arr = new JSArray();
        synchronized (pending) {
            for (JSObject p : pending) arr.put(p);
            pending.clear();
        }
        JSObject res = new JSObject();
        res.put("items", arr);
        call.resolve(res);
    }

    @PluginMethod
    public void markListenerReady(PluginCall call) {
        listenerAttached = true;
        // Flush anything queued to the newly-attached listener.
        JSObject[] drained;
        synchronized (pending) {
            drained = pending.toArray(new JSObject[0]);
            pending.clear();
        }
        for (JSObject p : drained) notifyListeners("files", p);
        call.resolve();
    }

    private void handleIncoming(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Parcelable p : list) if (p instanceof Uri) uris.add((Uri) p);
        }
        if (uris.isEmpty()) return;

        JSArray items = new JSArray();
        ContentResolver cr = getContext().getContentResolver();
        for (Uri u : uris) {
            JSObject item = new JSObject();
            item.put("uri", u.toString());
            item.put("name", displayName(cr, u));
            item.put("size", sizeOf(cr, u));
            item.put("mimeType", cr.getType(u));
            // Keep a persistent grant so our plugin can still read the URI
            // after onNewIntent returns.  Best-effort — some senders don't
            // grant persistable permissions, in which case we keep the
            // transient grant for the lifetime of the current intent.
            try {
                getContext().grantUriPermission(getContext().getPackageName(),
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignore) {}
            items.put(item);
        }

        JSObject payload = new JSObject();
        payload.put("items", items);

        if (listenerAttached) {
            notifyListeners("files", payload);
        } else {
            synchronized (pending) { pending.add(payload); }
        }
        Log.i(TAG, "share target delivered " + uris.size() + " item(s)");
    }

    private String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignore) {}
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private long sizeOf(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignore) {}
        return 0L;
    }
}
