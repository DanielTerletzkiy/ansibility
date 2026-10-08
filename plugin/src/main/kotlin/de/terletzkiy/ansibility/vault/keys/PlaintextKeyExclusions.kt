package de.terletzkiy.ansibility.vault.keys

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.monitor.SecretHealthService

/**
 * The files ANS-V108 never reports (plan amendment R21, D162): Ansibility's ignored paths and the allowlist of the
 * shared vault settings (`VaultProjectSettings.plaintextKeyAllowlist`, Molecule's scenario folders by default). The
 * inspection, the `ansibility.secrets` monitoring and the commit check share this one rule. Paths only; nothing is
 * read. Any thread.
 */
object PlaintextKeyExclusions {
    /** True when ANS-V108 skips [file]: an ignored path or an allowlisted one. */
    fun isExcluded(project: Project, file: VirtualFile): Boolean =
        AnsibilityProjectSettings.getInstance(project).isIgnored(file) || isAllowlisted(project, file)

    /**
     * True when [file] matches a glob of the allowlist: its path relative to the project directory, or its absolute
     * path for a file outside it.
     */
    fun isAllowlisted(project: Project, file: VirtualFile): Boolean {
        val globs = VaultProjectSettings.getInstance(project).plaintextKeyAllowlistGlobs()
        if (globs.isEmpty()) return false
        val path = RootKeys.relativePath(project, file) ?: file.path
        return globs.any { it.matches(path) }
    }

    /**
     * [isAllowlisted] for an absolute `/`-separated [path] without a file in the working tree (the commit check: a file
     * staged and then deleted): matched relative to the project directory, or by the absolute path outside it.
     */
    fun isAllowlistedPath(project: Project, path: String): Boolean {
        val globs = VaultProjectSettings.getInstance(project).plaintextKeyAllowlistGlobs()
        if (globs.isEmpty()) return false
        val matched = relativePath(project, path) ?: path
        return globs.any { it.matches(matched) }
    }

    /** True when the absolute [path] is one of Ansibility's ignored paths (relative to the project directory). */
    fun isIgnoredPath(project: Project, path: String): Boolean {
        val relative = relativePath(project, path) ?: return false
        return AnsibilityProjectSettings.getInstance(project).settings.paths.isIgnored(relative)
    }

    /** [path] relative to the project directory (`/`-separated), or null when it lies outside. */
    fun relativePath(project: Project, path: String): String? {
        val base = RootKeys.projectDir(project)?.path ?: return null
        if (path == base) return ""
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else null
    }

    /**
     * Replaces the allowlist with [globs] (trimmed, blank lines dropped) and, when it changed, re-highlights the project
     * and refreshes the Vault tab (`vault.monitor.SecretHealthService`), so ANS-V108 findings appear or go at once. Every
     * writer of the allowlist (the Vault settings page, the Vault tab's "Exclude Path…") goes through here.
     */
    fun setAllowlist(project: Project, globs: List<String>) {
        val settings = VaultProjectSettings.getInstance(project)
        val before = settings.plaintextKeyAllowlist
        settings.plaintextKeyAllowlist = globs
        if (settings.plaintextKeyAllowlist == before || project.isDisposed) return
        DaemonCodeAnalyzer.getInstance(project).restart(RESTART_REASON)
        SecretHealthService.getInstance(project).requestRefresh()
    }

    private const val RESTART_REASON = "Ansibility Vault: plaintext key allowlist changed"
}
