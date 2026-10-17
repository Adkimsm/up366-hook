package io.github.adkimsm.up366.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rewrites the time fields of the page's submit payloads onto a {@link TimelinePlanner} schedule.
 * Pure JSON handling (no Android or bridge types) so it is unit-testable. Every method returns the
 * original text unchanged when parsing fails or nothing matched; non-time fields are preserved.
 */
final class SubmitTimeRewriter {
    /** Supplies the planned times; the disguise wires it to the planner for the current task. */
    interface TimeSource {
        long timestampFor(String questionId);
        long secondsFor(String questionId);
        long startMs();
        long endMs();
        long totalSeconds();
    }

    private static final Set<String> EPOCH_KEYS = new HashSet<>(Arrays.asList(
            "timestamp", "time", "submittime", "starttime", "endtime", "submit_time", "start_time",
            "end_time", "usetime", "listentime", "costtime", "staytime", "answer_time"));
    private static final Set<String> SECOND_KEYS = new HashSet<>(Arrays.asList(
            "seconds", "useseconds", "listenseconds", "use_seconds", "listen_seconds"));

    /** Values below this look like durations, not epoch milliseconds. */
    private static final long EPOCH_FLOOR_MS = 1_000_000_000_000L;
    private static final long SECOND_CEILING = 100_000L;

    private SubmitTimeRewriter() {}

    /** Rewrites {@code timestamp} inside {@code pages.*.quesEleAttr.*[].elementAttr}. */
    static String rewriteSaveState(String json, TimeSource source) {
        if (json == null || json.isEmpty() || source == null || !json.contains("quesEleAttr")) {
            return json;
        }
        try {
            JSONObject state = new JSONObject(json);
            boolean changed = false;
            Iterator<String> pages = state.keys();
            while (pages.hasNext()) {
                JSONObject page = state.optJSONObject(pages.next());
                if (page == null) continue;
                JSONObject attributes = page.optJSONObject("quesEleAttr");
                if (attributes == null) continue;
                Iterator<String> ids = attributes.keys();
                while (ids.hasNext()) {
                    String questionId = ids.next();
                    JSONArray entries = attributes.optJSONArray(questionId);
                    if (entries == null) continue;
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject entry = entries.optJSONObject(i);
                        if (entry == null) continue;
                        JSONObject attr = entry.optJSONObject("elementAttr");
                        if (attr == null || attr.optLong("timestamp", 0L) <= 0L) continue;
                        attr.put("timestamp", source.timestampFor(questionId));
                        changed = true;
                    }
                }
            }
            return changed ? state.toString() : json;
        } catch (Throwable ignored) {
            return json;
        }
    }

    /** Rewrites {@code timestamp} in every entry of a {@code submitEleAttr} payload. */
    static String rewriteElementAttrs(String json, TimeSource source) {
        if (json == null || json.isEmpty() || source == null || !json.contains("elementAttr")) {
            return json;
        }
        try {
            JSONArray array = new JSONArray(json);
            boolean changed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) continue;
                JSONObject attr = entry.optJSONObject("elementAttr");
                if (attr == null || attr.optLong("timestamp", 0L) <= 0L) continue;
                attr.put("timestamp", source.timestampFor(attr.optString("question_id", "")));
                changed = true;
            }
            return changed ? array.toString() : json;
        } catch (Throwable ignored) {
            return json;
        }
    }

    /**
     * Walks any submit JSON and rewrites recognizable time values: epoch-millisecond fields onto
     * the timeline, {@code seconds}-style durations onto the planned totals. Unknown shapes and
     * unparsable payloads pass through unchanged.
     */
    static String rewriteGeneric(String json, TimeSource source) {
        if (json == null || json.isEmpty() || source == null) return json;
        char first = json.charAt(0);
        if (first != '{' && first != '[') return json;
        try {
            if (first == '{') {
                JSONObject object = new JSONObject(json);
                return walkObject(object, source) ? object.toString() : json;
            }
            JSONArray array = new JSONArray(json);
            return walkArray(array, source) ? array.toString() : json;
        } catch (Throwable ignored) {
            return json;
        }
    }

    private static boolean walkObject(JSONObject object, TimeSource source) {
        List<String> names = new ArrayList<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) names.add(keys.next());
        String questionId = questionIdOf(object);
        boolean changed = false;
        for (String name : names) {
            Object value = object.opt(name);
            String lower = name.toLowerCase(Locale.US);
            try {
                if (value instanceof Number && EPOCH_KEYS.contains(lower)
                        && Math.abs(((Number) value).longValue()) >= EPOCH_FLOOR_MS) {
                    object.put(name, epochFor(lower, questionId, source));
                    changed = true;
                } else if (value instanceof Number && SECOND_KEYS.contains(lower)
                        && ((Number) value).longValue() >= 0L
                        && ((Number) value).longValue() < SECOND_CEILING) {
                    object.put(name, questionId.isEmpty()
                            ? source.totalSeconds() : source.secondsFor(questionId));
                    changed = true;
                } else if (value instanceof JSONObject) {
                    changed |= walkObject((JSONObject) value, source);
                } else if (value instanceof JSONArray) {
                    changed |= walkArray((JSONArray) value, source);
                }
            } catch (Throwable ignored) {
                // One unreadable field must not discard the rest of the rewrite.
            }
        }
        return changed;
    }

    private static boolean walkArray(JSONArray array, TimeSource source) {
        boolean changed = false;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                changed |= walkObject((JSONObject) value, source);
            } else if (value instanceof JSONArray) {
                changed |= walkArray((JSONArray) value, source);
            }
        }
        return changed;
    }

    private static long epochFor(String key, String questionId, TimeSource source) {
        if (key.contains("start")) return source.startMs();
        if (key.contains("end") || key.contains("submit")) return source.endMs();
        return questionId.isEmpty() ? source.endMs() : source.timestampFor(questionId);
    }

    private static String questionIdOf(JSONObject object) {
        String id = object.optString("question_id", "");
        if (id.isEmpty()) id = object.optString("questionId", "");
        return id;
    }
}
