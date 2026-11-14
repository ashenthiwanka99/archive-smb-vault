package com.mrdon.server.backup;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * A tiny localhost HTTP server that serves SMB files to the WebView's
 * <video> element with proper Range support.
 *
 * <p>Why: Capacitor's WebView can't talk SMB.  The previous approach was to
 * download the entire file to cache before returning — on a 2 GB video that
 * blocks playback for minutes and wastes disk.  Instead we register the
 * remote file once, hand back `http://127.0.0.1:&lt;port&gt;/&lt;id&gt;`, and
 * serve byte ranges on demand using {@link SmbRandomAccessFile}.  The video
 * player makes Range requests as the user seeks; only the bytes that are
 * actually watched cross the wire.
 *
 * <p>Binds only to 127.0.0.1 so no other app on the device can reach it.
 * Singleton across the process lifetime; one {@link ServerSocket} is cheap.
 */
public class SmbStreamServer {
    private static final String TAG = "SmbStreamServer";
    private static SmbStreamServer INSTANCE;

    private ServerSocket serverSocket;
    private int port = -1;
    private Thread listenerThread;
    private final Map<String, Stream> streams = new HashMap<>();
    private volatile boolean running = false;

    public static synchronized SmbStreamServer getInstance() {
        if (INSTANCE == null) INSTANCE = new SmbStreamServer();
        return INSTANCE;
    }

    /** Lazily start the HTTP listener bound to 127.0.0.1. */
    public synchronized int ensureStarted() throws IOException {
        if (running) return port;
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        port = serverSocket.getLocalPort();
        running = true;
        listenerThread = new Thread(this::listen, "SmbStreamServer");
        listenerThread.setDaemon(true);
        listenerThread.start();
        Log.i(TAG, "Listening on 127.0.0.1:" + port);
        return port;
    }

    /**
     * Register a remote file; returns the id (random 128-bit token) to put
     * in the URL path.  Because localhost is reachable by any app on the
     * device, this token is the only thing preventing a sibling app from
     * enumerating and streaming our SMB files.  nanoTime + counter was
     * brute-forceable; SecureRandom isn't.
     */
    public synchronized String register(SmbFile file, String mimeType) {
        byte[] bytes = new byte[16];
        SECURE.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        String id = sb.toString();
        Stream s = new Stream();
        s.file = file;
        s.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        s.registeredAt = System.currentTimeMillis();
        streams.put(id, s);
        return id;
    }

    private static final java.security.SecureRandom SECURE = new java.security.SecureRandom();

    /** Drop a registration so the SmbFile can be GC'd after playback. */
    public synchronized void deregister(String id) {
        streams.remove(id);
    }

    private void listen() {
        while (running) {
            try {
                final Socket client = serverSocket.accept();
                Thread t = new Thread(() -> handle(client), "SmbStreamClient");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(60_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.ISO_8859_1));

            String requestLine = in.readLine();
            if (requestLine == null) { close(client); return; }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { respond(client, 400, null, null, 0, 0, 0, null); return; }
            String method = parts[0];
            String url = parts[1];
            String id = url.startsWith("/") ? url.substring(1) : url;
            int q = id.indexOf('?');
            if (q >= 0) id = id.substring(0, q);

            long rangeStart = 0, rangeEnd = -1;
            boolean hasRange = false;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("range:")) {
                    int eq = line.indexOf("bytes=");
                    if (eq >= 0) {
                        String val = line.substring(eq + 6).trim();
                        int dash = val.indexOf('-');
                        if (dash >= 0) {
                            try {
                                if (dash > 0) rangeStart = Long.parseLong(val.substring(0, dash));
                                String tail = val.substring(dash + 1);
                                if (!tail.isEmpty()) rangeEnd = Long.parseLong(tail);
                                hasRange = true;
                            } catch (NumberFormatException ignore) { /* treat as no-range */ }
                        }
                    }
                }
            }

            Stream s;
            synchronized (this) { s = streams.get(id); }
            if (s == null) { respond(client, 404, null, null, 0, 0, 0, null); return; }

            long total = s.file.length();
            if (rangeEnd < 0 || rangeEnd >= total) rangeEnd = total - 1;
            if (rangeStart < 0 || rangeStart > rangeEnd) {
                respond(client, 416, s.mimeType, null, total, 0, 0, null);
                return;
            }
            long contentLength = rangeEnd - rangeStart + 1;

            // HEAD requests: headers only.
            if ("HEAD".equalsIgnoreCase(method)) {
                respond(client, hasRange ? 206 : 200, s.mimeType, null,
                        total, rangeStart, rangeEnd, null);
                return;
            }

            respond(client, hasRange ? 206 : 200, s.mimeType, s.file,
                    total, rangeStart, rangeEnd, client.getOutputStream());
        } catch (Exception e) {
            // Broken pipes happen constantly with seek-heavy players; they're
            // expected, not an error.
            if (!(e instanceof IOException)) Log.w(TAG, "handle failed", e);
            close(client);
        }
    }

    /**
     * Write HTTP response headers and — if `out` is non-null and we have a
     * file — the requested byte range body.
     */
    private void respond(Socket client, int status, String mime, SmbFile file,
                         long total, long rangeStart, long rangeEnd,
                         OutputStream bodyStream) {
        try {
            OutputStream out = bodyStream != null ? bodyStream : client.getOutputStream();
            String reason = reasonFor(status);
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
            if (mime != null) h.append("Content-Type: ").append(mime).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (status == 206) {
                long cl = rangeEnd - rangeStart + 1;
                h.append("Content-Range: bytes ").append(rangeStart).append('-')
                    .append(rangeEnd).append('/').append(total).append("\r\n");
                h.append("Content-Length: ").append(cl).append("\r\n");
            } else if (status == 200) {
                h.append("Content-Length: ").append(total).append("\r\n");
            } else if (status == 416) {
                h.append("Content-Range: bytes */").append(total).append("\r\n");
                h.append("Content-Length: 0\r\n");
            } else {
                h.append("Content-Length: 0\r\n");
            }
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));

            if (bodyStream != null && file != null && status != 416) {
                try (SmbRandomAccessFile raf = new SmbRandomAccessFile(file, "r")) {
                    raf.seek(rangeStart);
                    byte[] buf = new byte[64 * 1024];
                    long remaining = rangeEnd - rangeStart + 1;
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int read = raf.read(buf, 0, toRead);
                        if (read < 0) break;
                        out.write(buf, 0, read);
                        remaining -= read;
                    }
                }
            }
            out.flush();
        } catch (Exception ignore) {
            /* client went away mid-response — normal for seek-heavy playback */
        } finally {
            close(client);
        }
    }

    private String reasonFor(int status) {
        switch (status) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default:  return "Error";
        }
    }

    private void close(Socket s) {
        try { s.close(); } catch (Exception ignore) {}
    }

    private static class Stream {
        SmbFile file;
        String mimeType;
        long registeredAt;
    }

    /**
     * Serve a stream request from inside the WebView via
     * `WebViewClient.shouldInterceptRequest` — this path bypasses the
     * localhost HTTP server entirely, so the video load becomes same-origin
     * to the page (`https://localhost/_smb_stream/<id>`) and escapes the
     * mixed-content + Private Network Access blocks newer Chromium-backed
     * WebViews impose on `http://127.0.0.1` from an https page.
     *
     * <p>Returns null when the id is unknown (caller should 404).  Otherwise
     * a bounded InputStream over the requested byte range, plus the headers
     * the WebView needs to honour HTTP semantics (Accept-Ranges,
     * Content-Range, Content-Length, Content-Type).
     *
     * <p>The returned InputStream takes ownership of a fresh
     * {@link SmbRandomAccessFile}; closing the stream releases the handle.
     * We open a new RAF per request because the WebView may issue parallel
     * range requests for the same id and RAFs aren't thread-safe.
     */
    public InterceptResponse serveInterception(String id, String rangeHeader) {
        Stream s;
        synchronized (this) { s = streams.get(id); }
        if (s == null) return null;

        long total;
        try {
            total = s.file.length();
        } catch (Exception e) {
            Log.w(TAG, "serveInterception: length() failed for " + id, e);
            return null;
        }

        long rangeStart = 0, rangeEnd = total - 1;
        boolean hasRange = false;
        if (rangeHeader != null) {
            int eq = rangeHeader.indexOf("bytes=");
            if (eq >= 0) {
                String val = rangeHeader.substring(eq + 6).trim();
                int dash = val.indexOf('-');
                if (dash >= 0) {
                    try {
                        if (dash > 0) rangeStart = Long.parseLong(val.substring(0, dash));
                        String tail = val.substring(dash + 1);
                        if (!tail.isEmpty()) rangeEnd = Long.parseLong(tail);
                        hasRange = true;
                    } catch (NumberFormatException ignore) { /* treat as no-range */ }
                }
            }
        }

        if (rangeEnd < 0 || rangeEnd >= total) rangeEnd = total - 1;

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        // CORS headers are belt-and-braces: same-origin doesn't strictly
        // need them, but setting them means the WebView won't ever tag the
        // response as opaque if the caller ever uses fetch() from JS.
        headers.put("Access-Control-Allow-Origin", "*");

        if (rangeStart < 0 || rangeStart > rangeEnd) {
            headers.put("Content-Range", "bytes */" + total);
            headers.put("Content-Length", "0");
            return new InterceptResponse(
                    416, "Range Not Satisfiable", s.mimeType,
                    new ByteArrayInputStream(new byte[0]),
                    headers);
        }

        long contentLength = rangeEnd - rangeStart + 1;
        headers.put("Content-Length", String.valueOf(contentLength));
        if (hasRange) {
            headers.put("Content-Range",
                    "bytes " + rangeStart + "-" + rangeEnd + "/" + total);
        }

        InputStream body;
        try {
            SmbRandomAccessFile raf = new SmbRandomAccessFile(s.file, "r");
            raf.seek(rangeStart);
            body = new BoundedSmbInputStream(raf, contentLength);
        } catch (Exception e) {
            Log.w(TAG, "serveInterception: open+seek failed for " + id, e);
            return null;
        }

        return new InterceptResponse(
                hasRange ? 206 : 200,
                hasRange ? "Partial Content" : "OK",
                s.mimeType, body, headers);
    }

    /** Simple DTO for the WebViewClient's shouldInterceptRequest callback. */
    public static class InterceptResponse {
        public final int status;
        public final String reason;
        public final String mimeType;
        public final InputStream body;
        public final Map<String, String> headers;
        InterceptResponse(int status, String reason, String mimeType,
                          InputStream body, Map<String, String> headers) {
            this.status = status;
            this.reason = reason;
            this.mimeType = mimeType;
            this.body = body;
            this.headers = headers;
        }
    }

    /**
     * InputStream view over an {@link SmbRandomAccessFile}.  Reads up to
     * `limit` bytes from the current file position, then returns EOF.  Owns
     * the RAF — closing the stream closes the underlying SMB handle.
     */
    private static class BoundedSmbInputStream extends InputStream {
        private final SmbRandomAccessFile raf;
        private long remaining;

        BoundedSmbInputStream(SmbRandomAccessFile raf, long limit) {
            this.raf = raf;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = raf.read();
            if (b < 0) return -1;
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int want = (int) Math.min(len, remaining);
            int read = raf.read(b, off, want);
            if (read < 0) return -1;
            remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            try { raf.close(); } catch (Exception ignore) {}
        }
    }
}
