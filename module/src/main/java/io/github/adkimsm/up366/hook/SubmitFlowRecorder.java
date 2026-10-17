package io.github.adkimsm.up366.hook;

import android.content.Context;
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
        boolean state = isState(method);
        String event = state ? "save_data" : "submit_task";
        String safeArgs;
        if (state) {
            safeArgs = ReadOnlyTaskMonitor.summarizeSaveState(webView, args);
            if (safeArgs.contains("unparsed")) dumpRawState(webView, method, callId, arg0(args));
        } else {
            safeArgs = "parameters=" + (args == null ? 0 : args.length) + ",content=<omitted>";
            dumpRawSubmit(webView, method, callId, args);
        }
        LogSink.event(event, "phase", "call", "method", method, "call_id", callId,
                "args", safeArgs);
    }

    static void result(WebView webView, String method, String callId, long elapsed,
                       Object result, Throwable error) {
        if (!observes(method)) return;
        String event = isState(method) ? "save_data" : "submit_task";
        String resultSummary;
        if (error != null) {
            resultSummary = "<error>";
        } else if ("loadData".equals(method)) {
            resultSummary = ReadOnlyTaskMonitor.summarizeState(webView, result);
            if (resultSummary.contains("unparsed")) dumpRawState(webView, method, callId, result);
        } else {
            resultSummary = LogSink.summarize(result, 2048);
        }
        LogSink.event(event, "phase", "result", "method", method, "call_id", callId,
                "elapsed_ms", Long.toString(elapsed),
                "result", resultSummary,
                "error", error == null ? "" : LogSink.limit(LogSink.redact(error.toString()), 512));
    }

    /**
     * When the raw debug switch is on, persists a state payload that the read-only parser could not
     * decode straight to the target-private log file (never Logcat), so its real shape can be read
     * back once and the parser fixed. Sensitive; the file must be deleted after use.
     */
    private static void dumpRawState(WebView webView, String method, String callId, Object payload) {
        try {
            Context context = webView == null ? null : webView.getContext();
            if (context == null || !Config.enabled(context, Config.LOG_RAW_BRIDGE)) return;
            LogSink.rawEvent("state_raw_payload", "method", method, "call_id", callId,
                    "class", payload == null ? "null" : payload.getClass().getName(),
                    "length", payload == null ? "0" : Integer.toString(String.valueOf(payload).length()),
                    "payload", payload == null ? "" : String.valueOf(payload));
        } catch (Throwable ignored) {
            // Raw diagnostics must never affect the target app.
        }
    }

    /**
     * When the raw debug switch is on, persists the full submit-call arguments to the target-private
     * log file (never Logcat) so the server-bound time fields can be inventoried once and then
     * erased. Sensitive; the file must be deleted after use.
     */
    private static void dumpRawSubmit(WebView webView, String method, String callId, Object[] args) {
        try {
            Context context = webView == null ? null : webView.getContext();
            if (context == null || !Config.enabled(context, Config.LOG_RAW_BRIDGE)) return;
            Object payload = arg0(args);
            LogSink.rawEvent("submit_task_raw_args", "method", method, "call_id", callId,
                    "class", payload == null ? "null" : payload.getClass().getName(),
                    "length", payload == null ? "0" : Integer.toString(String.valueOf(payload).length()),
                    "args", payload == null ? "" : String.valueOf(payload));
        } catch (Throwable ignored) {
            // Raw diagnostics must never affect the target app.
        }
    }

    private static boolean isState(String method) {
        return "saveData".equals(method) || "loadData".equals(method);
    }

    private static Object arg0(Object[] args) {
        return args == null || args.length == 0 ? null : args[0];
    }
}
