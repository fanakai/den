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
import ai.fanak.den.core.router.EngineCrashedException
import ai.fanak.den.core.router.EngineLostException
import ai.fanak.den.core.router.FailureStore
import ai.fanak.den.core.router.FileFailureStore
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DenConfig(
    // Default: <filesDir>/den/models
    val modelDir: File? = null,
    val contextLength: Int = 4096,
    val useMmap: Boolean = true,
    // Download the chosen file on first use, checked against its SHA-256.
    val download: Boolean = true,
    val onDownload: (DownloadProgress) -> Unit = {},
    val mode: DenMode = DenMode.AUTO,
    // SHA-256 of the certificates a Den app may be signed with; prompts go only to a Den app signed with one of them.
    val denCertificates: List<String> = DEN_CERTIFICATES,
    // When the Den app asks the user whether this app may use it, open its approval screen (this app must be visible);
    // false leaves it to the Den app's notification.
    val approvalScreen: Boolean = true,
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
    // Run the backends in a separate process (`:den_engine`, Android 10 and later), so an engine crash does not take
    // the app down. Each backend is created again there from its class: a Kotlin object, a constructor taking a
    // Context, or one without arguments; others run in the app process.
    val isolate: Boolean = true,
    // Crashed candidates, skipped until the system or the backend is updated. Default: saved in <filesDir>/den.
    val failures: FailureStore? = null,
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
    private val appContext = context
    private val local = java.util.Collections.synchronizedList(mutableListOf<LocalModel>())

    // An app process holding a model of a gigabyte or more is the first one Android kills; idle models go first.
    private val trim = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val low = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            if (low || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                synchronized(local) { local.toList() }.forEach { it.unloadIfIdle("trim memory, level $level") }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Replaced by onTrimMemory")
        override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    init {
        appContext.registerComponentCallbacks(trim)
    }

    private val app = DenAppConnection(context, config.onDownload, config.denCertificates, config.approvalScreen)
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
    private val engine = EngineConnection(context)
    private val router = Router(
        (config.backends ?: listOf(LlamaCppBackend())).map { b ->
            when {
                !config.isolate || Build.VERSION.SDK_INT < 29 -> b
                EngineService.creatable(b) -> IsolatedBackend(engine, b)
                else -> b.also { Log.w("Den", "${b.id} runs in the app process: no constructor the engine process can call") }
            }
        },
        InMemoryRecipeStore(recipes),
        catalog,
        store,
        config.failures ?: FileFailureStore(File(context.filesDir, "den/failures.json"), Build.FINGERPRINT),
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
    val connected: Boolean get() = config.mode != DenMode.EMBEDDED && app.status() == DenAppStatus.OK

    /**
     * The Den app's OpenAI-compatible endpoint (`http://127.0.0.1:<port>/v1`), for code that speaks HTTP; null when the
     * Den app is missing, not trusted, older, or has the endpoint off. Each client still needs a token from the Den app.
     */
    suspend fun httpEndpoint(): String? =
        if (app.status() == DenAppStatus.OK) runCatching { app.httpEndpoint() }.getOrNull() else null

    suspend fun model(name: String): DenModel {
        val status = if (config.mode == DenMode.EMBEDDED) DenAppStatus.MISSING else app.status()
        if (status == DenAppStatus.OK) {
            try {
                return app.open(name)
            } catch (e: Exception) {
                if (config.mode == DenMode.CONNECTED) throw e
                Log.w("Den", "Den app unavailable, running $name in the app: $e")
            }
        } else if (config.mode == DenMode.CONNECTED) {
            throw IllegalStateException(
                if (status == DenAppStatus.UNTRUSTED) "the app installed as $DEN_PACKAGE is not signed by Fanak Den"
                else "the Den app is not installed",
            )
        } else if (status == DenAppStatus.UNTRUSTED) {
            Log.w("Den", "$DEN_PACKAGE is not signed by Fanak Den; running $name in the app")
        }
        val reopen: suspend () -> Opened = { router.open(name, device(), config.contextLength, config.useMmap) }
        val crashed = { o: Opened, e: Throwable -> router.reportFailure(device(), o.candidate, e) }
        return LocalModel(name, reopen(), reopen, crashed) { local -= it }.also { local += it }
    }

    // Closes the models this Den opened in the app process, and the connection to the Den app.
    override fun close() {
        appContext.unregisterComponentCallbacks(trim)
        synchronized(local) { local.toList().also { local.clear() } }.forEach { it.close() }
        app.close()
        engine.close()
    }

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

/**
 * A model in this process. It may be unloaded while idle (low memory, `Den.close()`) and loads again on next use.
 * When its engine process dies, it loads again on the next candidate, and a request that had sent no text is replayed.
 */
internal class LocalModel(
    override val name: String,
    first: Opened,
    private val reopen: suspend () -> Opened,
    private val crashed: (Opened, Throwable) -> Unit = { _, _ -> },
    private val onClose: (LocalModel) -> Unit = {},
) : DenModel() {
    // Guards `opened` and `busy`: a request marks the model busy under the lock, an unload checks it under the lock.
    private val lock = Mutex()
    private var opened: Opened? = first
    private var last: Opened = first
    private var busy = 0
    @Volatile private var closed = false

    override val backend get() = last.candidate.backend.id
    override val accel get() = last.candidate.accel
    override val quant get() = last.candidate.variant.quant
    override val source get() = last.candidate.source
    override val connected = false
    override val failures get() = last.failures

    override fun chat(request: GenRequest): Flow<GenEvent> = flow {
        var replayed = false
        while (true) {
            val o = lock.withLock {
                check(!closed) { "model $name is closed" }
                val o = opened ?: reopen().also {
                    Log.i("Den", "loaded $name: ${it.candidate.backend.id} ${it.candidate.accel} ${it.candidate.variant.quant}")
                    opened = it
                    last = it
                }
                busy++
                o
            }
            var sent = false
            var lost: EngineLostException? = null
            try {
                o.session.generate(request)
                    .catch { if (it is EngineLostException) lost = it else throw it }
                    .collect {
                        if (it is GenEvent.Text) sent = true
                        emit(it)
                    }
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        busy--
                        // Several requests can lose the same engine; the first one to get here forgets the session.
                        if (lost != null && opened === o) opened = null
                    }
                    if (closed) unloadIfIdle("closed")
                }
            }
            val e = lost ?: return@flow
            Log.w("Den", "$name lost its engine: ${e.message}")
            if (e is EngineCrashedException) crashed(o, e)
            // Text already shown to the app cannot be taken back.
            if (sent || replayed || closed) throw e
            replayed = true
        }
    }

    // False when the model is generating or another call holds it.
    fun unloadIfIdle(why: String): Boolean {
        if (!lock.tryLock()) return false
        try {
            val o = opened ?: return true
            if (busy > 0) return false
            o.session.unload()
            opened = null
            Log.i("Den", "unloaded $name ($why)")
            return true
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        closed = true
        onClose(this)
        if (!unloadIfIdle("closed")) Log.i("Den", "$name is generating; it unloads when the request ends")
    }
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
            // A recipe this SDK cannot read (newer schema, damaged) is skipped; the others still apply.
            name.endsWith(".yaml") -> listOfNotNull(
                RecipeFiles.parseOrNull(open(p).bufferedReader().readText(), p)
                    ?: null.also { Log.w("Den", "recipe $p skipped: unreadable or newer schema") },
            )
            else -> emptyList()
        }
    }

// activeNetwork is null when the default network is blocked for this app (Doze, battery saver, data saver).
private fun hasNetwork(context: Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
