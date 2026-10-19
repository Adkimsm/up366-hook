package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
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

import java.util.ArrayDeque;
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
 *
 * <p>Layout: a fixed-size panel with a title bar, an always-visible hot-action row (fill /
 * fast-forward / stop), a five-tab strip (answers, automation, flags, diagnostics, tools) and one
 * scrollable page per tab. Colors follow the system light/dark setting.
 */
final class InjectedUi {
    private static final Map<Activity, Root> ATTACHED =
            Collections.synchronizedMap(new WeakHashMap<Activity, Root>());

    /** Panel geometry in dp; the height is additionally clamped to the window. */
    private static final int PANEL_WIDTH_DP = 340;
    private static final int PANEL_MAX_HEIGHT_DP = 520;
    private static final int PANEL_MIN_HEIGHT_DP = 240;
    private static final int LOG_VIEW_HEIGHT_DP = 140;

    /** Rolling automation output: how many result lines stay visible and how long one may be. */
    private static final int MAX_AUTOMATION_LINES = 8;
    private static final int MAX_AUTOMATION_LINE = 240;

    private static final int PAGE_ANSWERS = 0;
    private static final int PAGE_AUTOMATION = 1;
    private static final int PAGE_FLAGS = 2;
    private static final int PAGE_DIAGNOSTICS = 3;
    private static final int PAGE_TOOLS = 4;
    private static final int PAGE_COUNT = 5;
    private static final String[] TAB_TITLES = {"答案", "自动化", "开关", "诊断", "工具"};

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
        decor.addView(root.panel, panelParams(activity, parentWidth, parentHeight));
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

    /** Fixed panel box so the layout no longer jumps with the content height. */
    private static FrameLayout.LayoutParams panelParams(Context context, int parentWidth,
                                                        int parentHeight) {
        int width = Math.min(dp(context, PANEL_WIDTH_DP),
                Math.max(dp(context, 220), parentWidth - dp(context, 24)));
        int height = Math.min(dp(context, PANEL_MAX_HEIGHT_DP),
                Math.max(dp(context, PANEL_MIN_HEIGHT_DP), parentHeight - dp(context, 96)));
        height = Math.min(height, Math.max(dp(context, 160), parentHeight - dp(context, 24)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(context, 8);
        params.topMargin = dp(context, 140);
        return params;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    /** Rounded background with a darker pressed state, so the custom buttons stay responsive. */
    private static StateListDrawable pressedBackground(int color, int radiusPx) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(darken(color), radiusPx));
        states.addState(new int[]{}, rounded(color, radiusPx));
        return states;
    }

    private static int darken(int color) {
        return Color.rgb(
                Math.max(0, (int) (Color.red(color) * 0.82f)),
                Math.max(0, (int) (Color.green(color) * 0.82f)),
                Math.max(0, (int) (Color.blue(color) * 0.82f)));
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

    /** Compact flag label for the two-column switch grid. */
    private static String flagShortLabel(String key) {
        if (Config.ACTIVITY.equals(key)) return "Activity 生命周期";
        if (Config.NETWORK.equals(key)) return "网络元数据";
        if (Config.WEBVIEW.equals(key)) return "WebView 与桥";
        if (Config.PROBE.equals(key)) return "页面 JS 探针";
        if (Config.BRIDGE.equals(key)) return "桥调用参数";
        if (Config.QUESTION_METADATA.equals(key)) return "题包解析（只读）";
        if (Config.ANSWER_INDEX.equals(key)) return "答案索引（只读）";
        if (Config.INJECT_UI.equals(key)) return "注入面板";
        if (Config.LOG_FILE.equals(key)) return "写日志文件";
        if (Config.LOG_RAW_BRIDGE.equals(key)) return "原始桥回调（业务数据）";
        if (Config.AUTO_FILL.equals(key)) return "自动填答";
        if (Config.AUTO_ADVANCE.equals(key)) return "自动听音交卷";
        if (Config.SUBMIT_DISGUISE.equals(key)) return "提交时间伪装";
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

    /** Light/dark color set resolved from the system night mode when the panel is built. */
    private static final class Palette {
        final int panel;
        final int secondary;
        final int primaryText;
        final int secondaryText;
        final int accent;
        final int accentText;
        final int dangerText;
        final int divider;

        private Palette(int panel, int secondary, int primaryText, int secondaryText, int accent,
                        int accentText, int dangerText, int divider) {
            this.panel = panel;
            this.secondary = secondary;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.accent = accent;
            this.accentText = accentText;
            this.dangerText = dangerText;
            this.divider = divider;
        }

        static Palette of(Context context) {
            boolean night = false;
            try {
                night = (context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            } catch (Throwable ignored) {
                // Fall back to the light palette.
            }
            if (night) {
                return new Palette(0xFF1F2124, 0xFF2A2D31, 0xFFE8EAED, 0xFF9AA0A6,
                        0xFF1E88E5, Color.WHITE, 0xFFEF9A9A, 0xFF3C4043);
            }
            return new Palette(0xFFFFFFFF, 0xFFF5F6F7, 0xFF1B1C1E, 0xFF5F6368,
                    0xFF1565C0, Color.WHITE, 0xFFC62828, 0xFFE3E6EA);
        }
    }

    /** Holds one injection's Views and refresh entry points for a single Activity. */
    private static final class Root {
        final Activity activity;
        final Palette palette;
        final Button button;
        final LinearLayout panel;
        FrameLayout body;
        final Button[] tabButtons = new Button[PAGE_COUNT];
        final View[] pages = new View[PAGE_COUNT];
        final List<String> answerLines = new ArrayList<>();
        final ArrayDeque<String> automationLog = new ArrayDeque<>();
        TextView status;
        TextView coverage;
        LinearLayout answerSection;
        LinearLayout versionSection;
        TextView diagnostics;
        TextView logStatus;
        EditText devSelector;
        TextView devOutput;
        TextView automationOutput;
        ScrollView automationScroll;
        EditText traceInput;
        TextView traceResult;
        String lastDevDump = "";
        String lastDevSelector = PageDevToolsScripts.DEFAULT_SELECTOR;

        Root(Activity activity) {
            this.activity = activity;
            this.palette = Palette.of(activity);
            button = buildButton(activity);
            panel = buildPanel(activity);
            selectPage(Config.panelTab(activity), false);
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
            if (panelWidth <= 0) panelWidth = dp(activity, PANEL_WIDTH_DP);
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
            view.setBackground(rounded(palette.accent, dp(context, 18)));
            view.setTextColor(palette.accentText);
            return view;
        }

        // ------------------------------------------------------------------ panel shell

        private LinearLayout buildPanel(Context context) {
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(rounded(palette.panel, dp(context, 10)));
            panel.addView(buildHeader(context));
            panel.addView(buildHotActions(context));
            panel.addView(buildTabStrip(context));
            panel.addView(divider(context));
            body = new FrameLayout(context);
            panel.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            buildPages(context);
            return panel;
        }

        /** Title, live hook status and the refresh/collapse controls. */
        private View buildHeader(Context context) {
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(context, 12), dp(context, 8), dp(context, 8), 0);

            LinearLayout titles = new LinearLayout(context);
            titles.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(context, 14, palette.primaryText, "UP366 面板");
            status = text(context, 11, palette.secondaryText, "正在查询…");
            titles.addView(title);
            titles.addView(status);
            header.addView(titles, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button refresh = smallButton(context, "刷新");
            refresh.setOnClickListener(view -> refresh());
            header.addView(refresh);
            Button collapse = smallButton(context, "收起");
            collapse.setOnClickListener(view -> panel.setVisibility(View.GONE));
            header.addView(collapse);
            return header;
        }

        /** Hot actions stay visible on every tab so an exam run needs no page switching. */
        private View buildHotActions(Context context) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 6));

            Button fill = styledButton(context, "一键填答", palette.accent, palette.accentText, 13);
            fill.setOnClickListener(view -> runAutoFill());
            Button advance = styledButton(context, "快进听音", palette.accent, palette.accentText, 13);
            advance.setOnClickListener(view -> runAutoAdvance());
            Button stop = styledButton(context, "停止快进", palette.secondary, palette.dangerText, 13);
            stop.setOnClickListener(view -> stopAutoAdvance());

            row.addView(fill, hotParams(context, 0));
            row.addView(advance, hotParams(context, 1));
            row.addView(stop, hotParams(context, 2));
            return row;
        }

        private LinearLayout.LayoutParams hotParams(Context context, int index) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, dp(context, 40), 1f);
            params.setMargins(index == 0 ? 0 : dp(context, 6), 0, 0, 0);
            return params;
        }

        private View buildTabStrip(Context context) {
            LinearLayout strip = new LinearLayout(context);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(dp(context, 8), 0, dp(context, 8), 8);
            for (int i = 0; i < PAGE_COUNT; i++) {
                final int index = i;
                Button tab = new Button(context);
                tab.setText(TAB_TITLES[i]);
                tab.setTextSize(13);
                tab.setAllCaps(false);
                tab.setMinWidth(0);
                tab.setMinimumWidth(0);
                tab.setMinHeight(0);
                tab.setMinimumHeight(0);
                tab.setPadding(0, 0, 0, 0);
                tab.setStateListAnimator(null);
                tab.setOnClickListener(view -> selectPage(index, true));
                tabButtons[i] = tab;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, dp(context, 34), 1f);
                params.setMargins(i == 0 ? 0 : dp(context, 6), 0, 0, 0);
                strip.addView(tab, params);
            }
            return strip;
        }

        private void selectPage(int index, boolean persist) {
            int safe = Math.max(0, Math.min(PAGE_COUNT - 1, index));
            for (int i = 0; i < PAGE_COUNT; i++) {
                if (pages[i] == null) continue;
                pages[i].setVisibility(i == safe ? View.VISIBLE : View.GONE);
                styleTab(tabButtons[i], i == safe);
            }
            if (persist) Config.setPanelTab(activity, safe);
        }

        private void styleTab(Button tab, boolean selected) {
            tab.setBackground(pressedBackground(selected ? palette.accent : palette.secondary,
                    dp(activity, 7)));
            tab.setTextColor(selected ? palette.accentText : palette.primaryText);
        }

        private void buildPages(Context context) {
            pages[PAGE_ANSWERS] = page(context, buildAnswersPage(context));
            pages[PAGE_AUTOMATION] = page(context, buildAutomationPage(context));
            pages[PAGE_FLAGS] = page(context, buildFlagsPage(context));
            pages[PAGE_DIAGNOSTICS] = page(context, buildDiagnosticsPage(context));
            pages[PAGE_TOOLS] = page(context, buildToolsPage(context));
            for (View page : pages) {
                page.setVisibility(View.GONE);
                body.addView(page, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }

        private ScrollView page(Context context, LinearLayout content) {
            ScrollView scroll = new ScrollView(context);
            scroll.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return scroll;
        }

        private LinearLayout pageContent(Context context) {
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 12));
            return content;
        }

        // ------------------------------------------------------------------ answers page

        private LinearLayout buildAnswersPage(Context context) {
            LinearLayout content = pageContent(context);
            coverage = text(context, 12, palette.secondaryText, "（无数据）");
            content.addView(coverage);

            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(context, 6), 0, dp(context, 2));
            Button copyAll = smallButton(context, "复制全部");
            copyAll.setOnClickListener(view -> copyAnswerLines());
            actions.addView(copyAll);
            content.addView(actions);

            answerSection = new LinearLayout(context);
            answerSection.setOrientation(LinearLayout.VERTICAL);
            content.addView(answerSection);
            return content;
        }

        private void copyAnswerLines() {
            if (answerLines.isEmpty()) {
                Toast.makeText(activity, "暂无答案可复制", Toast.LENGTH_SHORT).show();
                return;
            }
            copyToClipboard(join(new ArrayList<>(answerLines)));
        }

        // ------------------------------------------------------------------ automation page

        private LinearLayout buildAutomationPage(Context context) {
            LinearLayout content = pageContent(context);

            Button speed = smallButton(context, speedLabel(activity));
            speed.setOnClickListener(view -> {
                int next = SpeedProfile.nextIndex(Config.speedProfile(activity));
                Config.setSpeedProfile(activity, next);
                speed.setText(speedLabel(activity));
                LogSink.diagnostic("panel_speed_profile", Trace.newTraceId(), Trace.newRequestId(),
                        "ok", "", 0, "profile", SpeedProfile.of(next).label);
                Toast.makeText(activity, "速度档：" + SpeedProfile.of(next).describe(),
                        Toast.LENGTH_SHORT).show();
            });
            content.addView(speed, fullWidth(context));

            Button disguise = smallButton(context, disguiseLabel(activity));
            disguise.setOnClickListener(view -> {
                int next = Config.nextDisguiseTarget(Config.disguiseTarget(activity));
                Config.setDisguiseTarget(activity, next);
                disguise.setText(disguiseLabel(activity));
                Toast.makeText(activity, disguiseLabel(activity), Toast.LENGTH_SHORT).show();
            });
            content.addView(disguise, fullWidth(context));

            content.addView(caption(context, "自动化日志"));
            automationOutput = text(context, 11, palette.primaryText, "（未执行）");
            automationOutput.setTextIsSelectable(true);
            automationScroll = new ScrollView(context);
            automationScroll.setBackground(rounded(palette.secondary, dp(context, 8)));
            automationScroll.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
            automationScroll.addView(automationOutput, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(automationScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, LOG_VIEW_HEIGHT_DP)));
            return content;
        }

        // ------------------------------------------------------------------ flags page

        private LinearLayout buildFlagsPage(Context context) {
            LinearLayout content = pageContent(context);
            content.addView(flagGroup(context, "观察",
                    new String[]{Config.ACTIVITY, Config.NETWORK, Config.WEBVIEW,
                            Config.PROBE, Config.BRIDGE}));
            content.addView(flagGroup(context, "数据与面板",
                    new String[]{Config.QUESTION_METADATA, Config.ANSWER_INDEX, Config.INJECT_UI,
                            Config.LOG_FILE, Config.LOG_RAW_BRIDGE}));
            content.addView(flagGroup(context, "自动化（高风险）",
                    new String[]{Config.AUTO_FILL, Config.AUTO_ADVANCE, Config.SUBMIT_DISGUISE}));
            content.addView(text(context, 11, palette.secondaryText,
                    "开关就地生效；完整说明见 README。"));
            return content;
        }

        /** One two-column group of switches. */
        private LinearLayout flagGroup(Context context, String title, String[] keys) {
            LinearLayout group = new LinearLayout(context);
            group.setOrientation(LinearLayout.VERTICAL);
            group.addView(caption(context, title));
            for (int i = 0; i < keys.length; i += 2) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(flagCheckBox(context, keys[i]),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (i + 1 < keys.length) {
                    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    right.setMargins(dp(context, 6), 0, 0, 0);
                    row.addView(flagCheckBox(context, keys[i + 1]), right);
                } else {
                    row.addView(new View(context),
                            new LinearLayout.LayoutParams(0, 0, 1f));
                }
                group.addView(row);
            }
            return group;
        }

        private CheckBox flagCheckBox(Context context, final String key) {
            CheckBox checkBox = new CheckBox(context);
            checkBox.setText(flagShortLabel(key));
            checkBox.setTextSize(12);
            checkBox.setTextColor(palette.primaryText);
            checkBox.setButtonTintList(ColorStateList.valueOf(palette.accent));
            checkBox.setMinHeight(0);
            checkBox.setMinimumHeight(0);
            checkBox.setPadding(0, 0, 0, 0);
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
            return checkBox;
        }

        // ------------------------------------------------------------------ diagnostics page

        private LinearLayout buildDiagnosticsPage(Context context) {
            LinearLayout content = pageContent(context);

            content.addView(caption(context, "最近解析任务"));
            diagnostics = text(context, 12, palette.secondaryText, "（无数据）");
            content.addView(diagnostics);

            content.addView(caption(context, "日志文件"));
            logStatus = text(context, 12, palette.secondaryText, "（未初始化）");
            content.addView(logStatus);
            Button copyLogPath = smallButton(context, "复制日志文件路径");
            copyLogPath.setOnClickListener(view -> copyToClipboard(LogFileSink.path()));
            content.addView(copyLogPath, fullWidth(context));

            content.addView(caption(context, "链路查询"));
            traceInput = edit(context, "输入 traceId 查询链路");
            content.addView(traceInput, fullWidth(context));
            Button traceQuery = smallButton(context, "查询 traceId 链路");
            traceQuery.setOnClickListener(view -> queryTrace());
            content.addView(traceQuery, fullWidth(context));
            traceResult = text(context, 11, palette.secondaryText, "（未查询）");
            traceResult.setTextIsSelectable(true);
            content.addView(traceResult);

            content.addView(caption(context, "维护"));
            Button clearAll = smallButton(context, "清除本地答案索引（全部）");
            clearAll.setOnClickListener(view -> clearIndex(null));
            content.addView(clearAll, fullWidth(context));
            versionSection = new LinearLayout(context);
            versionSection.setOrientation(LinearLayout.VERTICAL);
            content.addView(versionSection);
            Button hideButton = smallButton(context, "隐藏悬浮按钮（持久化）");
            hideButton.setOnClickListener(view -> {
                Config.setHideFloating(activity, true);
                LogSink.diagnostic("panel_hide_floating", Trace.newTraceId(),
                        Trace.newRequestId(), "ok", "", 0);
                hide(activity);
            });
            content.addView(hideButton, fullWidth(context));
            return content;
        }

        // ------------------------------------------------------------------ tools page

        private LinearLayout buildToolsPage(Context context) {
            LinearLayout content = pageContent(context);
            content.addView(caption(context, "页面查询"));
            devSelector = edit(context, "CSS 选择器，如 .u3compo-qst / .u3-qst-opt / .u3-submit");
            devSelector.setText(PageDevToolsScripts.DEFAULT_SELECTOR);
            content.addView(devSelector, fullWidth(context));

            Button outline = smallButton(context, "DOM 概要");
            outline.setOnClickListener(view -> runDevTool("outline"));
            Button styles = smallButton(context, "计算样式");
            styles.setOnClickListener(view -> runDevTool("styles"));
            content.addView(buttonPair(context, outline, styles));

            Button gate = smallButton(context, "门控概览");
            gate.setOnClickListener(view -> runDevTool("gate"));
            Button audio = smallButton(context, "原生音频回调");
            audio.setOnClickListener(view -> showAudioCallbacks());
            content.addView(buttonPair(context, gate, audio));

            Button devCopy = smallButton(context, "复制结果");
            devCopy.setOnClickListener(view -> copyToClipboard(devOutput.getText().toString()));
            Button devExport = smallButton(context, "写入日志文件");
            devExport.setOnClickListener(view -> exportDevDump());
            content.addView(buttonPair(context, devCopy, devExport));

            devOutput = text(context, 11, palette.primaryText,
                    "（未查询：先打开一次题目页，再选选择器执行）");
            devOutput.setTextIsSelectable(true);
            devOutput.setPadding(0, dp(context, 8), 0, 0);
            content.addView(devOutput);
            return content;
        }

        private View buttonPair(Context context, View left, View right) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(left, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rightParams.setMargins(dp(context, 6), 0, 0, 0);
            row.addView(right, rightParams);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(context, 2), 0, dp(context, 2));
            row.setLayoutParams(params);
            return row;
        }

        // ------------------------------------------------------------------ shared widgets

        private TextView text(Context context, float size, int color, String value) {
            TextView view = new TextView(context);
            view.setTextSize(size);
            view.setTextColor(color);
            view.setText(value);
            return view;
        }

        private TextView caption(Context context, String label) {
            TextView view = new TextView(context);
            view.setText(label);
            view.setTextSize(12);
            view.setTextColor(palette.secondaryText);
            view.setPadding(0, dp(context, 12), 0, dp(context, 4));
            return view;
        }

        private EditText edit(Context context, String hint) {
            EditText view = new EditText(context);
            view.setTextSize(12);
            view.setTextColor(palette.primaryText);
            view.setHintTextColor(palette.secondaryText);
            view.setHint(hint);
            view.setInputType(InputType.TYPE_CLASS_TEXT);
            view.setBackground(rounded(palette.secondary, dp(context, 6)));
            view.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
            return view;
        }

        private Button smallButton(Context context, String label) {
            return styledButton(context, label, palette.secondary, palette.primaryText, 12);
        }

        private Button styledButton(Context context, String label, int background, int textColor,
                                    float size) {
            Button view = new Button(context);
            view.setText(label);
            view.setTextSize(size);
            view.setAllCaps(false);
            view.setTextColor(textColor);
            view.setMinWidth(0);
            view.setMinimumWidth(0);
            view.setMinHeight(0);
            view.setMinimumHeight(0);
            view.setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7));
            view.setStateListAnimator(null);
            view.setBackground(pressedBackground(background, dp(context, 8)));
            return view;
        }

        private LinearLayout.LayoutParams fullWidth(Context context) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(context, 2), 0, dp(context, 2));
            return params;
        }

        private View divider(Context context) {
            View view = new View(context);
            view.setBackgroundColor(palette.divider);
            view.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            return view;
        }

        // ------------------------------------------------------------------ automation actions

        private void runAutoFill() {
            if (!Config.enabled(activity, Config.AUTO_FILL)) {
                Toast.makeText(activity, "请先在“开关”里打开：自动填答", Toast.LENGTH_LONG).show();
                return;
            }
            appendAutomation(Automation.isRunning()
                    ? "fill: 已并入快进，等待执行…" : "fill: 执行中…");
            Automation.fill(activity, json -> {
                appendAutomation("fill: " + compactJson(json));
                LogSink.diagnostic("panel_auto_fill", Trace.newTraceId(), Trace.newRequestId(),
                        "ok", "", 0, "result", LogSink.limit(LogSink.redact(json), 200));
            });
        }

        private void runAutoAdvance() {
            if (!Config.enabled(activity, Config.AUTO_ADVANCE)) {
                Toast.makeText(activity, "请先在“开关”里打开：自动听音交卷", Toast.LENGTH_LONG).show();
                return;
            }
            appendAutomation(Automation.isRunning()
                    ? "advance: 重启快进…"
                    : "advance: 开始快进（伪造听音完成并尝试交卷，请勿离开）");
            Automation.advance(activity, json -> {
                appendAutomation("advance: " + compactJson(json));
                LogSink.diagnostic("panel_auto_advance", Trace.newTraceId(), Trace.newRequestId(),
                        "ok", "", 0, "result", LogSink.limit(LogSink.redact(json), 200));
            });
        }

        /** Ends the running fast-forward; queued fill requests still execute afterwards. */
        private void stopAutoAdvance() {
            if (Automation.stop()) {
                appendAutomation("advance: 已请求停止");
            } else {
                Toast.makeText(activity, "当前没有快进在运行", Toast.LENGTH_SHORT).show();
            }
        }

        /** Appends one timestamped automation line; the two actions no longer overwrite each other. */
        private void appendAutomation(String text) {
            String line = "[" + DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                    + "] " + text;
            automationLog.addLast(line.length() <= MAX_AUTOMATION_LINE
                    ? line : line.substring(0, MAX_AUTOMATION_LINE) + "…");
            while (automationLog.size() > MAX_AUTOMATION_LINES) automationLog.removeFirst();
            automationOutput.setText(join(new ArrayList<>(automationLog)));
            if (automationScroll != null) {
                automationScroll.post(() -> automationScroll.fullScroll(View.FOCUS_DOWN));
            }
        }

        private String compactJson(String json) {
            try {
                return new JSONObject(json).toString();
            } catch (Throwable ignored) {
                return json;
            }
        }

        // ------------------------------------------------------------------ developer tools

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

        // ------------------------------------------------------------------ data refresh

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
                    answerLines.clear();
                    answerLines.addAll(setLines);
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
                section.addView(text(activity, 12, palette.secondaryText, "（无可清除的题包版本）"));
                return;
            }
            for (String version : versions) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = text(activity, 12, palette.primaryText,
                        "题包 " + Trace.hash(version));
                label.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button clear = smallButton(activity, "清除");
                clear.setOnClickListener(view -> clearIndex(version));
                row.addView(label);
                row.addView(clear);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, dp(activity, 2), 0, dp(activity, 2));
                section.addView(row, params);
            }
        }

        /** Adds one answer line that can be long-pressed (or selected) and copied to the clipboard. */
        private void addCopyableLine(LinearLayout section, String line) {
            TextView view = text(activity, 12, palette.primaryText, line);
            view.setTextIsSelectable(true);
            view.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
            view.setOnLongClickListener(v -> {
                copyToClipboard(line);
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
    }
}
