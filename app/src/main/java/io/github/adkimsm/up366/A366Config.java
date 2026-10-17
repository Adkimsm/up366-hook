package io.github.adkimsm.up366;

import android.content.Context;
import android.content.SharedPreferences;

final class A366Config {
    static final String ACTIVITY = "activity";
    static final String NETWORK = "network";
    static final String WEBVIEW = "webview";
    static final String PROBE = "probe";
    static final String BRIDGE = "bridge";
    static final String QUESTION_METADATA = "question_metadata";

    private static final String PREFS = "p0_flags";

    private A366Config() {}

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
}
