package ai.fanak.den.core.router

import ai.fanak.den.core.backend.BackendDescriptor
import ai.fanak.den.core.backend.BackendMode
import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Location
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.backend.SupportTier
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.InMemoryRecipeStore
import ai.fanak.den.core.recipe.Recipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The directory of the cases: a system property on the JVM, an environment variable on native targets.
internal expect fun routerCasesDir(): String

/**
 * The router cases in core/router-cases/, as data: a device, its backends, a catalog, recipes and crashes, and the plan
 * the router must make with what it leaves out. Other implementations of the router are checked against the same files.
 */
class RouterCasesTest {
    @Serializable
    private data class Case(
        val description: String,
        val device: DeviceProfile,
        val backends: List<BackendSpec>,
        val variants: List<Variant>,
        val recipes: List<Recipe> = emptyList(),
        // Paths that crashed on this phone before.
        val crashed: List<CrashedPath> = emptyList(),
        val disk: Disk = Disk(),
        val network: Network = Network(),
        val policy: RoutingPolicy = RoutingPolicy.LOCAL_ONLY,
        val request: Request,
        val expect: Expect,
    )

    // A server (LAN or CLOUD) runs the models of the catalog, and the names in serves that no catalog knows.
    @Serializable
    private data class BackendSpec(
        val id: String,
        val accels: Set<Accel>,
        val formats: Set<ModelFormat>,
        val architectures: Set<String>? = null,
        val location: Location = Location.DEVICE,
        val serves: Set<String> = emptySet(),
    )

    @Serializable
    private data class CrashedPath(val backend: String, val accel: Accel, val quant: String)

    // Free disk space, and the quants already on disk.
    @Serializable
    private data class Disk(val freeMb: Long = 100_000, val has: Set<String> = emptySet())

    // online: any network; localNetwork: Wi-Fi, Ethernet or a VPN, without which a LAN server is out of reach.
    @Serializable
    private data class Network(val online: Boolean = true, val localNetwork: Boolean = true)

    // reach: where this call may run the model; null leaves it to the policy.
    @Serializable
    private data class Request(val model: String, val minContext: Int? = null, val mmap: Boolean = true, val reach: Set<Location>? = null)

    // plan: "backend accel quant" in order; rejected: "quant REASON", or "quant REASON backend accel" for a path.
    @Serializable
    private data class Expect(val plan: List<String>, val source: Source? = null, val rejected: List<String> = emptyList())

    private class Fake(private val spec: BackendSpec) : DenBackend {
        override val id = spec.id
        override val about = BackendDescriptor(
            spec.id, spec.id, "test", "test", "MIT", "https://example.com", SupportTier.EXPERIMENTAL,
            setOf(BackendMode.CHAT), spec.formats, location = spec.location,
        )
        override fun probe(device: DeviceProfile) = Capabilities(spec.accels, spec.formats, spec.architectures)
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session = error("cases only plan")
        override fun health() = Health.Ok
        override fun serves(model: String) = model in spec.serves
    }

    private val json = Json { ignoreUnknownKeys = false }

    private fun cases(): List<Pair<String, Case>> {
        val dir = Path(routerCasesDir())
        return SystemFileSystem.list(dir).filter { it.name.endsWith(".json") }.sortedBy { it.name }.map { p ->
            p.name to json.decodeFromString(Case.serializer(), SystemFileSystem.source(p).buffered().use { it.readString() })
        }
    }

    @Test
    fun everyCaseGivesItsPlan() {
        val all = cases()
        assertTrue(all.isNotEmpty(), "no cases in ${routerCasesDir()}")
        val wrong = all.mapNotNull { (name, case) ->
            val got = run(case)
            val want = case.expect.copy(source = case.expect.source?.takeIf { case.expect.plan.isNotEmpty() })
            if (got == want) null else "$name (${case.description}):\n  want $want\n  got  $got"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun run(case: Case): Expect {
        val backends = case.backends.map { Fake(it) }
        val catalog = object : ModelCatalog {
            override fun variants(model: String) = case.variants.filter { it.model == model }
        }
        val store = object : ModelStore {
            override suspend fun fetch(variant: Variant): Artifact = error("cases only plan")
            override fun has(variant: Variant) = variant.quant in case.disk.has
            override fun freeBytes() = case.disk.freeMb shl 20
        }
        val router = Router(
            backends, InMemoryRecipeStore(case.recipes), catalog, store,
            policy = case.policy, online = { case.network.online }, localNetwork = { case.network.localNetwork },
        )
        case.crashed.forEach { p ->
            val v = case.variants.first { it.quant == p.quant }
            router.reportFailure(case.device, Candidate(backends.first { it.id == p.backend }, p.accel, v, Source.DEFAULT), EngineCrashedException("crashed"))
        }
        val r = case.request
        val why = router.explain(r.model, case.device, r.minContext, r.mmap, r.reach)
        return Expect(
            plan = why.candidates.map { "${it.backend.id} ${it.accel.name.lowercase()} ${it.variant.quant}" },
            source = why.candidates.firstOrNull()?.source,
            rejected = why.rejected.map { j ->
                listOfNotNull(j.quant, j.reason.name, j.backend, j.accel?.name?.lowercase()).joinToString(" ")
            },
        )
    }

    @Test
    fun theCasesCoverEveryReasonTheyCanReach() {
        val reasons = cases().flatMap { it.second.expect.rejected }.map { it.split(" ")[1] }.toSet()
        // LICENCE needs the app's rule, which is code: in RouterTest.
        val reachable = RejectReason.entries.map { it.name }.toSet() - setOf("LICENCE")
        assertEquals(reachable, reasons, "a reject reason without a case")
    }

    @Test
    fun theCasesCoverEveryPolicyAndLocation() {
        val all = cases().map { it.second }
        assertEquals(RoutingPolicy.entries.toSet(), all.map { it.policy }.toSet(), "a routing policy without a case")
        assertEquals(Location.entries.toSet(), all.flatMap { c -> c.backends.map { it.location } }.toSet(), "a location without a case")
    }
}
