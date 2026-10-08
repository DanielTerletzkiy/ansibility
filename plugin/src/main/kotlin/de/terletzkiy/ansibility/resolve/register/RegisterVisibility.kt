package de.terletzkiy.ansibility.resolve.register

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.yaml.YamlPaths

/**
 * Which `register:` definitions of a name the code at a position sees (plan F1.6 "role order", A.5 tiers T3 and T4),
 * by the rules Jinja name completion applies to runtime names:
 *
 * - **Own scope.** In a role task file, the role's registers set before the position in role order ([RoleTaskOrder]);
 *   the position's own task's register only inside its `until`/`changed_when`/`failed_when` ([RESULT_KEYS]). In a
 *   handler file or any other role file (defaults, vars), every register of the role. In a template, the registers
 *   before each task that renders it (in its role, or in its playbook), or every register of the template's role when
 *   no task renders it. In a playbook or a task file outside the role's task files, the file's registers before the
 *   position.
 * - **Play scope**, only when the own scope has none: the registers of the other roles applied by the plays that apply
 *   the own role (or by the playbook play around the position); they run in the same play, in some order.
 *
 * Every lookup stays in the position's root (`VarService` scoping). With [MoleculeView.EXCLUDE] (navigation, cards
 * and completion started outside Molecule while Molecule is hidden, plan amendment R20, D153) neither the registers
 * of Molecule files nor the plays of Molecule playbooks count; analysis uses [MoleculeView.INCLUDE]. Call in a read
 * action in smart mode.
 */
internal object RegisterVisibility {
    /** Task keys evaluated after the module ran, where the task's own `register` result exists. */
    val RESULT_KEYS: Set<String> = setOf("until", "changed_when", "failed_when")

    private const val J2_SUFFIX = ".j2"

    /**
     * Where runtime names stop being visible at [offset] of a task-like [file] (its model [model]): before the offset
     * and outside the task around it, plus that task's register when its result keys contain the offset.
     */
    fun cutoffAt(file: VirtualFile, model: TaskFileModel, offset: Int): Cutoff {
        val task = TaskChains.taskOf(TaskChains.chainAt(model, offset))
        val ownResult = task?.takeIf { node -> node.expressions.any { it.key in RESULT_KEYS && it.range.containsOffset(offset) } }
        return Cutoff(file, offset, ownResult?.range, task?.range)
    }

    /**
     * The range of the task whose `until`/`changed_when`/`failed_when` contains [offset] of the task-like [file] (the
     * task's own register holds the result of its current run there), or null anywhere else.
     */
    fun resultKeysTask(project: Project, file: VirtualFile, offset: Int): TextRange? {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        if (TaskFileModels.kindFor(context.kind) == null) return null
        val yaml = YamlFiles.yamlFile(project, file)?.takeIf { YamlPaths.isTopLevelSequence(it) } ?: return null
        return cutoffAt(file, TaskFileModels.of(yaml), offset).task
    }

    /**
     * The `register:` definitions of [name] visible at [offset] of [file] as [view] sees them, in file order: the own
     * scope's, else the play scope's; empty when none (or the file is in no root).
     */
    fun visible(project: Project, file: VirtualFile, offset: Int, name: String, view: MoleculeView = MoleculeView.INCLUDE): List<VarDefinition> {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return emptyList()
        val registers = registersOf(project, context.root, name, view)
        if (registers.isEmpty()) return emptyList()
        val scope = scopeAt(project, file, offset, context, view)
        val own = registers.filter(scope::sees)
        if (own.isNotEmpty()) return own
        val others = playRoles(project, file, offset, context, scope, view)
        return registers.filter { definition -> others.any { definition.location.file in it.taskFiles } }
    }

    /** Every `register:` definition of [name] in [root] as [view] sees them, in file order. */
    fun registersOf(project: Project, root: AnsibleRoot, name: String, view: MoleculeView = MoleculeView.INCLUDE): List<VarDefinition> =
        VarViews.symbol(project, root, name, view).definitions
            .filter { it.kind == VarDefKind.REGISTER }
            .sortedWith(compareBy({ it.location.file.path }, { it.location.offset }))

    // ------------------------------------------------------------------------------------------------ own scope

    /** A role and where its registers stop being visible (null: all of them count). */
    private class RoleScope(project: Project, val info: RoleInfo, val cutoffs: List<Cutoff>?) {
        val order: RoleTaskOrder by lazy(LazyThreadSafetyMode.NONE) { RoleTaskOrder(project, info) }
    }

    /** The own scope at a position: roles with their cutoffs, and cutoffs inside files outside every role's task files. */
    private class Scope(val roles: List<RoleScope>, val local: List<Cutoff>) {
        fun sees(definition: VarDefinition): Boolean {
            val runtime = RuntimeName(definition.name, true, definition.location)
            if (local.any { it.file == definition.location.file && RoleTaskOrder.isVisibleInFile(runtime, it) }) return true
            return roles.any { role ->
                ProgressManager.checkCanceled()
                if (definition.location.file !in role.info.taskFiles) return@any false
                val cutoffs = role.cutoffs ?: return@any true
                cutoffs.any { role.order.isVisible(runtime, it) }
            }
        }
    }

    private fun scopeAt(project: Project, file: VirtualFile, offset: Int, context: FileContext, view: MoleculeView): Scope {
        val registry = RoleRegistry.getInstance(project)
        val ownRole = context.roleName?.let { registry.role(context.root, it) }
        if (context.kind == FileKind.ROLE_TEMPLATE || file.name.endsWith(J2_SUFFIX)) return templateScope(project, file, context, ownRole, view)
        val yaml = YamlFiles.yamlFile(project, file)
        val taskLike = yaml != null && YamlPaths.isTopLevelSequence(yaml) && TaskFileModels.kindFor(context.kind) != null
        if (yaml == null || !taskLike) return Scope(listOfNotNull(ownRole?.let { RoleScope(project, it, null) }), emptyList())
        val cutoff = cutoffAt(file, TaskFileModels.of(yaml), offset)
        val handlers = context.kind == FileKind.ROLE_HANDLERS
        val roles = listOfNotNull(ownRole?.let { RoleScope(project, it, if (handlers) null else listOf(cutoff)) })
        val local = if (ownRole != null && file in ownRole.taskFiles) emptyList() else listOf(cutoff)
        return Scope(roles, local)
    }

    /**
     * A template sees the registers before each task that renders it: in that task's role, or in its playbook; its own
     * role's registers all count when no task of that role renders it. Render contexts as [view] sees them
     * ([MoleculeVisibility.contextsInView]).
     */
    private fun templateScope(project: Project, file: VirtualFile, context: FileContext, ownRole: RoleInfo?, view: MoleculeView): Scope {
        val registry = RoleRegistry.getInstance(project)
        val contexts = MoleculeVisibility.contextsInView(project, view, TemplateContextService.getInstance(project).renderContexts(file))
        val roles = LinkedHashMap<String, RoleScope>()
        val byRole = contexts.filter { it.role != null }.groupBy { it.role!!.name }
        ownRole?.let { role -> roles[role.ref.name] = RoleScope(project, role, byRole[role.ref.name]?.map { Cutoff(it.taskSite.file, it.taskSite.offset, null) }) }
        for ((name, group) in byRole) {
            ProgressManager.checkCanceled()
            if (name in roles) continue
            val info = registry.role(context.root, name) ?: continue
            roles[name] = RoleScope(project, info, group.map { Cutoff(it.taskSite.file, it.taskSite.offset, null) })
        }
        val local = contexts.filter { it.role == null }.map { Cutoff(it.taskSite.file, it.taskSite.offset, null) }
        return Scope(roles.values.toList(), local)
    }

    // ------------------------------------------------------------------------------------------------ play scope

    /** The other roles of the plays that apply the own roles, or of the playbook play around the position. */
    private fun playRoles(project: Project, file: VirtualFile, offset: Int, context: FileContext, scope: Scope, view: MoleculeView): List<RoleInfo> {
        val graph = PlayGraph.getInstance(project)
        val own = scope.roles.mapTo(HashSet()) { it.info.ref.name }
        val plays = own.flatMap { MoleculeVisibility.playsInView(project, view, graph.playsApplying(context.root, it)) }.toMutableSet()
        if (context.kind == FileKind.PLAYBOOK) {
            val yaml = YamlFiles.yamlFile(project, file)
            val play = yaml?.let { TaskFileModels.of(it).playAt(offset) }
            graph.playsOf(file).firstOrNull { it.ref.playIndex == play?.index }?.let { plays += it.ref }
        }
        val registry = RoleRegistry.getInstance(project)
        return plays.flatMap { graph.rolesOfPlay(it) }
            .mapNotNull { entry -> entry.role?.name?.takeIf { it !in own } }
            .distinct()
            .mapNotNull { registry.role(context.root, it) }
    }
}
