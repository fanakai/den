package ai.fanak.den;

import android.app.PendingIntent;

oneway interface IOpenCallback {
    void onOpened(long session, String backend, String accel, String quant, String source);

    void onError(String message);

    // Download of the model file, when the Den app does not have it yet.
    void onProgress(String file, long bytes, long total);

    // Version 3: the Den app asks the user about this app; opening this screen needs no notification permission.
    void onApproval(in PendingIntent screen);
}
