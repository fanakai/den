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

class HuggingFaceCatalogTest {
    private val cache: File = Files.createTempDirectory("den-hf").toFile()
    private val requests = mutableListOf<String>()
    private var online = true
    private var gated = "false"

    private fun lfs(path: String, size: Long, sha: String) =
        """{"type":"file","path":"$path","size":$size,"lfs":{"oid":"$sha","size":$size}}"""

    private val tree = listOf(
        """{"type":"file","path":".gitattributes","size":10}""",
        lfs("Qwen3-0.6B-Q4_0.gguf", 382_156_480, "a".repeat(64)),
        lfs("Qwen3-0.6B-Q4_K_M.gguf", 396_705_472, "b".repeat(64)),
        lfs("Qwen3-0.6B-Q2_K.gguf", 296_238_784, "c".repeat(64)),
        lfs("Qwen3-0.6B-Q2_K_L.gguf", 296_238_784, "c".repeat(64)),
        lfs("Qwen3-0.6B-UD-Q4_K_XL.gguf", 405_372_608, "d".repeat(64)),
        lfs("Qwen3-0.6B-BF16.gguf", 1_198_182_848, "e".repeat(64)),
        lfs("mmproj-F16.gguf", 100, "f".repeat(64)),
        lfs("big/Qwen3-0.6B-Q8_0-00001-of-00002.gguf", 100, "0".repeat(64)),
    ).joinToString(",", "[", "]")

    private val catalog = HuggingFaceCatalog(cache, fetch = { url ->
        requests += url
        if (!online) throw IOException("offline")
        when {
            "/tree/" in url -> tree
            else -> """{"sha":"abc123","gated":$gated,"cardData":{"license":"apache-2.0"}}"""
        }
    })

    @AfterTest
    fun cleanup() {
        cache.deleteRecursively()
    }

    @Test
    fun resolvesQuantsFromTheFileTree() = runTest {
        val vs = catalog.resolve("hf:unsloth/Qwen3-0.6B-GGUF")
        assertEquals(listOf("q4_0", "q4_k_m", "q2_k", "ud-q4_k_xl", "bf16"), vs.map { it.quant })
        assertTrue(vs.all { it.model == "hf:unsloth/Qwen3-0.6B-GGUF" })
        val r = catalog.remote(vs.first())!!
        assertEquals("a".repeat(64), r.sha256)
        assertEquals("https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/abc123/Qwen3-0.6B-Q4_0.gguf", r.url)
        assertEquals("hf/unsloth/Qwen3-0.6B-GGUF/Qwen3-0.6B-Q4_0.gguf", r.path)
        assertEquals(vs, catalog.variants("hf:unsloth/Qwen3-0.6B-GGUF"))
    }

    @Test
    fun revisionGoesIntoTheRequestAndThePath() = runTest {
        val v = catalog.resolve("hf:unsloth/Qwen3-0.6B-GGUF@v1").first()
        assertTrue(requests.first().contains("/revision/v1"))
        assertEquals("hf/unsloth/Qwen3-0.6B-GGUF/v1/Qwen3-0.6B-Q4_0.gguf", catalog.remote(v)!!.path)
    }

    @Test
    fun gatedRepositoryIsRefused() = runTest {
        gated = "\"manual\""
        assertFailsWith<GatedModelException> { catalog.resolve("hf:google/gemma-3-1b-it") }
    }

    @Test
    fun otherIdsAreNotResolved() = runTest {
        assertEquals(emptyList(), catalog.resolve("qwen3-0.6b"))
        assertEquals(emptyList(), catalog.resolve("hf:no-repo"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun cachedRepositoryWorksOffline() = runTest {
        catalog.resolve("hf:unsloth/Qwen3-0.6B-GGUF")
        online = false
        val again = HuggingFaceCatalog(cache, fetch = { throw IOException("offline") })
        assertEquals(5, again.resolve("hf:unsloth/Qwen3-0.6B-GGUF").size)
        assertFailsWith<IOException> { again.resolve("hf:unsloth/Other-GGUF") }
    }
}
