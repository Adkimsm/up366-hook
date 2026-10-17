package io.github.adkimsm.up366.hook;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * Runs the read-only developer-tool scripts against the most recently loaded {@code file://}
 * practice page and hands the JSON result back to the injected panel. It never clicks, mutates the
 * page or calls a business bridge method; it only evaluates the bounded read-only scripts from
 * {@link PageDevToolsScripts}.
 */
final class PageDevTools {
    interface Callback {
        void onResult(String json);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<WebView> current = new WeakReference<>(null);

    private PageDevTools() {}

    /** Remembers the latest local practice page; remote pages are ignored. */
    static void register(WebView webView, String url) {
        if (webView == null || url == null || !url.startsWith("file://")) return;
        current = new WeakReference<>(webView);
    }

    static void outline(String selector, Callback callback) {
        run(PageDevToolsScripts.outline(selector), "outline", callback);
    }

    static void styles(String selector, Callback callback) {
        run(PageDevToolsScripts.styles(selector), "styles", callback);
    }

    static void gateOverview(Callback callback) {
        run(PageDevToolsScripts.gateOverview(), "gate", callback);
    }

    private static void run(final String script, final String kind, final Callback callback) {
        final WebView webView = current.get();
        if (webView == null) {
            LogSink.event("devtools", "status", "no_webview", "tool", kind);
            deliver(callback, "{\"error\":\"no_webview\"}");
            return;
        }
        MAIN.post(() -> {
            try {
                webView.evaluateJavascript(script, value -> deliver(callback, unwrap(value)));
            } catch (Throwable error) {
                LogSink.error("devtools_" + kind, error);
                deliver(callback, "{\"error\":\"evaluate_failed\"}");
            }
        });
    }

    /** {@code evaluateJavascript} returns a JSON string literal; unwrap it back to raw JSON text. */
    private static String unwrap(String value) {
        if (value == null || "null".equals(value)) return "{\"error\":\"empty_result\"}";
        try {
            return new JSONObject("{\"v\":" + value + "}").getString("v");
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static void deliver(Callback callback, String json) {
        if (callback == null) return;
        MAIN.post(() -> {
            try {
                callback.onResult(json);
            } catch (Throwable error) {
                LogSink.error("devtools_callback", error);
            }
        });
    }
}
