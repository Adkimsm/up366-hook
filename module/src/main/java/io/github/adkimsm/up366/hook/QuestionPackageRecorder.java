package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.webkit.WebView;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QuestionPackageRecorder {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "up366-question-metadata");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<WebView, String> LAST_PAGES =
            Collections.synchronizedMap(new WeakHashMap<WebView, String>());

    private QuestionPackageRecorder() {}

    static void install(Context context) {
        if (context == null) return;
        try {
            ConfigReceiver receiver = new ConfigReceiver();
            IntentFilter filter = new IntentFilter(Broadcasts.CONFIG_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            LogSink.event("config_receiver_registered");
        } catch (Throwable error) {
            LogSink.error("register_config_receiver", error);
        }
    }

    static void schedule(WebView webView, String pageUrl) {
        if (webView == null || pageUrl == null || !pageUrl.startsWith("file://")) return;
        File pageFile = QuestionDataParser.pageFileFromUrl(pageUrl);
        if (pageFile == null) {
            LogSink.event("question_parse_schedule", "status", "invalid_file_url",
                    "url", LogSink.redact(pageUrl), "context", TaskContext.snapshot(webView));
            return;
        }
        if (!pageFile.isFile()) {
            LogSink.event("question_parse_schedule", "status", "page_file_missing",
                    "page", pageFile.getAbsolutePath(), "context", TaskContext.snapshot(webView));
            return;
        }
        synchronized (LAST_PAGES) {
            String previous = LAST_PAGES.put(webView, pageFile.getAbsolutePath());
            if (pageFile.getAbsolutePath().equals(previous)) {
                LogSink.event("question_parse_schedule", "status", "duplicate",
                        "page", pageFile.getAbsolutePath());
                return;
            }
        }
        final String traceId = Trace.newTraceId();
        LogSink.diagnostic("question_parse_schedule", traceId, "", "queued", "", 0,
                "page", pageFile.getAbsolutePath(), "context", TaskContext.snapshot(webView));
        WORKER.execute(() -> parse(webView, pageFile, traceId));
    }

    private static void parse(WebView webView, File pageFile, String traceId) {
        long started = SystemClock.elapsedRealtime();
        Context context = context(webView);
        try {
            List<QuestionModel> questions = QuestionDataParser.parsePage(pageFile);
            List<QuestionModel> flattened = CompositeQuestionExpander.flatten(questions);
            Map<String, ListeningResourceMapper.Resource> resources =
                    ListeningResourceMapper.index(questions);
            ReadOnlyTaskMonitor.registerQuestions(webView, questions);
            int roots = 0;
            for (QuestionModel question : questions) {
                if (question.parentQuestionId.length() == 0) roots++;
            }
            String pageId = pageFile.getParentFile() == null ? "" : pageFile.getParentFile().getName();
            int answersPresent = -1;
            String packageVersion = "";
            if (context != null && Config.enabled(context, Config.ANSWER_INDEX)) {
                AnswerIndexer.Result result = AnswerIndexer.index(pageFile);
                packageVersion = result.packageVersion;
                answersPresent = result.report.answersPresent;
                if (!result.records.isEmpty()) {
                    IpcClient.indexAnswers(context, result.records, result.packageVersion, traceId);
                }
                LogSink.diagnostic("answer_index_built", traceId, "", "ok", "",
                        SystemClock.elapsedRealtime() - started,
                        "packageVersionHash", Trace.hash(result.packageVersion),
                        "coverage", result.report.toString());
            }
            LogSink.diagnostic("question_parsed", traceId, "", "ok", "",
                    SystemClock.elapsedRealtime() - started,
                    "page", pageFile.getAbsolutePath(),
                    "context", TaskContext.snapshot(webView),
                    "roots", Integer.toString(roots),
                    "flattened", Integer.toString(flattened.size()),
                    "mapped", Integer.toString(resources.size()),
                    "answersPresent", Integer.toString(answersPresent),
                    "resources", ListeningResourceMapper.summarize(flattened));
            if (context != null) {
                IpcClient.reportParseEvent(context, packageVersion, pageId, roots, flattened.size(),
                        resources.size(), answersPresent, traceId);
            }
        } catch (Throwable error) {
            LogSink.diagnostic("question_parsed", traceId, "", "failed",
                    error.getClass().getSimpleName(), SystemClock.elapsedRealtime() - started,
                    "page", pageFile.getAbsolutePath());
            LogSink.error("parse_question_package", error);
        }
    }

    private static Context context(WebView webView) {
        try {
            return webView == null ? null : webView.getContext();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
