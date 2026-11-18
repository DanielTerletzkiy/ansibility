package de.terletzkiy.ansibility.vault.exclusion

/**
 * The file names of vault password files in one place (plan amendment R30, D224): the conventional names vault
 * discovery looks for (`VaultDiscoverer.CONVENTIONAL_NAMES`), the names the IDE always keeps out of its indexes, and
 * the rules for names the user adds (file names only, `*` and `?` as wildcards).
 */
object PasswordFileNames {
    /** The conventional password file names vault discovery looks for in a root (F7.9 step 4), in its order. */
    val CONVENTIONAL: List<String> = listOf(".vault-pass", ".vault_pass", ".vault-password")

    /** The names always kept out of the IDE's indexes: the conventional ones plus the underscore twin (D224). */
    val BUILT_IN: List<String> = CONVENTIONAL + ".vault_password"

    /** The repository convention file that names a password file; excluded only when you tick it (D228). */
    const val ENV_LOCAL: String = ".env.local"

    /** Why [name] cannot be a pattern of the list. */
    enum class Problem {
        /** Empty or blank. */
        EMPTY,

        /** It holds `/` or `\`: a path, not a file name. */
        PATH,

        /** `.` or `..`, or it holds `..`. */
        DOTS,

        /** Only wildcards and dots (`*`, `*.*`, `.*`): it would keep (nearly) every file out. */
        TOO_BROAD,

        /** A control character or a character the platform's patterns do not take (`;`). */
        CHARACTER,
    }

    /** The problem of the trimmed [name] as a pattern of the list, or null when it is a valid file name pattern. */
    fun problemOf(name: String): Problem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Problem.EMPTY
            '/' in trimmed || '\\' in trimmed -> Problem.PATH
            ".." in trimmed || trimmed == "." -> Problem.DOTS
            trimmed.any { it.isISOControl() || it == ';' } -> Problem.CHARACTER
            trimmed.none { it != '*' && it != '?' && it != '.' } -> Problem.TOO_BROAD
            else -> null
        }
    }

    /** True when [name] is a valid pattern ([problemOf] is null). */
    fun isValid(name: String): Boolean = problemOf(name) == null

    /**
     * True when the file name [fileName] matches [pattern], as the platform's exclusion patterns match a name: `*` any
     * characters, `?` one character, everything else literally (case-sensitive).
     */
    fun matches(pattern: String, fileName: String): Boolean {
        if ('*' !in pattern && '?' !in pattern) return pattern == fileName
        return regexOf(pattern).matches(fileName)
    }

    /** True when [fileName] matches one of [patterns]. */
    fun matchesAny(patterns: Collection<String>, fileName: String): Boolean = patterns.any { matches(it, fileName) }

    private fun regexOf(pattern: String): Regex = Regex(
        buildString {
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        },
    )
}
