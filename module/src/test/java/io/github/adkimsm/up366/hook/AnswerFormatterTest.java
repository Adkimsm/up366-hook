package io.github.adkimsm.up366.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AnswerFormatterTest {
    @Test
    public void formatsChoiceOrderingAndCompositeAnswers() {
        assertEquals("A、C", AnswerFormatter.formatValue(AnswerRecord.FORMAT_MULTI_CHOICE, "A,C"));
        assertEquals("2 -> 1 -> 3", AnswerFormatter.formatValue(AnswerRecord.FORMAT_ORDERING, "2,1,3"));
        assertEquals("B", AnswerFormatter.formatValue(AnswerRecord.FORMAT_SINGLE_CHOICE, "B"));
    }

    @Test
    public void masksFillBlankUntilRevealed() {
        assertEquals("h****", AnswerFormatter.formatValue(AnswerRecord.FORMAT_FILL_BLANK, "hello"));
        assertEquals("hello",
                AnswerFormatter.formatValue(AnswerRecord.FORMAT_FILL_BLANK, "hello", true));
    }

    @Test
    public void describesCompositeParentAndMissingAnswerWithoutGuessing() {
        AnswerRecord composite = record("1", "Q1", AnswerRecord.FORMAT_COMPOSITE, "", "composite_parent");
        String compositeText = AnswerFormatter.describe(composite, false);
        assertTrue(compositeText.contains("组合题，答案在子题"));

        AnswerRecord missing = record("1", "Q2", AnswerRecord.FORMAT_SINGLE_CHOICE, "", "answer_field_absent");
        String missingText = AnswerFormatter.describe(missing, false);
        assertTrue(missingText.contains("无答案字段"));
        assertFalse(missingText.contains("答案："));
    }

    @Test
    public void describesAnswerWithPageQuestionAndFormat() {
        AnswerRecord answered = record("1", "Q3", AnswerRecord.FORMAT_SINGLE_CHOICE, "B", "");
        String text = AnswerFormatter.describe(answered, false);
        assertTrue(text.contains("页面 1"));
        assertTrue(text.contains("题目 Q3"));
        assertTrue(text.contains("答案：B"));
        assertTrue(text.contains("单选"));
    }

    @Test
    public void labelsAnswerSources() {
        assertEquals("本地题包", AnswerFormatter.sourceLabel(AnswerRecord.SOURCE_LOCAL));
        assertEquals("Android 桥响应", AnswerFormatter.sourceLabel(AnswerRecord.SOURCE_BRIDGE));
        assertEquals("桌面参考（未确认）", AnswerFormatter.sourceLabel(AnswerRecord.SOURCE_DESKTOP));
    }

    private static AnswerRecord record(String pageId, String questionId, String format,
                                       String value, String missingReason) {
        return new AnswerRecord("pkg", "book", "chapter", pageId, questionId, "", 1,
                value, format, AnswerRecord.SOURCE_LOCAL, "page/questions/" + questionId,
                value.length() > 0 ? AnswerRecord.CONFIDENCE_CONFIRMED
                        : AnswerRecord.CONFIDENCE_UNKNOWN,
                missingReason, 0L);
    }
}
