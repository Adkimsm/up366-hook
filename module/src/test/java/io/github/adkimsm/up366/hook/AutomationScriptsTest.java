package io.github.adkimsm.up366.hook;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AutomationScriptsTest {

    @Test
    public void fillScriptOnlyTargetsAnsweringSlides() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("Q1", "A");
        answers.put("Q2", "B,C");

        String script = AutomationScripts.fillScript(answers);

        assertTrue(script.contains("'Q1':'A'"));
        assertTrue(script.contains("'Q2':'B,C'"));
        assertTrue(script.contains(".swiper-slide"));
        assertTrue(script.contains("u3-opt-id"));
        assertFalse("The preview list must not be touched", script.contains("first-wrap"));
        assertFalse(script.contains("#first-wrap"));
    }

    @Test
    public void fillScriptSkipsIncompleteEntriesAndEscapesQuotes() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("", "A");
        answers.put("Q3", "");
        answers.put("Q'4", "A");

        String script = AutomationScripts.fillScript(answers);

        assertFalse(script.contains("'':"));
        assertFalse(script.contains("'Q3':"));
        assertTrue(script.contains("'Q\\'4':'A'"));
    }

    @Test
    public void forceStopProbesTheBridgeFacadesThenTheGlobal() {
        String script = AutomationScripts.forceStopScript("audio123");

        assertTrue(script.contains("window.jsObj"));
        assertTrue(script.contains("window.jsChapterObj"));
        assertTrue(script.contains("window._android_js_obj_"));
        assertTrue(script.contains("window.onAudioStateChangeV1"));
        assertTrue(script.contains("\"state\":4"));
        assertTrue(script.contains("\"id\":\"audio123\""));
    }

    @Test
    public void forceStopPayloadSanitisesTheAudioId() {
        assertEquals("{\"duration\":0,\"msg\":\"\",\"id\":\"audio7\",\"state\":4,\"isForce\":0}",
                AutomationScripts.forceStopPayload("audio\"7\\"));
    }

    @Test
    public void submitScriptConfirmsThePopupBeforeClickingSubmit() {
        String script = AutomationScripts.submitScript(true);
        assertTrue(script.contains("u3compo-popup"));
        assertTrue(script.contains("u3-button:not(.u3-light-button)"));
        assertTrue(script.contains("offsetParent"));
        assertTrue(script.contains("sub.click()"));
        assertTrue(script.indexOf("confirmed") < script.indexOf("sub.click()"));
    }

    @Test
    public void submitScriptCanSkipClickingWhenAlreadySubmitted() {
        assertFalse(AutomationScripts.submitScript(false).contains("sub.click()"));
    }
}
