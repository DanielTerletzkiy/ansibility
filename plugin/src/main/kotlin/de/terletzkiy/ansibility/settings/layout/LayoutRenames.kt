package de.terletzkiy.ansibility.settings.layout

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.switching.ContextSwitcher
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings

/**
 * Follows an environment renamed in the Layout settings (F10.6): this user's stored context and the vault env → id
 * mapping move to the new name. Other users' stored selections report the old name as stale (`Selections`).
 */
internal object LayoutRenames {
    fun rename(project: Project, root: AnsibleRoot, old: String, new: String) {
        val current = AnsibleContextService.getInstance(project).selection(root)
        if ((current.environment as? EnvironmentChoice.Named)?.name == old) {
            ContextSwitcher.select(project, root, current.copy(environment = EnvironmentChoice.Named(new)))
        }
        val rootKey = VaultIdentityRegistry.getInstance(project).discovery(root).rootKey
        VaultProjectSettings.getInstance(project).renameEnvironment(rootKey, old, new)
    }
}
