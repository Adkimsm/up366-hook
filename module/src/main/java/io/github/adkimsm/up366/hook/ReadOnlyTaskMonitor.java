package io.github.adkimsm.up366.hook;

import android.webkit.WebView;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class ReadOnlyTaskMonitor {
    private static final Map<WebView, Map<String, QuestionModel>> QUESTIONS =
            Collections.synchronizedMap(new WeakHashMap<WebView, Map<String, QuestionModel>>());
    private static final Map<WebView, Map<String, ListeningResourceMapper.Resource>> RESOURCES =
            Collections.synchronizedMap(new WeakHashMap<WebView, Map<String, ListeningResourceMapper.Resource>>());

    private ReadOnlyTaskMonitor() {}

    static void registerQuestions(WebView webView, List<QuestionModel> questions) {
        if (webView == null) return;
        Map<String, QuestionModel> index = new HashMap<>();
        for (QuestionModel question : CompositeQuestionExpander.flatten(questions)) {
            index.put(question.questionId, question);
        }
        QUESTIONS.put(webView, index);
        RESOURCES.put(webView, ListeningResourceMapper.index(questions));
    }

    static String summarizeSaveState(WebView webView, Object[] args) {
        Map<String, QuestionModel> index = QUESTIONS.get(webView);
        return AnswerStateParser.summarize(args, index);
    }

    static String summarizeState(WebView webView, Object value) {
        Map<String, QuestionModel> index = QUESTIONS.get(webView);
        return AnswerStateParser.summarize(new Object[]{value}, index);
    }
}
