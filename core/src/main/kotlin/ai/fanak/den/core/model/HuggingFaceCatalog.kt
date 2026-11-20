package ai.fanak.den.core.model

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

class GatedModelException(id: String, hub: String = "Hugging Face") :
    IOException("$id is gated: accept its terms on $hub first; Den does not download gated files")

/**
 * GGUF files of one Hugging Face repository, named `hf:owner/repo` or `hf:owner/repo@revision`. Resolved on demand
 * (no listing of the hub): the file tree gives each file's size and SHA-256, downloads are pinned to the commit.
 * Results are cached on disk, so a resolved model also works offline.
 */
class HuggingFaceCatalog(
    private val cacheDir: File?,
    private val fetch: suspend (String) -> String = ::httpGet,
    private val host: String = "https://huggingface.co",
) : ModelCatalog {
    @Serializable
    private data class Repo(val commit: String, val licence: String?, val files: List<RepoFile>)

    @Serializable
    private data class RepoFile(val path: String, val size: Long, val sha256: String)

    private class Resolved(val variants: List<Variant>, val remote: Map<Variant, RemoteFile>)

    private val resolved = ConcurrentHashMap<String, Resolved>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun variants(model: String) = resolved[model]?.variants ?: emptyList()

    override fun remote(variant: Variant) = resolved[variant.model]?.remote?.get(variant)

    override suspend fun resolve(id: String): List<Variant> {
        val ref = parse(id) ?: return emptyList()
        resolved[id]?.let { return it.variants }
        val repo = try {
            load(id, ref).also { save(ref, it) }
        } catch (e: GatedModelException) {
            throw e
        } catch (e: IOException) {
            cached(ref) ?: throw e
        }
        return build(id, ref, repo).also { resolved[id] = it }.variants
    }

    private data class Ref(val owner: String, val repo: String, val revision: String) {
        val name get() = "$owner/$repo"
    }

    private fun parse(id: String): Ref? {
        if (!id.startsWith("hf:")) return null
        val (name, rev) = id.removePrefix("hf:").split('@', limit = 2).let { it[0] to it.getOrElse(1) { "main" } }
        val parts = name.split('/')
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return Ref(parts[0], parts[1], rev)
    }

    private suspend fun load(id: String, ref: Ref): Repo {
        val info = json.parseToJsonElement(fetch("$host/api/models/${ref.name}/revision/${ref.revision}?expand[]=gated&expand[]=cardData&expand[]=sha")).jsonObject
        val commit = info.str("sha") ?: throw IOException("no commit for ${ref.name}@${ref.revision}")
        val gated = (info["gated"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull != null) } ?: false
        if (gated) throw GatedModelException(id)
        val licence = (info["cardData"] as? JsonObject)?.str("license")
        val tree = json.parseToJsonElement(fetch("$host/api/models/${ref.name}/tree/$commit?recursive=true")) as JsonArray
        val files = tree.mapNotNull { e ->
            val o = e.jsonObject
            val path = o.str("path") ?: return@mapNotNull null
            val lfs = o["lfs"] as? JsonObject ?: return@mapNotNull null
            val sha = lfs.str("oid") ?: return@mapNotNull null
            val size = (lfs["size"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            RepoFile(path, size, sha)
        }
        return Repo(commit, licence, files)
    }

    private fun build(id: String, ref: Ref, repo: Repo): Resolved {
        val store = "hf/${ref.owner}/${ref.repo}" + if (ref.revision == "main") "" else "/${ref.revision}"
        val files = repo.files.map { HubFile(it.path, it.size, it.sha256) }
        val remote = GgufFiles.variants(id, files, store) { "$host/${ref.name}/resolve/${repo.commit}/${it.path}" }
        return Resolved(remote.keys.toList(), remote)
    }

    private fun cacheFile(ref: Ref) = cacheDir?.let { File(it, "hf/${ref.owner}/${ref.repo}@${ref.revision}.json") }

    private fun save(ref: Ref, repo: Repo) {
        val f = cacheFile(ref) ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(json.encodeToString(Repo.serializer(), repo)) }
    }

    private fun cached(ref: Ref): Repo? =
        cacheFile(ref)?.takeIf { it.isFile }?.let { runCatching { json.decodeFromString(Repo.serializer(), it.readText()) }.getOrNull() }

    private companion object {
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", DEN_USER_AGENT)
            try {
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
