package io.github.adkimsm.up366.hook;

final class UploadStatusRecorder {
    private UploadStatusRecorder() {}

    static boolean observes(String method) {
        return "getTasksUploadStatus".equals(method)
                || "manualUploadTask".equals(method)
                || "refreshJobList".equals(method)
                || "taskUpload".equals(method)
                || "registerEvent".equals(method)
                || "fileUpload".equals(method)
                || "fileDownload".equals(method);
    }

    static void call(String method, Object[] args, String callId) {
        if (!observes(method)) return;
        if ("registerEvent".equals(method)) {
            String eventName = args == null || args.length == 0
                    ? "" : LogSink.limit(LogSink.redact(String.valueOf(args[0])), 128);
            LogSink.event("upload_status", "phase", "event_registered", "method", method,
                    "call_id", callId, "event_name", eventName);
            return;
        }
        LogSink.event("upload_status", "phase", "call", "method", method,
                "call_id", callId, "argument_count", Integer.toString(args == null ? 0 : args.length),
                "content", "<omitted>");
    }

    static void result(String method, String callId, long elapsed, Object result, Throwable error) {
        if (!observes(method)) return;
        LogSink.event("upload_status", "phase", "result", "method", method,
                "call_id", callId, "elapsed_ms", Long.toString(elapsed),
                "result", error == null ? LogSink.summarize(result, 2048) : "<error>",
                "error", error == null ? "" : LogSink.limit(LogSink.redact(error.toString()), 512));
    }
}
