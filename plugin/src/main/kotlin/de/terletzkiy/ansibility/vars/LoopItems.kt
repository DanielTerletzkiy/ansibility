package de.terletzkiy.ansibility.vars

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RenderKind
import de.terletzkiy.ansibility.api.RenderLoop
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.include.IncludeBindings
import de.terletzkiy.ansibility.resolve.include.IncludeGraph
import de.terletzkiy.ansibility.resolve.loop.LiteralShapes
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.YamlPaths

/**
 * Loop variables at a Jinja reference (plan F2.4 "the same targets as F1.2 and F1.4", F1.7, X78): which loop defines
 * `item` (or a `loop_control.loop_var`, `index_var`, `ansible_loop`) where it is used, and what it iterates. The one
 * source of truth for hover, Ctrl+B, Find Usages, highlighting and rename of loop variables.
 *
 * - In a task file the loop is the one of the task around the reference ([LoopItemTyper]); else the loops of the
 *   include tasks that run the file ([IncludeBindings]: `include_tasks` of the role and `include_role` with
 *   `tasks_from`, transitively): per include path the include task whose binding the file sees
 *   ([de.terletzkiy.ansibility.resolve.include.IncludePath.winnerOf]: the nearest dynamic include that gives the name,
 *   include params beating imports' `vars:`), when it gives it through its loop. `import_tasks`/`import_role` loops bind
 *   nothing (ansible-core rejects them). Inside a loop's own expression that loop variable is not defined yet, but an
 *   include's loop variable remains in scope.
 * - In a template the loops are those of the tasks that render it ([TemplateContextService]), and for a rendering
 *   task whose own loop does not set the name, the loops of the include tasks that run its file (include params beat
 *   the rendering task's own `vars:`; a `lookup('template')` whose `template_vars` set the name keeps its own); several
 *   loops agree on the iterated variable only when all of them iterate the same one.
 * - Molecule (plan amendment R20): include tasks in Molecule files bind nothing for a production file
 *   ([MoleculeView.forAnalysis] of the file, a path property, so every request from production agrees on a loop
 *   variable's scope); a search that starts at a Molecule loop passes [MoleculeView.INCLUDE] to follow it into the
 *   production files it runs (D154).
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

        /**
         * Where each task binds [name]: its `loop_control.loop_var`/`index_var` value when it names [name] (the scalar's
         * start, as the variable index records it), else its loop keyword (`item`, `ansible_loop`).
         */
        fun bindingSites(project: Project, name: String): List<SourceLocation> = tasks.map { loopTask ->
            ProgressManager.checkCanceled()
            val value = taskAt(project, loopTask.task)?.let { LoopVarValues.valueNaming(it, name) }
            value?.let { SourceLocation(loopTask.task.file, it.ref.range.startOffset) } ?: loopTask.loopKey
        }.distinct()

        /**
         * True when some loop of the binding is run by no task: Molecule's create playbook renders a scenario's
         * `Dockerfile.j2` once per platform (X77), whose loop is the scenario's `platforms:` key.
         */
        fun runByNoTask(project: Project): Boolean = tasks.any { taskAt(project, it.task) == null }
    }

    /** A member key of a literal loop item: where it is written and the item keys from the loop variable down to it. */
    class MemberKey(val location: SourceLocation, val names: List<String>)

    /** The path segment that addresses one element of a list (`SpecOptions.resolve` skips it below a list). */
    const val ELEMENT: String = "0"

    private const val J2_SUFFIX = ".j2"
    private const val MAX_CLOSURE = 64
    private const val MAX_TASK_LABELS = 4
    private const val MAX_EXPRESSION = 60
    private val ACTION_PREFIXES = listOf("ansible.builtin.", "ansible.legacy.")

    /**
     * The loop that defines [name] at [offset] of [file], or null: in a template (a role template or any `.j2` file)
     * the loops of its rendering tasks (and of the include tasks that run them), in a YAML task list the loop of the
     * task around [offset] or of the include tasks that run the file.
     */
    fun bindingAt(project: Project, file: VirtualFile, offset: Int, name: String, view: MoleculeView? = null): Binding? {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return null
        if (context.kind == FileKind.ROLE_TEMPLATE || file.name.endsWith(J2_SUFFIX)) return templateBinding(project, file, name, view)
        val yaml = YamlFiles.yamlFile(project, file) ?: return null
        if (!YamlPaths.isTopLevelSequence(yaml)) return null
        val chain = TaskChains.chainAt(TaskFileModels.of(yaml), offset)
        val task = TaskChains.taskOf(chain)
        if (task != null && !inLoopValue(task, offset)) {
            val typed = LoopItemTyper.typeOf(project, yaml, task, chain)
            if (typed != null && name in typed.names) {
                return Binding(
                    typed.loop,
                    listOf(LoopTask(SourceLocation(file, task.range.startOffset), SourceLocation(file, loopKeyOffset(task)))),
                )
            }
        }
        return merge(includeLoops(project, file, name, view))
    }

    /**
     * The loop a `loop_control.loop_var`/`index_var` value at [offset] of [file] names, with that name: the task's own
     * loop; for a looping include task, together with every other include task whose loop binds the name in the files it
     * runs (two `include_tasks` of one file, each with `loop_var: x`, are one loop variable there), so the binding is the
     * one [bindingAt] finds in those files. Null when the offset is in no such value or the task does not loop.
     */
    fun bindingOfValue(project: Project, file: VirtualFile, offset: Int): Pair<String, Binding>? {
        val yaml = YamlFiles.yamlFile(project, file) ?: return null
        val value = LoopVarValues.valueAt(yaml, offset) ?: return null
        val task = value.task
        val name = value.ref.text
        val typed = LoopItemTyper.typeOf(project, yaml, task, TaskChains.chainAt(TaskFileModels.of(yaml), task.range.startOffset)) ?: return null
        if (name !in typed.names) return null
        val own = typed.loop to LoopTask(SourceLocation(file, task.range.startOffset), SourceLocation(file, loopKeyOffset(task)))
        if (task.taskInclude == null && task.roleInclude == null) return name to Binding(own.first, listOf(own.second))
        // A search from a Molecule loop follows it into the production files it runs (D154); else as those files see it.
        val view = MoleculeView.INCLUDE.takeIf { MoleculeVisibility.isMoleculeFile(project, file) }
        return name to (merge(listOf(own) + includeClosure(project, own.second, name, view)) ?: return null)
    }

    /**
     * The include loops that bind [name] in the files [start] (an include task) runs, and in turn in the files those
     * loops' tasks run: per file the loops [bindingAt] finds there through includes (as [view] sees them, null:
     * [MoleculeView.forAnalysis] of each file).
     */
    private fun includeClosure(project: Project, start: LoopTask, name: String, view: MoleculeView?): List<Pair<RenderLoop, LoopTask>> {
        val graph = IncludeGraph.getInstance(project)
        val found = LinkedHashMap<SourceLocation, Pair<RenderLoop, LoopTask>>()
        val queue = ArrayDeque(listOf(start.task))
        val seen = hashSetOf(start.task)
        var steps = 0
        while (queue.isNotEmpty() && steps++ < MAX_CLOSURE) {
            ProgressManager.checkCanceled()
            for (target in graph.filesRunBy(queue.removeFirst())) {
                for (loop in includeLoops(project, target, name, view)) {
                    found.putIfAbsent(loop.second.task, loop)
                    if (seen.add(loop.second.task)) queue += loop.second.task
                }
            }
        }
        return found.values.toList()
    }

    /**
     * True when an include task that runs [file] binds [name] through its loop: a structural check on the cached include
     * graph (no loop typing), for the caret path of the usage search.
     */
    fun includeLoopMayBind(project: Project, file: VirtualFile, name: String): Boolean =
        IncludeBindings.includers(project, file, MoleculeView.forAnalysis(project, file)).any { name in it.loopNames }

    /**
     * The include tasks that run [file] and bind [name] there through their loops, typed: per include path the include
     * task whose binding the file sees ([de.terletzkiy.ansibility.resolve.include.IncludePath.winnerOf]), when that is
     * its loop. [view] null: [MoleculeView.forAnalysis] of [file].
     */
    private fun includeLoops(project: Project, file: VirtualFile, name: String, view: MoleculeView? = null): List<Pair<RenderLoop, LoopTask>> {
        val result = LinkedHashMap<SourceLocation, Pair<RenderLoop, LoopTask>>()
        for (path in IncludeBindings.paths(project, file, view ?: MoleculeView.forAnalysis(project, file))) {
            ProgressManager.checkCanceled()
            val nearest = path.winnerOf(name) ?: continue
            if (name !in nearest.loopNames || nearest.location in result) continue
            val yaml = YamlFiles.yamlFile(project, nearest.file) ?: continue
            val typed = LoopItemTyper.typeOf(project, yaml, nearest.task, nearest.chain) ?: continue
            result[nearest.location] = typed.loop to LoopTask(nearest.location, SourceLocation(nearest.file, loopKeyOffset(nearest.task)))
        }
        return result.values.toList()
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

    /**
     * The keys of member [path] of the loop variable [name] in the literal items of [binding]'s loops
     * (`loop: [{dest: …}, {dest: …}]` → both `dest:` keys), deepest key written along [path]; empty for other names,
     * loops over variables and items without the key.
     */
    fun literalMemberKeys(project: Project, binding: Binding, name: String, path: List<String>): List<MemberKey> {
        if (path.isEmpty() || name != binding.loop.loopVar) return emptyList()
        val result = ArrayList<MemberKey>()
        for (loopTask in binding.tasks) {
            ProgressManager.checkCanceled()
            val task = taskAt(project, loopTask.task) ?: continue
            if (task.loopVar != name) continue
            val items = (task.loop?.value as? YSeq)?.items ?: continue
            for (item in items) {
                var value: YValue = item
                var found: MemberKey? = null
                for ((index, step) in path.withIndex()) {
                    val entry = (value as? YMap)?.entries?.lastOrNull { it.key.text == step } ?: break
                    val start = entry.key.range?.start ?: break
                    found = MemberKey(SourceLocation(loopTask.task.file, start), path.subList(0, index + 1))
                    value = entry.value
                }
                found?.let(result::add)
            }
        }
        return result
    }

    private fun templateBinding(project: Project, file: VirtualFile, name: String, view: MoleculeView?): Binding? {
        // Plan amendment R20, D153: outside Molecule, while Molecule is hidden, converge and verify tasks are no renderers.
        val contexts = MoleculeVisibility.contextsInView(
            project, view ?: MoleculeView.of(project, file), TemplateContextService.getInstance(project).renderContexts(file),
        )
        val matching = ArrayList<Pair<RenderLoop, LoopTask>>()
        for (context in contexts) {
            ProgressManager.checkCanceled()
            val loop = context.loop
            if (loop != null && name in LoopItemTyper.namesOf(loop)) {
                matching += loop to LoopTask(context.taskSite, loopKeyOf(project, context.taskSite))
                continue
            }
            // `template_vars` of a template lookup override everything; a task's own `vars:` lose to include params.
            if (context.kind == RenderKind.LOOKUP && name in context.taskVars) continue
            matching += includeLoops(project, context.taskSite.file, name, view)
        }
        return merge(matching)
    }

    /**
     * One binding for the loops [found] (in any order): their tasks in file order; the loop's variables and element as
     * far as they agree (literal item shapes are united, an iterated variable is kept only when all iterate the same).
     */
    private fun merge(found: List<Pair<RenderLoop, LoopTask>>): Binding? {
        if (found.isEmpty()) return null
        val tasks = found.map { it.second }.distinct().sortedWith(compareBy({ it.task.file.path }, { it.task.offset }))
        val loops = found.map { it.first }.distinct()
        if (loops.size == 1) return Binding(loops.single(), tasks)
        val source = loops.map { it.sourceVariable to it.sourcePath }.distinct().singleOrNull()
        val merged = RenderLoop(
            loopVar = loops.first().loopVar,
            indexVar = loops.firstNotNullOfOrNull { it.indexVar },
            extended = loops.any { it.extended },
            item = loops.mapNotNull { it.item }.distinct().reduceOrNull { a, b -> LiteralShapes.union(a, b) },
            sourceVariable = source?.first,
            sourcePath = source?.second.orEmpty(),
        )
        return Binding(merged, tasks)
    }

    /** The loop keyword of the task whose mapping starts at [taskSite], or the task itself. */
    private fun loopKeyOf(project: Project, taskSite: SourceLocation): SourceLocation {
        val task = taskAt(project, taskSite) ?: return taskSite
        return SourceLocation(taskSite.file, loopKeyOffset(task))
    }

    /** The task whose mapping holds [location], or null outside a YAML task list. */
    private fun taskAt(project: Project, location: SourceLocation): TaskNode? {
        val yaml = YamlFiles.yamlFile(project, location.file)?.takeIf { YamlPaths.isTopLevelSequence(it) } ?: return null
        return TaskChains.taskOf(TaskChains.chainAt(TaskFileModels.of(yaml), location.offset))
    }

    /** `the task at roles/web/tasks/main.yml:10`, or `2 rendering tasks (a.yml:3, b.yml:9)` for several. */
    fun tasksLabel(root: AnsibleRoot, tasks: List<LoopTask>): String {
        val labels = tasks.map { VarLocations.label(root, it.task) }
        return if (labels.size == 1) AnsibilityVarsBundle.message("card.loop.one.task", labels.single())
        else AnsibilityVarsBundle.message("card.loop.tasks", labels.size, labels.take(MAX_TASK_LABELS).joinToString(", "))
    }

    /**
     * What the loop card says about each task binding [name] (at most [MAX_TASK_LABELS], then how many more):
     * `loop variable of 'Apply rulesets' (include_tasks in roles/fw/tasks/rules.yml:8), iterates a list of 2 items`.
     */
    fun describe(project: Project, root: AnsibleRoot, binding: Binding, name: String): List<String> {
        val lines = binding.tasks.take(MAX_TASK_LABELS).map { describe(project, root, it, name) }
        val more = binding.tasks.size - lines.size
        return if (more > 0) lines + AnsibilityVarsBundle.message("card.loop.more", more) else lines
    }

    private fun describe(project: Project, root: AnsibleRoot, loopTask: LoopTask, name: String): String {
        val label = VarLocations.label(root, loopTask.task)
        val task = taskAt(project, loopTask.task)
        val action = task?.module?.name?.let { written -> ACTION_PREFIXES.firstOrNull { written.startsWith(it) }?.let { written.removePrefix(it) } ?: written }
        val index = task?.loopControl?.indexVar?.text == name && task.loopVar != name
        val title = task?.name?.text?.trim()?.takeIf { it.isNotEmpty() }
        val what = when {
            title != null && action != null -> AnsibilityVarsBundle.message(if (index) "card.loop.index.of" else "card.loop.of", title, action, label)
            action != null -> AnsibilityVarsBundle.message(if (index) "card.loop.index.of.unnamed" else "card.loop.of.unnamed", label, action)
            else -> AnsibilityVarsBundle.message("card.loop.task", AnsibilityVarsBundle.message("card.loop.one.task", label))
        }
        val iterates = task?.let { iterates(project, loopTask, it) } ?: return what
        return AnsibilityVarsBundle.message("card.loop.iterates", what, iterates)
    }

    /** `a list of 2 items`, the iterated variable (`fw_rules.chains`), or the loop expression; null when unknown. */
    private fun iterates(project: Project, loopTask: LoopTask, task: TaskNode): String? {
        val loop = task.loop ?: return null
        when (val value = loop.value) {
            is YSeq -> return AnsibilityVarsBundle.message("card.loop.iterates.literal", value.items.size)
            is YMap -> return AnsibilityVarsBundle.message("card.loop.iterates.mapping", value.entries.size)
            is YScalar -> {
                val yaml = YamlFiles.yamlFile(project, loopTask.task.file)
                val typed = yaml?.let { LoopItemTyper.typeOf(project, it, task, TaskChains.chainAt(TaskFileModels.of(it), task.range.startOffset)) }?.loop
                val variable = typed?.sourceVariable
                if (typed != null && variable != null) return (listOf(variable) + typed.sourcePath).joinToString(".")
                val text = value.text.trim().replace(Regex("\\s+"), " ")
                if (text.isEmpty()) return null
                return if (text.length <= MAX_EXPRESSION) text else text.take(MAX_EXPRESSION) + "…"
            }
            else -> return null
        }
    }

    private fun loopKeyOffset(task: TaskNode): Int = task.loop?.key?.range?.start ?: task.range.startOffset

    /** True when [offset] is inside the task's own loop expression, where the loop variable is not defined yet. */
    private fun inLoopValue(task: TaskNode, offset: Int): Boolean {
        val range = task.loop?.value?.range ?: return false
        return offset >= range.start && offset <= range.end
    }
}
