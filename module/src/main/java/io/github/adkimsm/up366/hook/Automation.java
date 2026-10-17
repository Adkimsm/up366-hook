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
    private static final long FAST_STEP_MS = 60L;
    private static final long SLOW_STEP_MS = 200L;
    private static final long STOP_RETRY_MS = 250L;
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
            LogSink.event("auto_advance_start", "prefix",
                    LogSink.limit(AudioPlayback.callbackPrefix(), 80),
                    "rate", AutomationScripts.ACCELERATED_RATE);
            // Compress the page's answer/reading waits for the duration of the run.
            evaluate(webView, AutomationScripts.timerClampScript(), clamp -> {
                Run run = new Run(webView);
                step(webView, run, callback);
            });
        });
    }

    private static final class Run {
        final WebView webView;
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        String targetId = "";
        int targetAttempts;
        long lastStopAt;
        int audioIds;
        int forcedStops;
        int calledTrue;
        int calledFalse;
        String lastVia = "";
        int steps;
        int clickedAtStep = -1;

        Run(WebView webView) {
            this.webView = webView;
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
            run.targetId = audioId;
            run.targetAttempts = 0;
            run.audioIds++;
        }
        boolean fireStop = !run.targetId.isEmpty() && now - run.lastStopAt >= STOP_RETRY_MS;
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
                    fireStop ? FAST_STEP_MS : SLOW_STEP_MS);
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
        evaluate(run.webView, AutomationScripts.timerRestoreScript(), restored -> { });
        long elapsed = android.os.SystemClock.elapsedRealtime() - run.startedAt;
        long average = run.audioIds == 0 ? 0 : elapsed / run.audioIds;
        String summary = "{\"reason\":\"" + reason + "\",\"audioIds\":" + run.audioIds
                + ",\"forcedStops\":" + run.forcedStops
                + ",\"calledTrue\":" + run.calledTrue
                + ",\"calledFalse\":" + run.calledFalse
                + ",\"lastVia\":\"" + run.lastVia + "\""
                + ",\"rate\":\"" + AutomationScripts.ACCELERATED_RATE + "\""
                + ",\"steps\":" + run.steps
                + ",\"elapsedMs\":" + elapsed
                + ",\"avgPerSectionMs\":" + average + "}";
        LogSink.event("auto_advance_result", "reason", reason,
                "audio_ids", Integer.toString(run.audioIds),
                "forced_stops", Integer.toString(run.forcedStops),
                "called_true", Integer.toString(run.calledTrue),
                "called_false", Integer.toString(run.calledFalse),
                "elapsed_ms", Long.toString(elapsed));
        deliver(callback, summary);
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
