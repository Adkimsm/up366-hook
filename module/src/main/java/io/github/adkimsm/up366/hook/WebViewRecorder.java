package io.github.adkimsm.up366.hook;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class WebViewRecorder {
    private static final Set<Class<?>> HOOKED_CLIENTS =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static final Set<Class<?>> HOOKED_SETTINGS =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static volatile Context appContext;

    private WebViewRecorder() {}

    static void install(Context context, ClassLoader classLoader) {
        appContext = context.getApplicationContext();
        hook("webview_load_url", () -> XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class, new LoadHook()));
        hook("webview_load_url_headers", () -> XposedHelpers.findAndHookMethod(WebView.class, "loadUrl", String.class, java.util.Map.class, new LoadHook()));
        hook("webview_load_data", () -> XposedHelpers.findAndHookMethod(WebView.class, "loadData", String.class, String.class, String.class, new LoadHook()));
        hook("webview_load_data_base", () -> XposedHelpers.findAndHookMethod(WebView.class, "loadDataWithBaseURL", String.class, String.class, String.class, String.class, String.class, new LoadHook()));
        hook("webview_evaluate_javascript", () -> XposedHelpers.findAndHookMethod(WebView.class, "evaluateJavascript", String.class, android.webkit.ValueCallback.class, new JavascriptHook()));
        hook("webview_add_javascript_interface", () -> XposedHelpers.findAndHookMethod(WebView.class, "addJavascriptInterface", Object.class, String.class, new BridgeHook()));
        hook("webview_set_client", () -> XposedHelpers.findAndHookMethod(WebView.class, "setWebViewClient", WebViewClient.class, new ClientHook()));
        hook("webview_get_settings", () -> XposedHelpers.findAndHookMethod(WebView.class, "getSettings", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object settings = param.getResult();
                    if (settings != null) hookSettingsClass(settings.getClass());
                }
            }));
        hook("webview_page_started", () -> XposedHelpers.findAndHookMethod(WebViewClient.class, "onPageStarted", WebView.class, String.class, android.graphics.Bitmap.class, new PageHook("page_started")));
        hook("webview_page_finished", () -> XposedHelpers.findAndHookMethod(WebViewClient.class, "onPageFinished", WebView.class, String.class, new PageHook("page_finished")));
    }

    private static void hook(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            LogSink.error("hook_" + name, error);
        }
    }

    private static void hookSettingsClass(Class<?> settingsClass) {
        synchronized (HOOKED_SETTINGS) {
            if (!HOOKED_SETTINGS.add(settingsClass)) return;
        }
        hookSetting(settingsClass, "setJavaScriptEnabled", "javascript_enabled");
        hookSetting(settingsClass, "setAllowFileAccess", "allow_file_access");
        hookSetting(settingsClass, "setAllowUniversalAccessFromFileURLs", "allow_universal_file_access");
    }

    private static void hookSetting(Class<?> settingsClass, String method, String event) {
        try {
            XposedHelpers.findAndHookMethod(settingsClass, method, boolean.class, new SettingsHook(event));
        } catch (Throwable error) {
            LogSink.error("hook_webview_setting_" + method, error);
        }
    }

    private static final class LoadHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            WebView webView = (WebView) param.thisObject;
            if (!Config.enabled(webView.getContext(), Config.WEBVIEW)) return;
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < param.args.length; i++) {
                Object arg = param.args[i];
                if (args.length() > 0) args.append(" | ");
                boolean pageContent = "loadData".equals(param.method.getName()) && i == 0
                        || "loadDataWithBaseURL".equals(param.method.getName()) && i == 1;
                if (pageContent) {
                    args.append("<page content omitted length=")
                            .append(arg == null ? 0 : String.valueOf(arg).length()).append('>');
                } else if (arg instanceof String && ((String) arg).length() > 2048 && i > 0) {
                    args.append("<content omitted length=").append(((String) arg).length()).append('>');
                } else {
                    args.append(LogSink.limit(LogSink.redact(String.valueOf(arg)), 2048));
                }
            }
            LogSink.event("webview_load", "method", param.method.getName(), "args", args.toString());
        }
    }

    private static final class SettingsHook extends XC_MethodHook {
        private final String setting;

        SettingsHook(String setting) {
            this.setting = setting;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Context context = appContext;
            if (context == null || !Config.enabled(context, Config.WEBVIEW)) return;
            LogSink.event("webview_setting", "setting", setting, "value", String.valueOf(param.args[0]));
        }
    }

    private static final class ClientHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            WebViewClient client = (WebViewClient) param.args[0];
            if (client != null) hookClientClass(client.getClass());
        }
    }

    private static final class PageHook extends XC_MethodHook {
        private final String event;

        PageHook(String event) {
            this.event = event;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            WebView webView = (WebView) param.args[0];
            String url = String.valueOf(param.args[1]);
            boolean logWebView = Config.enabled(webView.getContext(), Config.WEBVIEW);
            boolean probe = Config.enabled(webView.getContext(), Config.PROBE);
            boolean questionMetadata = Config.enabled(webView.getContext(), Config.QUESTION_METADATA);
            if (!logWebView && !probe && !questionMetadata) return;
            TaskContext.onPage(webView, url);
            if (logWebView) {
                LogSink.event(event, "client", param.thisObject.getClass().getName(), "url", LogSink.redact(url));
            }
            if ("page_finished".equals(event) && questionMetadata) {
                QuestionPackageRecorder.schedule(webView, url);
            }
            if ("page_finished".equals(event) && probe) {
                WebViewProbe.schedule(webView, url);
            }
        }
    }

    private static void hookClientClass(Class<?> clientClass) {
        synchronized (HOOKED_CLIENTS) {
            if (!HOOKED_CLIENTS.add(clientClass)) return;
        }
        try {
            Method started = clientClass.getMethod("onPageStarted", WebView.class, String.class, android.graphics.Bitmap.class);
            XposedBridge.hookMethod(started, new PageHook("page_started"));
        } catch (Throwable error) {
            LogSink.error("hook_webview_client_started", error);
        }
        try {
            Method finished = clientClass.getMethod("onPageFinished", WebView.class, String.class);
            XposedBridge.hookMethod(finished, new PageHook("page_finished"));
        } catch (Throwable error) {
            LogSink.error("hook_webview_client_finished", error);
        }
    }

    private static final class BridgeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            WebView webView = (WebView) param.thisObject;
            Object bridge = param.args[0];
            String name = String.valueOf(param.args[1]);
            if (Config.enabled(webView.getContext(), Config.WEBVIEW)) {
                LogSink.event("javascript_interface", "name", name, "class", bridge == null ? "null" : bridge.getClass().getName());
            }
            if (bridge != null && (Config.enabled(webView.getContext(), Config.BRIDGE)
                    || Config.enabled(webView.getContext(), Config.QUESTION_METADATA))) {
                BridgeRecorder.register(bridge, webView);
            }
        }
    }

    private static final class JavascriptHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                WebView webView = (WebView) param.thisObject;
                String script = String.valueOf(param.args[0]);
                BridgeRecorder.inspectJavascript(webView, script);
                if (param.args.length > 1 && param.args[1] instanceof android.webkit.ValueCallback
                        && BridgeRecorder.shouldObserveJavascript(script)) {
                    final android.webkit.ValueCallback callback =
                            (android.webkit.ValueCallback) param.args[1];
                    param.args[1] = (android.webkit.ValueCallback) value -> {
                        BridgeRecorder.recordJavascriptResult(webView, script, value);
                        callback.onReceiveValue(value);
                    };
                }
            } catch (Throwable error) {
                LogSink.error("inspect_webview_javascript", error);
            }
        }
    }
}
