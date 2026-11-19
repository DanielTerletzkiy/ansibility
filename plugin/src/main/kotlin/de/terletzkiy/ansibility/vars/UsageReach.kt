package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.card.HostCardViews
import de.terletzkiy.ansibility.model.effective.ExecutionSources

/**
 * Which definitions of a variable can reach one usage (plan amendment R31 fixes, decision 2): those that some host of
 * the usage's all-hosts scope loads there, whatever the selection. The scope is the one the inspections use
 * ([AnsibleContextServiceImpl.allHostsScopeAt]: a role file's hosts are the hosts of the plays that apply the role, a
 * playbook's those of the play at the caret), and "loads" is the precedence engine's answer for each of them (the
 * card's [HostCardViews.allHostsView]: every definition that wins or loses on some host), so the reach rules of the
 * override gutter hold without a second engine: a `group_vars` file of a group that never runs the role, role params
 * given to another role and the `vars:` of a play that never applies the role load on none of those hosts.
 *
 * The block and task vars and include params in force at the usage count, and an `include_vars` file counts when a
 * task of the usage's plays or roles loads variables of the name and the file lies in one of their role directories or
 * playbook directories (the engine marks such tasks, not the files they load).
 *
 * Call in a read action in smart mode; [at] is null when the model cannot answer (dumb mode, no host runs the file):
 * then nothing is filtered.
 */
internal class UsageReach private constructor(
    private val loaded: Set<SourceLocation>,
    /** The role and playbook directories whose tasks load variables of the name with `include_vars`. */
    private val includeVarsDirs: List<VirtualFile>,
) {
    /** Whether [definition] can reach the usage. */
    fun reaches(definition: VarDefinition): Boolean {
        if (definition.location in loaded) return true
        if (definition.kind != VarDefKind.INCLUDE_VARS) return false
        return includeVarsDirs.any { VfsUtilCore.isAncestor(it, definition.location.file, true) }
    }

    companion object {
        /** The reach of [name] at [offset] of [file], or null when the model cannot answer. */
        fun at(project: Project, file: VirtualFile, offset: Int, name: String): UsageReach? {
            val view = HostCardViews.getInstance(project).allHostsView(file, offset, name) ?: return null
            if (view.scope.targets.isEmpty()) return null
            val loaded = HashSet<SourceLocation>()
            for (location in view.winsOn.keys + view.shadowedOn.keys) loaded += SourceLocation(location.file, location.offset)
            val taskVars = view.taskVars
            (listOfNotNull(taskVars.applied) + taskVars.partial + taskVars.included).mapTo(loaded) { SourceLocation(it.file, it.offset) }
            val dirs = ArrayList<VirtualFile>()
            val impl = AnsibleContextServiceImpl.getInstance(project) ?: return UsageReach(loaded, dirs)
            for (target in view.scope.targets.distinctBy { it.play }) {
                ProgressManager.checkCanceled()
                val evaluation = impl.evaluator.evaluation(target, view.runningRole, view.scope.root) ?: continue
                val markers = ExecutionSources.getInstance(project).runtimeMarkers(evaluation.root, evaluation.inputs, name)
                if (markers.none { it.kind == RuntimeMarkerKind.INCLUDE_VARS }) continue
                dirs += evaluation.inputs.roleDirs
                target.play?.playbookDir?.let(dirs::add)
            }
            return UsageReach(loaded, dirs.distinct())
        }
    }
}
