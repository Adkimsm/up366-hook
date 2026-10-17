package io.github.adkimsm.up366.hook;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;

final class QuestionDataParser {
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_QUESTIONS_PER_SCAN = 5000;
    private static final Pattern PAGE_CONFIG = Pattern.compile(
            "^(?:var\\s+)?(?:window\\.)?pageConfig\\s*=", Pattern.CASE_INSENSITIVE);

    private QuestionDataParser() {}

    static List<QuestionModel> parsePage(File pageFile) throws Exception {
        if (pageFile == null || !pageFile.isFile()) return Collections.emptyList();
        File pageDirectory = pageFile.getParentFile();
        if (pageDirectory == null) return Collections.emptyList();
        File questionsDirectory = new File(pageDirectory, "questions");
        if (!questionsDirectory.isDirectory()) return Collections.emptyList();
        String questionsRoot = questionsDirectory.getCanonicalPath();
        Map<String, QuestionModel> output = new LinkedHashMap<>();
        List<File> files = new ArrayList<>();
        collect(questionsDirectory, questionsRoot, files);
        for (File file : files) {
            if (output.size() >= MAX_QUESTIONS_PER_SCAN) break;
            try {
                String source = read(file);
                String normalized = source.trim();
                java.util.regex.Matcher assignmentMatcher = PAGE_CONFIG.matcher(normalized);
                if (!assignmentMatcher.find()) continue;
                String json = extractObject(normalized, assignmentMatcher.end());
                if (json.length() == 0) continue;
                JSONObject pageConfig = new JSONObject(json);
                JSONObject question = pageConfig.optJSONObject("questionObj");
                if (question != null) expand(question, file, output, 0, "");
            } catch (Throwable error) {
                LogSink.event("question_parse_error", "file", file.getAbsolutePath(),
                        "error", error.getClass().getSimpleName() + ": " + LogSink.safe(error.getMessage()));
            }
        }
        List<QuestionModel> roots = new ArrayList<>();
        for (QuestionModel question : output.values()) {
            if (question.parentQuestionId.length() == 0) roots.add(question);
        }
        return roots;
    }

    /** Lists every {@code questionData.js} under a page's {@code questions} directory, in walk order. */
    static List<File> questionDataFiles(File pageFile) {
        if (pageFile == null) return Collections.emptyList();
        File pageDirectory = pageFile.getParentFile();
        if (pageDirectory == null) return Collections.emptyList();
        File questionsDirectory = new File(pageDirectory, "questions");
        if (!questionsDirectory.isDirectory()) return Collections.emptyList();
        List<File> files = new ArrayList<>();
        try {
            collect(questionsDirectory, questionsDirectory.getCanonicalPath(), files);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return files;
    }

    /**
     * Reads a single {@code questionData.js} assignment and returns its {@code questionObj} without
     * evaluating JavaScript. Returns {@code null} when the file is missing, oversized or unparsable.
     */
    static JSONObject readQuestionObject(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            String normalized = read(file).trim();
            java.util.regex.Matcher assignmentMatcher = PAGE_CONFIG.matcher(normalized);
            if (!assignmentMatcher.find()) return null;
            String json = extractObject(normalized, assignmentMatcher.end());
            if (json.length() == 0) return null;
            return new JSONObject(json).optJSONObject("questionObj");
        } catch (Throwable ignored) {
            return null;
        }
    }

    static File pageFileFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            String path = uri.getPath();
            return path == null ? null : new File(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static QuestionModel expand(JSONObject value, File source,
                                        Map<String, QuestionModel> output, int depth,
                                        String parentId) throws JSONException {
        if (depth > 32 || output.size() >= MAX_QUESTIONS_PER_SCAN) return null;
        String id = value.optString("question_id", "");
        if (id.length() == 0) return null;
        QuestionModel existing = output.get(id);
        if (existing != null) {
            if (parentId.length() == 0 || parentId.equals(existing.parentQuestionId)) return existing;
            QuestionModel linked = withParent(existing, parentId);
            output.put(id, linked);
            return linked;
        }
        List<QuestionModel> children = new ArrayList<>();
        JSONArray nested = value.optJSONArray("questions_list");
        if (nested != null) {
            for (int i = 0; i < nested.length(); i++) {
                JSONObject child = nested.optJSONObject(i);
                if (child != null) {
                    File childSource = sourceForQuestion(source, child.optString("question_id", ""));
                    QuestionModel model = expand(child, childSource, output, depth + 1, id);
                    if (model != null) children.add(model);
                }
            }
        }
        QuestionModel model = parseQuestion(value, source, children, parentId);
        output.put(id, model);
        return model;
    }

    private static QuestionModel withParent(QuestionModel source, String parentId) {
        return new QuestionModel(source.questionId, source.questionType, source.qtypeId,
                source.elementId, source.optionCount, source.options, source.media,
                source.children, source.sourceFile, parentId);
    }

    private static File sourceForQuestion(File current, String questionId) {
        if (questionId == null || !questionId.matches("[A-Za-z0-9_-]{1,128}")) return current;
        File directory = current.getParentFile();
        while (directory != null) {
            if ("questions".equals(directory.getName())) {
                File candidate = new File(new File(directory, questionId), "questionData.js");
                try {
                    String root = directory.getCanonicalPath() + File.separator;
                    String path = candidate.getCanonicalPath();
                    return path.startsWith(root) && candidate.isFile() ? candidate : current;
                } catch (Exception ignored) {
                    return current;
                }
            }
            directory = directory.getParentFile();
        }
        return current;
    }

    private static QuestionModel parseQuestion(JSONObject value, File source,
                                               List<QuestionModel> children, String parentId) {
        List<QuestionModel.OptionModel> options = new ArrayList<>();
        JSONArray optionArray = value.optJSONArray("options");
        if (optionArray != null) {
            for (int i = 0; i < optionArray.length(); i++) {
                JSONObject option = optionArray.optJSONObject(i);
                if (option == null) continue;
                options.add(new QuestionModel.OptionModel(option.optString("id", "")));
            }
        }
        List<QuestionModel.MediaModel> media = new ArrayList<>();
        addMedia(value.optJSONObject("media"), source, media);
        JSONArray mediaArray = value.optJSONArray("medias");
        if (mediaArray != null) {
            for (int i = 0; i < mediaArray.length(); i++) addMedia(mediaArray.optJSONObject(i), source, media);
        }
        return new QuestionModel(value.optString("question_id", ""),
                value.optInt("question_type", -1), value.optInt("qtype_id", -1),
                value.optString("element_id", ""), options.size(),
                options, media, children, source.getAbsolutePath(), parentId);
    }

    private static void addMedia(JSONObject value, File source, List<QuestionModel.MediaModel> output) {
        if (value == null) return;
        String file = value.optString("file", "");
        if (file.length() == 0) return;
        String type = value.optString("type", "");
        for (QuestionModel.MediaModel media : output) {
            if (file.equals(media.file) && type.equals(media.type)) return;
        }
        File questionDirectory = source.getParentFile();
        File resolved = new File(questionDirectory, file);
        try {
            String canonicalRoot = questionDirectory.getCanonicalPath() + File.separator;
            String canonicalFile = resolved.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalRoot)) canonicalFile = "";
            output.add(new QuestionModel.MediaModel(file, type,
                    canonicalFile, canonicalFile.length() > 0 && new File(canonicalFile).isFile()));
        } catch (Exception error) {
            output.add(new QuestionModel.MediaModel(file, type, "", false));
        }
    }

    private static void collect(File directory, String root, List<File> files) throws Exception {
        String directoryPath = directory.getCanonicalPath();
        if (files.size() >= MAX_QUESTIONS_PER_SCAN
                || (!directoryPath.equals(root) && !directoryPath.startsWith(root + File.separator))) return;
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (files.size() >= MAX_QUESTIONS_PER_SCAN) return;
            String canonical = entry.getCanonicalPath();
            if (!canonical.equals(root) && !canonical.startsWith(root + File.separator)) continue;
            if (java.nio.file.Files.isSymbolicLink(entry.toPath())) continue;
            if (entry.isDirectory()) collect(entry, root, files);
            else if ("questionData.js".equals(entry.getName())) files.add(entry);
        }
    }

    private static String read(File file) throws Exception {
        if (file.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("file too large");
        StringBuilder result = new StringBuilder((int) file.length());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (result.length() + read > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("file grew beyond size limit");
                }
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    /** Extracts the JSON object from the small JavaScript assignment without evaluating JS. */
    private static String extractObject(String source, int start) {
        int opening = source.indexOf('{', start);
        if (opening < 0) return "";
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = opening; i < source.length(); i++) {
            char character = source.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(opening, i + 1);
            }
        }
        return "";
    }
}
