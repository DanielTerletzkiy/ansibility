package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.PlayRoleEntry
import de.terletzkiy.ansibility.api.RoleEntryKind
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.lang.jinja.injection.JinjaYamlInjections
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.ImplicitExpression
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModel
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * What the way to a use tells (plan amendment R7/R8, F8.12 (c) and (d)): the names its conditions prove [defined], and
 * whether the use may not run at all ([conditional]): a `when`, a loop that may be empty, `run_once`, a `rescue`
 * section or a handler on the way. A conditional use fails only where its conditions hold, so it never proves a
 * certain failure.
 */
internal class Gate(val defined: Set<String>, val conditional: Boolean) {
    /** One after the other: both gates are passed. */
    operator fun plus(other: Gate): Gate = Gate(defined + other.defined, conditional || other.conditional)

    /** Either way may be taken: only what both prove, conditional only when both are. */
    infix fun or(other: Gate): Gate = Gate(defined intersect other.defined, conditional && other.conditional)

    override fun toString(): String = "Gate($defined${if (conditional) ", conditional" else ""})"

    companion object {
        /** Nothing proved, runs unconditionally. */
        val OPEN: Gate = Gate(emptySet(), false)
    }
}

/**
 * The gates of tasks (plan amendment R7/R8, F8.12 guard analysis (c)): the variable names a task's conditions prove
 * defined before anything else of the task is evaluated, and whether the task may not run. ansible-core evaluates
 * `when` first; a `loop` whose templating fails on an undefined name is deferred until the condition is known (probed
 * on 2.18.8 and 2.21.4: the task is skipped), so the guard covers every key of the task except its own earlier `when`
 * items.
 *
 * A task is gated by
 * - its own `when` items and those of every enclosing `block`, a loop that is not a non-empty literal list (for every
 *   key but the loop itself), `run_once`, and a `rescue` section on the way ([chainGate]);
 * - its **include chain** inside the role: the gates of every `include_tasks`/`import_tasks` path from the role's
 *   entry file (`tasks/main.yml`, or the `tasks_from` file) to the task's file, combined over the paths
 *   ([includeGate]);
 * - the role's application in a play: the `when` of its `roles:` entry, or the gate of the `include_role`/
 *   `import_role` task and its blocks, combined over the play's applications ([playGate]); a `meta/main.yml`
 *   dependency has none.
 *
 * Definedness facts are those of [ConditionFacts] (`x is defined`, `x | default(false)`, combinations through `and`,
 * `or`, `not`): read from the injected fragment of the `when` scalar, or, without one, from its tokens. One instance
 * memoises per analysis run; call in a read action.
 */
internal class TaskGuards(private val project: Project, private val rules: GuardRules) {
    private val includes = HashMap<Pair<VirtualFile, String>, Map<VirtualFile, Gate>>()
    private val applications = HashMap<Pair<PlayRef, PlayRoleEntry>, Gate>()
    private val textFacts = HashMap<String, Set<String>>()

    /**
     * The gate of the task chain at [offset] in [file]. A use inside the innermost item's own `when` list sees only the
     * items before it (they are evaluated in order), and a use in its loop is not gated by the loop; [loopGates] false
     * ignores the innermost task's loop (a fileglob loop that selects the rendered template itself). A play's
     * `handlers:` are conditional.
     */
    fun chainGate(file: VirtualFile, offset: Int, loopGates: Boolean = true): Gate {
        val yaml = YamlFiles.yamlFile(project, file) ?: return Gate.OPEN
        val model = TaskFileModels.of(yaml)
        val chain = TaskChains.chainAt(model, offset)
        val handler = model.playAt(offset)?.handlers?.any { it.range.containsOffset(offset) } == true
        return chainGate(yaml, chain, offset, loopGates).let { if (handler) Gate(it.defined, true) else it }
    }

    /** [chainGate] for an already located [chain] in [yaml]; [offset] -1 gates the whole innermost item. */
    fun chainGate(yaml: YAMLFile, chain: List<TaskItem>, offset: Int, loopGates: Boolean = true): Gate {
        val names = HashSet<String>()
        var conditional = false
        chain.forEachIndexed { index, item ->
            val conditions = item.conditions
            val inner = index == chain.lastIndex
            val own = if (inner && offset >= 0) conditions.indexOfFirst { it.range.containsOffset(offset) } else -1
            val applying = if (own >= 0) conditions.subList(0, own) else conditions
            names += conditionGuards(yaml, applying)
            if (applying.isNotEmpty() || runsOnce(item)) conditional = true
            if (item is BlockNode) chain.getOrNull(index + 1)?.let { next -> if (item.rescue.any { it === next }) conditional = true }
            if (inner && loopGates && item is TaskNode && mayLoopZeroTimes(item, offset)) conditional = true
        }
        return Gate(names, conditional)
    }

    /** The names a list of `when` [conditions] of [yaml] (all of which must hold) proves defined. */
    fun conditionGuards(yaml: YAMLFile, conditions: List<ImplicitExpression>): Set<String> =
        conditions.flatMapTo(HashSet()) { whenTrue(yaml, it) }

    /**
     * The gate of every include path from the entry file of [entryPoint] in the role at [roleDir] to [file]; open for
     * the entry file itself and for files no literal include of the role reaches.
     */
    fun includeGate(roleDir: VirtualFile, entryPoint: String, file: VirtualFile): Gate = includeMap(roleDir, entryPoint)[file] ?: Gate.OPEN

    /**
     * Whether [file] (a file of the role's `tasks/`) runs when the role at [roleDir] is entered at one of [entryPoints]:
     * it is an entry file or a literal `include_tasks`/`import_tasks` chain reaches it. A file only a templated include
     * or nothing names (a commented-out include) is not known to run.
     */
    fun runs(roleDir: VirtualFile, entryPoints: Collection<String>, file: VirtualFile): Boolean =
        entryPoints.any { file in includeMap(roleDir, it) }

    private fun includeMap(roleDir: VirtualFile, entryPoint: String): Map<VirtualFile, Gate> =
        includes.getOrPut(roleDir to entryPoint) { computeIncludes(roleDir, entryPoint) }

    /**
     * The gate for a task of [taskFile] (a file of the role at [roleDir], or null for a template's own statements) when
     * [play] runs the role: over the play's applications of the role, the application's own gate and the include chain
     * from its entry point. Open when the play applies the role only through other roles.
     */
    fun playGate(play: PlayRef, roleDir: VirtualFile, taskFile: VirtualFile?): Gate {
        val applied = PlayGraph.getInstance(project).rolesOfPlay(play).filter { it.role?.dir == roleDir }
        if (applied.isEmpty()) return Gate.OPEN
        val tasksDir = roleDir.findChild(RoleLayout.TASKS)
        val underTasks = taskFile != null && tasksDir != null && VfsUtilCore.isAncestor(tasksDir, taskFile, true)
        var result: Gate? = null
        for (application in applied) {
            ProgressManager.checkCanceled()
            val own = applications.getOrPut(play to application) { applicationGate(play, application) }
            val chained = if (underTasks) includeGate(roleDir, application.entryPoint, taskFile!!) else Gate.OPEN
            val gate = own + chained
            result = result?.or(gate) ?: gate
        }
        return result ?: Gate.OPEN
    }

    /** The gate of the `roles:` entry or `include_role`/`import_role` task (with its blocks) of [application]. */
    private fun applicationGate(play: PlayRef, application: PlayRoleEntry): Gate {
        val location = application.location?.takeIf { it.file == play.file } ?: return Gate.OPEN
        val yaml = YamlFiles.yamlFile(project, play.file) ?: return Gate.OPEN
        val model = TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK)
        val node = model.plays.getOrNull(play.playIndex) ?: return Gate.OPEN
        return when (application.kind) {
            RoleEntryKind.PLAY_ROLE -> {
                val entry = node.roles.firstOrNull { it.name?.range?.startOffset == location.offset } ?: return Gate.OPEN
                val conditions = entry.expressions.filter { it.key == WHEN }
                Gate(conditionGuards(yaml, conditions), conditions.isNotEmpty())
            }
            RoleEntryKind.INCLUDE_ROLE, RoleEntryKind.IMPORT_ROLE -> {
                val chain = TaskChains.chainAt(model, location.offset)
                val handler = node.handlers.any { it.range.containsOffset(location.offset) }
                chainGate(yaml, chain, -1).let { if (handler) Gate(it.defined, true) else it }
            }
            RoleEntryKind.DEPENDENCY -> Gate.OPEN
        }
    }

    // ------------------------------------------------------------------------------------------------ include chains

    /** One literal `include_tasks`/`import_tasks` edge and the gate of its own task chain. */
    private class Edge(val to: VirtualFile, val gate: Gate)

    private fun computeIncludes(roleDir: VirtualFile, entryPoint: String): Map<VirtualFile, Gate> {
        val tasksDir = roleDir.findChild(RoleLayout.TASKS) ?: return emptyMap()
        val entry = entryFile(tasksDir, entryPoint) ?: return emptyMap()
        val edges = HashMap<VirtualFile, MutableList<Edge>>()
        for (file in RoleLayout.taskFiles(roleDir)) {
            ProgressManager.checkCanceled()
            val yaml = YamlFiles.yamlFile(project, file) ?: continue
            collectEdges(yaml, file, TaskFileModels.of(yaml, TaskFileKind.TASKS), roleDir, tasksDir) { edges.getOrPut(file) { ArrayList() } += it }
        }
        val gates = HashMap<VirtualFile, Gate>()
        gates[entry] = Gate.OPEN
        val queue = ArrayDeque(listOf(entry))
        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val from = queue.removeFirst()
            val known = gates.getValue(from)
            for (edge in edges[from].orEmpty()) {
                val candidate = known + edge.gate
                val previous = gates[edge.to]
                val next = previous?.or(candidate) ?: candidate
                if (previous == null || next.defined != previous.defined || next.conditional != previous.conditional) {
                    gates[edge.to] = next
                    queue.addLast(edge.to)
                }
            }
        }
        return gates
    }

    private fun collectEdges(yaml: YAMLFile, file: VirtualFile, model: TaskFileModel, roleDir: VirtualFile, tasksDir: VirtualFile, add: (Edge) -> Unit) {
        fun visit(items: List<TaskItem>, outer: List<TaskItem>) {
            for (item in items) {
                val chain = outer + item
                when (item) {
                    is BlockNode -> {
                        visit(item.block, chain)
                        visit(item.rescue, chain)
                        visit(item.always, chain)
                    }
                    is TaskNode -> {
                        val path = item.taskInclude?.file?.text?.trim()?.takeIf { it.isNotEmpty() && !JinjaBearing.hasTemplateMarkers(it) } ?: continue
                        val target = listOfNotNull(file.parent, tasksDir, roleDir)
                            .firstNotNullOfOrNull { dir -> dir.findFileByRelativePath(path)?.takeIf { !it.isDirectory } } ?: continue
                        add(Edge(target, chainGate(yaml, chain, -1)))
                    }
                }
            }
        }
        visit(model.items, emptyList())
    }

    private fun entryFile(tasksDir: VirtualFile, entryPoint: String): VirtualFile? =
        listOf("$entryPoint.yml", "$entryPoint.yaml", entryPoint).firstNotNullOfOrNull { tasksDir.findFileByRelativePath(it)?.takeIf { f -> !f.isDirectory } }

    // ------------------------------------------------------------------------------------------------ conditions

    /** `run_once` with any value but a literal false: the item runs for one host of the batch only. */
    private fun runsOnce(item: TaskItem): Boolean {
        val value = item.keywords[RUN_ONCE]?.value ?: return false
        return (value as? YScalar)?.text?.trim()?.lowercase() !in FALSE_WORDS
    }

    /**
     * Whether [task]'s loop may run zero times for a use at [offset] (outside the loop's own key and value): any loop
     * but a non-empty literal list.
     */
    private fun mayLoopZeroTimes(task: TaskNode, offset: Int): Boolean {
        val loop = task.loop ?: return false
        val start = loop.key.range?.start
        val end = loop.value.range?.end
        if (offset >= 0 && start != null && end != null && offset in start..end) return false
        return (loop.value as? YSeq)?.items?.isNotEmpty() != true
    }

    /** The names [condition] proves defined when it is true. */
    private fun whenTrue(yaml: YAMLFile, condition: ImplicitExpression): Set<String> {
        val scalar = yaml.findElementAt(condition.range.startOffset)?.let { PsiTreeUtil.getParentOfType(it, YAMLScalar::class.java, false) }
        val fragment = scalar?.takeIf { it.textRange.startOffset == condition.range.startOffset }?.let(JinjaYamlInjections::injectedFile)
        val expression = fragment?.outputTags?.singleOrNull()?.expression
        if (expression != null && fragment.statements.isEmpty()) return JinjaConditions.whenTrue(expression, rules)
        return textFacts.getOrPut(condition.value.text) { textWhenTrue(condition.value.text, rules) }
    }

    private companion object {
        const val WHEN = "when"
        const val RUN_ONCE = "run_once"
        val FALSE_WORDS = setOf("false", "no", "off", "0", "n")

        /**
         * The text-level facts of a `when` condition without an injected fragment: the same [Cond] tree, built from
         * tokens ([JinjaTokenExpressions]). A `"{{ … }}"` condition (the X30 hazard) is read without its delimiters.
         */
        fun textWhenTrue(text: String, rules: GuardRules): Set<String> {
            val trimmed = text.trim()
            val condition = if (trimmed.startsWith("{{") && trimmed.endsWith("}}") && trimmed.indexOf("{{", 2) < 0) {
                trimmed.substring(2, trimmed.length - 2)
            } else {
                trimmed
            }
            if (JinjaBearing.hasTemplateMarkers(condition)) return emptySet()
            val tokens = JinjaTokenExpressions.tokens(condition, JinjaLexMode.EXPRESSION)
            val node = JinjaTokenExpressions.expression(condition, tokens, 0, tokens.size) ?: return emptySet()
            return ConditionFacts.of(JinjaTokenExpressions.cond(node), rules).whenTrue
        }
    }
}
