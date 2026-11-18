package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.vault.VaultIndex
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.identity.LocalVaultSourceAccess
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What the IDE keeps out of its indexes (plan amendment R30; R30 review fixes, decisions 1, 3 and 4), compiled once from
 * the entries of [IndexExclusionSettings]: name [patterns] (the built-in names, the names you typed, `.env.local` when
 * ticked) and exact [paths] (canonical absolute paths of the files added from the Vault tab, its notification or a
 * suggestion). Equal when the entries are, so the platform sees an unchanged exclusion as unchanged.
 *
 * [excludes] is the one rule everything uses (the exclusion condition, the stale-data walk, the Vault tab, the
 * suggestions): only a **file** that an entry names, never a directory, and never a file [KeepOutGuard] protects (a
 * vars or vault file, a script). Names follow the file system's case rule (case-insensitive on a case-insensitive file
 * system; `*.ext` always ignores case, as the platform's extension patterns do). Paths, names and patterns only.
 */
class KeepOutRules(val patterns: List<String>, paths: Collection<String>) {
    /** The exact files, as canonical absolute `/`-separated paths. */
    val paths: Set<String> = LinkedHashSet(paths)

    private val compiled: Array<NamePattern> = patterns.map(NamePattern::of).toTypedArray()

    /** The file names of [paths], so a path is built only for a file whose name is one of them. */
    private val pathNames: Array<String> = this.paths.map { it.substringAfterLast('/') }.distinct().toTypedArray()

    /**
     * True when the IDE keeps [file] out: a file (never a directory) an entry names that [KeepOutGuard] lets go. Cheap
     * unless an entry names the file; the platform calls it for every file it looks up below a content root. Any thread.
     */
    fun excludes(file: VirtualFile): Boolean = !file.isDirectory && names(file) && KeepOutGuard.canKeepOut(file)

    /** True when an entry names [file] (a pattern its name matches, or its exact path); the guard is not asked. */
    fun names(file: VirtualFile): Boolean {
        val name = file.nameSequence
        val caseSensitive = file.fileSystem.isCaseSensitive
        for (pattern in compiled) if (pattern.matches(name, caseSensitive)) return true
        if (pathNames.isEmpty() || pathNames.none { it.length == name.length && StringUtil.equal(name, it, caseSensitive) }) return false
        val path = pathOf(file)
        return if (caseSensitive) path in paths else paths.any { it.equals(path, ignoreCase = true) }
    }

    /** True when a name pattern matches the file name [name] (the paths are not asked), with the case rule given. */
    fun matchesName(name: CharSequence, caseSensitive: Boolean): Boolean = compiled.any { it.matches(name, caseSensitive) }

    override fun equals(other: Any?): Boolean = other is KeepOutRules && other.patterns == patterns && other.paths == paths

    override fun hashCode(): Int = 31 * patterns.hashCode() + paths.hashCode()

    override fun toString(): String = "KeepOutRules(${patterns.size} patterns, ${paths.size} paths)"

    companion object {
        /** The exact-path form of [file]: its canonical path (symbolic links resolved, as the VFS knows them). */
        fun pathOf(file: VirtualFile): String = file.canonicalPath ?: file.path

        /** True when [path] can be an exact-path entry: absolute, normalised, `/`-separated, no control character. */
        fun isValidPath(path: String): Boolean {
            if (path.isEmpty() || path.endsWith('/') || path.any { it.isISOControl() } || '\\' in path) return false
            return try {
                val parsed = Path.of(path)
                parsed.isAbsolute && FileUtil.toSystemIndependentName(parsed.normalize().toString()) == path
            } catch (_: InvalidPathException) {
                false
            }
        }
    }
}

/**
 * The files the IDE never keeps out of its indexes, whatever an entry says (R30 review fixes, decisions 1 and 4: "never
 * a directory, never a vars file"):
 * - a file Ansibility reads as vars or vault content: the inputs of the `ansible.vault` index by path (a YAML name, a
 *   `group_vars`, `host_vars`, `vars` or `defaults` file without one, a file below `files/`, `*.key`, `*.vault`), and a
 *   whole-file vault of any name (Rotate Vault Password finds those on the file system);
 * - a script (an executable file, as vault discovery decides it): Ansible runs it, and it holds no secret (D224).
 *
 * Only for files an entry names. The vault magic is read with `java.nio` (14 bytes, zeroed), never through the VFS, and
 * remembered per file until its time stamp or length changes; the executable bit is asked every time. Any thread.
 */
object KeepOutGuard {
    /** Why the IDE does not keep a file out although an entry names it. */
    enum class Reason {
        /** Ansibility reads it as a vars or vault file. */
        VARS_OR_VAULT,

        /** An executable file: a password script. */
        SCRIPT,
    }

    private class Head(val stamp: Long, val length: Long, val vault: Boolean)

    private val heads = ConcurrentHashMap<VirtualFile, Head>()
    private const val MAX_HEADS = 4096

    /** True when the IDE may keep [file] out: a file, not protected ([reasonNotToKeepOut] is null). */
    fun canKeepOut(file: VirtualFile): Boolean = !file.isDirectory && reasonNotToKeepOut(file) == null

    /** Why [file] is never kept out, or null when it may be. */
    fun reasonNotToKeepOut(file: VirtualFile): Reason? = when {
        isVarsOrVaultPath(file) || isWholeFileVault(file) -> Reason.VARS_OR_VAULT
        isScript(file) -> Reason.SCRIPT
        else -> null
    }

    /** A file the `ansible.vault` index reads by its path (and so the vars model, for the vars files among them). */
    fun isVarsOrVaultPath(file: VirtualFile): Boolean = VaultIndex.isYamlInput(PathFacts.of(file)) || VaultIndex.isVaultCandidate(file)

    /** An executable file, as vault discovery decides a password script (`VaultSourceAccess.isExecutable`). */
    fun isScript(file: VirtualFile): Boolean {
        if (!file.isInLocalFileSystem) return false
        val path = file.toNioPathOrNull() ?: return false
        return LocalVaultSourceAccess.isExecutable(path)
    }

    private fun isWholeFileVault(file: VirtualFile): Boolean {
        val stamp = file.timeStamp
        val length = file.length
        heads[file]?.takeIf { it.stamp == stamp && it.length == length }?.let { return it.vault }
        val vault = VaultEnvelopes.isWholeFileVault(file)
        if (heads.size >= MAX_HEADS) heads.clear()
        heads[file] = Head(stamp, length, vault)
        return vault
    }
}
