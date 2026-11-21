package ai.fanak.den.core.model

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ModelScopeCatalogTest {
    private val cache: File = Files.createTempDirectory("den-ms").toFile()
    private var limited = false
    private var approval = 0

    private fun file(path: String, size: Long, sha: String) =
        """{"Name":"${path.substringAfterLast('/')}","Path":"$path","Size":$size,"Sha256":"$sha","Revision":"c0ffee","Type":"blob","IsLFS":true}"""

    private val files = listOf(
        """{"Name":"README.md","Path":"README.md","Size":10,"Sha256":"","Revision":"c0ffee","Type":"blob"}""",
        file("Qwen3-0.6B-Q4_0.gguf", 382_156_480, "a".repeat(64)),
        file("Qwen3-0.6B-Q4_K_M.gguf", 396_705_472, "b".repeat(64)),
    ).joinToString(",", """{"Code":200,"Data":{"Files":[""", "]}}")

    private fun catalog(cacheDir: File? = cache) = ModelScopeCatalog(cacheDir, fetch = { url ->
        when {
            limited -> """{"Code":10013201001,"Message":"操作过于频繁，请稍后再试!","Success":false}"""
            "/repo/files" in url -> files
            else -> """{"Code":200,"Data":{"License":"apache-2.0","ApprovalMode":$approval}}"""
        }
    })

    @AfterTest
    fun cleanup() {
        cache.deleteRecursively()
    }

    @Test
    fun resolvesFilesPinnedToTheirCommit() = runTest {
        val c = catalog()
        val vs = c.resolve("ms:unsloth/Qwen3-0.6B-GGUF")
        assertEquals(listOf("q4_0", "q4_k_m"), vs.map { it.quant })
        val r = c.remote(vs.first())!!
        assertEquals("https://modelscope.cn/models/unsloth/Qwen3-0.6B-GGUF/resolve/c0ffee/Qwen3-0.6B-Q4_0.gguf", r.url)
        assertEquals("ms/unsloth/Qwen3-0.6B-GGUF/Qwen3-0.6B-Q4_0.gguf", r.path)
        assertEquals(2, catalog().resolve("modelscope:unsloth/Qwen3-0.6B-GGUF").size)
    }

    @Test
    fun rateLimitFallsBackToTheCache() = runTest {
        catalog().resolve("ms:unsloth/Qwen3-0.6B-GGUF")
        limited = true
        assertEquals(2, catalog().resolve("ms:unsloth/Qwen3-0.6B-GGUF").size)
        val e = assertFailsWith<IOException> { catalog(cacheDir = null).resolve("ms:unsloth/Qwen3-0.6B-GGUF") }
        assertTrue(e.message!!.contains("limiting requests"))
    }

    @Test
    fun modelThatNeedsApprovalIsRefused() = runTest {
        approval = 1
        assertFailsWith<GatedModelException> { catalog().resolve("ms:owner/private-model") }
    }
}
