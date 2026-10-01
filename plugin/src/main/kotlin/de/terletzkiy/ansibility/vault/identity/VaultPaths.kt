package de.terletzkiy.ansibility.vault.identity

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Path rules for password sources named outside Ansible's configuration (settings, `.env.local`). */
internal object VaultPaths {
    /** [path] as a [Path], or null when it is not a valid path. */
    fun parse(path: String): Path? = try {
        Paths.get(path)
    } catch (_: InvalidPathException) {
        null
    }

    /**
     * [value] as the shell and Ansible read it from a root: `~` and `~/…` expand to [home], an absolute path stays,
     * anything else resolves against [base] (null: unresolvable). The result is normalised.
     */
    fun resolve(value: String, base: Path?, home: String?): Path? {
        val expanded = when {
            value == "~" && home != null -> home
            value.startsWith("~/") && home != null -> home.trimEnd('/') + value.substring(1)
            else -> value
        }
        val path = parse(expanded) ?: return null
        if (path.isAbsolute) return path.normalize()
        return base?.resolve(path)?.normalize()
    }
}
