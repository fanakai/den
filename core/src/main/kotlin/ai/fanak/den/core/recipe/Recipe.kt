package ai.fanak.den.core.recipe

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.ModelFormat

data class RecipeChoice(
    val backend: String,
    val accel: Accel,
    val format: ModelFormat,
    val quant: String,
)

data class Measurement(
    val prefillTps: Double,
    val decodeTps: Double,
    val peakRamMb: Int,
    val device: String,
    val date: String,
)

// soc == null means the recipe applies to the whole socFamily.
data class Recipe(
    val model: String,
    val socFamily: String,
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
