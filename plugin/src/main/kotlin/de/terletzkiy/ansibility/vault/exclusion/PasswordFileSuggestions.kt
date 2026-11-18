package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.identity.ConsentTarget
import de.terletzkiy.ansibility.vault.identity.DiscoveredIdentity
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.rotate.RotationSources
import java.nio.file.Path

/**
 * A password file name the settings page suggests keeping out of the indexes: the name, the password files of the
 * project with it ([paths], for display, below the project) and their exact-path entries ([files], canonical absolute
 * paths, what Add adds; R30 review fixes, decision 1).
 */
class PasswordFileSuggestion(val name: String, val paths: List<String>, val files: List<String>) {
    override fun toString(): String = "PasswordFileSuggestion($name, $paths)"
}

/**
 * The suggestions of Settings › Ansibility › Vault › Keep out of the IDE's indexes (plan amendment R30, D224): the
 * password files the project's vault configuration uses inside the project, from every root's discovery
 * (`VaultIdentityRegistry`): the password files of `vault_password_file` and `vault_identity_list`, the conventional
 * names, explicit settings, and the file a `.env.local` names: read (its password-file key only) when its consent still
 * holds, else the file discovery expects. Never password scripts (code, no secret), never a file the list keeps out
 * already and never one the IDE cannot keep out ([KeepOutGuard]: a vars or vault file). Grouped by name; Add adds each
 * file's exact path, so no other file of that name is kept out. No password file is read.
 */
object PasswordFileSuggestions {
    /** The suggestions of [project], by name. Blocking (it may read a consented `.env.local`): off the EDT, outside a read action. */
    fun compute(project: Project): List<PasswordFileSuggestion> {
        val settings = IndexExclusionSettings.getInstance()
        val registry = VaultIdentityRegistry.getInstance(project)
        val entries = runReadActionBlocking {
            registry.discoveries().flatMap { discovery ->
                discovery.identities.filter { it.plan is SecretPlan.PasswordFile || it.plan is SecretPlan.EnvLocal }.map { discovery to it }
            }
        }
        val sources = RotationSources(project)
        val paths = entries.flatMap { (discovery, identity) ->
            ProgressManager.checkCanceled()
            targetOf(sources, discovery, identity)?.let { listOfNotNull(it.declared, it.path) }.orEmpty()
        }.distinct()
        return runReadActionBlocking {
            filesInProject(project, paths)
                .filter { !settings.isKeptOut(it) && KeepOutGuard.canKeepOut(it) }
                .groupBy { it.name }
                .map { (name, ofName) ->
                    PasswordFileSuggestion(
                        name,
                        ofName.map { RootKeys.relativePath(project, it) ?: it.path }.distinct().sorted(),
                        ofName.map(KeepOutRules::pathOf).distinct(),
                    )
                }
                .sortedBy { it.name }
        }
    }

    /** The password file [identity] reads: its file, or the one a `.env.local` names (consented) or is expected to name. */
    private fun targetOf(sources: RotationSources, discovery: VaultDiscovery, identity: DiscoveredIdentity): ConsentTarget? =
        when (val plan = identity.plan) {
            is SecretPlan.PasswordFile -> plan.target
            is SecretPlan.EnvLocal -> sources.sourceOf(discovery, identity.label, identity, read = true).file ?: plan.expected
            else -> null
        }

    /** The regular files at [paths] below a content root of [project] (excluded or not). Read action. */
    fun filesInProject(project: Project, paths: Collection<Path>): List<VirtualFile> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        return paths.mapNotNull { path ->
            LocalFileSystem.getInstance().findFileByNioFile(path)?.takeIf { it.isValid && !it.isDirectory && fileIndex.getContentRootForFile(it, false) != null }
        }.distinct()
    }
}
