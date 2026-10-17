package io.github.adkimsm.up366.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class BridgeScriptAnalyzerTest {

    @Test
    public void injectedBridgeSourceIsNotTreatedAsAResult() {
        String source = "window.WebViewJavascriptBridge={"
                + "callHandler:function(h,d){var r=window['_android_js_obj_'][h](d);"
                + "if(String(r).indexOf('async_callback_key_')===0){}},"
                + "_onAsyncFuncResult:function(e){},"
                + "_onAsyncFuncResult=function(e){},"
                + "_callJsFuncWithResult:function(e){},"
                + "__onResponseJSResult=function(){}};";
        assertTrue(BridgeScriptAnalyzer.isBridgeSource(source));
        assertNull(BridgeScriptAnalyzer.extractAsyncKey(source));
        assertFalse(BridgeScriptAnalyzer.hasAsyncCallback(source));
    }

    @Test
    public void readsKeyTokenRegardlessOfFieldOrder() {
        String script = "javascript:WebViewJavascriptBridge._onAsyncFuncResult("
                + "{\"data\":{\"a\":1},\"key\":\"async_callback_key_7\"});";
        assertFalse(BridgeScriptAnalyzer.isBridgeSource(script));
        assertEquals("async_callback_key_7", BridgeScriptAnalyzer.extractAsyncKey(script));
        assertTrue(BridgeScriptAnalyzer.hasAsyncCallback(script));
    }

    @Test
    public void readsKeyWhenWrappedInJsonParse() {
        String script = "WebViewJavascriptBridge._onAsyncFuncResult("
                + "JSON.parse('{\"key\":\"async_callback_key_12\",\"data\":{}}'))";
        assertEquals("async_callback_key_12", BridgeScriptAnalyzer.extractAsyncKey(script));
    }

    @Test
    public void fallsBackToKeyFieldWhenTokenIsAbsent() {
        String script = "WebViewJavascriptBridge._onAsyncFuncResult({\"key\":\"abc-123\",\"data\":null})";
        assertEquals("abc-123", BridgeScriptAnalyzer.extractAsyncKey(script));
    }

    @Test
    public void unreadableCallbackReturnsNullInsteadOfGuessing() {
        String script = "WebViewJavascriptBridge._onAsyncFuncResult(/* opaque payload */)";
        assertNull(BridgeScriptAnalyzer.extractAsyncKey(script));
        assertFalse(BridgeScriptAnalyzer.hasAsyncCallback(script));
    }

    @Test
    public void sourceDetectionRequiresADefinition() {
        String call = "WebViewJavascriptBridge._onAsyncFuncResult({\"key\":\"async_callback_key_1\"})";
        assertFalse(BridgeScriptAnalyzer.isBridgeSource(call));
    }
}
