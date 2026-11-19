package ai.fanak.den.core.backend

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import kotlinx.coroutines.flow.Flow

interface DenBackend {
    val id: String

    fun probe(device: DeviceProfile): Capabilities

    suspend fun load(artifact: Artifact, opts: LoadOptions): Session

    fun health(): Health
}

interface Session {
    fun generate(req: GenRequest): Flow<GenEvent>

    fun unload()
}

data class Capabilities(
    val accels: Set<Accel>,
    val formats: Set<ModelFormat>,
) {
    fun supports(accel: Accel, format: ModelFormat) = accel in accels && format in formats

    companion object {
        val NONE = Capabilities(emptySet(), emptySet())
    }
}

data class LoadOptions(
    val accel: Accel,
    val contextLength: Int = 4096,
    val threads: Int? = null,
)

sealed interface Health {
    data object Ok : Health
    data class Degraded(val reason: String) : Health
    data class Failed(val reason: String) : Health
}
