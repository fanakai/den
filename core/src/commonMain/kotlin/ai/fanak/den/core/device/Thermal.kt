package ai.fanak.den.core.device

/**
 * What Den does as a phone heats up. Phones are usually cool when a model loads and heat up after minutes of decoding,
 * so the state is read again before each request, not only at load.
 */
object Thermal {
    // Hot enough at load for a smaller file to come first (Android: the system throttles heavily from SEVERE on).
    fun hot(state: ThermalState) = state >= ThermalState.SEVERE

    /**
     * Threads for the next request, from the decode threads the engine picked on its own ([auto]); null keeps the
     * engine's choice. Fewer threads give less heat at a lower speed; the steps are a policy, not a measurement.
     */
    fun threads(auto: Int, state: ThermalState): Int? = when {
        auto <= 0 -> null
        state >= ThermalState.SEVERE -> maxOf(1, auto / 2)
        state >= ThermalState.MODERATE -> maxOf(1, auto * 3 / 4)
        else -> null
    }
}
