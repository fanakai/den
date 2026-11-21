package ai.fanak.den.core.model

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubLinksTest {
    @Test
    fun pastedLinksBecomeModelIds() {
        val repo = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF"
        assertEquals("hf:unsloth/Qwen3-0.6B-GGUF", HubLinks.modelId(repo))
        assertEquals("hf:unsloth/Qwen3-0.6B-GGUF", HubLinks.modelId("  $repo/  "))
        assertEquals("hf:unsloth/Qwen3-0.6B-GGUF", HubLinks.modelId("$repo/tree/main"))
        assertEquals("hf:unsloth/Qwen3-0.6B-GGUF:Q4_K_M", HubLinks.modelId("$repo/blob/main/Qwen3-0.6B-Q4_K_M.gguf"))
        assertEquals("hf:unsloth/Qwen3-0.6B-GGUF@abc123:Q4_0", HubLinks.modelId("$repo/resolve/abc123/Qwen3-0.6B-Q4_0.gguf?download=true"))
        assertEquals("hf:bartowski/Qwen_Qwen3-0.6B-GGUF:Q4_0", HubLinks.modelId("hf:bartowski/Qwen_Qwen3-0.6B-GGUF:Q4_0"))
        assertNull(HubLinks.modelId("https://example.com/unsloth/Qwen3-0.6B-GGUF"))
        assertNull(HubLinks.modelId("hf:only-owner"))
    }

    // A repository whose main branch moves from commit "c1" to "c2".
    private var head = "c1"
    private val catalog = HuggingFaceCatalog(
        Files.createTempDirectory("den-hf").toFile(),
        fetch = { url ->
            when {
                "/tree/" in url -> """[{"type":"file","path":"M-Q4_0.gguf","lfs":{"oid":"${"a".repeat(64)}","size":10}},""" +
                    """{"type":"file","path":"M-Q4_K_M.gguf","lfs":{"oid":"${"b".repeat(64)}","size":11}}]"""
                else -> """{"sha":"$head","gated":false,"cardData":{"license":"mit"}}"""
            }
        },
        fetchHead = { ByteArray(0) },
    )

    @Test
    fun aQuantKeepsItsFilesOnly() = runTest {
        assertEquals(listOf("q4_0", "q4_k_m"), catalog.resolve("hf:o/r").map { it.quant })
        assertEquals(listOf("q4_k_m"), catalog.resolve("hf:o/r:Q4_K_M").map { it.quant })
        assertEquals("hf:o/r:Q4_K_M", catalog.variants("hf:o/r:Q4_K_M").single().model)
    }

    @Test
    fun anUpdateCheckFindsANewerCommit() = runTest {
        catalog.resolve("hf:o/r")
        assertNull(catalog.newerCommit("hf:o/r"))
        head = "c2"
        assertEquals("c2", catalog.newerCommit("hf:o/r"))
        assertNull(catalog.newerCommit("hf:o/other"))
    }

    @Test
    fun anUpdateDownloadsFromTheNewCommit() = runTest {
        val v = catalog.resolve("hf:o/r").first()
        assertNull(catalog.update("hf:o/r"))
        head = "c2"
        assertEquals("c2", catalog.update("hf:o/r"))
        assertTrue("/resolve/c2/" in catalog.remote(v)!!.url)
        assertNull(catalog.newerCommit("hf:o/r"))
    }
}
