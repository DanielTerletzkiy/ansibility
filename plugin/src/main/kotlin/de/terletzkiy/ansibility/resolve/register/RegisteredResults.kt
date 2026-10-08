package de.terletzkiy.ansibility.resolve.register

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.semantics.registered.AsyncFeature
import de.terletzkiy.ansibility.semantics.registered.LoopFeature
import de.terletzkiy.ansibility.semantics.registered.ResultMember
import de.terletzkiy.ansibility.semantics.registered.ResultShape
import de.terletzkiy.ansibility.semantics.registered.ResultShapes
import de.terletzkiy.ansibility.semantics.registered.ResultSource
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.jetbrains.yaml.YAMLLanguage

/**
 * One task that registers a result (plan amendment FU, F1.12): where it registers, what it runs and the
 * [ResultSource] its result is built from.
 *
 * @property register the `register:` value (the [VarDefKind.REGISTER] definition's location)
 * @property task the task's mapping
 * @property taskName the task's `name:` as written, if any
 * @property module the module as written (`command`, `ansible.legacy.shell`); null when the task names none
 * @property canonical the module whose documentation applies after routing (`ansible.builtin.command`); null when no
 *   documentation source knows the module
 */
class RegisteringTask(
    val register: SourceLocation,
    val task: SourceLocation,
    val taskName: String?,
    val module: String?,
    val canonical: String?,
    val source: ResultSource,
) {
    /** The module to name in texts: the canonical one, else as written. */
    val moduleLabel: String? get() = canonical ?: module

    /**
     * This task as its own `until`/`changed_when`/`failed_when` see it: the result of the current run, which for a
     * looping task is one item's result (no `results`, and the item keys are added only after the run); the task
     * itself when it does not loop.
     */
    fun currentRun(): RegisteringTask =
        if (source.loop == null) this else RegisteringTask(register, task, taskName, module, canonical, source.copy(loop = null))

    override fun toString(): String = "RegisteringTask(${taskName ?: moduleLabel} @ ${register.file.name}:${register.offset})"
}

/**
 * The typed result of registered variable [name] as seen at one position of [root]: the union of the results of the
 * registering [tasks] ([ResultShapes]), each member saying which of them set it ([ResultMember.tasks] indexes
 * [tasks]). Immutable and PSI-free.
 */
class RegisteredResult(val root: AnsibleRoot, val name: String, val tasks: List<RegisteringTask>, val shape: ResultShape) {
    /** The member at [path] below the result (sequence indices step into list elements); null when undocumented. */
    fun member(path: List<String>): ResultMember? = shape.member(path)

    /**
     * The member at [path] as an option tree for the plugin's option walkers (loop items, completion of locals),
     * named [name] for the whole result; null when undocumented.
     */
    fun option(path: List<String>): OptionSpec? {
        val member = member(path) ?: return null
        return if (path.isEmpty()) member.option.copy(name = name) else member.option
    }

    /** The registering tasks that set [member]. */
    fun tasksOf(member: ResultMember): List<RegisteringTask> = member.tasks.mapNotNull(tasks::getOrNull)

    override fun toString(): String = "RegisteredResult($name from ${tasks.size} task(s))"
}

/**
 * Resolves registered variables to their typed results (plan amendment FU, F1.12; the project service of WU RV1):
 *
 * 1. **Visibility.** The `register:` definitions of a name that a position sees ([RegisterVisibility]: the own role
 *    before the position in role order, the task's own register in its `until`/`changed_when`/`failed_when` (as the
 *    result of the current run: one item's result for a looping task), the roles of the play otherwise), always
 *    inside the position's root.
 * 2. **Tasks.** Each definition's task from [TaskFileModels]: its module through the routing of [AnsibleDocService]
 *    (short names, `ansible.legacy`, redirects), the module's documented returns and `backup` option, and the task
 *    features (`loop`/`with_*` with `loop_control`, `until`/`retries`, `async`/`poll`).
 * 3. **Shapes.** [ResultShapes] builds the union of the tasks' results.
 *
 * Tasks and shapes live in [ModelCache]s (DEV.md rule 9): a task entry depends on the content stamp of its file and on
 * the documentation tracker (plus every YAML change when its loop item is typed through a variable); a shape entry on
 * its task entries. Call in a read action in smart mode.
 */
@Service(Service.Level.PROJECT)
class RegisteredResults(private val project: Project) {
    private data class TaskKey(val fileUrl: String, val offset: Int, val rootUrl: String)

    /** [currentRun]: the tasks seen as their own result keys see them ([RegisteringTask.currentRun]). */
    private data class ResultKey(val rootUrl: String, val name: String, val tasks: List<TaskKey>, val currentRun: Set<TaskKey>)

    private val taskCache = ModelCache<TaskKey, RegisteringTask?>(project, "registered.tasks", maxSize = MAX_TASKS)
    private val resultCache = ModelCache<ResultKey, RegisteredResult?>(project, "registered.results", maxSize = MAX_RESULTS)

    /** Task entries being computed on this thread: a loop over a registered result that leads back is cut. */
    private val computing = ThreadLocal.withInitial { HashSet<TaskKey>() }

    /**
     * The result of [name] as the code at [offset] of [file] sees it; null when no visible `register:` defines it.
     * Inside the `until`/`changed_when`/`failed_when` of the registering task itself, that task contributes the result
     * of the current run: one item's result for a looping task ([RegisteringTask.currentRun]). [view] is what the
     * request sees of Molecule ([RegisterVisibility]; navigation, cards and completion pass their origin's).
     */
    fun at(file: VirtualFile, offset: Int, name: String, view: MoleculeView = MoleculeView.INCLUDE): RegisteredResult? {
        val root = AnsibleWorkspace.getInstance(project).contextOf(file)?.root ?: return null
        val definitions = RegisterVisibility.visible(project, file, offset, name, view)
        if (definitions.isEmpty()) return null
        val ownTask = RegisterVisibility.resultKeysTask(project, file, offset)
        val currentRun = if (ownTask == null) {
            emptySet()
        } else {
            definitions.mapNotNullTo(HashSet()) { definition -> definition.location.takeIf { it.file == file && ownTask.containsOffset(it.offset) } }
        }
        return of(root, name, definitions, currentRun)
    }

    /**
     * The option tree of `name.path…` at [site] for option walkers; null when no visible `register:` sets [name] or the
     * result does not document [path] (callers that must tell the two apart, like [LoopItemTyper], use [at]).
     */
    fun optionAt(site: SourceLocation, name: String, path: List<String>): OptionSpec? = at(site.file, site.offset, name)?.option(path)

    /**
     * The result of [name] for a position-free reader (ANS-T020 type flow, a card reached through a link): the union over
     * every `register:` of the name in [root] inside [scopeDir] (a role directory), or in the whole root when [scopeDir]
     * is null, as [view] sees them.
     */
    fun inScope(root: AnsibleRoot, scopeDir: VirtualFile?, name: String, view: MoleculeView = MoleculeView.INCLUDE): RegisteredResult? {
        val registers = RegisterVisibility.registersOf(project, root, name, view)
            .filter { scopeDir == null || VfsUtilCore.isAncestor(scopeDir, it.location.file, true) }
        return of(root, name, registers)
    }

    /** The union result of the `register:` [definitions] of [name] in [root] (other kinds are ignored). */
    fun of(root: AnsibleRoot, name: String, definitions: List<VarDefinition>): RegisteredResult? = of(root, name, definitions, emptySet())

    /** [of], with the registers at [currentRun] contributing the result of the current run ([RegisteringTask.currentRun]). */
    private fun of(root: AnsibleRoot, name: String, definitions: List<VarDefinition>, currentRun: Set<SourceLocation>): RegisteredResult? {
        val registers = definitions.filter { it.kind == VarDefKind.REGISTER && it.name == name }
        if (registers.isEmpty()) return null
        val keys = registers.map { taskKey(root, it.location) }
        val runKeys = registers.zip(keys).filter { (definition, _) -> definition.location in currentRun }.mapTo(HashSet()) { it.second }
        return resultCache.get(ResultKey(root.dir.url, name, keys, runKeys)) {
            val tasks = registers.zip(keys).mapNotNull { (definition, key) ->
                ProgressManager.checkCanceled()
                registeringTask(root, definition.location, key)?.let { if (key in runKeys) it.currentRun() else it }
            }
            if (tasks.isEmpty()) null else RegisteredResult(root, name, tasks, ResultShapes.build(name, tasks.map { it.source }))
        }
    }

    // ------------------------------------------------------------------------------------------------ tasks

    private fun taskKey(root: AnsibleRoot, location: SourceLocation) = TaskKey(location.file.url, location.offset, root.dir.url)

    private fun registeringTask(root: AnsibleRoot, register: SourceLocation, key: TaskKey): RegisteringTask? {
        val inProgress = computing.get()
        if (!inProgress.add(key)) return null
        try {
            return taskCache.get(key) { computeTask(root, register) }
        } finally {
            inProgress.remove(key)
        }
    }

    private fun computeTask(root: AnsibleRoot, register: SourceLocation): RegisteringTask? {
        val file = register.file
        ModelInputs.file(project, file)
        val yaml = YamlFiles.yamlFile(project, file) ?: return null
        val model = TaskFileModels.of(yaml)
        val chain = TaskChains.chainAt(model, register.offset)
        // The definition points at the `register:` value, inside the registering task's mapping.
        val task = TaskChains.taskOf(chain)?.takeIf { it.register != null } ?: return null
        val docs = AnsibleDocService.getInstance(project)
        ModelInputs.external(docs.docsTracker.modificationCount) { docs.docsTracker.modificationCount }
        val call = task.module
        val resolved = call?.let { docs.moduleDoc(root, it.name) }
        val doc = resolved?.doc
        val loop = task.loop?.let {
            val typed = LoopItemTyper.typeOf(project, yaml, task, chain)?.loop
            if (typed?.sourceVariable != null) {
                // The item type comes from definitions in other files: follow every YAML change, like the loop typer.
                val tracker = PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE)
                ModelInputs.external(tracker.modificationCount) { tracker.modificationCount }
            }
            LoopFeature(
                loopVar = typed?.loopVar ?: task.loopVar ?: ResultShapes.DEFAULT_LOOP_VAR,
                indexVar = typed?.indexVar ?: task.loopControl?.indexVar?.text,
                extended = typed?.extended == true,
                item = typed?.item,
            )
        }
        val source = ResultSource(
            module = resolved?.canonical ?: call?.canonical,
            returns = doc?.returns,
            backup = doc?.options?.containsKey(BACKUP) == true,
            loop = loop,
            retried = isRetried(task),
            async = asyncOf(task, root, docs),
        )
        return RegisteringTask(
            register = register,
            task = SourceLocation(file, task.range.startOffset),
            taskName = task.name?.text?.takeIf { it.isNotBlank() },
            module = call?.name,
            canonical = resolved?.canonical?.takeIf { doc != null },
            source = source,
        )
    }

    /**
     * Whether ansible-core retries the task (`TaskExecutor._run_loop` → `_execute`: one run plus `retries`, or 3 when
     * only `until` is set), so its result carries `attempts`: `until:` without `retries: 0`, or `retries:` above 0. A
     * templated value counts as retrying.
     */
    private fun isRetried(task: TaskNode): Boolean {
        val retries = task.keywords[RETRIES]?.value?.let(::intValue)
        val hasRetries = task.keywords.containsKey(RETRIES)
        val hasUntil = task.keywords.containsKey(UNTIL) || task.expressions.any { it.key == UNTIL }
        return when {
            hasRetries -> retries == null || retries > 0
            else -> hasUntil
        }
    }

    /** The task's `async:` (above 0, or templated) with its `poll:` (default 15; 0 starts the job and moves on). */
    private fun asyncOf(task: TaskNode, root: AnsibleRoot, docs: AnsibleDocService): AsyncFeature? {
        val entry = task.keywords[ASYNC] ?: return null
        val seconds = intValue(entry.value)
        if (seconds != null && seconds <= 0) return null
        val poll = task.keywords[POLL]?.value?.let(::intValue)
        val statusReturns = docs.moduleDoc(root, ResultShapes.ASYNC_STATUS)?.doc?.returns
        return AsyncFeature(polled = poll == null || poll > 0, statusReturns = statusReturns)
    }

    /** A literal integer value (`3`, `"3"`), or null for templates and anything else. */
    private fun intValue(value: YValue): Long? {
        val scalar = value as? YScalar ?: return null
        return when (val resolved = scalar.resolved) {
            is Resolved.Int -> resolved.value.toLong()
            is Resolved.Str -> resolved.value.trim().toLongOrNull()
            else -> null
        }
    }

    companion object {
        private const val BACKUP = "backup"
        private const val RETRIES = "retries"
        private const val UNTIL = "until"
        private const val ASYNC = "async"
        private const val POLL = "poll"
        private const val MAX_TASKS = 4096
        private const val MAX_RESULTS = 1024

        fun getInstance(project: Project): RegisteredResults = project.service()
    }
}
