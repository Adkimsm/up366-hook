package io.github.adkimsm.up366.hook;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AnswerStateParserTest {

    @Test
    public void parsesSaveDataShapeAndMatchesKnownQuestions() {
        Map<String, QuestionModel> questions = new HashMap<>();
        questions.put("Q1", null);

        String summary = AnswerStateParser.summarize(new Object[]{
                "{\"pageA\":{\"quesEleAttr\":{\"Q1\":[{},{}],\"Q2\":[{}]}}}"}, questions);

        assertEquals("state_type=save_data,page_count=1,state_entries=1,question_groups=2,"
                + "question_entries=3,matched_ids=1,unmatched_ids=1", summary);
    }

    @Test
    public void reportsArrayPayload() {
        String summary = AnswerStateParser.summarize(new Object[]{"[1,2,3]"}, null);
        assertTrue(summary, summary.startsWith("state_type=array,length="));
    }

    @Test
    public void unparsedPayloadCarriesSafeShapeOnly() {
        String summary = AnswerStateParser.summarize(new Object[]{"not-json"}, null);
        assertEquals("state_type=unparsed,state_present=true,class=String,length=8,starts_with=n",
                summary);
    }

    @Test
    public void reportsNonStateTypes() {
        String summary = AnswerStateParser.summarize(new Object[]{Integer.valueOf(5)}, null);
        assertEquals("state_type=number,state_present=true", summary);
    }
}
