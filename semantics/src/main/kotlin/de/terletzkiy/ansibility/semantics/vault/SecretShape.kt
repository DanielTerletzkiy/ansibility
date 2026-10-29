package de.terletzkiy.ansibility.semantics.vault

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Why a current value is not treated as a password (plan amendment R29, D217): its row starts unchecked. */
enum class NotPasswordReason {
    /** Nothing at all. */
    EMPTY,

    /** Bytes that are not UTF-8 (a binary value). */
    NOT_UTF8,

    /** A line break: several lines, or a value that ends with one. */
    SEVERAL_LINES,

    /** A PEM block (`-----BEGIN …`): a key or a certificate. */
    PEM_BLOCK,

    /** A JSON or YAML document or flow collection (`{…}`, `[…]`, `---`). */
    STRUCTURED,

    /** Spaces or TABs: a passphrase or a sentence, not a generated value. */
    SPACES,

    /** Control characters other than line breaks and TABs. */
    CONTROL_CHARACTERS,

    /** Longer than [SecretShape.MAX_CHARACTERS] characters. */
    TOO_LONG,
}

/**
 * The shape of a current secret value (plan amendment R29, D218 rule 2, "same as now"): the generator that makes a new
 * value of the same alphabet and length, or why the value does not look like a password at all. Computed from the
 * plaintext bytes without ever turning them into a `String`; the result holds no part of the value.
 *
 * Precedence (first match wins), on a value that is UTF-8, one line, at most 512 characters, no PEM block, no JSON or
 * YAML collection, without spaces and control characters:
 * 1. **hex**: only hex digits, at least one letter `a–f`, and the letters all lowercase or all uppercase (the case is
 *    kept; odd lengths too). An all-digit value is not hex: it falls to alphanumeric (a random hex value is all digits
 *    with negligible probability, a PIN-like value is not hex);
 * 2. **base64**: only `A–Z a–z 0–9 + /` with at most two `=` at the end, a length that is a multiple of 4, and at least
 *    one `+`, `/` or `=` (otherwise it is plain alphanumeric); the length kept is the decoded byte count, so a 44-char
 *    value ending in `=` is base64 of 32 bytes (`openssl rand -base64 32`);
 * 3. **URL-safe base64**: only `A–Z a–z 0–9 - _` (padding as above, then a multiple of 4; unpadded any length that
 *    is not 1 more than a multiple of 4) with at least one `-` or `_`; the byte count and the padding are kept;
 * 4. **alphanumeric**: only `A–Z a–z 0–9`;
 * 5. **alphanumeric with symbols**: anything else printable (the new value uses `-_.!#%+=`, length kept).
 *
 * A value shorter than [SHORT_BELOW] characters is marked short but keeps its length (an 8-character VRRP password
 * must stay 8, D217).
 */
sealed interface SecretShape {
    /** A password: [generator] makes a value of the same shape; [short] when it has fewer than [SHORT_BELOW] characters. */
    class Password(val generator: SecretGenerator, val short: Boolean) : SecretShape {
        override fun toString(): String = "Password($generator, short=$short)"
    }

    /** Not a password, because of [reason]. */
    class NotPassword(val reason: NotPasswordReason) : SecretShape {
        override fun toString(): String = "NotPassword($reason)"
    }

    companion object {
        /** Values longer than this are no password (D217). */
        const val MAX_CHARACTERS: Int = SecretGenerator.MAX_CHARACTERS

        /** Values shorter than this are marked short (D217). */
        const val SHORT_BELOW: Int = 16

        /** The shape of [plaintext] (not modified; the decoded characters are zeroed before this returns). */
        fun of(plaintext: ByteArray): SecretShape {
            if (plaintext.isEmpty()) return NotPassword(NotPasswordReason.EMPTY)
            val chars = decode(plaintext) ?: return NotPassword(NotPasswordReason.NOT_UTF8)
            try {
                return classify(chars)
            } finally {
                chars.fill('\u0000')
            }
        }

        private fun classify(c: CharArray): SecretShape {
            val n = c.size
            var start = 0
            while (start < n && c[start].isWhitespace()) start++
            if (startsWith(c, start, "-----BEGIN")) return NotPassword(NotPasswordReason.PEM_BLOCK)
            if (c.any { it == '\n' || it == '\r' }) return NotPassword(NotPasswordReason.SEVERAL_LINES)
            if (n > MAX_CHARACTERS) return NotPassword(NotPasswordReason.TOO_LONG)
            if (start < n && (c[start] == '{' || c[start] == '[') || startsWith(c, start, "---")) return NotPassword(NotPasswordReason.STRUCTURED)
            if (c.any { it == ' ' || it == '\t' || it == ' ' }) return NotPassword(NotPasswordReason.SPACES)
            if (c.any { it < ' ' || it == '\u007F' || it in '\u0080'..'\u009F' }) return NotPassword(NotPasswordReason.CONTROL_CHARACTERS)
            val short = n < SHORT_BELOW
            return Password(generatorFor(c), short)
        }

        private fun generatorFor(c: CharArray): SecretGenerator {
            val n = c.size
            hex(c)?.let { upper -> return SecretGenerator(GeneratorKind.HEX, n, upperHex = upper) }
            val pads = c.reversed().takeWhile { it == '=' }.size
            val body = n - pads
            if (body > 0 && pads <= 2 && (0 until body).all { c[it].isAlnum() || c[it] == '+' || c[it] == '/' }) {
                val marked = pads > 0 || (0 until body).any { c[it] == '+' || c[it] == '/' }
                if (marked && n % 4 == 0) return SecretGenerator(GeneratorKind.BASE64, n / 4 * 3 - pads)
            }
            if (body > 0 && pads <= 2 && (0 until body).all { c[it].isAlnum() || c[it] == '-' || c[it] == '_' } && (0 until body).any { c[it] == '-' || c[it] == '_' }) {
                if (pads > 0 && n % 4 == 0) return SecretGenerator(GeneratorKind.BASE64_URL, n / 4 * 3 - pads, padded = true)
                if (pads == 0 && n % 4 != 1) return SecretGenerator(GeneratorKind.BASE64_URL, n * 3 / 4, padded = false)
            }
            if (c.all { it.isAlnum() }) return SecretGenerator(GeneratorKind.ALPHANUMERIC, n)
            return SecretGenerator(GeneratorKind.ALPHANUMERIC_SYMBOLS, n)
        }

        /** Null when [c] is not hex as [SecretShape] defines it, else whether its letters are uppercase. */
        private fun hex(c: CharArray): Boolean? {
            var lower = false
            var upper = false
            for (ch in c) {
                when (ch) {
                    in '0'..'9' -> Unit
                    in 'a'..'f' -> lower = true
                    in 'A'..'F' -> upper = true
                    else -> return null
                }
            }
            return when {
                lower && upper -> null
                upper -> true
                lower -> false
                else -> null
            }
        }

        private fun Char.isAlnum(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

        private fun startsWith(c: CharArray, from: Int, prefix: String): Boolean =
            c.size - from >= prefix.length && prefix.indices.all { c[from + it] == prefix[it] }

        /** [bytes] as UTF-8 characters in an array the caller zeroes, or null when they are not UTF-8. */
        private fun decode(bytes: ByteArray): CharArray? {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer: CharBuffer = try {
                decoder.decode(ByteBuffer.wrap(bytes))
            } catch (_: CharacterCodingException) {
                return null
            }
            val chars = CharArray(buffer.remaining()).also { buffer.get(it) }
            if (buffer.hasArray()) buffer.array().fill('\u0000')
            return chars
        }
    }
}
