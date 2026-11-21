package ai.fanak.den.core.router

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.ModelFormat
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable

// The engine process went away and took its sessions with it; reopen the model. Not the candidate's fault, e.g. the
// system stopped the process to free memory.
open class EngineLostException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// The engine process died while this candidate loaded or generated: the router blocks it on this device.
class EngineCrashedException(message: String, cause: Throwable? = null) : EngineLostException(message, cause)

@Serializable
data class FailureKey(
    val soc: String,
    val backend: String,
    val accel: Accel,
    val model: String,
    val format: ModelFormat,
    val quant: String,
    // A new backend version tests the path again.
    val backendVersion: String = "",
)

// Remembers candidates that crashed their engine on this device, so the router skips them.
interface FailureStore {
    fun isBlocked(key: FailureKey): Boolean
    fun record(key: FailureKey, error: Throwable)
}

class InMemoryFailureStore : FailureStore {
    // Replaced as a whole: crashes are rare, checks are not.
    @Volatile private var blocked: Set<FailureKey> = emptySet()

    override fun isBlocked(key: FailureKey) = key in blocked

    override fun record(key: FailureKey, error: Throwable) {
        if (error is EngineCrashedException) blocked = blocked + key
    }
}
