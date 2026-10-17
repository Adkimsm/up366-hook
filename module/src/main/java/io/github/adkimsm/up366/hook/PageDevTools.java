package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the read-only developer-tool scripts against the WebView that is actually on screen.
 *
 * <p>The target app preloads several practice pages, so "the last page that finished loading" is
 * often not the page the user is looking at. This class walks the current Activity's view tree,
 * collects every WebView, scores each one with a tiny read-only locate script and runs the requested
 * tool on the best match. It never clicks, mutates the page or calls a business bridge method.
 */
final class PageDevTools {
    interface Callback {
        void onResult(String json);
    }

    /** Delivers the WebView that currently shows content, with the candidate score summary. */
    interface TargetCallback {
        void onTarget(WebView webView, List<String> summaries);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_CANDIDATES = 8;
    private static final List<WeakReference<WebView>> TRACKED =
            Collections.synchronizedList(new ArrayList<>());

    private PageDevTools() {}

    /** Fallback registry for pages whose Activity is not reachable from the panel. */
    static void register(WebView webView, String url) {
        if (webView == null || url == null || !url.startsWith("file://")) return;
        synchronized (TRACKED) {
            for (WeakReference<WebView> ref : TRACKED) {
                if (ref.get() == webView) return;
            }
            TRACKED.add(new WeakReference<>(webView));
            while (TRACKED.size() > MAX_CANDIDATES) TRACKED.remove(0);
        }
    }

    static void outline(Activity activity, String selector, Callback callback) {
        run(activity, PageDevToolsScripts.outline(selector), callback);
    }

    static void styles(Activity activity, String selector, Callback callback) {
        run(activity, PageDevToolsScripts.styles(selector), callback);
    }

    static void gateOverview(Activity activity, Callback callback) {
        run(activity, PageDevToolsScripts.gateOverview(), callback);
    }

    private static void run(final Activity activity, final String script, final Callback callback) {
        resolveTarget(activity, (webView, summaries) -> {
            if (webView == null) {
                deliver(callback, errorWithCandidates("no_target", summaries));
                return;
            }
            runOn(webView, script, summaries, callback);
        });
    }

    /**
     * Scores every WebView of the current Activity (plus the tracked fallback) and reports the one
     * that shows the most content. Runs entirely on the UI thread; the callback may receive null.
     */
    static void resolveTarget(final Activity activity, final TargetCallback callback) {
        MAIN.post(() -> {
            List<WebView> candidates = collect(activity);
            if (candidates.isEmpty()) {
                LogSink.event("devtools", "status", "no_webview");
                callback.onTarget(null, new ArrayList<>());
                return;
            }
            final List<String> summaries = new ArrayList<>();
            final int[] bestScore = {Integer.MIN_VALUE};
            final WebView[] best = {null};
            locate(candidates, 0, summaries, bestScore, best,
                    () -> callback.onTarget(best[0], summaries));
        });
    }

    /** Calls a WebView method on the UI thread; never throws outwards. */
    private static void locate(final List<WebView> candidates, final int index,
                               final List<String> summaries, final int[] bestScore,
                               final WebView[] best, final Runnable done) {
        if (index >= candidates.size()) {
            done.run();
            return;
        }
        final WebView webView = candidates.get(index);
        try {
            webView.evaluateJavascript(PageDevToolsScripts.locate(), value -> {
                try {
                    JSONObject located = new JSONObject(unwrapJson(value));
                    int score = score(located);
                    summaries.add((index + 1) + ". " + shortUrl(located.optString("href"))
                            + " score=" + score);
                    if (score > bestScore[0]) {
                        bestScore[0] = score;
                        best[0] = webView;
                    }
                } catch (Throwable error) {
                    summaries.add((index + 1) + ". <unparsed>");
                }
                locate(candidates, index + 1, summaries, bestScore, best, done);
            });
        } catch (Throwable error) {
            summaries.add((index + 1) + ". <error>");
            locate(candidates, index + 1, summaries, bestScore, best, done);
        }
    }

    private static void runOn(final WebView webView, final String script,
                              final List<String> summaries, final Callback callback) {
        try {
            webView.evaluateJavascript(script, value ->
                    deliver(callback, enrich(unwrapJson(value), webView, summaries)));
        } catch (Throwable error) {
            LogSink.error("devtools_evaluate", error);
            deliver(callback, errorWithCandidates("evaluate_failed", summaries));
        }
    }

    /** Weighs what a page actually shows, preferring local practice pages and entered pages. */
    private static int score(JSONObject located) {
        int score = 0;
        score += 20 * located.optInt("qst");
        score += 20 * located.optInt("opts");
        score += 10 * located.optInt("sel");
        score += 20 * located.optInt("card");
        score += 10 * located.optInt("audio");
        score += 5 * located.optInt("slides");
        score += Math.min(Math.max(located.optInt("bodyLen"), 0), 20000) / 200;
        if (located.optBoolean("enterView")) score += 500;
        if (located.optString("href").startsWith("file://")) score += 1000;
        return score;
    }

    /** Adds the chosen target and all candidate scores to the tool result. */
    private static String enrich(String json, WebView webView, List<String> summaries) {
        try {
            JSONObject object = new JSONObject(json);
            String url = webView.getUrl();
            object.put("target", url == null ? "<unknown>" : LogSink.limit(url, 300));
            object.put("candidates", new JSONArray(summaries));
            return object.toString();
        } catch (Throwable ignored) {
            return json;
        }
    }

    private static String errorWithCandidates(String error, List<String> summaries) {
        try {
            JSONObject object = new JSONObject();
            object.put("error", error);
            object.put("candidates", new JSONArray(summaries));
            return object.toString();
        } catch (Throwable ignored) {
            return "{\"error\":\"" + error + "\"}";
        }
    }

    /** Every WebView in the current Activity plus the fallback registry, de-duplicated. */
    private static List<WebView> collect(Activity activity) {
        List<WebView> result = new ArrayList<>(WebViewFinder.collect(activity));
        synchronized (TRACKED) {
            for (WeakReference<WebView> ref : TRACKED) {
                WebView webView = ref.get();
                if (webView != null && !result.contains(webView) && result.size() < MAX_CANDIDATES) {
                    result.add(webView);
                }
            }
        }
        return result;
    }

    private static String shortUrl(String url) {
        if (url == null || url.isEmpty()) return "<empty>";
        return url.length() <= 60 ? url : "..." + url.substring(url.length() - 57);
    }

    /** {@code evaluateJavascript} returns a JSON string literal; unwrap it back to raw JSON text. */
    static String unwrapJson(String value) {
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
