package ai.fanak.den.backend.litertlm

import ai.fanak.den.core.backend.ChatMessage
import ai.fanak.den.core.backend.Finish
import ai.fanak.den.core.backend.GenRequest
import ai.fanak.den.core.backend.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartsTest {
    private fun scan(stops: List<String>, vararg pieces: String): Pair<String, Boolean> {
        val s = StopScan(stops)
        val out = StringBuilder()
        pieces.forEach { p -> s.add(p)?.let(out::append) }
        s.rest()?.let(out::append)
        return out.toString() to s.hit
    }

    @Test
    fun withoutStopStringsTheTextPassesAsItComes() {
        val s = StopScan(emptyList())
        assertEquals("Hel", s.add("Hel"))
        assertEquals("lo", s.add("lo"))
        assertNull(s.rest())
    }

    @Test
    fun theReplyEndsBeforeAStopStringSplitOverPieces() {
        assertEquals("Hello " to true, scan(listOf("END"), "Hello E", "N", "D and more"))
        assertEquals("a" to true, scan(listOf("\n\n", "##"), "a#", "#b"))
    }

    @Test
    fun aPrefixThatDoesNotBecomeTheStopStringIsPassedOn() {
        assertEquals("Hello Earth" to false, scan(listOf("END"), "Hello E", "arth"))
        assertEquals("abc E" to false, scan(listOf("END"), "abc E"))
    }

    @Test
    fun nothingPassesAfterTheStopString() {
        val s = StopScan(listOf("."))
        assertEquals("One", s.add("One."))
        assertTrue(s.hit)
        assertNull(s.add(" Two"))
        assertNull(s.rest())
    }

    @Test
    fun turnsSplitTheSystemTheHistoryAndTheLastMessage() {
        val t = Turns.of(listOf(
            ChatMessage(Role.SYSTEM, "Be short."),
            ChatMessage(Role.USER, "Hi"),
            ChatMessage(Role.ASSISTANT, "Hello"),
            ChatMessage(Role.USER, "Name a colour"),
        ))
        assertEquals("Be short.", t.system)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), t.history.map { it.role })
        assertEquals("Name a colour", t.last)
        assertNull(Turns.of(listOf(ChatMessage(Role.USER, "Hi"))).system)
        assertFailsWith<IllegalArgumentException> { Turns.of(listOf(ChatMessage(Role.USER, "Hi"), ChatMessage(Role.ASSISTANT, "Hello"))) }
    }

    @Test
    fun aReplyAtTheTokenLimitEndsOnLength() {
        val s = Stats.of(200, 50, 500, 2000, 512, stopped = false)
        assertEquals(400.0, s.prefillTps, 1e-9)
        assertEquals(25.0, s.decodeTps, 1e-9)
        assertEquals(Finish.STOP, s.finish)
        assertEquals(Finish.LENGTH, Stats.of(10, 64, 100, 1000, 64, stopped = false).finish)
        assertEquals(Finish.STOP, Stats.of(10, 64, 100, 1000, 64, stopped = true).finish)
    }

    @Test
    fun temperatureZeroIsGreedy() {
        assertEquals(1, Sampling.of(GenRequest(emptyList(), temperature = 0f)).topK)
        assertFalse(Sampling.of(GenRequest(emptyList(), temperature = 0.7f)).topK == 1)
    }
}
