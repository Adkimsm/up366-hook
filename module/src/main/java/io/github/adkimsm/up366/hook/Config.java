package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Target-process configuration reader. Order of preference: the module {@link HookStatusProvider},
 * then {@code XSharedPreferences}, then built-in defaults. The Provider is not trusted implicitly,
 * so the fallback chain stays in place.
 */
final class Config {
    static final String ACTIVITY = ModuleConfig.ACTIVITY;
    static final String NETWORK = ModuleConfig.NETWORK;
    static final String WEBVIEW = ModuleConfig.WEBVIEW;
    static final String PROBE = ModuleConfig.PROBE;
    static final String BRIDGE = ModuleConfig.BRIDGE;
    static final String QUESTION_METADATA = ModuleConfig.QUESTION_METADATA;
    static final String ANSWER_INDEX = ModuleConfig.ANSWER_INDEX;

    static final String AUTHORITY = HookStatusProvider.AUTHORITY;
    static final String METHOD_GET_FLAGS = HookStatusProvider.METHOD_GET_FLAGS;

    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final Object LOCK = new Object();
    private static final long CACHE_TTL_MS = 1500L;
    private static final Map<String, Boolean> VALUES = new HashMap<>();
    private static boolean loaded;
    private static boolean warned;
    private static long loadedAt;

    private Config() {}

    /** Drops the cached flag values so the next read re-queries the Provider. */
    static void invalidate() {
        synchronized (LOCK) {
            loaded = false;
            loadedAt = 0L;
        }
    }

    static boolean enabled(Context context, String key) {
        synchronized (LOCK) {
            Boolean cached = VALUES.get(key);
            long now = android.os.SystemClock.elapsedRealtime();
            if (cached != null && loaded && now - loadedAt < CACHE_TTL_MS) return cached;
        }

        try {
            Bundle result = context.getContentResolver().call(URI, METHOD_GET_FLAGS, null, null);
            synchronized (LOCK) {
                loaded = true;
                loadedAt = android.os.SystemClock.elapsedRealtime();
                for (String candidate : ModuleConfig.KEYS) {
                    VALUES.put(candidate, result == null
                            ? ModuleConfig.defaultValue(candidate)
                            : result.getBoolean(candidate, ModuleConfig.defaultValue(candidate)));
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
                for (String candidate : ModuleConfig.KEYS) {
                    VALUES.put(candidate, ModuleConfig.defaultValue(candidate));
                }
                if (!warned) {
                    warned = true;
                    LogSink.error("read_config_once", error);
                }
            }
            return ModuleConfig.defaultValue(key);
        }
    }

    private static Map<String, Boolean> readSharedPreferences() {
        try {
            XSharedPreferences preferences = new XSharedPreferences(
                    ModuleConfig.MODULE_PACKAGE, ModuleConfig.PREFS);
            preferences.reload();
            boolean hasValue = false;
            Map<String, Boolean> values = new HashMap<>();
            for (String candidate : ModuleConfig.KEYS) {
                hasValue |= preferences.contains(candidate);
                values.put(candidate, preferences.getBoolean(candidate,
                        ModuleConfig.defaultValue(candidate)));
            }
            return hasValue ? values : null;
        } catch (Throwable error) {
            if (!warned) LogSink.error("read_xshared_preferences", error);
            return null;
        }
    }
}
