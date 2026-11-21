package ai.fanak.den.core.model

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// A GGUF header as llama.cpp writes it, keys in the order of the Qwen3-0.6B file of unsloth; also used by other tests.
internal class GgufWriter {
        val out = ByteArrayOutputStream()
        private fun le(n: Int, f: ByteBuffer.() -> Unit) = out.write(ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(f).array())
        fun u32(v: Int) = le(4) { putInt(v) }
        fun u64(v: Long) = le(8) { putLong(v) }
        fun str(s: String) = s.encodeToByteArray().let { u64(it.size.toLong()); out.write(it) }
        fun kv(key: String, v: Any) {
            str(key)
            when (v) {
                is String -> { u32(8); str(v) }
                is Int -> { u32(4); u32(v) }
                is Float -> { u32(6); le(4) { putFloat(v) } }
                is List<*> -> { u32(9); u32(8); u64(v.size.toLong()); v.forEach { str(it as String) } }
                else -> error("type")
            }
        }
}

internal fun qwen3Gguf(extra: GgufWriter.() -> Unit = {}): ByteArray = GgufWriter().apply {
        out.write("GGUF".encodeToByteArray())
        u32(3)
        u64(310)
        u64(12)
        kv("general.architecture", "qwen3")
        kv("general.name", "Qwen3-0.6B")
        kv("qwen3.block_count", 28)
        kv("qwen3.context_length", 40960)
        kv("qwen3.embedding_length", 1024)
        kv("qwen3.attention.head_count", 16)
        kv("qwen3.attention.head_count_kv", 8)
        kv("qwen3.rope.freq_base", 1_000_000f)
        kv("qwen3.attention.key_length", 128)
        kv("qwen3.attention.value_length", 128)
        kv("tokenizer.ggml.model", "gpt2")
        extra()
    }.out.toByteArray()

class GgufHeaderTest {
    private fun qwen3(extra: GgufWriter.() -> Unit = {}) = qwen3Gguf(extra)

    @Test
    fun readsTheKvCacheShapeAndStopsAtTheTokenizer() {
        // The vocabulary is cut off: reading must stop before it.
        val bytes = qwen3 { str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(151_936) }
        val info = GgufHeader.read(bytes.inputStream())
        assertEquals(GgufInfo("qwen3", 40960, 28, 16, 8, 1024, 128, 128), info)
        // 28 layers x 8 KV heads x (128 + 128) x 2 bytes: 448 MiB at 4096 tokens.
        assertEquals(114_688L, info.kvBytesPerToken)
    }

    @Test
    fun withoutKeyLengthTheHeadSizeComesFromTheEmbedding() {
        assertEquals(28L * 8 * 128 * 2 * 2, GgufInfo("x", null, 28, 16, 8, 2048).kvBytesPerToken)
        assertEquals(null, GgufInfo("x").kvBytesPerToken)
    }

    @Test
    fun aRangeCutInTheMetadataKeepsWhatWasRead() {
        val bytes = qwen3()
        val cut = GgufHeader.read(bytes.copyOf(bytes.indexOf("qwen3.attention.head_count_kv") - 8).inputStream())
        assertEquals(GgufInfo("qwen3", 40960, 28, 16, null, 1024), cut)
    }

    private fun ByteArray.indexOf(s: String): Int {
        val b = s.encodeToByteArray()
        return (0..size - b.size).first { i -> b.indices.all { this[i + it] == b[it] } }
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { GgufHeader.read("PK\u0003\u0004 not gguf".byteInputStream()) }
    }
}
