package io.github.adkimsm.up366.hook;

import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

final class LogSink {
    private static final String TAG = "UP366-P0";
    private static final int MAX_LOG_CHARS = 3000;
    private static final AtomicLong CHUNK_IDS = new AtomicLong();
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\\\"?(?:access[_-]?token|refresh[_-]?token|token|authorization|cookie|password|passwd|secret|phone|mobile|idcard|user[_-]?answer|answer(?:[_-]?text)?|score|user[_-]?score|timestamp|duration|record[_-]?url)\\\"?\\s*[:=]\\s*\\\"?)[^\\\",&}\\s]+"
    );
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)(\\\"?(?:uId|userId|clientId|traceparent|tracestate)\\\"?\\s*[:=]\\s*\\\"?)([^\\\",&}\\s]+)"
    );
    private static volatile ClassLoader classLoader;

    private LogSink() {}

    static void init(ClassLoader loader) {
        classLoader = loader;
    }

    static void event(String event, String... fields) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
        field(json, "pid", Integer.toString(Process.myPid()));
        field(json, "sdk", Integer.toString(Build.VERSION.SDK_INT));
        field(json, "event", event);
        for (int i = 0; i + 1 < fields.length; i += 2) field(json, fields[i], fields[i + 1]);
        json.append('}');
        emit(json.toString());
    }

    static void error(String event, Throwable error) {
        event(event, "error_type", error == null ? "unknown" : error.getClass().getName());
    }

    static String limit(Object value, int max) {
        String text = String.valueOf(value);
        return text.length() <= max ? text : text.substring(0, max) + "...<truncated>";
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String redact(String value) {
        if (value == null) return "";
        String redacted = SECRET.matcher(value).replaceAll("$1<redacted>");
        Matcher matcher = IDENTIFIER.matcher(redacted);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(
                    matcher.group(1) + shortHash(matcher.group(2))));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    static String redactValue(String value) {
        return redact(value);
    }

    static String redactArgs(Object[] args) {
        return redact(Arrays.deepToString(args == null ? new Object[0] : args));
    }

    static String summarize(Object value, int max) {
        if (value == null) return "null";
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 16); i++) {
                if (i > 0) hex.append(' ');
                hex.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            }
            return "<binary size=" + bytes.length + " hex=" + hex + ">";
        }
        if (value instanceof CharSequence) {
            return "<string length=" + ((CharSequence) value).length() + ">";
        }
        return "<" + value.getClass().getName() + ">";
    }

    static ClassLoader classLoader() {
        return classLoader;
    }

    private static void emit(String line) {
        if (line.length() <= MAX_LOG_CHARS) {
            Log.i(TAG, TAG + " " + line);
            return;
        }
        long id = CHUNK_IDS.incrementAndGet();
        int total = (line.length() + MAX_LOG_CHARS - 1) / MAX_LOG_CHARS;
        for (int index = 0; index < total; index++) {
            int start = index * MAX_LOG_CHARS;
            int end = Math.min(line.length(), start + MAX_LOG_CHARS);
            Log.i(TAG, TAG + " P0CHUNK id=" + id + " part=" + (index + 1) + "/" + total + " "
                    + line.substring(start, end));
        }
    }

    private static void field(StringBuilder json, String key, String value) {
        if (json.length() > 1) json.append(',');
        json.append('"').append(escape(key)).append("\":\"").append(escape(limit(value, 16384))).append('"');
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '"') out.append("\\\"");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20) out.append(' ');
            else out.append(c);
        }
        return out.toString();
    }

    private static String shortHash(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) result.append(String.format(Locale.US, "%02x", bytes[i]));
            return result.toString();
        } catch (Throwable ignored) {
            return "<hashed>";
        }
    }
}
