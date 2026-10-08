package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ContextWidgetSegment
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.WidgetSegment
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl

/**
 * The `ansibilityFileScope` status-bar segment (plan amendment R7/R8, F8.1): `file: postfix → 4 hosts` when the
 * selected file narrows the Ansible context to the hosts it applies to (role files and templates, inventory files,
 * playbooks, molecule scenarios), nothing for files that follow the selection. Its tooltip is the "applies to" line,
 * and in the popup it adds that line plus "Make … the Ansible context" when the file's hosts form one environment
 * or one host that is not the selection.
 *
 * Read on the widget's background refresh: one cached `hostScope` lookup (file level), nothing while indexing.
 */
class FileScopeSegment : ContextWidgetSegment {
    override fun segment(project: Project, file: VirtualFile): WidgetSegment? {
        if (DumbService.isDumb(project)) return null
        if (AnsibleWorkspace.getInstance(project).rootFor(file) == null) return null
        // Plan amendment R20, D156: a role library's Molecule hosts show only while Molecule is shown in navigation.
        val scope = AnsibleContextServiceImpl.getInstance(project)?.cardScope(file) ?: AnsibleContextService.getInstance(project).hostScope(file)
        val text = ContextTexts.fileSegment(scope) ?: return null
        val appliesTo = ContextTexts.message("segment.file.tooltip", ContextTexts.appliesTo(scope))
        val tooltip = scope.emptyReason?.let { ContextTexts.message("segment.file.tooltip.empty", appliesTo, it) } ?: appliesTo
        val actions = ArrayList<AnAction>()
        actions += InfoAction(tooltip)
        if (scope.overriddenSelection) actions += InfoAction(ContextBanners.overrideText(scope, followEditor = true))
        narrowestTarget(scope)?.takeUnless { it.isSelected(scope.selection) }?.let { actions += MakeContextAction(it) }
        return WidgetSegment(text, tooltip, actions)
    }

    /** "Make prod-prod1 the Ansible context" from the status-bar popup. */
    private class MakeContextAction(private val target: ContextTarget) : DumbAwareAction() {
        init {
            plainText(ContextTexts.message("banner.make.context", target.label))
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            ContextSwitcher.use(e.project ?: return, target)
        }
    }

    companion object {
        /** The registration id (the widget's segment order: built-in, this, `ansibilityVault`, `ansibilityWorkspaceScope`). */
        const val ID: String = "ansibilityFileScope"

        /**
         * The one environment or host [scope]'s file applies to, as a context to switch to: its host when the file
         * applies to exactly one, its environment when all its hosts are in one (molecule scenarios are left out),
         * else null.
         */
        fun narrowestTarget(scope: HostScope): ContextTarget? {
            val hosts = scope.fileHosts.ifEmpty { scope.hosts }.filterNot { it.isMolecule }
            if (hosts.isEmpty()) return null
            val environment = hosts.map { it.environment }.distinct().singleOrNull() ?: return null
            return ContextTarget(scope.root, environment, hosts.map { it.host }.distinct().singleOrNull())
        }
    }
}
