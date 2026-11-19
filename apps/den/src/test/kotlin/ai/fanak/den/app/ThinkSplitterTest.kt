package ai.fanak.den.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkSplitterTest {
    private fun run(vararg pieces: String): Pair<String, String> {
        val s = ThinkSplitter()
        val r = StringBuilder()
        val c = StringBuilder()
        (pieces.map { s.feed(it) } + s.flush()).forEach { r.append(it.first); c.append(it.second) }
        return r.toString() to c.toString()
    }

    @Test
    fun emptyThinkBlockIsDropped() = assertEquals("" to "Hello!", run("<think>", "\n\n", "</think>", "\n\n", "Hello!"))

    @Test
    fun reasoningIsSeparated() = assertEquals("Count r. Two.\n" to "Two.", run("<thi", "nk>\nCount r. ", "Two.\n</th", "ink>\n", "Two."))

    @Test
    fun noThinkBlockIsUnchanged() = assertEquals("" to "Hi <think> there", run("Hi ", "<think> there"))

    @Test
    fun unclosedThinkIsReasoning() = assertEquals("still thinking" to "", run("<think>still ", "thinking"))
}
