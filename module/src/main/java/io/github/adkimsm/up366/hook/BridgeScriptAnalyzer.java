package io.github.adkimsm.up366.hook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text analysis of JavaScript pushed into a hooked WebView. It tells apart an injected copy of
 * the {@code WebViewJavascriptBridge} source from a real async result callback, and extracts the
 * native callback key so a result can be correlated with the call that produced it. It uses no
 * Android types, so the logic can be unit-tested on the JVM.
 */
final class BridgeScriptAnalyzer {
    private static final Pattern ASYNC_KEY = Pattern.compile("async_callback_key_[0-9]+");
    private static final Pattern KEY_FIELD = Pattern.compile(
            "[\"']?(?:callbackKey|callback_key|key)[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9_.:-]{1,128})");
    private static final Pattern SOURCE_DEFINITION = Pattern.compile(
            "_onAsyncFuncResult\\s*[=:]\\s*function|function\\s+_onAsyncFuncResult"
                    + "|_callJsFuncWithResult\\s*[=:]\\s*function|function\\s+_callJsFuncWithResult"
                    + "|__onResponseJSResult\\s*[=:]\\s*function|function\\s+__onResponseJSResult");
    private static final Pattern AUDIO_CALLBACK = Pattern.compile(
            "(onAudioStateChange(?:V[0-9])?)\\s*\\(([^)]{0,400})\\)");
    private static final Pattern AUDIO_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]{1,64})\"");

    private BridgeScriptAnalyzer() {}

    /**
     * True when the script body itself defines the bridge callbacks (an injected bridge source)
     * rather than invoking one result. The JS bridge source contains the literal decision token
     * {@code async_callback_key_} but never a concrete key, so it must not be read as a callback.
     */
    static boolean isBridgeSource(String script) {
        return script != null && SOURCE_DEFINITION.matcher(script).find();
    }

    /** True when the script delivers a concrete async callback carrying a readable key. */
    static boolean hasAsyncCallback(String script) {
        return extractAsyncKey(script) != null;
    }

    /**
     * Extracts the full native async callback key ({@code async_callback_key_N}) from a callback
     * script, or {@code null} when the key cannot be read. The key is never guessed from timing;
     * an unreadable key is reported as an orphan by the caller.
     */
    static String extractAsyncKey(String script) {
        if (script == null || !script.contains("_onAsyncFuncResult")) return null;
        if (isBridgeSource(script)) return null;
        Matcher token = ASYNC_KEY.matcher(script);
        if (token.find()) return token.group();
        Matcher field = KEY_FIELD.matcher(script);
        if (field.find()) return field.group(1);
        return null;
    }

    /** True when the script may deliver a native audio-state callback into the page. */
    static boolean isAudioStateCallback(String script) {
        return script != null && script.contains("onAudioStateChange");
    }

    /** Name of the invoked audio-state callback, or {@code null} when it is only a definition. */
    static String audioCallbackFunction(String script) {
        Matcher matcher = AUDIO_CALLBACK.matcher(script == null ? "" : script);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Raw argument text of the first audio-state callback, or {@code null} when absent. */
    static String audioCallbackArguments(String script) {
        Matcher matcher = AUDIO_CALLBACK.matcher(script == null ? "" : script);
        return matcher.find() ? matcher.group(2).trim() : null;
    }

    /**
     * Text immediately before the audio callback name (for example {@code window.jsObj.}), so the
     * automation can rebuild the exact same call. Limited to the last 80 characters.
     */
    static String audioCallbackPrefix(String script) {
        Matcher matcher = AUDIO_CALLBACK.matcher(script == null ? "" : script);
        if (!matcher.find()) return null;
        String prefix = script.substring(0, matcher.start(1));
        return prefix.length() <= 80 ? prefix : prefix.substring(prefix.length() - 80);
    }

    /** Extracts the audio id the page passed to {@code playAudioWithParams}, or {@code null}. */
    static String audioIdFromPlayRequest(String payload) {
        if (payload == null) return null;
        Matcher matcher = AUDIO_ID.matcher(payload);
        return matcher.find() ? matcher.group(1) : null;
    }
}
