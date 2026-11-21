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
    // Gets the file on disk (download) before the load; runs outside both locks, so downloads do not block loads.
    private val prepare: suspend (String) -> Unit = {},
    // RAM the router would need to load this model, given this much free RAM; null when nothing fits.
    private val need: (name: String, availMb: Int) -> Int?,
    private val availMb: () -> Int,
    private val now: () -> Long,
    private val log: (String) -> Unit,
    // Kept free on top of what the router asks for: its RAM figures leave out part of the KV cache and buffers.
    private val marginMb: Int = 512,
    // The accelerator a model would load on when that accelerator holds one model at a time (SlotPolicy.EXCLUSIVE_ACCEL),
    // e.g. "qnn NPU"; null for the others.
    private val exclusive: (name: String) -> String? = { null },
) {
    private class Slot(val name: String) {
        var model: DenModel? = null
        var exclusive: String? = null
        var sessions = 0
        var busy = 0
        var lastUsed = 0L
        var ramMb = 0
    }

    private val slots = mutableMapOf<String, Slot>()

    // Guards the slots; never held during a load, so requests on loaded models go on while another one downloads.
    private val lock = Mutex()

    // One load at a time, so two loads do not both count on the same free RAM.
    private val loading = Mutex()

    // An AIDL session keeps its slot; the model may still be unloaded while the session is idle.
    suspend fun acquire(name: String): DenModel = hold(name) { it.sessions++ }

    suspend fun release(name: String) = lock.withLock {
        val s = slots[name] ?: return@withLock
        if (--s.sessions <= 0 && s.busy == 0) unload(s, "no sessions")
    }

    suspend fun <T> use(name: String, block: suspend (DenModel) -> T): T {
        val m = hold(name) { it.busy++ }
        val s = lock.withLock { slots.getValue(name) }
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

    // Unloads the model so its file can be deleted; false while it generates. Its sessions reload it on next use.
    suspend fun evict(name: String): Boolean = lock.withLock {
        val s = slots[name] ?: return@withLock true
        if (s.busy > 0) return@withLock false
        unload(s, "deleted")
        true
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

    // Loads the model if needed, then marks it held under the lock, so it cannot be unloaded in between.
    private suspend fun hold(name: String, mark: (Slot) -> Unit): DenModel {
        while (true) {
            lock.withLock {
                val s = slots.getOrPut(name) { Slot(name) }
                s.model?.let { m ->
                    mark(s)
                    s.lastUsed = now()
                    return m
                }
            }
            prepare(name)
            loading.withLock {
                val ready = lock.withLock {
                    val s = slots.getOrPut(name) { Slot(name) }
                    if (s.model == null) makeRoom(s)
                    s.model != null
                }
                if (!ready) {
                    val before = availMb()
                    val t0 = now()
                    val m = try {
                        load(name)
                    } catch (e: Throwable) {
                        withContext(NonCancellable) {
                            lock.withLock { slots[name]?.takeIf { it.model == null && it.sessions <= 0 && it.busy == 0 }?.let { slots.remove(name) } }
                        }
                        throw e
                    }
                    lock.withLock {
                        val s = slots.getOrPut(name) { Slot(name) }
                        s.model = m
                        s.exclusive = exclusive(name)
                        s.ramMb = (before - availMb()).coerceAtLeast(0)
                        s.lastUsed = now()
                        log("load $name: ${m.backend} ${m.accel} ${m.quant}, ${s.ramMb} MB, ${now() - t0} ms")
                    }
                }
            }
        }
    }

    // Unloads idle models, least recently used first, until the file the router would pick fits in free RAM.
    private fun makeRoom(s: Slot) {
        var idle = slots.values.filter { it !== s && it.model != null && it.busy == 0 }.sortedBy { it.lastUsed }
        if (idle.isEmpty()) return
        // An accelerator that holds one model at a time: the idle model on it goes first, whatever the free RAM.
        exclusive(s.name)?.let { key ->
            idle.filter { it.exclusive == key }.forEach { unload(it, "${s.name} needs $key") }
            idle = idle.filter { it.model != null }
            if (idle.isEmpty()) return
        }
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
