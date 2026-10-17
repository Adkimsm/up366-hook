package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.os.Bundle;

/** Module-UI convenience wrapper around {@link IpcClient} for same-process Provider calls. */
final class HookStatusProviderIo {
    private HookStatusProviderIo() {}

    static Bundle call(Context context, String method, Bundle extras) {
        return IpcClient.call(context, method, extras, Trace.newTraceId(), true);
    }
}
