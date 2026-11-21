package ai.fanak.den.core.device

/** The arm64 features of /proc/cpuinfo (or AT_HWCAP names) that llama.cpp's kernels depend on, under plain names. */
object CpuFeatures {
    private val names = mapOf(
        "asimddp" to "dotprod",
        "asimdhp" to "fp16",
        "fphp" to "fp16",
        "i8mm" to "i8mm",
        "bf16" to "bf16",
        "sve" to "sve",
        "sve2" to "sve2",
    )

    // From the "Features" lines of /proc/cpuinfo; the set every core has, since a thread may run on any of them.
    fun parse(cpuinfo: String): Set<String> {
        val perCore = cpuinfo.lineSequence()
            .filter { it.startsWith("Features") }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull { names[it] }.toSet() }
            .toList()
        return perCore.reduceOrNull { a, b -> a intersect b } ?: emptySet()
    }
}
