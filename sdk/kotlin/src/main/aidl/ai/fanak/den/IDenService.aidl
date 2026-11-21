package ai.fanak.den;

import ai.fanak.den.IOpenCallback;
import ai.fanak.den.IGenCallback;

// The Den app's service, used by the SDK in connected mode.
interface IDenService {
    int version();

    String[] models();

    // Loads the model (shared by every caller) and returns a session through the callback.
    oneway void open(String model, IOpenCallback callback);

    oneway void generate(long session, in String[] roles, in String[] contents, int maxTokens,
            float temperature, float topP, in String[] stop, IGenCallback callback);

    oneway void cancel(long session);

    oneway void close(long session);

    // Version 2: stops an open still in progress, and the download it started (another waiting app resumes it).
    oneway void cancelOpen(IOpenCallback callback);

    // Version 3: open() may call IOpenCallback.onApproval with the approval screen.

    // Version 4: the port of the OpenAI-compatible endpoint on 127.0.0.1, or -1 when it is off.
    int httpPort();

    // Version 5: open() with the app's options as JSON, e.g. {"contextLength":4096,"minContextLength":2048}. The
    // callback gets onContext before onOpened, and onFailed instead of onError.
    oneway void openWith(String model, String options, IOpenCallback callback);

    // Version 5: the Den app's route for the model on this phone, one "backend accel quant source" per candidate.
    String[] plan(String model, String options);

    // Version 6: openWith and plan resolve aliases (den.*) with the Den app's own, which the user can move.
}
