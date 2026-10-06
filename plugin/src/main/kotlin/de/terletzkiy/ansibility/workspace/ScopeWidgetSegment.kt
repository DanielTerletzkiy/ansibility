package de.terletzkiy.ansibility.workspace

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.ContextWidgetSegment
import de.terletzkiy.ansibility.api.ScopeChoice
import de.terletzkiy.ansibility.api.WidgetSegment
import de.terletzkiy.ansibility.api.WorkspaceScopeService

/**
 * The status-bar segment of the workspace scope (plan amendment R9, WS9): `scope heron`, with `⚠` when the editor's
 * file lies outside it. Nothing shows for All roots. The popup offers the scope switcher.
 */
class ScopeWidgetSegment : ContextWidgetSegment {
    override fun segment(project: Project, file: VirtualFile): WidgetSegment? {
        val scope = WorkspaceScopeService.getInstance(project).current()
        if (scope.choice == ScopeChoice.AllRoots) return null
        val label = (scope as? WorkspaceScopeImpl)?.label ?: return null
        val outside = !scope.contains(file)
        val text = AnsibilityScopeBundle.message(if (outside) "segment.scope.outside" else "segment.scope", label)
        val tooltip = scope.problem ?: if (outside) AnsibilityScopeBundle.message("segment.scope.outside.tooltip", label) else null
        val actions = listOfNotNull(ActionManager.getInstance().getAction("Ansibility.Scope.Switch"))
        return WidgetSegment(text, tooltip, actions)
    }
}
