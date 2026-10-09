package de.terletzkiy.ansibility.vault.monitor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.settings.PathGlob
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.keys.PlaintextKeyExclusions
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import javax.swing.Icon

/**
 * The actions of the Vault tab (plan amendment R21, D165), on the findings under the selected rows
 * ([SecretHealthPanel.SELECTED_FINDINGS]): Convert to Whole-File Vault (ANS-V107 files with a wrapped envelope),
 * Encrypt Files… (Ansibility Vault's Encrypt File, for key files Ansible reads as they are), Show in Project View, and
 * Exclude Path… (a glob for the plaintext key allowlist of the Vault settings page; ANS-V108 findings only). None of
 * them opens a file.
 */
object SecretTabActions {
    /** The tab's own actions, for its toolbar and context menu. */
    fun actions(): List<AnAction> = listOf(Convert(), Encrypt(), ShowInProjectView(), ExcludePath())

    /** An action of the tab: enabled for the selected findings it applies to. */
    abstract class TabAction(text: String, description: String?, icon: Icon?) : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.project != null && applicable(findings(e)).isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val findings = applicable(findings(e))
            if (findings.isNotEmpty()) perform(project, findings)
        }

        /** The findings of [selected] this action acts on. */
        abstract fun applicable(selected: List<SecretFinding>): List<SecretFinding>

        abstract fun perform(project: Project, findings: List<SecretFinding>)

        private fun findings(e: AnActionEvent): List<SecretFinding> = e.getData(SecretHealthPanel.SELECTED_FINDINGS).orEmpty()
    }

    /** Builds a new snapshot now. */
    class Refresh : DumbAwareAction(message("monitor.action.refresh"), null, AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            e.project?.let { SecretHealthService.getInstance(it).requestRefresh() }
        }
    }

    /** "Convert to Whole-File Vault" ([WholeFileConversions]). */
    class Convert : TabAction(message("monitor.action.convert"), message("monitor.action.convert.description"), AllIcons.Nodes.Padlock) {
        override fun applicable(selected: List<SecretFinding>) = selected.filter { it.fix == SecretFix.CONVERT }

        override fun perform(project: Project, findings: List<SecretFinding>) {
            WholeFileConversions.convert(project, findings.map { it.file }.distinct())
        }
    }

    /** "Encrypt Files…" (`VaultFileOperations.encrypt`: one confirmation for several files, the id question per root). */
    class Encrypt : TabAction(message("monitor.action.encrypt"), message("monitor.action.encrypt.description"), AllIcons.Ide.Readonly) {
        override fun applicable(selected: List<SecretFinding>) = selected.filter { it.fix == SecretFix.ENCRYPT }

        override fun perform(project: Project, findings: List<SecretFinding>) {
            VaultFileOperations.getInstance(project).encrypt(findings.map { it.file }.distinct())
        }
    }

    /** "Show in Project View": the first selected file. */
    class ShowInProjectView : TabAction(message("monitor.action.project.view"), null, AllIcons.General.Locate) {
        override fun applicable(selected: List<SecretFinding>) = selected.filter { it.file.isValid }.take(1)

        override fun perform(project: Project, findings: List<SecretFinding>) {
            SecretTabPrompts.getInstance().selectInProjectView(project, findings.first().file)
        }
    }

    /** "Exclude Path…": asks for a glob (the file, or the folder of several) and adds it to the plaintext key allowlist. */
    class ExcludePath : TabAction(message("monitor.action.exclude"), message("monitor.action.exclude.description"), AllIcons.Actions.Cancel) {
        override fun applicable(selected: List<SecretFinding>) =
            selected.filter { it.category.plaintextKeyCheck }

        override fun perform(project: Project, findings: List<SecretFinding>) {
            val glob = SecretTabPrompts.getInstance().askExcludeGlob(project, suggestion(project, findings)) ?: return
            if (glob.isBlank() || PathGlob.compile(glob)?.let(VaultProjectSettings::isTooBroad) != false) return
            val current = VaultProjectSettings.getInstance(project).plaintextKeyAllowlist
            PlaintextKeyExclusions.setAllowlist(project, current + glob)
        }
    }

    /**
     * The glob suggested for [findings]: the path of one file relative to the project directory (absolute outside it),
     * or the folder all of them are in with a trailing any-depth wildcard, when that folder lies within the one
     * repository (`SecretGroup.dir`) they share; otherwise the first file's path. Never a glob broader than a repository.
     */
    fun suggestion(project: Project, findings: List<SecretFinding>): String {
        fun pathOf(file: VirtualFile) = RootKeys.relativePath(project, file) ?: file.path
        val files = findings.map { it.file }.distinct()
        files.singleOrNull()?.let { return pathOf(it) }
        val group = findings.map { it.group }.distinct().singleOrNull() ?: return pathOf(files.first())
        val common = files.map { it.parent }.reduceOrNull { a, b -> if (a == null || b == null) null else VfsUtilCore.getCommonAncestor(a, b) }
            ?.takeIf { VfsUtilCore.isAncestor(group.dir, it, false) }
            ?: return pathOf(files.first())
        val base = pathOf(common)
        return if (base.isEmpty()) pathOf(files.first()) else "$base/**"
    }
}
