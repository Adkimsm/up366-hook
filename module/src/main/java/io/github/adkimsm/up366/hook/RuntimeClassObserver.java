package io.github.adkimsm.up366.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Records late-loaded target classes without changing their methods or results.
 * The target application is packed, so business classes may not exist when the
 * regular module initialization hooks run.
 */
final class RuntimeClassObserver {
    private static final String[] CANDIDATES = {
            "com.up366.mobile.book.jsinterface.JSInterfaceV8",
            "com.up366.mobile.book.jsinterface.JSInterfaceV6",
            "com.up366.mobile.book.webview.StudyPageWebView",
            "com.up366.mobile.book.StudyActivity",
            "com.up366.mobile.course.task.JsSdk2023TaskActivity",
            "com.up366.mobile.common.utils.ViewUtil",
            "com.up366.asecengine.jni.AsesJni"
    };
    private static final Set<String> CANDIDATE_SET =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(CANDIDATES)));
    private static final Set<String> RECORDED =
            Collections.synchronizedSet(new HashSet<String>());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private RuntimeClassObserver() {}

    static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String name = param.args != null && param.args.length > 0
                                ? String.valueOf(param.args[0]) : "";
                        Object result = param.getResult();
                        if (result instanceof Class<?>) record((Class<?>) result, "load_class", name);
                    } catch (Throwable error) {
                        LogSink.error("observe_loaded_class", error);
                    }
                }
            });
            LogSink.event("runtime_class_observer", "status", "installed");
        } catch (Throwable error) {
            LogSink.error("install_runtime_class_observer", error);
        }

        // Classes loaded before the observer are still useful evidence.
        for (String candidate : CANDIDATES) {
            try {
                record(XposedHelpers.findClass(candidate, classLoader), "initial_scan", candidate);
            } catch (Throwable ignored) {
                // Packed business classes may be released later.
            }
        }
    }

    private static void record(Class<?> type, String source, String requestedName) {
        if (type == null || !CANDIDATE_SET.contains(type.getName())) return;
        if (!RECORDED.add(type.getName())) return;
        LogSink.event("runtime_class_loaded",
                "class", type.getName(),
                "source", source,
                "requested", LogSink.limit(LogSink.redact(requestedName), 256),
                "loader", type.getClassLoader() == null ? "bootstrap" : type.getClassLoader().getClass().getName(),
                "methods", methodSummary(type));
    }

    private static String methodSummary(Class<?> type) {
        StringBuilder result = new StringBuilder();
        try {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) {
                    continue;
                }
                if (result.length() > 0) result.append(';');
                result.append(method.getName()).append('/').append(method.getParameterTypes().length);
                if (result.length() >= 1800) {
                    result.append(";<truncated>");
                    break;
                }
            }
        } catch (Throwable error) {
            return "<unavailable:" + error.getClass().getSimpleName() + ">";
        }
        return result.toString();
    }
}
