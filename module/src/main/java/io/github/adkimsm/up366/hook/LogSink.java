package io.github.adkimsm.up366.hook;

import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

final class LogSink {
    private static final String TAG = "UP366-P0";
    private static final int MAX_LOG_CHARS = 3000;

    /** Bounded in-memory ring buffer, queried by the injected panel. Never written to disk. */
    private static final int RING_CAPACITY = 200;
    private static final Deque<RingEntry> RING = new ArrayDeque<>();
    private static final AtomicLong CHUNK_IDS = new AtomicLong();
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(\\\"?(?:access[_-]?token|refresh[_-]?token|token|authorization|cookie|password|passwd|secret|phone|mobile|idcard|user[_-]?answer|answer(?:[_-]?text)?|score|user[_-]?score|timestamp|duration|record[_-]?url)\\\"?\\s*[:=]\\s*\\\"?)[^\\\",&}\\s]+"
    );
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)(\\\"?(?:uId|userId|clientId|traceparent|tracestate)\\\"?\\s*[:=]\\s*\\\"?)([^\\\",&}\\s]+)"
    );
    private static volatile ClassLoader classLoader;
    private static volatile String processRole = "unknown";

    private LogSink() {}

    static void init(ClassLoader loader) {
        classLoader = loader;
    }

    /** Distinguishes the module process ({@code module}) from the hooked target process ({@code target_hook}). */
    static void setProcessRole(String role) {
        processRole = role == null || role.length() == 0 ? "unknown" : role;
    }

    static String processRole() {
        return processRole;
    }

    static void event(String event, String... fields) {
        String line = build(event, fields);
        String traceId = null;
        String requestId = null;
        if (fields != null) {
            for (int i = 0; i + 1 < fields.length; i += 2) {
                if ("traceId".equals(fields[i])) traceId = fields[i + 1];
                else if ("requestId".equals(fields[i])) requestId = fields[i + 1];
            }
        }
        record(event, line, traceId, requestId);
        emit(line);
        LogFileSink.write(line);
    }

    /**
     * Builds the same JSON line but writes it only to the file sink, never to Logcat or the ring
     * buffer. Used for raw (unredacted) debug payloads that must stay out of Logcat; the file is
     * only written when both file logging and the raw debug switch are on.
     */
    static void rawEvent(String event, String... fields) {
        LogFileSink.writeRaw(build(event, fields));
    }

    private static String build(String event, String... fields) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
        field(json, "pid", Integer.toString(Process.myPid()));
        field(json, "sdk", Integer.toString(Build.VERSION.SDK_INT));
        field(json, "processRole", processRole);
        field(json, "event", event);
        if (fields != null) {
            for (int i = 0; i + 1 < fields.length; i += 2) {
                field(json, fields[i], fields[i + 1]);
            }
        }
        json.append('}');
        return json.toString();
    }

    /** Records every emitted diagnostic into the bounded ring buffer, in emission order. */
    private static void record(String event, String line, String traceId, String requestId) {
        synchronized (RING) {
            RING.addLast(new RingEntry(event, line,
                    traceId == null ? "" : traceId,
                    requestId == null ? "" : requestId));
            while (RING.size() > RING_CAPACITY) RING.removeFirst();
        }
    }

    /** Newest-first snapshot of recorded events, most recent first, capped at {@code limit}. */
    static List<String> recentEvents(int limit) {
        List<String> result = new ArrayList<>();
        synchronized (RING) {
            java.util.Iterator<RingEntry> iterator = RING.descendingIterator();
            while (iterator.hasNext() && result.size() < Math.max(1, limit)) {
                result.add(iterator.next().line);
            }
        }
        return result;
    }

    /** Chronological lines whose {@code traceId} matches, for one flow's chain query. */
    static List<String> eventsForTrace(String traceId) {
        List<String> result = new ArrayList<>();
        if (traceId == null || traceId.length() == 0) return result;
        synchronized (RING) {
            for (RingEntry entry : RING) {
                if (traceId.equals(entry.traceId)) result.add(entry.line);
            }
        }
        return result;
    }

    /** Clears the in-memory ring buffer; used by the panel's log maintenance action. */
    static void clearBuffer() {
        synchronized (RING) {
            RING.clear();
        }
    }


    /**
     * Emits a structured diagnostic carrying the shared cross-process fields. Events without a
     * traceId are marked {@code orphan}, never paired by timestamp.
     */
    static void diagnostic(String event, String traceId, String requestId,
                           String status, String errorCode, long durationMs, String... fields) {
        java.util.List<String> all = new java.util.ArrayList<>(16);
        all.add("traceId");
        all.add(traceId == null || traceId.length() == 0 ? "orphan" : traceId);
        all.add("requestId");
        all.add(requestId == null || requestId.length() == 0 ? "orphan" : requestId);
        all.add("status");
        all.add(status == null || status.length() == 0
                ? (traceId == null || traceId.length() == 0 ? "orphan" : "") : status);
        all.add("errorCode");
        all.add(errorCode == null ? "" : errorCode);
        all.add("durationMs");
        all.add(Long.toString(durationMs < 0 ? 0 : durationMs));
        if (fields != null) {
            for (String field : fields) all.add(field);
        }
        event(event, all.toArray(new String[0]));
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

    /** Stable short hash used for identifier and version correlation fields. */
    static String hash(String value) {
        if (value == null) return "";
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

    private static String shortHash(String value) {
        return hash(value);
    }

    /** One recorded diagnostic line keyed by its correlation identifiers. */
    private static final class RingEntry {
        final String event;
        final String line;
        final String traceId;
        final String requestId;

        RingEntry(String event, String line, String traceId, String requestId) {
            this.event = event;
            this.line = line;
            this.traceId = traceId;
            this.requestId = requestId;
        }
    }
}
