package io.github.adkimsm.up366.hook;

import android.os.SystemClock;

/**
 * Remembers the audio id the page most recently asked the native player to play. The page's own
 * {@code onAudioStateChangeV1} handler ignores updates for unknown ids, so the automation must
 * reuse the id of the live player instead of inventing one.
 */
final class AudioPlayback {
    private static volatile String currentId = "";
    private static volatile String callbackPrefix = "";
    private static volatile long observedAt;

    private AudioPlayback() {}

    static void remember(String id) {
        if (id == null || id.length() == 0) return;
        currentId = id;
        observedAt = SystemClock.elapsedRealtime();
    }

    /** Remembers how the native side calls the page, e.g. {@code window.jsObj.}. */
    static void rememberCallbackPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return;
        callbackPrefix = prefix;
    }

    static String currentId() {
        return currentId;
    }

    static String callbackPrefix() {
        return callbackPrefix;
    }

    static long observedAt() {
        return observedAt;
    }
}
