package io.github.adkimsm.up366.hook;

import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers the audio id the page most recently asked the native player to play, the call form the
 * native layer uses to report state back, and the clip durations seen in those native callbacks.
 * The page's own {@code onAudioStateChangeV1} handler ignores updates for unknown ids, so the
 * automation must reuse the id of the live player instead of inventing one, and the synthetic
 * "finished" callback should carry the same duration the real one would have carried.
 */
final class AudioPlayback {
    private static final Pattern DURATION = Pattern.compile("\"duration\"\\s*:\\s*(\\d+)");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private static volatile String currentId = "";
    private static volatile String callbackPrefix = "";
    private static volatile long observedAt;
    private static final Map<String, Long> DURATIONS = new ConcurrentHashMap<>();

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

    /** Extracts {@code id}/{@code duration} from one native audio-state callback payload. */
    static void rememberFromCallback(String args) {
        if (args == null || args.isEmpty()) return;
        try {
            Matcher id = ID.matcher(args);
            Matcher duration = DURATION.matcher(args);
            if (!id.find() || !duration.find()) return;
            long ms = Long.parseLong(duration.group(1));
            if (ms > 0L) DURATIONS.put(id.group(1), ms);
        } catch (Throwable ignored) {
            // Diagnostics only; a parse failure must never affect the target app.
        }
    }

    /** Duration in milliseconds for an audio id, or 0 when no native callback carried it yet. */
    static long durationFor(String id) {
        if (id == null || id.isEmpty()) return 0L;
        Long duration = DURATIONS.get(id);
        return duration == null ? 0L : duration;
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
