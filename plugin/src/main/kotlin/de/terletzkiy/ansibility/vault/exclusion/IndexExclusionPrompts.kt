package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message

/**
 * What "Keep out of the IDE's indexes" asks (plan amendment R30, D225, D227): whether to reopen the project now, so the
 * names just added apply, and the reopen itself. An application service, so tests replace it
 * (`ansibility-vault-core.xml`). EDT.
 */
interface IndexExclusionPrompts {
    /** Whether to reopen [project] now that [names] were added to the list. */
    fun askReopen(project: Project, names: List<String>): Boolean

    /** Closes and opens [project] again (the platform's Reload Project). */
    fun reopen(project: Project)

    companion object {
        fun getInstance(): IndexExclusionPrompts = service()
    }
}

/** The dialog of [IndexExclusionPrompts] and the platform's reload. */
class IndexExclusionDialogPrompts : IndexExclusionPrompts {
    override fun askReopen(project: Project, names: List<String>): Boolean = Messages.showYesNoDialog(
        project,
        message("exclusion.reopen.message", names.joinToString(", ")),
        message("exclusion.reopen.title"),
        message("exclusion.reopen.yes"),
        message("exclusion.reopen.no"),
        Messages.getQuestionIcon(),
    ) == Messages.YES

    override fun reopen(project: Project) {
        if (!project.isDisposed) ProjectManager.getInstance().reloadProject(project)
    }
}

/**
 * "Keep Out of the IDE's Indexes" of the Vault tab and its notification (plan amendment R30, D227; R30 review fixes,
 * decision 1): adds the exact paths of [perform]'s files to the list ([IndexExclusionSettings.addPaths]; only those
 * files, never another file or folder of the same name) and offers to reopen the project, since the list applies when a
 * project opens (D225). Files the IDE never keeps out ([KeepOutGuard]: folders, vars or vault files, scripts) are left
 * out. Paths and names only. EDT.
 */
object KeepOutOfIndexes {
    /** Adds the exact paths of the files of [files] the IDE may keep out and asks to reopen [project]; returns the paths added. */
    fun perform(project: Project, files: Collection<VirtualFile>): List<String> {
        val valid = files.filter { it.isValid && KeepOutGuard.canKeepOut(it) }.distinct()
        if (valid.isEmpty()) return emptyList()
        val added = IndexExclusionSettings.getInstance().addPaths(valid.map(KeepOutRules::pathOf))
        val prompts = IndexExclusionPrompts.getInstance()
        if (prompts.askReopen(project, valid.map { it.name }.distinct())) prompts.reopen(project)
        return added
    }
}
