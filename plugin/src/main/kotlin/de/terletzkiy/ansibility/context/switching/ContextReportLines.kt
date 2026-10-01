package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.ContextPresentation.Detail
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.settings.RootKeys
import org.jetbrains.annotations.Nls

/**
 * The host-context lines of X75 "Show Ansible Context" (plan amendment R7/R8, F8.1), labelled like the rest of the
 * report:
 *
 * - `Selection: env prod · host prod-prod1 · play auto` (and the stored selection's problem, when it has one);
 * - `Applies to: role postfix → 4 hosts in ops, prod, test via playbook-setup-system.yml › System` (the file scope);
 * - `Effective scope: 1 host × 1 play`, or why it is empty, or that the file scope overrides the selection;
 * - `Playbook dir: repos/falcon/ansible`, and for plays that run elsewhere `danger_zone/database · does not load …`;
 * - for a variable, `Effective on prod-prod1: group_vars/all/vars.yml:156 (L5)`, or in All mode the grouped outcome
 *   count `Effective: 1 value on 5 hosts`.
 *
 * The X75 report (`dispatch.AnsibleContextReport`) appends them; they follow the selection like every presentation
 * feature (`hostScope`), never decrypt and never run a process.
 */
object ContextReportLines {
    /**
     * The lines for [file] at [offset] (null: file level) and the variable [variable] named at the caret, or none
     * outside every root and while indexing. Needs a read lock.
     */
    fun lines(project: Project, file: VirtualFile, offset: Int?, variable: String?): List<Detail> {
        if (DumbService.isDumb(project)) return emptyList()
        if (AnsibleWorkspace.getInstance(project).rootFor(file) == null) return emptyList()
        val service = AnsibleContextService.getInstance(project)
        val scope = service.hostScope(file, offset ?: -1)
        val root = scope.root
        val choices = ContextChoices(project)
        val lines = ArrayList<Detail>()
        val playLabel = scope.selection.play?.let { choices.playLabel(root, it) }
        lines += line("report.selection", ContextTexts.selectionLine(scope.selection, playLabel))
        choices.problem(root)?.let { lines += line("report.selection.problem", it) }
        lines += line("report.applies.to", ContextTexts.appliesTo(scope))
        lines += line("report.effective.scope", effectiveScope(scope))
        playbookDirs(project, scope)?.let { lines += line("report.playbook.dir", it) }
        if (variable != null) {
            ProgressManager.checkCanceled()
            lines += effectiveLine(project, service, scope, variable)
        }
        return lines
    }

    /** `1 host × 1 play`, `4 hosts × inventory only`, the empty reason, or the override note. */
    @Nls
    private fun effectiveScope(scope: HostScope): String {
        if (scope.targets.isEmpty()) return scope.emptyReason ?: ContextTexts.message("report.effective.scope.empty")
        val plays = scope.targets.mapNotNull { it.play }.distinct().size
        val playText = if (plays == 0) ContextTexts.message("report.inventory.only") else ContextTexts.message("count.plays", plays)
        val value = ContextTexts.message("report.effective.scope.value", ContextTexts.hostCount(scope.hosts.size), playText)
        return if (scope.overriddenSelection) ContextTexts.message("report.effective.scope.overridden", value) else value
    }

    /** The distinct playbook dirs of the scope's targets, each flagged when it does not load the root's playbook-level vars. */
    private fun playbookDirs(project: Project, scope: HostScope): String? {
        val dirs = scope.targets.mapNotNull { it.playbookDir }.distinct()
        if (dirs.isEmpty()) return null
        val owner = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(scope.root) ?: scope.root
        val base = RootKeys.projectDir(project)
        val ownerVars = owner.dir.children.orEmpty().filter { it.isDirectory && it.name in PLAYBOOK_VARS_DIRS }
        return ContextTexts.names(
            dirs.map { dir ->
                val label = base?.let { RootKeys.relativePath(it, dir) }?.ifEmpty { dir.name } ?: dir.presentableUrl
                if (dir == owner.dir || ownerVars.isEmpty()) {
                    label
                } else {
                    val missing = ownerVars.joinToString(", ") { "${owner.dir.name}/${it.name}" }
                    ContextTexts.message("report.playbook.dir.not.loading", label, missing)
                }
            },
        )
    }

    /** `Effective on prod-prod1: group_vars/all/vars.yml:156 (L5)` or `Effective: 2 values on 5 hosts`. */
    private fun effectiveLine(project: Project, service: AnsibleContextService, scope: HostScope, name: String): Detail {
        val breakdown = service.effective(scope, name)
        val groups = breakdown.groups
        val hosts = groups.flatMap { it.hosts }
        val single = groups.singleOrNull()?.takeIf { it.hosts.size == 1 && breakdown.undefinedOn.isEmpty() }
        if (single != null) {
            val winner = single.winner
            val value = if (winner == null) ContextTexts.message("report.effective.undefined") else source(project, scope.root, winner)
            return Detail(ContextTexts.message("report.effective.on", single.hosts.single().host), value)
        }
        val values = groups.count { it.winner != null }
        val parts = mutableListOf(ContextTexts.message("report.effective.values", values, ContextTexts.hostCount(hosts.size)))
        if (breakdown.undefinedOn.isNotEmpty()) {
            parts += ContextTexts.message("report.effective.undefined.on", ContextTexts.hostNames(breakdown.undefinedOn))
        }
        return line("report.effective", parts.joinToString(ContextTexts.SEPARATOR))
    }

    /**
     * `group_vars/all/vars.yml:156 (L5)`: the source relative to [root]'s inventory root (the project directory for a
     * source outside it), with its 1-based line and level.
     */
    private fun source(project: Project, root: AnsibleRoot, ref: VarSourceRef): String {
        val owner = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(root) ?: root
        val path = RootKeys.relativePath(owner.dir, ref.file) ?: RootKeys.relativePath(project, ref.file) ?: ref.file.name
        val document = FileDocumentManager.getInstance().getCachedDocument(ref.file)
        val line = if (document != null && ref.offset <= document.textLength) {
            document.getLineNumber(ref.offset)
        } else {
            StringUtil.offsetToLineNumber(VfsUtilCore.loadText(ref.file), ref.offset)
        }
        return ContextTexts.message("report.source", "$path:${line + 1}", ref.layer.level)
    }

    private fun line(labelKey: String, @Nls value: String) = Detail(ContextTexts.message(labelKey), value)

    private val PLAYBOOK_VARS_DIRS = setOf(AnsibleLayout.GROUP_VARS, AnsibleLayout.HOST_VARS)
}
