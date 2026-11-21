package ai.fanak.den.core.router

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.ModelFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileFailureStoreTest {
    private val dir = createTempDirectory().toFile()
    private val file = File(dir, "failures.json")
    private val key = FailureKey("SM7325", "llamacpp", Accel.GPU, "qwen3-1.7b", ModelFormat.GGUF, "q4_0", "b9590")

    @Test
    fun crashIsKeptAcrossRestarts() {
        FileFailureStore(file, "build-1").record(key, EngineCrashedException("died"))
        assertTrue(FileFailureStore(file, "build-1").isBlocked(key))
    }

    @Test
    fun systemUpdateTriesAgain() {
        FileFailureStore(file, "build-1").record(key, EngineCrashedException("died"))
        assertFalse(FileFailureStore(file, "build-2").isBlocked(key))
    }

    @Test
    fun newBackendVersionTriesAgain() {
        val store = FileFailureStore(file, "build-1")
        store.record(key, EngineCrashedException("died"))
        assertFalse(store.isBlocked(key.copy(backendVersion = "b9600")))
    }

    @Test
    fun onlyCrashesAreKept() {
        val store = FileFailureStore(file, "build-1")
        store.record(key, IllegalStateException("out of memory"))
        store.record(key, EngineLostException("stopped by the system"))
        assertFalse(store.isBlocked(key))
        assertFalse(file.exists())
    }

    @Test
    fun brokenFileStartsEmpty() {
        file.writeText("{not json")
        assertFalse(FileFailureStore(file, "build-1").isBlocked(key))
    }
}
