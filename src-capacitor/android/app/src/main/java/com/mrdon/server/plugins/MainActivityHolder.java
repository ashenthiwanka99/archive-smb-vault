package com.mrdon.server.plugins;

import android.content.Intent;

/**
 * Single-slot buffer for a share-target intent that arrived before the
 * Capacitor plugin bridge was ready to receive it.  Used from both sides:
 * MainActivity puts an intent in on cold-start, ShareTargetPlugin pulls it
 * out in {@code load()} and forwards it as a JS event.
 */
public final class MainActivityHolder {
    private static Intent pendingShare;

    public static synchronized void setPendingShare(Intent intent) {
        pendingShare = intent;
    }

    public static synchronized Intent takePendingShare() {
        Intent i = pendingShare;
        pendingShare = null;
        return i;
    }

    private MainActivityHolder() {}
}
