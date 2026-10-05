package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RenderLoop
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.resolve.register.RoleTaskOrder
import de.terletzkiy.ansibility.yaml.YamlPaths

/**
 * Loop variables at a Jinja reference (plan F2.4 "the same targets as F1.2 and F1.4", F1.7, X78): which loop defines
 * `item` (or a `loop_control.loop_var`, `index_var`, `ansible_loop`) where it is used, and what it iterates.
 *
 * - In a task file the loops are the one of the task around the reference ([LoopItemTyper]) and those of enclosing
 *   `include_tasks`/`import_tasks`, nearest first. Inside a loop's own expression that loop variable is not defined
 *   yet, but an outer include's differently named loop variable remains in scope.
 * - In a template the loops are those of the tasks that render it ([TemplateContextService]); several rendering tasks
 *   agree on the iterated variable only when all of them iterate the same one.
 *
 * Hover and Ctrl+B use it to document `item.floating.ssl.cert_file` as the nested option
 * `grafana_nginx_sites[].floating.ssl.cert_file` of the iterated variable and to go to that option's key, the way
 * completion already types loop items. Call in a read action in smart mode.
 */
internal object LoopItems {
    /** One task that runs the loop: where its mapping starts and where its loop keyword (`loop:`, `with_items:` …) is. */
    data class LoopTask(val task: SourceLocation, val loopKey: SourceLocation)

    /** The loop(s) that define a name at a reference, and the tasks that run them (in file order). */
    class Binding(val loop: RenderLoop, val tasks: List<LoopTask>) {
        /** The loop keywords of [tasks]. */
        val loopKeys: List<SourceLocation> get() = tasks.map { it.loopKey }

        /**
         * The variable and the path below it that `name.attrPath…` documents: for the loop variable, an element of the
         * iterated variable (`grafana_nginx_sites`, `["0", "floating", "ssl", "cert_file"]`); null for `index_var`,
         * `ansible_loop` and loops over literal lists or lookups.
         */
        fun documented(name: String, attrPath: List<String>): Pair<String, List<String>>? {
            if (name != loop.loopVar) return null
            val source = loop.sourceVariable ?: return null
            return source to (loop.sourcePath + ELEMENT + attrPath)
        }
    }

    /** The path segment that addresses one element of a list (`SpecOptions.resolve` skips it below a list). */
    const val ELEMENT: String = "0"

    private const val J2_SUFFIX = ".j2"
    private const val MAX_TASK_LABELS = 4

    /**
     * The loop that defines [name] at [offset] of [file], or null: in a template (a role template or any `.j2` file)
     * the loops of its rendering tasks, in a YAML task list the loop of the task around [offset] or of an enclosing
     * static task include.
     */
    fun bindingAt(project: Project, file: VirtualFile, offset: Int, name: String): Binding? {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        if (context.kind == FileKind.ROLE_TEMPLATE || file.name.endsWith(J2_SUFFIX)) return templateBinding(project, file, name)
        val yaml = YamlFiles.yamlFile(project, file) ?: return null
        if (!YamlPaths.isTopLevelSequence(yaml)) return null
        val chain = TaskChains.chainAt(TaskFileModels.of(yaml), offset)
        val task = TaskChains.taskOf(chain) ?: return null
        if (!inLoopValue(task, offset)) {
            val typed = LoopItemTyper.typeOf(project, yaml, task, chain)
            if (typed != null && name in typed.names) {
                return Binding(
                    typed.loop,
                    listOf(LoopTask(SourceLocation(file, task.range.startOffset), SourceLocation(file, loopKeyOffset(task)))),
                )
            }
        }
        val roleName = context.roleName ?: return null
        val role = RoleRegistry.getInstance(project).role(context.root, roleName) ?: return null
        for (include in RoleTaskOrder(project, role).enclosingIncludes(file)) {
            ProgressManager.checkCanceled()
            val typed = LoopItemTyper.typeOf(project, include.yaml, include.task, include.chain) ?: continue
            if (name !in typed.names) continue
            return Binding(
                typed.loop,
                listOf(
                    LoopTask(
                        SourceLocation(include.file, include.task.range.startOffset),
                        SourceLocation(include.file, loopKeyOffset(include.task)),
                    ),
                ),
            )
        }
        return null
    }

    /**
     * The subject [subject] documents when it is a loop variable's member with a documented iterated variable: the
     * nested option of an element of that variable, remembering the loop ([VarSubject.loop]). Null when the name is no
     * loop variable there, or when nothing in the root declares or sets the iterated variable (the card then explains
     * the loop instead).
     */
    fun documentedSubject(project: Project, root: AnsibleRoot, subject: VarSubject): VarSubject? {
        if (subject.origin != VarSubject.Origin.REFERENCE) return null
        val binding = bindingAt(project, subject.file, subject.offset, subject.name) ?: return null
        val (variable, path) = binding.documented(subject.name, subject.path) ?: return null
        val symbol = VarService.getInstance(project).symbol(root, variable)
        if (symbol.specBindings.isEmpty() && symbol.definitions.isEmpty()) return null
        val via = VarSubject.LoopVia(subject.name, (listOf(variable) + binding.loop.sourcePath).joinToString("."), binding.tasks)
        return VarSubject.referenceTo(root, variable, path, subject.file, subject.offset).withLoop(via)
    }

    private fun templateBinding(project: Project, file: VirtualFile, name: String): Binding? {
        val contexts = TemplateContextService.getInstance(project).renderContexts(file)
        val matching = contexts.mapNotNull { context ->
            ProgressManager.checkCanceled()
            context.loop?.takeIf { name in LoopItemTyper.namesOf(it) }?.let { it to context.taskSite }
        }
        if (matching.isEmpty()) return null
        val loops = matching.map { it.first }
        val source = loops.map { it.sourceVariable to it.sourcePath }.distinct().singleOrNull()
        val merged = RenderLoop(
            loopVar = loops.first().loopVar,
            indexVar = loops.firstNotNullOfOrNull { it.indexVar },
            extended = loops.any { it.extended },
            item = loops.mapNotNull { it.item }.distinct().singleOrNull(),
            sourceVariable = source?.first,
            sourcePath = source?.second.orEmpty(),
        )
        val tasks = matching.map { it.second }.distinct().map { LoopTask(it, loopKeyOf(project, it)) }
            .sortedWith(compareBy({ it.task.file.path }, { it.task.offset }))
        return Binding(merged, tasks)
    }

    /** The loop keyword of the task whose mapping starts at [taskSite], or the task itself. */
    private fun loopKeyOf(project: Project, taskSite: SourceLocation): SourceLocation {
        val yaml = YamlFiles.yamlFile(project, taskSite.file) ?: return taskSite
        val task = TaskChains.taskOf(TaskChains.chainAt(TaskFileModels.of(yaml), taskSite.offset)) ?: return taskSite
        return SourceLocation(taskSite.file, loopKeyOffset(task))
    }

    /** `the task at roles/web/tasks/main.yml:10`, or `2 rendering tasks (a.yml:3, b.yml:9)` for several. */
    fun tasksLabel(root: AnsibleRoot, tasks: List<LoopTask>): String {
        val labels = tasks.map { VarLocations.label(root, it.task) }
        return if (labels.size == 1) AnsibilityVarsBundle.message("card.loop.one.task", labels.single())
        else AnsibilityVarsBundle.message("card.loop.tasks", labels.size, labels.take(MAX_TASK_LABELS).joinToString(", "))
    }

    private fun loopKeyOffset(task: TaskNode): Int = task.loop?.key?.range?.start ?: task.range.startOffset

    /** True when [offset] is inside the task's own loop expression, where the loop variable is not defined yet. */
    private fun inLoopValue(task: TaskNode, offset: Int): Boolean {
        val range = task.loop?.value?.range ?: return false
        return offset >= range.start && offset <= range.end
    }
}
