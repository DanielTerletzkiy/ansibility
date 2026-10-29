package de.terletzkiy.ansibility.semantics.vault

/**
 * A root's rule for new secret values (plan amendment R29, D218 rule 1): values whose last mapping key matches
 * [pattern] get [kind] of [length]. The pattern is a shell-style glob: `*` any characters (none included), `?` one
 * character, everything else literally and case-sensitive (`vault_*_cookie_secret`). A rule with the pattern `*` is a
 * default for every key. Rules are tried in their order; the first match wins ([firstMatch]).
 *
 * Not secret; shared with the project (`.idea/ansibility-vault.xml`), so a `data class`.
 */
data class GenerationRule(val pattern: String, val kind: GeneratorKind, val length: Int) {
    /** The generator of this rule (hex lowercase, base64 padded, URL-safe base64 unpadded). */
    val generator: SecretGenerator get() = SecretGenerator(kind, length.coerceIn(SecretGenerator.lengths(kind)))

    /** True when [key] (the value's last mapping key; empty for a sequence item) matches [pattern]. */
    fun matches(key: String?): Boolean = globMatches(pattern, key.orEmpty())

    companion object {
        /** The default pattern: every key. */
        const val ANY: String = "*"

        /** The first of [rules] that matches [key], or null. */
        fun firstMatch(rules: List<GenerationRule>, key: String?): GenerationRule? = rules.firstOrNull { it.matches(key) }

        /** True when the shell-style glob [pattern] (`*`, `?`) matches all of [text]. Iterative, no regex, linear backtracking. */
        fun globMatches(pattern: String, text: String): Boolean {
            var p = 0
            var t = 0
            var star = -1
            var mark = 0
            while (t < text.length) {
                when {
                    p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) && pattern[p] != '*' -> {
                        p++
                        t++
                    }
                    p < pattern.length && pattern[p] == '*' -> {
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
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}
