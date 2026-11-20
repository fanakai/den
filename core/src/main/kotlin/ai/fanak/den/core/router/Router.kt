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
import java.io.IOException
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
    // Why nothing was tried, when the plan was empty.
    val reason: String? = null,
) : Exception(reason ?: "No backend could load $model (${failures.size} attempts)")

class Router(
    private val backends: List<DenBackend>,
    private val recipes: RecipeStore,
    private val catalog: ModelCatalog,
    private val store: ModelStore,
    private val failures: FailureStore = InMemoryFailureStore(),
) {
    // Without a recipe the CPU comes first: a GPU or NPU that is present is not always faster (A52s) and can crash.
    private val defaultOrder = listOf(Accel.CPU, Accel.GPU, Accel.NPU)

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
                variants.filter { usable(backend, accel, it) && !lowBit(it.quant) }
                    .sortedWith(compareBy<Variant> { quantRank(it.quant) }.thenByDescending { it.sizeBytes })
                    .map { Candidate(backend, accel, it, Source.DEFAULT) }
            }
        }

        return (fromRecipes + defaults)
            .distinctBy { Triple(it.backend.id, it.accel, it.variant) }
            .filterNot { failures.isBlocked(key(device, it)) }
    }

    suspend fun open(model: String, device: DeviceProfile, contextLength: Int = 4096, mmap: Boolean = true): Opened {
        catalog.resolve(model)
        val tried = mutableListOf<Failure>()
        if (plan(model, device).isEmpty()) throw NoUsableBackendException(model, tried, emptyPlanReason(model, device))
        for (c in plan(model, device)) {
            try {
                val artifact = try {
                    store.fetch(c.variant)
                } catch (e: IOException) {
                    // Without network every other file fails the same way, after its own timeout.
                    if (e.isNetwork()) throw NoUsableBackendException(model, tried + Failure(c, e))
                    throw e
                }
                val session = try {
                    c.backend.load(artifact, LoadOptions(c.accel, contextLength, mmap = mmap))
                } catch (e: EngineLostException) {
                    // The engine did not start or the system stopped it: other files would only be downloaded in vain.
                    if (e !is EngineCrashedException) throw NoUsableBackendException(model, tried + Failure(c, e))
                    throw e
                }
                return Opened(session, c, tried)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoUsableBackendException) {
                throw e
            } catch (e: Throwable) {
                tried += Failure(c, e)
                failures.record(key(device, c), e)
            }
        }
        throw NoUsableBackendException(model, tried)
    }

    private fun emptyPlanReason(model: String, device: DeviceProfile): String {
        val all = catalog.variants(model).filter { it.soc == null || it.soc == device.soc }
        val fits = all.filter { it.minRamMb <= device.availRamMb }
        return when {
            all.isEmpty() -> "unknown model $model: no catalog has a file for it"
            fits.isEmpty() -> "no file of $model fits in free RAM: ${device.availRamMb} MB free, the smallest needs ${all.minOf { it.minRamMb }} MB"
            fits.all { lowBit(it.quant) } -> "only 1- or 2-bit files of $model fit in free RAM (${device.availRamMb} MB); they are not used without a recipe"
            else -> "no backend on this device runs $model (${fits.map { it.format }.distinct().joinToString()})"
        }
    }

    // For crashes after load, e.g. during generation; the next open() skips this candidate.
    fun reportFailure(device: DeviceProfile, candidate: Candidate, error: Throwable) =
        failures.record(key(device, candidate), error)

    private fun key(device: DeviceProfile, c: Candidate) = FailureKey(
        device.soc, c.backend.id, c.accel, c.variant.model, c.variant.format, c.variant.quant, c.backend.version,
    )

    private companion object {
        // Without a recipe: the usual 4-bit quants first (quality per byte on a phone), then larger ones,
        // 3-bit last but one, F16 and BF16 last. Size breaks ties within a rank.
        val PREFERRED = listOf("q4_k_m", "q4_0", "q4_k_s", "iq4_xs", "iq4_nl", "q5_k_m", "q5_k_s", "q4_1", "q6_k", "q8_0")

        // 1- and 2-bit files lose too much quality to be picked without a recipe that measured them.
        fun lowBit(quant: String) = quant.lowercase().removePrefix("ud-").matches(Regex("i?q[12](_.*)?"))

        fun quantRank(quant: String): Int {
            val q = quant.lowercase().removePrefix("ud-")
            PREFERRED.indexOf(q).let { if (it >= 0) return it }
            return when {
                q == "f16" || q == "bf16" || q == "f32" -> PREFERRED.size + 2
                q.matches(Regex("i?q3.*")) -> PREFERRED.size + 1
                else -> PREFERRED.size
            }
        }
    }
}

private fun IOException.isNetwork() =
    this is java.net.UnknownHostException || this is java.net.SocketTimeoutException ||
        this is java.net.ConnectException || this is ai.fanak.den.core.model.OfflineException
