package de.terletzkiy.ansibility.semantics.vault

import java.security.SecureRandom
import java.util.Base64

/**
 * The kinds of random value Regenerate Secrets… makes (plan amendment R29, D218). The length of a generator is counted
 * in characters, except for the two base64 kinds, where it is the number of random bytes before encoding (as in
 * `openssl rand -base64 32`).
 */
enum class GeneratorKind(
    /** True when the length counts random bytes (base64), false when it counts characters. */
    val lengthInBytes: Boolean,
) {
    /** `A–Z`, `a–z`, `0–9`. */
    ALPHANUMERIC(false),

    /** Hex digits, lowercase like `openssl rand -hex` (uppercase when the value it replaces was). */
    HEX(false),

    /** Standard base64 with `=` padding, of a number of random bytes. */
    BASE64(true),

    /** URL-safe base64 (`-` and `_`), of a number of random bytes; unpadded unless the value it replaces was padded. */
    BASE64_URL(true),

    /**
     * Alphanumeric plus symbols: `-_.!#%+=` ([SecretGenerator.SYMBOLS]) when you or a rule pick it, the symbols the
     * current value has when "same as now" keeps them ([SecretGenerator.symbols]); never starts with a symbol.
     */
    ALPHANUMERIC_SYMBOLS(false),
}

/**
 * One way to make a new secret value (plan amendment R29, D218): a [kind] and a [length] (characters, or random bytes
 * for the base64 kinds), plus the details "same as now" keeps from the value it replaces ([upperHex], [padded],
 * [symbols]).
 *
 * [generate] draws from an injected [SecureRandom] (tests pass a seeded one) without modulo bias: a random byte is
 * used only when it falls below the largest multiple of the alphabet size (rejection sampling), so every character is
 * equally likely. The value goes into a `ByteArray` (ASCII) that the caller zeroes; no `String` ever holds it, and the
 * random bytes behind it are zeroed before [generate] returns.
 *
 * **YAML and shells.** [GeneratorKind.ALPHANUMERIC_SYMBOLS] uses [symbols], by default exactly `-_.!#%+=`, and never
 * starts with a symbol: a plain YAML scalar must not start with `!` (a tag), `#` (a comment), `%` (a directive) or `-`
 * (a sequence entry), so a value that starts with a letter or digit stays safe even where it is written unquoted, and
 * none of the default symbols is special to a shell inside double quotes. "Same as now" narrows [symbols] to the ones
 * the current value has (plan amendment R29 review), so a password embedded in a URL gets no `#`, `%` or `!` it did not
 * have. The value itself is never written as YAML by the plugin: it is encrypted.
 *
 * Not secret (a recipe, not a value), so a `data class`; [toString] counts the symbols instead of listing them, since
 * a narrowed set says which symbols the old value had.
 */
data class SecretGenerator(
    val kind: GeneratorKind,
    val length: Int,
    /** [GeneratorKind.HEX] only: uppercase digits (`A–F`). */
    val upperHex: Boolean = false,
    /** [GeneratorKind.BASE64_URL] only: `=` padding at the end ([GeneratorKind.BASE64] is always padded). */
    val padded: Boolean = false,
    /** [GeneratorKind.ALPHANUMERIC_SYMBOLS] only: the symbols after the first character (printable ASCII, no letter or digit). */
    val symbols: String = SYMBOLS,
) {
    init {
        require(length in lengths(kind)) { "length $length out of range for $kind" }
        require(symbols.isNotEmpty() && symbols.all { it in '!'..'~' && !it.isLetterOrDigit() }) { "symbols must be printable ASCII punctuation" }
    }

    /** The number of characters [generate] returns. */
    val characters: Int
        get() = when (kind) {
            GeneratorKind.BASE64 -> 4 * ((length + 2) / 3)
            GeneratorKind.BASE64_URL -> if (padded) 4 * ((length + 2) / 3) else (length * 4 + 2) / 3
            else -> length
        }

    /** A new random value of this kind and length, as ASCII bytes; the caller zeroes them. */
    fun generate(random: SecureRandom): ByteArray = when (kind) {
        GeneratorKind.ALPHANUMERIC -> pick(ALPHANUMERIC_CHARS, ALPHANUMERIC_CHARS, length, random)
        GeneratorKind.HEX -> (if (upperHex) HEX_UPPER else HEX_LOWER).let { pick(it, it, length, random) }
        GeneratorKind.ALPHANUMERIC_SYMBOLS -> pick(ALPHANUMERIC_CHARS, if (symbols == SYMBOLS) SYMBOL_CHARS else ALPHANUMERIC_CHARS + ascii(symbols.toSet().toList()), length, random)
        GeneratorKind.BASE64 -> encoded(Base64.getEncoder(), random)
        GeneratorKind.BASE64_URL -> encoded(if (padded) Base64.getUrlEncoder() else Base64.getUrlEncoder().withoutPadding(), random)
    }

    override fun toString(): String =
        "SecretGenerator($kind, $length" + (if (upperHex) ", upperHex" else "") + (if (padded) ", padded" else "") +
            (if (kind == GeneratorKind.ALPHANUMERIC_SYMBOLS) ", ${symbols.length} symbols" else "") + ")"

    private fun encoded(encoder: Base64.Encoder, random: SecureRandom): ByteArray {
        val raw = ByteArray(length).also(random::nextBytes)
        try {
            return encoder.encode(raw)
        } finally {
            raw.fill(0)
        }
    }

    companion object {
        /** The most characters a generated value may have: longer values are no password (D217). */
        const val MAX_CHARACTERS: Int = 512

        /** The generator for plaintext placeholders and anything else (D218 rule 3, the rotation script's default). */
        val DEFAULT: SecretGenerator = SecretGenerator(GeneratorKind.ALPHANUMERIC, 32)

        /** The symbols of [GeneratorKind.ALPHANUMERIC_SYMBOLS]. */
        const val SYMBOLS: String = "-_.!#%+="

        private val ALPHANUMERIC_CHARS = ascii(('A'..'Z') + ('a'..'z') + ('0'..'9'))
        private val HEX_LOWER = ascii(('0'..'9') + ('a'..'f'))
        private val HEX_UPPER = ascii(('0'..'9') + ('A'..'F'))
        private val SYMBOL_CHARS = ALPHANUMERIC_CHARS + ascii(SYMBOLS.toList())

        /** The alphabet of [kind] (for tests and the settings page); the base64 kinds list their encoding alphabet. */
        fun alphabet(kind: GeneratorKind, upperHex: Boolean = false, symbols: String = SYMBOLS): String = when (kind) {
            GeneratorKind.ALPHANUMERIC -> String(ALPHANUMERIC_CHARS, Charsets.US_ASCII)
            GeneratorKind.HEX -> String(if (upperHex) HEX_UPPER else HEX_LOWER, Charsets.US_ASCII)
            GeneratorKind.ALPHANUMERIC_SYMBOLS -> String(ALPHANUMERIC_CHARS, Charsets.US_ASCII) + symbols.toSet().joinToString("")
            GeneratorKind.BASE64 -> String(ALPHANUMERIC_CHARS, Charsets.US_ASCII) + "+/="
            GeneratorKind.BASE64_URL -> String(ALPHANUMERIC_CHARS, Charsets.US_ASCII) + "-_="
        }

        /** The lengths [kind] accepts: up to [MAX_CHARACTERS] characters (384 random bytes for base64). */
        fun lengths(kind: GeneratorKind): IntRange = if (kind.lengthInBytes) 1..MAX_CHARACTERS / 4 * 3 else 1..MAX_CHARACTERS

        private fun ascii(chars: List<Char>): ByteArray = ByteArray(chars.size) { chars[it].code.toByte() }

        /**
         * [count] characters: the first from [first], the rest from [rest], each by rejection sampling over random
         * bytes (a byte at or above the largest multiple of the alphabet size is drawn again), so there is no modulo
         * bias. The random buffer is zeroed afterwards.
         */
        private fun pick(first: ByteArray, rest: ByteArray, count: Int, random: SecureRandom): ByteArray {
            val out = ByteArray(count)
            val buffer = ByteArray(64)
            var position = buffer.size
            try {
                for (i in 0 until count) {
                    val alphabet = if (i == 0) first else rest
                    val limit = 256 - 256 % alphabet.size
                    while (true) {
                        if (position == buffer.size) {
                            random.nextBytes(buffer)
                            position = 0
                        }
                        val b = buffer[position++].toInt() and 0xFF
                        if (b < limit) {
                            out[i] = alphabet[b % alphabet.size]
                            break
                        }
                    }
                }
            } finally {
                buffer.fill(0)
            }
            return out
        }
    }
}
