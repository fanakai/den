package ai.fanak.den.backend.llamacpp

import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitecturesTest {
    @Test
    fun generatedFromTheSubmodule() {
        assertTrue(listOf("llama", "qwen3", "gemma3", "phi3").all { it in ARCHITECTURES })
        assertTrue("clip" !in ARCHITECTURES)
    }
}
