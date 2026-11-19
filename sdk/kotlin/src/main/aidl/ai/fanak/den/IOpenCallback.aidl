package ai.fanak.den;

oneway interface IOpenCallback {
    void onOpened(long session, String backend, String accel, String quant, String source);

    void onError(String message);
}
