package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.app.Instrumentation;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks the target Activity lifecycle and attaches the read-only panel as a sibling View on the
 * DecorView. Injection is best-effort: any failure is caught, never propagates into the target app,
 * and repeated failures trip a circuit breaker that stops further attempts.
 *
 * <p>Resume/pause are hooked through {@link Instrumentation} so they fire even when the app's
 * Activity overrides the lifecycle method without calling {@code super}; destroy is hooked on
 * {@link Activity} itself.
 */
final class UiInjector {
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static int failures;
    private static boolean disabled;

    private UiInjector() {}

    static void install() {
        hookInstrumentation("callActivityOnResume", true);
        hookInstrumentation("callActivityOnPause", false);
        hookActivity("onDestroy", false);
    }

    private static void hookInstrumentation(String method, boolean attach) {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, method, Activity.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            handle((Activity) param.args[0], attach);
                        }
                    });
            LogSink.event("ui_hook_registered", "hook", "Instrumentation." + method);
        } catch (Throwable error) {
            LogSink.error("hook_instrumentation_" + method, error);
        }
    }

    private static void hookActivity(String method, boolean attach) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handle((Activity) param.thisObject, attach);
                }
            });
            LogSink.event("ui_hook_registered", "hook", "Activity." + method);
        } catch (Throwable error) {
            LogSink.error("hook_activity_" + method, error);
        }
    }

    private static void handle(Activity activity, boolean attach) {
        if (attach) attach(activity);
        else detach(activity);
    }

    private static void attach(Activity activity) {
        if (disabled || activity == null) return;
        try {
            if (!Config.enabled(activity, Config.INJECT_UI)) return;
            if (activity.isFinishing()) return;
            if (InjectedUi.show(activity)) {
                failures = 0;
                LogSink.diagnostic("ui_injected", "", "", "ok", "", 0,
                        "activity", activity.getClass().getName());
            }
        } catch (Throwable error) {
            failures++;
            LogSink.diagnostic("ui_injection_failed", "", "", "failed",
                    error.getClass().getSimpleName(), 0,
                    "activity", activity.getClass().getName(),
                    "failures", Integer.toString(failures));
            LogSink.error("inject_ui", error);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                disabled = true;
                LogSink.event("ui_injection_failed", "status", "disabled_by_failure",
                        "failures", Integer.toString(failures));
            }
        }
    }

    private static void detach(Activity activity) {
        try {
            InjectedUi.hide(activity);
        } catch (Throwable error) {
            LogSink.error("remove_ui", error);
        }
    }
}
