package io.github.adkimsm.up366.hook;

/**
 * Pure, Android-free answer presentation helpers. The injected panel and the unit tests share this
 * single formatter so answer rendering stays consistent and testable. It never reads or writes the
 * store, the bridge or the network.
 */
final class AnswerFormatter {
    private AnswerFormatter() {}

    /** Full one-line description of an answer record, including source-visible status. */
    static String describe(AnswerRecord record, boolean revealFillBlank) {
        if (record == null) return "无答案记录";
        String prefix = "页面 " + safe(record.pageId) + " / 题目 " + safe(record.questionId)
                + "（" + formatLabel(record.answerFormat) + "）：";
        if (AnswerRecord.FORMAT_COMPOSITE.equals(record.answerFormat) && !record.hasAnswer()) {
            return prefix + "组合题，答案在子题";
        }
        if (!record.hasAnswer()) {
            return prefix + missingLabel(record.missingReason);
        }
        String shown = formatValue(record.answerFormat, record.answerValue, revealFillBlank);
        String confidence = AnswerRecord.CONFIDENCE_CONFIRMED.equals(record.confidence)
                ? "" : "（" + safe(record.confidence) + "）";
        return prefix + "答案：" + shown + confidence;
    }

    static String formatValue(String format, String value) {
        return formatValue(format, value, false);
    }

    /** Formats a stored answer value for display; fill-blank values are masked unless revealed. */
    static String formatValue(String format, String value, boolean revealFillBlank) {
        if (value == null || value.length() == 0) return "";
        if (AnswerRecord.FORMAT_MULTI_CHOICE.equals(format)) return value.replace(",", "、");
        if (AnswerRecord.FORMAT_ORDERING.equals(format)) return value.replace(",", " -> ");
        if (AnswerRecord.FORMAT_FILL_BLANK.equals(format)) {
            return revealFillBlank ? value : mask(value);
        }
        return value;
    }

    /** Keeps the first character and masks the rest so a fill-blank answer is not shown in full. */
    static String mask(String value) {
        if (value == null || value.length() <= 2) return safe(value);
        StringBuilder masked = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            masked.append(i == 0 ? value.charAt(i) : '*');
        }
        return masked.toString();
    }

    static String formatLabel(String format) {
        if (AnswerRecord.FORMAT_SINGLE_CHOICE.equals(format)) return "单选";
        if (AnswerRecord.FORMAT_MULTI_CHOICE.equals(format)) return "多选";
        if (AnswerRecord.FORMAT_FILL_BLANK.equals(format)) return "填空";
        if (AnswerRecord.FORMAT_ORDERING.equals(format)) return "排序";
        if (AnswerRecord.FORMAT_COMPOSITE.equals(format)) return "组合";
        return "未知格式";
    }

    static String missingLabel(String reason) {
        if (reason == null || reason.length() == 0) return "无答案（未在题包中找到）";
        if ("answer_field_absent".equals(reason)) return "无答案字段";
        if ("answer_field_empty".equals(reason)) return "答案字段为空";
        if ("answer_field_null".equals(reason)) return "答案字段为 null";
        if ("composite_parent".equals(reason)) return "组合题父题，答案在子题";
        return "无答案（" + reason + "）";
    }

    static String sourceLabel(String sourceType) {
        if (AnswerRecord.SOURCE_LOCAL.equals(sourceType)) return "本地题包";
        if (AnswerRecord.SOURCE_BRIDGE.equals(sourceType)) return "Android 桥响应";
        if (AnswerRecord.SOURCE_DESKTOP.equals(sourceType)) return "桌面参考（未确认）";
        return safe(sourceType);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
