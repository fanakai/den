package ai.fanak.den

import ai.fanak.den.backend.llamacpp.LlamaCppBackend
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.recipe.InMemoryRecipeStore
import ai.fanak.den.core.recipe.Recipe
import ai.fanak.den.core.recipe.RecipeFiles
import ai.fanak.den.core.router.Candidate
import ai.fanak.den.core.router.Failure
import ai.fanak.den.core.router.Opened
import ai.fanak.den.core.router.Router
import ai.fanak.den.core.router.Source
import android.content.Context
import android.content.res.AssetManager
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.fold

data class DenConfig(
    // Default: <filesDir>/den/models
    val modelDir: File? = null,
    val contextLength: Int = 4096,
)

/**
 * Entry point. `Den.connect(context).model("qwen3-1.7b")` picks the backend, accelerator and file for this phone.
 * Runs in the app process for now; the connected mode to the Den app comes with #8.
 */
class Den private constructor(context: Context, private val config: DenConfig) {
    private val probe = AndroidDeviceProbe(context)
    val modelDir: File = (config.modelDir ?: File(context.filesDir, "den/models")).apply { mkdirs() }
    val recipes: List<Recipe> = context.assets.recipes("den/recipes")
    private val router = Router(
        listOf(LlamaCppBackend()),
        InMemoryRecipeStore(recipes),
        BuiltinCatalog,
        FileModelStore(modelDir),
    )

    val models: List<String> get() = BuiltinCatalog.models

    fun device(): DeviceProfile = probe.profile()

    // What the router would try, in order, without loading anything.
    fun plan(model: String): List<Candidate> = router.plan(model, device())

    suspend fun model(name: String): DenModel = DenModel(name, router.open(name, device(), config.contextLength))

    companion object {
        fun connect(context: Context, config: DenConfig = DenConfig()) = Den(context.applicationContext, config)
    }
}

class DenModel internal constructor(val name: String, private val opened: Opened) : AutoCloseable {
    val backend: String get() = opened.candidate.backend.id
    val accel: Accel get() = opened.candidate.accel
    val quant: String get() = opened.candidate.variant.quant
    val source: Source get() = opened.candidate.source
    val failures: List<Failure> get() = opened.failures

    fun chat(request: GenRequest): Flow<GenEvent> = opened.session.generate(request)

    fun chat(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): Flow<GenEvent> =
        chat(GenRequest(listOf(ChatMessage(Role.USER, prompt)), maxTokens, temperature))

    suspend fun reply(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): String =
        chat(prompt, maxTokens, temperature).filterIsInstance<GenEvent.Text>().fold(StringBuilder()) { sb, t ->
            sb.append(t.text)
        }.toString()

    override fun close() = opened.session.unload()
}

internal fun AssetManager.recipes(path: String): List<Recipe> =
    (list(path) ?: emptyArray()).flatMap { name ->
        val p = "$path/$name"
        val children = list(p)
        when {
            !children.isNullOrEmpty() -> recipes(p)
            name.endsWith(".yaml") -> listOf(RecipeFiles.parse(open(p).bufferedReader().readText(), p))
            else -> emptyList()
        }
    }
