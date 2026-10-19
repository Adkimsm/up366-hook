package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional page automation, default-off and switch-gated. {@code fill} clicks the option that
 * matches each locally indexed answer (programmatic clicks bypass the app's CSS gating);
 * {@code advance} rebuilds the native "this audio finished" callback so the section unlocks and the
 * slide moves without waiting for playback, then clicks the submit control and confirms the
 * "not finished yet" popup. It never calls a native bridge method and every failure is swallowed.
 *
 * <p>Each WebView runs every page script through one main-thread pump, so the actions do not exclude
 * each other any more: a fill request that arrives while a fast-forward run is stepping is queued
 * with that run, executed before the next submit attempt, and never rejected as "busy". Repeated
 * fast-forward clicks restart the run from scratch, {@link #stop()} ends it early, and fill requests
 * left behind by any ending are executed by a standalone pump. All public entry points must be
 * called on the main thread; only {@link #onSubmitObserved} is posted from the bridge observer.
 */
final class Automation {
    interface Callback {
        void onResult(String json);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_STEPS = 1500;
    private static final int SUBMIT_GRACE_STEPS = 12;

    /** Pending fill rounds per WebView, shared by the fast-forward loop and the standalone pump. */
    private static final Map<WebView, FillQueue> FILL_QUEUES =
            Collections.synchronizedMap(new WeakHashMap<WebView, FillQueue>());

    /** The run currently driving the fast-forward, if any; used to finish it on observed submit. */
    private static volatile Advance active;
    /** Set between an advance request and the moment its run is created (main thread only). */
    private static volatile boolean starting;
    /** Bumped on every advance/stop request so superseded starts abort (main thread only). */
    private static int generation;

    private Automation() {}

    /** Clicks every known answer inside the answering slides; merges into a running fast-forward. */
    static void fill(final Activity activity, final Callback callback) {
        final Map<String, String> answers = CurrentQuestionSet.answers();
        if (answers.isEmpty()) {
            deliver(callback, "{\"error\":\"no_answers\"}");
            return;
        }
        final Advance run = active;
        if (run != null && !run.finished.get()) {
            // The fast-forward loop drains the same queue before its next submit attempt.
            SubmitDisguise.arm(run.webView);
            fillQueue(run.webView).submit(answers, sink(callback));
            LogSink.event("auto_fill_queued", "answers", Integer.toString(answers.size()),
                    "reason", "advance_running");
            return;
        }
        PageDevTools.resolveTarget(activity, (webView, summaries) -> {
            if (webView == null) {
                deliver(callback, "{\"error\":\"no_webview\"}");
                return;
            }
            SubmitDisguise.arm(webView);
            fillQueue(webView).submit(answers, sink(callback));
            pumpFills(webView);
        });
    }

    /**
     * Starts the fast-forward. An existing run is finished as {@code restarted} and a new run
     * begins; a pending start that has not produced a run yet is superseded silently.
     */
    static void advance(final Activity activity, final Callback callback) {
        final int gen = ++generation;
        starting = true;
        final Advance running = active;
        if (running != null && !running.finished.get()) {
            LogSink.event("auto_advance_restart", "steps", Integer.toString(running.steps));
            finish(running.callback, running, "restarted");
        }
        PageDevTools.resolveTarget(activity, (webView, summaries) -> {
            if (gen != generation) {
                deliver(callback, "{\"reason\":\"superseded\"}");
                return;
            }
            if (webView == null) {
                starting = false;
                deliver(callback, "{\"error\":\"no_webview\"}");
                return;
            }
            SubmitDisguise.arm(webView);
            final SpeedProfile profile = SpeedProfile.of(Config.speedProfile(webView.getContext()));
            LogSink.event("auto_advance_start", "profile", profile.label,
                    "rate", profile.rate, "clock", Integer.toString(profile.clock),
                    "prefix", LogSink.limit(AudioPlayback.callbackPrefix(), 80));
            // Compress the page's answer/reading waits for the duration of the run.
            evaluate(webView, AutomationScripts.pageHooksScript(profile), ignored -> {
                if (gen != generation) {
                    // Stopped or restarted while the hooks were installing: leave the page as found.
                    evaluate(webView, AutomationScripts.pageHooksRestoreScript(), restored -> { });
                    deliver(callback, "{\"reason\":\"superseded\"}");
                    return;
                }
                Advance run = new Advance(webView, profile, callback);
                active = run;
                starting = false;
                step(webView, run);
            });
        });
    }

    /**
     * Requests that the current fast-forward ends with {@code stopped}. Returns false when nothing
     * is running; fill requests still queued are executed afterwards, never dropped.
     */
    static boolean stop() {
        final boolean activeOrStarting = isRunning() || starting;
        generation++;
        starting = false;
        final Advance running = active;
        if (running != null && !running.finished.get()) {
            LogSink.event("auto_advance_stop", "steps", Integer.toString(running.steps));
            finish(running.callback, running, "stopped");
        }
        return activeOrStarting;
    }

    /** True while a fast-forward run is stepping (used by the panel for stop feedback). */
    static boolean isRunning() {
        final Advance run = active;
        return run != null && !run.finished.get();
    }

    /**
     * Called from the bridge observer: the page has submitted on its own (the exam flow calls
     * {@code submitData} when the last section ends), so the fast-forward has nothing left to do.
     * Ending here also stops the step loop instead of spinning until the step limit.
     */
    static void onSubmitObserved(WebView webView) {
        Advance run = active;
        if (run == null || run.finished.get() || webView != run.webView) return;
        MAIN.post(() -> finish(run.callback, run, "submitted"));
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

    private static final class Advance {
        final WebView webView;
        final SpeedProfile profile;
        final Callback callback;
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        final AtomicBoolean finished = new AtomicBoolean(false);
        String targetId = "";
        long targetSetAt;
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
        int fills;

        Advance(WebView webView, SpeedProfile profile, Callback callback) {
            this.webView = webView;
            this.profile = profile;
            this.callback = callback;
            this.targetSetAt = startedAt;
        }
    }

    private static void step(final WebView webView, final Advance run) {
        if (run.finished.get()) return;
        if (run.steps++ >= MAX_STEPS) {
            finish(run.callback, run, "max_steps");
            return;
        }
        final FillQueue queue = fillQueue(webView);
        final FillQueue.Round fill = queue.poll();
        if (fill != null) {
            // A fill the user asked for while the fast-forward is running: click the answers before
            // the next submit attempt and keep stepping afterwards. When the run ended meanwhile
            // (stop/submit), hand the remaining rounds to the standalone pump instead of dropping.
            executeFill(webView, run, queue, fill, () -> MAIN.post(() -> {
                if (run.finished.get()) pumpFills(webView);
                else step(webView, run);
            }));
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
                ? AutomationScripts.forceStopScript(run.targetId, AudioPlayback.callbackPrefix(),
                        AudioPlayback.durationFor(run.targetId))
                : AutomationScripts.submitScript(run.clickedAtStep < 0 && !queue.pending());
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
                finish(run.callback, run, "confirmed");
                return;
            }
            if (run.clickedAtStep >= 0 && run.steps - run.clickedAtStep >= SUBMIT_GRACE_STEPS) {
                finish(run.callback, run, "clicked");
                return;
            }
            MAIN.postDelayed(() -> step(webView, run),
                    fireStop ? run.profile.fastStepMs : run.profile.slowStepMs);
        });
    }

    /** Runs one queued fill round, reports it to every waiting sink and releases the queue. */
    private static void executeFill(final WebView webView, final Advance run, final FillQueue queue,
                                    final FillQueue.Round round, final Runnable done) {
        evaluate(webView, AutomationScripts.fillScript(round.answers()), json -> {
            queue.finishRound();
            if (run != null) run.fills++;
            LogSink.event("auto_fill_result", "answers", Integer.toString(round.size()),
                    "result", LogSink.limit(LogSink.redact(json), 300));
            for (FillQueue.Sink sink : round.sinks()) {
                if (sink != null) sink.onResult(json);
            }
            done.run();
        });
    }

    /** Drives queued fill rounds for a WebView that no fast-forward run is currently stepping. */
    private static void pumpFills(final WebView webView) {
        if (webView == null) return;
        final Advance run = active;
        if (run != null && run.webView == webView && !run.finished.get()) {
            return; // The step loop drains the same queue.
        }
        final FillQueue queue = fillQueue(webView);
        final FillQueue.Round round = queue.poll();
        if (round == null) return;
        executeFill(webView, null, queue, round,
                () -> MAIN.post(() -> pumpFills(webView)));
    }

    private static FillQueue fillQueue(WebView webView) {
        synchronized (FILL_QUEUES) {
            FillQueue queue = FILL_QUEUES.get(webView);
            if (queue == null) {
                queue = new FillQueue();
                FILL_QUEUES.put(webView, queue);
            }
            return queue;
        }
    }

    private static FillQueue.Sink sink(final Callback callback) {
        return json -> deliver(callback, json);
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

    private static void finish(Callback callback, Advance run, String reason) {
        if (!run.finished.compareAndSet(false, true)) return;
        if (active == run) active = null;
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
                    + ",\"fills\":" + run.fills
                    + ",\"audioWaitMs\":" + run.audioWaitMs
                    + ",\"steps\":" + run.steps
                    + ",\"elapsedMs\":" + elapsed
                    + ",\"avgPerSectionMs\":" + average + "}";
            LogSink.event("auto_advance_result", "reason", reason,
                    "audio_ids", Integer.toString(run.audioIds),
                    "forced_stops", Integer.toString(run.forcedStops),
                    "timer_calls", Integer.toString(timerCalls),
                    "clock_calls", Integer.toString(clockCalls),
                    "fills", Integer.toString(run.fills),
                    "audio_wait_ms", Long.toString(run.audioWaitMs),
                    "elapsed_ms", Long.toString(elapsed));
            deliver(callback, summary);
            // A fill request must never be dropped: anything still queued runs standalone now.
            pumpFills(run.webView);
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
