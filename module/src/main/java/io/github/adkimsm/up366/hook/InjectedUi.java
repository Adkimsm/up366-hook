package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only panel injected as sibling Views on the target Activity's DecorView. It only queries the
 * target-process {@link AnswerStore} and {@link LogSink} ring buffer; it never calls the target
 * bridge, never writes {@code user_answer}/{@code saveData} and never triggers page automation. The
 * existing View tree is never modified — Views are only appended.
 */
final class InjectedUi {
    private static final Map<Activity, Root> ATTACHED =
            Collections.synchronizedMap(new WeakHashMap<Activity, Root>());

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "up366-injected-ui");
        thread.setDaemon(true);
        return thread;
    });

    private InjectedUi() {}

    /** Appends the floating button and (hidden) panel; returns true when newly attached. */
    static boolean show(Activity activity) {
        if (activity == null) return false;
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(activity)) return false;
        }
        ViewGroup decor = decor(activity);
        if (decor == null) return false;

        Root root = new Root(activity);
        int parentWidth = decor.getWidth() > 0 ? decor.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels;
        int parentHeight = decor.getHeight() > 0 ? decor.getHeight()
                : activity.getResources().getDisplayMetrics().heightPixels;
        decor.addView(root.button, buttonParams(activity, parentWidth, parentHeight));
        decor.addView(root.panel, panelParams(activity));
        root.panel.setVisibility(View.GONE);
        root.installDrag(decor);
        ATTACHED.put(activity, root);
        return true;
    }

    /** Removes the injected Views from the Activity, leaving the target View tree otherwise intact. */
    static void hide(Activity activity) {
        Root root = ATTACHED.remove(activity);
        if (root == null) return;
        ViewGroup decor = decor(activity);
        if (decor == null) return;
        try {
            decor.removeView(root.button);
            decor.removeView(root.panel);
        } catch (Throwable ignored) {
            // The DecorView may already be detached; nothing to do.
        }
    }

    private static ViewGroup decor(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            return decor instanceof ViewGroup ? (ViewGroup) decor : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static FrameLayout.LayoutParams buttonParams(Context context, int parentWidth,
                                                         int parentHeight) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int savedX = Config.floatX(context, -1);
        int savedY = Config.floatY(context, -1);
        if (savedX >= 0 && savedY >= 0) {
            params.leftMargin = savedX;
            params.topMargin = savedY;
        } else {
            params.leftMargin = Math.max(0, parentWidth - dp(context, 64));
            params.topMargin = dp(context, 96);
        }
        return params;
    }

    private static FrameLayout.LayoutParams panelParams(Context context) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(context, 300), FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(context, 8);
        params.topMargin = dp(context, 140);
        return params;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /** Holds one injection's Views and refresh entry points for a single Activity. */
    private static final class Root {
        final Activity activity;
        final Button button;
        final ScrollView panel;
        final LinearLayout content;
        final TextView status;
        final TextView coverage;
        final LinearLayout answerSection;
        final LinearLayout versionSection;
        final TextView diagnostics;
        final EditText traceInput;
        final TextView traceResult;

        Root(Activity activity) {
            this.activity = activity;
            button = buildButton(activity);
            panel = new ScrollView(activity);
            panel.setBackground(rounded(Color.WHITE, dp(activity, 10)));
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(activity, 12), dp(activity, 12),
                    dp(activity, 12), dp(activity, 12));
            panel.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            status = new TextView(activity);
            coverage = new TextView(activity);
            answerSection = new LinearLayout(activity);
            answerSection.setOrientation(LinearLayout.VERTICAL);
            versionSection = new LinearLayout(activity);
            versionSection.setOrientation(LinearLayout.VERTICAL);
            diagnostics = new TextView(activity);
            traceInput = new EditText(activity);
            traceResult = new TextView(activity);

            buildContent(activity);
            refresh();
        }

        /** Wires drag-to-move plus tap-to-toggle on the floating button (UI thread only). */
        void installDrag(final ViewGroup parent) {
            final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            button.setOnTouchListener(new View.OnTouchListener() {
                float downX;
                float downY;
                int startLeft;
                int startTop;
                boolean dragging;

                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
                    FrameLayout.LayoutParams params =
                            (FrameLayout.LayoutParams) view.getLayoutParams();
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            startLeft = params.leftMargin;
                            startTop = params.topMargin;
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) (event.getRawX() - downX);
                            int dy = (int) (event.getRawY() - downY);
                            if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                                dragging = true;
                            }
                            if (dragging) {
                                int maxLeft = Math.max(0, parent.getWidth() - view.getWidth());
                                int maxTop = Math.max(0, parent.getHeight() - view.getHeight());
                                params.leftMargin = clamp(startLeft + dx, 0, maxLeft);
                                params.topMargin = clamp(startTop + dy, 0, maxTop);
                                view.setLayoutParams(params);
                                if (panel.getVisibility() == View.VISIBLE) positionPanel();
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (dragging) {
                                Config.setFloatPosition(activity, params.leftMargin,
                                        params.topMargin);
                            } else {
                                togglePanel();
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            return true;
                        default:
                            return false;
                    }
                }
            });
        }

        private void togglePanel() {
            boolean opening = panel.getVisibility() != View.VISIBLE;
            if (opening) {
                positionPanel();
                panel.setVisibility(View.VISIBLE);
                panel.post(this::positionPanel);
                refresh();
            } else {
                panel.setVisibility(View.GONE);
            }
        }

        /** Anchors the panel next to the button so it follows the button's dragged position. */
        private void positionPanel() {
            if (!(button.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            if (!(panel.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            if (!(button.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) button.getParent();
            FrameLayout.LayoutParams buttonParams =
                    (FrameLayout.LayoutParams) button.getLayoutParams();
            FrameLayout.LayoutParams panelLayout =
                    (FrameLayout.LayoutParams) panel.getLayoutParams();
            panelLayout.gravity = Gravity.TOP | Gravity.START;

            int gap = dp(activity, 6);
            int parentWidth = parent.getWidth();
            int parentHeight = parent.getHeight();
            int panelWidth = panel.getWidth() > 0 ? panel.getWidth() : panelLayout.width;
            if (panelWidth <= 0) panelWidth = dp(activity, 300);
            int panelHeight = panel.getHeight();

            int left = clamp(buttonParams.leftMargin, 0, Math.max(0, parentWidth - panelWidth));
            int below = buttonParams.topMargin + button.getHeight() + gap;
            int top = below;
            if (panelHeight > 0 && below + panelHeight > parentHeight) {
                int above = buttonParams.topMargin - gap - panelHeight;
                if (above >= 0) top = above;
            }
            panelLayout.leftMargin = left;
            panelLayout.topMargin = clamp(top, 0,
                    Math.max(0, parentHeight - Math.max(0, panelHeight)));
            panel.setLayoutParams(panelLayout);
        }

        private static int clamp(int value, int min, int max) {
            if (max < min) return min;
            return Math.max(min, Math.min(max, value));
        }

        private Button buildButton(Context context) {
            Button view = new Button(context);
            view.setText("答");
            view.setTextSize(12);
            view.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(21, 101, 192));
            background.setCornerRadius(dp(context, 18));
            view.setBackground(background);
            view.setTextColor(Color.WHITE);
            return view;
        }

        private void buildContent(Context context) {
            TextView title = new TextView(context);
            title.setText("UP366 只读答案（目标进程内）");
            title.setTextSize(16);
            content.addView(title);

            content.addView(section(context, "状态"));
            status.setText("正在查询…");
            content.addView(status);

            Button refresh = new Button(context);
            refresh.setText("刷新");
            refresh.setOnClickListener(view -> refresh());
            content.addView(refresh);

            content.addView(section(context, "功能开关（就地生效）"));
            for (String key : Config.KEYS) {
                CheckBox checkBox = new CheckBox(context);
                checkBox.setText(flagLabel(key));
                checkBox.setChecked(Config.enabled(context, key));
                checkBox.setOnCheckedChangeListener((button, checked) -> {
                    Config.set(context, key, checked);
                    LogSink.diagnostic("panel_toggle_flag", Trace.newTraceId(),
                            Trace.newRequestId(), "ok", "", 0, "key", key,
                            "value", Boolean.toString(checked));
                });
                content.addView(checkBox);
            }

            content.addView(section(context, "答案覆盖率"));
            coverage.setText("（无数据）");
            content.addView(coverage);

            content.addView(section(context, "当前套题答案"));
            answerSection.setOrientation(LinearLayout.VERTICAL);
            content.addView(answerSection);

            content.addView(section(context, "维护"));
            Button clearAll = new Button(context);
            clearAll.setText("清除本地答案索引（全部）");
            clearAll.setOnClickListener(view -> clearIndex(null));
            content.addView(clearAll);
            content.addView(versionSection);

            Button hideButton = new Button(context);
            hideButton.setText("隐藏悬浮按钮（持久化）");
            hideButton.setOnClickListener(view -> {
                Config.setHideFloating(context, true);
                LogSink.diagnostic("panel_hide_floating", Trace.newTraceId(),
                        Trace.newRequestId(), "ok", "", 0);
                hide(activity);
            });
            content.addView(hideButton);

            content.addView(section(context, "诊断：最近解析任务"));
            diagnostics.setText("（无数据）");
            content.addView(diagnostics);

            traceInput.setHint("输入 traceId 查询链路");
            traceInput.setInputType(InputType.TYPE_CLASS_TEXT);
            content.addView(traceInput);
            Button traceQuery = new Button(context);
            traceQuery.setText("查询 traceId 链路");
            traceQuery.setOnClickListener(view -> queryTrace());
            content.addView(traceQuery);
            traceResult.setText("（未查询）");
            content.addView(traceResult);
        }

        private void queryTrace() {
            String traceId = traceInput.getText().toString().trim();
            String requestId = Trace.newRequestId();
            final String trace = traceId;
            WORKER.execute(() -> {
                List<String> lines = LogSink.eventsForTrace(trace);
                LogSink.diagnostic("panel_trace_query", trace, requestId,
                        lines.isEmpty() ? "empty" : "ok", "", 0,
                        "matches", Integer.toString(lines.size()));
                final String text = lines.isEmpty() ? "（无该 traceId 的事件）" : join(lines);
                MAIN.post(() -> traceResult.setText(text));
            });
        }

        private void clearIndex(String packageVersion) {
            final Context context = activity;
            final String requestId = Trace.newRequestId();
            WORKER.execute(() -> {
                int cleared;
                try {
                    cleared = AnswerStore.get(context).clear(packageVersion);
                } catch (Throwable error) {
                    LogSink.diagnostic("panel_clear_index", "", requestId, "failed",
                            error.getClass().getSimpleName(), 0);
                    MAIN.post(() -> status.setText("清除失败：" + error.getClass().getSimpleName()));
                    return;
                }
                LogSink.diagnostic("panel_clear_index", "", requestId, "ok", "", 0,
                        "cleared", Integer.toString(cleared),
                        "packageVersion", Trace.hash(packageVersion));
                refresh();
            });
        }

        void refresh() {
            final Context context = activity;
            WORKER.execute(() -> {
                final int count = AnswerStore.get(context).count();
                final List<String> versions = AnswerStore.get(context).packageVersions();
                final List<String> events = AnswerStore.get(context).recentParseEvents(20);
                final CurrentQuestionSet.Snapshot snapshot = CurrentQuestionSet.snapshot();
                final List<String> setLines = new ArrayList<>();
                if (snapshot == null || snapshot.isEmpty()) {
                    setLines.add("（未检测到当前套题：请在题目页开启“题包结构解析”并等待解析完成）");
                } else {
                    String name = joinNonEmpty(" · ",
                            QuestionSetNameRegistry.bookName(snapshot.bookId),
                            QuestionSetNameRegistry.lastNavigationTitle());
                    setLines.add("套题：" + (name.length() == 0 ? "（未知套题名）" : name));
                    setLines.add("第 " + (snapshot.pageId.length() == 0 ? "?" : snapshot.pageId)
                            + " 页 · " + snapshot.itemCount() + " 题");
                    setLines.addAll(CurrentQuestionSet.renderAnswerLines(snapshot));
                }
                final String coverageText = "本地答案索引 " + count + " 条（当前套题 "
                        + (snapshot == null ? 0 : snapshot.itemCount()) + " 题）";
                final String diagnosticsText = events.isEmpty() ? "（无解析任务记录）" : join(events);
                MAIN.post(() -> {
                    status.setText("Hook 就绪，processRole=" + LogSink.processRole());
                    coverage.setText(coverageText);
                    answerSection.removeAllViews();
                    for (String line : setLines) addCopyableLine(answerSection, line);
                    versionSection.removeAllViews();
                    addVersions(versionSection, versions);
                    diagnostics.setText(diagnosticsText);
                });
            });
        }

        private void addVersions(LinearLayout section, List<String> versions) {
            if (versions.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("（无可清除的题包版本）");
                section.addView(empty);
                return;
            }
            for (String version : versions) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView label = new TextView(activity);
                label.setText("题包 " + Trace.hash(version));
                label.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button clear = new Button(activity);
                clear.setText("清除");
                clear.setOnClickListener(view -> clearIndex(version));
                row.addView(label);
                row.addView(clear);
                section.addView(row);
            }
        }

        /** Adds one answer line that can be long-pressed (or selected) and copied to the clipboard. */
        private void addCopyableLine(LinearLayout section, String text) {
            TextView view = new TextView(activity);
            view.setText(text);
            view.setTextIsSelectable(true);
            view.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
            view.setOnLongClickListener(v -> {
                copyToClipboard(text);
                return true;
            });
            section.addView(view);
        }

        private void copyToClipboard(String text) {
            try {
                ClipboardManager clipboard =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) return;
                clipboard.setPrimaryClip(ClipData.newPlainText("UP366 答案", text));
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Copy is best-effort; a failure must never affect the target app.
            }
        }

        private TextView section(Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextSize(14);
            view.setPadding(0, dp(context, 12), 0, dp(context, 4));
            return view;
        }
    }

    private static String flagLabel(String key) {
        if (Config.ACTIVITY.equals(key)) return "Activity 生命周期";
        if (Config.NETWORK.equals(key)) return "OkHttp 请求与响应元数据";
        if (Config.WEBVIEW.equals(key)) return "WebView 页面与桥对象";
        if (Config.PROBE.equals(key)) return "页面 JS 探针";
        if (Config.BRIDGE.equals(key)) return "JS 桥调用参数";
        if (Config.QUESTION_METADATA.equals(key)) return "题包结构解析（只读）";
        if (Config.ANSWER_INDEX.equals(key)) return "本地答案索引（只读）";
        if (Config.INJECT_UI.equals(key)) return "注入只读面板";
        return key;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(value);
        }
        return builder.toString();
    }

    /** Joins non-empty parts with a separator, skipping blanks. */
    private static String joinNonEmpty(String separator, String... values) {
        StringBuilder builder = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.length() == 0) continue;
                if (builder.length() > 0) builder.append(separator);
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }
}
