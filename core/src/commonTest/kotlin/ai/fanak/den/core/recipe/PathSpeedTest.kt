package ai.fanak.den.core.recipe

import ai.fanak.den.core.device.Accel
import ai.fanak.den.core.model.ModelFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class PathSpeedTest {
    private val s = PathSpeed(RecipeChoice("llamacpp", Accel.CPU, ModelFormat.GGUF, "q4_0"), 200, 100.0, 20.0, 1200, 50.0, 10.0)

    @Test
    fun atTheMeasuredLengthsTheSpeedsAreTheMeasuredOnes() {
        assertEquals(200 / 100.0 + 40 / 20.0, s.seconds(200, 40), 1e-9)
        assertEquals(1200 / 50.0 + 40 / 10.0, s.seconds(1200, 40), 1e-9)
    }

    @Test
    fun betweenThemTheSpeedsAreInterpolatedAndBeyondThemHeld() {
        assertEquals(700 / 75.0 + 40 / 15.0, s.seconds(700, 40), 1e-9)
        assertEquals(50 / 100.0 + 40 / 20.0, s.seconds(50, 40), 1e-9)
        assertEquals(4000 / 50.0 + 40 / 10.0, s.seconds(4000, 40), 1e-9)
    }

    @Test
    fun oneLengthOnlyUsesTheShortSpeeds() {
        assertEquals(1000 / 100.0, s.copy(longPromptTokens = 200).seconds(1000, 0), 1e-9)
    }
}
