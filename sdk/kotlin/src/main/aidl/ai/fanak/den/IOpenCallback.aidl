package ai.fanak.den;

oneway interface IOpenCallback {
    void onOpened(long session, String backend, String accel, String quant, String source);

    void onError(String message);

    // Download of the model file, when the Den app does not have it yet.
    void onProgress(String file, long bytes, long total);
}
