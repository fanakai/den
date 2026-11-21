package ai.fanak.den.core.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RemoteFile(
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    // Where the file is stored, relative to the model folder; by source and repository, so two uploads with the
    // same file name never collide.
    val path: String = fileName,
    val licence: String? = null,
)

// The platform reports no usable network for this app (Doze, battery saver, data saver, no connection).
class OfflineException(message: String) : IOException(message)

// The download was not allowed (user, quota): other files of the model would be refused the same way.
class DownloadRefusedException(message: String) : IOException(message)

data class DownloadProgress(val fileName: String, val bytes: Long, val total: Long)

/**
 * Downloads a variant on first use, resumes a partial download, and checks its SHA-256 before use.
 * A verified file gets a `.sha256` marker, so it is not hashed again on every load.
 */
class HttpModelStore(
    private val dir: File,
    private val locate: (Variant) -> RemoteFile,
    private val onProgress: (DownloadProgress) -> Unit = {},
    private val minFreeBytes: Long = 200L shl 20,
    // False when the platform says this app has no usable network, to fail at once instead of after timeouts.
    private val online: () -> Boolean = { true },
    private val retryDelayMs: Long = 2_000,
    // Runs before a file is downloaded, not when it is on disk already; may wait (network) or throw DownloadRefusedException.
    private val beforeDownload: suspend (Variant, RemoteFile) -> Unit = { _, _ -> },
) : ModelStore {
    private val manifest = ModelManifest(dir)

    override suspend fun fetch(variant: Variant): Artifact {
        val r = locate(variant)
        val file = File(dir, r.path)
        mirror(r, file)?.let { return Artifact(variant, it.path) }
        LOCKS.getOrPut(file.canonicalPath) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                adoptLegacy(r, file)
                if (!isVerified(file, r)) {
                    beforeDownload(variant, r)
                    manifest.put(entry(variant, r, complete = false))
                    downloadWithRetry(r, file)
                }
                manifest.put(entry(variant, r, complete = true))
            }
        }
        return Artifact(variant, file.path)
    }

    override fun freeBytes(): Long = (dir.apply { mkdirs() }.usableSpace - minFreeBytes).coerceAtLeast(0)

    override fun has(variant: Variant): Boolean {
        val r = runCatching { locate(variant) }.getOrNull() ?: return false
        val file = File(dir, r.path)
        return (file.isFile && file.length() == r.sizeBytes) || mirror(r, file) != null
    }

    // The same content (same SHA-256) already downloaded from another source: a mirror, used as it is.
    private fun mirror(r: RemoteFile, file: File): File? {
        if (file.isFile) return null
        return manifest.entries().firstOrNull { it.complete && it.sha256 == r.sha256 && it.path != r.path }
            ?.let { File(dir, it.path) }?.takeIf { it.isFile && it.length() == r.sizeBytes }
    }

    private fun entry(v: Variant, r: RemoteFile, complete: Boolean) =
        ManifestEntry(v.model, v.format, v.quant, r.path, r.sha256, r.sizeBytes, r.url, complete, r.licence)

    // Files stored before paths existed sit directly in the folder under their file name.
    private fun adoptLegacy(r: RemoteFile, file: File) {
        if (r.path == r.fileName || file.exists()) return
        listOf("", ".sha256", ".part").forEach { ext ->
            val old = File(dir, r.fileName + ext)
            if (old.isFile) {
                File(file.path + ext).parentFile?.mkdirs()
                old.renameTo(File(file.path + ext))
            }
        }
    }

    private fun marker(file: File) = File(file.path + ".sha256")

    private fun isVerified(file: File, r: RemoteFile): Boolean {
        if (!file.isFile || file.length() != r.sizeBytes) return false
        val m = marker(file)
        if (m.isFile && m.readText().trim() == r.sha256) return true
        // Placed by other means (copied, older version): hash it once.
        val ok = file.inputStream().use { sha256(it) } == r.sha256
        if (ok) m.writeText(r.sha256)
        return ok
    }

    // A cut connection or a server error is tried again, resuming from the partial file.
    private suspend fun downloadWithRetry(r: RemoteFile, file: File) {
        var attempt = 1
        while (true) {
            try {
                return download(r, file)
            } catch (e: IOException) {
                if (attempt >= ATTEMPTS || !retryable(e) || !online()) throw e
                delay(retryDelayMs * attempt++)
            }
        }
    }

    private suspend fun download(r: RemoteFile, file: File) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have >= r.sizeBytes) {
            part.delete()
            have = 0
        }
        if (!online()) throw OfflineException("no network for this app; ${r.fileName} not downloaded")
        if (dir.usableSpace - (r.sizeBytes - have) < minFreeBytes) {
            throw IOException("not enough space for ${r.fileName}: need ${(r.sizeBytes - have) shr 20} MB")
        }

        val conn = URL(r.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", DEN_USER_AGENT)
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val append = when (conn.responseCode) {
                206 -> have > 0
                200 -> false
                else -> throw IOException("HTTP ${conn.responseCode} for ${r.url}")
            }
            val md = MessageDigest.getInstance("SHA-256")
            if (append) part.inputStream().use { update(md, it) } else have = 0

            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = have
                    var reported = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - reported >= PROGRESS_STEP) {
                            onProgress(DownloadProgress(r.fileName, done, r.sizeBytes))
                            reported = done
                        }
                    }
                    onProgress(DownloadProgress(r.fileName, done, r.sizeBytes))
                }
            }
            val sha = md.digest().toHex()
            if (part.length() != r.sizeBytes || sha != r.sha256) {
                part.delete()
                throw IOException("checksum mismatch for ${r.fileName}")
            }
        } finally {
            conn.disconnect()
        }
        file.delete()
        if (!part.renameTo(file)) throw IOException("cannot move ${part.path}")
        marker(file).writeText(r.sha256)
    }

    private companion object {
        const val PROGRESS_STEP = 4L shl 20
        const val ATTEMPTS = 3

        // Shared by every store in the process: two Den instances on one folder must not write the same file.
        val LOCKS = ConcurrentHashMap<String, Mutex>()

        // Not retried: no network (fails the same way after its timeouts), 4xx, checksum, disk space.
        fun retryable(e: IOException) = when (e) {
            is OfflineException, is java.net.ConnectException, is java.net.UnknownHostException -> false
            is java.net.SocketTimeoutException, is java.net.SocketException, is java.io.EOFException -> true
            else -> e.message?.startsWith("HTTP 5") == true
        }

        fun update(md: MessageDigest, input: InputStream) {
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }

        fun sha256(input: InputStream) = MessageDigest.getInstance("SHA-256").also { update(it, input) }.digest().toHex()

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
