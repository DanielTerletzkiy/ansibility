package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.vars.JinjaTextSites
import de.terletzkiy.ansibility.vars.VarLocations
import org.jetbrains.annotations.Nls

/** Who a runtime variable belongs to (D-FU5): a role (its whole directory, molecule scenarios included) or one file outside roles. */
internal sealed interface VarOwner {
    data class Role(val dir: VirtualFile) : VarOwner

    /** A playbook or a task file outside every role. */
    data class File(val file: VirtualFile) : VarOwner
}

/**
 * Runtime-name scoping of Find Usages (plan amendment FU, D-FU5): `register`, `set_fact`, task, block and
 * `include_role` vars that no role declares are seen by the role or playbook that sets them, and by the templates its
 * tasks render. Names like `result` repeat across roles, so a search groups the occurrences of other owners apart.
 *
 * Call in a read action in smart mode (template owners come from [TemplateContextService]).
 */
internal object VarOwners {
    /** Definition kinds whose names live only while their owner's tasks run. */
    val RUNTIME_SCOPED: Set<VarDefKind> = setOf(
        VarDefKind.REGISTER, VarDefKind.SET_FACT, VarDefKind.TASK_VARS, VarDefKind.BLOCK_VARS, VarDefKind.INCLUDE_PARAMS,
        VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR,
    )

    /**
     * The owners of [file]: its role; for a template outside every role the owners of the tasks that render it; else
     * the file itself. Empty outside every root.
     */
    fun of(project: Project, file: VirtualFile): Set<VarOwner> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val context = workspace.contextOf(file) ?: return emptySet()
        context.roleDir?.let { return setOf(VarOwner.Role(it)) }
        if (JinjaTextSites.isTemplateFile(file, context)) {
            val renderers = LinkedHashSet<VarOwner>()
            for (render in TemplateContextService.getInstance(project).renderContexts(file)) {
                ProgressManager.checkCanceled()
                val taskFile = render.taskSite.file
                if (taskFile == file) continue
                renderers += workspace.contextOf(taskFile)?.roleDir?.let { VarOwner.Role(it) } ?: VarOwner.File(taskFile)
            }
            if (renderers.isNotEmpty()) return renderers
        }
        return setOf(VarOwner.File(file))
    }

    /**
     * The owners whose occurrences of a runtime name belong to a search started in [home], or null when the name is a
     * variable of the root: some definition is not runtime-scoped (a role declares it, the inventory sets it, play
     * vars …), or [home]'s owners set it nowhere. The owners of [home] grow by the roles their `include_role` vars
     * are passed to.
     */
    fun runtimeHome(project: Project, root: AnsibleRoot, home: VirtualFile, definitions: List<VarDefinition>): Set<VarOwner>? {
        val relevant = definitions.filter { it.kind != VarDefKind.JINJA_LOCAL }
        if (relevant.isEmpty() || relevant.any { it.kind !in RUNTIME_SCOPED }) return null
        val owners = LinkedHashSet(of(project, home))
        val own = relevant.filter { definition -> of(project, definition.location.file).any { it in owners } }
        if (own.isEmpty()) return null
        for (definition in own) {
            if (definition.kind == VarDefKind.INCLUDE_PARAMS) includedRole(project, root, definition.location)?.let { owners += VarOwner.Role(it) }
        }
        return owners
    }

    /** `role web`, or `playbook site.yml` with the path relative to [root]. */
    @Nls
    fun label(root: AnsibleRoot, owner: VarOwner): String = when (owner) {
        is VarOwner.Role -> AnsibilityUsagesBundle.message("owner.role", owner.dir.name)
        is VarOwner.File -> AnsibilityUsagesBundle.message("owner.file", VarLocations.path(root, owner.file))
    }

    /** The directory of the role an `include_role`/`import_role` task passes the vars at [location] to. */
    private fun includedRole(project: Project, root: AnsibleRoot, location: SourceLocation): VirtualFile? {
        val yaml = YamlFiles.yamlFile(project, location.file) ?: return null
        val task = TaskFileModels.of(yaml).itemAt(location.offset) as? TaskNode ?: return null
        val name = task.roleInclude?.name?.text ?: return null
        return RoleRegistry.getInstance(project).role(root, name)?.ref?.dir
    }
}
