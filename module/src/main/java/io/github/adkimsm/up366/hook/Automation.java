package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional page automation, default-off and switch-gated. {@code fill} clicks the option that
 * matches each locally indexed answer (programmatic clicks bypass the app's CSS gating);
 * {@code advance} rebuilds the native "this audio finished" callback so the section unlocks and the
 * slide moves without waiting for playback, then clicks the submit control and confirms the
 * "not finished yet" popup. It never calls a native bridge method and every failure is swallowed.
 */
final class Automation {
    interface Callback {
        void onResult(String json);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final int MAX_STEPS = 1500;
    private static final int SUBMIT_GRACE_STEPS = 12;

    private Automation() {}

    /** Clicks every known answer inside the answering slides. */
    static void fill(final Activity activity, final Callback callback) {
        final Map<String, String> answers = CurrentQuestionSet.answers();
        if (answers.isEmpty()) {
            deliver(callback, "{\"error\":\"no_answers\"}");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            deliver(callback, "{\"error\":\"busy\"}");
            return;
        }
        PageDevTools.resolveTarget(activity, (webView, summaries) -> {
            if (webView == null) {
                BUSY.set(false);
                deliver(callback, "{\"error\":\"no_webview\"}");
                return;
            }
            evaluate(webView, AutomationScripts.fillScript(answers), json -> {
                BUSY.set(false);
                LogSink.event("auto_fill_result", "answers", Integer.toString(answers.size()),
                        "result", LogSink.limit(LogSink.redact(json), 300));
                deliver(callback, json);
            });
        });
    }

    /** Fast-forwards the listening gate and submits. */
    static void advance(final Activity activity, final Callback callback) {
        if (!BUSY.compareAndSet(false, true)) {
            deliver(callback, "{\"error\":\"busy\"}");
            return;
        }
        PageDevTools.resolveTarget(activity, (webView, summaries) -> {
            if (webView == null) {
                BUSY.set(false);
                deliver(callback, "{\"error\":\"no_webview\"}");
                return;
            }
            final SpeedProfile profile = SpeedProfile.of(Config.speedProfile(webView.getContext()));
            LogSink.event("auto_advance_start", "profile", profile.label,
                    "rate", profile.rate, "clock", Integer.toString(profile.clock),
                    "prefix", LogSink.limit(AudioPlayback.callbackPrefix(), 80));
            // Compress the page's answer/reading waits for the duration of the run.
            evaluate(webView, AutomationScripts.pageHooksScript(profile), clamp -> {
                Run run = new Run(webView, profile);
                step(webView, run, callback);
            });
        });
    }

    /**
     * Installs the page hooks as early as possible (page start) so the page's own captured timer
     * references are already compressed. Only active while {@code auto_advance} is switched on.
     */
    static void installPageHooks(final WebView webView) {
        if (webView == null) return;
        try {
            if (!Config.enabled(webView.getContext(), Config.AUTO_ADVANCE)) return;
        } catch (Throwable ignored) {
            return;
        }
        MAIN.post(() -> {
            try {
                webView.evaluateJavascript(
                        AutomationScripts.pageHooksScript(SpeedProfile.of(Config.speedProfile(webView.getContext()))),
                        value -> { });
            } catch (Throwable error) {
                LogSink.error("automation_page_hooks", error);
            }
        });
    }

    private static final class Run {
        final WebView webView;
        final SpeedProfile profile;
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        String targetId = "";
        long targetSetAt = startedAt;
        long audioWaitMs;
        int targetAttempts;
        long lastStopAt;
        int audioIds;
        int forcedStops;
        int calledTrue;
        int calledFalse;
        String lastVia = "";
        int steps;
        int clickedAtStep = -1;

        Run(WebView webView, SpeedProfile profile) {
            this.webView = webView;
            this.profile = profile;
            this.targetSetAt = startedAt;
        }
    }

    private static void step(final WebView webView, final Run run, final Callback callback) {
        if (run.steps++ >= MAX_STEPS) {
            finish(callback, run, "max_steps");
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        String audioId = AudioPlayback.currentId();
        if (audioId != null && !audioId.isEmpty() && !audioId.equals(run.targetId)) {
            if (!run.targetId.isEmpty()) run.audioWaitMs += now - run.targetSetAt;
            run.targetId = audioId;
            run.targetSetAt = now;
            run.targetAttempts = 0;
            run.audioIds++;
        }
        boolean fireStop = !run.targetId.isEmpty() && now - run.lastStopAt >= run.profile.stopRetryMs;
        if (fireStop) {
            run.targetAttempts++;
            run.forcedStops++;
            run.lastStopAt = now;
        }
        String script = fireStop
                ? AutomationScripts.forceStopScript(run.targetId, AudioPlayback.callbackPrefix())
                : AutomationScripts.submitScript(run.clickedAtStep < 0);
        evaluate(webView, script, json -> {
            if (fireStop) {
                boolean called = json != null && json.contains("\"called\":true");
                if (called) {
                    run.calledTrue++;
                    int via = json.indexOf("\"via\":\"");
                    if (via >= 0) run.lastVia = json.substring(via + 7, Math.min(json.length(), via + 20));
                } else {
                    run.calledFalse++;
                }
                LogSink.ringOnly("auto_advance_step", "id", Trace.hash(run.targetId),
                        "attempt", Integer.toString(run.targetAttempts),
                        "called", Boolean.toString(called),
                        "result", LogSink.limit(json, 80));
            }
            if (json != null && json.contains("\"clicked\":true") && run.clickedAtStep < 0) {
                run.clickedAtStep = run.steps;
            }
            boolean confirmed = json != null && json.contains("\"confirmed\":true");
            if (confirmed) {
                finish(callback, run, "confirmed");
                return;
            }
            if (run.clickedAtStep >= 0 && run.steps - run.clickedAtStep >= SUBMIT_GRACE_STEPS) {
                finish(callback, run, "clicked");
                return;
            }
            MAIN.postDelayed(() -> step(webView, run, callback),
                    fireStop ? run.profile.fastStepMs : run.profile.slowStepMs);
        });
    }

    private static void evaluate(WebView webView, String script, java.util.function.Consumer<String> onJson) {
        MAIN.post(() -> {
            try {
                webView.evaluateJavascript(script, value -> onJson.accept(PageDevTools.unwrapJson(value)));
            } catch (Throwable error) {
                LogSink.error("automation_evaluate", error);
                onJson.accept("{\"error\":\"evaluate_failed\"}");
            }
        });
    }

    private static void finish(Callback callback, Run run, String reason) {
        BUSY.set(false);
        evaluate(run.webView, AutomationScripts.pageHooksRestoreScript(), restored -> {
            int timerCalls = counter(restored, "timerCalls");
            int clockCalls = counter(restored, "clockCalls");
            long elapsed = android.os.SystemClock.elapsedRealtime() - run.startedAt;
            if (!run.targetId.isEmpty()) {
                run.audioWaitMs += android.os.SystemClock.elapsedRealtime() - run.targetSetAt;
            }
            long average = run.audioIds == 0 ? 0 : elapsed / run.audioIds;
            String summary = "{\"reason\":\"" + reason + "\",\"audioIds\":" + run.audioIds
                    + ",\"forcedStops\":" + run.forcedStops
                    + ",\"calledTrue\":" + run.calledTrue
                    + ",\"calledFalse\":" + run.calledFalse
                    + ",\"lastVia\":\"" + run.lastVia + "\""
                    + ",\"profile\":\"" + run.profile.label + "\""
                    + ",\"rate\":\"" + run.profile.rate + "\""
                    + ",\"timerCalls\":" + timerCalls
                    + ",\"clockCalls\":" + clockCalls
                    + ",\"audioWaitMs\":" + run.audioWaitMs
                    + ",\"steps\":" + run.steps
                    + ",\"elapsedMs\":" + elapsed
                    + ",\"avgPerSectionMs\":" + average + "}";
            LogSink.event("auto_advance_result", "reason", reason,
                    "audio_ids", Integer.toString(run.audioIds),
                    "forced_stops", Integer.toString(run.forcedStops),
                    "timer_calls", Integer.toString(timerCalls),
                    "clock_calls", Integer.toString(clockCalls),
                    "audio_wait_ms", Long.toString(run.audioWaitMs),
                    "elapsed_ms", Long.toString(elapsed));
            deliver(callback, summary);
        });
    }

    private static int counter(String json, String name) {
        try {
            return new org.json.JSONObject(json).optInt(name, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void deliver(Callback callback, String json) {
        if (callback == null) return;
        MAIN.post(() -> {
            try {
                callback.onResult(json);
            } catch (Throwable error) {
                LogSink.error("automation_callback", error);
            }
        });
    }
}
