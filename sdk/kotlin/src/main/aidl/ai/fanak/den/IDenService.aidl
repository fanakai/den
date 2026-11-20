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
}
