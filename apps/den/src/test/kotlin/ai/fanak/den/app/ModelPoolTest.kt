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

class ModelPoolTest {
    // A phone with 2000 MB free; a loaded model takes its size from the free RAM.
    private val sizes = mapOf("small" to 700, "big" to 1500)
    private var free = 2000
    private var clock = 0L
    private val loads = mutableListOf<String>()

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
    fun trimUnloadsIdleModels() = runBlocking {
        pool.use("small") {}
        pool.trim()
        assertEquals(emptyList(), pool.loadedNames())
    }
}
