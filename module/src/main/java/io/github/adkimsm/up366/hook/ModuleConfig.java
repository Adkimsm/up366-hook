package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Module-process configuration store. The hooked target process reads the same values through
 * {@link HookStatusProvider} or {@link Config}'s {@code XSharedPreferences} fallback.
 */
final class ModuleConfig {
    static final String ACTIVITY = "activity";
    static final String NETWORK = "network";
    static final String WEBVIEW = "webview";
    static final String PROBE = "probe";
    static final String BRIDGE = "bridge";
    static final String QUESTION_METADATA = "question_metadata";
    static final String ANSWER_INDEX = "answer_index";

    static final String PREFS = "p0_flags";
    static final String MODULE_PACKAGE = "io.github.adkimsm.up366.hook";
    static final String TARGET_PACKAGE = "com.up366.mobile";

    static final String[] KEYS = {
            ACTIVITY, NETWORK, WEBVIEW, PROBE, BRIDGE, QUESTION_METADATA, ANSWER_INDEX
    };

    private ModuleConfig() {}

    static boolean get(Context context, String key) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(key, defaultValue(key));
    }

    static void set(Context context, String key, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
    }

    static boolean defaultValue(String key) {
        return ACTIVITY.equals(key);
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
}
