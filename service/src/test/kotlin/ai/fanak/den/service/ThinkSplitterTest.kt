package ai.fanak.den.service

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

    // The model's pieces can end anywhere, inside a tag too: every split must give the same result as one piece.
    @Test
    fun anySplitGivesTheSameResult() {
        val texts = listOf(
            "<think>\nCount the r's. Three.\n</think>\n\nThere are three.",
            "  <think></think>Hi <think> there",
            "No reasoning at all, just text with < and > signs.",
            "<think>never closed",
        )
        val rnd = kotlin.random.Random(42)
        for (text in texts) {
            val expected = run(text)
            repeat(300) {
                val cuts = (0..rnd.nextInt(1, 8)).map { rnd.nextInt(0, text.length + 1) }.sorted()
                val pieces = (listOf(0) + cuts + text.length).zipWithNext { a, b -> text.substring(a, b) }
                assertEquals(expected, run(*pieces.toTypedArray()), "split $pieces")
            }
        }
    }
}
