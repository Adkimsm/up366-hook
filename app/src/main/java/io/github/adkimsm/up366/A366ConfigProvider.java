package io.github.adkimsm.up366;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.content.pm.PackageManager;

public final class A366ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.adkimsm.up366.provider";
    private static final String CONTROL_PACKAGE = "io.github.adkimsm.up366";
    private static final String TARGET_PACKAGE = "com.up366.mobile";
    public static final String METHOD_GET = "get_flags";
    public static final String METHOD_SET = "set_flag";

    private static final String[] KEYS = {
            A366Config.ACTIVITY,
            A366Config.NETWORK,
            A366Config.WEBVIEW,
            A366Config.PROBE,
            A366Config.BRIDGE,
            A366Config.QUESTION_METADATA
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        String caller = getCallingPackage();
        if (caller == null) {
            int uid = Binder.getCallingUid();
            if (!hasPackageForUid(uid, CONTROL_PACKAGE) && !hasPackageForUid(uid, TARGET_PACKAGE)) {
                throw new SecurityException("Unexpected caller uid: " + uid);
            }
        } else if (!CONTROL_PACKAGE.equals(caller) && !TARGET_PACKAGE.equals(caller)) {
            throw new SecurityException("Unexpected caller: " + caller);
        }
        if (METHOD_GET.equals(method)) {
            Bundle result = new Bundle();
            for (String key : KEYS) {
                result.putBoolean(key, A366Config.get(requireContext(), key));
            }
            return result;
        }
        if (METHOD_SET.equals(method) && extras != null) {
            String key = extras.getString("key");
            if (key != null && isKnownKey(key)) {
                A366Config.set(requireContext(), key, extras.getBoolean("value"));
            }
            return get(METHOD_GET);
        }
        return super.call(method, arg, extras);
    }

    private boolean hasPackageForUid(int uid, String packageName) {
        try {
            return requireContext().getPackageManager().getApplicationInfo(packageName, 0).uid == uid;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private static boolean isKnownKey(String key) {
        for (String candidate : KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    private Bundle get(String method) {
        return call(method, null, null);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
