package ai.fanak.den.core.bench

import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.LocalRecipeStore
import ai.fanak.den.core.router.Candidate
import ai.fanak.den.core.router.FirstRun
import ai.fanak.den.core.router.Source
import java.time.LocalDate

/**
 * The first-run benchmark: when no recipe matches, measures the router's first file on each backend and accelerator
 * that could run it, and the other files already on disk, a short run each, and saves their order in [recipes]. It
 * downloads nothing the router would not download anyway.
 */
class FirstRunBench(
    private val store: ModelStore,
    private val recipes: LocalRecipeStore,
    private val device: String,
    private val sha256: (Variant) -> String? = { null },
    private val ramMb: () -> Int? = { null },
    private val onProgress: (String) -> Unit = {},
    private val contextLength: Int = 4096,
    private val today: () -> String = { LocalDate.now().toString() },
) : FirstRun {
    override suspend fun measure(device: DeviceProfile, plan: List<Candidate>, report: (Candidate, Throwable) -> Unit) {
        val first = plan.firstOrNull { it.source == Source.DEFAULT } ?: return
        // Other files only when on disk already: they cost no download.
        val same = plan.filter { it.source == Source.DEFAULT && (it.variant == first.variant || store.has(it.variant)) }.take(MAX)
        val v = first.variant
        // Nothing to choose between, or no size to file the recipe under.
        if (same.size < 2 || v.arch == null || v.params == null) return
        onProgress("first-run benchmark of ${v.model}: ${same.joinToString { "${it.backend.id} ${it.accel} ${it.variant.quant}" }}")
        val results = Bench(
            store, runs = 1, contextLength = contextLength, sha256 = sha256, ramMb = ramMb, onProgress = onProgress,
            outputTokens = OUTPUT_TOKENS, full = false, onFailure = report,
        ).run(same)
        val engines = results.mapNotNull { it.engine }.distinct().joinToString().ifEmpty { null }
        val protocol = listOfNotNull("first-run benchmark", engines, "automatic threads", "$OUTPUT_TOKENS tokens greedy, 1 run").joinToString(", ")
        val r = BenchResult(
            v.model, v.arch, v.params, this.device, device.soc, device.socFamily, today(), protocol,
            Conditions(thermal = device.thermal.name), Conditions(), results,
        )
        r.recipe()?.let { recipe ->
            recipes.add(recipe)
            onProgress("first-run benchmark: ${r.ranked().joinToString { "${it.accel} ${it.quant} ${it.prefillTps?.median}/${it.decodeTps?.median} tok/s" }}")
        }
    }

    companion object {
        const val OUTPUT_TOKENS = 32

        // Candidates measured at most, to keep the first load short.
        const val MAX = 4
    }
}
