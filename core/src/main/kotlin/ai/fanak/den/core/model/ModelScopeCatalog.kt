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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * GGUF files of one ModelScope repository, named `ms:owner/repo` or `ms:owner/repo@revision`. Same contract as
 * [HuggingFaceCatalog]: resolved on demand, each file pinned to its commit, cached on disk for offline use.
 * ModelScope limits anonymous requests; a refused request falls back to the cache when there is one.
 */
class ModelScopeCatalog(
    private val cacheDir: File?,
    private val fetch: suspend (String) -> String = ::httpGet,
    private val host: String = "https://modelscope.cn",
) : ModelCatalog {
    @Serializable
    private data class Repo(val licence: String?, val files: List<RepoFile>)

    @Serializable
    private data class RepoFile(val path: String, val size: Long, val sha256: String, val commit: String)

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
        val store = "ms/${ref.owner}/${ref.repo}" + if (ref.revision == "master") "" else "/${ref.revision}"
        val files = repo.files.map { HubFile(it.path, it.size, it.sha256) }
        val commits = repo.files.associate { it.path to it.commit }
        val remote = GgufFiles.variants(id, files, store, licence = repo.licence) { f ->
            "$host/models/${ref.name}/resolve/${commits[f.path] ?: ref.revision}/${f.path}"
        }
        return Resolved(remote.keys.toList(), remote).also { resolved[id] = it }.variants
    }

    private data class Ref(val owner: String, val repo: String, val revision: String) {
        val name get() = "$owner/$repo"
    }

    private fun parse(id: String): Ref? {
        val rest = when {
            id.startsWith("ms:") -> id.removePrefix("ms:")
            id.startsWith("modelscope:") -> id.removePrefix("modelscope:")
            else -> return null
        }
        val (name, rev) = rest.split('@', limit = 2).let { it[0] to it.getOrElse(1) { "master" } }
        val parts = name.split('/')
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return Ref(parts[0], parts[1], rev)
    }

    // ModelScope answers HTTP 200 with its own code; anything but 200 there is an error, e.g. a rate limit.
    private suspend fun api(url: String): JsonObject {
        val o = json.parseToJsonElement(fetch(url)).jsonObject
        val code = (o["Code"] as? JsonPrimitive)?.longOrNull
        if (code != 200L) {
            val msg = (o["Message"] as? JsonPrimitive)?.contentOrNull ?: "error $code"
            throw IOException(if (code == RATE_LIMITED) "ModelScope is limiting requests; try again in a few minutes" else "ModelScope: $msg")
        }
        return o["Data"] as? JsonObject ?: throw IOException("ModelScope: no data for $url")
    }

    private suspend fun load(id: String, ref: Ref): Repo {
        val info = api("$host/api/v1/models/${ref.name}?Revision=${ref.revision}")
        // Models whose owner approves each download.
        if (((info["ApprovalMode"] as? JsonPrimitive)?.intOrNull ?: 0) != 0) throw GatedModelException(id, "ModelScope")
        val licence = (info["License"] as? JsonPrimitive)?.contentOrNull
        val list = api("$host/api/v1/models/${ref.name}/repo/files?Revision=${ref.revision}&Recursive=true")
        val files = (list["Files"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val o = e.jsonObject
            val path = (o["Path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val size = (o["Size"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val sha = (o["Sha256"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length == 64 } ?: return@mapNotNull null
            val commit = (o["Revision"] as? JsonPrimitive)?.contentOrNull ?: ref.revision
            RepoFile(path, size, sha, commit)
        }
        return Repo(licence, files)
    }

    private fun cacheFile(ref: Ref) = cacheDir?.let { File(it, "ms/${ref.owner}/${ref.repo}@${ref.revision}.json") }

    private fun save(ref: Ref, repo: Repo) {
        val f = cacheFile(ref) ?: return
        runCatching { f.parentFile?.mkdirs(); f.writeText(json.encodeToString(Repo.serializer(), repo)) }
    }

    private fun cached(ref: Ref): Repo? =
        cacheFile(ref)?.takeIf { it.isFile }?.let { runCatching { json.decodeFromString(Repo.serializer(), it.readText()) }.getOrNull() }

    private companion object {
        const val RATE_LIMITED = 10013201001L

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
