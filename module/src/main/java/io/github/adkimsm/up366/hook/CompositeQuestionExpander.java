package io.github.adkimsm.up366.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Flattens nested question groups while preserving each model's child list. */
final class CompositeQuestionExpander {
    private CompositeQuestionExpander() {}

    static List<QuestionModel> flatten(List<QuestionModel> roots) {
        if (roots == null || roots.isEmpty()) return Collections.emptyList();
        List<QuestionModel> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QuestionModel root : roots) add(root, result, seen);
        return result;
    }

    private static void add(QuestionModel question, List<QuestionModel> result, Set<String> seen) {
        if (question == null || !seen.add(question.questionId)) return;
        result.add(question);
        for (QuestionModel child : question.children) add(child, result, seen);
    }
}
