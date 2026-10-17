package io.github.adkimsm.up366.hook;

import android.media.MediaMetadataRetriever;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads local audio durations for the timeline layout; failures fall back to zero (unknown). */
final class MediaDurations {
    private static final int MAX_ENTRIES = 64;
    private static final Map<String, Long> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private MediaDurations() {}

    /** Duration in milliseconds, or 0 when the file cannot be read. */
    static long probeMs(String path) {
        if (path == null || path.isEmpty()) return 0L;
        Long cached = CACHE.get(path);
        if (cached != null) return cached;
        long duration = read(path);
        CACHE.put(path, duration);
        return duration;
    }

    private static long read(String path) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(path);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Math.max(0L, Long.parseLong(value.trim()));
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                    // Releasing is best-effort; a failure must never affect the target app.
                }
            }
        }
    }
}
