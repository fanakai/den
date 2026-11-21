package ai.fanak.den;

import ai.fanak.den.IGenCallback;

// The engine process of an app (":den_engine"), used by the SDK only. Values pass as JSON of the core classes.
interface IEngine {
    int pid();

    // Capabilities as "accel:CPU" and "format:GGUF" entries.
    String[] probe(String backend, String device);

    String version(String backend);

    // Returns a session id; a failed load throws IllegalStateException with the message.
    long load(String backend, String artifact, String options);

    // Within one APK, so the app and its engine always match: parameters may change in place.
    oneway void generate(long session, in String[] roles, in String[] contents, int maxTokens,
            float temperature, float topP, in String[] stop, boolean cache, int threads,
            in String[] constraints, IGenCallback callback);

    oneway void cancel(IGenCallback callback);

    void unload(long session);

    // Resident memory of the engine process in kB (VmRSS); the app cannot read another process's /proc.
    long residentKb();
}
