package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.MoleculeInventory
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.model.inventory.EnvironmentModel
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.model.task.BlockNode
import de.terletzkiy.ansibility.model.task.TaskFileKind
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskItem
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.semantics.inventory.HostPattern
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.settings.RootKeys

/**
 * Where one play runs: its matched hosts per environment ([HostPattern], ansible-core's rules), or [templated] when
 * its `hosts:` pattern is a template that static evaluation cannot resolve (the play is then counted nowhere).
 * Implicit hosts (`localhost` when the inventory does not define it) are left out: they have no inventory view.
 */
internal class PlayHits(val play: PlayRef, val hostsByEnvironment: Map<String, List<String>>, val templated: Boolean)

/**
 * The model facts the host-context service is computed from (plan amendment R7/R8, A.14): environments and molecule
 * scenarios of a root, host identities, play hits and the per-play "plain" test used to deduplicate contexts.
 *
 * Play hits are a [ModelCache] entry per inventory root that depends on the play graphs of its playbooks and on its
 * environments (their `hosts.yml`); the "plain" test is one entry per play on its graph and playbook. Neither depends on
 * the selection or on vars files. Every method needs a read action.
 */
internal class ContextModel(private val project: Project) {
    private val playHits = ModelCache<VirtualFile, List<PlayHits>>(project, "context.playHits")
    private val plainPlays = ModelCache<PlayRef, Boolean>(project, "context.plainPlays", maxSize = MAX_CACHED)

    val workspace: AnsibleWorkspace get() = AnsibleWorkspace.getInstance(project)

    private val playGraph: PlayGraph get() = PlayGraph.getInstance(project)

    // ------------------------------------------------------------------------------------------------ roots and keys

    /** The root whose inventories [root] uses: the parent of a nested playbook root, else [root] itself. */
    fun inventoryRoot(root: AnsibleRoot): AnsibleRoot {
        if (root.kind != RootKind.NESTED_PLAYBOOK) return root
        val parentDir = root.parentDir ?: return root
        return workspace.roots().firstOrNull { it.dir == parentDir } ?: root
    }

    /** [HostKey.root] of [root]'s hosts: the `RootKeys` key of its inventory root. */
    fun rootKey(root: AnsibleRoot): String = RootKeys.keyOf(project, inventoryRoot(root).dir)

    /** The root a [HostKey.root] names (a PROJECT, ROLE_LIBRARY or detached root, never a nested one). */
    fun rootOfKey(key: String): AnsibleRoot? {
        val roots = workspace.roots().filter { it.kind != RootKind.NESTED_PLAYBOOK }
        return roots.firstOrNull { RootKeys.keyOf(project, it.dir) == key }
    }

    fun hostKey(root: AnsibleRoot, environment: String, host: String): HostKey = HostKey(rootKey(root), environment, host)

    /** The playbook dir of contexts without a play: the root's own directory (`ansible-inventory --playbook-dir <root>`). */
    fun defaultPlaybookDir(root: AnsibleRoot): VirtualFile? = root.dir.takeIf { root.kind != RootKind.ROLE_LIBRARY }

    // ------------------------------------------------------------------------------------------------ inventories

    /** The environments of [root] (a nested root shares its parent's), sorted by name. */
    fun environments(root: AnsibleRoot): List<EnvironmentModel> = InventoryModels.getInstance(project).environments(root)

    fun environment(root: AnsibleRoot, name: String): EnvironmentModel? = environments(root).firstOrNull { it.name == name }

    /** The molecule scenarios of [root]'s roles and of `<root>/molecule`. */
    fun molecules(root: AnsibleRoot): List<MoleculeInventory> = InventoryService.getInstance(project).moleculeInventories(root)

    /** The scenario a molecule [HostKey.environment] names, searched in [root] and the nested roots inside it. */
    fun molecule(root: AnsibleRoot, environment: String): MoleculeInventory? {
        val candidates = listOf(root) + workspace.roots().filter { it.kind == RootKind.NESTED_PLAYBOOK && it.parentDir == root.dir }
        return candidates.firstNotNullOfOrNull { candidate -> molecules(candidate).firstOrNull { moleculeEnvironment(it) == environment } }
    }

    fun moleculeEnvironment(molecule: MoleculeInventory): String = HostKey.moleculeEnvironment(molecule.roleName, molecule.scenarioDir.name)

    /** The host graph of [environment] in [root]: an environment's inventory or a molecule scenario's. */
    fun graphOf(root: AnsibleRoot, environment: String): InventoryGraph? =
        if (environment.startsWith(HostKey.MOLECULE_PREFIX)) moleculeGraph(inventoryRoot(root), environment)
        else environment(root, environment)?.graph

    /** The parsed graph of the molecule scenario [environment] names, searched as [molecule] does. */
    private fun moleculeGraph(root: AnsibleRoot, environment: String): InventoryGraph? {
        val molecule = molecule(root, environment) ?: return null
        val owner = workspace.rootFor(molecule.scenarioDir) ?: root
        return InventoryModels.getInstance(project).moleculeModel(owner, molecule).graph
    }

    /** The evaluation contexts of every host of [molecule]: the scenario's converge play when it has one. */
    fun moleculeTargets(root: AnsibleRoot, molecule: MoleculeInventory): List<EvalTarget> {
        val environment = moleculeEnvironment(molecule)
        val converge = molecule.scenarioDir.children.orEmpty()
            .firstOrNull { !it.isDirectory && it.name in CONVERGE_NAMES }
            ?.let { playGraph.playsOf(it).firstOrNull()?.ref }
        return molecule.inventory.hosts.keys.map { host -> EvalTarget(hostKey(root, environment, host), converge, molecule.scenarioDir) }
    }

    // ------------------------------------------------------------------------------------------------ plays

    /**
     * Every play of [root]'s family that runs against the environments (playbooks of the inventory root and of the
     * nested roots inside it; molecule playbooks run against their scenario and are left out), with its hits.
     */
    fun playHits(root: AnsibleRoot): List<PlayHits> {
        val owner = inventoryRoot(root)
        return playHits.get(owner.dir) { computePlayHits(owner) }
    }

    private fun computePlayHits(owner: AnsibleRoot): List<PlayHits> {
        val family = listOf(owner) + workspace.roots().filter { it.kind == RootKind.NESTED_PLAYBOOK && it.parentDir == owner.dir }
        val plays = LinkedHashSet<PlayRef>()
        for (member in family) {
            for (playbook in playGraph.playbooks(member)) {
                ProgressManager.checkCanceled()
                if (workspace.contextOf(playbook)?.kind != FileKind.PLAYBOOK) continue
                playGraph.playsOf(playbook).mapTo(plays) { it.ref }
            }
        }
        val environments = environments(owner)
        return plays.map { hitsOf(it, environments) }
    }

    /** The hits of [play] in [environments]. */
    fun hitsOf(play: PlayRef, environments: List<EnvironmentModel>): PlayHits {
        val pattern = play.hostsPattern
        if (pattern == null || JinjaBearing.hasTemplateMarkers(pattern)) return PlayHits(play, emptyMap(), templated = pattern != null)
        val byEnvironment = LinkedHashMap<String, List<String>>()
        for (environment in environments) {
            ProgressManager.checkCanceled()
            val match = HostPattern.resolve(environment.graph, pattern)
            val hosts = match.hosts.filter { it !in match.implicitHosts }
            if (hosts.isNotEmpty()) byEnvironment[environment.name] = hosts
        }
        return PlayHits(play, byEnvironment, templated = false)
    }

    /** The hits of [play] among [root]'s plays, computed on the spot for a play outside them (a molecule playbook). */
    fun hitsOf(root: AnsibleRoot, play: PlayRef): PlayHits =
        playHits(root).firstOrNull { it.play == play } ?: hitsOf(play, environments(root))

    /**
     * Whether [play] adds nothing of its own to the variables of the roles it applies: no `vars:`, no `vars_files`,
     * no role params or `vars:` on its `roles:` entries and no `vars:` on its `include_role`/`import_role` tasks.
     * Plain plays with the same roles and playbook dir give the same values, so their contexts are deduplicated.
     */
    fun isPlain(play: PlayRef): Boolean = plainPlays.get(play) { computePlain(play) }

    private fun computePlain(play: PlayRef): Boolean {
        val info = playGraph.play(play) ?: return true
        if (info.varsKeys.isNotEmpty() || info.varsFiles.isNotEmpty()) return false
        ModelInputs.file(project, play.file)
        val yaml = YamlFiles.yamlFile(project, play.file) ?: return true
        val node = TaskFileModels.of(yaml, TaskFileKind.PLAYBOOK).plays.getOrNull(play.playIndex) ?: return true
        if (node.roles.any { it.params.isNotEmpty() || it.keywords.containsKey("vars") }) return false
        fun hasIncludeVars(items: List<TaskItem>): Boolean = items.any { item ->
            when (item) {
                is TaskNode -> item.roleInclude?.vars != null
                is BlockNode -> hasIncludeVars(item.block) || hasIncludeVars(item.rescue) || hasIncludeVars(item.always)
            }
        }
        return node.sections().none { hasIncludeVars(it) }
    }

    /**
     * [targets] deduplicated by (host, playbook dir, role list) for plain plays ([isPlain]); a play with inputs of its
     * own is never merged with another. The first target of each key is kept, so the order is preserved.
     */
    fun deduplicate(targets: List<EvalTarget>): List<EvalTarget> {
        val seen = HashSet<Any>()
        return targets.filter { target ->
            val play = target.play
            val key: Any = when {
                play == null -> Triple(target.host, target.playbookDir, null)
                isPlain(play) -> Triple(target.host, target.playbookDir, playGraph.rolesOfPlay(play).map { it.role?.dir ?: it.written to it.kind })
                else -> Triple(target.host, target.playbookDir, play)
            }
            seen.add(key)
        }
    }

    /** [targets] in environment, inventory and play order (molecule scenarios after the environments). */
    fun sorted(root: AnsibleRoot, targets: Collection<EvalTarget>, playOrder: List<PlayRef>): List<EvalTarget> {
        val environments = environments(root)
        val envRank = environments.withIndex().associate { (i, e) -> e.name to i }
        val hostRank = environments.associate { e -> e.name to e.graph.hosts.keys.withIndex().associate { (i, h) -> h to i } }
        val playRank = playOrder.withIndex().associate { (i, p) -> p to i }
        return targets.sortedWith(
            compareBy<EvalTarget>(
                { envRank[it.host.environment] ?: Int.MAX_VALUE },
                { it.host.environment },
                { hostRank[it.host.environment]?.get(it.host.host) ?: Int.MAX_VALUE },
                { it.play?.let(playRank::get) ?: -1 },
            ),
        )
    }

    companion object {
        private const val MAX_CACHED = 8192

        /** The converge playbook molecule runs (`provisioner.playbooks.converge` defaults to it). */
        private val CONVERGE_NAMES = setOf("converge.yml", "converge.yaml")
    }
}
