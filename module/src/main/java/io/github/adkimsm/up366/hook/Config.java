package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Target-process configuration store. Values live in the target app's own private
 * {@code SharedPreferences} and are read and written only from inside the hooked target process
 * (the Hook code and the injected panel). No cross-process or exported surface is involved.
 *
 * <p>Reads are cheap but frequent (per WebView callback), so they are cached for a short window and
 * the cache is dropped whenever the panel writes a value through {@link #set}.
 */
final class Config {
    static final String ACTIVITY = "activity";
    static final String NETWORK = "network";
    static final String WEBVIEW = "webview";
    static final String PROBE = "probe";
    static final String BRIDGE = "bridge";
    static final String QUESTION_METADATA = "question_metadata";
    static final String ANSWER_INDEX = "answer_index";
    static final String INJECT_UI = "inject_ui";
    static final String LOG_FILE = "log_file";
    static final String LOG_RAW_BRIDGE = "log_raw_bridge";
    static final String AUTO_FILL = "auto_fill";
    static final String AUTO_ADVANCE = "auto_advance";

    /** Int-valued preference (not a boolean toggle): index into {@link SpeedProfile#LEVELS}. */
    static final String SPEED_PROFILE = "speed_profile";

    /** Persistent UI preference (not a functional hook toggle) for the floating button. */
    static final String HIDE_FLOATING = "hide_floating";
    static final String FLOAT_X = "float_x";
    static final String FLOAT_Y = "float_y";

    static final String PREFS = "p0_flags";
    static final String TARGET_PACKAGE = "com.up366.mobile";

    static final String[] KEYS = {
            ACTIVITY, NETWORK, WEBVIEW, PROBE, BRIDGE, QUESTION_METADATA, ANSWER_INDEX, INJECT_UI,
            LOG_FILE, LOG_RAW_BRIDGE, AUTO_FILL, AUTO_ADVANCE
    };

    private static final Object LOCK = new Object();
    private static final long CACHE_TTL_MS = 1000L;
    private static final Map<String, Boolean> VALUES = new LinkedHashMap<>();
    private static boolean loaded;
    private static long loadedAt;

    private Config() {}

    /** Drops the cached flag values so the next read reloads the SharedPreferences. */
    static void invalidate() {
        synchronized (LOCK) {
            loaded = false;
            loadedAt = 0L;
        }
    }

    static boolean enabled(Context context, String key) {
        if (context == null) return defaultValue(key);
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (loaded && now - loadedAt < CACHE_TTL_MS) {
                Boolean cached = VALUES.get(key);
                if (cached != null) return cached;
            }
        }
        Map<String, Boolean> values = read(context);
        synchronized (LOCK) {
            VALUES.putAll(values);
            loaded = true;
            loadedAt = now;
        }
        return values.containsKey(key) ? values.get(key) : defaultValue(key);
    }

    static void set(Context context, String key, boolean value) {
        if (context == null || !isKnownKey(key)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
        invalidate();
        LogSink.event("config_updated", "key", key, "value", Boolean.toString(value),
                "status", "ok");
    }

    static boolean get(Context context, String key) {
        return enabled(context, key);
    }

    static boolean hideFloating(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(HIDE_FLOATING, false);
    }

    static void setHideFloating(Context context, boolean hidden) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(HIDE_FLOATING, hidden).apply();
        LogSink.event("config_updated", "key", HIDE_FLOATING, "value", Boolean.toString(hidden),
                "status", "ok");
    }

    /** Saved floating-button x offset in px, or {@code fallback} when never dragged. */
    static int floatX(Context context, int fallback) {
        if (context == null) return fallback;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(FLOAT_X, fallback);
    }

    /** Saved floating-button y offset in px, or {@code fallback} when never dragged. */
    static int floatY(Context context, int fallback) {
        if (context == null) return fallback;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(FLOAT_Y, fallback);
    }

    static void setFloatPosition(Context context, int x, int y) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(FLOAT_X, x).putInt(FLOAT_Y, y).apply();
        LogSink.event("config_updated", "key", "float_position", "status", "ok");
    }

    static boolean defaultValue(String key) {
        return ACTIVITY.equals(key) || INJECT_UI.equals(key);
    }

    /** Selected speed profile index; falls back to the packaged default. */
    static int speedProfile(Context context) {
        if (context == null) return SpeedProfile.DEFAULT_INDEX;
        try {
            return SpeedProfile.clampIndex(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(SPEED_PROFILE, SpeedProfile.DEFAULT_INDEX));
        } catch (Throwable ignored) {
            return SpeedProfile.DEFAULT_INDEX;
        }
    }

    static void setSpeedProfile(Context context, int index) {
        if (context == null) return;
        int safe = SpeedProfile.clampIndex(index);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(SPEED_PROFILE, safe).apply();
        LogSink.event("config_updated", "key", SPEED_PROFILE, "value", Integer.toString(safe),
                "status", "ok");
    }

    static boolean isKnownKey(String key) {
        for (String candidate : KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    static Map<String, Boolean> all(Context context) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String key : KEYS) values.put(key, get(context, key));
        return values;
    }

    private static Map<String, Boolean> read(Context context) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        try {
            SharedPreferences preferences =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (String key : KEYS) {
                values.put(key, preferences.getBoolean(key, defaultValue(key)));
            }
        } catch (Throwable error) {
            for (String key : KEYS) values.put(key, defaultValue(key));
        }
        return values;
    }
}
