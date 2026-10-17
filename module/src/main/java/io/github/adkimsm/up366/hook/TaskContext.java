package io.github.adkimsm.up366.hook;

import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Read-only context assembled from page URLs and bridge arguments. */
final class TaskContext {
    private static final Pattern VALUE = Pattern.compile(
            "(?:^|[^A-Za-z0-9_])(?:[\\\"']?)(studyTaskId|bookId|taskId|courseId|chapterId|pageId)"
                    + "(?:[\\\"']?)[\\s=:]+(?:[\\\"']?)([^\\\"',}\\s]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FLIPBOOK = Pattern.compile(
            "/flipbook/([^/]+)(?:/([^/]+))?(?:/([^/]+))?", Pattern.CASE_INSENSITIVE);
    private static final Map<WebView, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<WebView, State>());

    private TaskContext() {}

    static void onPage(WebView webView, String url) {
        if (webView == null) return;
        State state = state(webView);
        boolean changed = !same(state.currentPagePath, url);
        state.currentPagePath = url;
        inferPath(state, url);
        if (changed) emit(webView, state, "page");
    }

    static void onBridgeCall(WebView webView, String method, Object[] args) {
        if (webView == null) return;
        State state = state(webView);
        boolean changed = false;
        if (args != null) {
            for (Object arg : args) changed |= update(state, arg);
        }
        if (changed) emit(webView, state, method);
    }

    static void onBridgeResult(WebView webView, String method, Object result) {
        if (webView == null || result == null) return;
        State state = state(webView);
        boolean changed = update(state, result);
        if (changed) emit(webView, state, method + "_result");
    }

    static String snapshot(WebView webView) {
        State state = STATES.get(webView);
        return state == null ? "" : state.toString();
    }

    private static State state(WebView webView) {
        synchronized (STATES) {
            State state = STATES.get(webView);
            if (state == null) {
                state = new State();
                STATES.put(webView, state);
            }
            return state;
        }
    }

    private static boolean update(State state, Object value) {
        boolean changed = false;
        if (value instanceof JSONObject) {
            return updateObject(state, (JSONObject) value, 0);
        }
        if (value instanceof JSONArray) {
            return updateArray(state, (JSONArray) value, 0);
        }
        String text = String.valueOf(value);
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                return updateObject(state, new JSONObject(trimmed), 0);
            } catch (Throwable ignored) {
                // Fall through to the conservative text/form parser.
            }
        } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                return updateArray(state, new JSONArray(trimmed), 0);
            } catch (Throwable ignored) {
                // Fall through to the conservative text/form parser.
            }
        }
        changed |= updateForm(state, text);
        Matcher matcher = VALUE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String key = matcher.group(1);
            String contextValue = LogSink.redactValue(matcher.group(2));
            if (contextValue.length() == 0 || "null".equalsIgnoreCase(contextValue)) continue;
            if ("bookId".equalsIgnoreCase(key)) changed |= set(state, "bookId", contextValue);
            else if ("taskId".equalsIgnoreCase(key)) changed |= set(state, "taskId", contextValue);
            else if ("studyTaskId".equalsIgnoreCase(key)) changed |= set(state, "studyTaskId", contextValue);
            else if ("courseId".equalsIgnoreCase(key)) changed |= set(state, "courseId", contextValue);
            else if ("chapterId".equalsIgnoreCase(key)) changed |= set(state, "chapterId", contextValue);
            else if ("pageId".equalsIgnoreCase(key)) changed |= set(state, "pageId", contextValue);
        }
        return changed;
    }

    private static boolean updateObject(State state, JSONObject object, int depth) {
        if (object == null || depth > 8) return false;
        boolean changed = false;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (isContextKey(key) && value != null && value != JSONObject.NULL) {
                changed |= setContextValue(state, key, String.valueOf(value));
            }
            if (value instanceof JSONObject) changed |= updateObject(state, (JSONObject) value, depth + 1);
            else if (value instanceof JSONArray) changed |= updateArray(state, (JSONArray) value, depth + 1);
        }
        return changed;
    }

    private static boolean updateArray(State state, JSONArray array, int depth) {
        if (array == null || depth > 8) return false;
        boolean changed = false;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) changed |= updateObject(state, (JSONObject) value, depth + 1);
            else if (value instanceof JSONArray) changed |= updateArray(state, (JSONArray) value, depth + 1);
        }
        return changed;
    }

    private static boolean updateForm(State state, String text) {
        if (text == null || text.indexOf('=') < 0 || text.length() > 65536) return false;
        boolean changed = false;
        for (String pair : text.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            try {
                String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8.name());
                if (isContextKey(key)) changed |= setContextValue(state, key, value);
            } catch (Throwable ignored) {
                // A malformed form field must not affect the target process.
            }
        }
        return changed;
    }

    private static boolean isContextKey(String key) {
        return "bookId".equalsIgnoreCase(key) || "taskId".equalsIgnoreCase(key)
                || "studyTaskId".equalsIgnoreCase(key) || "courseId".equalsIgnoreCase(key)
                || "chapterId".equalsIgnoreCase(key) || "pageId".equalsIgnoreCase(key);
    }

    private static boolean setContextValue(State state, String key, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() == 0 || "null".equalsIgnoreCase(clean)) return false;
        String normalized;
        if ("bookId".equalsIgnoreCase(key)) normalized = "bookId";
        else if ("taskId".equalsIgnoreCase(key)) normalized = "taskId";
        else if ("studyTaskId".equalsIgnoreCase(key)) normalized = "studyTaskId";
        else if ("courseId".equalsIgnoreCase(key)) normalized = "courseId";
        else if ("chapterId".equalsIgnoreCase(key)) normalized = "chapterId";
        else if ("pageId".equalsIgnoreCase(key)) normalized = "pageId";
        else return false;
        return set(state, normalized, LogSink.redactValue(clean));
    }

    private static void inferPath(State state, String url) {
        Matcher matcher = FLIPBOOK.matcher(url == null ? "" : url);
        if (!matcher.find()) return;
        set(state, "bookId", matcher.group(1));
        if (matcher.group(2) != null) set(state, "chapterId", matcher.group(2));
        if (matcher.group(3) != null) set(state, "pageId", matcher.group(3));
        int end = matcher.start(1) + matcher.group(1).length();
        state.questionPackagePath = url.substring(0, end);
    }

    private static boolean set(State state, String field, String value) {
        String old;
        if ("bookId".equals(field)) old = state.bookId;
        else if ("taskId".equals(field)) old = state.taskId;
        else if ("studyTaskId".equals(field)) old = state.studyTaskId;
        else if ("courseId".equals(field)) old = state.courseId;
        else if ("chapterId".equals(field)) old = state.chapterId;
        else if ("pageId".equals(field)) old = state.pageId;
        else return false;
        if (same(old, value)) return false;
        if ("bookId".equals(field)) state.bookId = value;
        else if ("taskId".equals(field)) state.taskId = value;
        else if ("studyTaskId".equals(field)) state.studyTaskId = value;
        else if ("courseId".equals(field)) state.courseId = value;
        else if ("chapterId".equals(field)) state.chapterId = value;
        else if ("pageId".equals(field)) state.pageId = value;
        return true;
    }

    private static void emit(WebView webView, State state, String source) {
        LogSink.event("task_context", "source", source, "webview_id",
                Integer.toHexString(System.identityHashCode(webView)),
                "context", state.toString());
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final class State {
        String bookId;
        String taskId;
        String studyTaskId;
        String courseId;
        String chapterId;
        String pageId;
        String questionPackagePath;
        String currentPagePath;

        @Override
        public String toString() {
            return "bookId=" + LogSink.safe(bookId)
                    + ",taskId=" + LogSink.safe(taskId)
                    + ",studyTaskId=" + LogSink.safe(studyTaskId)
                    + ",courseId=" + LogSink.safe(courseId)
                    + ",chapterId=" + LogSink.safe(chapterId)
                    + ",pageId=" + LogSink.safe(pageId)
                    + ",questionPackagePath=" + LogSink.safe(questionPackagePath)
                    + ",currentPagePath=" + LogSink.safe(currentPagePath);
        }
    }
}
