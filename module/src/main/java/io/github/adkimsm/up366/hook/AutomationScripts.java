package io.github.adkimsm.up366.hook;

import java.util.Map;

/**
 * Read/write page scripts used by the optional automation switches. They only touch the question
 * answering widgets the app itself renders ({@code .u3-qst-opt[u3-opt-id]} clicks and the page's own
 * {@code onAudioStateChangeV1} handler); they never call a native bridge method. Kept free of
 * Android types so the builders are unit-testable.
 */
final class AutomationScripts {

    private AutomationScripts() {}

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
        String exact = exactCallExpression(capturedPrefix);
        return "(function(){try{"
                + "var payload=" + forceStopPayload(audioId) + ";"
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
        String id = audioId == null ? "" : audioId.replace("\\", "").replace("\"", "");
        return "{\"duration\":0,\"msg\":\"\",\"id\":\"" + id + "\",\"state\":4,\"isForce\":0}";
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
