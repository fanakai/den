package ai.fanak.den.app

import ai.fanak.den.DenModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The models loaded in the Den app, shared by all clients. A model stays loaded while it is in use; an idle one is
 * unloaded after a timeout, under memory pressure, or to make room for another model, and loaded again on next use.
 */
internal class ModelPool(
    private val load: suspend (String) -> DenModel,
    // RAM the router would need to load this model, given this much free RAM; null when nothing fits.
    private val need: (name: String, availMb: Int) -> Int?,
    private val availMb: () -> Int,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    // Kept free on top of what the router asks for: its RAM figures leave out part of the KV cache and buffers.
    private val marginMb: Int = 512,
) {
    private class Slot(val name: String) {
        var model: DenModel? = null
        var sessions = 0
        var busy = 0
        var lastUsed = 0L
        var ramMb = 0
    }

    private val slots = mutableMapOf<String, Slot>()
    private val lock = Mutex()

    // An AIDL session keeps its slot; the model may still be unloaded while the session is idle.
    suspend fun acquire(name: String): DenModel = lock.withLock {
        val s = slots.getOrPut(name) { Slot(name) }
        val m = loaded(s)
        s.sessions++
        s.lastUsed = now()
        m
    }

    suspend fun release(name: String) = lock.withLock {
        val s = slots[name] ?: return@withLock
        if (--s.sessions <= 0 && s.busy == 0) unload(s, "no sessions")
    }

    suspend fun <T> use(name: String, block: suspend (DenModel) -> T): T {
        val (s, m) = lock.withLock {
            val s = slots.getOrPut(name) { Slot(name) }
            val m = loaded(s)
            s.busy++
            s to m
        }
        try {
            return block(m)
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    s.busy--
                    s.lastUsed = now()
                }
            }
        }
    }

    suspend fun unloadIdle(idleMs: Long) = lock.withLock {
        slots.values.filter { it.model != null && it.busy == 0 && now() - it.lastUsed >= idleMs }
            .forEach { unload(it, "idle") }
    }

    suspend fun trim() = lock.withLock {
        slots.values.filter { it.model != null && it.busy == 0 }.forEach { unload(it, "low memory") }
    }

    suspend fun loadedNames(): List<String> = lock.withLock { slots.values.filter { it.model != null }.map { it.name } }

    suspend fun sessions(name: String): Int = lock.withLock { slots[name]?.sessions ?: 0 }

    // On destroy, when no call can come any more.
    fun closeAll() {
        slots.values.forEach { it.model?.close() }
        slots.clear()
    }

    private suspend fun loaded(s: Slot): DenModel {
        s.model?.let { return it }
        makeRoom(s)
        val before = availMb()
        val t0 = now()
        val m = load(s.name)
        s.model = m
        s.ramMb = (before - availMb()).coerceAtLeast(0)
        s.lastUsed = now()
        log("load ${s.name}: ${m.backend} ${m.accel} ${m.quant}, ${s.ramMb} MB, ${now() - t0} ms")
        return m
    }

    // Unloads idle models, least recently used first, until the file the router would pick fits in free RAM.
    private fun makeRoom(s: Slot) {
        val idle = slots.values.filter { it !== s && it.model != null && it.busy == 0 }.sortedBy { it.lastUsed }
        if (idle.isEmpty()) return
        var avail = availMb()
        val required = (need(s.name, avail + idle.sumOf { it.ramMb }) ?: return) + marginMb
        for (o in idle) {
            if (avail >= required) break
            unload(o, "room for ${s.name}")
            avail += o.ramMb
        }
    }

    private fun unload(s: Slot, why: String) {
        val m = s.model
        if (m != null) {
            m.close()
            s.model = null
            log("unload ${s.name} ($why), ${s.ramMb} MB")
        }
        if (s.sessions <= 0 && s.busy == 0) slots.remove(s.name)
    }
}
