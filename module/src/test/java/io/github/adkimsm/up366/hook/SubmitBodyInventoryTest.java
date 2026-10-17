package io.github.adkimsm.up366.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SubmitBodyInventoryTest {

    @Test
    public void inventoriesTimeFieldsButNeverAnswersOrScores() {
        String body = "{\"tasksJson\":\"{\\\"seconds\\\":1}\",\"seconds\":123,\"score\":98,"
                + "\"answers\":[{\"question_id\":\"Q1\",\"timestamp\":1699999999999,"
                + "\"user_answer\":\"A\"}]}";

        String inventory = SubmitBodyInventory.of(body);

        assertTrue(inventory, inventory.contains("seconds=123"));
        assertTrue(inventory, inventory.contains("timestamp=1699999999999"));
        assertTrue(inventory, inventory.contains("tasksJson"));
        assertFalse(inventory, inventory.contains("user_answer"));
        assertFalse(inventory, inventory.contains("=98"));
    }

    @Test
    public void descendsIntoNestedJsonStringsLikeTasksJson() {
        String body = "{\"submitType\":1,\"ut\":\"abc\",\"tasksJson\":\"{\\\"seconds\\\":1050,"
                + "\\\"questions\\\":[{\\\"question_id\\\":\\\"Q1\\\",\\\"timestamp\\\":1699999999999,"
                + "\\\"user_answer\\\":\\\"A\\\"}]}\"}";

        String inventory = SubmitBodyInventory.of(body);

        assertTrue(inventory, inventory.contains("tasksJson.keys="));
        assertTrue(inventory, inventory.contains("tasksJson.seconds=1050"));
        assertTrue(inventory, inventory.contains("tasksJson.questions[0].timestamp=1699999999999"));
        assertFalse(inventory, inventory.contains("user_answer"));
        assertFalse(inventory, inventory.contains("\"A\""));
    }

    @Test
    public void reportsNonJsonAndEmptyInputs() {
        assertEquals("empty", SubmitBodyInventory.of(""));
        assertEquals("non_json length=3", SubmitBodyInventory.of("abc"));
        assertTrue(SubmitBodyInventory.of("{broken").startsWith("unparsed"));
    }

    @Test
    public void limitsLargeArraysWithoutThrowing() {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < 200; i++) {
            if (i > 0) body.append(',');
            body.append("{\"timestamp\":").append(1_700_000_000_000L + i).append('}');
        }
        body.append(']');

        String inventory = SubmitBodyInventory.of(body.toString());

        assertTrue(inventory, inventory.contains("timestamp="));
    }
}
