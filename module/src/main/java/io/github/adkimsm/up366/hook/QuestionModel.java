package io.github.adkimsm.up366.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class QuestionModel {
    final String questionId;
    final int questionType;
    final int qtypeId;
    final String elementId;
    final int optionCount;
    final List<OptionModel> options;
    final List<MediaModel> media;
    final List<QuestionModel> children;
    final String sourceFile;
    final String parentQuestionId;

    QuestionModel(String questionId, int questionType, int qtypeId, String elementId,
                  int optionCount,
                  List<OptionModel> options, List<MediaModel> media,
                  List<QuestionModel> children, String sourceFile, String parentQuestionId) {
        this.questionId = questionId;
        this.questionType = questionType;
        this.qtypeId = qtypeId;
        this.elementId = elementId;
        this.optionCount = optionCount;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.media = Collections.unmodifiableList(new ArrayList<>(media));
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
        this.sourceFile = sourceFile;
        this.parentQuestionId = parentQuestionId;
    }

    static final class OptionModel {
        final String id;
        OptionModel(String id) {
            this.id = id;
        }
    }

    static final class MediaModel {
        final String file;
        final String type;
        final String resolvedPath;
        final boolean exists;

        MediaModel(String file, String type, String resolvedPath, boolean exists) {
            this.file = file;
            this.type = type;
            this.resolvedPath = resolvedPath;
            this.exists = exists;
        }
    }
}
