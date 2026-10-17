package io.github.adkimsm.up366.hook;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

final class WebViewProbe {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WebViewProbe() {}

    static void schedule(final WebView webView, final String pageUrl) {
        if (webView == null || pageUrl == null || !pageUrl.startsWith("file://")) return;
        MAIN.postDelayed(() -> {
            try {
                webView.evaluateJavascript(SCRIPT, value -> LogSink.event(
                        "webview_probe", "url", LogSink.redact(pageUrl),
                        "context", TaskContext.snapshot(webView),
                        "result", LogSink.limit(LogSink.redact(value), 12000)));
            } catch (Throwable error) {
                LogSink.error("evaluate_webview_probe", error);
            }
        }, 250L);
    }

    private static String methodExpressions() {
        StringBuilder result = new StringBuilder();
        for (String method : BridgeApiCatalog.methods()) {
            if (result.length() > 0) result.append(',');
            result.append("'").append(method).append("'");
        }
        return result.toString();
    }

    private static final String SCRIPT = "(function(){"
            + "var names=[" + methodExpressions() + "];"
            + "function inspect(b){return {present:!!b,methods:names.reduce(function(o,n){"
            + "o[n]=b?typeof b[n]:'missing';return o},{} )};}"
            + "function attrs(x){var o={tag:x.tagName||'',id:x.id||'',name:x.getAttribute&&x.getAttribute('name')||'',"
            + "element_id:x.getAttribute&&x.getAttribute('element_id')||'',question_id:x.getAttribute&&x.getAttribute('question_id')||''};return o;}"
            + "var nodes=Array.prototype.map.call(document.querySelectorAll('[element_id],[question_id],[data-question-id],input,textarea,button'),attrs).slice(0,200);"
            + "var result={href:location.href,title:document.title,ready:document.readyState,"
            + "scripts:Array.prototype.map.call(document.scripts,function(x){return x.src||'inline'}),"
            + "links:Array.prototype.map.call(document.querySelectorAll('link'),function(x){return x.href}),"
            + "storage:(function(){try{return Object.keys(localStorage)}catch(e){return ['<error>']}})(),"
            + "bridges:{v8:inspect(window['_android_js_obj_']),v6:inspect(window['jsObj'])},"
            + "dom:{nodes:nodes,questions:document.querySelectorAll('[question_id],[data-question-id]').length,"
            + "options:document.querySelectorAll('input,textarea,[role=option],[class*=option]').length,"
            + "buttons:document.querySelectorAll('button,[role=button]').length},"
            + "html_length:(document.documentElement&&document.documentElement.outerHTML||'').length};"
            + "return JSON.stringify(result);})()";
}
