package ai.fanak.den.core.recipe

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.ModelFormat
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecipeChoice(
    val backend: String,
    val accel: Accel,
    val format: ModelFormat,
    val quant: String,
)

@Serializable
data class Measurement(
    @SerialName("prefill_tps") val prefillTps: Double,
    @SerialName("decode_tps") val decodeTps: Double,
    val device: String,
    val date: String,
    @SerialName("peak_ram_mb") val peakRamMb: Int? = null,
    val protocol: String? = null,
    // SHA-256 of the measured file.
    val sha256: String? = null,
)

// The model a ranking was measured with: its GGUF architecture and its size in billions of parameters.
@Serializable
data class ModelSize(
    val arch: String,
    @SerialName("params_b") val paramsB: Double,
)

// The choices in order for one measured model; `measured` is for the first choice.
@Serializable
data class Ranking(
    val model: ModelSize,
    val choices: List<RecipeChoice>,
    val measured: Measurement? = null,
)

// A choice that fails for one model (crash, unsupported operator, wrong output) although it suits its size.
@Serializable
data class Avoid(
    val model: ModelSize,
    val choice: RecipeChoice,
    val reason: String,
)

/**
 * What works best on one SoC (or a whole family, when `soc` is null), by model size: speed depends mostly on the
 * chip, the engine, the accelerator, the quant and the size, so a few measured sizes cover every model.
 */
@Serializable
data class Recipe(
    val schema: Int = 1,
    @SerialName("soc_family") val socFamily: String,
    val soc: String? = null,
    val rankings: List<Ranking>,
    val avoid: List<Avoid> = emptyList(),
) {
    /**
     * The ranking for a model of this architecture and size: the nearest measured size on a log scale, among the
     * rankings of the same architecture when there are any. Null without a size.
     */
    fun rankingFor(arch: String?, params: Long?, sameSize: Boolean = false): Ranking? {
        if (params == null || params <= 0) return null
        if (sameSize) return rankings.firstOrNull { it.model.arch == arch && near(it.model.paramsB, params) }
        val sameArch = rankings.filter { it.model.arch == arch }
        val pool = sameArch.ifEmpty { rankings }
        val b = params / 1e9
        return pool.minByOrNull { abs(ln(it.model.paramsB / b)) }
    }

    // The ranking's choices, without those to avoid for this model (same architecture, same measured size).
    // [sameSize]: only a ranking measured with this architecture and size, as for the first-run benchmark.
    fun choicesFor(arch: String?, params: Long?, sameSize: Boolean = false): List<RecipeChoice> {
        val r = rankingFor(arch, params, sameSize) ?: return emptyList()
        val avoided = avoid.filter { it.model.arch == arch && near(it.model.paramsB, params!!) }.map { it.choice }
        return r.choices - avoided.toSet()
    }

    private fun near(paramsB: Double, params: Long) = abs(ln(paramsB / (params / 1e9))) < ln(1.25)

    companion object {
        const val SCHEMA = 1
    }
}

interface RecipeStore {
    // Exact SoC matches first, then family matches.
    fun find(device: DeviceProfile): List<Recipe>
}

class InMemoryRecipeStore(private val recipes: List<Recipe>) : RecipeStore {
    override fun find(device: DeviceProfile): List<Recipe> {
        val exact = recipes.filter { it.soc == device.soc }
        val family = recipes.filter { it.soc == null && it.socFamily == device.socFamily }
        return exact + family
    }
}

/**
 * Recipes measured on this phone by the first-run benchmark, one file per SoC in [dir]. A new ranking replaces the
 * one of the same model size. [env] names the system they were measured on (on Android the build fingerprint):
 * after a system or driver update they are measured again.
 */
class LocalRecipeStore(private val dir: File, private val env: String = "") : RecipeStore {
    private val cache = mutableMapOf<String, Recipe?>()

    init {
        val marker = File(dir, "env")
        if (marker.takeIf { it.isFile }?.readText() != env) {
            clear()
            dir.mkdirs()
            marker.writeText(env)
        }
    }

    @Synchronized
    override fun find(device: DeviceProfile): List<Recipe> = listOfNotNull(read(device.soc))

    @Synchronized
    fun add(recipe: Recipe) {
        val soc = requireNotNull(recipe.soc) { "a local recipe names its SoC" }
        val old = read(soc)?.rankings.orEmpty()
        val merged = recipe.copy(rankings = old.filter { o -> recipe.rankings.none { it.model == o.model } } + recipe.rankings)
        dir.mkdirs()
        val tmp = File(file(soc).path + ".tmp")
        tmp.writeText(RecipeFiles.encode(merged))
        if (!tmp.renameTo(file(soc))) {
            file(soc).delete()
            tmp.renameTo(file(soc))
        }
        cache[soc] = merged
    }

    // Forgets what was measured, e.g. after a system update.
    @Synchronized
    fun clear() {
        dir.listFiles()?.filter { it.name != "env" }?.forEach { it.delete() }
        cache.clear()
    }

    private fun file(soc: String) = File(dir, "${soc.replace(Regex("[^A-Za-z0-9._-]"), "_")}.yaml")

    private fun read(soc: String): Recipe? = cache.getOrPut(soc) {
        file(soc).takeIf { it.isFile }?.let { RecipeFiles.parseOrNull(it.readText(), it.name) }
    }
}
