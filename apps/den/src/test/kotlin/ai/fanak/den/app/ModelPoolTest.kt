package ai.fanak.den.app

import ai.fanak.den.DenModel
import ai.fanak.den.core.backend.GenEvent
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.router.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class ModelPoolTest {
    // A phone with 2000 MB free; a loaded model takes its size from the free RAM.
    private val sizes = mapOf("small" to 700, "big" to 1500)
    private var free = 2000
    private var clock = 0L
    private val loads = mutableListOf<String>()

    // A load waits on its gate when one is set, like a download in progress.
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    private inner class Fake(override val name: String) : DenModel() {
        override val backend = "fake"
        override val accel = Accel.CPU
        override val quant = "q4_0"
        override val source = Source.DEFAULT
        override val connected = false
        override fun chat(request: GenRequest): Flow<GenEvent> = emptyFlow()
        override fun close() {
            free += sizes.getValue(name)
        }
    }

    private val pool = ModelPool(
        load = { name ->
            gates[name]?.await()
            check(free >= sizes.getValue(name)) { "out of memory loading $name" }
            loads += name
            free -= sizes.getValue(name)
            Fake(name)
        },
        need = { name, avail -> sizes.getValue(name).takeIf { it <= avail } },
        availMb = { free },
        now = { clock },
        log = {},
    )

    @Test
    fun sharedModelLoadsOnce() = runBlocking {
        pool.acquire("small")
        pool.acquire("small")
        assertEquals(listOf("small"), loads)
        assertEquals(2, pool.sessions("small"))
    }

    @Test
    fun lastSessionUnloads() = runBlocking {
        pool.acquire("small")
        pool.release("small")
        assertEquals(emptyList(), pool.loadedNames())
        assertEquals(2000, free)
    }

    @Test
    fun idleModelMakesRoomForAnother() = runBlocking {
        pool.acquire("small")
        clock = 1_000
        pool.use("big") {}
        assertEquals(listOf("big"), pool.loadedNames())
        // The session on the unloaded model loads it again on next use, making room in turn.
        clock = 2_000
        pool.use("small") {}
        assertEquals(listOf("small", "big", "small"), loads)
        assertEquals(listOf("small"), pool.loadedNames())
    }

    @Test
    fun busyModelIsNotUnloaded() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val gen = async { pool.use("small") { started.complete(Unit); finish.await() } }
        started.await()
        val failed = runCatching { pool.use("big") {} }.isFailure
        finish.complete(Unit)
        gen.await()
        assertTrue(failed)
        assertEquals(listOf("small"), pool.loadedNames())
    }

    @Test
    fun idleTimeoutUnloadsButKeepsSessions() = runBlocking {
        pool.acquire("small")
        clock = 60_000
        pool.unloadIdle(idleMs = 300_000)
        assertEquals(listOf("small"), pool.loadedNames())
        clock = 300_000
        pool.unloadIdle(idleMs = 300_000)
        assertEquals(emptyList(), pool.loadedNames())
        assertEquals(1, pool.sessions("small"))
    }

    @Test
    fun loadDoesNotBlockLoadedModels() = runBlocking {
        pool.use("small") {}
        val gate = CompletableDeferred<Unit>().also { gates["big"] = it }
        free = 4000
        val loading = async { pool.use("big") {} }
        yield()
        var used = false
        pool.use("small") { used = true }
        assertTrue(used)
        assertTrue(!loading.isCompleted)
        gate.complete(Unit)
        loading.await()
        assertEquals(listOf("small", "big"), pool.loadedNames())
    }

    @Test
    fun cancelledLoadLeavesNothing() = runBlocking {
        gates["big"] = CompletableDeferred()
        val loading = async { pool.use("big") {} }
        yield()
        loading.cancel()
        runCatching { loading.await() }
        assertEquals(emptyList(), pool.loadedNames())
        assertEquals(0, pool.sessions("big"))
    }

    @Test
    fun trimUnloadsIdleModels() = runBlocking {
        pool.use("small") {}
        pool.trim()
        assertEquals(emptyList(), pool.loadedNames())
    }

    @Test
    fun anExclusiveAcceleratorHoldsOneModel() = runBlocking {
        // Both fit in RAM, but "npu-a" and "npu-b" run on an NPU that holds one model; "cpu" does not share it.
        val npu = setOf("npu-a", "npu-b")
        val held = mutableListOf<String>()
        val p = ModelPool(
            load = { name -> loads += name; held += name; object : DenModel() {
                override val name = name
                override val backend = "qnn"
                override val accel = if (name in npu) Accel.NPU else Accel.CPU
                override val quant = "int4"
                override val source = Source.RECIPE_SOC
                override val connected = false
                override fun chat(request: GenRequest): Flow<GenEvent> = emptyFlow()
                override fun close() { held -= name }
            } },
            need = { _, _ -> 100 },
            availMb = { 8000 },
            now = { clock++ },
            log = {},
            exclusive = { name -> if (name in npu) "qnn NPU" else null },
        )
        p.use("npu-a") {}
        p.use("cpu") {}
        p.use("npu-b") {}
        assertEquals(listOf("cpu", "npu-b"), held)
        p.use("npu-a") {}
        assertEquals(listOf("cpu", "npu-a"), held)
    }

    // A pool of 3000 MB free where each model takes 1000 MB, with load times and pins set per test.
    private class Bench(val pins: MutableSet<String> = mutableSetOf(), val loadMs: Map<String, Long> = emptyMap()) {
        var free = 3000
        var clock = 0L
        val held = mutableListOf<String>()
        val pool = ModelPool(
            load = { name ->
                clock += loadMs[name] ?: 0
                check(free >= 1000) { "out of memory loading $name" }
                free -= 1000
                held += name
                object : DenModel() {
                    override val name = name
                    override val backend = "fake"
                    override val accel = Accel.CPU
                    override val quant = "q4_0"
                    override val source = Source.DEFAULT
                    override val connected = false
                    override fun chat(request: GenRequest): Flow<GenEvent> = emptyFlow()
                    override fun close() { free += 1000; held -= name }
                }
            },
            need = { _, avail -> 500.takeIf { it <= avail } },
            availMb = { free },
            now = { clock },
            log = {},
            marginMb = 0,
            pinned = { it in pins },
        )
    }

    @Test
    fun aPinnedModelStaysForRoomIdleAndSessionEnd() = runBlocking {
        val b = Bench(pins = mutableSetOf("a"))
        b.pool.acquire("a")
        b.pool.release("a")
        b.clock += 3_600_000
        b.pool.unloadIdle(60_000)
        b.pool.use("b") {}
        b.pool.use("c") {}
        // Room for "d": "b" goes (not pinned), "a" stays.
        b.pool.use("d") {}
        assertTrue("a" in b.held && "b" !in b.held, "${b.held}")
        b.pool.trim()
        assertEquals(listOf("a"), b.held)
        b.pool.trim(critical = true)
        assertEquals(emptyList(), b.held)
    }

    @Test
    fun aPoolFullOfPinnedModelsSaysSo() = runBlocking {
        val b = Bench(pins = mutableSetOf("a", "b", "c"))
        listOf("a", "b", "c").forEach { b.pool.use(it) {} }
        val e = runCatching { b.pool.use("d") {} }.exceptionOrNull()
        assertEquals("not enough free RAM for d: a, b, c kept loaded; turn off Keep loaded for one on the Den screen", e?.message)
        assertEquals(listOf("a", "b", "c"), b.held)
    }

    @Test
    fun theCheapestModelToReloadGoesFirst() = runBlocking {
        // About the same idle time; "slow" took 14 s to load, "other" 20 s, "quick" 6 s: "quick" is cheapest to get back.
        val b = Bench(loadMs = mapOf("slow" to 14_000L, "quick" to 6_000L, "other" to 20_000L))
        b.pool.use("slow") {}
        b.pool.use("quick") {}
        b.pool.use("other") {}
        b.clock += 60_000
        b.pool.use("new") {}
        assertEquals(listOf("slow", "other", "new"), b.held)
    }
}