package io.github.adkimsm.up366.hook;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

final class OkHttpRecorder {
    private static final Map<Object, Boolean> INJECTED_BUILDERS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static volatile Context context;
    private static volatile ClassLoader classLoader;

    private OkHttpRecorder() {}

    static void install(ClassLoader loader) {
        classLoader = loader;
        try {
            Class<?> builder = Class.forName("okhttp3.OkHttpClient$Builder", false, loader);
            XposedHelpers.findAndHookMethod(builder, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    inject(param.thisObject);
                }
            });
        } catch (Throwable error) {
            LogSink.error("hook_okhttp_build", error);
        }
    }

    static void setContext(Context value) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
    }

    private static void inject(Object builder) {
        Context current = context;
        if (current == null || !Config.enabled(current, Config.NETWORK)) return;
        synchronized (INJECTED_BUILDERS) {
            if (INJECTED_BUILDERS.containsKey(builder)) return;
            INJECTED_BUILDERS.put(builder, Boolean.TRUE);
        }
        try {
            Class<?> interceptor = Class.forName("okhttp3.Interceptor", false, classLoader);
            Object proxy = Proxy.newProxyInstance(
                    classLoader,
                    new Class<?>[]{interceptor},
                    new InterceptorHandler()
            );
            Method add = builder.getClass().getMethod("addInterceptor", interceptor);
            add.invoke(builder, proxy);
            LogSink.event("okhttp_interceptor_added", "builder", builder.getClass().getName());
        } catch (Throwable error) {
            LogSink.error("add_okhttp_interceptor", error);
        }
    }

    private static final class InterceptorHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"intercept".equals(method.getName()) || args == null || args.length != 1) {
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) return "UP366-P0-Interceptor";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                }
                return null;
            }
            Object chain = args[0];
            Object request = callNoArg(chain, "request");
            logRequest(request);
            captureSubmitBody(request);
            Object response;
            try {
                response = callOneArg(chain, "proceed", request);
            } catch (Throwable error) {
                LogSink.error("okhttp_request_failed", error);
                throw error;
            }
            logResponse(response);
            return response;
        }
    }

    private static void logRequest(Object request) {
        try {
            Object url = callNoArg(request, "url");
            Object body = callNoArg(request, "body");
            Object contentType = body == null ? null : callNoArg(body, "contentType");
            LogSink.event(
                    "http_request",
                    "method", String.valueOf(callNoArg(request, "method")),
                    "url", LogSink.redact(String.valueOf(url)),
                    "headers", LogSink.redact(String.valueOf(callNoArg(request, "headers"))),
                    "body_type", body == null ? "none" : body.getClass().getName(),
                    "body_content_type", String.valueOf(contentType),
                    "body", bodyText(body, contentType)
            );
        } catch (Throwable error) {
            LogSink.error("log_http_request", error);
        }
    }

    private static void logResponse(Object response) {
        try {
            Object body = callNoArg(response, "body");
            Object contentType = body == null ? null : callNoArg(body, "contentType");
            Object length = body == null ? null : callNoArg(body, "contentLength");
            LogSink.event(
                    "http_response",
                    "code", String.valueOf(callNoArg(response, "code")),
                    "message", String.valueOf(callNoArg(response, "message")),
                    "headers", LogSink.redact(String.valueOf(callNoArg(response, "headers"))),
                    "content_type", String.valueOf(contentType),
                    "content_length", String.valueOf(length),
                    "body", bodyPreview(body, contentType)
            );
        } catch (Throwable error) {
            LogSink.error("log_http_response", error);
        }
    }

    /**
     * Debug capture for the submit endpoints: reads the (possibly gzip) request body, decompresses
     * it and writes only a redacted field inventory — key names and time-like values, never answers
     * or scores — to the target-private log file. Requires {@code log_file} and {@code log_raw_bridge};
     * skipped entirely otherwise, and every failure is swallowed.
     */
    private static void captureSubmitBody(Object request) {
        Context current = context;
        if (current == null || !Config.enabled(current, Config.LOG_RAW_BRIDGE)) return;
        try {
            String url = String.valueOf(callNoArg(request, "url"));
            String lower = url.toLowerCase(Locale.US);
            if (!lower.contains("/submit")) return;
            Object body = callNoArg(request, "body");
            if (body == null) return;
            String encoding = headerValue(request, "Content-Encoding");
            byte[] bytes = readBodyBytes(body);
            if (bytes == null) return;
            if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
                bytes = gunzip(bytes);
                if (bytes == null) return;
                encoding = encoding.isEmpty() ? "gzip" : encoding + "+gunzip";
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            LogSink.rawEvent("submit_body_inventory",
                    "url", LogSink.redact(url),
                    "encoding", encoding,
                    "bytes", Integer.toString(bytes.length),
                    "inventory", SubmitBodyInventory.of(text));
        } catch (Throwable ignored) {
            // Debug capture must never affect the request path.
        }
    }

    private static String headerValue(Object request, String name) {
        try {
            Method header = findOneArg(request.getClass(), "header", String.class);
            if (header == null) return "";
            Object value = header.invoke(request, name);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static byte[] readBodyBytes(Object body) {
        try {
            Object oneShot = callNoArg(body, "isOneShot");
            if (Boolean.TRUE.equals(oneShot)) return null;
        } catch (NoSuchMethodException ignored) {
            // Older OkHttp versions do not expose isOneShot.
        } catch (Throwable ignored) {
            // Treat any other reflection failure as unknown and continue below.
        }
        try {
            Object length = callNoArg(body, "contentLength");
            if (length instanceof Number && ((Number) length).longValue() > 1_048_576L) return null;
        } catch (Throwable ignored) {
            // Unknown length is fine; the read below is bounded by the gunzip guard.
        }
        try {
            Class<?> bufferClass = Class.forName("okio.Buffer", false, classLoader);
            Object buffer = bufferClass.getDeclaredConstructor().newInstance();
            Method writeTo = findOneArg(body.getClass(), "writeTo", bufferClass);
            if (writeTo == null) return null;
            writeTo.invoke(body, buffer);
            Method readByteArray = bufferClass.getMethod("readByteArray");
            Object result = readByteArray.invoke(buffer);
            return result instanceof byte[] ? (byte[]) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] bytes) {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(chunk)) > 0) {
                total += read;
                if (total > 1_048_576) return null;
                output.write(chunk, 0, read);
            }
            return output.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String bodyText(Object body, Object contentType) {
        if (body == null) return "";
        String type = String.valueOf(contentType).toLowerCase();
        if (!(type.contains("text") || type.contains("json") || type.contains("xml") || type.contains("form"))) {
            return "<binary or non-text body>";
        }
        try {
            try {
                Object oneShot = callNoArg(body, "isOneShot");
                if (Boolean.TRUE.equals(oneShot)) return "<one-shot body skipped>";
            } catch (NoSuchMethodException ignored) {
                // Older OkHttp versions do not expose isOneShot.
            }
            Object length = callNoArg(body, "contentLength");
            if (length instanceof Number && ((Number) length).longValue() > 65536L) {
                return "<body too large>";
            }
            if (length instanceof Number && ((Number) length).longValue() < 0L) {
                return "<unknown-length body skipped>";
            }
            Class<?> bufferClass = Class.forName("okio.Buffer", false, classLoader);
            Object buffer = bufferClass.getDeclaredConstructor().newInstance();
            Method writeTo = findOneArg(body.getClass(), "writeTo", bufferClass);
            if (writeTo == null) return "<unavailable>";
            writeTo.invoke(body, buffer);
            Method readUtf8 = bufferClass.getMethod("readUtf8");
            return LogSink.limit(LogSink.redact(String.valueOf(readUtf8.invoke(buffer))), 4096);
        } catch (Throwable error) {
            return "<unavailable:" + error.getClass().getSimpleName() + ">";
        }
    }

    private static String bodyPreview(Object body, Object contentType) {
        if (body == null) return "";
        String type = String.valueOf(contentType).toLowerCase();
        if (!(type.contains("text") || type.contains("json") || type.contains("xml") || type.contains("form"))) {
            return "<binary or non-text body>";
        }
        try {
            Object source = callNoArg(body, "source");
            Method request = findOneArg(source.getClass(), "request", long.class);
            if (request == null) return "<unavailable>";
            request.invoke(source, 4096L);
            Object buffer = callNoArg(source, "buffer");
            Object copy = callNoArg(buffer, "clone");
            return LogSink.limit(LogSink.redact(String.valueOf(callNoArg(copy, "readUtf8"))), 4096);
        } catch (Throwable error) {
            return "<unavailable:" + error.getClass().getSimpleName() + ">";
        }
    }

    private static String redact(String headers) {
        String[] lines = headers.split("\\n");
        StringBuilder result = new StringBuilder(headers.length());
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.startsWith("authorization:") || lower.startsWith("cookie:")
                    || lower.startsWith("set-cookie:") || lower.contains("token:")
                    || lower.contains("secret:") || lower.contains("password:")) {
                int colon = line.indexOf(':');
                line = line.substring(0, colon + 1) + " <redacted>";
            }
            if (result.length() > 0) result.append('\n');
            result.append(line);
        }
        return result.toString();
    }

    private static Object callNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static Object callOneArg(Object target, String name, Object argument) throws Exception {
        Method method = findOneArg(target.getClass(), name, argument.getClass());
        if (method == null) throw new NoSuchMethodException(name);
        return method.invoke(target, argument);
    }

    private static Method findOneArg(Class<?> type, String name, Class<?> argumentType) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 1) continue;
            if (method.getParameterTypes()[0].isAssignableFrom(argumentType)) return method;
        }
        return null;
    }
}
