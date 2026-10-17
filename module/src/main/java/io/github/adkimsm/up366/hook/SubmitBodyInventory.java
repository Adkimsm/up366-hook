package io.github.adkimsm.up366.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Builds a redacted field inventory of a captured submit body: key names plus time-like values,
 * never answers or scores. The native submit body carries {@code tasksJson} as a JSON string, so
 * nested JSON strings are parsed a couple of levels deep — that is where the real listen-time
 * fields live. Used by the {@code LOG_RAW_BRIDGE} debug capture to learn which time fields the
 * native layer really sends without storing the whole payload.
 */
final class SubmitBodyInventory {
    private static final int MAX_ARRAY_ITEMS = 20;
    private static final int MAX_NESTED_DEPTH = 2;

    private SubmitBodyInventory() {}

    static String of(String text) {
        if (text == null || text.isEmpty()) return "empty";
        char first = text.charAt(0);
        if (first != '{' && first != '[') return "non_json length=" + text.length();
        try {
            List<String> fields = new ArrayList<>();
            StringBuilder keys = new StringBuilder();
            if (first == '{') {
                JSONObject object = new JSONObject(text);
                collectTopLevelKeys(object, keys);
                walk(object, "root", fields, 0);
            } else {
                walk(new JSONArray(text), "root", fields, 0);
            }
            StringBuilder result = new StringBuilder();
            result.append("length=").append(text.length());
            if (keys.length() > 0) result.append(" keys=").append(keys);
            for (String field : fields) result.append(';').append(field);
            return LogSink.limit(result.toString(), 4000);
        } catch (Throwable ignored) {
            return "unparsed length=" + text.length();
        }
    }

    private static void collectTopLevelKeys(JSONObject object, StringBuilder out) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            if (out.length() > 0) out.append(',');
            out.append(keys.next());
        }
    }

    private static void walk(Object value, String path, List<String> out, int depth) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Object child = object.opt(name);
                String childPath = path + "." + name;
                if (child instanceof Number && isTimeLike(name)) {
                    out.add(childPath + "=" + child);
                } else if (child instanceof String && depth < MAX_NESTED_DEPTH
                        && looksLikeJson((String) child)) {
                    describeNested((String) child, childPath, out, depth + 1);
                } else {
                    walk(child, childPath, out, depth);
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && i < MAX_ARRAY_ITEMS; i++) {
                walk(array.opt(i), path + "[" + i + "]", out, depth);
            }
        }
    }

    /** Parses one string-encoded JSON payload (for example {@code tasksJson}) and inventories it. */
    private static void describeNested(String text, String path, List<String> out, int depth) {
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                JSONObject object = new JSONObject(trimmed);
                StringBuilder keys = new StringBuilder();
                collectTopLevelKeys(object, keys);
                if (keys.length() > 0) out.add(path + ".keys=" + keys);
                walk(object, path, out, depth);
            } else if (trimmed.startsWith("[")) {
                JSONArray array = new JSONArray(trimmed);
                out.add(path + ".items=" + array.length());
                walk(array, path, out, depth);
            }
        } catch (Throwable ignored) {
            // A nested payload we cannot read is simply not inventoried.
        }
    }

    private static boolean looksLikeJson(String text) {
        if (text == null || text.length() < 2) return false;
        char first = text.trim().charAt(0);
        return first == '{' || first == '[';
    }

    private static boolean isTimeLike(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.contains("time") || lower.contains("second") || "duration".equals(lower);
    }
}
