package ai.fanak.den

import ai.fanak.den.core.model.ManifestEntry
import ai.fanak.den.core.model.ModelFormat
import ai.fanak.den.core.model.ModelManifest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelFilesTest {
    private val dir = createTempDirectory("den-files").toFile()

    private fun write(path: String, bytes: Int) = File(dir, path).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(bytes))

    private fun record(model: String, quant: String, path: String, size: Long, complete: Boolean) =
        ModelManifest(dir).put(ManifestEntry(model, ModelFormat.GGUF, quant, path, "00", size, "https://x/f", complete))

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun listsFromTheManifestOnly() {
        write("hf/a/r/one.gguf", 100)
        write("hf/b/r/two.gguf.part", 40)
        write("notes.txt", 5)
        record("one", "q4_0", "hf/a/r/one.gguf", 100, complete = true)
        record("two", "q4_0", "hf/b/r/two.gguf", 400, complete = false)
        val list = ModelFiles(dir).list().associateBy { it.fileName }
        assertEquals(setOf("one.gguf", "two.gguf"), list.keys)
        assertTrue(list.getValue("one.gguf").complete)
        assertEquals(40 to 400L, list.getValue("two.gguf").let { it.bytes.toInt() to it.totalBytes })
    }

    @Test
    fun adoptsFilesStoredBeforeTheManifest() {
        val e = BuiltinCatalog.entries.first { it.variant.model == "qwen3-0.6b" && it.variant.quant == "q4_0" }
        write(e.fileName, 100)
        val list = ModelFiles(dir).list()
        assertEquals(listOf(e.path), list.map { it.path })
        assertFalse(File(dir, e.fileName).exists())
        assertTrue(File(dir, e.path).isFile)
        // Not the expected size, so not complete: the store checks it before use.
        assertFalse(list.single().complete)
    }

    @Test
    fun deleteRemovesFilesEntriesAndEmptyFolders() {
        write("hf/a/r/one.gguf", 100)
        write("hf/a/r/one.gguf.sha256", 64)
        write("hf/b/r/two.gguf", 200)
        record("one", "q4_0", "hf/a/r/one.gguf", 100, complete = true)
        record("two", "q4_0", "hf/b/r/two.gguf", 200, complete = true)
        val files = ModelFiles(dir)
        assertEquals(164, files.delete("one"))
        assertEquals(listOf("two.gguf"), files.list().map { it.fileName })
        assertFalse(File(dir, "hf/a").exists())
    }

    @Test
    fun deletePartialKeepsDownloadsInProgress() {
        write("hf/a/r/one.gguf.part", 30)
        write("hf/b/r/two.gguf.part", 40)
        record("one", "q4_0", "hf/a/r/one.gguf", 100, complete = false)
        record("two", "q4_0", "hf/b/r/two.gguf", 100, complete = false)
        val files = ModelFiles(dir)
        assertEquals(30, files.deletePartial(keep = setOf("two.gguf")))
        assertEquals(listOf("two.gguf"), files.list().map { it.fileName })
    }
}
