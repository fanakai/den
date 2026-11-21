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

    external fun load(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int, useMmap: Boolean): Long

    // [promptTokens, outputTokens, prefillMs, decodeMs, finish]; contents and stop strings as UTF-8.
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

    external fun free(handle: Long)

    external fun version(): String
}
