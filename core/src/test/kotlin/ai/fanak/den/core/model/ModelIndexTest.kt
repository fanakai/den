package ai.fanak.den.core.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ModelIndexTest {
    private val source = object : ModelCatalog {
        val v = Variant("hf:o/r", ModelFormat.GGUF, "q4_0", 100, 600)
        override suspend fun resolve(id: String) = if (id == "hf:o/r") listOf(v) else emptyList()
        override fun variants(model: String) = emptyList<Variant>()
        override fun remote(variant: Variant) = if (variant == v) RemoteFile("f.gguf", "https://x/f", "00", 100, "hf/o/r/f.gguf") else null
    }
    private val index = IndexCatalog(listOf(IndexEntry("small", "hf:o/r", "apache-2.0", aliases = listOf("tiny"))), source)

    @Test
    fun shortNameResolvesThroughItsSource() = runTest {
        val v = index.resolve("small").single()
        assertEquals("small" to "q4_0", v.model to v.quant)
        assertEquals("hf/o/r/f.gguf", index.remote(v)?.path)
        assertEquals(listOf(v), index.variants("small"))
        assertEquals(listOf(v), index.resolve("tiny"))
    }

    @Test
    fun rejectsBadEntries() {
        assertFailsWith<IllegalArgumentException> { ModelIndex.parse("name: Small\nsource: hf:o/r\nlicence: mit") }
        assertFailsWith<IllegalArgumentException> { ModelIndex.parse("name: small\nsource: o/r\nlicence: mit") }
        assertFailsWith<IllegalArgumentException> { ModelIndex.parse("name: small\nsource: hf:o/r\nlicence: mit\nsize: 3") }
    }

    @Test
    fun repositoryIndexParses() {
        val dir = File(System.getProperty("den.models"))
        val files = dir.listFiles { f -> f.name.endsWith(".yaml") }!!
        assertTrue(files.isNotEmpty())
        // Each file is named after its entry.
        files.forEach { f -> assertEquals(f.name, ModelIndex.parse(f.readText(), f.name).name + ".yaml") }
    }
}
