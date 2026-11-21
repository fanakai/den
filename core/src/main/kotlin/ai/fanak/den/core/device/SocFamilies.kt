package ai.fanak.den.core.device

// SoC model (Build.SOC_MODEL) to the family name used by recipes. Unknown chips map to themselves.
object SocFamilies {
    private val known = mapOf(
        "SM8750" to "snapdragon-8-elite",
        "SM7325" to "snapdragon-778g",
        "T760" to "unisoc-t760",
    )

    // Chips not in the table, by the prefix of their model: a family recipe for a series reaches its other chips.
    private val prefixes = listOf(
        Regex("^SM8\\d{3}") to "snapdragon-8-series",
        Regex("^SM7\\d{3}") to "snapdragon-7-series",
        Regex("^SM6\\d{3}") to "snapdragon-6-series",
        Regex("^SM4\\d{3}") to "snapdragon-4-series",
        Regex("^MT6[89]\\d{2}") to "mediatek-dimensity",
        Regex("^MT67\\d{2}") to "mediatek-helio",
        Regex("^(TENSOR|GS\\d{3})") to "google-tensor",
        Regex("^S5E\\d{4}") to "samsung-exynos",
        Regex("^T[67]\\d{2}") to "unisoc-t",
    )

    fun of(soc: String): String {
        val s = soc.trim().uppercase()
        return known[s] ?: prefixes.firstOrNull { it.first.containsMatchIn(s) }?.second ?: soc.lowercase()
    }

    // The families the probe reports for known chips; a family recipe must name one of them.
    val families: Set<String> get() = known.values.toSet() + prefixes.map { it.second }
}
