package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    private static final long STEP_DELAY_MS = 120L;
    private static final int MAX_STEPS = 1200;
    private static final int MAX_FORCE_STOPS = 40;
    private static final int SUBMIT_GRACE_STEPS = 10;

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
                    LogSink.limit(AudioPlayback.callbackPrefix(), 80));
            step(webView, new Run(), callback);
        });
    }

    private static final class Run {
        final Set<String> seenAudioIds = new HashSet<>();
        int forcedStops;
        int targetMisses;
        int steps;
        int clickedAtStep = -1;
    }

    private static void step(final WebView webView, final Run run, final Callback callback) {
        if (run.steps++ >= MAX_STEPS) {
            finish(callback, run, "max_steps");
            return;
        }
        String audioId = AudioPlayback.currentId();
        boolean fireStop = audioId != null && !audioId.isEmpty()
                && !run.seenAudioIds.contains(audioId) && run.forcedStops < MAX_FORCE_STOPS;
        if (fireStop) {
            run.seenAudioIds.add(audioId);
            run.forcedStops++;
        }
        String script = fireStop
                ? AutomationScripts.forceStopScript(audioId)
                : AutomationScripts.submitScript(run.clickedAtStep < 0);
        evaluate(webView, script, json -> {
            if (fireStop && json != null && json.contains("\"called\":false")) {
                run.targetMisses++;
                if (run.targetMisses >= 3) {
                    finish(callback, run, "callback_target_missing");
                    return;
                }
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
            MAIN.postDelayed(() -> step(webView, run, callback), STEP_DELAY_MS);
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
        String summary = "{\"reason\":\"" + reason + "\",\"forcedStops\":" + run.forcedStops
                + ",\"steps\":" + run.steps + ",\"audioIds\":" + run.seenAudioIds.size() + "}";
        LogSink.event("auto_advance_result", "reason", reason,
                "forced_stops", Integer.toString(run.forcedStops),
                "steps", Integer.toString(run.steps));
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
