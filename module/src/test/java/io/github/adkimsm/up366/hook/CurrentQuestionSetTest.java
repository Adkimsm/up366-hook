package io.github.adkimsm.up366.hook;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CurrentQuestionSetTest {
    @Test
    public void rendersMaterialGroupsAndCorrectOptionContent() {
        QuestionModel composite = question("P1", 99, "听第9段材料，回答第14至17题。", "", true,
                option("A", "To attend cookery classes."), option("B", "To enjoy some tasty food."),
                option("C", "To research historical geography."));
        composite = withChildren(composite,
                question("C1", 1, "Why was the man in Italy?", "A", false,
                        option("A", "To attend cookery classes."), option("B", "To enjoy some tasty food."),
                        option("C", "To research historical geography.")),
                question("C2", 1, "Which city was the man most interested in?", "C", false,
                        option("A", "Rome."), option("B", "Florence."), option("C", "Genoa.")));
        QuestionModel leaf = question("L1", 1, "Where did the man make the sauce?", "B", false,
                option("A", "At a chef training center."), option("B", "At a restaurant."),
                option("C", "At school."));

        CurrentQuestionSet.Snapshot snapshot = CurrentQuestionSet.build(
                new PackageIdentity("pkg", "book", "chapter", "1"),
                Arrays.asList(composite, leaf));
        List<String> lines = CurrentQuestionSet.renderAnswerLines(snapshot);

        assertTrue(lines.contains("【听第9段材料，回答第14至17题。】"));
        assertTrue(lines.contains("1. Why was the man in Italy?：To attend cookery classes."));
        assertTrue(lines.contains("2. Which city was the man most interested in?：Genoa."));
        assertTrue(lines.contains("3. Where did the man make the sauce?：At a restaurant."));
        assertEquals(3, snapshot.itemCount());
    }

    @Test
    public void resolvesMultiChoiceAndFallsBackForOtherFormats() {
        CurrentQuestionSet.Item multi = new CurrentQuestionSet.Item("Q1", "E1", "Pick two", "A,C",
                Arrays.asList(option("A", "Alpha"), option("B", "Bravo"), option("C", "Charlie")));
        assertEquals("Alpha、Charlie", CurrentQuestionSet.answerDisplay(multi));

        CurrentQuestionSet.Item missing = new CurrentQuestionSet.Item("Q2", "E2", "Pick", "D",
                Arrays.asList(option("A", "Alpha")));
        assertEquals("D", CurrentQuestionSet.answerDisplay(missing));

        CurrentQuestionSet.Item ordering = new CurrentQuestionSet.Item("Q3", "E3", "Sort", "2,1,3",
                Collections.emptyList());
        assertEquals("2 -> 1 -> 3", CurrentQuestionSet.answerDisplay(ordering));

        CurrentQuestionSet.Item blank = new CurrentQuestionSet.Item("Q4", "E4", "Fill", "hello",
                Collections.emptyList());
        assertEquals("h****", CurrentQuestionSet.answerDisplay(blank));
    }

    @Test
    public void answersMapUsesQuestionIdsOnlyForAnsweredItems() {
        QuestionModel answered = question("Q-A", 1, "A?", "B", false,
                option("A", "Alpha"), option("B", "Bravo"));
        QuestionModel withoutAnswer = question("Q-B", 1, "B?", "", false,
                option("A", "Alpha"), option("B", "Bravo"));
        QuestionModel composite = withChildren(question("Q-C", 99, "材料", "", true),
                answered);
        CurrentQuestionSet.update(new PackageIdentity("pkg", "book", "chapter", "1"),
                Arrays.asList(withoutAnswer, composite));

        java.util.Map<String, String> answers = CurrentQuestionSet.answers();

        assertEquals(1, answers.size());
        assertEquals("B", answers.get("Q-A"));
        assertFalse(answers.containsKey("Q-B"));
    }

    @Test
    public void stripsHtmlFromStems() {
        assertEquals("History Talks is a podcast",
                CurrentQuestionSet.clean("<span style=\"\"><em>History Talks</em></span> is a podcast"));
        assertFalse(CurrentQuestionSet.clean("<p>a</p>  <p>b</p>").contains("<"));
    }

    @Test
    public void nameRegistryCapturesBookNameAndNavigationTitle() {
        QuestionSetNameRegistry.reset();
        QuestionSetNameRegistry.capture("openWebView", new Object[]{
                "{\"linkedData\":{\"chapters\":[{\"bookName\":\"听力培优（高中）\","
                        + "\"subjectId\":303,\"bookId\":\"59A0044287BEAB997F7C03AE4B9FC101\"}]}}"});
        assertEquals("听力培优（高中）",
                QuestionSetNameRegistry.bookName("59A0044287BEAB997F7C03AE4B9FC101"));

        QuestionSetNameRegistry.capture("updateNavigationTitle",
                new Object[]{"诊断测试 （高三卷B）2026年国庆听力能力诊断"});
        assertEquals("诊断测试 （高三卷B）2026年国庆听力能力诊断",
                QuestionSetNameRegistry.lastNavigationTitle());
        QuestionSetNameRegistry.reset();
    }

    private static QuestionModel question(String id, int type, String text, String answer,
                                          boolean composite, QuestionModel.OptionModel... options) {
        return new QuestionModel(id, type, 0, "", text, answer, options.length,
                Arrays.asList(options), Collections.emptyList(), Collections.emptyList(), "", "");
    }

    private static QuestionModel withChildren(QuestionModel question, QuestionModel... children) {
        List<QuestionModel> list = new ArrayList<>();
        for (QuestionModel child : children) {
            list.add(new QuestionModel(child.questionId, child.questionType, child.qtypeId,
                    child.elementId, child.questionText, child.answerText, child.optionCount,
                    child.options, child.media, child.children, child.sourceFile, question.questionId));
        }
        return new QuestionModel(question.questionId, question.questionType, question.qtypeId,
                question.elementId, question.questionText, question.answerText, question.optionCount,
                question.options, question.media, list, question.sourceFile, question.parentQuestionId);
    }

    private static QuestionModel.OptionModel option(String id, String content) {
        return new QuestionModel.OptionModel(id, content);
    }
}
