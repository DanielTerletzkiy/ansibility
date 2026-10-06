package de.terletzkiy.ansibility.model.effective

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EffectiveVars
import de.terletzkiy.ansibility.model.inventory.EnvironmentModel
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelCacheStats
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.model.inventory.VarsDirectories
import de.terletzkiy.ansibility.model.inventory.VarsDocuments
import de.terletzkiy.ansibility.model.inventory.readLocked
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.precedence.InventoryVarSources
import de.terletzkiy.ansibility.semantics.precedence.InventoryView
import de.terletzkiy.ansibility.semantics.precedence.PrecedenceEngine
import de.terletzkiy.ansibility.semantics.precedence.VarSource
import org.jetbrains.annotations.TestOnly

/**
 * The inventory view of one host with everything needed to continue evaluating on top of it: the engine's
 * [InventoryView] (levels 3–10, with its combination tree), the [engine] configured from the root's `ansible.cfg`
 * (the execution layers must be combined under the same `hash_behaviour`), the [origins] of its sources and the API
 * form [vars] (built on first use).
 */
class HostView(
    val engine: PrecedenceEngine,
    val view: InventoryView,
    val origins: Map<String, SourceOrigin>,
    private val environment: String,
) {
    /** The API view: every variable with its winner, shadowed and merged definitions, sorted by name. */
    val vars: EffectiveVars by lazy {
        EffectiveVars(view.host, environment, view.vars.values.sortedBy { it.name }.mapNotNull { VarSourceRefs.entry(it, origins) })
    }
}

/**
 * The inventory views of every host of one environment for one playbook dir (plan amendment R7/R8, A.9 change 1): one
 * cache entry per (environment, playbook dir), computed together from one set of sources.
 */
class EnvironmentViews internal constructor(
    val environment: String,
    private val views: Map<String, HostView>,
    /** The sources every view of the entry was evaluated from (shared by the views). */
    private val sources: List<VarSource>,
) {
    /** The hosts with a view, in inventory order. */
    val hosts: Set<String> get() = views.keys

    /** The view of [host], or null when the environment does not define it. */
    operator fun get(host: String): HostView? = views[host]

    /** Every view, in inventory order. */
    val all: Collection<HostView> get() = views.values

    /**
     * An estimate of the resident size in bytes: the shared sources (their entry maps, key ranges and values) once, and
     * per view its variables with their winner and shadowed definitions and its combination tree. Object headers and
     * references are counted at 16 and 4 bytes, map entries at 48; values are counted as flat scalars, so nested values
     * are underestimated.
     */
    fun estimatedBytes(): Long {
        val sourceBytes = sources.sumOf { source -> SOURCE_BYTES + source.entries.size * (2 * MAP_ENTRY_BYTES + VALUE_BYTES + RANGE_BYTES) }
        val viewBytes = views.values.sumOf { host ->
            val view = host.view
            VIEW_BYTES + view.appliedSources.size * (REFERENCE_BYTES + LEAF_BYTES) +
                view.vars.values.sumOf { effective ->
                    MAP_ENTRY_BYTES + EFFECTIVE_BYTES + DEFINITION_BYTES + effective.shadowed.size * (REFERENCE_BYTES + DEFINITION_BYTES)
                }
        }
        return sourceBytes + viewBytes
    }

    private companion object {
        const val REFERENCE_BYTES = 4L
        const val MAP_ENTRY_BYTES = 48L
        const val SOURCE_BYTES = 64L
        const val VALUE_BYTES = 48L
        const val RANGE_BYTES = 24L
        const val VIEW_BYTES = 96L
        const val LEAF_BYTES = 24L
        const val EFFECTIVE_BYTES = 40L
        const val DEFINITION_BYTES = 24L
    }
}

/**
 * Inventory views per (root, environment, playbook dir) (plan A.5 InventoryView; plan amendment R7/R8, A.9 change 1),
 * shared by [EffectiveVarsServiceImpl] (the API DTOs) and the host-context service (single-name evaluation on top of
 * them).
 *
 * Sources: the inline vars of `hosts.yml` (`all.vars`, group `vars`, host entries), the `group_vars`/`host_vars` next
 * to the inventory (`environments/<env>`) and, when a playbook dir is given, those of that directory, under the
 * root's `precedence`, `hash_behaviour` and `yaml_valid_extensions` ([VarsConfig]). A playbook dir outside the root
 * (and, for a nested root, outside its parent) is ignored, so no definition from another root is ever returned.
 *
 * **Caching.** One [ModelCache] entry per (environment directory, `ansible.cfg`, playbook dir) holds the views of every
 * host of that environment. It depends on the environment's model (its `hosts.yml`), on every vars file it loaded (by
 * committed content stamp), on `ansible.cfg` and on the layout stamp, never on the global YAML PSI tracker: typing in
 * `roles/x/tasks/main.yml` invalidates no view, and editing `environments/prod/group_vars/all/vars.yml` invalidates the
 * prod views of that root only. A nested root shares its parent's entries. The target repo has 88 views in at most
 * about 24 entries per root; they stay resident. [stats] counts the entries computed.
 *
 * Methods may be called from any thread; they take a read lock when the caller holds none.
 */
@Service(Service.Level.PROJECT)
class HostViews(private val project: Project) {
    private data class Key(val environment: String, val environmentDir: VirtualFile, val cfgFile: VirtualFile?, val playbookDir: VirtualFile?)

    private val cache = ModelCache<Key, EnvironmentViews>(project, CACHE_NAME, maxSize = MAX_ENTRIES)

    private val models: InventoryModels get() = InventoryModels.getInstance(project)

    /** The counters of the view cache (one computation per (environment, playbook dir) entry). */
    val stats: ModelCacheStats get() = cache.stats

    /** The number of (environment, playbook dir) entries held. */
    val entries: Int get() = cache.size

    /** An estimate of the resident size of every entry held, current or not ([EnvironmentViews.estimatedBytes]). */
    fun estimatedBytes(): Long = cache.keys().sumOf { cache.last(it)?.estimatedBytes() ?: 0L }

    /** Drops every entry (benchmarks measure computing them again). */
    @TestOnly
    fun clear() = cache.clear()

    /** The view of [host] in [environment] of [root], or null when the root has no such environment or host. */
    fun view(root: AnsibleRoot, environment: String, host: String, playbookDir: VirtualFile?): HostView? = readLocked {
        val model = models.environment(root, environment)
        if (model == null || model.graph.host(host) == null) null else views(root, model, playbookDir)[host]
    }

    /** The views of every host of [environment] in [root] for [playbookDir], or null when the root has no such environment. */
    fun views(root: AnsibleRoot, environment: String, playbookDir: VirtualFile?): EnvironmentViews? = readLocked {
        models.environment(root, environment)?.let { views(root, it, playbookDir) }
    }

    private fun views(root: AnsibleRoot, model: EnvironmentModel, playbookDir: VirtualFile?): EnvironmentViews {
        val key = Key(model.name, model.dir, VarsConfig.cfgFile(root), playbookDir?.takeIf { isInFamily(root, it) && it.isValid && it.isDirectory })
        return cache.get(key) { compute(root, key) }
    }

    private fun compute(root: AnsibleRoot, key: Key): EnvironmentViews {
        // Read through the cache again so the entry depends on the environment's model entry (its hosts.yml).
        val model = models.environment(root, key.environment) ?: return EnvironmentViews(key.environment, emptyMap(), emptyList())
        val config = VarsConfig.load(key.cfgFile)
        val origins = HashMap<String, SourceOrigin>()
        fun origin(file: VirtualFile): String = file.url.also { origins[it] = SourceOrigin(file) }

        val sources = ArrayList<VarSource>()
        sources += InventoryVarSources.inline(model.graph) { index -> origin(model.sourceFiles.getOrNull(index) ?: model.hostsFile) }
        for (dir in model.varsDirs) sources += adjacent(model.graph, dir, playbookAdjacent = false, config, ::origin)
        if (key.playbookDir != null) sources += adjacent(model.graph, key.playbookDir, playbookAdjacent = true, config, ::origin)
        val engine = PrecedenceEngine(config.hashBehaviour, config.precedence)
        val views = LinkedHashMap<String, HostView>()
        for (host in model.graph.hosts.keys) {
            ProgressManager.checkCanceled()
            val view = engine.inventoryView(model.graph, host, sources) ?: continue
            views[host] = HostView(engine, view, origins, model.name)
        }
        return EnvironmentViews(model.name, views, sources)
    }

    /** The `group_vars`/`host_vars` sources below [base] for every group and host of [graph]. */
    private fun adjacent(
        graph: InventoryGraph,
        base: VirtualFile,
        playbookAdjacent: Boolean,
        config: VarsConfig,
        origin: (VirtualFile) -> String,
    ): List<VarSource> = InventoryVarSources.adjacent(
        graph = graph,
        base = VarsDirectories.lister(base),
        playbookAdjacent = playbookAdjacent,
        load = { relativePath -> VarsDocuments.load(project, base.findFileByRelativePath(relativePath)) },
        originId = { relativePath -> base.findFileByRelativePath(relativePath)?.let(origin) ?: "${base.url}/$relativePath" },
        extensions = config.extensions,
    )

    /** Whether [dir] belongs to [root] or, for a nested root, to its parent (whose playbooks it may import). */
    private fun isInFamily(root: AnsibleRoot, dir: VirtualFile): Boolean =
        VfsUtilCore.isAncestor(root.dir, dir, false) || root.parentDir?.let { VfsUtilCore.isAncestor(it, dir, false) } == true

    companion object {
        /** The [ModelCache] name of the view entries ([de.terletzkiy.ansibility.model.inventory.ModelCaches.snapshot]). */
        const val CACHE_NAME: String = "views.inventory"

        /** Far above the about 24 (environment, playbook dir) entries per root of the target repo. */
        private const val MAX_ENTRIES = 1024

        fun getInstance(project: Project): HostViews = project.service()
    }
}
