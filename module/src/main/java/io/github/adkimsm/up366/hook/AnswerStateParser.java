package io.github.adkimsm.up366.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Parses saveData shape but deliberately discards answer-bearing values. */
final class AnswerStateParser {
    private AnswerStateParser() {}

    static String summarize(Object[] args, Map<String, QuestionModel> questions) {
        if (args == null || args.length == 0) return "args=0";
        Object value = args[0];
        if (!(value instanceof String) && !(value instanceof JSONObject) && !(value instanceof JSONArray)) {
            return "state_type=" + type(value) + ",state_present=" + (value != null);
        }
        try {
            if (value instanceof JSONObject) return summarizeObject((JSONObject) value, questions);
            if (value instanceof JSONArray) {
                return "state_type=array,length=" + ((JSONArray) value).length();
            }
            String text = String.valueOf(value).trim();
            if (text.startsWith("[")) return "state_type=array,length=" + text.length();
            return summarizeObject(new JSONObject(text), questions);
        } catch (Throwable error) {
            String text = String.valueOf(value);
            String trimmed = text.trim();
            return "state_type=unparsed,state_present=true"
                    + ",class=" + value.getClass().getSimpleName()
                    + ",length=" + text.length()
                    + ",starts_with=" + (trimmed.isEmpty() ? "empty"
                    : Character.toString(trimmed.charAt(0)));
        }
    }

    private static String summarizeObject(JSONObject state, Map<String, QuestionModel> questions) {
        int pageCount = 0;
        int questionCount = 0;
        int stateEntries = 0;
        Set<String> questionIds = new HashSet<>();
        Set<String> matchedIds = new HashSet<>();
        Set<String> unmatchedIds = new HashSet<>();
        Iterator<String> pages = state.keys();
        while (pages.hasNext()) {
            String pageId = pages.next();
            pageCount++;
            JSONObject page = state.optJSONObject(pageId);
            if (page == null) continue;
            stateEntries++;
            JSONObject attributes = page.optJSONObject("quesEleAttr");
            if (attributes == null) continue;
            Iterator<String> ids = attributes.keys();
            while (ids.hasNext()) {
                String questionId = ids.next();
                questionIds.add(questionId);
                if (questions != null && questions.containsKey(questionId)) matchedIds.add(questionId);
                else unmatchedIds.add(questionId);
                JSONArray entries = attributes.optJSONArray(questionId);
                if (entries != null) questionCount += entries.length();
            }
        }
        return "state_type=save_data,page_count=" + pageCount
                + ",state_entries=" + stateEntries
                + ",question_groups=" + questionIds.size()
                + ",question_entries=" + questionCount
                + ",matched_ids=" + matchedIds.size()
                + ",unmatched_ids=" + unmatchedIds.size();
    }

    private static String type(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONArray) return "array";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        return value.getClass().getSimpleName();
    }
}
