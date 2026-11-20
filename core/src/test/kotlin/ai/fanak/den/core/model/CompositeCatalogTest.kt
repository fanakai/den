package ai.fanak.den.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompositeCatalogTest {
    private fun v(model: String, quant: String) = Variant(model, ModelFormat.GGUF, quant, 100, 100)

    private class Fixed(val variants: List<Variant>, val host: String) : ModelCatalog {
        override val models get() = variants.map { it.model }.distinct()
        override fun variants(model: String) = variants.filter { it.model == model }
        override fun remote(variant: Variant) =
            variant.takeIf { it in variants }?.let { RemoteFile("${it.model}-${it.quant}.gguf", "https://$host/f", "00", 100) }
    }

    private val a = Fixed(listOf(v("qwen", "q4_0"), v("qwen", "q8_0")), "a")
    private val b = Fixed(listOf(v("qwen", "q4_0"), v("gemma", "q4_0")), "b")
    private val both = CompositeCatalog(listOf(a, b))

    @Test
    fun modelsAndVariantsAreMergedInOrder() {
        assertEquals(listOf("qwen", "gemma"), both.models)
        assertEquals(listOf("q4_0", "q8_0"), both.variants("qwen").map { it.quant })
    }

    @Test
    fun firstCatalogServesASharedVariant() {
        assertEquals("https://a/f", both.remote(v("qwen", "q4_0"))?.url)
        assertEquals("https://b/f", both.remote(v("gemma", "q4_0"))?.url)
        assertNull(both.remote(v("llama", "q4_0")))
    }
}
