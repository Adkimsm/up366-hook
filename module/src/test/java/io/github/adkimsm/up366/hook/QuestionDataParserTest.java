package io.github.adkimsm.up366.hook;

import org.junit.Test;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class QuestionDataParserTest {
    private static final String FIXTURE = "flipbook/59A0044287BEAB997F7C03AE4B9FC101/"
            + "f63a1067f59c479a9a4257ccb78c6a7e/1/1.html";

    @Test
    public void parsesCompositeQuestionAndResolvesMediaInsideQuestionDirectory() throws Exception {
        List<QuestionModel> questions = QuestionDataParser.parsePage(fixture());
        List<QuestionModel> flattened = CompositeQuestionExpander.flatten(questions);

        assertTrue("Expected questions from the page directory", flattened.size() >= 20);
        QuestionModel composite = find(flattened, "B8F30D0097DC4D2EF0C0D62426BD887C");
        assertNotNull(composite);
        assertEquals(99, composite.questionType);
        assertTrue("Composite should retain nested questions", composite.children.size() >= 2);
        assertEquals(composite.questionId, composite.children.get(0).parentQuestionId);
        assertNotNull(find(flattened, "BE6A5B4E6F2A62AEC7218DB953CECE45"));
        assertNotNull(find(flattened, "21EE0D3577218F8D25B37ECC1504EB68"));
        assertFalse("Media should resolve against the questionData.js directory",
                composite.media.isEmpty() || !composite.media.get(0).exists);
        for (QuestionModel.MediaModel media : composite.media) {
            assertTrue(media.resolvedPath.startsWith(new File(composite.sourceFile).getParent()));
        }
    }

    private static File fixture() {
        File directory = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (directory != null) {
            File candidate = new File(directory, FIXTURE);
            if (candidate.isFile()) return candidate;
            directory = directory.getParentFile();
        }
        return new File(FIXTURE);
    }

    @Test
    public void answerStateSummaryContainsCountsButNoAnswerValues() {
        String state = "{\"page\":{\"quesEleAttr\":{\"Q1\":[{\"elementAttr\":{"
                + "\"question_id\":\"Q1\",\"user_answer\":\"SECRET_USER_ANSWER\","
                + "\"answer\":\"SECRET_STANDARD_ANSWER\",\"score\":9}}]}}}";
        Map<String, QuestionModel> index = new HashMap<>();
        index.put("Q1", question("Q1"));

        String summary = AnswerStateParser.summarize(new Object[]{state}, index);

        assertTrue(summary.contains("matched_ids=1"));
        assertTrue(summary.contains("question_entries=1"));
        assertFalse(summary.contains("SECRET_USER_ANSWER"));
        assertFalse(summary.contains("SECRET_STANDARD_ANSWER"));
    }

    @Test
    public void resourceIndexLinksNestedQuestionsToCompositeRoot() throws Exception {
        List<QuestionModel> questions = QuestionDataParser.parsePage(fixture());
        java.util.Map<String, ListeningResourceMapper.Resource> index =
                ListeningResourceMapper.index(questions);

        ListeningResourceMapper.Resource child = index.get("BE6A5B4E6F2A62AEC7218DB953CECE45");
        assertNotNull(child);
        assertEquals("B8F30D0097DC4D2EF0C0D62426BD887C", child.parentQuestionId);
        assertEquals("B8F30D0097DC4D2EF0C0D62426BD887C", child.rootQuestionId);
        assertTrue(child.mediaPaths.size() > 0);
        assertTrue(child.mediaPaths.get(0).endsWith("media/T6-ZC.mp3"));
        assertTrue(child.optionIds.contains("C"));
    }

    @Test
    public void redactionMasksAnswerSecretsAndHashesIdentifiers() {
        String redacted = LogSink.redact("{\"answer_text\":\"SECRET\",\"user_answer\":\"CHOICE\","
                + "\"clientId\":\"device-123\",\"token\":\"secret-token\"}");

        assertFalse(redacted.contains("SECRET"));
        assertFalse(redacted.contains("CHOICE"));
        assertFalse(redacted.contains("device-123"));
        assertFalse(redacted.contains("secret-token"));
        assertTrue(redacted.contains("<redacted>"));
    }

    @Test
    public void bridgeProbeCatalogCoversApiJsonCallHandlers() throws Exception {
        File catalogFile = locate("data/bridge-api.json");
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(catalogFile))) {
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
        }
        JSONArray entries = new JSONArray(json.toString());
        java.util.Set<String> observed = new java.util.HashSet<>(Arrays.asList(BridgeApiCatalog.methods()));
        for (int i = 0; i < entries.length(); i++) {
            JSONArray entry = entries.optJSONArray(i);
            if (entry == null || entry.length() == 0) continue;
            String path = entry.optString(0, "");
            if (path.startsWith("callHandler|")) {
                String name = path.substring("callHandler|".length()).split("\\|")[0];
                assertTrue("Probe catalog missing bridge method: " + name, observed.contains(name));
            }
        }
    }

    private static File locate(String path) {
        File directory = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (directory != null) {
            File candidate = new File(directory, path);
            if (candidate.isFile()) return candidate;
            directory = directory.getParentFile();
        }
        return new File(path);
    }

    private static QuestionModel find(List<QuestionModel> models, String id) {
        for (QuestionModel model : models) if (id.equals(model.questionId)) return model;
        return null;
    }

    private static QuestionModel question(String id) {
        return new QuestionModel(id, 1, 0, "", "", "", 0,
                java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), "", "");
    }
}
