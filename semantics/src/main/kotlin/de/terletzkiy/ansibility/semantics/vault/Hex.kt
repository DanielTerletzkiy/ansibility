package de.terletzkiy.ansibility.semantics.vault

/** Strict hex as Python's `binascii.hexlify` / `unhexlify` do it: even length, `[0-9a-fA-F]` only, no whitespace. */
internal object Hex {
    sealed interface Decoded {
        class Bytes(val bytes: ByteArray) : Decoded

        data class Error(val reason: FormatReason) : Decoded
    }

    private val ODD = Decoded.Error(FormatReason.ODD_LENGTH)
    private val NON_HEX = Decoded.Error(FormatReason.NON_HEX_DIGIT)
    private const val DIGITS = "0123456789abcdef"

    /** `unhexlify(text)`: the odd-length check comes first, as in CPython. */
    fun decode(text: CharSequence): Decoded {
        if (text.length % 2 != 0) return ODD
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = digit(text[2 * i].code)
            val lo = digit(text[2 * i + 1].code)
            if (hi < 0 || lo < 0) return NON_HEX
            out[i] = (hi shl 4 or lo).toByte()
        }
        return Decoded.Bytes(out)
    }

    /** `unhexlify(bytes[from until to])`. */
    fun decode(bytes: ByteArray, from: Int, to: Int): Decoded {
        val length = to - from
        if (length % 2 != 0) return ODD
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            val hi = digit(bytes[from + 2 * i].toInt() and 0xFF)
            val lo = digit(bytes[from + 2 * i + 1].toInt() and 0xFF)
            if (hi < 0 || lo < 0) return NON_HEX
            out[i] = (hi shl 4 or lo).toByte()
        }
        return Decoded.Bytes(out)
    }

    fun encodeLower(bytes: ByteArray): String = appendLower(bytes, StringBuilder(bytes.size * 2)).toString()

    fun appendLower(bytes: ByteArray, out: StringBuilder): StringBuilder {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return out
    }

    private fun digit(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }
}
