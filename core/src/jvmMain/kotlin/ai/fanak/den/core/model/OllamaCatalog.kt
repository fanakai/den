package ai.fanak.den.core.model

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Models of the Ollama registry, named `ollama:model:tag` (library models) or `ollama:namespace/model:tag`. A tag is
 * an OCI-style manifest whose model layer is one GGUF file, with its size and SHA-256 digest; its config blob gives
 * the quant, the architecture and the size. Results are cached on disk, so a resolved model also works offline.
 */
class OllamaCatalog(
    private val cacheDir: File?,
    private val fetch: suspend (String) -> String = ::httpGet,
    private val fetchHead: suspend (String) -> ByteArray = ::httpHead,
    private val host: String = "https://registry.ollama.ai",
) : ModelCatalog {
    @Serializable
    private data class Tag(
        val digest: String,
        val size: Long,
        val quant: String,
        val arch: String? = null,
        val params: Long? = null,
        val maxContext: Int? = null,
        val kvBytesPerToken: Long? = null,
    )

    private class Resolved(val variant: Variant, val remote: RemoteFile)

    @Volatile private var resolved: Map<String, Resolved> = emptyMap()
    private val json = Json { ignoreUnknownKeys = true }

    override fun variants(model: String) = listOfNotNull(resolved[model]?.variant)

    override fun remote(variant: Variant) = resolved[variant.model]?.takeIf { it.variant == variant }?.remote

    override suspend fun resolve(id: String): List<Variant> {
        val ref = parse(id) ?: return emptyList()
        resolved[id]?.let { return listOf(it.variant) }
        val tag = try {
            load(ref).also { save(ref, it) }
        } catch (e: IOException) {
            cached(ref) ?: throw e
        }
        val v = Variant(
            id, ModelFormat.GGUF, tag.quant, tag.size, GgufFiles.ramMb(tag.size),
            arch = tag.arch, params = tag.params, maxContext = tag.maxContext, kvBytesPerToken = tag.kvBytesPerToken,
        )
        val sha = tag.digest.removePrefix("sha256:")
        val file = "${ref.model}-${ref.tag}.gguf"
        val r = RemoteFile(file, blobUrl(ref, tag.digest), sha, tag.size, "ollama/${ref.namespace}/${ref.model}/${ref.tag}/$file")
        resolved = resolved + (id to Resolved(v, r))
        return listOf(v)
    }

    private data class Ref(val namespace: String, val model: String, val tag: String) {
        val path get() = "$namespace/$model"
    }

    private fun parse(id: String): Ref? {
        if (!id.startsWith("ollama:")) return null
        val (name, tag) = id.removePrefix("ollama:").split(':', limit = 2).let { it[0] to it.getOrElse(1) { "latest" } }
        val parts = name.split('/')
        return when {
            parts.size == 1 && parts[0].isNotBlank() -> Ref("library", parts[0], tag)
            parts.size == 2 && parts.none { it.isBlank() } -> Ref(parts[0], parts[1], tag)
            else -> null
        }
    }

    private fun blobUrl(ref: Ref, digest: String) = "$host/v2/${ref.path}/blobs/$digest"

    private suspend fun load(ref: Ref): Tag {
        val manifest = json.parseToJsonElement(fetch("$host/v2/${ref.path}/manifests/${ref.tag}")).jsonObject
        val layer = (manifest["layers"] as? JsonArray).orEmpty().map { it.jsonObject }
            .firstOrNull { (it["mediaType"] as? JsonPrimitive)?.contentOrNull == "application/vnd.ollama.image.model" }
            ?: throw IOException("no model layer in ollama:${ref.path}:${ref.tag}")
        val digest = (layer["digest"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("model layer without a digest")
        val size = (layer["size"] as? JsonPrimitive)?.longOrNull ?: throw IOException("model layer without a size")
        val configDigest = ((manifest["config"] as? JsonObject)?.get("digest") as? JsonPrimitive)?.contentOrNull
        val config = configDigest?.let { runCatching { json.parseToJsonElement(fetch(blobUrl(ref, it))).jsonObject }.getOrNull() }
        fun cfg(key: String) = (config?.get(key) as? JsonPrimitive)?.contentOrNull
        if (cfg("model_format")?.let { it != "gguf" } == true) throw IOException("ollama:${ref.path}:${ref.tag} is not a GGUF model")
        val header = runCatching { GgufHeader.read(fetchHead(blobUrl(ref, digest)).inputStream()) }.getOrNull()
        return Tag(
            digest, size, (cfg("file_type") ?: "unknown").lowercase(), header?.arch ?: cfg("model_family"),
            params(cfg("model_type")), header?.contextLength, header?.kvBytesPerToken,
        )
    }

    private fun cacheFile(ref: Ref) = cacheDir?.let { File(it, "ollama/${ref.path}@${ref.tag}.json") }

    private fun save(ref: Ref, tag: Tag) {
        val f = cacheFile(ref) ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(json.encodeToString(Tag.serializer(), tag)) }
    }

    private fun cached(ref: Ref): Tag? =
        cacheFile(ref)?.takeIf { it.isFile }?.let { runCatching { json.decodeFromString(Tag.serializer(), it.readText()) }.getOrNull() }

    companion object {
        // "751.63M" or "1.7B", as Ollama writes the size: parameters, for recipes to match on.
        internal fun params(text: String?): Long? {
            val m = Regex("""^([\d.]+)\s*([MB])$""", RegexOption.IGNORE_CASE).matchEntire(text?.trim() ?: return null) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            return (n * if (m.groupValues[2].equals("B", true)) 1e9 else 1e6).toLong()
        }

        private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", DEN_USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.docker.distribution.manifest.v2+json")
            try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }

        // The first 256 KB of the model layer, for its GGUF header.
        private suspend fun httpHead(url: String): ByteArray = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", DEN_USER_AGENT)
            conn.setRequestProperty("Range", "bytes=0-${256 * 1024 - 1}")
            try {
                if (conn.responseCode !in listOf(200, 206)) throw IOException("HTTP ${conn.responseCode} for $url")
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() < 256 * 1024) {
                        val n = input.read(buf, 0, minOf(buf.size, 256 * 1024 - out.size()))
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
