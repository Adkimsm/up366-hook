package io.github.adkimsm.up366.hook;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ListeningResourceMapper {
    private ListeningResourceMapper() {}

    static Map<String, Resource> index(List<QuestionModel> questions) {
        Map<String, Resource> result = new LinkedHashMap<>();
        for (QuestionModel question : CompositeQuestionExpander.flatten(questions)) {
            String rootId = question.questionId;
            String parentId = question.parentQuestionId;
            Set<String> visited = new HashSet<>();
            while (parentId.length() > 0 && visited.add(parentId)) {
                rootId = parentId;
                QuestionModel parent = find(questions, parentId);
                if (parent == null) break;
                parentId = parent.parentQuestionId;
            }
            List<String> media = new ArrayList<>();
            for (QuestionModel.MediaModel item : question.media) media.add(item.resolvedPath);
            if (media.isEmpty()) addInheritedMedia(questions, question.parentQuestionId, media,
                    new HashSet<String>());
            List<String> options = new ArrayList<>();
            for (QuestionModel.OptionModel option : question.options) options.add(option.id);
            result.put(question.questionId, new Resource(question.questionId,
                    question.parentQuestionId, rootId, question.sourceFile, media, options));
        }
        return Collections.unmodifiableMap(result);
    }

    static String summarize(List<QuestionModel> questions) {
        int composite = 0;
        int child = 0;
        int mediaCount = 0;
        int missingMedia = 0;
        Set<String> mediaFiles = new HashSet<>();
        for (QuestionModel question : questions) {
            if (question.questionType == 99 || !question.children.isEmpty()) composite++;
            if (question.parentQuestionId.length() > 0) child++;
            for (QuestionModel.MediaModel media : question.media) {
                mediaCount++;
                if (!media.exists) missingMedia++;
                mediaFiles.add(media.file);
            }
        }
        return "questions=" + questions.size()
                + ",composite=" + composite
                + ",children=" + child
                + ",media=" + mediaCount
                + ",unique_media=" + mediaFiles.size()
                + ",missing_media=" + missingMedia;
    }

    private static QuestionModel find(List<QuestionModel> roots, String id) {
        if (roots == null || id == null) return null;
        for (QuestionModel question : CompositeQuestionExpander.flatten(roots)) {
            if (id.equals(question.questionId)) return question;
        }
        return null;
    }

    private static void addInheritedMedia(List<QuestionModel> roots, String parentId,
                                          List<String> output, Set<String> visited) {
        if (parentId == null || parentId.length() == 0 || !visited.add(parentId)) return;
        QuestionModel parent = find(roots, parentId);
        if (parent == null) return;
        for (QuestionModel.MediaModel media : parent.media) {
            if (!output.contains(media.resolvedPath)) output.add(media.resolvedPath);
        }
        if (output.isEmpty()) addInheritedMedia(roots, parent.parentQuestionId, output, visited);
    }

    static final class Resource {
        final String questionId;
        final String parentQuestionId;
        final String rootQuestionId;
        final String sourceFile;
        final List<String> mediaPaths;
        final List<String> optionIds;

        Resource(String questionId, String parentQuestionId, String rootQuestionId,
                 String sourceFile, List<String> mediaPaths, List<String> optionIds) {
            this.questionId = questionId;
            this.parentQuestionId = parentQuestionId;
            this.rootQuestionId = rootQuestionId;
            this.sourceFile = sourceFile;
            this.mediaPaths = Collections.unmodifiableList(new ArrayList<>(mediaPaths));
            this.optionIds = Collections.unmodifiableList(new ArrayList<>(optionIds));
        }
    }
}
