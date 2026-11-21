package ai.fanak.den.backend.llamacpp

class EngineException(message: String) : RuntimeException(message)

internal fun interface TextCallback {
    fun onText(utf8: ByteArray): Boolean
}

internal object LlamaNative {
    init {
        // The JVM unit tests load a desktop build of the engine from a path.
        System.getProperty("den.engineLib")?.let { System.load(it) } ?: System.loadLibrary("den_llamacpp")
    }

    // "name\tdescription\tgpu|cpu" per device
    external fun devices(): Array<String>

    // nSeq: requests that generate on the handle at the same time, decoded together; further ones wait.
    external fun load(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int, useMmap: Boolean, nSeq: Int, kvQ8: Boolean): Long

    // [promptTokens, outputTokens, prefillMs, decodeMs, finish, cachedTokens, threads]; contents and stop strings
    // as UTF-8. Several threads may call it at once; free must wait for them.
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stop: Array<ByteArray>,
        cache: Boolean,
        threads: Int,
        // [grammar, JSON schema], each empty when unused.
        constraints: Array<ByteArray>,
        callback: TextCallback,
    ): LongArray

    // Another handle on the same weights, with its own context; free it like any handle.
    external fun fork(handle: Long): Long

    external fun free(handle: Long)

    external fun version(): String
}
