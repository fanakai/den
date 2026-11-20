package ai.fanak.den.core.model

// Sent with every request to a model hub, so the hubs can tell which client asks.
const val DEN_USER_AGENT = "fanak-den/0.1"

// A file in a model repository, as a hub lists it.
internal data class HubFile(val path: String, val size: Long, val sha256: String)

/** Turns the files of a hub repository into GGUF variants; shared by the Hugging Face and ModelScope catalogs. */
internal object GgufFiles {
    private val QUANT = Regex("""[-_.]((?:UD-)?(?:I?Q\d[A-Z0-9_]*|BF16|F16|F32))\.gguf$""", RegexOption.IGNORE_CASE)

    // Split files and multimodal projectors need multi-file artifacts (#81).
    private val SKIP = Regex("""-\d{5}-of-\d{5}\.gguf$|mmproj""", RegexOption.IGNORE_CASE)

    // Rough until the estimate reads layers and KV heads from the GGUF header (#79): weights, a quarter more for the
    // KV cache and buffers (about 600 MB for a 4B model at 4096 tokens), and a margin for the system.
    fun ramMb(bytes: Long): Int = ((bytes shr 20) * 5 / 4).toInt() + 512

    // `store` is the folder under the model folder; `url` builds the download link of a file.
    // `arch` and `params` come from the hub's GGUF metadata, the same for every file of a repository.
    fun variants(
        id: String,
        files: List<HubFile>,
        store: String,
        arch: String? = null,
        params: Long? = null,
        url: (HubFile) -> String,
    ): Map<Variant, RemoteFile> {
        val out = LinkedHashMap<Variant, RemoteFile>()
        // Same content under two names (Q2_K and Q2_K_L in some repositories): keep the first.
        for (f in files.distinctBy { it.sha256 }) {
            val name = f.path.substringAfterLast('/')
            if (!name.endsWith(".gguf", ignoreCase = true) || SKIP.containsMatchIn(name)) continue
            val quant = QUANT.find(name)?.groupValues?.get(1)?.lowercase() ?: continue
            val v = Variant(id, ModelFormat.GGUF, quant, f.size, ramMb(f.size), arch = arch, params = params)
            out[v] = RemoteFile(name, url(f), f.sha256, f.size, "$store/${f.path}")
        }
        return out
    }
}
