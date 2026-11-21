package ai.fanak.den;

import android.app.PendingIntent;

oneway interface IOpenCallback {
    void onOpened(long session, String backend, String accel, String quant, String source);

    void onError(String message);

    // Download of the model file, when the Den app does not have it yet.
    void onProgress(String file, long bytes, long total);

    // Version 3: the Den app asks the user about this app; opening this screen needs no notification permission.
    void onApproval(in PendingIntent screen);

    // Version 5, after openWith: the context the model was loaded with, in tokens.
    void onContext(int contextLength);

    // Version 5, after openWith: an error with its kind, "no_backend" when no candidate could run, else "error".
    void onFailed(String kind, String message);

    // Version 6, after openWith: the model behind the name, when the name is an alias (den.default).
    void onModel(String model);
}
