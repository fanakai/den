package ai.fanak.den

import ai.fanak.den.core.backend.BackendDescriptor
import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.Features
import ai.fanak.den.core.backend.KvCache
import ai.fanak.den.core.backend.UnsupportedFeatureException
import ai.fanak.den.core.backend.features
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.Location
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import ai.fanak.den.core.backend.SlotPolicy
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Thermal
import ai.fanak.den.core.device.ThermalState
import ai.fanak.den.core.model.DownloadProgress
import ai.fanak.den.core.model.CompositeCatalog
import ai.fanak.den.core.model.HttpModelStore
import ai.fanak.den.core.model.HuggingFaceCatalog
import ai.fanak.den.core.model.IndexCatalog
import ai.fanak.den.core.model.ModelIndex
import ai.fanak.den.core.model.ModelAliases
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.OllamaCatalog
import ai.fanak.den.core.model.RemoteFile
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.InMemoryRecipeStore
import ai.fanak.den.core.recipe.LocalRecipeStore
import ai.fanak.den.core.recipe.Recipe
import ai.fanak.den.core.recipe.RecipeFiles
import ai.fanak.den.core.backend.GenStats
import ai.fanak.den.core.router.Candidate
import ai.fanak.den.core.router.Expected
import ai.fanak.den.core.router.Failure
import ai.fanak.den.core.router.Opened
import ai.fanak.den.core.router.Rejection
import ai.fanak.den.core.router.RouteExplanation
import ai.fanak.den.core.router.Router
import ai.fanak.den.core.router.RoutingPolicy
import ai.fanak.den.core.router.RouteHint
import ai.fanak.den.core.router.Source
import ai.fanak.den.core.router.EngineCrashedException
import ai.fanak.den.core.router.EngineLostException
import ai.fanak.den.core.router.FailureStore
import ai.fanak.den.core.router.FileFailureStore
import ai.fanak.den.core.bench.Bench
import ai.fanak.den.core.bench.BenchResult
import ai.fanak.den.core.bench.Conditions
import ai.fanak.den.core.bench.FirstRunBench
import ai.fanak.den.core.router.NoUsableBackendException
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.content.res.AssetManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DenConfig(
    // Default: <filesDir>/den/models
    val modelDir: File? = null,
    val contextLength: Int = 4096,
    // The shortest context the app accepts: a file with a lower maximum (e.g. an NPU build) then runs with its
    // maximum instead of being left out. Null: contextLength in full.
    val minContextLength: Int? = null,
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
    // false: only `recipes`, e.g. to test a phone without a recipe.
    val bundledRecipes: Boolean = true,
    // Run the backends in a separate process (`:den_engine`, Android 10 and later), so an engine crash does not take
    // the app down. Each backend is created again there from its class: a Kotlin object, a constructor taking a
    // Context, or one without arguments; others run in the app process.
    val isolate: Boolean = true,
    // Crashed candidates, skipped until the system or the backend is updated. Default: saved in <filesDir>/den.
    val failures: FailureStore? = null,
    // Embedded mode: runs before a file is downloaded (not when it is on disk). It may wait, or refuse with
    // DownloadRefusedException; the Den app asks the user here, checks its quota and waits for Wi-Fi.
    val beforeDownload: suspend (Variant, RemoteFile) -> Unit = { _, _ -> },
    // Embedded mode, when no recipe matches the phone: the first load measures the first file on each accelerator
    // (a few seconds each) and keeps the order on the phone, in <filesDir>/den/local-recipes.
    val firstRunBenchmark: Boolean = true,
    // Whether the app may use a model under this licence id (null: none given), e.g. { !Licences.nonCommercial(it) }.
    // Embedded mode; in connected mode it is checked when the model opens.
    val allowLicence: (String?) -> Boolean = { true },
    // AUTO: when the Den app is installed but fails to open the model, run it in this app instead. False reports the
    // error, so a second large download never starts in this app without it knowing.
    val fallback: Boolean = true,
    // Stable names for roles (den.default, den.small), each with models in order of preference; the Den app's own
    // apply in connected mode, where the user can move them.
    val aliases: Map<String, List<String>> = DEFAULT_ALIASES,
    // Requests on one model that run at the same time in this process: sequences of one context, decoded together
    // in batches (a KV cache more each, when free RAM has room at load); further ones wait.
    val parallel: Int = 1,
    // Whether a request may leave the phone, once remote backends exist: LOCAL_ONLY (the default) never sends one.
    val routing: RoutingPolicy = RoutingPolicy.LOCAL_ONLY,
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
    val recipes: List<Recipe> = (if (config.bundledRecipes) context.assets.recipes("den/recipes") else emptyList()) + config.recipes
    // GGUF files the user added from storage, used where they are.
    private val localFiles = LocalFiles(context)
    private val catalog: ModelCatalog = CompositeCatalog((config.catalogs ?: defaultCatalogs(context)) + localFiles)

    // The model files of this process (embedded mode); the Den app manages the shared ones.
    val files = ModelFiles(modelDir, catalog)
    private val store = config.store ?: LocalFirstStore(
        localFiles,
        if (config.download) {
            HttpModelStore(
                modelDir,
                { v -> catalog.remote(v) ?: throw java.io.FileNotFoundException("no download for $v in the catalog") },
                config.onDownload,
                online = { hasNetwork(context) },
                beforeDownload = config.beforeDownload,
            )
        } else {
            FileModelStore(modelDir, catalog)
        },
    )
    private val engine = EngineConnection(context)
    // The engine process when the backends run there, else this one.
    private fun engineRamMb(): Int? = ((engine.residentKb() ?: residentKb("self")) ?: 0L).takeIf { it > 0 }?.let { (it / 1024).toInt() }

    // Measured on this phone by the first-run benchmark.
    val localRecipes = LocalRecipeStore(File(context.filesDir, "den/local-recipes"), Build.FINGERPRINT)
    private val backends: List<DenBackend> = (config.backends ?: defaultBackends(context)).map { b ->
        when {
            !config.isolate || Build.VERSION.SDK_INT < 29 -> b
            // A server elsewhere: no engine on the phone to keep apart.
            (b.about?.location ?: Location.DEVICE) != Location.DEVICE -> b
            EngineService.creatable(b) -> IsolatedBackend(engine, b)
            else -> b.also { Log.w("Den", "${b.id} runs in the app process: no constructor the engine process can call") }
        }
    }

    // Embedded mode needs a backend in the app; this says which dependency to add instead of "nothing runs".
    private fun noBackend(model: String) = NoUsableBackendException(
        model, emptyList(),
        "no backend in this app for embedded mode: add the llama.cpp backend (:backends:llamacpp) or install the Den app",
    )

    private val router = Router(
        backends,
        InMemoryRecipeStore(recipes),
        catalog,
        store,
        config.failures ?: FileFailureStore(File(context.filesDir, "den/failures.json"), Build.FINGERPRINT),
        localRecipes,
        if (!config.firstRunBenchmark) null else FirstRunBench(
            store, localRecipes, "${Build.MANUFACTURER} ${Build.MODEL}",
            sha256 = { catalog.remote(it)?.sha256 }, ramMb = ::engineRamMb, contextLength = config.contextLength,
            onProgress = { Log.i("Den", it) },
        ),
        config.allowLicence,
        freeRamMb = { probe.profile().availRamMb },
        policy = config.routing,
        online = { hasNetwork(context) },
        localNetwork = { onLocalNetwork(context) },
    )

    private val minContext = config.minContextLength?.coerceAtMost(config.contextLength) ?: config.contextLength

    val models: List<String> get() = catalog.models

    fun device(): DeviceProfile = probe.profile()

    // What the router of this process would try, in order, without loading anything. route() gives the Den app's.
    // [reach]: where it may run, e.g. only on the phone for an app the user did not allow further; null: anywhere.
    fun plan(
        model: String,
        device: DeviceProfile = device(),
        minContextLength: Int = minContext,
        reach: Set<Location>? = null,
    ): List<Candidate> = router.plan(model, device, minContextLength, config.useMmap, reach)

    /**
     * What model() would try, in order, in the mode it would use: the Den app's route when connected (version 5 and
     * later), else this process's. Looks the model up first, as model() does.
     */
    suspend fun route(model: String): List<Route> {
        if (connected) {
            runCatching { app.plan(model, config.contextLength, minContext) }.getOrNull()?.let { return it }
        }
        val target = resolveAlias(model)
        resolve(target)
        return plan(target).map { Route(it.backend.id, it.accel, it.variant.quant, it.source) }
    }

    val aliases = ModelAliases(config.aliases)

    // The model behind [name] in this process: itself, or for an alias the user's choice or the first default that runs.
    suspend fun resolveAlias(name: String): String = aliases.resolve(name) { m -> resolve(m); plan(m).isNotEmpty() }

    // The file the router would pick on this phone, and its download without loading it. Runs in this process.
    fun fileName(model: String): String? = plan(model).firstOrNull()?.let { catalog.remote(it.variant)?.fileName }

    // The licence id of the file the router would pick, else of any file of the model (e.g. apache-2.0); null when
    // the source gives none.
    fun licence(model: String): String? = plan(model).firstOrNull()?.let { catalog.remote(it.variant)?.licence }
        ?: catalog.variants(model).firstNotNullOfOrNull { catalog.remote(it)?.licence } ?: catalog.licence(model)

    private fun licence(model: String, quant: String): String? =
        catalog.variants(model).firstOrNull { it.quant == quant }?.let { catalog.remote(it)?.licence }

    // Free RAM, in MB, the file the router would pick needs with this Den's shortest accepted context.
    fun ramNeedMb(model: String, device: DeviceProfile = device()): Int? =
        plan(model, device).firstOrNull()?.let { Router.ramNeed(it.variant, minContext, config.useMmap).freeMb }

    suspend fun download(model: String, reach: Set<Location>? = null) {
        resolve(model)
        val plan = plan(model, reach = reach)
        val local = plan.firstOrNull { it.location == Location.DEVICE } ?: return
        try {
            store.fetch(local.variant)
        } catch (e: java.io.IOException) {
            // Refused or offline: a server off the phone can still run it, and the router tries it next.
            if (plan.none { it.location != Location.DEVICE }) throw e
        }
    }

    /**
     * What each backend is, with its version: asking an engine process for it can start that process, so it runs on
     * the IO dispatcher. A backend that does not describe itself is left out.
     */
    suspend fun backendDescriptors(): List<BackendDescriptor> = withContext(Dispatchers.IO) {
        backends.mapNotNull { b -> runCatching { b.descriptor }.getOrNull() ?: b.about }
    }

    // The accelerator [model] would load on, as "backend ACCEL", when that accelerator holds one model at a time.
    fun exclusiveAccelerator(model: String): String? =
        plan(model).firstOrNull()?.takeIf { it.backend.about?.slots == SlotPolicy.EXCLUSIVE_ACCEL }?.let { "${it.backend.id} ${it.accel}" }

    // What this process's router would try for [model] (an alias resolved first), and what it leaves out and why.
    suspend fun explain(model: String): RouteExplanation {
        val target = resolveAlias(model)
        resolve(target)
        return router.explain(target, device(), minContext, config.useMmap)
    }

    // A newer commit of [model]'s hub repository than the one in use, for "check for updates"; null when up to date.
    suspend fun newerCommit(model: String): String? = withContext(Dispatchers.IO) { catalog.newerCommit(model) }

    /**
     * Moves [model] to the latest commit of its hub repository, in this process: downloads the file the router picks
     * (the old one is replaced once the new one's SHA-256 checks out) and loads it once. Files of the old commit that
     * the new one no longer has are deleted after that load. Returns the new commit, or null when up to date.
     * A model loaded before keeps its old file until it loads again.
     */
    suspend fun update(model: String): String? = withContext(Dispatchers.IO) {
        val target = resolveAlias(model)
        val commit = catalog.update(target) ?: return@withContext null
        router.open(target, device(), minContext, config.useMmap, minContext).session.unload()
        val kept = catalog.variants(target).mapNotNull { catalog.remote(it)?.path }.toSet()
        files.deleteExcept(target, kept)
        commit
    }

    /**
     * Adds a GGUF file the user picked with the system file picker (ACTION_OPEN_DOCUMENT): it stays where it is, and
     * apps name it by the returned file's id (file:<name>). Reads the whole file once for its SHA-256.
     */
    suspend fun addFile(uri: Uri): LocalFile = withContext(Dispatchers.IO) { localFiles.add(uri) }

    val addedFiles: List<LocalFile> get() = localFiles.all()

    /**
     * Adds the GGUF files of a folder the user granted with the system picker (ACTION_OPEN_DOCUMENT_TREE), where they
     * are; calling it again for the same folder adds the files that appeared since. Returns the files it added.
     */
    suspend fun addFolder(tree: Uri): List<LocalFile> = withContext(Dispatchers.IO) { localFiles.addFolder(tree) }

    val addedFolders: List<Uri> get() = localFiles.folders().map(Uri::parse)

    // Forgets a folder and its files, and gives up its permission; the files stay.
    fun removeFolder(tree: Uri) = localFiles.removeFolder(tree)

    // Forgets an added file and gives up its permission; the file itself stays.
    fun removeFile(id: String) = localFiles.remove(id)

    // Looks a model up in the catalogs (network for hf: names, then cached); plan() and fileName() use the result.
    suspend fun resolve(model: String) = catalog.resolve(model)

    /**
     * den bench: measures each file of [model] the router would try on this phone, by default the 4-bit ones, and
     * gives the results with a recipe (BenchResult.recipe()). Runs in this app, so it needs a release build, in the
     * foreground with the screen on: debug builds and background apps run the engine several times slower.
     */
    suspend fun bench(
        model: String,
        runs: Int = 5,
        quants: Set<String>? = setOf("q4_0", "q4_k_m"),
        // Each path once per cache type, F16 first; its reply is the reference of the others.
        kvCaches: Set<KvCache> = setOf(KvCache.F16),
        onProgress: (String) -> Unit = {},
    ): BenchResult {
        check(appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "den bench needs a release build" }
        val power = appContext.getSystemService(PowerManager::class.java)
        // Checked again before each file: the numbers no longer hold once one of these changes.
        fun notReady(): String? {
            val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            return when {
                !power.isInteractive -> "the screen is off"
                power.isPowerSaveMode -> "power saving is on; it lowers the CPU clocks"
                state.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "the app is not in the foreground"
                else -> null
            }
        }
        notReady()?.let { throw IllegalStateException("den bench cannot start: $it") }

        resolve(model)
        val device = device()
        val plan = router.plan(model, device, minContext).filter { quants == null || it.variant.quant in quants }
            .flatMap { c -> kvCaches.sorted().map { c.copy(kvCache = it) } }
        if (plan.isEmpty()) throw NoUsableBackendException(model, emptyList(), "nothing to measure for $model on this phone")
        val start = conditions()
        val results = Bench(
            store, runs, config.contextLength,
            sha256 = { catalog.remote(it)?.sha256 },
            ramMb = ::engineRamMb,
            onProgress = onProgress,
            check = ::notReady,
            beforeEach = { coolDown(onProgress) },
        ).run(plan)
        val end = conditions()
        val ran = results.firstOrNull { it.ok }
        val protocol = listOfNotNull(
            "den bench",
            results.mapNotNull { it.engine }.distinct().joinToString().ifEmpty { null },
            "automatic threads",
            ran?.let { "${it.promptTokens}-token prompt, ${it.outputTokens} tokens greedy" },
            ran?.takeIf { it.longPromptTokens > 0 }?.let { "${it.longPromptTokens}-token long prompt, ${Bench.LONG_TOKENS} tokens" },
            "median of $runs runs",
            "KV cache " + kvCaches.sorted().joinToString(" and ") { it.name.lowercase() },
            "release build, foreground, screen on",
            "battery ${start.batteryPct}%" + if (start.charging == true) " charging" else "",
            "thermal ${start.thermal}",
            "each path after the phone cooled to thermal NONE (at most $COOL_MINUTES minutes)",
        ).joinToString(", ")
        val v = plan.first().variant
        return BenchResult(
            model, v.arch, v.params, "${Build.MANUFACTURER} ${Build.MODEL}", device.soc, device.socFamily,
            LocalDate.now().toString(), protocol, start, end, results,
        )
    }

    // A path measured on a phone the one before heated runs slower; the wait ends after COOL_MINUTES anyway.
    private suspend fun coolDown(onProgress: (String) -> Unit) {
        val until = System.currentTimeMillis() + COOL_MINUTES * 60_000L
        while (probe.thermal() > ThermalState.NONE && System.currentTimeMillis() < until) {
            onProgress("bench: thermal ${probe.thermal()}, waiting to cool down")
            kotlinx.coroutines.delay(15_000)
        }
    }

    private fun conditions(): Conditions {
        val bm = appContext.getSystemService(BatteryManager::class.java)
        return Conditions(
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
            bm?.isCharging,
            device().thermal.name,
        )
    }


    // Whether model() will use the Den app.
    val connected: Boolean get() = config.mode != DenMode.EMBEDDED && app.status() == DenAppStatus.OK

    /**
     * The Den app's OpenAI-compatible endpoint (`http://127.0.0.1:<port>/v1`), for code that speaks HTTP; null when the
     * Den app is missing, not trusted, older, or has the endpoint off. Each client still needs a token from the Den app.
     */
    suspend fun httpEndpoint(): String? =
        if (app.status() == DenAppStatus.OK) runCatching { app.httpEndpoint() }.getOrNull() else null

    /**
     * [contextLength] and [minContextLength] replace the config's for this model, e.g. per client in the Den app.
     * [reach]: where it may run in this process; the Den app applies its own for each app. [hint]: what the app asks
     * for this model, e.g. that the prompt stays on the phone; it narrows the reach, in either mode. [expected]: how long
     * the app's prompts and answers are, so the path that answers them soonest comes first where recipes measured it.
     */
    suspend fun model(
        name: String,
        contextLength: Int = config.contextLength,
        minContextLength: Int = minOf(minContext, contextLength),
        reach: Set<Location>? = null,
        hint: RouteHint? = null,
        expected: Expected? = null,
    ): DenModel {
        val status = if (config.mode == DenMode.EMBEDDED) DenAppStatus.MISSING else app.status()
        if (status == DenAppStatus.OK) {
            val m = try {
                app.open(name, contextLength, minContextLength, hint, expected).also { it.licenceOf = ::licence }
            } catch (e: Exception) {
                // A model no backend can run fails the same way here; and the app may refuse the fallback.
                if (config.mode == DenMode.CONNECTED || !config.fallback || e is NoUsableBackendException) throw e
                Log.w("Den", "Den app unavailable, running $name in the app: $e")
                null
            }
            if (m != null) {
                // The Den app does not know this app's rule; refused here, not run in the app instead.
                if (!config.allowLicence(m.licence)) {
                    m.close()
                    throw NoUsableBackendException(name, emptyList(), "this app does not allow the licence of $name (${m.licence ?: "none given"})")
                }
                return m
            }
        } else if (config.mode == DenMode.CONNECTED) {
            throw IllegalStateException(
                if (status == DenAppStatus.UNTRUSTED) "the app installed as $DEN_PACKAGE is not signed by Fanak Den"
                else "the Den app is not installed",
            )
        } else if (status == DenAppStatus.UNTRUSTED) {
            Log.w("Den", "$DEN_PACKAGE is not signed by Fanak Den; running $name in the app")
        }
        if (backends.isEmpty()) throw noBackend(name)
        val target = resolveAlias(name)
        if (target != name) Log.i("Den", "$name is $target")
        val reopen: suspend () -> Opened = { router.open(target, device(), contextLength, config.useMmap, minContextLength, config.parallel, hint?.limit(reach) ?: reach, hint, expected) }
        val crashed = { o: Opened, e: Throwable -> router.reportFailure(device(), o.candidate, e) }
        return LocalModel(target, reopen(), reopen, crashed, probe::thermal, router::reportSpeed) { local -= it }.also {
            it.licenceOf = ::licence
            it.alias = name.takeIf { n -> n != target }
            local += it
        }
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

// The longest den bench waits for the phone to cool down before a path.
private const val COOL_MINUTES = 10

// Built-in aliases: the first model of each list that runs on the phone.
val DEFAULT_ALIASES = mapOf(
    "den.default" to listOf("qwen3-1.7b", "qwen3-0.6b"),
    "den.small" to listOf("qwen3-0.6b"),
)

// One candidate of a route, the same in both modes.
data class Route(val backend: String, val accel: Accel, val quant: String, val source: Source) {
    override fun toString() = "$backend $accel $quant ($source)"
}

// Backends an app may carry; the SDK finds them by class name, so an app that only uses the Den app has no native code.
internal val DEFAULT_BACKENDS = listOf("ai.fanak.den.backend.llamacpp.LlamaCppBackend", "ai.fanak.den.backend.litertlm.LiteRtLmBackend")

internal fun defaultBackends(context: Context): List<DenBackend> = DEFAULT_BACKENDS.mapNotNull { name ->
    runCatching { EngineService.create(context, name) }.getOrNull()
}

abstract class DenModel : AutoCloseable {
    abstract val name: String
    abstract val backend: String
    abstract val accel: Accel
    abstract val quant: String
    abstract val source: Source

    internal var licenceOf: (String, String) -> String? = { _, _ -> null }

    // The alias the app asked for (e.g. den.default) when [name] is the model behind it; null otherwise.
    var alias: String? = null
        internal set

    // Licence id of the file in use (e.g. apache-2.0), from the catalog; null when the source gives none.
    val licence: String? get() = licenceOf(name, quant)

    // True when the model runs in the Den app, shared with other apps.
    abstract val connected: Boolean

    // Where it runs: on this phone, or on a server of the local network or the cloud, so an app can show it.
    open val location: Location get() = Location.DEVICE

    // Files and paths the router left out before trying, with the reason; empty in connected mode for now.
    open val rejected: List<Rejection> get() = emptyList()

    // Why it runs off the phone, e.g. "no file fits the phone: q4_0 needs 9800 MB free, 3100 MB free"; null on the
    // phone and in connected mode.
    open val offPhone: String? get() = null

    // True when an earlier candidate failed and this one runs instead.
    val fallback: Boolean get() = failures.isNotEmpty()

    // The context it was loaded with, in tokens; 0 from a Den app older than version 5.
    open val contextLength: Int get() = 0
    open val failures: List<Failure> get() = emptyList()

    abstract fun chat(request: GenRequest): Flow<GenEvent>

    fun chat(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): Flow<GenEvent> =
        chat(GenRequest(listOf(ChatMessage(Role.USER, prompt)), maxTokens, temperature))

    suspend fun reply(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): String =
        chat(prompt, maxTokens, temperature).filterIsInstance<GenEvent.Text>().fold(StringBuilder()) { sb, t ->
            sb.append(t.text)
        }.toString()

    // A conversation that keeps its messages; turns after the first reuse the engine's cache for the history.
    fun newChat(system: String? = null, maxTokens: Int = 512, temperature: Float = 0.7f) = Chat(this, system, maxTokens, temperature)
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
    private val thermal: () -> ThermalState = { ThermalState.NONE },
    private val speed: (Candidate, GenStats) -> Unit = { _, _ -> },
    private val onClose: (LocalModel) -> Unit = {},
) : DenModel() {
    // Decode threads the engine picked on its own, the base for fewer threads when the phone heats up.
    @Volatile private var autoThreads = 0
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
    override val location get() = last.candidate.location
    override val rejected get() = last.rejected
    override val offPhone get() = last.offPhone
    override val contextLength get() = last.contextLength
    override val failures get() = last.failures

    override fun chat(request: GenRequest): Flow<GenEvent> = flow {
        // Refused here, before the engine: a feature the backend lacks would otherwise be ignored.
        last.candidate.backend.about?.let { about ->
            (request.features - about.features).firstOrNull()?.let { throw UnsupportedFeatureException(it, about.name) }
        }
        // Read before each request: a phone heats up during a session, not at load.
        val cooler = if (request.threads == null) Thermal.threads(autoThreads, thermal()) else null
        if (cooler != null) Log.i("Den", "$name: ${thermal()} thermal state, $cooler threads instead of $autoThreads")
        val req = if (cooler != null) request.copy(threads = cooler) else request
        var replayed = false
        while (true) {
            val o = lock.withLock {
                check(!closed) { "model $name is closed" }
                val o = opened ?: reopen().also {
                    Log.i("Den", "loaded $name: ${it.candidate.backend.id} ${it.candidate.accel} ${it.candidate.variant.quant}, context ${it.contextLength}")
                    opened = it
                    last = it
                }
                busy++
                o
            }
            var sent = false
            var lost: EngineLostException? = null
            try {
                o.session.generate(req)
                    .catch { if (it is EngineLostException) lost = it else throw it }
                    .collect {
                        if (it is GenEvent.Text) sent = true
                        if (it is GenEvent.Done) {
                            if (req.threads == null && it.stats.threads > 0) autoThreads = it.stats.threads
                            speed(o.candidate, it.stats)
                        }
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
    val ollama = OllamaCatalog(File(context.filesDir, "den/catalog"))
    val index = (context.assets.list("den/models") ?: emptyArray()).filter { it.endsWith(".yaml") }.map { name ->
        ModelIndex.parse(context.assets.open("den/models/$name").bufferedReader().readText(), name)
    }
    return listOf(BuiltinCatalog, IndexCatalog(index, CompositeCatalog(listOf(hf, ollama))), hf, ollama)
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
// Wi-Fi, Ethernet or a VPN (a LAN server reached over a VPN on mobile data counts); true when it cannot tell.
private fun onLocalNetwork(context: Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return listOf(
        android.net.NetworkCapabilities.TRANSPORT_WIFI,
        android.net.NetworkCapabilities.TRANSPORT_ETHERNET,
        android.net.NetworkCapabilities.TRANSPORT_VPN,
    ).any { caps.hasTransport(it) }
}

private fun hasNetwork(context: Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
