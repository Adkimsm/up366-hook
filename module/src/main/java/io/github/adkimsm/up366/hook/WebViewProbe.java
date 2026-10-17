package io.github.adkimsm.up366.hook;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

/**
 * Read-only {@code file://} page probe. Real practice pages are script-rendered shells: the
 * static HTML only has empty containers ({@code #page-wrap} etc.), and the components are drawn
 * later by the bundled {@code U3CompoLib} / {@code page.js} / {@code exam-zdcs.js} scripts. The
 * probe therefore waits until a container/first component appears (with a bounded retry) and reads
 * the real component selectors.
 *
 * <p>The DOM summary is emitted first and everything else is compact (counts and short samples), so
 * the useful structure is never pushed past the log-truncation limit by the large bridge-method or
 * script lists. It never invokes business bridge methods, never writes the page and never emits HTML
 * or answer text.
 */
final class WebViewProbe {
    private static final int MAX_ATTEMPTS = 15;
    private static final long FIRST_DELAY_MS = 250L;
    private static final long RETRY_DELAY_MS = 400L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Selectors confirmed from the readable question-package render libraries. */
    private static final String SELECTORS =
            "[element_id],[question_id],[data-question-id],input,textarea,button,select,"
                    + ".u3-qst-opt,.u3-qst-option,.u3compo-qst,.optionId,[data-skUID],[data-skBtn]";

    private WebViewProbe() {}

    static void schedule(final WebView webView, final String pageUrl) {
        if (webView == null || pageUrl == null || !pageUrl.startsWith("file://")) return;
        attempt(webView, pageUrl, 0);
    }

    private static void attempt(final WebView webView, final String pageUrl, final int attempt) {
        MAIN.postDelayed(() -> {
            try {
                webView.evaluateJavascript(READY, value -> {
                    if (!isRendered(value) && attempt < MAX_ATTEMPTS) {
                        attempt(webView, pageUrl, attempt + 1);
                        return;
                    }
                    probe(webView, pageUrl, attempt);
                });
            } catch (Throwable error) {
                LogSink.error("evaluate_webview_probe_ready", error);
            }
        }, attempt == 0 ? FIRST_DELAY_MS : RETRY_DELAY_MS);
    }

    private static void probe(final WebView webView, final String pageUrl, final int attempt) {
        try {
            webView.evaluateJavascript(SCRIPT, value -> LogSink.event(
                    "webview_probe", "url", LogSink.redact(pageUrl),
                    "context", TaskContext.snapshot(webView),
                    "attempt", Integer.toString(attempt),
                    "result", LogSink.limit(LogSink.redact(value), 12000)));
        } catch (Throwable error) {
            LogSink.error("evaluate_webview_probe", error);
        }
    }

    /** {@code value} is the JSON-encoded result of {@link #READY}: {@code "<children>:<matches>"}. */
    private static boolean isRendered(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        int separator = trimmed.indexOf(':');
        if (separator <= 0) return false;
        try {
            int children = Integer.parseInt(trimmed.substring(0, separator).trim());
            int matches = Integer.parseInt(trimmed.substring(separator + 1).trim());
            return children > 0 || matches > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String methodExpressions() {
        StringBuilder result = new StringBuilder();
        for (String method : BridgeApiCatalog.methods()) {
            if (result.length() > 0) result.append(',');
            result.append("'").append(method).append("'");
        }
        return result.toString();
    }

    private static final String READY = "(function(){try{"
            + "function k(id){try{var e=document.getElementById(id);return e&&e.children?e.children.length:0}catch(e){return 0}}"
            + "var c=Math.max(k('page-wrap'),k('first-wrap'),k('upload-wrap'),k('report-wrap'));"
            + "var m=document.querySelectorAll('" + SELECTORS + "').length;"
            + "return c+':'+m;}catch(e){return '0:0';}})()";

    private static final String SCRIPT = "(function(){"
            + "var names=[" + methodExpressions() + "];"
            + "function inspect(b){var avail=0,sample=[];for(var i=0;i<names.length;i++){"
            + "var t='missing';try{t=b?typeof b[names[i]]:'missing';}catch(e){}"
            + "if(t!=='missing'&&t!=='undefined'){avail++;if(sample.length<8)sample.push(names[i]);}}"
            + "return {present:!!b,available:avail,total:names.length,sample:sample};}"
            + "function attrs(x){var o={tag:x.tagName||'',id:x.id||'',name:x.getAttribute&&x.getAttribute('name')||'',"
            + "element_id:x.getAttribute&&x.getAttribute('element_id')||'',"
            + "question_id:x.getAttribute&&x.getAttribute('question_id')||'',"
            + "cls:(x.className&&typeof x.className==='string')?x.className.slice(0,60):''};return o;}"
            + "function kids(id){try{var e=document.getElementById(id);return e&&e.children?e.children.length:-1}catch(e){return -1}}"
            + "var sel='" + SELECTORS + "';"
            + "var frames=[];try{var fs=document.querySelectorAll('iframe');"
            + "for(var i=0;i<fs.length&&i<8;i++){try{var d=fs[i].contentDocument;"
            + "frames.push({i:i,reachable:!!d,nodes:d?d.querySelectorAll(sel).length:-1});}"
            + "catch(e){frames.push({i:i,reachable:false,nodes:-1});}}}catch(e){}"
            + "var nodes=Array.prototype.map.call(document.querySelectorAll(sel),attrs).slice(0,120);"
            + "var result={href:location.href,title:document.title,ready:document.readyState,"
            + "dom:{matches:document.querySelectorAll(sel).length,"
            + "questions:document.querySelectorAll('[question_id],[data-question-id],.u3compo-qst').length,"
            + "options:document.querySelectorAll('input,textarea,[role=option],[class*=option],.u3-qst-opt,.u3-qst-option').length,"
            + "buttons:document.querySelectorAll('button,[role=button],[data-skBtn]').length,"
            + "containers:{first:kids('first-wrap'),page:kids('page-wrap'),upload:kids('upload-wrap'),report:kids('report-wrap')},"
            + "nodes:nodes,frames:frames},"
            + "html_length:(document.documentElement&&document.documentElement.outerHTML||'').length,"
            + "storage:(function(){try{return Object.keys(localStorage)}catch(e){return ['<error>']}})(),"
            + "scripts:{count:document.scripts.length,sample:Array.prototype.map.call(document.scripts,"
            + "function(x){return x.src?x.src.split('/').pop():'inline'}).slice(0,8)},"
            + "links:document.querySelectorAll('link').length,"
            + "bridges:{v8:inspect(window['_android_js_obj_']),v6:inspect(window['jsObj'])}"
            + "};"
            + "return JSON.stringify(result);})()";
}
