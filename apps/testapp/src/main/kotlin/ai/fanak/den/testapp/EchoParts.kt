package ai.fanak.den.testapp

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import kotlinx.coroutines.flow.flow

// A backend, catalog and store from outside the SDK, passed through DenConfig (#71). The model repeats the prompt.
private val ECHO = Variant("echo", ModelFormat.GGUF, "none", 0, 0)

object EchoCatalog : ModelCatalog {
    override val models = listOf("echo")

    override fun variants(model: String) = if (model == "echo") listOf(ECHO) else emptyList()
}

object EchoStore : ModelStore {
    override suspend fun fetch(variant: Variant) = Artifact(variant, "echo")
}

object EchoBackend : DenBackend {
    override val id = "echo"

    override fun probe(device: DeviceProfile) = Capabilities(setOf(Accel.CPU), setOf(ModelFormat.GGUF))

    override suspend fun load(artifact: Artifact, opts: LoadOptions) = object : Session {
        override fun generate(req: GenRequest) = flow {
            val words = req.messages.last().content.split(" ")
            words.forEach { emit(GenEvent.Text("$it ")) }
            emit(GenEvent.Done(GenStats(words.size, words.size, 1, 1)))
        }

        override fun unload() {}
    }

    override fun health() = Health.Ok
}
