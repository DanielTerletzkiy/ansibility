package de.terletzkiy.ansibility.workspace.crossroot

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.WorkspaceScopeService
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.resolve.VarViews

/** Where one definition takes effect in its root for the report's environment: wins, shadowed, never loaded, or unknown. */
sealed interface DefinitionMark {
    data class Wins(val hosts: Int) : DefinitionMark

    data class Shadowed(val hosts: Int) : DefinitionMark

    data class NotLoaded(val reason: String?) : DefinitionMark

    /** Role defaults, task vars and the like, whose effect depends on a play. */
    data object None : DefinitionMark
}

class CrossRootDefinition(val definition: VarDefinition, val mark: DefinitionMark)

class CrossRootGroup(val root: AnsibleRoot, val inScope: Boolean, val definitions: List<CrossRootDefinition>)

class CrossRootReport(val name: String, val environment: String?, val groups: List<CrossRootGroup>, val environments: List<String>) {
    val definitionCount: Int get() = groups.sumOf { it.definitions.size }
}

/**
 * Every definition of one variable in every non-detached root (plan amendment R9, F9.8): one cached
 * `VarViews.symbol(root, name, view)` per root, in-scope roots first. Previews are the index's vault-safe ones. The
 * effective column comes from `AnsibleContextService.definitionStatus`, narrowed to [environment] when one is chosen.
 * Molecule definitions are listed as a request started in `origin` sees them (plan amendment R20, D153/D154,
 * [MoleculeView.of]): from a Molecule file always; otherwise, and without a file, only with "Show Molecule in navigation
 * and search" on. Needs a read action in smart mode.
 */
object CrossRootVars {
    private val LOCAL_KINDS = setOf(VarDefKind.JINJA_LOCAL, VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR)
    private val INVENTORY_KINDS = setOf(VarDefKind.GROUP_VARS, VarDefKind.HOST_VARS, VarDefKind.INVENTORY_INLINE)

    /** The report of [name]; [origin] is the file the request started in (the action's editor), null for none. */
    fun report(project: Project, name: String, environment: String?, origin: VirtualFile? = null): CrossRootReport {
        val scope = WorkspaceScopeService.getInstance(project).current()
        val inScope = scope.roots.toSet()
        val roots = AnsibleWorkspace.getInstance(project).roots().filter { !it.detached }
        val view = MoleculeView.of(project, origin?.takeIf { it.isValid })
        val context = AnsibleContextService.getInstance(project)
        val environments = sortedSetOf<String>()
        val groups = roots.mapNotNull { root ->
            ProgressManager.checkCanceled()
            val definitions = VarViews.symbol(project, root, name, view).definitions
                .filter { it.kind !in LOCAL_KINDS && it.location.file.path.startsWith(root.dir.path) }
                .distinctBy { it.location }
            if (definitions.isEmpty()) return@mapNotNull null
            definitions.mapNotNullTo(environments) { it.environment }
            CrossRootGroup(root, root in inScope, definitions.map { CrossRootDefinition(it, mark(context, it, environment)) })
        }.sortedBy { if (it.inScope) 0 else 1 }
        return CrossRootReport(name, environment, groups, environments.toList())
    }

    private fun mark(context: AnsibleContextService, definition: VarDefinition, environment: String?): DefinitionMark {
        if (definition.kind !in INVENTORY_KINDS) return DefinitionMark.None
        val status = runCatching { context.definitionStatus(definition) }.getOrNull() ?: return DefinitionMark.None
        fun inEnv(env: String) = environment == null || env.substringAfterLast('/') == environment.substringAfterLast('/')
        val wins = status.winsOn.count { inEnv(it.environment) }
        val shadowed = status.shadowedOn.keys.count { inEnv(it.environment) }
        return when {
            wins > 0 -> DefinitionMark.Wins(wins)
            shadowed > 0 -> DefinitionMark.Shadowed(shadowed)
            status.notLoadedReason != null || status.winsOn.isEmpty() && status.shadowedOn.isEmpty() -> DefinitionMark.NotLoaded(status.notLoadedReason)
            else -> DefinitionMark.NotLoaded(null)
        }
    }
}
