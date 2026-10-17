package io.github.adkimsm.up366.hook;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AnswerIndexerTest {
    private static final String FIXTURE_BOOK = "59A0044287BEAB997F7C03AE4B9FC101";
    private static final String FIXTURE_PAGE = "flipbook/" + FIXTURE_BOOK
            + "/ad015c6fbcdd4d5aacf43c5e21de56b4/1/1.html";

    @Test
    public void indexesFixtureAnswersAndKeepsCompositeParentsSeparate() throws Exception {
        File pageFile = fixture();
        AnswerIndexer.Result result = AnswerIndexer.index(pageFile);

        assertFalse("Expected local answers from the fixture", result.records.isEmpty());
        assertTrue(result.report.answersPresent > 0);
        assertEquals(FIXTURE_BOOK, result.bookId);
        assertEquals("1", result.pageId);
        assertFalse(result.packageVersion.isEmpty());

        AnswerRecord composite = find(result.records, "1D433E68FC9E5A49EA18A9FD5CEBC57D");
        assertNotNull(composite);
        assertEquals(AnswerRecord.FORMAT_COMPOSITE, composite.answerFormat);
        assertFalse(composite.hasAnswer());
        assertEquals("composite_parent", composite.missingReason);

        AnswerRecord child = find(result.records, "B8F9285F2C2FC21249A25C8490317D01");
        assertNotNull(child);
        assertEquals("1D433E68FC9E5A49EA18A9FD5CEBC57D", child.parentQuestionId);
        assertEquals(AnswerRecord.FORMAT_SINGLE_CHOICE, child.answerFormat);
        assertEquals(AnswerRecord.SOURCE_LOCAL, child.sourceType);
        assertTrue(child.hasAnswer());
        assertTrue(result.report.compositeParents >= 1);
    }

    @Test
    public void recordsMissingAnswerWithoutGuessing() throws Exception {
        File pageFile = page(new String[]{"Q1"}, new String[]{
                "var pageConfig={\"questionObj\":{\"question_id\":\"Q1\",\"question_type\":1,"
                        + "\"options\":[{\"id\":\"A\"}]}};"});

        AnswerIndexer.Result result = AnswerIndexer.index(pageFile);

        assertEquals(1, result.records.size());
        AnswerRecord record = result.records.get(0);
        assertFalse(record.hasAnswer());
        assertEquals("answer_field_absent", record.missingReason);
        assertEquals(1, result.report.answersMissing);
    }

    @Test
    public void normalizesArrayObjectAndOrderingAnswers() throws Exception {
        File pageFile = page(new String[]{"MULTI", "ORDER", "BLANK"}, new String[]{
                "var pageConfig={\"questionObj\":{\"question_id\":\"MULTI\",\"question_type\":1,"
                        + "\"options\":[{\"id\":\"A\"},{\"id\":\"C\"}],\"answer_text\":[\"A\",\"C\"]}};",
                "var pageConfig={\"questionObj\":{\"question_id\":\"ORDER\",\"question_type\":1,"
                        + "\"answer_text\":[2,1,3]}};",
                "var pageConfig={\"questionObj\":{\"question_id\":\"BLANK\",\"question_type\":1,"
                        + "\"answer_text\":{\"1\":\"hello\"}}};"});

        AnswerIndexer.Result result = AnswerIndexer.index(pageFile);

        AnswerRecord multi = find(result.records, "MULTI");
        assertNotNull(multi);
        assertEquals(AnswerRecord.FORMAT_MULTI_CHOICE, multi.answerFormat);
        assertEquals("A,C", multi.answerValue);

        AnswerRecord ordering = find(result.records, "ORDER");
        assertNotNull(ordering);
        assertEquals(AnswerRecord.FORMAT_ORDERING, ordering.answerFormat);
        assertEquals("2,1,3", ordering.answerValue);

        AnswerRecord blank = find(result.records, "BLANK");
        assertNotNull(blank);
        assertEquals(AnswerRecord.FORMAT_FILL_BLANK, blank.answerFormat);
        assertTrue(blank.hasAnswer());
    }

    @Test
    public void countsDuplicateQuestionIdsInsteadOfOverwriting() throws Exception {
        File pageFile = page(new String[]{"1", "2"}, new String[]{
                "var pageConfig={\"questionObj\":{\"question_id\":\"DUP\",\"question_type\":1,"
                        + "\"answer_text\":\"A\"}};",
                "var pageConfig={\"questionObj\":{\"question_id\":\"DUP\",\"question_type\":1,"
                        + "\"answer_text\":\"B\"}};"});

        AnswerIndexer.Result result = AnswerIndexer.index(pageFile);

        assertEquals(2, result.records.size());
        assertEquals(1, result.report.duplicateKeys);
    }

    private static File page(String[] questionIds, String[] contents) throws Exception {
        File root = Files.createTempDirectory("up366-answer-index").toFile();
        File pageDirectory = new File(root, "book/chapter/page");
        assertTrue(pageDirectory.mkdirs());
        File pageFile = new File(pageDirectory, "1.html");
        assertTrue(pageFile.createNewFile());
        for (int i = 0; i < questionIds.length; i++) {
            File questionDirectory = new File(new File(pageDirectory, "questions"), questionIds[i]);
            assertTrue(questionDirectory.mkdirs());
            Files.write(new File(questionDirectory, "questionData.js").toPath(),
                    contents[i].getBytes(StandardCharsets.UTF_8));
        }
        return pageFile;
    }

    private static AnswerRecord find(List<AnswerRecord> records, String questionId) {
        for (AnswerRecord record : records) {
            if (questionId.equals(record.questionId)) return record;
        }
        return null;
    }

    private static File fixture() {
        File directory = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (directory != null) {
            File candidate = new File(directory, FIXTURE_PAGE);
            if (candidate.isFile()) return candidate;
            directory = directory.getParentFile();
        }
        return new File(FIXTURE_PAGE);
    }
}
