package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Runtime half of the fast-forward disguise. While armed (any auto-fill/auto-advance run in this
 * task), the time fields of the page's submit bridge calls are rewritten onto a
 * {@link TimelinePlanner} schedule before the native layer stores or signs them, so the server sees
 * a normally paced session instead of accelerated timestamps and near-zero listening time.
 *
 * <p>Every rewrite fails open: parsing errors, missing packages or disabled configs leave the
 * original arguments untouched, matching the module's "never affect the target app" rule.
 */
final class SubmitDisguise {
    /** How long one arm stays valid; the page may submit minutes after the automation finished. */
    private static final long ARM_TTL_MS = 60L * 60_000L;
    /** A plan older than this is rebuilt so a late manual submit still ends close to "now". */
    private static final long PLAN_REFRESH_MS = 5L * 60_000L;
    private static final long ANCHOR_JITTER_MIN_MS = 3_000L;
    private static final long ANCHOR_JITTER_MAX_MS = 20_000L;

    private static final Map<WebView, List<TimelinePlanner.Section>> PACKAGES =
            Collections.synchronizedMap(new WeakHashMap<WebView, List<TimelinePlanner.Section>>());
    private static final Map<WebView, Plan> PLANS =
            Collections.synchronizedMap(new WeakHashMap<WebView, Plan>());
    private static volatile boolean armed;
    private static volatile WebView armedView;
    private static volatile long armedUntilMs;

    private SubmitDisguise() {}

    /** Marks the current task as automation-driven so later submit calls get disguised. */
    static void arm(WebView webView) {
        if (webView == null) return;
        armed = true;
        armedView = webView;
        armedUntilMs = SystemClock.elapsedRealtime() + ARM_TTL_MS;
        PLANS.remove(webView);
        LogSink.event("submit_disguise_armed", "webview_id", id(webView), "ttl_min", "60");
    }

    /**
     * Remembers the parsed question package so the timeline can use the real audio durations.
     * Called on the package parser worker; probing is skipped entirely while the switch is off.
     */
    static void registerPackage(WebView webView, List<QuestionModel> questions) {
        if (webView == null || questions == null || questions.isEmpty()) return;
        try {
            Context context = webView.getContext();
            if (context == null || !Config.enabled(context, Config.SUBMIT_DISGUISE)) return;
        } catch (Throwable ignored) {
            return;
        }
        List<TimelinePlanner.Section> sections = buildSections(questions);
        if (sections.isEmpty()) return;
        PACKAGES.put(webView, sections);
        PLANS.remove(webView);
        long audioMs = 0L;
        int questionCount = 0;
        for (TimelinePlanner.Section section : sections) {
            audioMs += section.audioMs * section.playCount;
            questionCount += section.questionIds.size();
        }
        LogSink.event("submit_disguise_package",
                "sections", Integer.toString(sections.size()),
                "questions", Integer.toString(questionCount),
                "audio_ms", Long.toString(audioMs));
    }

    /** Rewrites one bridge call before it reaches the native layer; null when nothing changed. */
    static Object[] rewrite(WebView webView, String method, Object[] args) {
        if (!armed || webView == null || webView != armedView || method == null || args == null) {
            return null;
        }
        if (SystemClock.elapsedRealtime() > armedUntilMs) {
            armed = false;
            return null;
        }
        try {
            Context context = webView.getContext();
            if (context == null || !Config.enabled(context, Config.SUBMIT_DISGUISE)) return null;

            if ("saveData".equals(method)) {
                String state = stringArg(args, 0);
                if (state == null || !state.contains("quesEleAttr")) return null;
                return replaced(args, 0, SubmitTimeRewriter.rewriteSaveState(state, source(webView)),
                        "save_data");
            }
            if ("submitEleAttr".equals(method)) {
                String attrs = stringArg(args, 0);
                if (attrs == null || !attrs.contains("elementAttr")) return null;
                return replaced(args, 0,
                        SubmitTimeRewriter.rewriteElementAttrs(attrs, source(webView)),
                        "submit_ele_attr");
            }
            if ("submitTask".equals(method) || "submitTaskV1".equals(method)
                    || "submitBatch".equals(method)) {
                Object[] copy = null;
                int limit = Math.min(args.length, 3);
                for (int i = 0; i < limit; i++) {
                    String payload = stringArg(args, i);
                    if (payload == null) continue;
                    String rewritten = SubmitTimeRewriter.rewriteGeneric(payload, source(webView));
                    if (rewritten != null && !rewritten.equals(payload)) {
                        if (copy == null) copy = args.clone();
                        copy[i] = rewritten;
                    }
                }
                if (copy != null) note("submit_task", 0L);
                return copy;
            }
            if ("addSubmitTaskInfo".equals(method)) {
                if (args.length < 4 || !(args[3] instanceof Number)) return null;
                Object[] copy = args.clone();
                copy[3] = source(webView).endMs();
                note("add_submit_task_info", 0L);
                return copy;
            }
            return null;
        } catch (Throwable error) {
            LogSink.error("submit_disguise_rewrite", error);
            return null;
        }
    }

    private static Object[] replaced(Object[] args, int index, String rewritten, String kind) {
        if (rewritten == null || rewritten.equals(args[index])) return null;
        Object[] copy = args.clone();
        copy[index] = rewritten;
        note(kind, rewritten.length());
        return copy;
    }

    private static TimelinePlanner source(WebView webView) {
        long now = System.currentTimeMillis();
        synchronized (SubmitDisguise.class) {
            Plan plan = PLANS.get(webView);
            if (plan != null && now - plan.builtAtMs <= PLAN_REFRESH_MS) return plan.planner;
            Context context = null;
            try {
                context = webView.getContext();
            } catch (Throwable ignored) {
                // Falls back to the auto target below.
            }
            long targetMs = context == null ? 0L : Config.disguiseTargetMs(context);
            List<TimelinePlanner.Section> sections = PACKAGES.get(webView);
            if (sections == null || sections.isEmpty()) sections = fallbackSections();
            long anchor = now - ANCHOR_JITTER_MIN_MS
                    - (long) (Math.random() * (ANCHOR_JITTER_MAX_MS - ANCHOR_JITTER_MIN_MS));
            TimelinePlanner planner = TimelinePlanner.build(sections, anchor, targetMs, new Random());
            PLANS.put(webView, new Plan(planner, now));
            LogSink.event("submit_disguise_plan",
                    "webview_id", id(webView),
                    "questions", Integer.toString(planner.questionCount()),
                    "total_s", Long.toString(planner.totalSeconds()),
                    "target_min", Long.toString(targetMs / 60_000L));
            return planner;
        }
    }

    /** Question set without a parsed package: one quiet section per material group. */
    private static List<TimelinePlanner.Section> fallbackSections() {
        List<TimelinePlanner.Section> sections = new ArrayList<>();
        CurrentQuestionSet.Snapshot snapshot = CurrentQuestionSet.snapshot();
        if (snapshot == null) return sections;
        for (CurrentQuestionSet.Group group : snapshot.groups) {
            List<String> ids = new ArrayList<>();
            for (CurrentQuestionSet.Item item : group.items) {
                if (item.questionId != null && !item.questionId.isEmpty()) ids.add(item.questionId);
            }
            if (!ids.isEmpty()) sections.add(new TimelinePlanner.Section(0L, 1, ids));
        }
        return sections;
    }

    /** Groups the parsed package into listening sections in page order with probed audio lengths. */
    private static List<TimelinePlanner.Section> buildSections(List<QuestionModel> roots) {
        List<TimelinePlanner.Section> sections = new ArrayList<>();
        List<String> questionIds = new ArrayList<>();
        long audioMs = 0L;
        boolean open = false;
        for (QuestionModel question : CompositeQuestionExpander.flatten(roots)) {
            if (question.parentQuestionId.isEmpty()) {
                if (open) {
                    sections.add(new TimelinePlanner.Section(audioMs, TimelinePlanner.DEFAULT_PLAY_COUNT,
                            questionIds));
                }
                questionIds = new ArrayList<>();
                audioMs = 0L;
                open = true;
            }
            boolean compositeParent = !question.children.isEmpty() && question.options.isEmpty();
            if (!compositeParent && !question.questionId.isEmpty()) {
                questionIds.add(question.questionId);
            }
            if (audioMs <= 0L) {
                for (QuestionModel.MediaModel media : question.media) {
                    if (!media.exists || media.resolvedPath.isEmpty()) continue;
                    long probed = MediaDurations.probeMs(media.resolvedPath);
                    if (probed > 0L) {
                        audioMs = probed;
                        break;
                    }
                }
            }
        }
        if (open) {
            sections.add(new TimelinePlanner.Section(audioMs, TimelinePlanner.DEFAULT_PLAY_COUNT,
                    questionIds));
        }
        return sections;
    }

    private static String stringArg(Object[] args, int index) {
        if (index >= args.length || args[index] == null) return null;
        Object value = args[index];
        return value instanceof String ? (String) value : null;
    }

    private static void note(String kind, long length) {
        LogSink.event("submit_disguise_rewrite", "kind", kind, "length", Long.toString(length));
    }

    private static String id(WebView webView) {
        return Integer.toHexString(System.identityHashCode(webView));
    }

    private static final class Plan {
        final TimelinePlanner planner;
        final long builtAtMs;

        Plan(TimelinePlanner planner, long builtAtMs) {
            this.planner = planner;
            this.builtAtMs = builtAtMs;
        }
    }
}
