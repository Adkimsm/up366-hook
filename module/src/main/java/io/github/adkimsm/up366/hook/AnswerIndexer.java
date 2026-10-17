package io.github.adkimsm.up366.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code answer_text} from the already-downloaded question package and builds
 * {@link AnswerRecord}s. This path never touches the DOM, the JS bridge or the network, and never
 * guesses an answer: a missing field is recorded as a missing reason.
 */
final class AnswerIndexer {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_RECORDS = 5000;

    private AnswerIndexer() {}

    static Result index(File pageFile) {
        AnswerCoverageReport report = new AnswerCoverageReport();
        List<AnswerRecord> records = new ArrayList<>();
        if (pageFile == null || !pageFile.isFile()) return new Result("", "", "", "", records, report);

        File pageDirectory = pageFile.getParentFile();
        File bookDirectory = pageDirectory == null ? null
                : pageDirectory.getParentFile() == null ? null
                : pageDirectory.getParentFile().getParentFile();
        PackageIdentity identity = PackageIdentity.of(pageFile);
        String pageId = identity.pageId;
        String chapterId = identity.chapterId;
        String bookId = identity.bookId;
        String packageVersion = identity.packageVersion;

        Map<String, Integer> keyCounts = new HashMap<>();
        long observedAt = System.currentTimeMillis();
        List<File> files = QuestionDataParser.questionDataFiles(pageFile);
        for (File file : files) {
            if (records.size() >= MAX_RECORDS) break;
            JSONObject question = QuestionDataParser.readQuestionObject(file);
            if (question == null) {
                report.parseErrors++;
                continue;
            }
            String relativePath = relative(bookDirectory, file);
            visit(question, packageVersion, bookId, chapterId, pageId, relativePath,
                    "", 0, observedAt, records, report, keyCounts);
        }
        report.duplicateKeys = countDuplicates(keyCounts);
        return new Result(packageVersion, bookId, chapterId, pageId, records, report);
    }

    private static void visit(JSONObject value, String packageVersion, String bookId,
                              String chapterId, String pageId, String relativePath,
                              String parentQuestionId, int depth, long observedAt,
                              List<AnswerRecord> records, AnswerCoverageReport report,
                              Map<String, Integer> keyCounts) {
        if (value == null || depth > MAX_DEPTH || records.size() >= MAX_RECORDS) return;
        String questionId = value.optString("question_id", "");
        if (questionId.length() == 0) return;

        JSONArray nested = value.optJSONArray("questions_list");
        if (nested != null) {
            for (int i = 0; i < nested.length(); i++) {
                JSONObject child = nested.optJSONObject(i);
                if (child != null) {
                    visit(child, packageVersion, bookId, chapterId, pageId, relativePath,
                            questionId, depth + 1, observedAt, records, report, keyCounts);
                }
            }
        }

        int questionType = value.optInt("question_type", -1);
        int optionCount = value.optJSONArray("options") == null
                ? 0 : value.optJSONArray("options").length();
        Object answer = value.opt("answer_text");
        String answerValue = normalize(answer);
        String missingReason = answerValue.length() == 0 ? missingReason(value) : "";
        String format = format(value, answer, optionCount);
        if (questionType == 99 && answerValue.length() == 0) {
            missingReason = "composite_parent";
        }
        AnswerRecord record = new AnswerRecord(packageVersion, bookId, chapterId, pageId,
                questionId, parentQuestionId, questionType, answerValue, format,
                AnswerRecord.SOURCE_LOCAL, relativePath,
                answerValue.length() > 0 ? AnswerRecord.CONFIDENCE_CONFIRMED
                        : AnswerRecord.CONFIDENCE_UNKNOWN,
                missingReason, observedAt);
        records.add(record);
        report.record(record);
        String key = record.indexKey();
        Integer previous = keyCounts.get(key);
        keyCounts.put(key, previous == null ? 1 : previous + 1);
    }

    private static String format(JSONObject value, Object answer, int optionCount) {
        int questionType = value.optInt("question_type", -1);
        if (questionType == 99) return AnswerRecord.FORMAT_COMPOSITE;
        if (answer == null || answer == JSONObject.NULL) return AnswerRecord.FORMAT_UNKNOWN;
        if (answer instanceof JSONArray) {
            return optionCount > 0 ? AnswerRecord.FORMAT_MULTI_CHOICE : AnswerRecord.FORMAT_ORDERING;
        }
        if (answer instanceof JSONObject) return AnswerRecord.FORMAT_FILL_BLANK;
        return optionCount > 0 ? AnswerRecord.FORMAT_SINGLE_CHOICE : AnswerRecord.FORMAT_FILL_BLANK;
    }

    private static String missingReason(JSONObject value) {
        if (!value.has("answer_text")) return "answer_field_absent";
        Object answer = value.opt("answer_text");
        if (answer == null || answer == JSONObject.NULL) return "answer_field_null";
        if (answer instanceof String && ((String) answer).trim().isEmpty()) return "answer_field_empty";
        if (answer instanceof JSONArray && ((JSONArray) answer).length() == 0) return "answer_field_empty";
        return "";
    }

    private static String normalize(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                String element = normalizeElement(array.opt(i));
                if (element.length() == 0) continue;
                if (builder.length() > 0) builder.append(',');
                builder.append(element);
            }
            return builder.toString();
        }
        if (value instanceof JSONObject) return ((JSONObject) value).toString();
        if (value instanceof Boolean) return Boolean.toString((Boolean) value);
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isInfinite(number) && number == Math.floor(number)) {
                return Long.toString((long) number);
            }
            return Double.toString(number);
        }
        return String.valueOf(value).trim();
    }

    private static String normalizeElement(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String id = object.optString("id", "");
            if (id.length() > 0) return id;
            String answer = object.optString("value", object.optString("answer", ""));
            if (answer.length() > 0) return answer;
            return object.toString();
        }
        return normalize(value);
    }

    private static int countDuplicates(Map<String, Integer> keyCounts) {
        int duplicates = 0;
        for (Integer count : keyCounts.values()) {
            if (count != null && count > 1) duplicates++;
        }
        return duplicates;
    }

    private static String relative(File bookDirectory, File file) {
        try {
            if (bookDirectory != null) {
                String root = bookDirectory.getCanonicalPath() + File.separator;
                String path = file.getCanonicalPath();
                if (path.startsWith(root)) return path.substring(root.length());
                return path;
            }
        } catch (Exception ignored) {
            // Fall through to the directory name.
        }
        return file.getName();
    }

    static final class Result {
        final String packageVersion;
        final String bookId;
        final String chapterId;
        final String pageId;
        final List<AnswerRecord> records;
        final AnswerCoverageReport report;

        Result(String packageVersion, String bookId, String chapterId, String pageId,
               List<AnswerRecord> records, AnswerCoverageReport report) {
            this.packageVersion = packageVersion;
            this.bookId = bookId;
            this.chapterId = chapterId;
            this.pageId = pageId;
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.report = report;
        }
    }
}
