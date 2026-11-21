package ai.fanak.den.core.model

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OllamaCatalogTest {
    private val cache = Files.createTempDirectory("den-ollama").toFile()
    private val model = "7f4030143c1c477224c5434f8272c662a8b042079a0a584f0a27a1684fe2e1fa"
    private var online = true
    private val requests = mutableListOf<String>()

    // The manifest and config of qwen3:0.6b as the registry gave them on 2026-11-21 (template and params layers cut).
    private val catalog = OllamaCatalog(
        cache,
        fetch = { url ->
            requests += url
            if (!online) throw IOException("offline")
            when {
                url.endsWith("/manifests/0.6b") -> """{"schemaVersion":2,"config":{"digest":"sha256:b0830f"},"layers":[""" +
                    """{"mediaType":"application/vnd.ollama.image.model","digest":"sha256:$model","size":522640096},""" +
                    """{"mediaType":"application/vnd.ollama.image.license","digest":"sha256:d18a5c","size":11338}]}"""
                url.endsWith("/blobs/sha256:b0830f") ->
                    """{"model_format":"gguf","model_family":"qwen3","model_type":"751.63M","file_type":"Q4_K_M"}"""
                else -> throw IOException("HTTP 404 for $url")
            }
        },
        fetchHead = { qwen3Gguf() },
    )

    @Test
    fun aTagGivesItsGgufLayerWithSizeAndDigest() = runTest {
        val v = catalog.resolve("ollama:qwen3:0.6b").single()
        assertEquals("q4_k_m" to 522_640_096L, v.quant to v.sizeBytes)
        assertEquals("qwen3" to 751_630_000L, v.arch to v.params)
        assertEquals(40960 to 114_688L, v.maxContext to v.kvBytesPerToken)
        val r = catalog.remote(v)!!
        assertEquals(model, r.sha256)
        assertEquals("https://registry.ollama.ai/v2/library/qwen3/blobs/sha256:$model", r.url)
        assertEquals("ollama/library/qwen3/0.6b/qwen3-0.6b.gguf", r.path)

        // Cached: a new catalog resolves it offline.
        online = false
        val again = OllamaCatalog(cache, fetch = { throw IOException("offline") }, fetchHead = { ByteArray(0) })
        assertEquals(v, again.resolve("ollama:qwen3:0.6b").single())
    }

    @Test
    fun namesAndSizes() = runTest {
        assertEquals(emptyList(), catalog.resolve("hf:qwen3"))
        assertFailsWith<IOException> { catalog.resolve("ollama:someone/model:tag") }
        assertEquals("https://registry.ollama.ai/v2/someone/model/manifests/tag", requests.last())
        assertEquals(1_700_000_000L, OllamaCatalog.params("1.7B"))
        assertEquals(null, OllamaCatalog.params("big"))
    }
}
