package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only answer, coverage and diagnostics UI. It only queries {@link HookStatusProvider}; it
 * never calls the target bridge, never writes {@code user_answer}/{@code saveData} and never
 * triggers page automation.
 */
public final class MainActivity extends Activity {
    private static final Uri ANSWERS_URI =
            Uri.parse("content://" + HookStatusProvider.AUTHORITY + "/answers");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "up366-module-ui");
        thread.setDaemon(true);
        return thread;
    });

    private TextView status;
    private TextView coverage;
    private LinearLayout answerSection;
    private TextView diagnostics;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LogSink.setProcessRole("module");
        buildUi();
        refresh();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 28, 32, 24);
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("UP366 答案与诊断（只读）");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, 16, 0, 8);
        status.setText("正在查询模块状态…");
        root.addView(status);

        addSectionTitle(root, "功能开关（修改后需重启天学网生效）");
        addFlag(root, ModuleConfig.ACTIVITY, "Activity 生命周期");
        addFlag(root, ModuleConfig.NETWORK, "OkHttp 请求与响应元数据");
        addFlag(root, ModuleConfig.WEBVIEW, "WebView 页面与桥对象");
        addFlag(root, ModuleConfig.PROBE, "页面 JS 探针");
        addFlag(root, ModuleConfig.BRIDGE, "JS 桥调用参数");
        addFlag(root, ModuleConfig.QUESTION_METADATA, "题包结构与资源元数据（只读）");
        addFlag(root, ModuleConfig.ANSWER_INDEX, "本地答案索引（只读，单独存储）");

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(view -> refresh());
        root.addView(refresh);

        addSectionTitle(root, "答案覆盖率");
        coverage = new TextView(this);
        coverage.setText("（无数据）");
        root.addView(coverage);

        addSectionTitle(root, "答案（本地题包）");
        answerSection = new LinearLayout(this);
        answerSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(answerSection);

        addSectionTitle(root, "诊断：最近解析任务");
        diagnostics = new TextView(this);
        diagnostics.setText("（无数据）");
        root.addView(diagnostics);

        setContentView(scroll);
    }

    private void addSectionTitle(ViewGroup root, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setPadding(0, 24, 0, 8);
        root.addView(view);
    }

    private void addFlag(ViewGroup root, String key, String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setChecked(ModuleConfig.get(this, key));
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            Bundle extras = new Bundle();
            extras.putString(HookStatusProvider.KEY_KEY, key);
            extras.putBoolean(HookStatusProvider.KEY_VALUE, checked);
            worker.execute(() -> HookStatusProviderIo.call(MainActivity.this,
                    HookStatusProvider.METHOD_SET_FLAG, extras));
        });
        root.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));
    }

    private void refresh() {
        worker.execute(() -> {
            Bundle ping = HookStatusProviderIo.call(this, HookStatusProvider.METHOD_PING, null);
            Bundle events = HookStatusProviderIo.call(this,
                    HookStatusProvider.METHOD_RECENT_PARSE_EVENTS, null);
            final StringBuilder answers = new StringBuilder();
            final int[] counts = new int[]{0, 0, 0};
            ContentResolver resolver = getContentResolver();
            try (Cursor cursor = resolver.query(ANSWERS_URI, null, null, null, null)) {
                if (cursor != null) {
                    int pageIndex = cursor.getColumnIndex("page_id");
                    int questionIndex = cursor.getColumnIndex("question_id");
                    int typeIndex = cursor.getColumnIndex("question_type");
                    int valueIndex = cursor.getColumnIndex("answer_value");
                    int formatIndex = cursor.getColumnIndex("answer_format");
                    int confidenceIndex = cursor.getColumnIndex("confidence");
                    while (cursor.moveToNext()) {
                        counts[0]++;
                        String value = valueIndex < 0 ? "" : cursor.getString(valueIndex);
                        if (value == null || value.length() == 0) counts[2]++;
                        else counts[1]++;
                        answers.append(describe(
                                pageIndex < 0 ? "" : cursor.getString(pageIndex),
                                questionIndex < 0 ? "" : cursor.getString(questionIndex),
                                typeIndex < 0 ? -1 : cursor.getInt(typeIndex),
                                value, formatIndex < 0 ? "" : cursor.getString(formatIndex),
                                confidenceIndex < 0 ? "" : cursor.getString(confidenceIndex)))
                                .append('\n');
                        if (counts[0] >= 200) break;
                    }
                }
            } catch (Throwable error) {
                answers.append("查询失败：").append(error.getClass().getSimpleName()).append('\n');
            }
            final String statusText = ping == null
                    ? "Provider 不可用（未收到响应）"
                    : "Provider 正常，已存储答案 " + ping.getInt(HookStatusProvider.KEY_COUNT) + " 条";
            final String coverageText = "已存储=" + counts[0]
                    + "，有答案=" + counts[1] + "，缺失=" + counts[2];
            final String diagnosticsText = events == null || events.getStringArray(
                    HookStatusProvider.KEY_EVENTS) == null
                    || events.getStringArray(HookStatusProvider.KEY_EVENTS).length == 0
                    ? "（无解析任务记录）"
                    : join(events.getStringArray(HookStatusProvider.KEY_EVENTS));
            main.post(() -> {
                status.setText(statusText);
                coverage.setText(coverageText);
                answerSection.removeAllViews();
                addAnswerViews(answers.toString());
                diagnostics.setText(diagnosticsText);
            });
        });
    }

    private void addAnswerViews(String text) {
        if (text.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("（本地答案索引为空：请在目标 App 打开题目页并启用“本地答案索引”）");
            answerSection.addView(empty);
            return;
        }
        for (String line : text.split("\n")) {
            if (line.length() == 0) continue;
            TextView view = new TextView(this);
            view.setText(line);
            view.setPadding(0, 6, 0, 6);
            answerSection.addView(view);
        }
    }

    private static String describe(String pageId, String questionId, int questionType,
                                   String value, String format, String confidence) {
        String prefix = "页面 " + safe(pageId) + " / 题目 " + safe(questionId) + "（" + safe(format) + "）：";
        if (AnswerRecord.FORMAT_COMPOSITE.equals(format)) return prefix + "组合题，答案在子题";
        if (value == null || value.length() == 0) return prefix + "无答案（未在题包中找到）";
        String shown;
        if (AnswerRecord.FORMAT_MULTI_CHOICE.equals(format)) {
            shown = value.replace(",", "、");
        } else if (AnswerRecord.FORMAT_ORDERING.equals(format)) {
            shown = value.replace(",", " -> ");
        } else if (AnswerRecord.FORMAT_FILL_BLANK.equals(format)) {
            shown = mask(value);
        } else {
            shown = value;
        }
        return prefix + "答案：" + shown
                + (AnswerRecord.CONFIDENCE_CONFIRMED.equals(confidence) ? "" : "（" + safe(confidence) + "）");
    }

    private static String mask(String value) {
        if (value.length() <= 2) return value;
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < value.length(); i++) masked.append(i == 0 ? value.charAt(i) : '*');
        return masked.toString();
    }

    private static String join(String[] values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(value);
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
