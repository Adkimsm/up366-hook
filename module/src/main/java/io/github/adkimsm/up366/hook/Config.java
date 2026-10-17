package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;

final class Config {
    static final String ACTIVITY = "activity";
    static final String NETWORK = "network";
    static final String WEBVIEW = "webview";
    static final String PROBE = "probe";
    static final String BRIDGE = "bridge";
    static final String QUESTION_METADATA = "question_metadata";

    private static final Uri URI = Uri.parse("content://io.github.adkimsm.up366.provider");
    private static final Object LOCK = new Object();
    private static final long CACHE_TTL_MS = 1500L;
    private static final Map<String, Boolean> VALUES = new HashMap<>();
    private static boolean loaded;
    private static boolean warned;
    private static long loadedAt;

    private Config() {}

    static boolean enabled(Context context, String key) {
        synchronized (LOCK) {
            Boolean cached = VALUES.get(key);
            long now = android.os.SystemClock.elapsedRealtime();
            if (cached != null && loaded && now - loadedAt < CACHE_TTL_MS) return cached;
        }

        try {
            Bundle result = context.getContentResolver().call(URI, "get_flags", null, null);
            synchronized (LOCK) {
                loaded = true;
                loadedAt = android.os.SystemClock.elapsedRealtime();
                for (String candidate : new String[]{ACTIVITY, NETWORK, WEBVIEW, PROBE, BRIDGE, QUESTION_METADATA}) {
                    VALUES.put(candidate, result == null
                            ? defaultValue(candidate)
                            : result.getBoolean(candidate, defaultValue(candidate)));
                }
                return VALUES.get(key);
            }
        } catch (Throwable error) {
            Map<String, Boolean> shared = readSharedPreferences();
            if (shared != null) {
                synchronized (LOCK) {
                    loaded = true;
                    loadedAt = android.os.SystemClock.elapsedRealtime();
                    VALUES.putAll(shared);
                    return VALUES.get(key);
                }
            }
            synchronized (LOCK) {
                loaded = true;
                loadedAt = android.os.SystemClock.elapsedRealtime();
                for (String candidate : new String[]{ACTIVITY, NETWORK, WEBVIEW, PROBE, BRIDGE, QUESTION_METADATA}) {
                    VALUES.put(candidate, defaultValue(candidate));
                }
                if (!warned) {
                    warned = true;
                    LogSink.error("read_config_once", error);
                }
            }
            return defaultValue(key);
        }
    }

    private static Map<String, Boolean> readSharedPreferences() {
        try {
            XSharedPreferences preferences = new XSharedPreferences(
                    "io.github.adkimsm.up366", "p0_flags");
            preferences.reload();
            boolean hasValue = false;
            Map<String, Boolean> values = new HashMap<>();
            for (String candidate : new String[]{ACTIVITY, NETWORK, WEBVIEW, PROBE, BRIDGE, QUESTION_METADATA}) {
                hasValue |= preferences.contains(candidate);
                values.put(candidate, preferences.getBoolean(candidate, defaultValue(candidate)));
            }
            return hasValue ? values : null;
        } catch (Throwable error) {
            if (!warned) LogSink.error("read_xshared_preferences", error);
            return null;
        }
    }

    private static boolean defaultValue(String key) {
        return ACTIVITY.equals(key);
    }
}
