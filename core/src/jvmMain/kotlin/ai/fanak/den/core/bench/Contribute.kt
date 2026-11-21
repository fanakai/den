package ai.fanak.den.core.bench

import java.net.URLEncoder

// Links that send a den bench result to the repository, pre-filled, for users without a clone.
object Contribute {
    const val REPO = "https://github.com/fanakai/den"

    // GitHub's new-file page with the recipe as recipes/<soc>.yaml; saving it from a fork opens a pull request.
    fun newRecipeUrl(soc: String, yaml: String): String =
        "$REPO/new/main/recipes?filename=${enc("$soc.yaml")}&value=${enc(yaml)}"

    // An issue with the recipe, for a SoC that has a recipe file already: GitHub cannot pre-fill an edit.
    fun issueUrl(r: BenchResult, yaml: String): String {
        val title = "Recipe: ${r.soc}, ${r.model} (${r.device})"
        val rows = r.candidates.joinToString("\n") { c ->
            val result = if (c.ok) "${c.prefillTps!!.median} | ${c.decodeTps!!.median} | ${c.peakRamMb ?: ""}" else "failed: ${c.error} | |"
            val kv = if (c.kvCache != ai.fanak.den.core.backend.KvCache.F16) ", KV ${c.kvCache.name.lowercase()}" else ""
            "| ${c.backend} ${c.accel.name.lowercase()} ${c.quant}$kv | $result |"
        }
        val body = buildString {
            appendLine("Measured with den bench on ${r.device}, ${r.date}.")
            appendLine()
            appendLine("| Choice | Prefill tok/s | Decode tok/s | Peak RAM MB |")
            appendLine("|---|---|---|---|")
            appendLine(rows)
            appendLine()
            appendLine("```yaml")
            append(yaml)
            appendLine("```")
        }
        return "$REPO/issues/new?title=${enc(title)}&body=${enc(body)}"
    }

    // The String overload: the Charset one needs Android 13.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
