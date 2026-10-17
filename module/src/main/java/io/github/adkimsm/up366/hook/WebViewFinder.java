package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/** Collects the WebViews of the current Activity's view tree, bounded and exception-safe. */
final class WebViewFinder {
    static final int MAX_WEBVIEWS = 8;

    private WebViewFinder() {}

    static List<WebView> collect(Activity activity) {
        List<WebView> result = new ArrayList<>();
        try {
            if (activity != null) walk(activity.getWindow().getDecorView(), result);
        } catch (Throwable ignored) {
            // A missing decor view simply yields no candidates.
        }
        return result;
    }

    private static void walk(View view, List<WebView> result) {
        if (view == null || result.size() >= MAX_WEBVIEWS) return;
        if (view instanceof WebView) {
            result.add((WebView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), result);
        }
    }
}
