package ai.fanak.den.testapp

import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.Recipe
import ai.fanak.den.core.recipe.RecipeFiles
import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException

// Qwen3 GGUF files from unsloth/Qwen3-*-GGUF. minRamMb is a rough margin until den bench measures peak RAM (#14).
private val files = mapOf(
    Variant("qwen3-0.6b", ModelFormat.GGUF, "q4_0", 382_156_480, 700) to "Qwen3-0.6B-Q4_0.gguf",
    Variant("qwen3-0.6b", ModelFormat.GGUF, "q4_k_m", 396_705_472, 700) to "Qwen3-0.6B-Q4_K_M.gguf",
    Variant("qwen3-1.7b", ModelFormat.GGUF, "q4_0", 1_056_782_912, 1_500) to "Qwen3-1.7B-Q4_0.gguf",
    Variant("qwen3-1.7b", ModelFormat.GGUF, "q4_k_m", 1_107_409_472, 1_500) to "Qwen3-1.7B-Q4_K_M.gguf",
)

object Catalog : ModelCatalog {
    val models get() = files.keys.map { it.model }.distinct()
    override fun variants(model: String) = files.keys.filter { it.model == model }
}

// Files copied in with `adb shell run-as`; downloads come with the model manager (#10).
class LocalStore(private val dir: File) : ModelStore {
    override suspend fun fetch(variant: Variant): Artifact {
        val f = File(dir, files.getValue(variant))
        if (!f.isFile) throw FileNotFoundException("missing ${f.path}")
        return Artifact(variant, f.path)
    }
}

fun AssetManager.recipes(path: String = ""): List<Recipe> =
    (list(path) ?: emptyArray()).flatMap { name ->
        val p = if (path.isEmpty()) name else "$path/$name"
        val children = list(p)
        when {
            !children.isNullOrEmpty() -> recipes(p)
            name.endsWith(".yaml") -> listOf(RecipeFiles.parse(open(p).bufferedReader().readText(), p))
            else -> emptyList()
        }
    }
