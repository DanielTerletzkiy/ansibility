package de.terletzkiy.ansibility.workspace.actions

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.workspace.AnsibilityScopeBundle
import de.terletzkiy.ansibility.workspace.ProjectViewTarget
import de.terletzkiy.ansibility.workspace.RootCoverage
import de.terletzkiy.ansibility.workspace.ScopeCoverage
import de.terletzkiy.ansibility.workspace.ScopeUi
import de.terletzkiy.ansibility.workspace.WorkspaceScopeImpl
import de.terletzkiy.ansibility.workspace.WorkspaceScopeServiceImpl
import org.jetbrains.annotations.Nls

/**
 * What the scope actions do, shared by the registered actions (Find Action) and the selector popup's items, plus
 * the texts both show.
 */
object ScopeCommands {
    /** At most this many root names are spelled out in a coverage text before "+ n more". */
    private const val NAMED_ROOTS = 3

    /** Opens "Choose roots…" with the current choice's roots checked and stores the result. EDT. */
    fun chooseRoots(project: Project) {
        val service = WorkspaceScopeServiceImpl.getInstance(project) ?: return
        val options = service.rootOptions()
        val scope = service.currentScope()
        val initial = when (val choice = scope.choice) {
            is ScopeChoice.Roots -> choice.keys
            ScopeChoice.AllRoots -> options.mapTo(HashSet()) { it.key }
            ScopeChoice.CurrentFileRoot -> scope.chosenRoots.mapTo(HashSet()) { RootKeys.keyOf(project, it.dir) }
            is ScopeChoice.Named -> scope.coverageIfComputed()?.roots.orEmpty().mapTo(HashSet()) { RootKeys.keyOf(project, it.dir) }
        }
        val chosen = ScopeUi.getInstance().chooseRoots(project, options, initial) ?: return
        if (chosen.isNotEmpty()) service.set(ScopeChoice.Roots(chosen))
    }

    /** Opens the platform's Scopes settings on the current named scope when it is a user scope. EDT. */
    fun editScopes(project: Project) {
        ScopeUi.getInstance().editScopes(project, editableScopeName(project))
    }

    /** The user scope "Edit Scopes…" preselects: the current named scope when its holder lets the user edit it. */
    fun editableScopeName(project: Project): String? {
        val scope = WorkspaceScopeServiceImpl.getInstance(project)?.currentScope() ?: return null
        val named = scope.namedScope ?: return null
        val holder = scope.holder ?: return null
        return named.presentableName.takeIf { holder.editableScopes.any { it.scopeId == named.scopeId } }
    }

    /** Shows the current scope in the Project view; false when there is nothing to show. EDT. */
    fun openInProjectView(project: Project): Boolean {
        val target = projectViewTarget(project) ?: return false
        ScopeUi.getInstance().openInProjectView(project, target)
        return true
    }

    /**
     * Where "Open in Project view" goes: the Scope pane for a (not deleted) named scope, the root directory when the
     * choice resolved to exactly one root (Current file's root, or one chosen root), else null (All roots and several
     * chosen roots have no single place in the Project view).
     */
    fun projectViewTarget(project: Project): ProjectViewTarget? {
        val scope = WorkspaceScopeServiceImpl.getInstance(project)?.currentScope() ?: return null
        scope.namedScope?.let { return ProjectViewTarget.ScopePane(it.scopeId, it.presentableName) }
        return scope.chosenRoots.singleOrNull()?.let { ProjectViewTarget.Directory(it.dir) }
    }

    /** The coverage text of a selector row: "falcon", "falcon + pelican › danger_zone/database", "matches no Ansible root". */
    @Nls
    fun coverageText(coverage: ScopeCoverage?): String {
        if (coverage == null) return AnsibilityScopeBundle.message("popup.coverage.computing")
        if (coverage.roots.isEmpty()) return AnsibilityScopeBundle.message("popup.coverage.none")
        val names = coverage.roots.map { root ->
            if (coverage.of(root) == RootCoverage.PARTIAL) AnsibilityScopeBundle.message("popup.coverage.partial", root.displayName) else root.displayName
        }
        return if (names.size <= NAMED_ROOTS) {
            names.joinToString(" + ")
        } else {
            AnsibilityScopeBundle.message("popup.coverage.more", names.take(NAMED_ROOTS).joinToString(" + "), names.size - NAMED_ROOTS)
        }
    }

    /** The selector button's text, "Scope: All roots". */
    @Nls
    fun comboText(scope: WorkspaceScopeImpl): String = AnsibilityScopeBundle.message("scope.combo.text", scope.label)

    /** The selector button's tooltip: the coverage (when known), the problem if any, and the "lists only" rule. */
    @Nls
    fun comboTooltip(scope: WorkspaceScopeImpl): String {
        val parts = ArrayList<String>()
        parts += AnsibilityScopeBundle.message("scope.combo.tooltip", scope.label, coverageText(scope.coverageIfComputed()))
        scope.problemIfComputed()?.let { parts += it }
        parts += AnsibilityScopeBundle.message("scope.combo.tooltip.rule")
        return parts.joinToString(". ")
    }
}
