package ai.fanak.den;

oneway interface IGenCallback {
    void onText(String text);

    // [promptTokens, outputTokens, prefillMs, decodeMs]
    void onDone(in long[] stats);

    void onError(String message);
}
