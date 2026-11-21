package ai.fanak.den.core.model

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** What the router needs from a GGUF header: the architecture and the shape of its KV cache. */
data class GgufInfo(
    val arch: String,
    val contextLength: Int? = null,
    val blockCount: Int? = null,
    val headCount: Int? = null,
    val headCountKv: Int? = null,
    val embeddingLength: Int? = null,
    val keyLength: Int? = null,
    val valueLength: Int? = null,
) {
    // KV cache per token of context, in bytes, as llama.cpp keeps it by default (F16 keys and values).
    val kvBytesPerToken: Long?
        get() {
            val layers = blockCount ?: return null
            val heads = headCount ?: return null
            val kvHeads = headCountKv ?: heads
            val k = keyLength ?: embeddingLength?.div(heads) ?: return null
            val v = valueLength ?: k
            return layers.toLong() * kvHeads * (k + v) * 2
        }
}

/**
 * Reads the key-value metadata at the start of a GGUF file, and stops at the tokenizer, before the large vocabulary
 * arrays, so a few kilobytes of a remote file (an HTTP range) are enough.
 */
object GgufHeader {
    fun read(input: InputStream): GgufInfo {
        val r = Reader(input)
        require(r.bytes(4).decodeToString() == "GGUF") { "not a GGUF file" }
        val version = r.u32()
        require(version in 2..3) { "GGUF version $version" }
        r.u64() // tensors
        val count = r.u64()
        val kv = HashMap<String, Any?>()
        var arch: String? = null
        try {
            for (i in 0 until count) {
                val key = r.string()
                if (key.startsWith("tokenizer.")) break
                val value = r.value(r.u32().toInt())
                if (key == "general.architecture") arch = value as? String
                kv[key] = value
            }
        } catch (e: EOFException) {
            // A range that ends inside the metadata: what was read is still usable.
        }
        val a = requireNotNull(arch) { "no general.architecture" }
        fun int(name: String): Int? = when (val v = kv["$a.$name"]) {
            is Number -> v.toInt()
            // Per-layer values (e.g. KV heads in some models): the largest sizes the cache.
            is List<*> -> v.filterIsInstance<Number>().maxOfOrNull { it.toInt() }
            else -> null
        }
        return GgufInfo(
            a, int("context_length"), int("block_count"), int("attention.head_count"), int("attention.head_count_kv"),
            int("embedding_length"), int("attention.key_length"), int("attention.value_length"),
        )
    }

    // Little-endian, as GGUF stores everything.
    private class Reader(input: InputStream) {
        private val d = DataInputStream(input)

        fun bytes(n: Int) = ByteArray(n).also { d.readFully(it) }
        fun u32(): Long = Integer.toUnsignedLong(Integer.reverseBytes(d.readInt()))
        fun u64(): Long = java.lang.Long.reverseBytes(d.readLong())

        fun string(): String {
            val n = u64()
            if (n < 0 || n > 1 shl 20) throw EOFException("string of $n bytes")
            return bytes(n.toInt()).decodeToString()
        }

        fun value(type: Int): Any? = when (type) {
            0 -> d.readUnsignedByte()
            1 -> d.readByte().toInt()
            2 -> java.lang.Short.reverseBytes(d.readShort()).toInt() and 0xFFFF
            3 -> java.lang.Short.reverseBytes(d.readShort()).toInt()
            4 -> u32()
            5 -> Integer.reverseBytes(d.readInt())
            6 -> java.lang.Float.intBitsToFloat(Integer.reverseBytes(d.readInt()))
            7 -> d.readByte().toInt() != 0
            8 -> string()
            9 -> {
                val t = u32().toInt()
                val n = u64()
                if (n < 0 || n > 1 shl 24) throw EOFException("array of $n")
                List(n.toInt()) { value(t) }
            }
            10 -> u64()
            11 -> java.lang.Long.reverseBytes(d.readLong())
            12 -> java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(d.readLong()))
            else -> throw IllegalArgumentException("GGUF value type $type")
        }
    }
}
