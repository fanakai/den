package ai.fanak.den.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerSettingsTest {
    @Test
    fun modelsMapDenNamesToTheServersOrKeepTheServersNames() {
        val server = listOf("Qwen3-8B-Q4_K_M", "gemma-3-12b")
        assertEquals(mapOf("Qwen3-8B-Q4_K_M" to "Qwen3-8B-Q4_K_M", "gemma-3-12b" to "gemma-3-12b"), ServerSettings.parseModels(" \n", server))
        assertEquals(
            mapOf("qwen3-8b" to "Qwen3-8B-Q4_K_M", "hf:unsloth/Qwen3-8B-GGUF" to "Qwen3-8B-Q4_K_M"),
            ServerSettings.parseModels("qwen3-8b = Qwen3-8B-Q4_K_M\n\n hf:unsloth/Qwen3-8B-GGUF=Qwen3-8B-Q4_K_M ", server),
        )
        assertFailsWith<IllegalArgumentException> { ServerSettings.parseModels("qwen3-8b", server) }
        assertFailsWith<IllegalArgumentException> { ServerSettings.parseModels("= x", server) }
    }
}
