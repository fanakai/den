package ai.fanak.den.core.recipe

import ai.fanak.den.core.device.SocFamilies
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rules a recipe in recipes/ must follow, checked on every pull request: only measured numbers, with the device,
 * the date and the protocol (AGENTS.md, rule 3), for a backend Den has, and a SoC the device probe can report.
 */
class RecipeLintTest {
    private val dir = File(System.getProperty("den.recipes"))

    // Backends in this repository; a recipe for another one would never match.
    private val backends = setOf("llamacpp")

    @Test
    fun everyRecipeFollowsTheRules() {
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "yaml" }.toList()
        assertTrue(files.isNotEmpty(), "no recipes in $dir")
        val problems = files.flatMap { f -> lint(f.name, f.nameWithoutExtension, RecipeFiles.parse(f.readText(), f.name)) }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun theLintCatchesWhatItShould() {
        val ok = Recipe(
            socFamily = "unisoc-t760", soc = "T760",
            rankings = listOf(
                Ranking(
                    ModelSize("qwen3", 0.6),
                    listOf(RecipeChoice("llamacpp", ai.fanak.den.core.device.Accel.CPU, ai.fanak.den.core.model.ModelFormat.GGUF, "q4_0")),
                    Measurement(166.4, 13.6, "ZTE Z2357N", "2026-11-20", protocol = "den bench"),
                ),
            ),
        )
        assertTrue(lint("T760.yaml", "T760", ok).isEmpty())
        fun problems(r: Recipe, name: String = "T760") = lint("$name.yaml", name, r)
        val ranking = ok.rankings.single()
        assertTrue(problems(ok.copy(rankings = listOf(ranking.copy(measured = null)))).any { "no measured block" in it })
        assertTrue(problems(ok.copy(rankings = listOf(ranking.copy(measured = ranking.measured!!.copy(protocol = null))))).any { "protocol" in it })
        assertTrue(problems(ok.copy(rankings = listOf(ranking.copy(measured = ranking.measured!!.copy(date = "2026-13-40"))))).any { "date" in it })
        assertTrue(problems(ok.copy(rankings = listOf(ranking.copy(measured = ranking.measured!!.copy(decodeTps = 0.0))))).any { "speeds" in it })
        assertTrue(problems(ok.copy(socFamily = "unisoc")).any { "soc_family" in it })
        assertTrue(problems(ok, name = "t760-copy").any { "file name" in it })
        assertTrue(problems(ok.copy(soc = null), name = "unisoc-t760").isEmpty())
        assertTrue(problems(ok.copy(soc = null, socFamily = "unisoc-t999"), name = "unisoc-t999").any { "probe" in it })
        assertTrue(problems(ok.copy(rankings = listOf(ranking.copy(choices = listOf(ranking.choices[0].copy(backend = "mnn")))))).any { "backend" in it })
    }

    private fun lint(file: String, name: String, r: Recipe): List<String> = buildList {
        val soc = r.soc
        if (soc == null) {
            if (name != r.socFamily) add("$file: file name must be the family, ${r.socFamily}.yaml")
            if (r.socFamily !in SocFamilies.families) add("$file: soc_family ${r.socFamily} is not one the probe reports")
        } else {
            if (name != soc) add("$file: file name must be the SoC, $soc.yaml")
            if (r.socFamily != SocFamilies.of(soc)) add("$file: soc_family ${r.socFamily}, but the probe reports ${SocFamilies.of(soc)} for $soc")
        }
        r.rankings.forEach { k ->
            val at = "$file, ${k.model.arch} ${k.model.paramsB}B"
            k.choices.filter { it.backend !in backends }.forEach { add("$at: unknown backend ${it.backend}") }
            k.choices.filter { it.quant != it.quant.lowercase() }.forEach { add("$at: quant ${it.quant} must be lower case") }
            val m = k.measured
            if (m == null) {
                add("$at: no measured block")
                return@forEach
            }
            if (m.prefillTps <= 0 || m.decodeTps <= 0) add("$at: speeds must be measured, above 0")
            if (m.device.isBlank()) add("$at: no device")
            if (m.protocol.isNullOrBlank()) add("$at: no protocol")
            val date = runCatching { LocalDate.parse(m.date) }.getOrNull()
            if (date == null || date.isAfter(LocalDate.now())) add("$at: date ${m.date} is not a past date (yyyy-mm-dd)")
        }
    }
}
