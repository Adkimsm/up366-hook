package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.List;

/**
 * Target-process IPC client. Every call records its own {@code traceId}/{@code requestId}, the
 * outcome, the duration and the classified error, so a missing Provider is distinguishable from a
 * rejected or failed call instead of only surfacing as a final failure.
 */
final class IpcClient {
    private static final Uri URI = Uri.parse("content://" + HookStatusProvider.AUTHORITY);

    private IpcClient() {}

    static Bundle call(Context context, String method, Bundle extras, String traceId) {
        return call(context, method, extras, traceId, false);
    }

    static Bundle call(Context context, String method, Bundle extras, String traceId, boolean idempotent) {
        if (context == null) return null;
        Bundle input = extras == null ? new Bundle() : extras;
        String requestId = Trace.newRequestId();
        input.putString(HookStatusProvider.KEY_TRACE_ID, traceId);
        input.putString(HookStatusProvider.KEY_REQUEST_ID, requestId);
        Bundle result = attempt(context, method, new Bundle(input), traceId, requestId, false);
        if (result == null && idempotent) {
            result = attempt(context, method, new Bundle(input), traceId, requestId, true);
        }
        return result;
    }

    private static Bundle attempt(Context context, String method, Bundle input, String traceId,
                                  String requestId, boolean retry) {
        long started = SystemClock.elapsedRealtime();
        try {
            Bundle result = context.getContentResolver().call(URI, method, null, input);
            LogSink.diagnostic("ipc_call", traceId, requestId, "ok", "",
                    SystemClock.elapsedRealtime() - started, "method", method,
                    "retry", Boolean.toString(retry));
            return result;
        } catch (SecurityException error) {
            LogSink.diagnostic("ipc_call", traceId, requestId, "rejected", "forbidden",
                    SystemClock.elapsedRealtime() - started, "method", method,
                    "retry", Boolean.toString(retry));
            return null;
        } catch (Throwable error) {
            LogSink.diagnostic("ipc_call", traceId, requestId, "failed", classify(error),
                    SystemClock.elapsedRealtime() - started, "method", method,
                    "retry", Boolean.toString(retry));
            return null;
        }
    }

    static boolean indexAnswers(Context context, List<AnswerRecord> records, String packageVersion,
                               String traceId) {
        if (records == null || records.isEmpty()) return false;
        Bundle extras = new Bundle();
        extras.putString(HookStatusProvider.KEY_PACKAGE_VERSION, packageVersion);
        extras.putString(HookStatusProvider.KEY_ANSWERS,
                AnswerRecord.toJsonArray(records).toString());
        return call(context, HookStatusProvider.METHOD_INDEX_ANSWERS, extras, traceId, true) != null;
    }

    static boolean reportParseEvent(Context context, String packageVersion, String pageId, int roots,
                                    int flattened, int mapped, int answersPresent, String traceId) {
        Bundle extras = new Bundle();
        extras.putString(HookStatusProvider.KEY_PACKAGE_VERSION, packageVersion);
        extras.putString(HookStatusProvider.KEY_PAGE_ID, pageId);
        extras.putInt(HookStatusProvider.KEY_ROOTS, roots);
        extras.putInt(HookStatusProvider.KEY_FLATTENED, flattened);
        extras.putInt(HookStatusProvider.KEY_MAPPED, mapped);
        extras.putInt(HookStatusProvider.KEY_ANSWERS_PRESENT, answersPresent);
        return call(context, HookStatusProvider.METHOD_REPORT_PARSE_EVENT, extras, traceId) != null;
    }

    private static String classify(Throwable error) {
        if (error == null) return "unknown";
        String name = error.getClass().getSimpleName();
        if ("IllegalArgumentException".equals(name)) return "provider_unavailable";
        if ("DeadObjectException".equals(name) || "RemoteException".equals(name)) return "remote_failure";
        if ("TimeoutException".equals(name)) return "timeout";
        return name;
    }
}
