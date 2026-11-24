package ai.fanak.den.backend.openai

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Location
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact

/**
 * The endpoints of one [location] as one backend, read again on each plan and load, so the user can add or remove a
 * server while the router runs. A model goes to the first endpoint that serves it and answers.
 */
class OpenAiEndpoints(
    private val endpoints: () -> List<Endpoint>,
    private val location: Location = Location.LAN,
) : DenBackend {
    override val id = "openai-${location.name.lowercase()}"
    override val version = "1"

    private fun current() = endpoints().filter { it.location == location }.map { OpenAiBackend(it) }

    override val about = OpenAiBackend(Endpoint(location.name.lowercase(), "", location, emptyMap())).about.copy(
        id = id, name = "OpenAI-compatible servers (${location.name.lowercase()})",
    )

    // Nothing to plan while no server is set.
    override fun probe(device: DeviceProfile): Capabilities =
        current().firstOrNull()?.probe(device) ?: Capabilities.NONE

    override suspend fun load(artifact: Artifact, opts: LoadOptions): Session {
        val errors = mutableListOf<String>()
        for (b in current()) {
            try {
                return b.load(artifact, opts)
            } catch (e: Exception) {
                errors += "${b.id}: ${e.message}"
            }
        }
        throw IllegalStateException("no ${location.name.lowercase()} server runs ${artifact.variant.model}: ${errors.joinToString("; ").ifEmpty { "none set" }}")
    }

    override fun health(): Health = Health.Ok

    override fun serves(model: String) = current().any { it.serves(model) }
}
