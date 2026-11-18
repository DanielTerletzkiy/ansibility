package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
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
 * "Keep Out of the IDE's Indexes" of the Vault tab and its notification (plan amendment R30, D227): adds [perform]'s
 * names to the list ([IndexExclusionSettings.addNames]; a name kept out already stays as it is) and offers to reopen
 * the project, since the list applies when a project opens (D225). Names only. EDT.
 */
object KeepOutOfIndexes {
    /** Adds the valid ones of [names] and asks to reopen [project]; returns the names added. */
    fun perform(project: Project, names: Collection<String>): List<String> {
        val valid = names.map { it.trim() }.filter(PasswordFileNames::isValid).distinct()
        if (valid.isEmpty()) return emptyList()
        val added = IndexExclusionSettings.getInstance().addNames(valid)
        val prompts = IndexExclusionPrompts.getInstance()
        if (prompts.askReopen(project, valid)) prompts.reopen(project)
        return added
    }
}
