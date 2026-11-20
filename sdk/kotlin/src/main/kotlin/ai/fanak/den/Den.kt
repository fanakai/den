package ai.fanak.den

import ai.fanak.den.backend.llamacpp.LlamaCppBackend
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.DownloadProgress
import ai.fanak.den.core.model.CompositeCatalog
import ai.fanak.den.core.model.HttpModelStore
import ai.fanak.den.core.model.HuggingFaceCatalog
import ai.fanak.den.core.model.IndexCatalog
import ai.fanak.den.core.model.ModelIndex
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelStore
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
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.fold

data class DenConfig(
    // Default: <filesDir>/den/models
    val modelDir: File? = null,
    val contextLength: Int = 4096,
    val useMmap: Boolean = true,
    // Download the chosen file on first use, checked against its SHA-256.
    val download: Boolean = true,
    val onDownload: (DownloadProgress) -> Unit = {},
    val mode: DenMode = DenMode.AUTO,
    // The parts below apply in embedded mode; the Den app uses its own. Defaults: llama.cpp, the built-in catalog,
    // downloads (or files on disk when download = false), and the recipes bundled with the SDK.
    val backends: List<DenBackend>? = null,
    // Merged in order: the first catalog that serves a variant gives its file. Default: the built-in models, the
    // curated index (short names) and Hugging Face (hf:owner/repo). ModelScopeCatalog can be added here; it
    // needs access that anonymous clients do not get (#90).
    val catalogs: List<ModelCatalog>? = null,
    val store: ModelStore? = null,
    // Added to the bundled recipes.
    val recipes: List<Recipe> = emptyList(),
)

enum class DenMode {
    // The Den app when it is installed (models shared by all apps), otherwise in the app process.
    AUTO,
    EMBEDDED,
    CONNECTED,
}

/**
 * Entry point. `Den.connect(context).model("qwen3-1.7b")` picks the backend, accelerator and file for this phone,
 * and runs it in the Den app when installed, so all apps share one copy.
 */
class Den private constructor(context: Context, private val config: DenConfig) : AutoCloseable {
    private val app = DenAppConnection(context, config.onDownload)
    private val probe = AndroidDeviceProbe(context)
    val modelDir: File = (config.modelDir ?: File(context.filesDir, "den/models")).apply { mkdirs() }
    val recipes: List<Recipe> = context.assets.recipes("den/recipes") + config.recipes
    private val catalog: ModelCatalog = (config.catalogs ?: defaultCatalogs(context)).let { it.singleOrNull() ?: CompositeCatalog(it) }

    // The model files of this process (embedded mode); the Den app manages the shared ones.
    val files = ModelFiles(modelDir, catalog)
    private val store = config.store ?: if (config.download) {
        HttpModelStore(
            modelDir,
            { v -> catalog.remote(v) ?: throw java.io.FileNotFoundException("no download for $v in the catalog") },
            config.onDownload,
            online = { hasNetwork(context) },
        )
    } else {
        FileModelStore(modelDir, catalog)
    }
    private val router = Router(
        config.backends ?: listOf(LlamaCppBackend(config.useMmap)),
        InMemoryRecipeStore(recipes),
        catalog,
        store,
    )

    val models: List<String> get() = catalog.models

    fun device(): DeviceProfile = probe.profile()

    // What the router would try, in order, without loading anything.
    fun plan(model: String, device: DeviceProfile = device()): List<Candidate> = router.plan(model, device)

    // The file the router would pick on this phone, and its download without loading it. Runs in this process.
    fun fileName(model: String): String? = plan(model).firstOrNull()?.let { catalog.remote(it.variant)?.fileName }

    suspend fun download(model: String) {
        resolve(model)
        plan(model).firstOrNull()?.let { store.fetch(it.variant) }
    }

    // Looks a model up in the catalogs (network for hf: names, then cached); plan() and fileName() use the result.
    suspend fun resolve(model: String) = catalog.resolve(model)

    // Whether model() will use the Den app.
    val connected: Boolean get() = config.mode != DenMode.EMBEDDED && app.installed()

    suspend fun model(name: String): DenModel {
        if (connected) {
            try {
                return app.open(name)
            } catch (e: Exception) {
                if (config.mode == DenMode.CONNECTED) throw e
                Log.w("Den", "Den app unavailable, running $name in the app: $e")
            }
        } else if (config.mode == DenMode.CONNECTED) {
            throw IllegalStateException("the Den app is not installed")
        }
        return LocalModel(name, router.open(name, device(), config.contextLength))
    }

    override fun close() = app.close()

    companion object {
        fun connect(context: Context, config: DenConfig = DenConfig()) = Den(context.applicationContext, config)
    }
}

abstract class DenModel : AutoCloseable {
    abstract val name: String
    abstract val backend: String
    abstract val accel: Accel
    abstract val quant: String
    abstract val source: Source

    // True when the model runs in the Den app, shared with other apps.
    abstract val connected: Boolean
    open val failures: List<Failure> get() = emptyList()

    abstract fun chat(request: GenRequest): Flow<GenEvent>

    fun chat(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): Flow<GenEvent> =
        chat(GenRequest(listOf(ChatMessage(Role.USER, prompt)), maxTokens, temperature))

    suspend fun reply(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): String =
        chat(prompt, maxTokens, temperature).filterIsInstance<GenEvent.Text>().fold(StringBuilder()) { sb, t ->
            sb.append(t.text)
        }.toString()
}

internal class LocalModel(override val name: String, private val opened: Opened) : DenModel() {
    override val backend get() = opened.candidate.backend.id
    override val accel get() = opened.candidate.accel
    override val quant get() = opened.candidate.variant.quant
    override val source get() = opened.candidate.source
    override val connected = false
    override val failures get() = opened.failures

    override fun chat(request: GenRequest) = opened.session.generate(request)

    override fun close() = opened.session.unload()
}

private fun defaultCatalogs(context: Context): List<ModelCatalog> {
    val hf = HuggingFaceCatalog(File(context.filesDir, "den/catalog"))
    val index = (context.assets.list("den/models") ?: emptyArray()).filter { it.endsWith(".yaml") }.map { name ->
        ModelIndex.parse(context.assets.open("den/models/$name").bufferedReader().readText(), name)
    }
    return listOf(BuiltinCatalog, IndexCatalog(index, hf), hf)
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

// activeNetwork is null when the default network is blocked for this app (Doze, battery saver, data saver).
private fun hasNetwork(context: Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
