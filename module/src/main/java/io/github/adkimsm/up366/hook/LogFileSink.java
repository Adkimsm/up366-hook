package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional structured file log for read-only verification. It writes the same already-redacted
 * NDJSON event lines that go to Logcat into the target app's own private files directory, so a
 * whole flow can be captured without Logcat's single-line limit or {@code P0CHUNK} reassembly.
 *
 * <p>The file lives in the target app's private storage; nothing is exposed to other apps and
 * nothing is written back to Logcat. Raw bridge callback scripts are written only through
 * {@link #writeRaw}, and only when {@link Config#LOG_RAW_BRIDGE} is explicitly enabled for a debug
 * session; that content must be treated as sensitive and deleted after use.
 *
 * <p>The sink records a {@link #reason()} for the active state and never reports a bare
 * "unavailable": the panel and the {@code log_file_ready} event always carry the cause and the
 * directory that was attempted.
 */
final class LogFileSink {
    static final String REASON_OK = "ok";
    static final String REASON_INIT_NOT_CALLED = "init_not_called";
    static final String REASON_FILES_DIR_UNAVAILABLE = "files_dir_unavailable";
    static final String REASON_MKDIRS_FAILED = "mkdirs_failed";
    static final String REASON_FALLBACK_EXTERNAL = "fallback_external";

    private static final int MAX_FILES = 5;
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final long RETRY_BACKOFF_MS = 30_000L;
    private static final String DIRECTORY = "up366/logs";
    private static final String FILE_PREFIX = "up366-";
    private static final String FILE_SUFFIX = ".log";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "up366-log-file");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Context context;
    private static volatile File directory;
    private static volatile File current;
    private static volatile String reason = REASON_INIT_NOT_CALLED;
    private static volatile long nextRetryAtMs;
    private static BufferedWriter writer;

    private LogFileSink() {}

    /**
     * Remembers the target context and prepares the log directory. The passed context is used
     * directly; this matters because the first install runs from the {@code Application.attach}
     * hook, where {@code getApplicationContext()} is still {@code null}. Never throws.
     */
    static void init(Context ctx) {
        if (ctx == null) {
            reason = REASON_INIT_NOT_CALLED;
            return;
        }
        try {
            Context app = null;
            try {
                app = ctx.getApplicationContext();
            } catch (Throwable ignored) {
                // Fall back to the passed context below.
            }
            context = app != null ? app : ctx;
            nextRetryAtMs = 0L;
            ensureDirectory();
        } catch (Throwable error) {
            reason = "exception:" + error.getClass().getSimpleName();
        }
    }

    /** Absolute path of the active log file, or empty when file logging is unavailable. */
    static String path() {
        if (ensureDirectory()) {
            File file = current;
            if (file != null) return file.getAbsolutePath();
        }
        return "";
    }

    /** Absolute path of the attempted log directory; shown even when unavailable. */
    static String directoryPath() {
        File dir = directory;
        return dir == null ? "" : dir.getAbsolutePath();
    }

    /** Machine-readable reason for the current state; see the {@code REASON_*} constants. */
    static String reason() {
        return reason;
    }

    /** True when a log file can be written. */
    static boolean available() {
        return ensureDirectory();
    }

    /** True when file logging is currently switched on. */
    static boolean enabled() {
        Context ctx = context;
        return ctx != null && Config.enabled(ctx, Config.LOG_FILE);
    }

    /**
     * Emits one event that names the active log file, so the location (or the exact failure cause)
     * is always discoverable in Logcat and, once enabled, as the first line of the file itself.
     */
    static void announce() {
        String path = path();
        boolean ok = path.length() > 0;
        LogSink.event("log_file_ready",
                "path", ok ? path : "<unavailable>",
                "directory", directoryPath().length() == 0 ? "<none>" : directoryPath(),
                "status", ok ? "ok" : "unavailable",
                "reason", reason,
                "enabled", Boolean.toString(enabled()));
    }

    /** Appends one already-redacted event line, when file logging is enabled. */
    static void write(String line) {
        if (line == null || !enabled() || !available()) return;
        WORKER.execute(() -> append(line));
    }

    /** Appends a raw (unredacted) line; only when file logging and the raw debug switch are on. */
    static void writeRaw(String line) {
        Context ctx = context;
        if (line == null || ctx == null || !Config.enabled(ctx, Config.LOG_FILE)
                || !Config.enabled(ctx, Config.LOG_RAW_BRIDGE) || !available()) return;
        WORKER.execute(() -> append(line));
    }

    private static synchronized boolean ensureDirectory() {
        if (current != null) return true;
        Context ctx = context;
        if (ctx == null) {
            reason = REASON_INIT_NOT_CALLED;
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextRetryAtMs) return false;
        try {
            File base = null;
            try {
                base = ctx.getFilesDir();
            } catch (Throwable ignored) {
                // Recorded as unavailable below unless the external fallback succeeds.
            }
            if (base != null) {
                File candidate = new File(base, DIRECTORY);
                if (candidate.exists() || candidate.mkdirs()) {
                    directory = candidate;
                    current = new File(candidate, newName());
                    reason = REASON_OK;
                    return true;
                }
                directory = candidate;
            }
            File external = externalFilesDir(ctx);
            if (external != null) {
                File fallback = new File(external, DIRECTORY);
                if (fallback.exists() || fallback.mkdirs()) {
                    directory = fallback;
                    current = new File(fallback, newName());
                    reason = REASON_FALLBACK_EXTERNAL;
                    return true;
                }
                if (directory == null) directory = fallback;
            }
            reason = base == null ? REASON_FILES_DIR_UNAVAILABLE : REASON_MKDIRS_FAILED;
        } catch (Throwable error) {
            reason = "exception:" + error.getClass().getSimpleName();
        } finally {
            if (current == null) nextRetryAtMs = now + RETRY_BACKOFF_MS;
        }
        return current != null;
    }

    private static File externalFilesDir(Context ctx) {
        try {
            return ctx.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void append(String line) {
        try {
            if (!ensureWriter()) return;
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (Throwable ignored) {
            closeQuietly();
        }
    }

    private static boolean ensureWriter() throws Exception {
        File file = current;
        if (file == null) return false;
        if (writer == null) writer = open(file);
        if (file.length() > MAX_BYTES) {
            closeQuietly();
            File dir = directory;
            file.delete();
            if (dir != null) {
                current = new File(dir, newName());
                prune(dir);
            }
            writer = open(current);
        }
        return writer != null;
    }

    private static BufferedWriter open(File file) throws Exception {
        return new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8));
    }

    private static void closeQuietly() {
        try {
            if (writer != null) writer.close();
        } catch (Throwable ignored) {
            // Closing is best-effort.
        } finally {
            writer = null;
        }
    }

    private static void prune(File dir) {
        try {
            File[] files = dir.listFiles(
                    (parent, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
            if (files == null || files.length <= MAX_FILES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < files.length - MAX_FILES; i++) files[i].delete();
        } catch (Throwable ignored) {
            // Pruning is best-effort; an extra file must never break logging.
        }
    }

    private static String newName() {
        return FILE_PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + "-" + Process.myPid() + FILE_SUFFIX;
    }
}
