package io.github.adkimsm.up366.hook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Answer and parse-summary storage owned by the target process. The database lives in the target
 * app's private directory and is opened only from inside the hooked target process; there is no
 * provider, IPC or exported surface. Cleared together with the target app's data.
 */
final class AnswerStore extends SQLiteOpenHelper {
    private static final String NAME = "up366_answers.db";
    private static final int VERSION = 1;

    static final String TABLE_ANSWERS = "answers";
    static final String TABLE_PARSE_EVENTS = "parse_events";

    private static AnswerStore instance;

    private AnswerStore(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    static synchronized AnswerStore get(Context context) {
        if (instance == null) instance = new AnswerStore(context);
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE " + TABLE_ANSWERS + " ("
                + "index_key TEXT PRIMARY KEY,"
                + "package_version TEXT, book_id TEXT, chapter_id TEXT, page_id TEXT,"
                + "question_id TEXT, parent_question_id TEXT, question_type INTEGER,"
                + "answer_value TEXT, answer_format TEXT, source_type TEXT,"
                + "source_path TEXT, confidence TEXT, missing_reason TEXT,"
                + "observed_at INTEGER, updated_at INTEGER)");
        database.execSQL("CREATE INDEX idx_answers_page ON " + TABLE_ANSWERS
                + "(package_version, page_id)");
        database.execSQL("CREATE TABLE " + TABLE_PARSE_EVENTS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "observed_at INTEGER, trace_id TEXT, package_version_hash TEXT,"
                + "page_id TEXT, roots INTEGER, flattened INTEGER, mapped INTEGER,"
                + "answers_present INTEGER, status TEXT, error_code TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        database.execSQL("DROP TABLE IF EXISTS " + TABLE_ANSWERS);
        database.execSQL("DROP TABLE IF EXISTS " + TABLE_PARSE_EVENTS);
        onCreate(database);
    }

    int upsert(List<AnswerRecord> records) {
        if (records == null || records.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        SQLiteDatabase database = getWritableDatabase();
        int written = 0;
        database.beginTransaction();
        try {
            for (AnswerRecord record : records) {
                if (record == null || record.questionId.length() == 0) continue;
                ContentValues values = new ContentValues();
                values.put("index_key", record.indexKey());
                values.put("package_version", record.packageVersion);
                values.put("book_id", record.bookId);
                values.put("chapter_id", record.chapterId);
                values.put("page_id", record.pageId);
                values.put("question_id", record.questionId);
                values.put("parent_question_id", record.parentQuestionId);
                values.put("question_type", record.questionType);
                values.put("answer_value", record.answerValue);
                values.put("answer_format", record.answerFormat);
                values.put("source_type", record.sourceType);
                values.put("source_path", record.sourcePath);
                values.put("confidence", record.confidence);
                values.put("missing_reason", record.missingReason);
                values.put("observed_at", record.observedAt);
                values.put("updated_at", now);
                database.insertWithOnConflict(TABLE_ANSWERS, null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
                written++;
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return written;
    }

    List<AnswerRecord> queryByPage(String packageVersion, String pageId) {
        return query("package_version=? AND page_id=?",
                new String[]{safe(packageVersion), safe(pageId)}, 500);
    }

    List<AnswerRecord> queryByBook(String packageVersion) {
        return query("package_version=?", new String[]{safe(packageVersion)}, 500);
    }

    List<AnswerRecord> queryAll(int limit) {
        return query(null, null, limit);
    }

    AnswerRecord get(String packageVersion, String questionId) {
        List<AnswerRecord> records = query("package_version=? AND question_id=?",
                new String[]{safe(packageVersion), safe(questionId)}, 1);
        return records.isEmpty() ? null : records.get(0);
    }

    int count() {
        return count(null, null);
    }

    int count(String packageVersion) {
        return count("package_version=?", new String[]{safe(packageVersion)});
    }

    int clear(String packageVersion) {
        SQLiteDatabase database = getWritableDatabase();
        if (packageVersion == null || packageVersion.length() == 0) {
            return database.delete(TABLE_ANSWERS, null, null);
        }
        return database.delete(TABLE_ANSWERS, "package_version=?",
                new String[]{packageVersion});
    }

    /** Distinct package versions currently holding answer records, newest insertion order first. */
    List<String> packageVersions() {
        List<String> versions = new ArrayList<>();
        SQLiteDatabase database = getReadableDatabase();
        try (Cursor cursor = database.rawQuery("SELECT DISTINCT package_version FROM "
                + TABLE_ANSWERS + " WHERE package_version IS NOT NULL"
                + " AND package_version <> '' ORDER BY package_version ASC", null)) {
            while (cursor.moveToNext()) {
                String version = cursor.getString(0);
                if (version != null && version.length() > 0) versions.add(version);
            }
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        return versions;
    }

    void recordParseEvent(long observedAt, String traceId, String packageVersionHash,
                          String pageId, int roots, int flattened, int mapped,
                          int answersPresent, String status, String errorCode) {
        ContentValues values = new ContentValues();
        values.put("observed_at", observedAt);
        values.put("trace_id", safe(traceId));
        values.put("package_version_hash", safe(packageVersionHash));
        values.put("page_id", safe(pageId));
        values.put("roots", roots);
        values.put("flattened", flattened);
        values.put("mapped", mapped);
        values.put("answers_present", answersPresent);
        values.put("status", safe(status));
        values.put("error_code", safe(errorCode));
        SQLiteDatabase database = getWritableDatabase();
        database.insert(TABLE_PARSE_EVENTS, null, values);
        database.execSQL("DELETE FROM " + TABLE_PARSE_EVENTS + " WHERE id NOT IN "
                + "(SELECT id FROM " + TABLE_PARSE_EVENTS + " ORDER BY id DESC LIMIT 200)");
    }

    List<String> recentParseEvents(int limit) {
        List<String> events = new ArrayList<>();
        SQLiteDatabase database = getReadableDatabase();
        try (Cursor cursor = database.query(TABLE_PARSE_EVENTS,
                new String[]{"observed_at", "trace_id", "package_version_hash", "page_id",
                        "roots", "flattened", "mapped", "answers_present", "status", "error_code"},
                null, null, null, null, "id DESC", Integer.toString(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                events.add("observedAt=" + cursor.getLong(0)
                        + ",traceId=" + cursor.getString(1)
                        + ",packageVersionHash=" + cursor.getString(2)
                        + ",pageId=" + cursor.getString(3)
                        + ",roots=" + cursor.getInt(4)
                        + ",flattened=" + cursor.getInt(5)
                        + ",mapped=" + cursor.getInt(6)
                        + ",answersPresent=" + cursor.getInt(7)
                        + ",status=" + cursor.getString(8)
                        + ",errorCode=" + cursor.getString(9));
            }
        }
        return events;
    }

    private List<AnswerRecord> query(String selection, String[] args, int limit) {
        List<AnswerRecord> records = new ArrayList<>();
        SQLiteDatabase database = getReadableDatabase();
        try (Cursor cursor = database.query(TABLE_ANSWERS, null, selection, args,
                null, null, "page_id ASC, question_id ASC", Integer.toString(Math.max(1, limit)))) {
            while (cursor.moveToNext()) records.add(fromCursor(cursor));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        return records;
    }

    private int count(String selection, String[] args) {
        SQLiteDatabase database = getReadableDatabase();
        try (Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM " + TABLE_ANSWERS
                + (selection == null ? "" : " WHERE " + selection), args)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static AnswerRecord fromCursor(Cursor cursor) {
        return new AnswerRecord(
                cursor.getString(cursor.getColumnIndexOrThrow("package_version")),
                cursor.getString(cursor.getColumnIndexOrThrow("book_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("chapter_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("page_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("question_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("parent_question_id")),
                cursor.getInt(cursor.getColumnIndexOrThrow("question_type")),
                cursor.getString(cursor.getColumnIndexOrThrow("answer_value")),
                cursor.getString(cursor.getColumnIndexOrThrow("answer_format")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_type")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("confidence")),
                cursor.getString(cursor.getColumnIndexOrThrow("missing_reason")),
                cursor.getLong(cursor.getColumnIndexOrThrow("observed_at")));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
