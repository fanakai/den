package ai.fanak.den.app

import ai.fanak.den.core.backend.GenStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun logLinesThatCouldQuoteARequestAreLeftOut() {
        assertTrue(Diagnostics.safe("11-21 12:11:36.697 10308 10327 I DenService: load qwen3-0.6b: llamacpp CPU q4_0, 625 MB, 8696 ms"))
        assertFalse(Diagnostics.safe("""I DenService: http error: Unexpected JSON token at offset 12. JSON input: {"messages":[{"role":"user","content":"hi"}]}"""))
        assertFalse(Diagnostics.safe("""I DenService: bad body {"content":"secret"}"""))
        // llama.cpp's load progress.
        assertFalse(Diagnostics.safe("11-21 12:11:29.420 10340 10356 W den-llama: ."))
        assertFalse(Diagnostics.safe("11-21 12:11:29.597 10340 10356 W den-llama: "))
    }

    @Test
    fun keepsTheLastRequestsOnly() {
        repeat(60) { i -> Diagnostics.record(Diagnostics.Request(i.toLong(), "m", "llamacpp", "CPU", "q4_0", 100, GenStats(24, 25, 100, 1000), "NONE")) }
        val r = Diagnostics.recent()
        assertEquals(50, r.size)
        assertEquals(59L, r.last().at)
    }
}
