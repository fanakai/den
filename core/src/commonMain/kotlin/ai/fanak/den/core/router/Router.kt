package ai.fanak.den.core.router

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Location
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.device.Thermal
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.RecipeStore
import kotlin.concurrent.Volatile
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

// RECIPE_LOCAL: measured on this phone by the first-run benchmark.
enum class Source { RECIPE_SOC, RECIPE_FAMILY, RECIPE_LOCAL, DEFAULT }

/**
 * Runs when no recipe matches the model: measures candidates of [plan] and saves their order as a local recipe, so
 * this and later opens follow it. Errors go to [report]; the router then keeps its default order.
 */
fun interface FirstRun {
    suspend fun measure(device: DeviceProfile, plan: List<Candidate>, report: (Candidate, Throwable) -> Unit)
}

data class Candidate(
    val backend: DenBackend,
    val accel: Accel,
    val variant: Variant,
    val source: Source,
) {
    // Where it runs, from its backend's descriptor; a backend without one runs on the phone.
    val location: Location get() = backend.about?.location ?: Location.DEVICE
}

/**
 * Whether the router may send a request off the phone. LOCAL_ONLY: never (the default). LOCAL_FIRST: remote candidates
 * after the local ones, when none fits or all fail; on a hot phone, LAN servers before the phone. REMOTE_FIRST: remote
 * ones first. Remote ones go LAN before cloud, and only when the phone is online.
 */
enum class RoutingPolicy { LOCAL_ONLY, LOCAL_FIRST, REMOTE_FIRST }

/** What an app asks of one model, within the reach the user gave it: a hint only narrows it. */
enum class RouteHint {
    // The prompt stays on the phone.
    PRIVATE,

    // No paid endpoint: the phone and LAN servers.
    CHEAP;

    fun limit(reach: Set<Location>?): Set<Location> {
        val allowed = when (this) {
            PRIVATE -> setOf(Location.DEVICE)
            CHEAP -> setOf(Location.DEVICE, Location.LAN)
        }
        return reach?.intersect(allowed) ?: allowed
    }
}

data class Failure(val candidate: Candidate, val error: Throwable)

// Why a file, or a path for it, is not in the plan.
enum class RejectReason { OTHER_SOC, CONTEXT, LICENCE, RAM, PHONE_RAM, DISK, NO_BACKEND, ARCHITECTURE, LOW_BIT, CRASHED }

data class Rejection(
    val quant: String,
    val format: ModelFormat,
    val reason: RejectReason,
    val detail: String,
    // Set for a path that crashed before; the reasons above it are about the whole file.
    val backend: String? = null,
    val accel: Accel? = null,
)

// The plan, in order, and what was left out and why: what an app shows when it asks why a model runs where it does.
data class RouteExplanation(val candidates: List<Candidate>, val rejected: List<Rejection>)

data class Opened(
    val session: Session,
    val candidate: Candidate,
    val failures: List<Failure>,
    // The context it was loaded with, in tokens: shorter than asked when RAM or the file allowed no more.
    val contextLength: Int = 0,
    // Left out of the plan before any try; [failures] are the tries that failed before this one (a fallback).
    val rejected: List<Rejection> = emptyList(),
    // Why it runs off the phone, for the user to read; null on the phone.
    val offPhone: String? = null,
) {
    // No recipe for this device: worth running a benchmark and submitting it.
    val needsBenchmark get() = candidate.source == Source.DEFAULT
}

class NoUsableBackendException(
    val model: String,
    val failures: List<Failure>,
    // Why nothing was tried, when the plan was empty.
    val reason: String? = null,
    val rejected: List<Rejection> = emptyList(),
) : Exception(reason ?: "No backend could load $model (${failures.size} attempts)")

class Router(
    private val backends: List<DenBackend>,
    private val recipes: RecipeStore,
    private val catalog: ModelCatalog,
    private val store: ModelStore,
    private val failures: FailureStore = InMemoryFailureStore(),
    private val local: RecipeStore? = null,
    private val firstRun: FirstRun? = null,
    // Whether the app may use a file under this licence id (null: none given); the catalog gives the id.
    private val allowLicence: (String?) -> Boolean = { true },
    // Free RAM now, in MB, read again before each candidate is fetched and loaded; default: the device profile's.
    private val freeRamMb: (() -> Int)? = null,
    private val policy: RoutingPolicy = RoutingPolicy.LOCAL_ONLY,
    // Whether the phone has a network now; remote candidates need one.
    private val online: () -> Boolean = { true },
) {
    // Variants measured by the first run in this process, so a run that saved nothing is not repeated on each open.
    @Volatile private var measured: Set<Variant> = emptySet()

    // Without a recipe the CPU comes first: a GPU or NPU that is present is not always faster (A52s) and can crash.
    private val defaultOrder = listOf(Accel.CPU, Accel.GPU, Accel.NPU)

    /**
     * [minContext]: the shortest context the app accepts, in tokens. A file is left out when its maximum context is
     * lower, when it does not fit in RAM with that context, or when it is not on disk and the disk has no room for it.
     * [reach]: where this call may run the model, e.g. only on the phone for an app the user did not allow further;
     * null leaves it to the policy.
     */
    fun plan(
        model: String,
        device: DeviceProfile,
        minContext: Int? = null,
        mmap: Boolean = true,
        reach: Set<Location>? = null,
    ): List<Candidate> {
        val caps = backends.associateWith { runCatching { it.probe(device) }.getOrNull() }
        val variants = catalog.variants(model)
            .filter { it.soc == null || it.soc == device.soc }
            .filter { fitsContext(it, minContext) }
            .filter { licenceOk(it) }
            .filter { fits(it, minContext ?: 0, device, mmap, device.availRamMb) }
            .filter { onDisk(it) }

        fun usable(backend: DenBackend, accel: Accel, v: Variant) =
            caps[backend]?.let { it.supports(accel, v.format) && it.loads(v.arch) } == true

        // Recipes match the model by architecture and size, so a curated name and its hub repository get the same.
        val size = variants.firstOrNull { it.params != null }
        val fromRecipes = recipes.find(device).flatMap { recipe ->
            val source = if (recipe.soc != null) Source.RECIPE_SOC else Source.RECIPE_FAMILY
            recipe.choicesFor(size?.arch, size?.params).flatMap { choice ->
                val backend = backends.find { it.id == choice.backend } ?: return@flatMap emptyList()
                variants
                    .filter { it.format == choice.format && it.quant == choice.quant }
                    .sortedByDescending { it.soc != null }
                    .filter { usable(backend, choice.accel, it) }
                    .map { Candidate(backend, choice.accel, it, source) }
            }
        }

        val fromLocal = local?.find(device).orEmpty().flatMap { recipe ->
            recipe.choicesFor(size?.arch, size?.params, sameSize = true).flatMap { choice ->
                val backend = backends.find { it.id == choice.backend } ?: return@flatMap emptyList()
                variants
                    .filter { it.format == choice.format && it.quant == choice.quant && usable(backend, choice.accel, it) }
                    .map { Candidate(backend, choice.accel, it, Source.RECIPE_LOCAL) }
            }
        }

        // File by file, each on every accelerator: a failure tries the file already fetched before another download.
        val defaults = variants.filter { !lowBit(it.quant) }
            .sortedWith(compareBy<Variant> { quantRank(it.quant) }.thenByDescending { it.sizeBytes })
            .flatMap { v ->
                defaultOrder.flatMap { accel ->
                    backends.filter { usable(it, accel, v) }.map { Candidate(it, accel, v, Source.DEFAULT) }
                }
            }

        val onPhone = (fromRecipes + fromLocal + defaults)
            .filter { it.location == Location.DEVICE }
            .distinctBy { Triple(it.backend.id, it.accel, it.variant) }
            .filterNot { failures.isBlocked(key(device, it)) }
            // Hot: smaller files first, each still on its accelerators in order (a stable sort).
            .let { plan -> if (Thermal.hot(device.thermal)) plan.sortedBy { it.variant.sizeBytes } else plan }
        val remote = remote(model, device, minContext, caps)
        val ordered = when (policy) {
            RoutingPolicy.LOCAL_ONLY -> onPhone
            // Hot: a LAN server spares the phone; the cloud still comes last.
            RoutingPolicy.LOCAL_FIRST -> if (Thermal.hot(device.thermal)) {
                remote.filter { it.location == Location.LAN } + onPhone + remote.filter { it.location != Location.LAN }
            } else {
                onPhone + remote
            }
            RoutingPolicy.REMOTE_FIRST -> remote + onPhone
        }
        return if (reach == null) ordered else ordered.filter { it.location in reach }
    }

    /** The plan of [model] with what it leaves out: each file with the first reason that removes it, then each path that crashed before. */
    fun explain(
        model: String,
        device: DeviceProfile,
        minContext: Int? = null,
        mmap: Boolean = true,
        reach: Set<Location>? = null,
    ): RouteExplanation {
        val planned = plan(model, device, minContext, mmap, reach)
        val caps = backends.mapNotNull { b -> runCatching { b.probe(device) }.getOrNull()?.let { b to it } }
        val ctx = minContext ?: 0
        val rejected = mutableListOf<Rejection>()
        for (v in catalog.variants(model)) {
            fun no(reason: RejectReason, detail: String) = rejected.add(Rejection(v.quant, v.format, reason, detail))
            val need = ramNeed(v, ctx, mmap)
            when {
                v.soc != null && v.soc != device.soc -> no(RejectReason.OTHER_SOC, "built for ${v.soc}")
                !fitsContext(v, minContext) -> no(RejectReason.CONTEXT, "${v.maxContext} tokens at most, $minContext needed")
                !licenceOk(v) -> no(RejectReason.LICENCE, "licence ${catalog.remote(v)?.licence ?: "not given"} not allowed by this app")
                need.totalMb > device.ramMb -> no(RejectReason.PHONE_RAM, "needs ${need.totalMb} MB, the phone has ${device.ramMb} MB")
                need.freeMb > device.availRamMb -> no(RejectReason.RAM, "needs ${need.freeMb} MB free, ${device.availRamMb} MB free")
                !onDisk(v) -> no(RejectReason.DISK, "needs ${v.sizeBytes shr 20} MB on disk, ${(store.freeBytes() ?: 0L) shr 20} MB free")
                caps.none { (_, c) -> v.format in c.formats } -> no(RejectReason.NO_BACKEND, "no backend here reads ${v.format}")
                caps.none { (_, c) -> v.format in c.formats && c.loads(v.arch) } -> no(RejectReason.ARCHITECTURE, "architecture ${v.arch} needs a newer engine")
                planned.none { it.variant == v } && lowBit(v.quant) -> no(RejectReason.LOW_BIT, "1- or 2-bit, used only when a recipe names it")
                else -> caps.forEach { (b, c) ->
                    c.accels.filter { c.supports(it, v.format) }.forEach { accel ->
                        if (failures.isBlocked(key(device, Candidate(b, accel, v, Source.DEFAULT)))) {
                            rejected += Rejection(v.quant, v.format, RejectReason.CRASHED, "crashed the engine before", b.id, accel)
                        }
                    }
                }
            }
        }
        return RouteExplanation(planned, rejected)
    }

    private fun offPhone(model: String, why: RouteExplanation, tried: List<Failure>, device: DeviceProfile, reach: Set<Location>?): String {
        val failed = tried.firstOrNull { it.candidate.location == Location.DEVICE }
        return when {
            failed != null -> with(failed.candidate) {
                "on the phone, ${backend.id} ${accel.name.lowercase()} ${variant.quant} failed: ${failed.error.message ?: failed.error::class.simpleName}"
            }
            reach != null && Location.DEVICE !in reach -> "this call may not run on the phone"
            why.candidates.none { it.location == Location.DEVICE } ->
                smallest(model, why.rejected)?.let { "no file fits the phone: ${it.quant} ${it.detail}" } ?: "no file of this model runs on the phone"
            policy == RoutingPolicy.REMOTE_FIRST -> "the routing policy puts servers first"
            Thermal.hot(device.thermal) -> "the phone is hot (${device.thermal.name.lowercase()}), a LAN server spares it"
            else -> "the phone was not tried"
        }
    }

    // The smallest file left out says more than a bf16 one; a 1- or 2-bit file only when no other was left out.
    private fun smallest(model: String, rejected: List<Rejection>): Rejection? {
        val size = catalog.variants(model).associate { (it.quant to it.format) to it.sizeBytes }
        return rejected.filter { !lowBit(it.quant) }.ifEmpty { rejected }
            .minByOrNull { size[it.quant to it.format] ?: Long.MAX_VALUE }
    }

    // Candidates off the phone, LAN before cloud: the phone's RAM and disk do not limit them, the network does.
    private fun remote(model: String, device: DeviceProfile, minContext: Int?, caps: Map<DenBackend, Capabilities?>): List<Candidate> {
        if (policy == RoutingPolicy.LOCAL_ONLY || !online()) return emptyList()
        val off = backends.filter { (it.about?.location ?: Location.DEVICE) != Location.DEVICE }
        if (off.isEmpty()) return emptyList()
        val variants = catalog.variants(model).filter { (it.soc == null || it.soc == device.soc) && fitsContext(it, minContext) && licenceOk(it) }
            .sortedWith(compareBy<Variant> { quantRank(it.quant) }.thenByDescending { it.sizeBytes })
        return off.sortedBy { it.about!!.location }.flatMap { b ->
            val c = caps[b] ?: return@flatMap emptyList()
            variants.flatMap { v -> c.accels.filter { c.supports(it, v.format) && c.loads(v.arch) }.map { Candidate(b, it, v, Source.DEFAULT) } }
        }.filterNot { failures.isBlocked(key(device, it)) }
    }

    /**
     * Loads the first candidate that works, with a context of [contextLength] tokens, or less down to [minContext]
     * for a file whose maximum is lower.
     */
    suspend fun open(
        model: String,
        device: DeviceProfile,
        contextLength: Int = 4096,
        mmap: Boolean = true,
        minContext: Int = contextLength,
        sequences: Int = 1,
        reach: Set<Location>? = null,
    ): Opened {
        catalog.resolve(model)
        val tried = mutableListOf<Failure>()
        val why = explain(model, device, minContext, mmap, reach)
        var plan = why.candidates
        if (plan.isEmpty()) throw NoUsableBackendException(model, tried, emptyPlanReason(model, device, minContext, mmap), why.rejected)
        val first = plan.first()
        if (firstRun != null && first.source == Source.DEFAULT && first.location == Location.DEVICE && first.variant !in measured) {
            measured = measured + first.variant
            try {
                firstRun.measure(device, plan) { c, e -> failures.record(key(device, c), e) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The default order still works.
            }
            plan = plan(model, device, minContext, mmap, reach).ifEmpty { plan }
        }
        var noDownloads = false
        for (c in plan) {
            val onPhone = c.location == Location.DEVICE
            if (noDownloads && onPhone && !store.has(c.variant)) continue
            // Free RAM changes while files download and other apps run: checked again for each candidate.
            val free = freeRamMb?.invoke() ?: device.availRamMb
            val context = if (onPhone) contextFor(c.variant, contextLength, minContext, device, mmap, free)
                else minOf(contextLength, c.variant.maxContext ?: contextLength)
            if (context == null) {
                tried += Failure(c, NotEnoughMemoryException("${c.variant.quant} needs ${ramNeed(c.variant, minContext, mmap).freeMb} MB free, $free MB free now"))
                continue
            }
            try {
                // A remote backend has the file itself: nothing is downloaded to the phone.
                val artifact = if (!onPhone) Artifact(c.variant, "") else try {
                    store.fetch(c.variant)
                } catch (e: IOException) {
                    // Without network, or when the download is refused, every other download fails the same way: only
                    // files on disk and servers off the phone are left to try.
                    if (e.isNetwork() || e is ai.fanak.den.core.model.DownloadRefusedException) {
                        tried += Failure(c, e)
                        noDownloads = true
                        continue
                    }
                    throw e
                }
                // Each sequence has its own KV cache; one when free RAM has no room for more.
                val seqs = if (sequences > 1 && onPhone && !fits(c.variant, context * sequences, device, mmap, free)) 1 else sequences
                val session = try {
                    c.backend.load(artifact, LoadOptions(c.accel, context, mmap = mmap, sequences = seqs))
                } catch (e: EngineLostException) {
                    // The engine did not start or the system stopped it: other files would only be downloaded in vain.
                    if (e !is EngineCrashedException) throw NoUsableBackendException(model, tried + Failure(c, e))
                    throw e
                }
                return Opened(session, c, tried, context, why.rejected, if (onPhone) null else offPhone(model, why, tried, device, reach))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoUsableBackendException) {
                throw e
            } catch (e: Throwable) {
                tried += Failure(c, e)
                failures.record(key(device, c), e)
            }
        }
        throw NoUsableBackendException(model, tried, rejected = why.rejected)
    }

    private fun emptyPlanReason(model: String, device: DeviceProfile, minContext: Int?, mmap: Boolean): String {
        val all = catalog.variants(model).filter { it.soc == null || it.soc == device.soc }
        val allowed = all.filter { licenceOk(it) }
        val longEnough = allowed.filter { fitsContext(it, minContext) }
        val ctx = minContext ?: 0
        val inRam = longEnough.filter { fits(it, ctx, device, mmap, device.availRamMb) }
        val fits = inRam.filter { onDisk(it) }
        val caps = backends.mapNotNull { runCatching { it.probe(device) }.getOrNull() }
        val unknownArch = all.filter { v ->
            caps.any { v.format in it.formats } && caps.none { v.format in it.formats && it.loads(v.arch) }
        }
        return when {
            all.isEmpty() -> "unknown model $model: no catalog has a file for it"
            allowed.isEmpty() ->
                "this app does not allow the licence of $model (${all.map { catalog.remote(it)?.licence ?: "none given" }.distinct().joinToString()})"
            longEnough.isEmpty() ->
                "no file of $model supports a context of $minContext tokens (the longest has ${allowed.mapNotNull { it.maxContext }.max()})"
            unknownArch.size == all.size ->
                "$model needs a newer engine (architecture ${unknownArch.mapNotNull { it.arch }.distinct().joinToString()})"
            inRam.isEmpty() -> longEnough.minBy { ramNeed(it, ctx, mmap).freeMb }.let { v ->
                val need = ramNeed(v, ctx, mmap)
                if (need.totalMb > device.ramMb) "no file of $model fits in this phone's ${device.ramMb} MB of RAM: the smallest needs ${need.totalMb} MB"
                else "no file of $model fits in free RAM: ${device.availRamMb} MB free, the smallest needs ${need.freeMb} MB with $ctx tokens of context"
            }
            fits.isEmpty() ->
                "no file of $model fits on disk: ${(store.freeBytes() ?: 0L) shr 20} MB free, the smallest needs ${inRam.minOf { it.sizeBytes } shr 20} MB"
            fits.all { lowBit(it.quant) } -> "only 1- or 2-bit files of $model fit in free RAM (${device.availRamMb} MB); they are not used without a recipe"
            else -> "no backend on this device runs $model (${fits.map { it.format }.distinct().joinToString()})"
        }
    }

    private fun fitsContext(v: Variant, minContext: Int?) = minContext == null || v.maxContext == null || v.maxContext >= minContext

    private fun licenceOk(v: Variant) = allowLicence(catalog.remote(v)?.licence)

    private fun fits(v: Variant, context: Int, device: DeviceProfile, mmap: Boolean, free: Int) =
        ramNeed(v, context, mmap).let { it.freeMb <= free && it.totalMb <= device.ramMb }

    // On disk already, or room to download it.
    private fun onDisk(v: Variant) = store.has(v) || store.freeBytes()?.let { v.sizeBytes <= it } ?: true

    // The longest context up to [contextLength] (and the file's maximum) that fits in [free] MB, halving down to
    // [minContext]; null when not even that fits.
    private fun contextFor(v: Variant, contextLength: Int, minContext: Int, device: DeviceProfile, mmap: Boolean, free: Int): Int? {
        var c = minOf(contextLength, v.maxContext ?: contextLength)
        while (true) {
            if (fits(v, c, device, mmap, free)) return c
            if (c <= minContext) return null
            c = maxOf(minContext, c / 2)
        }
    }

    // For crashes after load, e.g. during generation; the next open() skips this candidate.
    fun reportFailure(device: DeviceProfile, candidate: Candidate, error: Throwable) =
        failures.record(key(device, candidate), error)

    private fun key(device: DeviceProfile, c: Candidate) = FailureKey(
        device.soc, c.backend.id, c.accel, c.variant.model, c.variant.format, c.variant.quant, c.backend.version,
    )

    // RAM a file needs with a context: free RAM for what cannot be paged out, and in total with the weights.
    data class RamNeed(val freeMb: Int, val totalMb: Int)

    companion object {
        /**
         * Memory a load takes beside the weights and the KV cache (compute buffers, runtime): den bench peak RSS minus
         * the weights and the KV cache of Qwen3-0.6B Q4_0 at 4096 tokens, 2026-11-21: A52s 389, ZTE 403, S25 516 MB.
         */
        const val OVERHEAD_MB = 520

        /**
         * With the KV cache size known, free RAM must hold the KV cache and the overhead; weights read through mmap are
         * file pages the system can drop again, so they count only without mmap (the A52s runs qwen3-0.6b with 1155 MB
         * free and a peak RSS of 1201 MB). Without the size, the catalog's rough minRamMb.
         */
        fun ramNeed(v: Variant, context: Int, mmap: Boolean = true): RamNeed {
            val kv = v.kvBytesPerToken ?: return RamNeed(v.minRamMb, v.minRamMb)
            val weights = (v.sizeBytes shr 20).toInt()
            val anon = ((kv * context) shr 20).toInt() + OVERHEAD_MB
            return RamNeed(anon + if (mmap) 0 else weights, anon + weights)
        }

        // Without a recipe: the usual 4-bit quants first (quality per byte on a phone), then larger ones,
        // 3-bit last but one, F16 and BF16 last. Size breaks ties within a rank.
        private val PREFERRED = listOf("q4_k_m", "q4_0", "q4_k_s", "iq4_xs", "iq4_nl", "q5_k_m", "q5_k_s", "q4_1", "q6_k", "q8_0")

        // 1- and 2-bit files lose too much quality to be picked without a recipe that measured them.
        private fun lowBit(quant: String) = quant.lowercase().removePrefix("ud-").matches(Regex("i?q[12](_.*)?"))

        private fun quantRank(quant: String): Int {
            val q = quant.lowercase().removePrefix("ud-")
            PREFERRED.indexOf(q).let { if (it >= 0) return it }
            return when {
                q == "f16" || q == "bf16" || q == "f32" -> PREFERRED.size + 2
                q.matches(Regex("i?q3.*")) -> PREFERRED.size + 1
                else -> PREFERRED.size
            }
        }
    }
}

// Not enough free RAM now for a candidate, even with the shortest context the app accepts.
class NotEnoughMemoryException(message: String) : Exception(message)

private fun IOException.isNetwork() = this is ai.fanak.den.core.model.OfflineException || isNetworkError()

// No host, no connection or a timeout: per platform, since each names these errors its own way.
internal expect fun Throwable.isNetworkError(): Boolean
