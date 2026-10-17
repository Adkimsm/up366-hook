package io.github.adkimsm.up366.hook;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SubmitTimeRewriterTest {

    private static final SubmitTimeRewriter.TimeSource SOURCE = new SubmitTimeRewriter.TimeSource() {
        @Override
        public long timestampFor(String questionId) {
            return 1_700_000_500_000L + Math.floorMod(String.valueOf(questionId).hashCode(), 10_000);
        }

        @Override
        public long secondsFor(String questionId) {
            return 45L;
        }

        @Override
        public long startMs() {
            return 1_700_000_000_000L;
        }

        @Override
        public long endMs() {
            return 1_700_001_200_000L;
        }

        @Override
        public long totalSeconds() {
            return 1_200L;
        }
    };

    @Test
    public void rewritesSaveStateTimestampsAndKeepsOtherFields() throws Exception {
        String state = "{\"pageA\":{\"quesEleAttr\":{\"Q1\":[{\"elementId\":\"e1\","
                + "\"elementType\":1,\"elementAttr\":{\"user_answer\":\"A\",\"user_score\":1,"
                + "\"timestamp\":9999999999999}}]}}}";

        String rewritten = SubmitTimeRewriter.rewriteSaveState(state, SOURCE);

        JSONObject attr = new JSONObject(rewritten).getJSONObject("pageA")
                .getJSONObject("quesEleAttr").getJSONArray("Q1").getJSONObject(0)
                .getJSONObject("elementAttr");
        assertEquals(SOURCE.timestampFor("Q1"), attr.getLong("timestamp"));
        assertEquals("A", attr.getString("user_answer"));
        assertEquals(1, attr.getInt("user_score"));
        assertTrue(attr.getLong("timestamp") <= SOURCE.endMs());
    }

    @Test
    public void leavesZeroTimestampsAndForeignPayloadsAlone() {
        String unanswered = "{\"pageA\":{\"quesEleAttr\":{\"Q1\":[{\"elementAttr\":"
                + "{\"timestamp\":0}}]}}}";
        assertEquals(unanswered, SubmitTimeRewriter.rewriteSaveState(unanswered, SOURCE));
        assertEquals("not-json", SubmitTimeRewriter.rewriteSaveState("not-json", SOURCE));
        assertEquals("{\"a\":1}", SubmitTimeRewriter.rewriteSaveState("{\"a\":1}", SOURCE));
    }

    @Test
    public void rewritesSubmitElementAttrPayload() throws Exception {
        String payload = "[{\"elementId\":\"e1\",\"elementType\":1,\"elementAttr\":"
                + "{\"question_id\":\"Q2\",\"user_answer\":\"B\",\"timestamp\":9999999999999}}]";

        String rewritten = SubmitTimeRewriter.rewriteElementAttrs(payload, SOURCE);

        JSONObject attr = new JSONArray(rewritten).getJSONObject(0)
                .getJSONObject("elementAttr");
        assertEquals(SOURCE.timestampFor("Q2"), attr.getLong("timestamp"));
        assertEquals("B", attr.getString("user_answer"));
    }

    @Test
    public void genericRewriteMapsSubmitSecondsAndStartEndTimes() throws Exception {
        String payload = "{\"taskId\":\"t1\",\"seconds\":7,\"submitTime\":1999999999999,"
                + "\"startTime\":1600000000000,\"score\":100}";

        JSONObject parsed = new JSONObject(SubmitTimeRewriter.rewriteGeneric(payload, SOURCE));

        assertEquals(SOURCE.totalSeconds(), parsed.getLong("seconds"));
        assertEquals(SOURCE.endMs(), parsed.getLong("submitTime"));
        assertEquals(SOURCE.startMs(), parsed.getLong("startTime"));
        assertEquals(100, parsed.getInt("score"));
        assertEquals("t1", parsed.getString("taskId"));
    }

    @Test
    public void genericRewriteUsesTheQuestionTimelineWhenIdentified() throws Exception {
        String payload = "{\"question_id\":\"Q9\",\"timestamp\":1999999999999}";

        JSONObject parsed = new JSONObject(SubmitTimeRewriter.rewriteGeneric(payload, SOURCE));

        assertEquals(SOURCE.timestampFor("Q9"), parsed.getLong("timestamp"));
    }

    @Test
    public void genericRewriteLeavesSmallDurationsAndPlainStringsAlone() {
        assertEquals("{\"duration\":31500}",
                SubmitTimeRewriter.rewriteGeneric("{\"duration\":31500}", SOURCE));
        assertEquals("7", SubmitTimeRewriter.rewriteGeneric("7", SOURCE));
        assertEquals("not-json", SubmitTimeRewriter.rewriteGeneric("not-json", SOURCE));
    }
}
