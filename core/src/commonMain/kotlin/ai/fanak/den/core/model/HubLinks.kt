package ai.fanak.den.core.model

/**
 * Turns what a user pastes into a model id: a Hugging Face link to a repository, a revision or a file, or an `hf:` id
 * with an optional revision and quant (`hf:owner/repo@rev:Q4_K_M`). A link to one file keeps its quant.
 */
object HubLinks {
    private val HF_URL = Regex("""^https?://(?:www\.)?huggingface\.co/([\w.-]+)/([\w.-]+)(?:/(?:tree|blob|resolve)/([^/?#]+)(?:/([^?#]+))?)?/?(?:[?#].*)?$""")
    private val QUANT = Regex("""[-_.]((?:UD-)?(?:I?Q\d[A-Z0-9_]*|BF16|F16|F32))\.gguf$""", RegexOption.IGNORE_CASE)

    fun modelId(text: String): String? {
        val s = text.trim()
        if (s.startsWith("hf:")) return s.takeIf { Regex("""^hf:[\w.-]+/[\w.-]+(@[^:\s]+)?(:[\w-]+)?$""").matches(it) }
        val m = HF_URL.matchEntire(s) ?: return null
        val (owner, repo, rev, file) = m.destructured
        val quant = QUANT.find(file)?.groupValues?.get(1)?.uppercase()
        return "hf:$owner/$repo" + (if (rev.isNotEmpty() && rev != "main") "@$rev" else "") + (quant?.let { ":$it" } ?: "")
    }
}
