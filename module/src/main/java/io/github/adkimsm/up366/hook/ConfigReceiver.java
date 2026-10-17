package io.github.adkimsm.up366.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Target-process receiver for the module's notification-only broadcasts. It never trusts the
 * broadcast payload: it simply invalidates the cached flags so the next read re-queries the
 * Provider (or falls back to {@code XSharedPreferences}).
 */
final class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Broadcasts.CONFIG_CHANGED.equals(action)) {
            Config.invalidate();
            LogSink.event("config_broadcast_received", "action", action, "status", "ok");
        } else {
            LogSink.event("config_broadcast_received", "action", String.valueOf(action),
                    "status", "orphan");
        }
    }
}
