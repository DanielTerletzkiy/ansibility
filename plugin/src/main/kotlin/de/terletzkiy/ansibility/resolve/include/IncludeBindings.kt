package de.terletzkiy.ansibility.resolve.include

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.api.RoleEntryKind
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.ModuleUseIndex
import de.terletzkiy.ansibility.index.PathFacts
import de.terletzkiy.ansibility.index.TaskKeywords
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.role.RoleMeta
import de.terletzkiy.ansibility.model.task.IncludeKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.navigation.RoleLocator
import de.terletzkiy.ansibility.resolve.loop.LoopItemTyper
import de.terletzkiy.ansibility.resolve.loop.TaskChains
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.jetbrains.yaml.YAMLLanguage
import java.util.concurrent.ConcurrentHashMap

/** One key of an include task's (or its blocks') `vars:` mapping: what the include gives the included file under [name]. */
internal class IncludeVar(val name: String, val value: YValue, val location: SourceLocation)

/**
 * One include task that runs a task file: an `include_tasks`/`import_tasks` of a role task file, or an
 * `include_role`/`import_role` (anywhere in the root) whose entry file (`tasks_from`, else `main`) is that file or leads
 * to it. PSI-free: the task as the task model read it.
 */
internal class Includer(
    /** The file the include task is written in. */
    val file: VirtualFile,
    /** The include task. */
    val task: TaskNode,
    /** The task with its enclosing blocks, outermost first. */
    val chain: List<TaskItem>,
    /** [IncludeKind.INCLUDE] for `include_tasks`/`include_role` (dynamic), [IncludeKind.IMPORT] for the static imports. */
    val kind: IncludeKind,
    /** True for `include_role`/`import_role`, false for `include_tasks`/`import_tasks`. */
    val role: Boolean,
) {
    /** Where the include task's mapping starts. */
    val location: SourceLocation get() = SourceLocation(file, task.range.startOffset)

    /**
     * The `vars:` of the include task and of its enclosing blocks, outer to inner (an inner key wins): ansible-core
     * applies them to every task of the included file, for includes and imports alike (`include_role` vars are role
     * params of the included role).
     */
    val vars: Map<String, IncludeVar> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val result = LinkedHashMap<String, IncludeVar>()
        for (item in chain) {
            TaskChains.varsOf(item)?.entries?.forEach { entry ->
                val name = entry.key.text
                val offset = entry.key.range?.start ?: item.range.startOffset
                result.remove(name)
                result[name] = IncludeVar(name, entry.value, SourceLocation(file, offset))
            }
        }
        result
    }

    /**
     * Whether the include binds loop names inside the included file: only a dynamic include (`include_tasks`,
     * `include_role`) with a loop; ansible-core rejects a loop on `import_tasks`/`import_role`.
     */
    val loops: Boolean get() = kind == IncludeKind.INCLUDE && task.loop != null

    /** The loop variable the include binds (`loop_control.loop_var`, else `item`), or null when it binds no loop. */
    val loopVar: String? get() = if (loops) task.loopVar else null

    /** The names the include's loop binds: the loop variable, `index_var`, and `ansible_loop` with `extended`. */
    val loopNames: List<String>
        get() {
            if (!loops) return emptyList()
            val control = task.loopControl
            return listOfNotNull(
                task.loopVar,
                control?.indexVar?.text,
                LoopItemTyper.ANSIBLE_LOOP.takeIf { (control?.extended as? YScalar)?.text?.lowercase() in TRUE },
            )
        }

    /** Every name the include gives the included file: its loop names and its `vars:` keys. */
    val names: Set<String> get() = LinkedHashSet<String>().also { it += loopNames; it += vars.keys }

    /**
     * The keys of the include task's own `vars:` (not its blocks'): for an `include_role`/`import_role` in a play, the role
     * params the model evaluates per play (`ExecutionSources`).
     */
    val ownVarNames: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TaskChains.varsOf(task)?.entries?.mapTo(LinkedHashSet()) { it.key.text } ?: emptySet()
    }

    /**
     * True for a dynamic include (`include_tasks`, `include_role`): its `vars:` and loop names are include params, which
     * ansible-core (`Task.get_include_params`) applies above the included tasks' own `vars:`, `include_vars`, `set_fact`
     * and role params; an import's `vars:` are task vars of the imported tasks, below their own.
     */
    val dynamic: Boolean get() = kind == IncludeKind.INCLUDE

    fun provides(name: String): Boolean = name in loopNames || name in vars

    /**
     * Where the include writes [name] as a definition the index records: the `vars:` key, or the value of
     * `loop_control.loop_var`/`index_var`; null for `item` and `ansible_loop` (nothing is written) and other names.
     */
    fun definitionsOf(name: String): List<SourceLocation> {
        val result = ArrayList<SourceLocation>(1)
        vars[name]?.let { result += it.location }
        if (loops) {
            val control = task.loopControl
            control?.loopVar?.takeIf { it.text == name }?.let { result += SourceLocation(file, it.range.startOffset) }
            control?.indexVar?.takeIf { it.text == name }?.let { result += SourceLocation(file, it.range.startOffset) }
        }
        return result
    }

    override fun toString(): String = "Includer(${file.name}@${task.range.startOffset} ${if (role) "role" else "tasks"} $kind)"

    private companion object {
        val TRUE = setOf("true", "yes", "on")
    }
}

/**
 * One way a task file is run: through includes, its direct includer first, then the includer of that includer's file,
 * outward; or, with no includer at all ([direct]), directly: a play applies the role whose entry file it is (`roles:`,
 * a dependency) while include tasks run it too. Inner includers win among their kind: their `vars:` override outer ones
 * and their loop names shadow outer ones; a dynamic include's `vars:` ([params]) beat an import's ([importVars]).
 */
internal class IncludePath(val includers: List<Includer>) {
    /** The direct includer of the file; null for a [direct] run. */
    val nearest: Includer? get() = includers.firstOrNull()

    /** The file runs without any include on this path: a play applies its role directly. */
    val direct: Boolean get() = includers.isEmpty()

    /** Whether some includer of the path gives the file [name]. */
    fun provides(name: String): Boolean = includers.any { it.provides(name) }

    /**
     * The includer whose binding of [name] the file sees on this path: the nearest dynamic include that gives it
     * (include params beat task vars), else the nearest import that gives it; null when none does.
     */
    fun winnerOf(name: String): Includer? =
        includers.firstOrNull { it.dynamic && it.provides(name) } ?: includers.firstOrNull { it.provides(name) }

    /** The `vars:` the path gives the file, outermost first, so an inner key overrides an outer one. */
    fun vars(): Map<String, IncludeVar> = merged(includers)

    /** The include params of the path: the `vars:` of its dynamic includes, outermost first (an inner key wins). */
    fun params(): Map<String, IncludeVar> = merged(includers.filter { it.dynamic })

    /** The `vars:` of the path's imports, outermost first: task vars of the imported tasks, below their own `vars:`. */
    fun importVars(): Map<String, IncludeVar> = merged(includers.filter { !it.dynamic })

    /** The part of the path outside [includer] (the includers that run [includer]'s file), nearest first. */
    fun outside(includer: Includer): IncludePath = IncludePath(includers.drop(includers.indexOf(includer) + 1))

    override fun toString(): String = if (direct) "direct" else includers.joinToString(" <- ")

    private companion object {
        fun merged(includers: List<Includer>): Map<String, IncludeVar> {
            val result = LinkedHashMap<String, IncludeVar>()
            for (includer in includers.asReversed()) {
                for ((name, variable) in includer.vars) {
                    result.remove(name)
                    result[name] = variable
                }
            }
            return result
        }
    }
}

/** The include paths of a file ([IncludeBindings.pathsOf]); [truncated] when more paths exist than were followed. */
internal class IncludePaths(val paths: List<IncludePath>, val truncated: Boolean)

/**
 * Which includers give a file (or the rendering tasks of a template) a name: [providers] do (each once); the include
 * paths through [missing] (their nearest includers) do not, nor does a [direct] run of the file, and for a template
 * neither do the rendering tasks at [unprovidedRenders], which nothing includes and which do not set the name
 * themselves. [winners] are, per path that gives the name, the includer whose binding the file sees there
 * ([IncludePath.winnerOf]).
 */
internal class IncludeCoverage(
    val providers: List<Includer>,
    val missing: List<Includer>,
    val unprovidedRenders: List<SourceLocation> = emptyList(),
    /** Some run of the file goes through no include (a play applies its role directly) and so does not get the name. */
    val direct: Boolean = false,
    /** More include paths exist than [IncludeBindings.MAX_PATHS] (or deeper than [IncludeBindings.MAX_DEPTH]): the rest are unknown. */
    val truncated: Boolean = false,
    val winners: List<Includer> = emptyList(),
) {
    /** Some includer gives the name (ANS-V003 counts it as set: the "any includer" rule). */
    val provided: Boolean get() = providers.isNotEmpty()

    /** Every include path (and every rendering task of a template) gives the name, and no run of the file goes without. */
    val everywhere: Boolean get() = providers.isNotEmpty() && missing.isEmpty() && unprovidedRenders.isEmpty() && !direct && !truncated

    /**
     * Every path gives the name through a dynamic include ([everywhere], and each path's winner is an `include_tasks` or
     * `include_role`): include params, which beat the included tasks' own `vars:`, `set_fact` and role params.
     */
    val dynamicEverywhere: Boolean get() = everywhere && winners.isNotEmpty() && winners.all { it.dynamic }

    /** Every includer that gives the name gives it through its loop (the loop variable, `index_var`, `ansible_loop`). */
    fun byLoop(name: String): Boolean = providers.isNotEmpty() && providers.all { name in it.loopNames }

    companion object {
        val NONE = IncludeCoverage(emptyList(), emptyList())

        /** The coverage of several files at once (each once): a name is set everywhere only when it is in each of them. */
        fun union(coverages: List<IncludeCoverage>): IncludeCoverage {
            if (coverages.size == 1) return coverages.single()
            fun <T> distinct(items: List<T>, key: (T) -> Any): List<T> = items.distinctBy(key)
            return IncludeCoverage(
                distinct(coverages.flatMap { it.providers }) { it.location },
                distinct(coverages.flatMap { it.missing }) { it.location },
                coverages.flatMap { it.unprovidedRenders }.distinct(),
                coverages.any { it.direct },
                coverages.any { it.truncated },
                distinct(coverages.flatMap { it.winners }) { it.location },
            )
        }
    }
}

/**
 * What the includers give an included task file (the shared rule of ANS-V003, the variable card, the rendered previews,
 * the loop bindings and completion): every include task that runs the file, transitively.
 *
 * - **Sources:** the literal `include_tasks`/`import_tasks` of the role's task files (role order, [IncludeGraph]), and
 *   the `include_role`/`import_role` tasks of the root (playbooks, other roles, Molecule playbooks) whose literal role
 *   name finds this role and whose `tasks_from` is literal (a templated one names no file statically); they reach the
 *   role's entry file (`tasks_from`, else `main`) and, through the role's own includes, every file it includes. Task
 *   files outside roles and handler files have no includers here.
 * - **Direct runs:** a role entry file that include tasks run and that a play also applies directly (`roles:`, a
 *   `meta/main.yml` dependency) has a path without includers too ([IncludePath.direct]): it is not set "everywhere".
 * - **Bindings:** an include gives the file its `vars:` and its blocks' `vars:` (includes and imports alike) and, only for
 *   a dynamic include with a loop, the loop variable (`item` or `loop_control.loop_var`), `index_var` and, with
 *   `extended`, `ansible_loop` ([Includer]).
 * - **Molecule** (plan amendment R20): an includer in a Molecule file binds nothing for a request whose [MoleculeView]
 *   excludes Molecule. Production files pass [MoleculeView.forAnalysis] (Molecule includers never bind there, for
 *   analysis and presentation alike); a search that starts in Molecule may pass [MoleculeView.INCLUDE].
 * - **"Any includer"**: [names] is ANS-V003's union over the includers (a name is set when some includer sets it, minus
 *   the role params the witness evaluates per play); [coverage] tells whether every path does, for the card.
 *
 * Call in a read action in smart mode (the `include_role` source reads an index).
 */
internal object IncludeBindings {
    /** Include paths returned at most ([paths]). */
    const val MAX_PATHS: Int = 20

    /** Include levels followed at most. */
    const val MAX_DEPTH: Int = 16

    /** Playbook kinds: an `include_role` written there is a role entry of its play. */
    private val PLAYBOOK_KINDS = setOf(FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK)

    /** The include tasks that run [file] directly, as [view] sees them (file order). */
    fun directIncluders(project: Project, file: VirtualFile, view: MoleculeView): List<Includer> {
        val all = IncludeGraph.getInstance(project).includersOf(file)
        if (view.includesMolecule || all.isEmpty()) return all
        return all.filter { !MoleculeVisibility.isMoleculeFile(project, it.file) }
    }

    /** Every include task that runs [file], transitively, nearest first; each include task once. */
    fun includers(project: Project, file: VirtualFile, view: MoleculeView): List<Includer> {
        val result = ArrayList<Includer>()
        visit(project, file, view) { includer, _ -> result += includer }
        return result
    }

    /** Calls [action] with every include task that runs [file] (transitively, nearest first, each once) and the file it runs. */
    private fun visit(project: Project, file: VirtualFile, view: MoleculeView, action: (Includer, VirtualFile) -> Unit) {
        val seenTasks = HashSet<SourceLocation>()
        val seenFiles = hashSetOf(file)
        var level = listOf(file)
        var depth = 0
        while (level.isNotEmpty() && depth++ < MAX_DEPTH) {
            ProgressManager.checkCanceled()
            val next = ArrayList<VirtualFile>()
            for (target in level) {
                for (includer in directIncluders(project, target, view)) {
                    if (seenTasks.add(includer.location)) action(includer, target)
                    if (seenFiles.add(includer.file)) next += includer.file
                }
            }
            level = next
        }
    }

    /**
     * The include paths of [file] (each from its direct includer outward, ending at a file nothing includes, at a cycle,
     * or at a role entry file a play also applies directly), at most [MAX_PATHS]; empty when nothing includes [file].
     */
    fun paths(project: Project, file: VirtualFile, view: MoleculeView): List<IncludePath> = pathsOf(project, file, view).paths

    /** [paths], and whether more paths exist than were followed. */
    fun pathsOf(project: Project, file: VirtualFile, view: MoleculeView): IncludePaths {
        val graph = IncludeGraph.getInstance(project)
        val result = ArrayList<IncludePath>()
        var truncated = false
        fun add(path: IncludePath) {
            if (result.size < MAX_PATHS) result += path else truncated = true
        }
        fun walk(current: VirtualFile, prefix: List<Includer>, files: Set<VirtualFile>) {
            if (result.size >= MAX_PATHS) {
                truncated = true
                return
            }
            ProgressManager.checkCanceled()
            val direct = directIncluders(project, current, view)
            if (direct.isNotEmpty() && prefix.size >= MAX_DEPTH) truncated = true
            if (direct.isEmpty() || prefix.size >= MAX_DEPTH) {
                if (prefix.isNotEmpty()) add(IncludePath(prefix))
                return
            }
            // A role entry file a play also applies directly runs without these includes as well.
            if (graph.directlyApplied(current, view)) add(IncludePath(prefix))
            for (includer in direct) {
                if (includer.file in files) {
                    // A cycle: the path ends at the include that closes it.
                    add(IncludePath(prefix + includer))
                    continue
                }
                walk(includer.file, prefix + includer, files + includer.file)
            }
        }
        walk(file, emptyList(), setOf(file))
        return IncludePaths(result, truncated)
    }

    /**
     * Every name some includer of [file] gives it, for ANS-V003 (the "any includer" rule): the loop names of every
     * looping include and the `vars:` keys of every include, except role params the witness already evaluates per play
     * (`DefinitionWitnesses`): the own `vars:` of an `include_role`/`import_role` written in a play, and all `vars:` of
     * one whose entry file a play also applies directly (the hosts of that direct run must still be checked).
     */
    fun names(project: Project, file: VirtualFile, view: MoleculeView): Set<String> {
        val result = LinkedHashSet<String>()
        val workspace = AnsibleWorkspace.getInstance(project)
        val graph = IncludeGraph.getInstance(project)
        visit(project, file, view) { includer, target ->
            result += includer.loopNames
            when {
                !includer.role -> result += includer.vars.keys
                graph.directlyApplied(target, view) -> Unit
                workspace.contextOf(includer.file)?.kind in PLAYBOOK_KINDS -> result += includer.vars.keys - includer.ownVarNames
                else -> result += includer.vars.keys
            }
        }
        return result
    }

    /** The includers of [file] whose loop binds [name] (loop variable, `index_var`, `ansible_loop`), nearest first. */
    fun loopBindings(project: Project, file: VirtualFile, name: String, view: MoleculeView): List<Includer> =
        includers(project, file, view).filter { name in it.loopNames }

    /**
     * Which includers give [file] the name [name]: the providers (each once, nearest first), the nearest includers of
     * the include paths that do not ([IncludeCoverage.missing]), a direct run, and per path the winning includer.
     * [IncludeCoverage.NONE] when nothing includes [file].
     */
    fun coverage(project: Project, file: VirtualFile, name: String, view: MoleculeView): IncludeCoverage {
        val found = pathsOf(project, file, view)
        if (found.paths.isEmpty()) return IncludeCoverage.NONE
        val providers = LinkedHashMap<SourceLocation, Includer>()
        val missing = LinkedHashMap<SourceLocation, Includer>()
        val winners = LinkedHashMap<SourceLocation, Includer>()
        var direct = false
        for (path in found.paths) {
            val winner = path.winnerOf(name)
            if (winner == null) {
                val nearest = path.nearest
                if (nearest == null) direct = true else missing.putIfAbsent(nearest.location, nearest)
                continue
            }
            winners.putIfAbsent(winner.location, winner)
            for (includer in path.includers) if (includer.provides(name)) providers.putIfAbsent(includer.location, includer)
        }
        return IncludeCoverage(providers.values.toList(), missing.values.toList(), emptyList(), direct, found.truncated, winners.values.toList())
    }

    /**
     * The coverage of [name] seen from [file] (a card, a check): a role task file's include paths ([coverage]), a
     * template's rendering tasks as [view] sees them ([templateCoverage]); [IncludeCoverage.NONE] for other files.
     */
    fun coverageAt(project: Project, file: VirtualFile, name: String, view: MoleculeView): IncludeCoverage {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return IncludeCoverage.NONE
        return when {
            context.kind == FileKind.ROLE_TASKS -> coverage(project, file, name, view)
            context.kind == FileKind.ROLE_TEMPLATE || PathFacts.isJ2(file.name) -> {
                val contexts = MoleculeVisibility.contextsInView(project, view, TemplateContextService.getInstance(project).renderContexts(file))
                templateCoverage(project, contexts, name, view)
            }
            else -> IncludeCoverage.NONE
        }
    }

    /**
     * [coverage] for a template over its rendering [contexts] (as the caller's view filtered them): a rendering task
     * whose own `vars:` or loop set [name] covers its renders; otherwise the include paths of its file decide, and a
     * rendering task nothing includes counts in [IncludeCoverage.unprovidedRenders]. [IncludeCoverage.NONE] when no
     * includer gives the name at all.
     */
    fun templateCoverage(project: Project, contexts: List<RenderContext>, name: String, view: MoleculeView): IncludeCoverage {
        val parts = ArrayList<IncludeCoverage>()
        val renders = LinkedHashSet<SourceLocation>()
        for (context in contexts) {
            ProgressManager.checkCanceled()
            if (name in context.taskVars || context.loop?.let { name in LoopItemTyper.namesOf(it) } == true) continue
            val coverage = coverage(project, context.taskSite.file, name, view)
            if (!coverage.provided) {
                renders += context.taskSite
                continue
            }
            parts += coverage
        }
        if (parts.isEmpty()) return IncludeCoverage.NONE
        val union = IncludeCoverage.union(parts)
        return IncludeCoverage(union.providers, union.missing, renders.toList(), union.direct, union.truncated, union.winners)
    }

    /**
     * The task files the include task whose own `vars:` has its key at [definition] runs directly (its `include_tasks`
     * file, the entry file of its `include_role`); empty when [definition] is no such key.
     */
    fun filesRunByVarsKey(project: Project, definition: SourceLocation): List<VirtualFile> {
        val yaml = YamlFiles.yamlFile(project, definition.file) ?: return emptyList()
        val task = TaskChains.taskOf(TaskChains.chainAt(TaskFileModels.of(yaml), definition.offset)) ?: return emptyList()
        if (task.taskInclude == null && task.roleInclude == null) return emptyList()
        if (task.vars.none { it.range.startOffset == definition.offset }) return emptyList()
        return IncludeGraph.getInstance(project).filesRunBy(SourceLocation(definition.file, task.range.startOffset))
    }
}

/**
 * The include graph behind [IncludeBindings]: per role, the literal `include_tasks`/`import_tasks` of its task files
 * (the files they name, resolved like `RoleTaskOrder` does), and per root, the `include_role`/`import_role` tasks
 * grouped by the role directory their literal name finds (`ansible.module.use` index).
 *
 * Held by this project service (DEV.md rule 9: nothing is attached to PSI), per root in `CachedValue`s valid until YAML
 * PSI, the Ansible structure or the project roots change (the `include_role` part also until the `ansible.module.use`
 * index changes); each role's part is computed on first use, so a caret move over an unchanged project reads maps
 * only. Values are PSI-free (task models, files).
 * Call in a read action in smart mode.
 */
@Service(Service.Level.PROJECT)
internal class IncludeGraph(private val project: Project) {
    private val taskCaches = ConcurrentHashMap<VirtualFile, CachedValue<TaskIncludes>>()
    private val roleCaches = ConcurrentHashMap<VirtualFile, CachedValue<RoleIncludes>>()

    private val indexStamp = ModificationTracker { FileBasedIndex.getInstance().getIndexModificationStamp(ModuleUseIndex.NAME, project) }

    /** The include tasks that run [file] directly (unfiltered; [IncludeBindings.directIncluders] applies the Molecule view). */
    fun includersOf(file: VirtualFile): List<Includer> {
        if (!file.isValid || file.isDirectory) return emptyList()
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return emptyList()
        if (context.kind != FileKind.ROLE_TASKS) return emptyList()
        val role = RoleRegistry.getInstance(project).roleOf(file)?.takeIf { it.ref.rootDir == context.root.dir } ?: return emptyList()
        val result = ArrayList<Includer>()
        taskIncludesOf(context.root).of(role)[file]?.let(result::addAll)
        // The `include_role` source reads an index: while indexing, only the role's own includes count.
        if (isEntryOf(role, file) && !DumbService.isDumb(project)) {
            val entry = normalizeEntry(file.name)
            roleIncludesOf(context.root).all()[role.ref.dir]?.filter { it.second == entry }?.forEach { result += it.first }
        }
        return result
    }

    /**
     * Whether a play applies the role whose entry file [file] is without an include task: a `roles:` entry (entry point
     * `main`) or a `meta/main.yml` dependency of an applied role, in a play [view] sees (no Molecule play for a production
     * request). Such a file also runs without whatever its include tasks give it. Reads the cached play graph.
     */
    fun directlyApplied(file: VirtualFile, view: MoleculeView): Boolean {
        if (!file.isValid || file.isDirectory) return false
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return false
        if (context.kind != FileKind.ROLE_TASKS) return false
        val role = RoleRegistry.getInstance(project).roleOf(file)?.takeIf { it.ref.rootDir == context.root.dir } ?: return false
        if (!isEntryOf(role, file)) return false
        val entry = normalizeEntry(file.name)
        val graph = PlayGraph.getInstance(project)
        val plays = MoleculeVisibility.playsInView(project, view, graph.playsApplying(context.root, role.ref.name))
        return plays.any { play ->
            ProgressManager.checkCanceled()
            graph.rolesOfPlay(play).any { applied ->
                (applied.kind == RoleEntryKind.PLAY_ROLE || applied.kind == RoleEntryKind.DEPENDENCY) &&
                    applied.role?.dir == role.ref.dir && normalizeEntry(applied.entryPoint) == entry
            }
        }
    }

    /**
     * The task files the include task whose mapping starts at [location] runs directly: the file of its role-internal
     * `include_tasks`/`import_tasks`, or the entry file of its `include_role`/`import_role` (unfiltered by Molecule; the
     * `include_role` part not while indexing). Reads the cached maps.
     */
    fun filesRunBy(location: SourceLocation): List<VirtualFile> {
        val context = AnsibleWorkspace.getInstance(project).contextOf(location.file) ?: return emptyList()
        val result = LinkedHashSet<VirtualFile>()
        if (context.kind == FileKind.ROLE_TASKS) {
            RoleRegistry.getInstance(project).roleOf(location.file)?.takeIf { it.ref.rootDir == context.root.dir }?.let { role ->
                for ((target, includers) in taskIncludesOf(context.root).of(role)) {
                    if (includers.any { it.location == location }) result += target
                }
            }
        }
        if (!DumbService.isDumb(project)) {
            for ((dir, calls) in roleIncludesOf(context.root).all()) {
                for ((includer, entry) in calls) {
                    if (includer.location == location) entryFile(dir, entry)?.let(result::add)
                }
            }
        }
        return result.toList()
    }

    /** The file of entry point [entry] (`main`, a `tasks_from`) in the role at [roleDir]: `tasks/<entry>` with any of the extensions. */
    private fun entryFile(roleDir: VirtualFile, entry: String): VirtualFile? {
        val tasks = roleDir.findChild(RoleLayout.TASKS) ?: return null
        return (ENTRY_EXTENSIONS.map { "$entry$it" } + entry).firstNotNullOfOrNull { tasks.findFileByRelativePath(it)?.takeIf { file -> !file.isDirectory } }
    }

    /**
     * The role's own `include_tasks`/`import_tasks` as `RoleTaskOrder` reads them: included file → include tasks. The
     * same map while no YAML, structure or project root changes (no index is read).
     */
    fun taskIncludes(role: RoleInfo): Map<VirtualFile, List<Includer>> {
        val root = AnsibleWorkspace.getInstance(project).roots().firstOrNull { it.dir == role.ref.rootDir } ?: return computeTaskIncludes(role)
        return taskIncludesOf(root).of(role)
    }

    private fun taskIncludesOf(root: AnsibleRoot): TaskIncludes {
        if (taskCaches.size > MAX_ROOTS) taskCaches.clear()
        val cached = taskCaches.computeIfAbsent(root.dir) {
            CachedValuesManager.getManager(project).createCachedValue(
                {
                    CachedValueProvider.Result.create(
                        TaskIncludes(root),
                        PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                        AnsibleWorkspace.getInstance(project).structureTracker,
                        ProjectRootManager.getInstance(project),
                    )
                },
                false,
            )
        }
        return cached.value.takeIf { it.root == root } ?: TaskIncludes(root)
    }

    private fun roleIncludesOf(root: AnsibleRoot): RoleIncludes {
        if (roleCaches.size > MAX_ROOTS) roleCaches.clear()
        val cached = roleCaches.computeIfAbsent(root.dir) {
            CachedValuesManager.getManager(project).createCachedValue(
                {
                    CachedValueProvider.Result.create(
                        RoleIncludes(root),
                        indexStamp,
                        PsiModificationTracker.getInstance(project).forLanguage(YAMLLanguage.INSTANCE),
                        AnsibleWorkspace.getInstance(project).structureTracker,
                        ProjectRootManager.getInstance(project),
                    )
                },
                false,
            )
        }
        return cached.value.takeIf { it.root == root } ?: RoleIncludes(root)
    }

    /** The role task includes of one root, computed per role on first use. */
    private inner class TaskIncludes(val root: AnsibleRoot) {
        private val perRole = ConcurrentHashMap<VirtualFile, Map<VirtualFile, List<Includer>>>()

        fun of(role: RoleInfo): Map<VirtualFile, List<Includer>> =
            perRole[role.ref.dir] ?: computeTaskIncludes(role).let { perRole.putIfAbsent(role.ref.dir, it) ?: it }
    }

    /** The `include_role`/`import_role` tasks of one root, computed on first use. */
    private inner class RoleIncludes(val root: AnsibleRoot) {
        @Volatile
        private var calls: Map<VirtualFile, List<Pair<Includer, String>>>? = null

        /** Role directory → its `include_role`/`import_role` tasks with the entry point each names (`main` by default). */
        fun all(): Map<VirtualFile, List<Pair<Includer, String>>> = calls ?: computeRoleIncludes(root).also { calls = it }
    }

    private fun computeTaskIncludes(role: RoleInfo): Map<VirtualFile, List<Includer>> {
        val result = HashMap<VirtualFile, MutableList<Includer>>()
        for (file in role.taskFiles) {
            ProgressManager.checkCanceled()
            val yaml = YamlFiles.yamlFile(project, file) ?: continue
            val model = TaskFileModels.of(yaml)
            for (task in model.tasks()) {
                val include = task.taskInclude ?: continue
                val target = includeTarget(role, file, task) ?: continue
                result.getOrPut(target) { ArrayList(1) } +=
                    Includer(file, task, TaskChains.chainAt(model, task.range.startOffset), include.kind, role = false)
            }
        }
        return result
    }

    private fun computeRoleIncludes(root: AnsibleRoot): Map<VirtualFile, List<Pair<Includer, String>>> {
        val result = HashMap<VirtualFile, MutableList<Pair<Includer, String>>>()
        val registry = RoleRegistry.getInstance(project)
        val seen = HashSet<SourceLocation>()
        for (written in TaskKeywords.INCLUDE_ROLE + TaskKeywords.IMPORT_ROLE) {
            for (hit in AnsibleIndexQueries.moduleUses(project, root, written)) {
                ProgressManager.checkCanceled()
                val yaml = YamlFiles.yamlFile(project, hit.file) ?: continue
                val model = TaskFileModels.of(yaml)
                val chain = TaskChains.chainAt(model, hit.value)
                val task = TaskChains.taskOf(chain) ?: continue
                val call = task.roleInclude ?: continue
                val name = call.name?.text?.trim() ?: continue
                if (!seen.add(SourceLocation(hit.file, task.range.startOffset))) continue
                val contextRole = hit.context.roleDir.takeIf { hit.context.kind == FileKind.ROLE_TASKS || hit.context.kind == FileKind.ROLE_HANDLERS }
                val playbookDirs = if (contextRole == null) listOfNotNull(hit.file.parent) else emptyList()
                val dir = when (val found = RoleLocator.locate(project, name, hit.file, root, playbookDirs, contextRole)) {
                    is RoleLocator.Result.Found -> found.dir
                    else -> registry.role(root, RoleMeta.nameOf(name))?.ref?.dir
                } ?: continue
                if (!dir.isDirectory) continue
                // A templated `tasks_from` names no file statically: the include runs some entry file, not `main`.
                val tasksFrom = call.tasksFrom?.text?.trim()?.takeIf { it.isNotEmpty() }
                if (tasksFrom != null && JinjaBearing.hasTemplateMarkers(tasksFrom)) continue
                val entry = tasksFrom ?: RoleLayout.MAIN
                result.getOrPut(dir) { ArrayList(1) } += Includer(hit.file, task, chain, call.kind, role = true) to normalizeEntry(entry)
            }
        }
        return result
    }

    /** The task file a static `include_tasks`/`import_tasks` of [task] in [file] names, if it exists (as `RoleTaskOrder`). */
    private fun includeTarget(role: RoleInfo, file: VirtualFile, task: TaskNode): VirtualFile? {
        val include = task.taskInclude?.file?.text?.trim() ?: return null
        if (include.contains("{{") || include.contains("{%")) return null
        return file.parent?.findFileByRelativePath(include)?.takeIf { !it.isDirectory }
            ?: role.ref.dir.findFileByRelativePath("${RoleLayout.TASKS}/$include")?.takeIf { !it.isDirectory }
    }

    /** Whether [file] lies in the role's `tasks/` directory (an entry point `include_role` may name). */
    private fun isEntryOf(role: RoleInfo, file: VirtualFile): Boolean = file.parent?.let { it.name == RoleLayout.TASKS && it.parent == role.ref.dir } == true

    /** `rules`, `rules.yml`, `rules.yaml` and `rules.json` name the same entry point (`RoleDefinition._load_role_yaml`). */
    private fun normalizeEntry(written: String): String {
        val name = written.trim().trimStart('/')
        for (extension in ENTRY_EXTENSIONS) if (name.endsWith(extension)) return name.removeSuffix(extension)
        return name
    }

    companion object {
        private const val MAX_ROOTS = 32
        private val ENTRY_EXTENSIONS = listOf(".yml", ".yaml", ".json")

        fun getInstance(project: Project): IncludeGraph = project.service()
    }
}
