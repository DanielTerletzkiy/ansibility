package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.PlayRoleEntry
import de.terletzkiy.ansibility.api.RoleEntryKind
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RuntimeMarker
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.AnsibleCfg
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelCacheStats
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.model.inventory.readLocked
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.PlayNode
import de.terletzkiy.ansibility.model.task.RoleEntryNode
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.precedence.ExecutionIndex
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEngine
import de.terletzkiy.ansibility.semantics.precedence.RoleApplication
import de.terletzkiy.ansibility.semantics.precedence.RoleLayering
import de.terletzkiy.ansibility.semantics.precedence.VarLayer
import de.terletzkiy.ansibility.semantics.precedence.VarOwner
import de.terletzkiy.ansibility.semantics.precedence.VarSource
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import java.io.IOException

/**
 * The execution-layer inputs of one (play, running role) context (plan amendment R7/R8, A.14 "Per-host evaluation"):
 * the [sources] `PrecedenceEngine.executionView`/`effectiveOf` layers on top of an inventory view, and what static
 * evaluation cannot know.
 *
 * [sources] hold, each owned by `all` (they apply to every host of the play):
 * - L2 role defaults and L14 role vars of the play's roles in [RoleLayering] order (public roles in play order, the
 *   running role and its dependency chain re-applied last);
 * - L12 the play's `vars:`; L13 its `vars_files`, in order;
 * - on top of L14, the running role's `vars:` keyword of its `roles:` entry;
 * - L18 the running role's params (the non-keyword keys of its `roles:` entry) and the `vars:` of its
 *   `include_role`/`import_role` task (include params).
 *
 * L15 block/task vars belong to a caret, never to a context, and L16/L17 values are unknown statically: `set_fact`,
 * `register` and `include_vars` become [RuntimeMarker]s ([ExecutionSources.runtimeMarkers]), never sources.
 */
class ExecutionInputs internal constructor(
    /** The root the context was built in. */
    val rootDir: VirtualFile,
    val play: PlayRef?,
    /** The running role's name, or null for play-level tasks. */
    val runningRole: String?,
    val sources: List<VarSource>,
    /** The origin of every source id in [sources]. */
    val origins: Map<String, SourceOrigin>,
    /** `vars_files` entries with a templated path: they may define any name, which is unknown statically. */
    val unknownSources: List<SourceLocation>,
    /** The role directories whose tasks run in the context, in application order (runtime markers come from them). */
    val roleDirs: List<VirtualFile>,
) {
    /** Whether every source applies to every host of the play (the sources [ExecutionSources] builds always do). */
    private val hostIndependent: Boolean = sources.all { it.owner == VarOwner.All }

    @Volatile
    private var sharedIndex: ExecutionIndex? = null

    /**
     * The per-name winners of [sources] for a task on [host] (a member of [groups], without `all`), built once and
     * shared by every host while all sources are owned by `all` ([PrecedenceEngine.executionIndex]).
     */
    fun index(engine: PrecedenceEngine, host: String, groups: Collection<String>): ExecutionIndex {
        if (!hostIndependent) return engine.executionIndex(host, groups, sources)
        return sharedIndex ?: engine.executionIndex(host, groups, sources).also { sharedIndex = it }
    }
}

/**
 * Builds and caches [ExecutionInputs] (plan amendment R7/R8, `model/effective/ExecutionSources.kt`).
 *
 * Role files load the way ansible-core's `Role._load_role_yaml` loads them: `defaults/main` and `vars/main` (the first
 * of `main.yml`, `main.yaml`, `main.json`, `main`; a `main/` directory loads all its files), or the literal
 * `defaults_from`/`vars_from` file of the running role's `include_role`/`import_role`. `private_role_vars` in the
 * root's `ansible.cfg` makes every role private. Dynamically included roles (`include_role`) are private unless they
 * are the running role (spike S-H2 confirms this for 2.18.8).
 *
 * Without a play (a molecule scenario without a converge play, an inventory-only context) the running role
 * contributes its own `meta/main.yml` dependency chain and itself. A play that does not apply the running role (a
 * template another role renders) is evaluated as for a play-level task.
 *
 * **Caching** (plan amendment R7/R8, A.9): inputs are [ModelCache] entries per (root, play, running role). Each depends
 * on the play's graph (its playbook and the `meta/main.yml` files it expanded), the role `defaults/`/`vars/` and
 * `vars_files` it loaded, the role metadata of a standalone chain, the root's `ansible.cfg` and the layout stamp, never on
 * the global YAML tracker: typing in a role's tasks keeps every input, typing in a role's defaults rebuilds the inputs
 * that load them. The `include_vars` targets of [runtimeMarkers] are cached separately on the task files they scan.
 * Call from any thread; a read lock is taken when the caller holds none.
 */
@Service(Service.Level.PROJECT)
class ExecutionSources(private val project: Project) {
    private data class Key(val rootDir: VirtualFile, val play: PlayRef?, val runningRole: String?)

    private val cache = ModelCache<Key, ExecutionInputs>(project, CACHE_NAME, maxSize = MAX_CACHED)
    private val includeVarsCache = ModelCache<Key, Map<String, List<SourceLocation>>>(project, "execution.includeVars", maxSize = MAX_CACHED)

    /** The counters of the inputs cache. */
    val stats: ModelCacheStats get() = cache.stats

    /** The inputs of [play] (null: no play) for a task of [runningRole] (null: a play-level task) in [root]. */
    fun inputs(root: AnsibleRoot, play: PlayRef?, runningRole: String?): ExecutionInputs = readLocked {
        cache.get(Key(root.dir, play, runningRole)) { Builder(root, play, runningRole).build() }
    }

    /**
     * The tasks of [inputs]' context that may replace [name] at runtime: `set_fact` keys and `register` names in the
     * play's own tasks and in the task files of its roles, and `include_vars` tasks whose literal file defines [name]
     * (or whose `name:` option is [name]). Needs smart mode (the variable index).
     *
     * A role's Molecule files (a converge play below its directory) are none of its task files: they count only for a
     * context whose play is itself a Molecule playbook (plan amendment R20, D157).
     */
    fun runtimeMarkers(root: AnsibleRoot, inputs: ExecutionInputs, name: String): List<RuntimeMarker> = readLocked {
        val playRange = inputs.play?.let { playNode(it)?.range }
        val moleculePlay = inputs.play?.file?.let { MoleculeVisibility.isMoleculeFile(project, it) } == true
        val markers = ArrayList<RuntimeMarker>()
        for (definition in VarService.getInstance(project).symbol(root, name).definitions) {
            ProgressManager.checkCanceled()
            val kind = when (definition.kind) {
                VarDefKind.SET_FACT -> RuntimeMarkerKind.SET_FACT
                VarDefKind.REGISTER -> RuntimeMarkerKind.REGISTER
                else -> continue
            }
            val file = definition.location.file
            val inRole = inputs.roleDirs.any { VfsUtilCore.isAncestor(it, file, true) } &&
                (moleculePlay || !MoleculeVisibility.isMoleculeFile(project, file))
            val inPlay = inputs.play?.file == file && playRange?.containsOffset(definition.location.offset) == true
            if (inRole || inPlay) markers += RuntimeMarker(name, kind, definition.location)
        }
        val includes = includeVarsCache.get(Key(root.dir, inputs.play, inputs.runningRole)) {
            // Read the inputs through their cache, so the targets depend on the current role directories as well.
            includeVarsOf(inputs(root, inputs.play, inputs.runningRole))
        }
        includes[name].orEmpty().mapTo(markers) { RuntimeMarker(name, RuntimeMarkerKind.INCLUDE_VARS, it) }
        markers.sortedWith(compareBy({ it.location.file.path }, { it.location.offset }))
    }

    /** Builds the inputs of one context. */
    private inner class Builder(val root: AnsibleRoot, val play: PlayRef?, val runningRole: String?) {
        private val sources = ArrayList<VarSource>()
        private val origins = HashMap<String, SourceOrigin>()
        private val unknown = ArrayList<SourceLocation>()

        fun build(): ExecutionInputs {
            val entries = play?.let { PlayGraph.getInstance(project).rolesOfPlay(it) }.orEmpty()
            val applications = entries.map { entry ->
                RoleApplication(
                    id = entry.role?.dir?.url ?: "unresolved:${entry.written}",
                    name = entry.name,
                    kind = when (entry.kind) {
                        RoleEntryKind.PLAY_ROLE -> RoleApplication.Kind.PLAY_ROLE
                        RoleEntryKind.IMPORT_ROLE -> RoleApplication.Kind.IMPORT_ROLE
                        RoleEntryKind.INCLUDE_ROLE -> RoleApplication.Kind.INCLUDE_ROLE
                        RoleEntryKind.DEPENDENCY -> RoleApplication.Kind.DEPENDENCY
                    },
                    requiredBy = entry.requiredBy,
                )
            }
            val layering = RoleLayering.order(applications, runningRole, privateRoleVars(root))
            val runningEntry = layering.running?.let(entries::get)
            val steps = layering.order.mapNotNull { index -> entries[index].role?.dir?.let { Step(it, entries[index].name, entries[index]) } }
                .toMutableList()
            if (runningRole != null && play == null) steps += standaloneChain(runningRole)

            val includeCall = if (play != null && runningEntry != null) (entryNode(play, runningEntry) as? TaskNode)?.roleInclude else null
            steps.forEachIndexed { order, step ->
                val running = step.entry != null && step.entry == runningEntry
                val defaultsFrom = includeCall?.defaultsFrom?.text?.takeIf { running }
                val varsFrom = includeCall?.varsFrom?.text?.takeIf { running }
                for (file in RoleDefaults.loadedFiles(step.dir, RoleLayout.DEFAULTS, defaultsFrom)) add(VarLayer.ROLE_DEFAULTS, "defaults", file, order, step.name)
                for (file in RoleDefaults.loadedFiles(step.dir, RoleLayout.VARS, varsFrom)) add(VarLayer.ROLE_VARS, "vars", file, order, step.name)
            }
            if (play != null) playSources(play)
            if (runningEntry != null && play != null) runningEntrySources(play, runningEntry, steps.size)
            return ExecutionInputs(root.dir, play, runningRole, sources.toList(), origins.toMap(), unknown.toList(), steps.map { it.dir })
        }

        /** L12 and L13 of [play]. */
        private fun playSources(play: PlayRef) {
            val info = PlayGraph.getInstance(project).play(play) ?: return
            info.vars?.let { source(VarLayer.PLAY_VARS, "${play.file.url}#${play.playIndex}/vars", 0, it, SourceOrigin(play.file, play = play)) }
            info.varsFiles.forEachIndexed { order, ref ->
                ProgressManager.checkCanceled()
                val file = ref.file
                when {
                    file != null -> source(VarLayer.VARS_FILES, "vars_files:${file.url}", order, VarsDocuments.load(project, file), SourceOrigin(file, play = play))
                    JinjaBearing.hasTemplateMarkers(ref.path) -> ref.location?.let(unknown::add)
                }
            }
        }

        /** The running role's `vars:` keyword (on top of L14) and its params (L18), as written in [play]. */
        private fun runningEntrySources(play: PlayRef, entry: PlayRoleEntry, order: Int) {
            val origin = SourceOrigin(play.file, role = entry.name, play = play)
            val offset = entry.location?.offset ?: return
            when (val node = entryNode(play, entry)) {
                is RoleEntryNode -> {
                    (node.keywords["vars"]?.value as? YMap)?.let { source(VarLayer.ROLE_VARS, "entry-vars:${play.file.url}#$offset", order, it, origin) }
                    if (node.params.isNotEmpty()) source(VarLayer.ROLE_PARAMS, "params:${play.file.url}#$offset", 0, YMap(node.params), origin)
                }
                is TaskNode -> (node.roleInclude?.vars?.value as? YMap)?.let {
                    source(VarLayer.ROLE_PARAMS, "include-params:${play.file.url}#$offset", 1, it, origin)
                }
                else -> Unit
            }
        }

        private fun add(layer: VarLayer, prefix: String, file: VirtualFile, order: Int, role: String) {
            source(layer, "$prefix:${file.url}", order, VarsDocuments.load(project, file), SourceOrigin(file, role = role, play = play))
        }

        private fun source(layer: VarLayer, id: String, order: Int, document: YValue?, origin: SourceOrigin) {
            val source = VarSource.fromDocument(layer, VarOwner.All, id, order, document) ?: return
            sources += source
            origins[id] = origin
        }

        /** [role] of [root] with its `meta/main.yml` dependencies (theirs first), for a role the play does not apply. */
        private fun standaloneChain(role: String): List<Step> {
            val registry = RoleRegistry.getInstance(project)
            val out = ArrayList<Step>()
            fun visit(name: String, chain: Set<String>) {
                if (name in chain || chain.size > MAX_DEPENDENCY_DEPTH) return
                val info = registry.role(root, name) ?: return
                RoleLayout.metaFile(info.ref.dir)?.let { ModelInputs.file(project, it) }
                for (dependency in info.metaDependencies) visit(dependency, chain + name)
                if (out.none { it.dir == info.ref.dir }) out += Step(info.ref.dir, name, null)
            }
            visit(role, emptySet())
            return out
        }
    }

    /** One role whose defaults and vars apply, in application order; [entry] is null for a standalone running role. */
    private class Step(val dir: VirtualFile, val name: String, val entry: PlayRoleEntry?)

    /** The task-model node of [entry] in [play]: its `roles:` entry, or its `include_role`/`import_role` task. */
    private fun entryNode(play: PlayRef, entry: PlayRoleEntry): Any? {
        val location = entry.location?.takeIf { it.file == play.file } ?: return null
        val node = playNode(play) ?: return null
        return when (entry.kind) {
            RoleEntryKind.PLAY_ROLE -> node.roles.firstOrNull { it.name?.range?.startOffset == location.offset }
            RoleEntryKind.INCLUDE_ROLE, RoleEntryKind.IMPORT_ROLE ->
                tasksOf(node).firstOrNull { it.roleInclude?.name?.range?.startOffset == location.offset }
            RoleEntryKind.DEPENDENCY -> null
        }
    }

    private fun playNode(play: PlayRef): PlayNode? {
        ModelInputs.file(project, play.file)
        val yaml = YamlFiles.yamlFile(project, play.file) ?: return null
        return TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK).plays.getOrNull(play.playIndex)
    }

    /** The `include_vars` targets of the context's tasks, by the variable names they define. */
    private fun includeVarsOf(inputs: ExecutionInputs): Map<String, List<SourceLocation>> {
        val out = LinkedHashMap<String, MutableList<SourceLocation>>()
        fun scan(taskFile: VirtualFile, tasks: List<TaskNode>, searchDirs: List<VirtualFile>) {
            for (task in tasks) {
                ProgressManager.checkCanceled()
                val call = task.module ?: continue
                if (call.canonical !in INCLUDE_VARS && call.name !in INCLUDE_VARS) continue
                val location = SourceLocation(taskFile, task.range.startOffset)
                val namespace = (call.args.option("name") as? YScalar)?.text
                if (namespace != null) {
                    out.getOrPut(namespace) { ArrayList() } += location
                    continue
                }
                val path = (call.args.option("file") as? YScalar)?.text ?: call.args.rawParams?.trim() ?: continue
                if (path.isEmpty() || JinjaBearing.hasTemplateMarkers(path)) continue
                val target = (searchDirs + taskFile.parent).firstNotNullOfOrNull { it.findFileByRelativePath(path)?.takeIf { f -> !f.isDirectory } }
                    ?: continue
                for (key in (VarsDocuments.load(project, target) as? YMap)?.keys.orEmpty()) out.getOrPut(key) { ArrayList() } += location
            }
        }
        for (dir in inputs.roleDirs) {
            val searchDirs = listOfNotNull(dir.findChild(RoleLayout.VARS), dir)
            for (file in RoleLayout.taskFiles(dir)) {
                ModelInputs.file(project, file)
                val yaml = YamlFiles.yamlFile(project, file) ?: continue
                scan(file, TaskFileModels.of(yaml, TaskFileKind.TASKS).tasks(), searchDirs)
            }
        }
        inputs.play?.let { play ->
            val node = playNode(play)
            if (node != null) scan(play.file, tasksOf(node), listOfNotNull(play.playbookDir.findChild(RoleLayout.VARS), play.playbookDir))
        }
        return out
    }

    /** Every task of [play]'s sections, blocks included, in source order. */
    private fun tasksOf(play: PlayNode): List<TaskNode> {
        val out = ArrayList<TaskNode>()
        fun visit(items: List<TaskItem>) {
            for (item in items) when (item) {
                is TaskNode -> out += item
                is BlockNode -> {
                    visit(item.block)
                    visit(item.rescue)
                    visit(item.always)
                }
            }
        }
        play.sections().forEach(::visit)
        return out
    }

    /** `private_role_vars` of the root's `ansible.cfg` (ansible-core's boolean spelling). */
    private fun privateRoleVars(root: AnsibleRoot): Boolean {
        val file = VarsConfig.cfgFile(root) ?: return false
        ModelInputs.savedFile(file)
        val value = try {
            AnsibleCfg.parse(VfsUtilCore.loadText(file)).value("defaults", "private_role_vars")
        } catch (e: IOException) {
            LOG.debug("Cannot read ${file.path}", e)
            null
        }
        return value?.trim()?.lowercase() in TRUE_VALUES
    }

    companion object {
        /** The [ModelCache] name of the inputs entries ([de.terletzkiy.ansibility.model.inventory.ModelCaches.snapshot]). */
        const val CACHE_NAME: String = "execution.inputs"

        private val LOG = logger<ExecutionSources>()
        private const val MAX_CACHED = 8192
        private const val MAX_DEPENDENCY_DEPTH = 16

        private val INCLUDE_VARS = setOf("include_vars", "ansible.builtin.include_vars", "ansible.legacy.include_vars")
        private val TRUE_VALUES = setOf("y", "yes", "on", "1", "true", "t")

        fun getInstance(project: Project): ExecutionSources = project.service()
    }
}
