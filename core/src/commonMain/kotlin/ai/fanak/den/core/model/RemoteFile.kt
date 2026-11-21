package ai.fanak.den.core.model

import kotlinx.io.IOException

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

