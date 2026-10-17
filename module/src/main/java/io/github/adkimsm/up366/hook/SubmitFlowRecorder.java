package io.github.adkimsm.up366.hook;

import android.webkit.WebView;

final class SubmitFlowRecorder {
    private SubmitFlowRecorder() {}

    static boolean observes(String method) {
        return "saveData".equals(method)
                || "loadData".equals(method)
                || "submitEleAttr".equals(method)
                || "submitPageEleInfo".equals(method)
                || "submitTask".equals(method)
                || "submitTaskV1".equals(method)
                || "submitBatch".equals(method);
    }

    static void call(WebView webView, String method, Object[] args, String callId) {
        if (!observes(method)) return;
        String event = "saveData".equals(method) || "loadData".equals(method)
                ? "save_data" : "submit_task";
        String safeArgs = "saveData".equals(method) || "loadData".equals(method)
                ? ReadOnlyTaskMonitor.summarizeSaveState(webView, args)
                : "parameters=" + (args == null ? 0 : args.length) + ",content=<omitted>";
        LogSink.event(event, "phase", "call", "method", method, "call_id", callId,
                "args", safeArgs);
    }

    static void result(WebView webView, String method, String callId, long elapsed,
                       Object result, Throwable error) {
        if (!observes(method)) return;
        String event = "saveData".equals(method) || "loadData".equals(method)
                ? "save_data" : "submit_task";
        String resultSummary;
        if (error != null) {
            resultSummary = "<error>";
        } else if ("loadData".equals(method)) {
            resultSummary = ReadOnlyTaskMonitor.summarizeState(webView, result);
        } else {
            resultSummary = LogSink.summarize(result, 2048);
        }
        LogSink.event(event, "phase", "result", "method", method, "call_id", callId,
                "elapsed_ms", Long.toString(elapsed),
                "result", resultSummary,
                "error", error == null ? "" : LogSink.limit(LogSink.redact(error.toString()), 512));
    }
}
