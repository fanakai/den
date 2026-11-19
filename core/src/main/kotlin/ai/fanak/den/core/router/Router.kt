package ai.fanak.den.core.router

import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.RecipeStore
import kotlin.coroutines.cancellation.CancellationException

enum class Source { RECIPE_SOC, RECIPE_FAMILY, DEFAULT }

data class Candidate(
    val backend: DenBackend,
    val accel: Accel,
    val variant: Variant,
    val source: Source,
)

data class Failure(val candidate: Candidate, val error: Throwable)

data class Opened(
    val session: Session,
    val candidate: Candidate,
    val failures: List<Failure>,
) {
    // No recipe for this device: worth running a benchmark and submitting it.
    val needsBenchmark get() = candidate.source == Source.DEFAULT
}

class NoUsableBackendException(
    val model: String,
    val failures: List<Failure>,
) : Exception("No backend could load $model (${failures.size} attempts)")

class Router(
    private val backends: List<DenBackend>,
    private val recipes: RecipeStore,
    private val catalog: ModelCatalog,
    private val store: ModelStore,
) {
    private val defaultOrder = listOf(Accel.NPU, Accel.GPU, Accel.CPU)

    fun plan(model: String, device: DeviceProfile): List<Candidate> {
        val caps = backends.associateWith { runCatching { it.probe(device) }.getOrNull() }
        val variants = catalog.variants(model)
            .filter { it.soc == null || it.soc == device.soc }
            .filter { it.minRamMb <= device.availRamMb }

        fun usable(backend: DenBackend, accel: Accel, v: Variant) =
            caps[backend]?.supports(accel, v.format) == true

        val fromRecipes = recipes.find(model, device).flatMap { recipe ->
            val source = if (recipe.soc != null) Source.RECIPE_SOC else Source.RECIPE_FAMILY
            recipe.choices.flatMap { choice ->
                val backend = backends.find { it.id == choice.backend } ?: return@flatMap emptyList()
                variants
                    .filter { it.format == choice.format && it.quant == choice.quant }
                    .sortedByDescending { it.soc != null }
                    .filter { usable(backend, choice.accel, it) }
                    .map { Candidate(backend, choice.accel, it, source) }
            }
        }

        val defaults = defaultOrder.flatMap { accel ->
            backends.flatMap { backend ->
                variants.filter { usable(backend, accel, it) }
                    .sortedByDescending { it.sizeBytes }
                    .map { Candidate(backend, accel, it, Source.DEFAULT) }
            }
        }

        return (fromRecipes + defaults).distinctBy { Triple(it.backend.id, it.accel, it.variant) }
    }

    suspend fun open(model: String, device: DeviceProfile, contextLength: Int = 4096): Opened {
        val failures = mutableListOf<Failure>()
        for (c in plan(model, device)) {
            try {
                val artifact = store.fetch(c.variant)
                val session = c.backend.load(artifact, LoadOptions(c.accel, contextLength))
                return Opened(session, c, failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += Failure(c, e)
            }
        }
        throw NoUsableBackendException(model, failures)
    }
}
