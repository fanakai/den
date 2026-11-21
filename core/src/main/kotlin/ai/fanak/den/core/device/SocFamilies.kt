package ai.fanak.den.core.device

// SoC model (Build.SOC_MODEL) to the family name used by recipes. Unknown chips map to themselves.
object SocFamilies {
    private val known = mapOf(
        "SM8750" to "snapdragon-8-elite",
        "SM7325" to "snapdragon-778g",
        "T760" to "unisoc-t760",
    )

    fun of(soc: String): String = known[soc.uppercase()] ?: soc.lowercase()

    // The families the probe reports for known chips; a family recipe must name one of them.
    val families: Set<String> get() = known.values.toSet()
}
