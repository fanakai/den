package ai.fanak.den.core.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelAliasesTest {
    private val aliases = ModelAliases(mapOf("den.default" to listOf("big", "small"), "den.small" to listOf("small")))

    @Test
    fun anAliasTakesTheFirstModelThatRuns() = runTest {
        assertEquals("big", aliases.resolve("den.default") { true })
        // A phone where "big" does not fit.
        assertEquals("small", aliases.resolve("den.default") { it == "small" })
        // Nothing runs: the first one, so the error names a real model.
        assertEquals("big", aliases.resolve("den.default") { false })
        assertEquals("qwen3-0.6b", aliases.resolve("qwen3-0.6b") { true })
    }

    @Test
    fun theUserCanMoveAnAlias() = runTest {
        aliases.set("den.default", "other")
        assertEquals("other", aliases.resolve("den.default") { true })
        aliases.set("den.default", null)
        assertEquals("big", aliases.resolve("den.default") { true })
        aliases.set("den.chat", "small")
        assertEquals(setOf("den.default", "den.small", "den.chat"), aliases.names)
        assertFailsWith<IllegalArgumentException> { aliases.set("default", "small") }
    }
}
