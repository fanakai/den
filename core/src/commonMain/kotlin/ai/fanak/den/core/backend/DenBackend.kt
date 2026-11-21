package ai.fanak.den.core.backend

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface DenBackend {
    val id: String

    // Part of the key of a recorded crash, so an updated backend gets tried again.
    val version: String get() = ""

    // What it is, without its version: plain data, so reading it never starts the engine. Null: not described.
    val about: BackendDescriptor? get() = null

    // With the version: shown on the Den screen and at /den/v1/system-info.
    val descriptor: BackendDescriptor? get() = about?.copy(version = version)

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
    // GGUF architectures the engine loads; null when the backend does not say.
    val architectures: Set<String>? = null,
) {
    fun supports(accel: Accel, format: ModelFormat) = accel in accels && format in formats

    // A file of unknown architecture is tried: only a known one that the engine lacks is refused.
    fun loads(arch: String?) = arch == null || architectures == null || arch in architectures

    companion object {
        val NONE = Capabilities(emptySet(), emptySet())
    }
}

@Serializable
data class LoadOptions(
    val accel: Accel,
    val contextLength: Int = 4096,
    val threads: Int? = null,
    // Map the weights from the file; false reads them into RAM, so the OS cannot drop them under memory pressure.
    val mmap: Boolean = true,
)

sealed interface Health {
    data object Ok : Health
    data class Degraded(val reason: String) : Health
    data class Failed(val reason: String) : Health
}
