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
)

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
) : ModelStore {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val manifest = ModelManifest(dir)

    override suspend fun fetch(variant: Variant): Artifact {
        val r = locate(variant)
        val file = File(dir, r.path)
        locks.getOrPut(r.path) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                adoptLegacy(r, file)
                if (!isVerified(file, r)) {
                    manifest.put(entry(variant, r, complete = false))
                    download(r, file)
                }
                manifest.put(entry(variant, r, complete = true))
            }
        }
        return Artifact(variant, file.path)
    }

    private fun entry(v: Variant, r: RemoteFile, complete: Boolean) =
        ManifestEntry(v.model, v.format, v.quant, r.path, r.sha256, r.sizeBytes, r.url, complete)

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

    private suspend fun download(r: RemoteFile, file: File) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have >= r.sizeBytes) {
            part.delete()
            have = 0
        }
        if (!online()) throw IOException("no network for this app; ${r.fileName} not downloaded")
        if (dir.usableSpace - (r.sizeBytes - have) < minFreeBytes) {
            throw IOException("not enough space for ${r.fileName}: need ${(r.sizeBytes - have) shr 20} MB")
        }

        val conn = URL(r.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
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
