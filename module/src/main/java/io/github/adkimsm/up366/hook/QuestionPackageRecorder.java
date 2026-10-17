package io.github.adkimsm.up366.hook;

import android.webkit.WebView;

import java.io.File;
import java.util.Collections;
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
        LogSink.event("question_parse_schedule", "status", "queued",
                "page", pageFile.getAbsolutePath(), "context", TaskContext.snapshot(webView));
        WORKER.execute(() -> parse(webView, pageFile));
    }

    private static void parse(WebView webView, File pageFile) {
        try {
            java.util.List<QuestionModel> questions = QuestionDataParser.parsePage(pageFile);
            java.util.List<QuestionModel> flattened = CompositeQuestionExpander.flatten(questions);
            java.util.Map<String, ListeningResourceMapper.Resource> resources =
                    ListeningResourceMapper.index(questions);
            ReadOnlyTaskMonitor.registerQuestions(webView, questions);
            int roots = 0;
            for (QuestionModel question : questions) {
                if (question.parentQuestionId.length() == 0) roots++;
            }
            LogSink.event("question_parsed", "page", pageFile.getAbsolutePath(),
                    "context", TaskContext.snapshot(webView),
                    "roots", Integer.toString(roots),
                    "flattened", Integer.toString(flattened.size()),
                    "mapped", Integer.toString(resources.size()),
                    "resources", ListeningResourceMapper.summarize(flattened));
        } catch (Throwable error) {
            LogSink.error("parse_question_package", error);
        }
    }
}
