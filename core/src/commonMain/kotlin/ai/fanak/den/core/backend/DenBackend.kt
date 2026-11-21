package ai.fanak.den.core.backend

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
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

    // A second session on the same loaded weights, with its own context: requests on the two run at the same time.
    // Null when the backend cannot (one context per model), or when memory does not allow it.
    fun fork(): Session? = null

    // One vector per text, for search; a backend without embeddings says so.
    suspend fun embed(texts: List<String>): List<FloatArray> = throw UnsupportedFeatureException("embeddings", "this backend")

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

/** The type of the KV cache. A q8_0 cache takes 34 bytes per 32 values, against 64 for F16. */
@Serializable
enum class KvCache {
    @SerialName("f16") F16,
    @SerialName("q8_0") Q8_0,
}

@Serializable
data class LoadOptions(
    val accel: Accel,
    val contextLength: Int = 4096,
    val threads: Int? = null,
    // Map the weights from the file; false reads them into RAM, so the OS cannot drop them under memory pressure.
    val mmap: Boolean = true,
    // Requests that run at the same time on this session, each a sequence of contextLength tokens in one KV cache,
    // decoded together in batches; further ones wait. Backends that cannot run several take them one after another.
    val sequences: Int = 1,
    // A backend that has one type only ignores it.
    val kvCache: KvCache = KvCache.F16,
)

sealed interface Health {
    data object Ok : Health
    data class Degraded(val reason: String) : Health
    data class Failed(val reason: String) : Health
}
