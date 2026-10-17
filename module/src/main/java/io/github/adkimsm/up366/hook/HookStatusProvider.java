package io.github.adkimsm.up366.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * IPC main channel owned by the module process. The hooked target process reaches it only through
 * {@code ContentResolver}; the module UI uses the same surface so authorization stays uniform.
 *
 * <p>Every call is authorized per operation and, on success or failure, logged in the module process
 * with the caller's {@code requestId}/{@code traceId} so the two process logs can be joined.
 */
public final class HookStatusProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.adkimsm.up366.hook.provider";

    public static final String METHOD_GET_FLAGS = "get_flags";
    public static final String METHOD_SET_FLAG = "set_flag";
    public static final String METHOD_PING = "ping";
    public static final String METHOD_REPORT_PARSE_EVENT = "report_parse_event";
    public static final String METHOD_INDEX_ANSWERS = "index_answers";
    public static final String METHOD_GET_COVERAGE = "get_coverage";
    public static final String METHOD_CLEAR_ANSWER_CACHE = "clear_answer_cache";
    public static final String METHOD_RECENT_PARSE_EVENTS = "recent_parse_events";
    public static final String METHOD_GET_ANSWER = "get_answer";
    public static final String METHOD_GET_ANSWERS = "get_answers";

    public static final String KEY_TRACE_ID = "traceId";
    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_PACKAGE_VERSION = "packageVersion";
    public static final String KEY_PAGE_ID = "pageId";
    public static final String KEY_QUESTION_ID = "questionId";
    public static final String KEY_KEY = "key";
    public static final String KEY_VALUE = "value";
    public static final String KEY_ANSWERS = "answers";
    public static final String KEY_COVERAGE = "coverage";
    public static final String KEY_EVENTS = "events";
    public static final String KEY_COUNT = "count";
    public static final String KEY_ANSWER = "answer";
    public static final String KEY_STATUS = "status";
    public static final String KEY_ROOTS = "roots";
    public static final String KEY_FLATTENED = "flattened";
    public static final String KEY_MAPPED = "mapped";
    public static final String KEY_ANSWERS_PRESENT = "answersPresent";
    public static final String KEY_ERROR_CODE = "errorCode";

    static final int MATCH_STATUS = 1;
    static final int MATCH_ANSWERS = 2;
    static final int MATCH_COVERAGE = 3;
    static final int MATCH_CONFIG = 4;

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "status", MATCH_STATUS);
        MATCHER.addURI(AUTHORITY, "answers", MATCH_ANSWERS);
        MATCHER.addURI(AUTHORITY, "coverage", MATCH_COVERAGE);
        MATCHER.addURI(AUTHORITY, "config", MATCH_CONFIG);
    }

    @Override
    public boolean onCreate() {
        LogSink.setProcessRole("module");
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        long started = SystemClock.elapsedRealtime();
        Bundle input = extras == null ? new Bundle() : extras;
        String traceId = input.getString(KEY_TRACE_ID, "");
        String requestId = input.getString(KEY_REQUEST_ID, "");
        String caller;
        try {
            caller = resolveCaller();
        } catch (Throwable error) {
            caller = null;
        }
        try {
            authorize(method, caller);
        } catch (SecurityException error) {
            LogSink.diagnostic("provider_call", traceId, requestId, "forbidden", "forbidden",
                    SystemClock.elapsedRealtime() - started, "method", method,
                    "callerCategory", callerCategory(caller));
            throw error;
        }
        Bundle result;
        try {
            result = dispatch(method, input, caller);
        } catch (Throwable error) {
            LogSink.diagnostic("provider_call", traceId, requestId, "failed",
                    error.getClass().getSimpleName(), SystemClock.elapsedRealtime() - started,
                    "method", method, "callerCategory", callerCategory(caller));
            throw error instanceof SecurityException ? (SecurityException) error
                    : new IllegalArgumentException("provider call failed: " + method);
        }
        if (result == null) result = new Bundle();
        result.putString(KEY_TRACE_ID, traceId);
        result.putString(KEY_REQUEST_ID, requestId);
        result.putString(KEY_STATUS, "ok");
        LogSink.diagnostic("provider_call", traceId, requestId, "ok", "",
                SystemClock.elapsedRealtime() - started, "method", method,
                "callerCategory", callerCategory(caller));
        return result;
    }

    private Bundle dispatch(String method, Bundle input, String caller) {
        if (METHOD_GET_FLAGS.equals(method)) return getFlags();
        if (METHOD_PING.equals(method)) return ping();
        if (METHOD_SET_FLAG.equals(method)) return setFlag(input);
        if (METHOD_REPORT_PARSE_EVENT.equals(method)) return reportParseEvent(input);
        if (METHOD_INDEX_ANSWERS.equals(method)) return indexAnswers(input);
        if (METHOD_GET_COVERAGE.equals(method)) return getCoverage(input);
        if (METHOD_CLEAR_ANSWER_CACHE.equals(method)) return clearAnswerCache(input);
        if (METHOD_RECENT_PARSE_EVENTS.equals(method)) return recentParseEvents(input);
        if (METHOD_GET_ANSWER.equals(method)) return getAnswer(input);
        if (METHOD_GET_ANSWERS.equals(method)) return getAnswers(input);
        throw new IllegalArgumentException("unknown method: " + method);
    }

    private Bundle getFlags() {
        Bundle result = new Bundle();
        for (String key : ModuleConfig.KEYS) result.putBoolean(key, ModuleConfig.get(context(), key));
        return result;
    }

    private Bundle ping() {
        Bundle result = new Bundle();
        result.putString("role", "module");
        result.putInt(KEY_COUNT, AnswerStore.get(context()).count());
        return result;
    }

    private Bundle setFlag(Bundle input) {
        String key = input.getString(KEY_KEY);
        if (key != null && ModuleConfig.isKnownKey(key)) {
            ModuleConfig.set(context(), key, input.getBoolean(KEY_VALUE));
            Broadcasts.send(context(), Broadcasts.CONFIG_CHANGED);
        } else {
            throw new IllegalArgumentException("unknown config key");
        }
        return getFlags();
    }

    private Bundle reportParseEvent(Bundle input) {
        AnswerStore.get(context()).recordParseEvent(System.currentTimeMillis(),
                input.getString(KEY_TRACE_ID, ""),
                Trace.hash(input.getString(KEY_PACKAGE_VERSION, "")),
                input.getString(KEY_PAGE_ID, ""),
                input.getInt(KEY_ROOTS, -1), input.getInt(KEY_FLATTENED, -1),
                input.getInt(KEY_MAPPED, -1), input.getInt(KEY_ANSWERS_PRESENT, -1),
                "parsed", input.getString(KEY_ERROR_CODE, ""));
        Broadcasts.send(context(), Broadcasts.PARSE_FINISHED);
        return new Bundle();
    }

    private Bundle indexAnswers(Bundle input) {
        String json = input.getString(KEY_ANSWERS, "");
        List<AnswerRecord> records = AnswerRecord.fromJsonArray(json);
        int written = AnswerStore.get(context()).upsert(records);
        Bundle result = new Bundle();
        result.putInt(KEY_COUNT, written);
        result.putString(KEY_PACKAGE_VERSION, input.getString(KEY_PACKAGE_VERSION, ""));
        if (written > 0) Broadcasts.send(context(), Broadcasts.INDEX_UPDATED);
        return result;
    }

    private Bundle getCoverage(Bundle input) {
        String packageVersion = input.getString(KEY_PACKAGE_VERSION, "");
        List<AnswerRecord> records = AnswerStore.get(context()).queryByBook(packageVersion);
        AnswerCoverageReport report = new AnswerCoverageReport();
        for (AnswerRecord record : records) report.record(record);
        Bundle result = new Bundle();
        try {
            result.putString(KEY_COVERAGE, report.toJson().toString());
        } catch (JSONException error) {
            result.putString(KEY_ERROR_CODE, "coverage_encode_failed");
        }
        return result;
    }

    private Bundle clearAnswerCache(Bundle input) {
        int cleared = AnswerStore.get(context())
                .clear(input.getString(KEY_PACKAGE_VERSION, ""));
        Bundle result = new Bundle();
        result.putInt(KEY_COUNT, cleared);
        Broadcasts.send(context(), Broadcasts.INDEX_UPDATED);
        return result;
    }

    private Bundle recentParseEvents(Bundle input) {
        List<String> events = AnswerStore.get(context()).recentParseEvents(50);
        Bundle result = new Bundle();
        result.putStringArray(KEY_EVENTS, events.toArray(new String[0]));
        return result;
    }

    private Bundle getAnswer(Bundle input) {
        AnswerRecord record = AnswerStore.get(context())
                .get(input.getString(KEY_PACKAGE_VERSION, ""), input.getString(KEY_QUESTION_ID, ""));
        Bundle result = new Bundle();
        if (record == null) {
            result.putString(KEY_ERROR_CODE, "not_found");
            return result;
        }
        try {
            result.putString(KEY_ANSWER, record.toJson().toString());
        } catch (JSONException error) {
            result.putString(KEY_ERROR_CODE, "encode_failed");
        }
        return result;
    }

    private Bundle getAnswers(Bundle input) {
        String packageVersion = input.getString(KEY_PACKAGE_VERSION, "");
        String pageId = input.getString(KEY_PAGE_ID, "");
        List<AnswerRecord> records = pageId.length() == 0
                ? AnswerStore.get(context()).queryByBook(packageVersion)
                : AnswerStore.get(context()).queryByPage(packageVersion, pageId);
        Bundle result = new Bundle();
        result.putString(KEY_ANSWERS, AnswerRecord.toJsonArray(records).toString());
        result.putInt(KEY_COUNT, records.size());
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String caller;
        try {
            caller = resolveCaller();
        } catch (Throwable error) {
            caller = null;
        }
        if (!ModuleConfig.MODULE_PACKAGE.equals(caller)) {
            LogSink.diagnostic("provider_query", "", "", "forbidden", "forbidden", 0,
                    "uri", String.valueOf(uri), "callerCategory", callerCategory(caller));
            throw new SecurityException("answer queries are restricted to the module UI");
        }
        int match = MATCHER.match(uri);
        MatrixCursor cursor;
        if (match == MATCH_ANSWERS) {
            cursor = new MatrixCursor(new String[]{"index_key", "page_id", "question_id",
                    "question_type", "answer_value", "answer_format", "source_type", "confidence"});
            for (AnswerRecord record : AnswerStore.get(context()).queryAll(500)) {
                cursor.addRow(new Object[]{record.indexKey(), record.pageId, record.questionId,
                        record.questionType, record.answerValue, record.answerFormat,
                        record.sourceType, record.confidence});
            }
        } else if (match == MATCH_COVERAGE) {
            cursor = new MatrixCursor(new String[]{"metric", "value"});
            cursor.addRow(new Object[]{"stored_answers", AnswerStore.get(context()).count()});
        } else {
            cursor = new MatrixCursor(new String[]{"metric", "value"});
            cursor.addRow(new Object[]{"status", "ok"});
        }
        return cursor;
    }

    @Override public String getType(Uri uri) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("insert is not permitted; use call()");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("delete is not permitted; use call()");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("update is not permitted; use call()");
    }

    private void authorize(String method, String caller) {
        if (caller == null) throw new SecurityException("unresolvable caller");
        boolean module = ModuleConfig.MODULE_PACKAGE.equals(caller);
        boolean target = ModuleConfig.TARGET_PACKAGE.equals(caller);
        if (!module && !target) throw new SecurityException("unexpected caller: " + caller);
        if (METHOD_SET_FLAG.equals(method)
                || METHOD_GET_COVERAGE.equals(method)
                || METHOD_CLEAR_ANSWER_CACHE.equals(method)
                || METHOD_RECENT_PARSE_EVENTS.equals(method)
                || METHOD_GET_ANSWER.equals(method)
                || METHOD_GET_ANSWERS.equals(method)) {
            if (!module) throw new SecurityException("operation restricted to the module UI: " + method);
            return;
        }
        if (METHOD_INDEX_ANSWERS.equals(method) || METHOD_REPORT_PARSE_EVENT.equals(method)) {
            if (!target && !module) {
                throw new SecurityException("write restricted to the target process: " + method);
            }
            return;
        }
        // get_flags / ping: both the module UI and the target process may read.
    }

    private String resolveCaller() throws PackageManager.NameNotFoundException {
        String caller = getCallingPackage();
        if (caller != null) return caller;
        int uid = Binder.getCallingUid();
        for (String candidate : new String[]{ModuleConfig.MODULE_PACKAGE, ModuleConfig.TARGET_PACKAGE}) {
            if (context().getPackageManager().getApplicationInfo(candidate, 0).uid == uid) {
                return candidate;
            }
        }
        return null;
    }

    private static String callerCategory(String caller) {
        if (ModuleConfig.MODULE_PACKAGE.equals(caller)) return "module";
        if (ModuleConfig.TARGET_PACKAGE.equals(caller)) return "target_hook";
        return "unknown";
    }

    private Context context() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("provider has no context");
        return context;
    }
}
