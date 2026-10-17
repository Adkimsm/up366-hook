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
    public void withRateRewritesOnlyTheRateField() {
        assertEquals("{\"id\":\"a\",\"rate\":8.0}",
                AutomationScripts.withRate("{\"id\":\"a\",\"rate\":1}", "8.0"));
        assertEquals("{\"id\":\"a\",\"rate\":8.0}",
                AutomationScripts.withRate("{\"id\":\"a\"}", "8.0"));
        assertEquals("{\"id\":\"a\",\"rate\":8.0,\"x\":1}",
                AutomationScripts.withRate("{\"id\":\"a\",\"rate\":2,\"x\":1}", "8.0"));
        assertEquals("not-json", AutomationScripts.withRate("not-json", "8.0"));
    }

    @Test
    public void pageHooksCompressTimersAccelerateClockAndRestore() {
        String hooks = AutomationScripts.pageHooksScript();
        assertTrue(hooks.contains("window.setTimeout"));
        assertTrue(hooks.contains("window.setInterval"));
        assertTrue(hooks.contains("Date.now"));
        assertTrue(hooks.contains("performance.now"));
        assertTrue(hooks.contains("timerCalls"));
        assertTrue(hooks.contains("clockCalls"));
        assertTrue(hooks.contains("Math.min(ms,60)"));

        String restore = AutomationScripts.pageHooksRestoreScript();
        assertTrue(restore.contains("window.setTimeout=H.st"));
        assertTrue(restore.contains("window.setInterval=H.si"));
        assertTrue(restore.contains("Date.now=H.dn"));
        assertTrue(restore.contains("delete window.__up366_hooks"));

        assertTrue(AutomationScripts.pageHooksStatsScript().contains("clockCalls"));
    }

    @Test
    public void forceStopUsesTheCapturedPrefixWhenSafe() {
        String script = AutomationScripts.forceStopScript("audio123", "window.onAudioStateChangeV1&&");

        assertTrue(script.contains("window.onAudioStateChangeV1&&onAudioStateChangeV1(payload)"));
        assertTrue(script.contains("via:'exact'"));
        assertTrue(script.contains("window.jsObj"));
        assertTrue(script.contains("window.jsChapterObj"));
        assertTrue(script.contains("window._android_js_obj_"));
        assertTrue(script.contains("window.onAudioStateChangeV1"));
        assertTrue(script.contains("\"state\":4"));
        assertTrue(script.contains("\"id\":\"audio123\""));
    }

    @Test
    public void unsafePrefixIsRejectedAndOnlyTheProbesRemain() {
        assertEquals("window.onAudioStateChangeV1&&onAudioStateChangeV1(payload)",
                AutomationScripts.exactCallExpression("window.onAudioStateChangeV1&&"));
        assertEquals(null, AutomationScripts.exactCallExpression("alert('x');window."));
        assertEquals(null, AutomationScripts.exactCallExpression("<script>"));
        assertFalse(AutomationScripts.forceStopScript("audio1", "x;alert(1)")
                .contains("alert(1)"));
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
