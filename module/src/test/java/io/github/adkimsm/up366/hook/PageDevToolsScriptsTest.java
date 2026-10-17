package io.github.adkimsm.up366.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PageDevToolsScriptsTest {

    @Test
    public void escapesSelectorIntoASafeJsStringLiteral() {
        assertEquals("'a\\'b\\\\c'", PageDevToolsScripts.jsString("a'b\\c"));
        assertEquals("''", PageDevToolsScripts.jsString(null));
    }

    @Test
    public void selectorWithQuoteCannotBreakOutOfTheLiteral() {
        String script = PageDevToolsScripts.styles(".a'b");
        assertTrue(script.contains(".a\\'b"));
        assertFalse("A raw unescaped quote must not appear", script.contains("querySelector('.a'b')"));
    }

    @Test
    public void outlineIsBoundedAndReadOnly() {
        String script = PageDevToolsScripts.outline("body", 3, 10);
        assertTrue(script.contains("MAXD=3"));
        assertTrue(script.contains("MAXN=10"));
        assertTrue(script.contains("querySelector"));
        assertFalse("Outline must not modify the page", script.contains(".click("));
        assertFalse(script.contains("innerHTML="));
    }

    @Test
    public void outlineAndStylesAndGateHaveNoMutatingCalls() {
        for (String script : new String[]{
                PageDevToolsScripts.outline("body"),
                PageDevToolsScripts.styles(".u3-qst-opt"),
                PageDevToolsScripts.gateOverview()}) {
            assertFalse(script.contains(".click("));
            assertFalse(script.contains("setAttribute"));
            assertFalse(script.contains("classList.add"));
            assertFalse(script.contains("addClass"));
        }
    }

    @Test
    public void gateOverviewReportsTheFieldsNeededForTheSubmitGate() {
        String script = PageDevToolsScripts.gateOverview();
        assertTrue(script.contains("u3compo-qst.u3-disable"));
        assertTrue(script.contains(".u3-submit"));
        assertTrue(script.contains("pointerEvents"));
        assertTrue(script.contains("WebViewReady"));
    }
}
