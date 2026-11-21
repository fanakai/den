package ai.fanak.den.backend.llamacpp

import ai.fanak.den.core.backend.SlotPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

// The descriptor is complete without the engine; the version comes from the engine at run time.
class DescriptorTest {
    @Test
    fun everyFieldButTheVersionIsSet() {
        val about = LlamaCppBackend().about!!
        assertEquals(listOf("version"), about.missing())
        assertEquals("llamacpp", about.id)
        assertEquals(SlotPolicy.STANDARD, about.slots)
    }

    @Test
    fun aMissingFieldIsReported() {
        val about = LlamaCppBackend().about!!.copy(version = "1", licence = "", url = "http://x")
        assertEquals(listOf("licence", "url"), about.missing())
    }
}
