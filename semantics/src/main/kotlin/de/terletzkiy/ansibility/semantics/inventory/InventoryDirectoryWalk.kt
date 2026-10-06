package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.CoreVersion
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Read access to a tree of inventory files, by handle (a `VirtualFile` in the plugin, a `File` in tests).
 */
interface InventoryTree<F> {
    /** The last path component, as `os.listdir` returns it. */
    fun name(file: F): String

    /** True for a directory (symbolic links followed, as `os.path.isdir` does). */
    fun isDirectory(file: F): Boolean

    /** The entries of the directory [dir], in any order. */
    fun children(dir: F): List<F>
}

/**
 * Which entries of a directory inventory source ansible-core reads (`InventoryManager.parse_source`): the entries
 * of every directory in sorted order (`sorted(os.listdir())`, i.e. by code point, so `Zeta` comes before `alpha`),
 * subdirectories recursed at their sorted position, and every name skipped that the `IGNORED` regex finds:
 *
 * - always: names starting with `.`, and exactly `group_vars`, `host_vars` and `vars_plugins`;
 * - `inventory_ignore_patterns` (`[inventory] ignore_patterns`): regexes searched anywhere in the name;
 * - `inventory_ignore_extensions` (`[defaults] inventory_ignore_extensions` / `[inventory] ignore_extensions`):
 *   suffixes of the name, for files and directories alike. An explicit list replaces the default; the default
 *   depends on the target ansible-core (D62): before 2.19 it contains `.ini`, so `.ini` files inside an inventory
 *   directory are not read ([defaultIgnoreExtensions]).
 *
 * The rules apply to directory walks only: a file given as a source is read whatever its name.
 */
object InventoryDirectoryWalk {
    /**
     * The first ansible-core whose default `inventory_ignore_extensions` no longer contains `.ini` (spike S-L1:
     * 2.18.19, the last 2.18 release, skips `.ini` in a directory walk; 2.19.0 reads it; measured with the
     * `inventory-directory` and `cfg-empty-inventory` layout-oracle cases).
     */
    val INI_READ_SINCE = CoreVersion(2, 19, 0)

    /** `REJECT_EXTS + ['.orig', '.cfg', '.retry']`, the default from ansible-core 2.19 on. */
    private val DEFAULT_SINCE_2_19 = listOf(".pyc", ".pyo", ".swp", ".bak", "~", ".rpm", ".md", ".txt", ".rst", ".orig", ".cfg", ".retry")

    /** `REJECT_EXTS + ('.orig', '.ini', '.cfg', '.retry')`, the default before 2.19. */
    private val DEFAULT_BEFORE_2_19 = listOf(".pyc", ".pyo", ".swp", ".bak", "~", ".rpm", ".md", ".txt", ".rst", ".orig", ".ini", ".cfg", ".retry")

    private val ALWAYS_IGNORED = setOf("group_vars", "host_vars", "vars_plugins")

    /** The default `inventory_ignore_extensions` of [core]; null means the newest behaviour. */
    fun defaultIgnoreExtensions(core: CoreVersion?): List<String> =
        if (core != null && core < INI_READ_SINCE) DEFAULT_BEFORE_2_19 else DEFAULT_SINCE_2_19

    /** Why an entry was skipped. */
    enum class SkipReason {
        /** A leading `.`, or one of `group_vars`, `host_vars`, `vars_plugins`. */
        ALWAYS,

        /** An `inventory_ignore_patterns` regex was found in the name. */
        PATTERN,

        /** The name ends with an `inventory_ignore_extensions` entry. */
        EXTENSION,
    }

    /** A skipped entry; [rule] is the matching pattern or extension (null for [SkipReason.ALWAYS]). */
    data class Skipped<F>(val file: F, val reason: SkipReason, val rule: String?)

    /** The result of a walk: the files read, in read order, and every entry skipped on the way. */
    data class Walk<F>(val files: List<F>, val skipped: List<Skipped<F>>)

    /**
     * The settings that decide what a walk skips. [ignoreExtensions] null means the default of [core]; an explicit
     * list (even an empty one) replaces it. [ignorePatterns] are Python regexes; one Java cannot compile is listed in
     * [invalidPatterns] and ignored (ansible-core itself would fail to start).
     */
    class Options(
        val core: CoreVersion? = null,
        ignoreExtensions: List<String>? = null,
        ignorePatterns: List<String> = emptyList(),
    ) {
        val ignoreExtensions: List<String> = ignoreExtensions ?: defaultIgnoreExtensions(core)
        val ignorePatterns: List<String> = ignorePatterns
        private val compiled: List<Pair<String, Pattern?>> = ignorePatterns.map { it to compile(it) }
        val invalidPatterns: List<String> = compiled.filter { it.second == null }.map { it.first }

        /** Why [name] is skipped in a directory walk, or null when it is read. */
        fun skipReason(name: String): Pair<SkipReason, String?>? {
            if (name.startsWith(".") || name in ALWAYS_IGNORED) return SkipReason.ALWAYS to null
            for ((text, pattern) in compiled) if (pattern != null && pattern.matcher(name).find()) return SkipReason.PATTERN to text
            for (ext in ignoreExtensions) if (name.endsWith(ext)) return SkipReason.EXTENSION to ext
            return null
        }

        private fun compile(pattern: String): Pattern? = try {
            Pattern.compile(pattern)
        } catch (_: PatternSyntaxException) {
            null
        }
    }

    /** The entries of [dir] that a walk visits, in visiting order (directories included, not recursed). */
    fun <F> entries(dir: F, tree: InventoryTree<F>, options: Options, skipped: MutableList<Skipped<F>>? = null): List<F> {
        val out = ArrayList<F>()
        for (child in tree.children(dir).sortedWith(compareBy(Py.STRING_ORDER) { tree.name(it) })) {
            val skip = options.skipReason(tree.name(child))
            if (skip == null) out += child else skipped?.add(Skipped(child, skip.first, skip.second))
        }
        return out
    }

    /**
     * How deep a walk descends. ansible-core has no limit (a symbolic link loop ends in a `RecursionError`); the IDE
     * stops descending there instead.
     */
    const val MAX_DEPTH = 32

    /** Every file of the directory source [dir] that ansible-core reads, recursively, in read order. */
    fun <F> walk(dir: F, tree: InventoryTree<F>, options: Options = Options()): Walk<F> {
        val files = ArrayList<F>()
        val skipped = ArrayList<Skipped<F>>()
        fun visit(d: F, depth: Int) {
            for (entry in entries(d, tree, options, skipped)) {
                if (!tree.isDirectory(entry)) files += entry else if (depth < MAX_DEPTH) visit(entry, depth + 1)
            }
        }
        visit(dir, 0)
        return Walk(files, skipped)
    }
}
