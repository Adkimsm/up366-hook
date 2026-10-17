package io.github.adkimsm.up366.hook;

/**
 * Read-only JavaScript builders for the in-panel developer tools. They only read the live DOM and
 * computed styles, never click, mutate or call business bridge methods, and always bound their
 * output. Kept free of Android types so the escaping and script shape are unit-testable.
 */
final class PageDevToolsScripts {
    static final String DEFAULT_SELECTOR = "body";
    static final int MAX_DEPTH = 8;
    static final int MAX_NODES = 400;

    private PageDevToolsScripts() {}

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

    /** Bounded DOM outline: one line per element with attributes and child count, no text content. */
    static String outline(String selector) {
        return outline(selector, MAX_DEPTH, MAX_NODES);
    }

    static String outline(String selector, int maxDepth, int maxNodes) {
        return "(function(){try{"
                + "var sel=" + jsString(selector) + ";"
                + "var root=null;try{root=document.querySelector(sel);}catch(e){"
                + "return JSON.stringify({selector:sel,error:'bad_selector'});}"
                + "if(!root)return JSON.stringify({selector:sel,found:false});"
                + "var MAXD=" + Math.max(1, maxDepth) + ",MAXN=" + Math.max(1, maxNodes) + ";"
                + "var n=0,lines=[],truncated=false;"
                + "function pad(d){var s='';for(var i=0;i<d;i++)s+='  ';return s;}"
                + "function walk(el,d){"
                + "if(truncated)return;if(n>=MAXN){truncated=true;return;}n++;"
                + "var a='';"
                + "if(el.attributes){for(var i=0;i<el.attributes.length;i++){var at=el.attributes[i];"
                + "if(at.name==='style')continue;var v=at.value||'';"
                + "if(v.length>60)v=v.slice(0,60)+'~';a+=' '+at.name+'='+v;}}"
                + "lines.push(pad(d)+'<'+String(el.tagName||'').toLowerCase()+'>'+a+' children='+el.children.length);"
                + "if(d>=MAXD){if(el.children.length){lines.push(pad(d+1)+'... '+el.children.length+' children');}return;}"
                + "for(var i=0;i<el.children.length;i++)walk(el.children[i],d+1);}"
                + "walk(root,0);"
                + "return JSON.stringify({selector:sel,found:true,nodes:n,truncated:truncated,lines:lines});"
                + "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";
    }

    /** Computed style and visibility of the first node matching the selector. */
    static String styles(String selector) {
        return "(function(){try{"
                + "var sel=" + jsString(selector) + ";"
                + "var el=null;try{el=document.querySelector(sel);}catch(e){"
                + "return JSON.stringify({selector:sel,error:'bad_selector'});}"
                + "if(!el)return JSON.stringify({selector:sel,found:false});"
                + "var s=getComputedStyle(el),r=el.getBoundingClientRect();"
                + "return JSON.stringify({selector:sel,found:true,tag:el.tagName,id:el.id||'',"
                + "cls:(typeof el.className==='string'?el.className:''),"
                + "display:s.display,visibility:s.visibility,pointerEvents:s.pointerEvents,"
                + "opacity:s.opacity,offsetParent:!!el.offsetParent,"
                + "rect:{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}});"
                + "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";
    }

    /** Tiny read-only page fingerprint used to pick the WebView that is actually on screen. */
    static String locate() {
        return "(function(){try{"
                + "function q(s){try{return document.querySelectorAll(s).length}catch(e){return 0}}"
                + "var href='';try{href=location.href}catch(e){}"
                + "var b=0;try{b=Math.min((document.body&&document.body.textContent"
                + "?document.body.textContent.length:0),20000)}catch(e){b=-1}"
                + "return JSON.stringify({href:href,qst:q('.u3compo-qst'),opts:q('.u3-qst-opt'),"
                + "sel:q('.u3-qst-opt.u3-selected'),card:q('.u3compo-answercard'),audio:q('audio'),"
                + "slides:q('.swiper-slide'),bodyLen:b,"
                + "enterView:!!(window.WebViewReady&&window.WebViewReady.enterView)});"
                + "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";
    }

    /** Compact read-only state overview used to pin down the listening/submit gating. */
    static String gateOverview() {
        return "(function(){try{"
                + "function q(sel){try{return document.querySelectorAll(sel).length}catch(e){return -1}}"
                + "function st(sel){try{var e=document.querySelector(sel);if(!e)return 'absent';"
                + "var s=getComputedStyle(e);"
                + "return s.display+'/'+s.visibility+'/'+s.pointerEvents+'/'+(e.offsetParent?'visible':'hidden');"
                + "}catch(e){return 'err'}}"
                + "function peNone(sel){try{var els=document.querySelectorAll(sel),n=0;"
                + "for(var i=0;i<els.length&&i<200;i++){"
                + "if(getComputedStyle(els[i]).pointerEvents==='none')n++;}return n}catch(e){return -1}}"
                + "function optIds(){try{var els=document.querySelectorAll('[u3-opt-id]'),out=[];"
                + "for(var i=0;i<els.length&&out.length<8;i++){var v=els[i].getAttribute('u3-opt-id');"
                + "if(v&&out.indexOf(v)<0)out.push(v);}return out}catch(e){return []}}"
                + "var audio=[];try{var as=document.querySelectorAll('audio');"
                + "for(var i=0;i<as.length&&i<5;i++){var a=as[i];audio.push({paused:a.paused,ended:a.ended,"
                + "t:Math.round((a.currentTime||0)*10)/10,d:Math.round((a.duration||0)*10)/10});}}catch(e){}"
                + "return JSON.stringify({"
                + "url:location.href,ready:document.readyState,visibility:document.visibilityState,"
                + "bodyLen:(function(){try{return Math.min((document.body&&document.body.textContent"
                + "?document.body.textContent.length:0),20000)}catch(e){return -1}})(),"
                + "enterView:!!(window.WebViewReady&&window.WebViewReady.enterView),"
                + "qst:{all:q('.u3compo-qst'),disabled:q('.u3compo-qst.u3-disable'),"
                + "single:q('.u3compo-singlechoice'),opts:q('.u3-qst-opt'),"
                + "selected:q('.u3-qst-opt.u3-selected'),withOptId:q('[u3-opt-id]'),"
                + "optPointerNone:peNone('.u3-qst-opt'),qstPointerNone:peNone('.u3compo-qst'),"
                + "optIds:optIds()},"
                + "submit:{card:q('.u3compo-answercard'),btn:q('.u3-submit'),style:st('.u3-submit')},"
                + "popup:q('.u3compo-popup'),"
                + "audio:{els:q('audio'),state:audio,medias:q('.u3-audio'),playBtns:q('.u3-mplay-btn')},"
                + "slides:{all:q('.swiper-slide'),active:q('.swiper-slide-active')},"
                + "nav:{next:q('.u3-pagination-buttion-next'),nextDisabled:q('.u3-pagination-buttion-next.u3-disable')}"
                + "});"
                + "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";
    }
}
