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
    // Models the user keeps loaded: never unloaded for room, when idle or without sessions; only by critical memory.
    private val pinned: (name: String) -> Boolean = { false },
) {
    private class Slot(val name: String) {
        var model: DenModel? = null
        var exclusive: String? = null
        var sessions = 0
        var busy = 0
        var lastUsed = 0L
        var ramMb = 0
        var loadMs = 0L
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
        if (--s.sessions <= 0 && s.busy == 0 && !pinned(name)) unload(s, "no sessions")
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
        slots.values.filter { it.model != null && it.busy == 0 && now() - it.lastUsed >= idleMs && !pinned(it.name) }
            .forEach { unload(it, "idle") }
    }

    // Unloads the model so its file can be deleted; false while it generates. Its sessions reload it on next use.
    suspend fun evict(name: String): Boolean = lock.withLock {
        val s = slots[name] ?: return@withLock true
        if (s.busy > 0) return@withLock false
        unload(s, "deleted")
        true
    }

    // [critical]: the system is about to kill processes; pinned models go too.
    suspend fun trim(critical: Boolean = false) = lock.withLock {
        slots.values.filter { it.model != null && it.busy == 0 && (critical || !pinned(it.name)) }.forEach { unload(it, "low memory") }
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
                        s.loadMs = now() - t0
                        s.lastUsed = now()
                        log("load $name: ${m.backend} ${m.accel} ${m.quant}, ${s.ramMb} MB, ${now() - t0} ms")
                    }
                }
            }
        }
    }

    /**
     * Unloads idle models until the file the router would pick fits in free RAM: the cheapest to get back first, that
     * is the most idle time per second of reload (a model that took 14 s to load stays longer than one of 6 s). Pinned
     * models stay; when only they are in the way, the load fails and says so.
     */
    private fun makeRoom(s: Slot) {
        val loaded = slots.values.filter { it !== s && it.model != null && it.busy == 0 }
        val held = loaded.filter { pinned(it.name) }
        var idle = loaded.filter { !pinned(it.name) }
            .sortedByDescending { (now() - it.lastUsed).toDouble() / maxOf(it.loadMs, 1) }
        // An accelerator that holds one model at a time: the idle model on it goes first, whatever the free RAM.
        exclusive(s.name)?.let { key ->
            held.firstOrNull { it.exclusive == key }?.let { refuse(s, "${it.name} is kept loaded on $key") }
            idle.filter { it.exclusive == key }.forEach { unload(it, "${s.name} needs $key") }
            idle = idle.filter { it.model != null }
        }
        var avail = availMb()
        val required = need(s.name, avail + idle.sumOf { it.ramMb })?.plus(marginMb)
            ?: if (held.isEmpty()) return else refuse(s, "${held.joinToString { it.name }} kept loaded")
        for (o in idle) {
            if (avail >= required) break
            unload(o, "room for ${s.name}")
            avail += o.ramMb
        }
        if (avail < required && held.isNotEmpty()) refuse(s, "${held.joinToString { it.name }} kept loaded")
    }

    private fun refuse(s: Slot, why: String): Nothing {
        if (s.model == null && s.sessions <= 0 && s.busy == 0) slots.remove(s.name)
        throw IllegalStateException("not enough free RAM for ${s.name}: $why; turn off Keep loaded for one on the Den screen")
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
