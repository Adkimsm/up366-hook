package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.content.Intent;

/**
 * Notification-only broadcasts. They never carry answers or configuration payloads because they
 * can be dropped: the receiver re-queries {@link HookStatusProvider} for the real state.
 */
final class Broadcasts {
    static final String CONFIG_CHANGED = "io.github.adkimsm.up366.hook.CONFIG_CHANGED";
    static final String INDEX_UPDATED = "io.github.adkimsm.up366.hook.INDEX_UPDATED";
    static final String HOOK_STATUS_CHANGED = "io.github.adkimsm.up366.hook.HOOK_STATUS_CHANGED";
    static final String PARSE_FINISHED = "io.github.adkimsm.up366.hook.PARSE_FINISHED";

    private Broadcasts() {}

    static void send(Context context, String action) {
        if (context == null || action == null) return;
        try {
            Intent intent = new Intent(action);
            intent.setPackage(ModuleConfig.TARGET_PACKAGE);
            context.sendBroadcast(intent);
            LogSink.event("broadcast_sent", "action", action, "status", "ok");
        } catch (Throwable error) {
            LogSink.event("broadcast_sent", "action", action, "status", "failed",
                    "errorCode", error.getClass().getSimpleName());
        }
    }
}
