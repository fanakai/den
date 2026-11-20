package ai.fanak.den.core.backend

import kotlin.test.Test
import kotlin.test.assertEquals

class GenStatsTest {
    @Test
    fun roundTripsWithTheReason() {
        val s = GenStats(24, 64, 150, 2000, Finish.CONTEXT)
        assertEquals(s, GenStats.of(s.toLongs()))
    }

    @Test
    fun olderSendersGiveNoReason() {
        assertEquals(Finish.STOP, GenStats.of(longArrayOf(24, 64, 150, 2000)).finish)
    }
}
