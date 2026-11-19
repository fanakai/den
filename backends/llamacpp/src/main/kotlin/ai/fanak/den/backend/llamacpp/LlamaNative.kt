package ai.fanak.den.backend.llamacpp

class EngineException(message: String) : RuntimeException(message)

internal fun interface TextCallback {
    fun onText(utf8: ByteArray): Boolean
}

internal object LlamaNative {
    init {
        System.loadLibrary("den_llamacpp")
    }

    // "name\tdescription\tgpu|cpu" per device
    external fun devices(): Array<String>

    external fun load(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int, useMmap: Boolean): Long

    // [promptTokens, outputTokens, prefillMs, decodeMs]
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        stop: Array<String>,
        callback: TextCallback,
    ): LongArray

    external fun free(handle: Long)
}
