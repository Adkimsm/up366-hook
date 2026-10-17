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

import org.json.JSONArray;
import org.json.JSONObject;

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
        final TextView logStatus;
        final EditText devSelector;
        final TextView devOutput;
        final TextView automationOutput;
        final EditText traceInput;
        final TextView traceResult;
        String lastDevDump = "";
        String lastDevSelector = PageDevToolsScripts.DEFAULT_SELECTOR;

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
            logStatus = new TextView(activity);
            devSelector = new EditText(activity);
            devOutput = new TextView(activity);
            automationOutput = new TextView(activity);
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
                    if (Config.LOG_FILE.equals(key) && checked) {
                        LogFileSink.announce();
                        String path = LogFileSink.path();
                        Toast.makeText(context, path.length() == 0
                                ? "日志不可用：" + LogFileSink.reason()
                                : "日志写入：" + path, Toast.LENGTH_LONG).show();
                    }
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

            content.addView(section(context, "日志文件"));
            logStatus.setText("（未初始化）");
            content.addView(logStatus);
            Button copyLogPath = new Button(context);
            copyLogPath.setText("复制日志文件路径");
            copyLogPath.setOnClickListener(view -> copyToClipboard(LogFileSink.path()));
            content.addView(copyLogPath);

            traceInput.setHint("输入 traceId 查询链路");
            traceInput.setInputType(InputType.TYPE_CLASS_TEXT);
            content.addView(traceInput);
            Button traceQuery = new Button(context);
            traceQuery.setText("查询 traceId 链路");
            traceQuery.setOnClickListener(view -> queryTrace());
            content.addView(traceQuery);
            traceResult.setText("（未查询）");
            content.addView(traceResult);

            content.addView(section(context, "开发者工具（只读）"));
            devSelector.setText(PageDevToolsScripts.DEFAULT_SELECTOR);
            devSelector.setHint("CSS 选择器，如 .u3compo-qst / .u3-qst-opt / .u3-submit");
            devSelector.setInputType(InputType.TYPE_CLASS_TEXT);
            content.addView(devSelector);

            Button outlineButton = new Button(context);
            outlineButton.setText("DOM 概要");
            outlineButton.setOnClickListener(view -> runDevTool("outline"));
            content.addView(outlineButton);

            Button stylesButton = new Button(context);
            stylesButton.setText("计算样式");
            stylesButton.setOnClickListener(view -> runDevTool("styles"));
            content.addView(stylesButton);

            Button gateButton = new Button(context);
            gateButton.setText("门控概览");
            gateButton.setOnClickListener(view -> runDevTool("gate"));
            content.addView(gateButton);

            Button audioButton = new Button(context);
            audioButton.setText("原生音频回调");
            audioButton.setOnClickListener(view -> showAudioCallbacks());
            content.addView(audioButton);

            Button devCopy = new Button(context);
            devCopy.setText("复制结果");
            devCopy.setOnClickListener(view -> copyToClipboard(devOutput.getText().toString()));
            content.addView(devCopy);

            Button devExport = new Button(context);
            devExport.setText("写入日志文件（需 raw 开关）");
            devExport.setOnClickListener(view -> exportDevDump());
            content.addView(devExport);

            devOutput.setTextSize(11);
            devOutput.setTextIsSelectable(true);
            devOutput.setText("（未查询：先打开一次题目页，再选选择器执行）");
            content.addView(devOutput);

            content.addView(section(context, "自动化（默认关，需先打开对应开关）"));
            Button speedButton = new Button(context);
            speedButton.setText(speedLabel(activity));
            speedButton.setOnClickListener(view -> {
                int next = SpeedProfile.nextIndex(Config.speedProfile(activity));
                Config.setSpeedProfile(activity, next);
                speedButton.setText(speedLabel(activity));
                LogSink.diagnostic("panel_speed_profile", Trace.newTraceId(), Trace.newRequestId(),
                        "ok", "", 0, "profile", SpeedProfile.of(next).label);
                Toast.makeText(activity, "速度档：" + SpeedProfile.of(next).describe(),
                        Toast.LENGTH_SHORT).show();
            });
            content.addView(speedButton);

            Button disguiseButton = new Button(context);
            disguiseButton.setText(disguiseLabel(activity));
            disguiseButton.setOnClickListener(view -> {
                int next = Config.nextDisguiseTarget(Config.disguiseTarget(activity));
                Config.setDisguiseTarget(activity, next);
                disguiseButton.setText(disguiseLabel(activity));
                Toast.makeText(activity, disguiseLabel(activity), Toast.LENGTH_SHORT).show();
            });
            content.addView(disguiseButton);

            Button fillButton = new Button(context);
            fillButton.setText("一键填答（本地答案 → 点击选项）");
            fillButton.setOnClickListener(view -> runAutoFill());
            content.addView(fillButton);

            Button advanceButton = new Button(context);
            advanceButton.setText("快进听音并交卷");
            advanceButton.setOnClickListener(view -> runAutoAdvance());
            content.addView(advanceButton);

            automationOutput.setTextSize(11);
            automationOutput.setTextIsSelectable(true);
            automationOutput.setText("（未执行）");
            content.addView(automationOutput);
        }

        private void runAutoFill() {
            if (!Config.enabled(activity, Config.AUTO_FILL)) {
                Toast.makeText(activity, "请先在“功能开关”里打开：自动填答", Toast.LENGTH_LONG).show();
                return;
            }
            automationOutput.setText("填答中…");
            Automation.fill(activity, json -> {
                automationOutput.setText(renderAutomation("fill", json));
                LogSink.diagnostic("panel_auto_fill", Trace.newTraceId(), Trace.newRequestId(),
                        "ok", "", 0, "result", LogSink.limit(LogSink.redact(json), 200));
            });
        }

        private void runAutoAdvance() {
            if (!Config.enabled(activity, Config.AUTO_ADVANCE)) {
                Toast.makeText(activity, "请先在“功能开关”里打开：自动推进听音并交卷", Toast.LENGTH_LONG).show();
                return;
            }
            automationOutput.setText("快进中…（伪造听音完成并尝试交卷，请勿离开）");
            Automation.advance(activity, json -> {
                automationOutput.setText(renderAutomation("advance", json));
                LogSink.diagnostic("panel_auto_advance", Trace.newTraceId(), Trace.newRequestId(),
                        "ok", "", 0, "result", LogSink.limit(LogSink.redact(json), 200));
            });
        }

        private String renderAutomation(String kind, String json) {
            try {
                return kind + ":\n" + new JSONObject(json).toString(2);
            } catch (Throwable ignored) {
                return kind + ": " + json;
            }
        }

        private void runDevTool(final String kind) {
            String typed = devSelector.getText() == null ? "" : devSelector.getText().toString().trim();
            final String selector = typed.isEmpty() ? PageDevToolsScripts.DEFAULT_SELECTOR : typed;
            lastDevSelector = selector;
            devOutput.setText("查询中…");
            PageDevTools.Callback callback = json -> {
                String text = "outline".equals(kind) ? renderOutline(selector, json) : renderJson(json);
                lastDevDump = text;
                devOutput.setText(text);
                LogSink.diagnostic("devtools_query", Trace.newTraceId(), Trace.newRequestId(), "ok", "", 0,
                        "tool", kind, "selector", LogSink.limit(LogSink.redact(selector), 120));
            };
            if ("styles".equals(kind)) PageDevTools.styles(activity, selector, callback);
            else if ("gate".equals(kind)) PageDevTools.gateOverview(activity, callback);
            else PageDevTools.outline(activity, selector, callback);
        }

        private void exportDevDump() {
            if (lastDevDump.isEmpty()) {
                Toast.makeText(activity, "先执行一次查询", Toast.LENGTH_SHORT).show();
                return;
            }
            LogSink.rawEvent("devtools_dump", "selector", lastDevSelector, "dump", lastDevDump);
            Toast.makeText(activity, LogFileSink.enabled()
                    ? "已按 raw 开关写入日志文件" : "需先开启 log_file", Toast.LENGTH_LONG).show();
        }

        /** Shows the native-to-page audio-state callbacks captured in the in-memory ring buffer. */
        private void showAudioCallbacks() {
            List<String> lines = LogSink.recentEventsNamed("audio_state_callback", 20);
            String text = lines.isEmpty()
                    ? "（未捕获到原生音频回调：请先在听力页播放一次音频）" : join(lines);
            lastDevSelector = "<native audio callbacks>";
            lastDevDump = text;
            devOutput.setText(text);
            LogSink.diagnostic("devtools_query", Trace.newTraceId(), Trace.newRequestId(),
                    lines.isEmpty() ? "empty" : "ok", "", 0,
                    "tool", "audio", "count", Integer.toString(lines.size()));
        }

        private String renderOutline(String selector, String json) {
            try {
                JSONObject object = new JSONObject(json);
                if (!object.optBoolean("found", false)) {
                    String error = object.optString("error", "");
                    return "未命中：" + selector + (error.isEmpty() ? "" : "（" + error + "）");
                }
                JSONArray lines = object.optJSONArray("lines");
                StringBuilder builder = new StringBuilder();
                builder.append("selector=").append(selector)
                        .append(" nodes=").append(object.optInt("nodes"))
                        .append(object.optBoolean("truncated", false) ? " (truncated)" : "")
                        .append('\n');
                for (int i = 0; lines != null && i < lines.length(); i++) {
                    builder.append(lines.optString(i)).append('\n');
                }
                return builder.toString();
            } catch (Throwable error) {
                return json;
            }
        }

        private String renderJson(String json) {
            try {
                return new JSONObject(json).toString(2);
            } catch (Throwable ignored) {
                return json;
            }
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
                final String logPath = LogFileSink.path();
                final String logText;
                if (logPath.length() == 0) {
                    String directory = LogFileSink.directoryPath();
                    logText = "不可用（" + LogFileSink.reason() + "）"
                            + (directory.length() == 0 ? "" : "\n尝试目录：" + directory);
                } else {
                    String mode = LogFileSink.enabled() ? "已开启" : "未开启（路径预览）";
                    String fallback = LogFileSink.REASON_FALLBACK_EXTERNAL
                            .equals(LogFileSink.reason())
                            ? "\n（内部私有目录不可写，已回退到外部私有目录）" : "";
                    logText = mode + "\n" + logPath + fallback;
                }
                MAIN.post(() -> {
                    status.setText("Hook 就绪，processRole=" + LogSink.processRole());
                    coverage.setText(coverageText);
                    answerSection.removeAllViews();
                    for (String line : setLines) addCopyableLine(answerSection, line);
                    versionSection.removeAllViews();
                    addVersions(versionSection, versions);
                    diagnostics.setText(diagnosticsText);
                    logStatus.setText(logText);
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

    /** Label for the speed-profile button, e.g. {@code 速度档：快速}. */
    private static String speedLabel(Context context) {
        return "速度档：" + SpeedProfile.of(Config.speedProfile(context)).label + "（点击切换）";
    }

    /** Label for the disguise-target button, e.g. {@code 伪装时长：自动（点击切换）}. */
    private static String disguiseLabel(Context context) {
        int minutes = Config.disguiseTarget(context);
        return "伪装时长：" + (minutes <= 0 ? "自动" : minutes + " 分钟") + "（点击切换）";
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
        if (Config.LOG_FILE.equals(key)) return "结构化日志写入私有目录";
        if (Config.LOG_RAW_BRIDGE.equals(key)) return "调试：落原始桥回调与提交体字段（含业务数据，用完删除）";
        if (Config.AUTO_FILL.equals(key)) return "自动填答（只读答案索引 → 点击选项）";
        if (Config.AUTO_ADVANCE.equals(key)) return "自动推进听音并交卷（伪造听完，高风险）";
        if (Config.SUBMIT_DISGUISE.equals(key)) return "提交时间伪装（快进时伪造正常用时）";
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
