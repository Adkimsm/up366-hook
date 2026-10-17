package io.github.adkimsm.up366.hook;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One read-only answer observation, keyed by the full index key so duplicate {@code question_id}s
 * across packages, chapters or versions never collide.
 *
 * <p>{@link #answerValue} is the deliberate answer store and therefore never flows through
 * {@link LogSink}; runtime log lines only carry counts, lengths and hashes.
 */
final class AnswerRecord {
    static final String SOURCE_LOCAL = "local_question_data";
    static final String SOURCE_BRIDGE = "android_bridge_response";
    static final String SOURCE_DESKTOP = "desktop_reference_only";

    static final String FORMAT_SINGLE_CHOICE = "single_choice";
    static final String FORMAT_MULTI_CHOICE = "multi_choice";
    static final String FORMAT_FILL_BLANK = "fill_blank";
    static final String FORMAT_ORDERING = "ordering";
    static final String FORMAT_COMPOSITE = "composite";
    static final String FORMAT_UNKNOWN = "unknown";

    static final String CONFIDENCE_CONFIRMED = "confirmed";
    static final String CONFIDENCE_UNKNOWN = "unknown";

    final String packageVersion;
    final String bookId;
    final String chapterId;
    final String pageId;
    final String questionId;
    final String parentQuestionId;
    final int questionType;
    final String answerValue;
    final String answerFormat;
    final String sourceType;
    final String sourcePath;
    final String confidence;
    final String missingReason;
    final long observedAt;

    AnswerRecord(String packageVersion, String bookId, String chapterId, String pageId,
                 String questionId, String parentQuestionId, int questionType,
                 String answerValue, String answerFormat, String sourceType, String sourcePath,
                 String confidence, String missingReason, long observedAt) {
        this.packageVersion = safe(packageVersion);
        this.bookId = safe(bookId);
        this.chapterId = safe(chapterId);
        this.pageId = safe(pageId);
        this.questionId = safe(questionId);
        this.parentQuestionId = safe(parentQuestionId);
        this.questionType = questionType;
        this.answerValue = safe(answerValue);
        this.answerFormat = safe(answerFormat);
        this.sourceType = safe(sourceType);
        this.sourcePath = safe(sourcePath);
        this.confidence = safe(confidence);
        this.missingReason = safe(missingReason);
        this.observedAt = observedAt;
    }

    static String key(String packageVersion, String bookId, String chapterId,
                      String pageId, String questionId) {
        return safe(packageVersion) + '|' + safe(bookId) + '|' + safe(chapterId)
                + '|' + safe(pageId) + '|' + safe(questionId);
    }

    String indexKey() {
        return key(packageVersion, bookId, chapterId, pageId, questionId);
    }

    boolean hasAnswer() {
        return answerValue.length() > 0;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("packageVersion", packageVersion);
        json.put("bookId", bookId);
        json.put("chapterId", chapterId);
        json.put("pageId", pageId);
        json.put("questionId", questionId);
        json.put("parentQuestionId", parentQuestionId);
        json.put("questionType", questionType);
        json.put("answerValue", answerValue);
        json.put("answerFormat", answerFormat);
        json.put("sourceType", sourceType);
        json.put("sourcePath", sourcePath);
        json.put("confidence", confidence);
        json.put("missingReason", missingReason);
        json.put("observedAt", observedAt);
        return json;
    }

    static AnswerRecord fromJson(JSONObject json) {
        if (json == null) return null;
        String questionId = json.optString("questionId", "");
        if (questionId.length() == 0) return null;
        return new AnswerRecord(json.optString("packageVersion", ""),
                json.optString("bookId", ""), json.optString("chapterId", ""),
                json.optString("pageId", ""), questionId, json.optString("parentQuestionId", ""),
                json.optInt("questionType", -1), json.optString("answerValue", ""),
                json.optString("answerFormat", FORMAT_UNKNOWN), json.optString("sourceType", ""),
                json.optString("sourcePath", ""), json.optString("confidence", CONFIDENCE_UNKNOWN),
                json.optString("missingReason", ""), json.optLong("observedAt", 0L));
    }

    static JSONArray toJsonArray(List<AnswerRecord> records) {
        JSONArray array = new JSONArray();
        if (records == null) return array;
        for (AnswerRecord record : records) {
            if (record == null) continue;
            try {
                array.put(record.toJson());
            } catch (JSONException ignored) {
                // A single malformed record must not drop the whole batch.
            }
        }
        return array;
    }

    static List<AnswerRecord> fromJsonArray(String json) {
        if (json == null || json.length() == 0) return Collections.emptyList();
        try {
            JSONArray array = new JSONArray(json);
            List<AnswerRecord> records = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                AnswerRecord record = fromJson(array.optJSONObject(i));
                if (record != null) records.add(record);
            }
            return records;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
