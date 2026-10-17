package io.github.adkimsm.up366.hook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only, in-memory registry of the human-readable question-set names the target app already
 * receives over its JavaScript bridge. It stores a {@code bookId -> bookName} lookup (from
 * {@code openWebView}/{@code getBookInfo}/{@code loadTaskInfo}) and the most recent
 * {@code updateNavigationTitle} value the app displayed.
 *
 * <p>Names are app metadata, never question/answer text, and are kept only in memory. Nothing is
 * written to {@link AnswerStore} or Logcat. Capture is best-effort and never changes bridge
 * arguments or results.
 */
final class QuestionSetNameRegistry {
    private static final Pattern PAIR = Pattern.compile("\"(bookName|bookId)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Map<String, String> BOOK_NAMES = new ConcurrentHashMap<>();
    private static volatile String lastNavigationTitle = "";

    private QuestionSetNameRegistry() {}

    /** Inspects one bridge call's arguments (or result) without logging or mutating them. */
    static void capture(String method, Object[] args) {
        if (method == null) return;
        if ("updateNavigationTitle".equals(method)) {
            String title = firstString(args);
            if (title.length() > 0) lastNavigationTitle = title;
            return;
        }
        if (!carriesNames(method) || args == null) return;
        for (Object arg : args) scan(stringify(arg));
    }

    static String bookName(String bookId) {
        if (bookId == null || bookId.length() == 0) return "";
        String name = BOOK_NAMES.get(bookId);
        return name == null ? "" : name;
    }

    static String lastNavigationTitle() {
        return lastNavigationTitle;
    }

    static int knownBookCount() {
        return BOOK_NAMES.size();
    }

    /** Test hook: clears captured state. */
    static synchronized void reset() {
        BOOK_NAMES.clear();
        lastNavigationTitle = "";
    }

    private static boolean carriesNames(String method) {
        return "openWebView".equals(method) || "getBookInfo".equals(method)
                || "loadTaskInfo".equals(method) || "loadChapterInfo".equals(method)
                || "getTaskList".equals(method);
    }

    private static void scan(String text) {
        if (text == null || text.length() == 0) return;
        if (text.indexOf("bookName") < 0 && text.indexOf("bookId") < 0) return;
        Matcher matcher = PAIR.matcher(text);
        String pendingName = null;
        String pendingId = null;
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            if (value.length() == 0) continue;
            if ("bookName".equals(key)) {
                if (pendingId != null) {
                    BOOK_NAMES.put(pendingId, value);
                    pendingId = null;
                } else {
                    pendingName = value;
                }
            } else if (pendingName != null) {
                BOOK_NAMES.put(value, pendingName);
                pendingName = null;
            } else {
                pendingId = value;
            }
        }
    }

    private static String firstString(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return "";
        String value = String.valueOf(args[0]);
        return value == null ? "" : value.trim();
    }

    private static String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence) return value.toString();
        try {
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
