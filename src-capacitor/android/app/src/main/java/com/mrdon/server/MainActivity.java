package com.mrdon.server;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.core.view.WindowCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import com.mrdon.server.backup.SmbStreamServer;
import com.mrdon.server.plugins.BackupPlugin;
import com.mrdon.server.plugins.MainActivityHolder;
import com.mrdon.server.plugins.MediaScannerPlugin;
import com.mrdon.server.plugins.ShareTargetPlugin;
import com.mrdon.server.plugins.SmbPlugin;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends BridgeActivity {

    /**
     * Keys used on the Intent extras forwarded to the WebView as deep-links.
     * {@code open} is a router path (e.g. "/gallery") that MainActivity will
     * push onto the Vue router after the bridge is ready.
     */
    public static final String EXTRA_OPEN_PATH = "open";

    /** URL path prefix whose requests we proxy from the WebView into the
     *  SMB stream server. Must stay in sync with SmbPlugin.getStreamUrl. */
    private static final String STREAM_PATH_PREFIX = "/_smb_stream/";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SmbPlugin.class);
        registerPlugin(MediaScannerPlugin.class);
        registerPlugin(BackupPlugin.class);
        registerPlugin(ShareTargetPlugin.class);
        super.onCreate(savedInstanceState);
        // Android 15 (SDK 35) and above force edge-to-edge by default. Opt out so
        // the system status bar owns its own space above the webview and the
        // "Archive." wordmark isn't drawn under the status icons. viewport-fit=cover
        // + safe-area insets still work on devices/OEMs that ignore this call.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        // Install a WebViewClient that proxies /_smb_stream/<id> requests
        // straight from the SMB stream server.  Serving the video under the
        // WebView's own origin (https://localhost) sidesteps mixed-content
        // and Private Network Access blocks that newer Chromium imposes on
        // http://127.0.0.1 from an https page.
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().setWebViewClient(
                    new StreamInterceptingWebViewClient(getBridge()));
        }

        // The intent that started this activity may be a share-target or a
        // deep-link from a notification; handle both here.
        routeIntent(getIntent());
    }

    /**
     * WebView client that serves /_smb_stream/<id> from the SMB proxy and
     * forwards everything else to Capacitor's default handler.  Keeping
     * this as a nested class inside MainActivity avoids reaching into
     * Capacitor internals from multiple places.
     */
    private static class StreamInterceptingWebViewClient extends BridgeWebViewClient {
        StreamInterceptingWebViewClient(com.getcapacitor.Bridge bridge) {
            super(bridge);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return super.shouldInterceptRequest(view, request);
            }
            Uri uri = request.getUrl();
            String path = uri.getPath();
            if (path == null || !path.startsWith(STREAM_PATH_PREFIX)) {
                return super.shouldInterceptRequest(view, request);
            }
            String id = path.substring(STREAM_PATH_PREFIX.length());
            int slash = id.indexOf('/');
            if (slash >= 0) id = id.substring(0, slash);
            String rangeHeader = null;
            Map<String, String> reqHeaders = request.getRequestHeaders();
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    if ("range".equalsIgnoreCase(e.getKey())) {
                        rangeHeader = e.getValue();
                        break;
                    }
                }
            }
            SmbStreamServer.InterceptResponse ir =
                    SmbStreamServer.getInstance().serveInterception(id, rangeHeader);
            if (ir == null) {
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                return new WebResourceResponse(
                        "text/plain", "utf-8", 404, "Not Found",
                        headers, new ByteArrayInputStream(new byte[0]));
            }
            return new WebResourceResponse(
                    ir.mimeType, "utf-8",
                    ir.status, ir.reason,
                    ir.headers, ir.body);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Update the activity's stored intent so getIntent() returns the
        // latest one (important for downstream lifecycle code).
        setIntent(intent);
        routeIntent(intent);
    }

    private void routeIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            // ShareTargetPlugin picks it up on its own load() or via deliver().
            ShareTargetPlugin.deliver(intent);
            MainActivityHolder.setPendingShare(intent);
        }
        // Deep-link from a notification: `open` is a router path like
        // "/gallery".  We hand it off to JS via a simple evalJS once the
        // bridge is ready; MainLayout.vue listens via Capacitor's appUrlOpen
        // event is tricky to synthesize, so we use a minimal bridge script.
        String openPath = intent.getStringExtra(EXTRA_OPEN_PATH);
        if (openPath != null && !openPath.isEmpty() && getBridge() != null) {
            getBridge().getWebView().post(() -> {
                String safe = openPath.replace("\\", "\\\\").replace("'", "\\'");
                getBridge().getWebView().evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('archive-open-path', { detail: '" + safe + "' }))",
                    null
                );
            });
        }
    }
}
