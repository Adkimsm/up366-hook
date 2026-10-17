package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

final class ActivityRecorder {
    private ActivityRecorder() {}

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnCreate", Activity.class, Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.args[0];
                    if (!Config.enabled(activity, Config.ACTIVITY)) return;
                    LogSink.event(
                            "activity_on_create",
                            "class", activity.getClass().getName(),
                            "task", String.valueOf(activity.getTaskId()),
                            "title", String.valueOf(activity.getTitle())
                    );
                }
            });
        } catch (Throwable error) {
            LogSink.error("hook_activity_on_create", error);
        }
    }
}
