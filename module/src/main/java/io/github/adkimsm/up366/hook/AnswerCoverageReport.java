package io.github.adkimsm.up366.hook;

import org.json.JSONException;
import org.json.JSONObject;

/** Aggregate answer coverage for one parse pass or one stored package version. */
final class AnswerCoverageReport {
    int questionsTotal;
    int answersPresent;
    int answersMissing;
    int unsupportedFormats;
    int duplicateKeys;
    int parseErrors;
    int compositeParents;

    void record(AnswerRecord record) {
        if (record == null) return;
        questionsTotal++;
        if (AnswerRecord.FORMAT_COMPOSITE.equals(record.answerFormat) && !record.hasAnswer()) {
            // Composite parents carry no own answer: their answers live in the children.
            compositeParents++;
            return;
        }
        if (record.hasAnswer()) {
            answersPresent++;
        } else {
            answersMissing++;
        }
        if (AnswerRecord.FORMAT_UNKNOWN.equals(record.answerFormat) && record.hasAnswer()) {
            unsupportedFormats++;
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("questions_total", questionsTotal);
        json.put("answers_present", answersPresent);
        json.put("answers_missing", answersMissing);
        json.put("unsupported_formats", unsupportedFormats);
        json.put("duplicate_keys", duplicateKeys);
        json.put("parse_errors", parseErrors);
        json.put("composite_parents", compositeParents);
        return json;
    }

    @Override
    public String toString() {
        return "questions_total=" + questionsTotal
                + ",answers_present=" + answersPresent
                + ",answers_missing=" + answersMissing
                + ",unsupported_formats=" + unsupportedFormats
                + ",duplicate_keys=" + duplicateKeys
                + ",parse_errors=" + parseErrors
                + ",composite_parents=" + compositeParents;
    }
}
