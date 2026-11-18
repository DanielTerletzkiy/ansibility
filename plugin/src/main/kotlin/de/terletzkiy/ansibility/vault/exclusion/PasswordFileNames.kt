package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.vault.reencrypt.AtomicFiles

/**
 * The file names of vault password files in one place (plan amendment R30, D224): the conventional names vault
 * discovery looks for (`VaultDiscoverer.CONVENTIONAL_NAMES`), the names the IDE always keeps out of its indexes, and
 * the rules for names the user adds (file names only, `*` and `?` as wildcards).
 */
object PasswordFileNames {
    /** The conventional password file names vault discovery looks for in a root (F7.9 step 4), in its order. */
    val CONVENTIONAL: List<String> = listOf(".vault-pass", ".vault_pass", ".vault-password")

    /**
     * Rotate Vault Password's temporary file (plan amendment R29, `AtomicFiles`): next to a password file it holds the new
     * password until it is moved over the file, and a crash can leave it behind (R30 review, unconfirmed 1).
     */
    const val ROTATION_TEMP: String = AtomicFiles.TEMP_PREFIX + "*" + AtomicFiles.TEMP_SUFFIX

    /**
     * The name patterns always kept out of the IDE's indexes: the conventional names, their underscore twin (D224) and
     * Rotate Vault Password's temporary file (R30 review fixes, decision 1).
     */
    val BUILT_IN: List<String> = CONVENTIONAL + ".vault_password" + ROTATION_TEMP

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
     * True when the file name [fileName] matches [pattern] as the platform's file name patterns match a name
     * (`FileNameMatcherFactory`): `*.ext` compares the extension ignoring case; otherwise `*` matches any characters, `?`
     * one character and everything else itself, ignoring case unless [caseSensitive] (the file system's rule, R30 review
     * fixes, decision 3).
     */
    fun matches(pattern: String, fileName: CharSequence, caseSensitive: Boolean = true): Boolean = NamePattern.of(pattern).matches(fileName, caseSensitive)

    /** True when [fileName] matches one of [patterns] ([matches]). */
    fun matchesAny(patterns: Collection<String>, fileName: CharSequence, caseSensitive: Boolean = true): Boolean =
        patterns.any { matches(it, fileName, caseSensitive) }
}

/**
 * One compiled file name pattern of [PasswordFileNames.matches]: an extension (`*.pw`, case-insensitive as the
 * platform's `ExtensionFileNameMatcher`), a wildcard pattern, or an exact name. Matching allocates nothing, because the
 * exclusion condition runs it for every file the IDE looks up below a content root.
 */
internal class NamePattern private constructor(val text: String, private val dotExtension: String?, private val wildcard: Boolean) {
    fun matches(name: CharSequence, caseSensitive: Boolean): Boolean = when {
        dotExtension != null -> StringUtil.endsWithIgnoreCase(name, dotExtension)
        wildcard -> glob(name, !caseSensitive)
        else -> name.length == text.length && StringUtil.equal(name, text, caseSensitive)
    }

    /** `*` any characters (also none), `?` one character, anything else itself; backtracks to the last `*` only. */
    private fun glob(name: CharSequence, ignoreCase: Boolean): Boolean {
        var p = 0
        var n = 0
        var star = -1
        var mark = 0
        while (n < name.length) {
            val c = if (p < text.length) text[p] else null
            when {
                c == '*' -> {
                    star = p++
                    mark = n
                }
                c != null && (c == '?' || c.equals(name[n], ignoreCase)) -> {
                    p++
                    n++
                }
                star >= 0 -> {
                    p = star + 1
                    n = ++mark
                }
                else -> return false
            }
        }
        while (p < text.length && text[p] == '*') p++
        return p == text.length
    }

    override fun toString(): String = text

    companion object {
        /** [pattern] compiled the way `FileNameMatcherFactoryImpl.createMatcher` sorts patterns. */
        fun of(pattern: String): NamePattern = when {
            pattern.startsWith("*.") && pattern.indexOf('*', 2) < 0 && pattern.indexOf('.', 2) < 0 && pattern.indexOf('?', 2) < 0 ->
                NamePattern(pattern, pattern.substring(1), wildcard = false)
            '*' in pattern || '?' in pattern -> NamePattern(pattern, null, wildcard = true)
            else -> NamePattern(pattern, null, wildcard = false)
        }
    }
}
