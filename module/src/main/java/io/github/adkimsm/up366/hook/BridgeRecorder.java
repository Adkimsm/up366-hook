package io.github.adkimsm.up366.hook;

import android.webkit.WebView;
import android.webkit.JavascriptInterface;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Observes both V6 and V8 JavaScript bridge implementations without changing results. */
final class BridgeRecorder {
    private static final String EXTRA = "up366.bridge.call";
    private static final long ASYNC_TTL_MS = 30_000L;
    private static final Pattern ASYNC_RESULT = Pattern.compile(
            "_onAsyncFuncResult\\s*\\(\\s*\\{\\s*['\\\"]?key['\\\"]?\\s*:\\s*['\\\"]([^'\\\"]+)['\\\"]"
    );
    private static final AtomicLong IDS = new AtomicLong();
    private static final Handler TIMEOUTS = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static final Map<Object, WebView> WEBVIEWS =
            Collections.synchronizedMap(new WeakHashMap<Object, WebView>());
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    private BridgeRecorder() {}

    static void register(Object bridge, WebView webView) {
        if (bridge == null || webView == null) return;
        WEBVIEWS.put(bridge, webView);
        Class<?> type = bridge.getClass();
        synchronized (HOOKED) {
            if (!HOOKED.add(type)) return;
        }
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            if (method.getAnnotation(JavascriptInterface.class) == null
                    && !isKnown(method.getName())) continue;
            try {
                XposedBridge.hookMethod(method, new MethodHook(method.getName()));
            } catch (Throwable error) {
                LogSink.error("hook_bridge_method", error);
            }
        }
    }

    static void inspectJavascript(WebView webView, String script) {
        if (webView == null || script == null || !Config.enabled(webView.getContext(), Config.BRIDGE)) return;
        String inspected = script.startsWith("javascript:")
                ? script.substring("javascript:".length()) : script;
        if (inspected.contains("taskUpload") || inspected.contains("fileUpload")
                || inspected.contains("fileDownload")) {
            LogSink.event("upload_status", "phase", "native_event_callback", "webview_id", webViewId(webView),
                    "event", inspected.contains("taskUpload") ? "taskUpload"
                            : inspected.contains("fileUpload") ? "fileUpload" : "fileDownload",
                    "script_length", Integer.toString(inspected.length()));
        }
        if (inspected.contains("_callJsFuncWithResult")) {
            LogSink.event("bridge_result", "source", "registered_event_callback", "webview_id", webViewId(webView),
                    "script_length", Integer.toString(inspected.length()));
        }
        if (inspected.contains("__onResponseJSResult")) {
            LogSink.event("bridge_result", "source", "page_callback_script", "webview_id", webViewId(webView),
                    "script_length", Integer.toString(inspected.length()));
        }
        if (!inspected.contains("_onAsyncFuncResult")) return;
        Matcher matcher = ASYNC_RESULT.matcher(inspected);
        if (!matcher.find()) {
            LogSink.event("bridge_result", "source", "orphan_async_bridge", "webview_id", webViewId(webView),
                    "reason", "callback_key_unreadable", "script_length", Integer.toString(inspected.length()));
            return;
        }
        String key = matcher.group(1);
        Pending pending = PENDING.remove(key);
        LogSink.event("bridge_result",
                "source", pending == null ? "orphan_async_bridge" : "async_bridge_callback",
                "call_id", pending == null ? "" : pending.callId,
                "method", pending == null ? "" : pending.method,
                "callback_key", key,
                "elapsed_ms", pending == null ? "" : Long.toString(System.currentTimeMillis() - pending.started),
                "script_length", Integer.toString(inspected.length()));
    }

    static boolean shouldObserveJavascript(String script) {
        if (script == null) return false;
        return script.contains("_callJsFuncWithResult")
                || script.contains("__onResponseJSResult")
                || script.contains("_onAsyncFuncResult")
                || script.contains("taskUpload")
                || script.contains("fileUpload")
                || script.contains("fileDownload")
                || script.contains("recordStateChange");
    }

    static void recordJavascriptResult(WebView webView, String script, Object value) {
        if (webView == null || !shouldObserveJavascript(script)
                || !Config.enabled(webView.getContext(), Config.BRIDGE)) return;
        String source = script.contains("_callJsFuncWithResult")
                ? "registered_event_callback" : script.contains("__onResponseJSResult")
                ? "page_callback_script" : script.contains("_onAsyncFuncResult")
                ? "async_bridge_callback" : "javascript_callback";
        LogSink.event("bridge_result_callback", "source", source,
                "webview_id", webViewId(webView),
                "script_length", Integer.toString(script.length()),
                "result", LogSink.summarize(value, 2048));
    }

    private static boolean isKnown(String name) {
        return "registerEvent".equals(name) || "__onResponseJSResult".equals(name)
                || "getClientInfo".equals(name);
    }

    private static boolean isSensitive(String method) {
        return "request".equals(method) || "requestDataFromServer".equals(method)
                || "searchDB".equals(method) || "getUserInfo".equals(method)
                || "getClientInfo".equals(method) || "getData".equals(method)
                || "postMessage".equals(method) || "writeFile".equals(method)
                || "uploadFile".equals(method) || "downloadFile".equals(method);
    }

    private static final class MethodHook extends XC_MethodHook {
        private final String method;

        MethodHook(String method) {
            this.method = method;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            WebView webView = WEBVIEWS.get(param.thisObject);
            if (webView == null || !Config.enabled(webView.getContext(), Config.BRIDGE)) return;
            String callId = "b" + IDS.incrementAndGet();
            long started = System.currentTimeMillis();
            Call call = new Call(callId, started, webView);
            param.setObjectExtra(EXTRA, call);
            TaskContext.onBridgeCall(webView, method, param.args);
            if ("__onResponseJSResult".equals(method)) {
                String uniqueId = String.valueOf(first(param.args));
                Pending pending = PENDING.remove(uniqueId);
                LogSink.event("bridge_result", "source", "page_callback", "call_id", callId,
                        "related_call_id", pending == null ? "" : pending.callId,
                        "unique_id", uniqueId, "data", LogSink.summarize(last(param.args), 2048));
            } else {
                String argsSummary = SubmitFlowRecorder.observes(method)
                        ? "<captured by read-only monitor>" : UploadStatusRecorder.observes(method)
                        ? "<upload payload omitted>" : isSensitive(method)
                        ? "<payload omitted>" : LogSink.redactArgs(param.args);
                LogSink.event("bridge_call", "call_id", callId, "method", method,
                        "webview_id", webViewId(webView), "args", argsSummary);
            }
            SubmitFlowRecorder.call(webView, method, param.args, callId);
            UploadStatusRecorder.call(method, param.args, callId);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Call call = (Call) param.getObjectExtra(EXTRA);
            if (call == null) return;
            long elapsed = Math.max(0L, System.currentTimeMillis() - call.started);
            Throwable error = param.hasThrowable() ? param.getThrowable() : null;
            Object result = error == null ? param.getResult() : null;
            if (error == null) {
                TaskContext.onBridgeResult(call.webView, method, result);
            }
            if (error == null && result instanceof String
                    && ((String) result).startsWith("async_callback_key_")) {
                final String key = String.valueOf(result);
                final Pending pending = new Pending(call.callId, method, call.started,
                        System.currentTimeMillis());
                PENDING.put(key, pending);
                TIMEOUTS.postDelayed(() -> timeout(key, pending), ASYNC_TTL_MS);
                LogSink.event("bridge_result", "source", "async_bridge", "call_id", call.callId,
                        "method", method, "callback_key", String.valueOf(result),
                        "elapsed_ms", Long.toString(elapsed));
            } else if (!"__onResponseJSResult".equals(method)) {
                LogSink.event("bridge_result", "source", "sync_java", "call_id", call.callId,
                        "method", method, "elapsed_ms", Long.toString(elapsed),
                        "result", error == null ? LogSink.summarize(result, 2048) : "<error>",
                        "error", error == null ? "" : LogSink.limit(LogSink.redact(error.toString()), 512));
            }
            SubmitFlowRecorder.result(call.webView, method, call.callId, elapsed, result, error);
            UploadStatusRecorder.result(method, call.callId, elapsed, result, error);
        }
    }

    private static void timeout(String key, Pending expected) {
        if (PENDING.remove(key, expected)) {
            LogSink.event("bridge_result", "source", "async_bridge_timeout",
                    "call_id", expected.callId, "method", expected.method,
                    "callback_key", key, "elapsed_ms",
                    Long.toString(System.currentTimeMillis() - expected.started));
        }
    }

    private static String first(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return "";
        return LogSink.limit(LogSink.redact(String.valueOf(args[0])), 128);
    }

    private static Object last(Object[] args) {
        return args == null || args.length == 0 ? null : args[args.length - 1];
    }

    private static String webViewId(WebView webView) {
        return Integer.toHexString(System.identityHashCode(webView));
    }

    private static final class Call {
        final String callId;
        final long started;
        final WebView webView;

        Call(String callId, long started, WebView webView) {
            this.callId = callId;
            this.started = started;
            this.webView = webView;
        }
    }

    private static final class Pending {
        final String callId;
        final String method;
        final long started;
        final long created;

        Pending(String callId, String method, long started, long created) {
            this.callId = callId;
            this.method = method;
            this.started = started;
            this.created = created;
        }
    }
}
