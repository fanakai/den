package ai.fanak.den

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelFilesTest {
    private val dir = createTempDirectory("den-files").toFile()
    private val files = ModelFiles(dir)

    private fun write(name: String, bytes: Int) = File(dir, name).writeBytes(ByteArray(bytes))

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun listsCompleteAndPartialFiles() {
        write("Qwen3-0.6B-Q4_0.gguf", 100)
        write("Qwen3-1.7B-Q4_0.gguf.part", 40)
        write("notes.txt", 5)
        val list = files.list().associateBy { it.fileName }
        assertEquals(setOf("Qwen3-0.6B-Q4_0.gguf", "Qwen3-1.7B-Q4_0.gguf"), list.keys)
        assertTrue(list.getValue("Qwen3-0.6B-Q4_0.gguf").complete)
        assertFalse(list.getValue("Qwen3-1.7B-Q4_0.gguf").complete)
        assertEquals(140, files.usedBytes)
    }

    @Test
    fun deleteRemovesEveryFileOfTheModel() {
        write("Qwen3-0.6B-Q4_0.gguf", 100)
        write("Qwen3-0.6B-Q4_0.gguf.sha256", 64)
        write("Qwen3-0.6B-Q4_K_M.gguf.part", 30)
        write("Qwen3-1.7B-Q4_0.gguf", 200)
        assertEquals(194, files.delete("qwen3-0.6b"))
        assertEquals(listOf("Qwen3-1.7B-Q4_0.gguf"), files.list().map { it.fileName })
    }

    @Test
    fun deletePartialKeepsDownloadsInProgress() {
        write("Qwen3-0.6B-Q4_0.gguf.part", 30)
        write("Qwen3-1.7B-Q4_0.gguf.part", 40)
        assertEquals(30, files.deletePartial(keep = setOf("Qwen3-1.7B-Q4_0.gguf")))
        assertEquals(listOf("Qwen3-1.7B-Q4_0.gguf"), files.list().map { it.fileName })
    }
}
