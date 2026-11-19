package ai.fanak.den.core.router

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.ModelFormat

// Thrown by a backend when its engine process died, as opposed to a normal load or generate error.
class EngineCrashedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class FailureKey(
    val soc: String,
    val backend: String,
    val accel: Accel,
    val model: String,
    val format: ModelFormat,
    val quant: String,
)

// Remembers candidates that crashed their engine on this device, so the router skips them.
interface FailureStore {
    fun isBlocked(key: FailureKey): Boolean
    fun record(key: FailureKey, error: Throwable)
}

class InMemoryFailureStore : FailureStore {
    private val blocked = mutableSetOf<FailureKey>()

    @Synchronized
    override fun isBlocked(key: FailureKey) = key in blocked

    @Synchronized
    override fun record(key: FailureKey, error: Throwable) {
        if (error is EngineCrashedException) blocked += key
    }
}
