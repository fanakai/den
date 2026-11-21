package ai.fanak.den.core.router

import ai.fanak.den.core.backend.Capabilities
import ai.fanak.den.core.backend.DenBackend
import ai.fanak.den.core.backend.Health
import ai.fanak.den.core.backend.LoadOptions
import ai.fanak.den.core.backend.Session
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.device.DeviceProfile
import ai.fanak.den.core.model.Artifact
import ai.fanak.den.core.model.ModelCatalog
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelStore
import ai.fanak.den.core.model.Variant
import ai.fanak.den.core.recipe.InMemoryRecipeStore
import ai.fanak.den.core.recipe.Recipe
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
        val crashed: List<Path> = emptyList(),
        val disk: Disk = Disk(),
        val request: Request,
        val expect: Expect,
    )

    @Serializable
    private data class BackendSpec(
        val id: String,
        val accels: Set<Accel>,
        val formats: Set<ModelFormat>,
        val architectures: Set<String>? = null,
    )

    @Serializable
    private data class Path(val backend: String, val accel: Accel, val quant: String)

    // Free disk space, and the quants already on disk.
    @Serializable
    private data class Disk(val freeMb: Long = 100_000, val has: Set<String> = emptySet())

    @Serializable
    private data class Request(val model: String, val minContext: Int? = null, val mmap: Boolean = true)

    // plan: "backend accel quant" in order; rejected: "quant REASON", or "quant REASON backend accel" for a path.
    @Serializable
    private data class Expect(val plan: List<String>, val source: Source? = null, val rejected: List<String> = emptyList())

    private class Fake(private val spec: BackendSpec) : DenBackend {
        override val id = spec.id
        override fun probe(device: DeviceProfile) = Capabilities(spec.accels, spec.formats, spec.architectures)
        override suspend fun load(artifact: Artifact, opts: LoadOptions): Session = error("cases only plan")
        override fun health() = Health.Ok
    }

    private val json = Json { ignoreUnknownKeys = false }
    private val dir = File(System.getProperty("den.routerCases"))

    @Test
    fun everyCaseGivesItsPlan() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no cases in $dir")
        val wrong = files.mapNotNull { f ->
            val case = json.decodeFromString(Case.serializer(), f.readText())
            val got = run(case)
            val want = case.expect.copy(source = case.expect.source?.takeIf { case.expect.plan.isNotEmpty() })
            if (got == want) null else "${f.name} (${case.description}):\n  want $want\n  got  $got"
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
        val router = Router(backends, InMemoryRecipeStore(case.recipes), catalog, store)
        case.crashed.forEach { p ->
            val v = case.variants.first { it.quant == p.quant }
            router.reportFailure(case.device, Candidate(backends.first { it.id == p.backend }, p.accel, v, Source.DEFAULT), EngineCrashedException("crashed"))
        }
        val r = case.request
        val why = router.explain(r.model, case.device, r.minContext, r.mmap)
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
        val reasons = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .flatMap { json.decodeFromString(Case.serializer(), it.readText()).expect.rejected }
            .map { it.split(" ")[1] }.toSet()
        // LICENCE needs the app's rule, which is code: in RouterTest.
        val reachable = RejectReason.entries.map { it.name }.toSet() - setOf("LICENCE")
        assertEquals(reachable, reasons, "a reject reason without a case")
    }
}
