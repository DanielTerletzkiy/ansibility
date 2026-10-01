package de.terletzkiy.ansibility.vault.identity

import java.nio.file.Path

/**
 * Builds the [ConsentTarget] of a file source of one root: keyed by the file's real path, and named the way the user
 * knows it (root-relative inside the root, `~/…` inside the home directory, else absolute).
 *
 * When the file itself is a symbolic link, the name also shows where it points (`.vault-pass → ~/.ssh/id_rsa`): a
 * cloned repository can commit a link, and a consent must say what is really read. Links in the directories above
 * the file (macOS `/var` → `/private/var`) are not shown.
 */
class ConsentTargets(private val rootPath: Path?, home: String?, private val access: VaultSourceAccess) {
    private val root: Path? = rootPath?.toAbsolutePath()?.normalize()
    private val realRoot: Path? = root?.let(access::canonical)
    private val home: Path? = home?.let(VaultPaths::parse)?.toAbsolutePath()?.normalize()
    private val realHome: Path? = this.home?.let(access::canonical)

    /** The target for the file at [path]. */
    fun file(path: Path): ConsentTarget {
        val declared = path.toAbsolutePath().normalize()
        val canonical = access.canonical(declared) ?: declared
        val viaParent = declared.parent?.let(access::canonical)?.resolve(declared.fileName)
        val linked = viaParent != null && viaParent != canonical
        val display = if (linked) "${display(declared, root, home)} → ${display(canonical, realRoot, realHome)}" else display(declared, root, home)
        return ConsentTarget.file(canonical, declared, display)
    }

    private fun display(path: Path, root: Path?, home: Path?): String {
        if (root != null && path.startsWith(root) && path != root) return root.relativize(path).toString()
        if (home != null && path.startsWith(home) && path != home) return "~/" + home.relativize(path)
        return path.toString()
    }
}
