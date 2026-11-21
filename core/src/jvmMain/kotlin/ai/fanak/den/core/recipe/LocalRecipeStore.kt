package ai.fanak.den.core.recipe

import ai.fanak.den.core.device.DeviceProfile
import java.io.File

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

