package de.terletzkiy.ansibility.settings

/**
 * A `/`-separated path glob, as used by the ignored-paths list and the outer-language rules.
 *
 * - `**` matches any number of path segments (including none): `**` + `/.ansible/` + `**` matches `.ansible/x` and
 *   `golden/.ansible/collections/y`; `patches/` + `**` matches `patches` itself and everything below it.
 * - `*` matches any characters except `/`, `?` exactly one such character.
 * - Everything else matches literally (case-sensitive, like the file systems the repos live on).
 *
 * Paths are compared without leading or trailing `/`.
 */
class PathGlob private constructor(val pattern: String, private val regex: Regex) {

    /** True when the pattern contains a `/`, i.e. it describes a path rather than a file name. */
    val isPathPattern: Boolean
        get() = '/' in pattern

    /** Whether [path] (relative, `/`-separated) matches this glob. */
    fun matches(path: String): Boolean = regex.matches(path.trim('/'))

    override fun toString(): String = pattern

    override fun equals(other: Any?): Boolean = other is PathGlob && other.pattern == pattern

    override fun hashCode(): Int = pattern.hashCode()

    companion object {
        /** Compiles [pattern]; returns null for a blank pattern. */
        fun compile(pattern: String): PathGlob? {
            val normalized = pattern.trim().trim('/')
            if (normalized.isEmpty()) return null
            return PathGlob(normalized, Regex(toRegex(normalized)))
        }

        private fun toRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    glob.startsWith("**/", i) -> {
                        out.append("(?:.*/)?")
                        i += 3
                        continue
                    }
                    glob.startsWith("/**", i) && i + 3 == glob.length -> {
                        out.append("(?:/.*)?")
                        i += 3
                        continue
                    }
                    glob.startsWith("**", i) -> {
                        out.append(".*")
                        i += 2
                        continue
                    }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return out.toString()
        }
    }
}
