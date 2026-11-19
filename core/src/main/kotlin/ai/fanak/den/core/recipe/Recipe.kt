package ai.fanak.den.core.recipe

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.ModelFormat
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
)

// soc == null means the recipe applies to the whole socFamily.
@Serializable
data class Recipe(
    val model: String,
    @SerialName("soc_family") val socFamily: String,
    val choices: List<RecipeChoice>,
    val soc: String? = null,
    val measured: Measurement? = null,
)

interface RecipeStore {
    // Exact SoC matches first, then family matches.
    fun find(model: String, device: DeviceProfile): List<Recipe>
}

class InMemoryRecipeStore(private val recipes: List<Recipe>) : RecipeStore {
    override fun find(model: String, device: DeviceProfile): List<Recipe> {
        val forModel = recipes.filter { it.model == model }
        val exact = forModel.filter { it.soc == device.soc }
        val family = forModel.filter { it.soc == null && it.socFamily == device.socFamily }
        return exact + family
    }
}
