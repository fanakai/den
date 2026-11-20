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

    oneway void generate(long session, in String[] roles, in String[] contents, int maxTokens,
            float temperature, float topP, in String[] stop, IGenCallback callback);

    oneway void cancel(long session);

    void unload(long session);
}
