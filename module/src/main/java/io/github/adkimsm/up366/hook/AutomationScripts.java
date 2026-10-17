package io.github.adkimsm.up366.hook;

import java.util.Map;

/**
 * Read/write page scripts used by the optional automation switches. They only touch the question
 * answering widgets the app itself renders ({@code .u3-qst-opt[u3-opt-id]} clicks and the page's own
 * {@code onAudioStateChangeV1} handler); they never call a native bridge method. Kept free of
 * Android types so the builders are unit-testable.
 */
final class AutomationScripts {
    private static final java.util.regex.Pattern RATE_FIELD =
            java.util.regex.Pattern.compile("\"rate\"\\s*:\\s*[0-9.]+");

    private AutomationScripts() {}

    /**
     * Rewrites the page's own {@code playAudioWithParams} request so the native player plays the
     * clip at {@code rate}. Only the rate field changes; everything else is preserved verbatim.
     */
    static String withRate(String payload, String rate) {
        if (payload == null || payload.isEmpty() || rate == null || rate.isEmpty()) return payload;
        if (RATE_FIELD.matcher(payload).find()) {
            return RATE_FIELD.matcher(payload).replaceFirst("\"rate\":" + rate);
        }
        int end = payload.lastIndexOf('}');
        if (end < 0) return payload;
        return payload.substring(0, end) + ",\"rate\":" + rate + payload.substring(end);
    }

    /**
     * Installs the page hooks used by the fast-forward: long {@code setTimeout}/{@code setInterval}
     * delays are compressed and the clock is accelerated so countdown-style waits collapse too.
     * Counting the calls tells us whether the waits actually run through the page's JS. Idempotent.
     */
    static String pageHooksScript(SpeedProfile profile) {
        SpeedProfile safe = profile == null ? SpeedProfile.of(SpeedProfile.DEFAULT_INDEX) : profile;
        if (!safe.hooksPage()) return "(function(){return 'disabled';})()";
        return "(function(){try{"
                + "if(window.__up366_hooks){return 'already';}"
                + "var H={st:window.setTimeout,si:window.setInterval,dn:Date.now,"
                + "pn:(window.performance&&window.performance.now)?window.performance.now:null,"
                + "timerCalls:0,clockCalls:0,t0:Date.now(),speed:" + safe.clock + "};"
                + "window.__up366_hooks=H;"
                + "function c(ms){if(typeof ms!=='number'||ms<=" + safe.timerMaxMs + ")return ms;"
                + "return Math.max(" + safe.timerFloorMs + ",Math.min(ms," + safe.timerMaxMs + "));}"
                + "window.setTimeout=function(fn,ms){var a=Array.prototype.slice.call(arguments,2);"
                + "var d=c(ms);if(d!==ms)H.timerCalls++;"
                + "return H.st.apply(window,[fn,d].concat(a));};"
                + "window.setInterval=function(fn,ms){var a=Array.prototype.slice.call(arguments,2);"
                + "var d=c(ms);if(d!==ms)H.timerCalls++;"
                + "return H.si.apply(window,[fn,d].concat(a));};"
                + "Date.now=function(){H.clockCalls++;"
                + "return H.t0+(H.dn.call(Date)-H.t0)*H.speed;};"
                + "try{if(H.pn&&window.performance){window.performance.now=function(){"
                + "H.clockCalls++;return H.pn.call(window.performance)*H.speed;};}}catch(e){}"
                + "return 'ok';"
                + "}catch(e){return 'err';}})()";
    }

    /** Restores every hooked function and returns the call counters as JSON. */
    static String pageHooksRestoreScript() {
        return "(function(){try{"
                + "var H=window.__up366_hooks;if(!H)return '{\"timerCalls\":0,\"clockCalls\":0}';"
                + "window.setTimeout=H.st;window.setInterval=H.si;Date.now=H.dn;"
                + "try{if(H.pn&&window.performance){window.performance.now=H.pn;}}catch(e){}"
                + "delete window.__up366_hooks;"
                + "return JSON.stringify({timerCalls:H.timerCalls,clockCalls:H.clockCalls});"
                + "}catch(e){return '{\"timerCalls\":-1,\"clockCalls\":-1}';}})()";
    }

    /** Reads the page-hook counters without restoring anything. */
    static String pageHooksStatsScript() {
        return "(function(){try{var H=window.__up366_hooks;"
                + "if(!H)return '{\"timerCalls\":0,\"clockCalls\":0}';"
                + "return JSON.stringify({timerCalls:H.timerCalls,clockCalls:H.clockCalls});"
                + "}catch(e){return '{\"timerCalls\":-1,\"clockCalls\":-1}';}})()";
    }

    /**
     * Clicks the option matching each known answer inside the answering slides only. The preview
     * list rendered under {@code #first-wrap} is deliberately skipped. Programmatic clicks bypass
     * the app's {@code pointer-events:none} gating, which is what makes an instant fill possible.
     */
    static String fillScript(Map<String, String> answers) {
        StringBuilder map = new StringBuilder("{");
        if (answers != null) {
            for (Map.Entry<String, String> entry : answers.entrySet()) {
                String questionId = entry.getKey();
                String answer = entry.getValue();
                if (questionId == null || questionId.isEmpty()
                        || answer == null || answer.isEmpty()) continue;
                if (map.length() > 1) map.append(',');
                map.append(jsString(questionId)).append(':').append(jsString(answer));
            }
        }
        map.append('}');
        return "(function(){try{"
                + "var answers=" + map + ";"
                + "var out={filled:0,clickedOptions:0,missing:0,noOption:0,"
                + "selected:0,slides:0};"
                + "var slides=document.querySelectorAll('.swiper-slide');out.slides=slides.length;"
                + "for(var qid in answers){"
                + "var letters=String(answers[qid]).split(',');var clicked=0,found=0;"
                + "for(var i=0;i<slides.length;i++){"
                + "var q=slides[i].querySelector('[question_id=\"'+qid+'\"]');"
                + "if(!q)continue;found++;"
                + "for(var j=0;j<letters.length;j++){"
                + "var L=letters[j].trim();if(!L)continue;"
                + "var opt=q.querySelector('.u3-qst-opt[u3-opt-id=\"'+L+'\"]');"
                + "if(!opt)continue;"
                + "if(!opt.classList.contains('u3-selected'))opt.click();"
                + "clicked++;}}"
                + "if(!found)out.missing++;else if(clicked>0){out.filled++;out.clickedOptions+=clicked;}"
                + "else out.noOption++;}"
                + "out.selected=document.querySelectorAll('.swiper-slide .u3-qst-opt.u3-selected').length;"
                + "return JSON.stringify(out);"
                + "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";
    }

    /**
     * Rebuilds the native "this audio finished" callback. The exact call form is taken from the
     * captured prefix when it is a safe expression (for example {@code window.onAudioStateChangeV1&&}
     * yields {@code window.onAudioStateChangeV1&&onAudioStateChangeV1(payload)}); otherwise the page
     * facades and the global function are probed. The page's handler then emits {@code stop}, which
     * unlocks the section and advances the slide.
     */
    static String forceStopScript(String audioId, String capturedPrefix) {
        return forceStopScript(audioId, capturedPrefix, 0L);
    }

    static String forceStopScript(String audioId, String capturedPrefix, long durationMs) {
        String exact = exactCallExpression(capturedPrefix);
        return "(function(){try{"
                + "var payload=" + forceStopPayload(audioId, durationMs) + ";"
                + (exact == null ? "" : "try{" + exact
                        + ";return JSON.stringify({called:true,via:'exact'});}catch(e){}")
                + "var objs=[window.jsObj,window.jsChapterObj,window._android_js_obj_];"
                + "for(var i=0;i<objs.length;i++){var o=objs[i];try{"
                + "if(o&&typeof o.onAudioStateChangeV1==='function'){"
                + "o.onAudioStateChangeV1(payload);"
                + "return JSON.stringify({called:true,via:'facade'+i});}"
                + "}catch(e){}}"
                + "try{if(typeof window.onAudioStateChangeV1==='function'){"
                + "window.onAudioStateChangeV1(payload);"
                + "return JSON.stringify({called:true,via:'global'});}}catch(e){}"
                + "return JSON.stringify({called:false});"
                + "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";
    }

    /** Full call expression rebuilt from the captured prefix, or {@code null} when unsafe. */
    static String exactCallExpression(String capturedPrefix) {
        if (!isSafePrefix(capturedPrefix)) return null;
        return capturedPrefix + "onAudioStateChangeV1(payload)";
    }

    /** Accepts only short, expression-safe prefixes (letters, digits, dots, brackets and operators). */
    static boolean isSafePrefix(String capturedPrefix) {
        if (capturedPrefix == null) return false;
        String prefix = capturedPrefix.trim();
        if (prefix.isEmpty() || prefix.length() > 80) return false;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c)
                    || c == '_' || c == '$' || c == '.' || c == '[' || c == ']'
                    || c == '&' || c == '|' || c == '!' || c == '?';
            if (!allowed) return false;
        }
        return true;
    }

    static String forceStopPayload(String audioId) {
        return forceStopPayload(audioId, 0L);
    }

    /**
     * The synthetic "finished" payload. The real callback carries the clip's duration, so a known
     * duration is used to keep the page-side player state realistic; 0 only when it was never seen.
     */
    static String forceStopPayload(String audioId, long durationMs) {
        String id = audioId == null ? "" : audioId.replace("\\", "").replace("\"", "");
        long duration = Math.max(0L, durationMs);
        return "{\"duration\":" + duration + ",\"msg\":\"\",\"id\":\"" + id
                + "\",\"state\":4,\"isForce\":0}";
    }

    /**
     * Checks the submit control and the confirm popup: a visible popup is confirmed first, otherwise
     * the submit button is clicked when {@code clickSubmit} is set. Returns a status summary.
     */
    static String submitScript(boolean clickSubmit) {
        return "(function(){try{"
                + "var out={submit:false,clicked:false,popup:false,confirmed:false,"
                + "selected:document.querySelectorAll('.swiper-slide .u3-qst-opt.u3-selected').length};"
                + "var sub=document.querySelector('.u3-submit');out.submit=!!sub;"
                + "var pop=document.querySelector('.u3compo-popup');"
                + "if(pop&&pop.offsetParent!==null){out.popup=true;"
                + "var box=pop.querySelector('.u3-button-list')||pop;"
                + "var btn=box.querySelector('.u3-button:not(.u3-light-button)');"
                + "if(btn){btn.click();out.confirmed=true;}}"
                + (clickSubmit ? "else if(sub){sub.click();out.clicked=true;}" : "")
                + "return JSON.stringify(out);"
                + "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";
    }

    /** Embeds a Java string as a JS single-quoted literal, escaping quotes and backslashes. */
    static String jsString(String value) {
        String text = value == null ? "" : value;
        StringBuilder builder = new StringBuilder(text.length() + 2);
        builder.append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '\'') builder.append('\\');
            if (c == '\n' || c == '\r') {
                builder.append(' ');
                continue;
            }
            builder.append(c);
        }
        builder.append('\'');
        return builder.toString();
    }
}
