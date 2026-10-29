package de.terletzkiy.ansibility.semantics.vault

/**
 * A root's rule for new secret values (plan amendment R29, D218 rule 1): values whose last mapping key matches
 * [pattern] get [kind] of [length]. The pattern is a shell-style glob as a `case` arm reads it: `*` any characters
 * (none included), `?` one character, `[…]` one character of a set (ranges `a-z`, classes `[:alnum:]`, negated with
 * `[!…]` or `[^…]`), `\` before a character takes it literally, everything else literally and case-sensitive
 * (`vault_*_[Cc]ookie_secret`). A pattern that cannot be read ([isValidPattern]: an unclosed `[`, a trailing `\`, an
 * unknown class, or `(`, `)` and `|`, which only a shell's extended patterns use) matches nothing. A rule with the
 * pattern `*` is a default for every key. Rules are tried in their order; the first match wins ([firstMatch]).
 *
 * Not secret; shared with the project (`.idea/ansibility-vault.xml`), so a `data class`.
 */
data class GenerationRule(val pattern: String, val kind: GeneratorKind, val length: Int) {
    /** The pattern read once; null when it cannot be read. */
    private val glob: List<GlobPart>? = compile(pattern)

    /** The generator of this rule (hex lowercase, base64 padded, URL-safe base64 unpadded, the full symbol set). */
    val generator: SecretGenerator get() = SecretGenerator(kind, length.coerceIn(SecretGenerator.lengths(kind)))

    /** True when [pattern] can be read ([GenerationRule.isValidPattern]). */
    val isValidPattern: Boolean get() = glob != null

    /** True when [key] (the value's last mapping key; empty for a sequence item) matches [pattern]. */
    fun matches(key: String?): Boolean = glob?.let { matches(it, key.orEmpty()) } ?: false

    /** One character position of a glob: `*`, or a test for one character (`?`, a literal, a `[…]` set). */
    private sealed interface GlobPart {
        data object Star : GlobPart

        class One(val test: (Char) -> Boolean) : GlobPart
    }

    companion object {
        /** The default pattern: every key. */
        const val ANY: String = "*"

        /** The first of [rules] that matches [key], or null. */
        fun firstMatch(rules: List<GenerationRule>, key: String?): GenerationRule? = rules.firstOrNull { it.matches(key) }

        /** True when the shell-style glob [pattern] matches all of [text]; false when it cannot be read ([isValidPattern]). */
        fun globMatches(pattern: String, text: String): Boolean = compile(pattern)?.let { matches(it, text) } ?: false

        /**
         * True when [pattern] can be read as a glob of this class: every `[` closed, no trailing `\`, only known
         * `[:class:]` names, and no `(`, `)` or `|` (a shell's extended patterns, which rules do not support).
         */
        fun isValidPattern(pattern: String): Boolean = compile(pattern) != null

        /** True when [pattern] is only stars: a `case` arm's default (`*)`). */
        fun isCatchAll(pattern: String): Boolean = pattern.isNotEmpty() && pattern.all { it == '*' }

        /** Iterative matching with one backtrack point per star: linear backtracking, no regex. */
        private fun matches(glob: List<GlobPart>, text: String): Boolean {
            var p = 0
            var t = 0
            var star = -1
            var mark = 0
            while (t < text.length) {
                val part = glob.getOrNull(p)
                when {
                    part is GlobPart.One && part.test(text[t]) -> {
                        p++
                        t++
                    }
                    part == GlobPart.Star -> {
                        star = p++
                        mark = t
                    }
                    star >= 0 -> {
                        p = star + 1
                        t = ++mark
                    }
                    else -> return false
                }
            }
            while (p < glob.size && glob[p] == GlobPart.Star) p++
            return p == glob.size
        }

        /** [pattern] as glob parts, or null when it cannot be read. */
        private fun compile(pattern: String): List<GlobPart>? {
            val parts = ArrayList<GlobPart>()
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '*' -> parts += GlobPart.Star
                    '?' -> parts += GlobPart.One { true }
                    '\\' -> {
                        val literal = pattern.getOrNull(++i) ?: return null
                        parts += GlobPart.One { it == literal }
                    }
                    '[' -> {
                        val (test, end) = bracket(pattern, i) ?: return null
                        parts += GlobPart.One(test)
                        i = end
                    }
                    '(', ')', '|' -> return null
                    else -> parts += GlobPart.One { it == c }
                }
                i++
            }
            return parts
        }

        /**
         * The bracket expression that starts at [open] (`[`): its test and the index of its closing `]`; null when it
         * is not closed or names an unknown class. A `]` right after `[` (or `[!`, `[^`) is a member, as in a shell.
         */
        private fun bracket(pattern: String, open: Int): Pair<(Char) -> Boolean, Int>? {
            var i = open + 1
            val negated = pattern.getOrNull(i) == '!' || pattern.getOrNull(i) == '^'
            if (negated) i++
            val tests = ArrayList<(Char) -> Boolean>()
            var first = true
            while (true) {
                val c = pattern.getOrNull(i) ?: return null
                if (c == ']' && !first) break
                first = false
                if (c == '[' && pattern.getOrNull(i + 1) == ':') {
                    val close = pattern.indexOf(":]", i + 2)
                    if (close < 0) return null
                    tests += CLASSES[pattern.substring(i + 2, close)] ?: return null
                    i = close + 2
                    continue
                }
                val low = if (c == '\\') pattern.getOrNull(++i) ?: return null else c
                val dash = pattern.getOrNull(i + 1) == '-' && pattern.getOrNull(i + 2).let { it != null && it != ']' }
                if (dash) {
                    var high = pattern[i + 2]
                    i += 2
                    if (high == '\\') high = pattern.getOrNull(++i) ?: return null
                    tests += { it in low..high }
                } else {
                    tests += { it == low }
                }
                i++
            }
            val test: (Char) -> Boolean = { ch -> tests.any { it(ch) } != negated }
            return test to i
        }

        /** The POSIX character classes a `[…]` may name (ASCII, as in the C locale). */
        private val CLASSES: Map<String, (Char) -> Boolean> = mapOf(
            "alnum" to { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' },
            "alpha" to { c -> c in 'A'..'Z' || c in 'a'..'z' },
            "digit" to { c -> c in '0'..'9' },
            "lower" to { c -> c in 'a'..'z' },
            "upper" to { c -> c in 'A'..'Z' },
            "xdigit" to { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' },
            "word" to { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' },
            "punct" to { c -> c in '!'..'~' && !(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') },
            "space" to { c -> c == ' ' || c in '\t'..'\r' },
            "blank" to { c -> c == ' ' || c == '\t' },
            "cntrl" to { c -> c < ' ' || c == '\u007F' },
            "graph" to { c -> c in '!'..'~' },
            "print" to { c -> c in ' '..'~' },
        )
    }
}
