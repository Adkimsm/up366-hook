package io.github.adkimsm.up366.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * In-memory snapshot of the question set that is currently open in the target app. It carries the
 * parsed question stems and option contents needed by the injected panel to render
 * {@code <n>. <stem>：<correct option content>} for the current page only.
 *
 * <p>This is deliberately transient: question stems and option contents never reach
 * {@link AnswerStore} or Logcat. The snapshot is rebuilt on every parse, so revisiting a page
 * regenerates it.
 */
final class CurrentQuestionSet {
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static volatile Snapshot current;

    private CurrentQuestionSet() {}

    static Snapshot snapshot() {
        return current;
    }

    /** Replaces the snapshot with the freshly parsed page; called from the parse worker. */
    static void update(PackageIdentity identity, List<QuestionModel> roots) {
        current = build(identity, roots);
    }

    static Snapshot build(PackageIdentity identity, List<QuestionModel> roots) {
        PackageIdentity safe = identity == null
                ? new PackageIdentity("", "", "", "") : identity;
        List<Group> groups = new ArrayList<>();
        if (roots != null) {
            for (QuestionModel root : roots) addRoot(root, groups);
        }
        return new Snapshot(safe.packageVersion, safe.bookId, safe.chapterId, safe.pageId,
                System.currentTimeMillis(), groups);
    }

    private static void addRoot(QuestionModel root, List<Group> groups) {
        if (root == null) return;
        if (root.questionType == 99 || !root.children.isEmpty()) {
            List<Item> items = new ArrayList<>();
            for (QuestionModel child : root.children) collectLeaves(child, items);
            groups.add(new Group(root.questionText, items));
            return;
        }
        List<Item> items = new ArrayList<>();
        collectLeaves(root, items);
        groups.add(new Group("", items));
    }

    private static void collectLeaves(QuestionModel question, List<Item> out) {
        if (question == null) return;
        if (question.questionType == 99 && !question.children.isEmpty()) {
            for (QuestionModel child : question.children) collectLeaves(child, out);
            return;
        }
        if (!question.options.isEmpty() || question.answerText.length() > 0) out.add(toItem(question));
        for (QuestionModel child : question.children) collectLeaves(child, out);
    }

    private static Item toItem(QuestionModel question) {
        return new Item(question.questionId, question.elementId,
                question.questionText, question.answerText, question.options);
    }

    /**
     * {@code question_id -> answer_text} for the current snapshot, used by the optional auto-fill.
     * Entries without a usable answer are skipped; the values never leave the target process.
     */
    static java.util.Map<String, String> answers() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        Snapshot snapshot = current;
        if (snapshot == null) return result;
        for (Group group : snapshot.groups) {
            for (Item item : group.items) {
                if (item.questionId.length() == 0 || item.answerValue.length() == 0) continue;
                result.put(item.questionId, item.answerValue);
            }
        }
        return result;
    }

    /** Renders the material group headers plus the numbered {@code 题干：选项内容} lines. */
    static List<String> renderAnswerLines(Snapshot snapshot) {
        List<String> lines = new ArrayList<>();
        if (snapshot == null) return lines;
        int number = 1;
        for (Group group : snapshot.groups) {
            String material = clean(group.materialText);
            if (material.length() > 0) lines.add("【" + material + "】");
            for (Item item : group.items) {
                String stem = clean(item.questionText);
                if (stem.length() == 0) stem = "第 " + number + " 题";
                lines.add(number + ". " + stem + "：" + clean(answerDisplay(item)));
                number++;
            }
        }
        if (lines.isEmpty()) lines.add("（当前套题未解析到可显示的答案）");
        return lines;
    }

    /** Resolves an answer to the correct option content when possible, otherwise falls back. */
    static String answerDisplay(Item item) {
        if (item == null) return "（无答案）";
        String value = item.answerValue == null ? "" : item.answerValue.trim();
        if (value.length() == 0) return "（无答案）";
        if (!item.options.isEmpty()) {
            String[] parts = value.split(",");
            List<String> contents = new ArrayList<>();
            boolean resolved = true;
            for (String part : parts) {
                String content = lookup(item.options, part.trim());
                if (content == null) {
                    resolved = false;
                    break;
                }
                if (content.length() > 0) contents.add(content);
            }
            if (resolved && !contents.isEmpty()) return join(contents, "、");
            return value;
        }
        if (value.indexOf(',') >= 0) return value.replace(",", " -> ");
        return AnswerFormatter.mask(value);
    }

    private static String lookup(List<QuestionModel.OptionModel> options, String id) {
        for (QuestionModel.OptionModel option : options) {
            if (option.id.equalsIgnoreCase(id)) return option.content;
        }
        return null;
    }

    static String clean(String text) {
        if (text == null) return "";
        String value = TAG.matcher(text).replaceAll(" ");
        value = value.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&ldquo;", "“").replace("&rdquo;", "”")
                .replace("&mdash;", "—").replace("&middot;", "·");
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    /** Immutable snapshot of the current page's displayable questions. */
    static final class Snapshot {
        final String packageVersion;
        final String bookId;
        final String chapterId;
        final String pageId;
        final long builtAt;
        final List<Group> groups;

        Snapshot(String packageVersion, String bookId, String chapterId, String pageId,
                 long builtAt, List<Group> groups) {
            this.packageVersion = packageVersion == null ? "" : packageVersion;
            this.bookId = bookId == null ? "" : bookId;
            this.chapterId = chapterId == null ? "" : chapterId;
            this.pageId = pageId == null ? "" : pageId;
            this.builtAt = builtAt;
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        }

        int itemCount() {
            int count = 0;
            for (Group group : groups) count += group.items.size();
            return count;
        }

        boolean isEmpty() {
            return itemCount() == 0;
        }
    }

    static final class Group {
        final String materialText;
        final List<Item> items;

        Group(String materialText, List<Item> items) {
            this.materialText = materialText == null ? "" : materialText;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    static final class Item {
        final String questionId;
        final String elementId;
        final String questionText;
        final String answerValue;
        final List<QuestionModel.OptionModel> options;

        Item(String questionId, String elementId, String questionText, String answerValue,
             List<QuestionModel.OptionModel> options) {
            this.questionId = questionId == null ? "" : questionId;
            this.elementId = elementId == null ? "" : elementId;
            this.questionText = questionText == null ? "" : questionText;
            this.answerValue = answerValue == null ? "" : answerValue;
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
        }
    }
}
