package de.terletzkiy.ansibility.toolwindow.host

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.RuntimeMarker
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.host.HostContextReads
import de.terletzkiy.ansibility.context.host.PlayKeys
import de.terletzkiy.ansibility.context.host.PlayMatch
import de.terletzkiy.ansibility.context.host.TypedEffectiveVar
import de.terletzkiy.ansibility.context.host.TypedSourceRef
import de.terletzkiy.ansibility.context.host.ValueKind
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelCacheKind
import de.terletzkiy.ansibility.model.inventory.ModelCacheStats
import de.terletzkiy.ansibility.model.inventory.ModelInputs
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import de.terletzkiy.ansibility.toolwindow.model.SourceLabels
import org.jetbrains.annotations.Nls
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The evaluation a host's Effective vars use: the [choice] made in the play selector resolved against the model and the
 * Ansible context.
 *
 * - [PlayChoice.Inventory]: no play ([evaluated] is empty), the inventory view with the root's playbook dir.
 * - A chosen play that still runs on the host, or the Ansible context's play under [PlayChoice.Auto] ([fromContext]):
 *   that one play, with its own playbook dir.
 * - [PlayChoice.Auto] otherwise, and a chosen play that no longer runs on the host ([problem]): every play that runs on
 *   the host ([allPlays]), each in its own playbook dir; a host no play runs on gets the inventory view.
 */
class EffectiveContext(
    val root: AnsibleRoot,
    val host: HostKey,
    val choice: PlayChoice,
    /** The plays evaluated, in play-graph order: none for the inventory view. */
    val evaluated: List<PlayRef>,
    /** [PlayChoice.Auto] took the play of the Ansible context. */
    val fromContext: Boolean,
    /** [evaluated] is every play that runs on the host (Auto without a context play), however many there are. */
    val allPlays: Boolean,
    /** The play [PlayChoice.Auto] takes from the Ansible context, whatever is chosen now; null when Auto means every play. */
    val autoPlay: PlayRef?,
    /** The target of the inventory view: the host with the root's playbook dir. */
    private val inventoryTarget: EvalTarget,
    /** The plays that run on the host, in play-graph order: the play selector's entries. */
    val plays: List<PlayMatch>,
    /** Why a chosen play is not used ("playbook-setup-system.yml#9 no longer runs on prod-prod1"), or null. */
    @Nls val problem: String?,
) {
    /** The one play evaluated, or null for the inventory view and for [allPlays]. */
    val play: PlayRef? get() = if (allPlays) null else evaluated.singleOrNull()

    /** What is evaluated: the inventory target, or one target per play of [evaluated] with that play's playbook dir. */
    val targets: List<EvalTarget>
        get() = if (evaluated.isEmpty()) listOf(inventoryTarget) else evaluated.map { EvalTarget(host, it, it.playbookDir) }

    /** The playbook dirs the evaluation reads `group_vars` and `host_vars` from, distinct, in play order. */
    val playbookDirs: List<VirtualFile> get() = targets.mapNotNull { it.playbookDir }.distinct()
}

/** One definition as a cell of the table: where it is written, its masked value preview, type and layer. */
class DefinitionCell(
    val location: SourceLocation,
    /** The value preview; vault values and secret files are masked (`🔒 …`), never decrypted. */
    @Nls val value: String,
    val kind: ValueKind,
    val layer: VarsLayer,
    /** The group, host or role the layer belongs to (`keycloak`, `prod-prod1`, `postfix`), or null. */
    val owner: String?,
    /** `L5 playbook group_vars/all`, `L2 role defaults of postfix`. */
    @Nls val layerText: String,
    /** `group_vars/all/vars.yml:156`, relative to the inventory root. */
    val source: String,
    /** True when the value is masked (a vault value, a `vault_*` name, a vault file). */
    val masked: Boolean,
    /** Under `hash_behaviour = merge`: this shadowed dictionary still contributes keys. */
    val merged: Boolean = false,
)

/** "May be replaced at runtime by set_fact at roles/x/tasks/main.yml:12". */
class MarkerCell(val kind: RuntimeMarkerKind, val location: SourceLocation, val source: String)

/** Under Auto over every play: a definition that wins in some of the plays ([plays], in play-graph order). */
class PlayOutcome(val winner: DefinitionCell, val plays: List<PlayRef>)

/**
 * One variable of a host: the winning definition, the ones it shadows (runner-up first) and the runtime markers.
 *
 * Over several plays ([EffectiveContext.allPlays]) the winner is the definition that wins in the most plays (the
 * earlier play on a tie); [plays] are the plays it wins in, [others] the definitions that win in the remaining plays
 * that define the variable, most plays first. [shadowed] and [markers] are the union over the plays, without the
 * definitions that win somewhere.
 */
class EffectiveRow(
    val name: String,
    val winner: DefinitionCell,
    val shadowed: List<DefinitionCell>,
    val markers: List<MarkerCell>,
    /** The plays [winner] wins in; empty for the inventory view. */
    val plays: List<PlayRef> = emptyList(),
    val others: List<PlayOutcome> = emptyList(),
) {
    /** The number of evaluated plays that define the variable. */
    val definedIn: Int get() = plays.size + others.sumOf { it.plays.size }
}

/**
 * The Effective vars of one host in one [context] (plan amendment R7/R8 F8.7, WU HA7a), sorted by name. [markersKnown]
 * is false without a play (there are none) and while indexing (the markers need the variable index).
 */
class EffectiveTable(val context: EffectiveContext, val rows: List<EffectiveRow>, val markersKnown: Boolean) {
    fun row(name: String): EffectiveRow? = rows.firstOrNull { it.name == name }
}

/** The cached part of an [EffectiveTable]: it depends on the evaluated (host, plays) only, never on how they were chosen. */
private class EffectiveRows(val rows: List<EffectiveRow>, val markersKnown: Boolean)

/**
 * Builds and caches the Effective vars of hosts (plan amendment R7/R8 F8.7, WU HA7a).
 *
 * - **Lazily:** a table is computed when a node or the details pane asks for it, i.e. when its host is expanded or its
 *   Effective vars are selected; listing hosts computes none.
 * - **Off the EDT:** callers are the tree's background invoker and the details pane's background read action; loops
 *   check for cancellation.
 * - **Cached on the model trackers (HA2):** one [ModelCache] entry (kind [ModelCacheKind.PRESENTATION]) per (host,
 *   evaluated plays), validated by the inventory views and execution inputs it read; a table with plays also depends
 *   on the PSI (its runtime markers come from the variable index) and on dumb mode. The resolution of
 *   [PlayChoice.Auto] reads the selection, which is never part of a model key.
 * - **Vault-safe:** previews follow the one vault-safe rule; vault values are masked and never decrypted.
 */
@Service(Service.Level.PROJECT)
class EffectiveTables(private val project: Project) {
    private data class Key(val rootDir: VirtualFile, val host: HostKey, val plays: List<String>)

    private val cache = ModelCache<Key, EffectiveRows>(project, CACHE_NAME, ModelCacheKind.PRESENTATION, MAX_TABLES)
    private val computed = ConcurrentLinkedQueue<HostKey>()

    /** The counters of the table cache (one computation per host and evaluated plays). */
    val stats: ModelCacheStats get() = cache.stats

    /**
     * The hosts of the last [RECENT] table computations, oldest first (tests check that expanding a host computes only
     * that one).
     */
    val computedHosts: List<HostKey> get() = computed.toList()

    /**
     * Resolves the play choice of [host] of [environment] in [root] (a project root; nested roots share its hosts).
     * Null when the host context is not available. Needs a read action.
     */
    fun context(root: AnsibleRoot, environment: String, host: String): EffectiveContext? {
        val reads = HostContextReads.getInstance(project) ?: return null
        val key = reads.hostKey(root, environment, host)
        val plays = reads.playsOn(root, key)
        val choice = EffectivePlayChoices.getInstance(project).get(key)
        val inventory = EvalTarget(key, null, reads.defaultPlaybookDir(root))
        val chosen = (choice as? PlayChoice.Play)?.let { PlayKeys.resolve(project, root, it.key) }?.takeIf { ref -> plays.any { it.play == ref } }
        val problem = (choice as? PlayChoice.Play)?.takeIf { chosen == null }?.let { message("effective.choice.stale", it.key, host) }
        val autoPlay = contextPlay(root, environment, host, plays)
        val fromContext = autoPlay.takeIf { choice != PlayChoice.Inventory && chosen == null }
        val evaluated = when {
            choice == PlayChoice.Inventory -> emptyList()
            chosen != null -> listOf(chosen)
            fromContext != null -> listOf(fromContext)
            else -> plays.map { it.play }
        }
        val allPlays = choice != PlayChoice.Inventory && chosen == null && fromContext == null && evaluated.isNotEmpty()
        return EffectiveContext(root, key, choice, evaluated, fromContext != null, allPlays, autoPlay, inventory, plays, problem)
    }

    /**
     * The table of [context]; its rows come from the cache while their inputs are unchanged (two choices that evaluate
     * the same plays share them). Needs a read action.
     */
    fun table(context: EffectiveContext): EffectiveTable? {
        val reads = HostContextReads.getInstance(project) ?: return null
        val key = Key(context.root.dir, context.host, context.evaluated.map { PlayKeys.of(context.root, it) })
        val rows = cache.get(key) {
            computed += context.host
            while (computed.size > RECENT) computed.poll()
            compute(reads, context)
        }
        return EffectiveTable(context, rows.rows, rows.markersKnown)
    }

    /** [context] and [table] for one host in one call. */
    fun tableOf(root: AnsibleRoot, environment: String, host: String): EffectiveTable? = context(root, environment, host)?.let(::table)

    /** One evaluated target: its play (null for the inventory view) and its typed variables by name. */
    private class View(val play: PlayRef?, val vars: Map<String, TypedEffectiveVar>)

    private fun compute(reads: HostContextReads, context: EffectiveContext): EffectiveRows {
        val base = reads.inventoryRoot(context.root).dir
        val views = context.targets.map { target ->
            ProgressManager.checkCanceled()
            View(target.play, reads.typedView(target, context.root).orEmpty().associateBy { it.name })
        }
        val names = views.flatMapTo(sortedSetOf()) { it.vars.keys }
        val markers = markers(reads, context, base, names)
        val rows = names.map { name ->
            ProgressManager.checkCanceled()
            row(base, name, views, markers?.get(name).orEmpty())
        }
        return EffectiveRows(rows, markersKnown = context.evaluated.isNotEmpty() && markers != null)
    }

    /**
     * The row of [name] over [views]: one view gives its winner and shadowed definitions as they are; several group the
     * plays by the definition that wins (as [AnsibleContextService.effective] groups hosts and plays into outcomes).
     */
    private fun row(base: VirtualFile, name: String, views: List<View>, markers: List<MarkerCell>): EffectiveRow {
        class Outcome(val winner: TypedSourceRef) {
            val plays = ArrayList<PlayRef>()
        }
        val outcomes = LinkedHashMap<SourceLocation, Outcome>()
        val shadowed = LinkedHashMap<SourceLocation, TypedSourceRef>()
        val merged = HashSet<SourceLocation>()
        for (view in views) {
            val variable = view.vars[name] ?: continue
            val outcome = outcomes.getOrPut(locationOf(variable.winner)) { Outcome(variable.winner) }
            view.play?.let(outcome.plays::add)
            for (shadow in variable.shadowed) shadowed.putIfAbsent(locationOf(shadow), shadow)
            variable.mergedFrom.mapTo(merged, ::locationOf)
        }
        // A stable sort: on a tie the outcome of the earlier play stays first.
        val ranked = outcomes.values.sortedByDescending { it.plays.size }
        val winner = ranked.first()
        return EffectiveRow(
            name = name,
            winner = cell(base, winner.winner, merged = false),
            shadowed = shadowed.filterKeys { it !in outcomes }.values.map { cell(base, it, merged = locationOf(it) in merged) },
            markers = markers,
            plays = winner.plays,
            others = ranked.drop(1).map { PlayOutcome(cell(base, it.winner, merged = false), it.plays) },
        )
    }

    /**
     * The runtime markers of [names] over the context's plays (their union, deduplicated, in file order), or null when
     * they cannot be known now (indexing). The entry then depends on the PSI and on dumb mode, so a new `set_fact` or
     * the end of indexing recomputes it.
     */
    private fun markers(reads: HostContextReads, context: EffectiveContext, base: VirtualFile, names: Collection<String>): Map<String, List<MarkerCell>>? {
        if (context.evaluated.isEmpty()) return emptyMap()
        val dumb = DumbService.getInstance(project)
        ModelInputs.external(dumb.isDumb) { DumbService.isDumb(project) }
        if (dumb.isDumb) return null
        val tracker = PsiModificationTracker.getInstance(project)
        ModelInputs.external(tracker.modificationCount) { tracker.modificationCount }
        val union = HashMap<String, LinkedHashSet<RuntimeMarker>>()
        try {
            for (target in context.targets) {
                for ((name, found) in reads.runtimeMarkers(target, context.root, names)) union.getOrPut(name, ::LinkedHashSet) += found
            }
        } catch (_: IndexNotReadyException) {
            return null
        }
        return union.mapValues { (_, markers) ->
            markers.sortedWith(MARKER_ORDER).map { MarkerCell(it.kind, it.location, SourceLabels.of(base, it.location.file, it.location.offset)) }
        }
    }

    private fun cell(base: VirtualFile, typed: TypedSourceRef, merged: Boolean): DefinitionCell {
        val ref = typed.ref
        return DefinitionCell(
            location = locationOf(typed),
            value = valueText(ref),
            kind = typed.kind,
            layer = ref.layer,
            owner = ref.group ?: ref.host ?: ref.role,
            layerText = layerText(ref),
            source = SourceLabels.of(base, ref.file, ref.offset),
            masked = ref.isVault || ref.preview == null,
            merged = merged,
        )
    }

    private fun locationOf(typed: TypedSourceRef) = SourceLocation(typed.ref.file, typed.ref.offset)

    /** The context's play when it selects [host] of [environment] and a play that runs on it. */
    private fun contextPlay(root: AnsibleRoot, environment: String, host: String, plays: List<PlayMatch>): PlayRef? {
        val selection = AnsibleContextService.getInstance(project).selection(root)
        if ((selection.environment as? EnvironmentChoice.Named)?.name != environment || selection.host != host) return null
        val key = selection.play ?: return null
        return PlayKeys.resolve(project, root, key)?.takeIf { ref -> plays.any { it.play == ref } }
    }

    companion object {
        /** The [ModelCache] name of the tables. */
        const val CACHE_NAME: String = "toolwindow.effective"

        /**
         * A table holds a few hundred rows, and computing one again takes well under 100 ms (every play of a host), so
         * the cache keeps the tables of the hosts in use and bounds the memory of a long session.
         */
        private const val MAX_TABLES = 64

        /** How many computations [computedHosts] remembers. */
        private const val RECENT = 64

        private val MARKER_ORDER = compareBy<RuntimeMarker>({ it.location.file.path }, { it.location.offset }, { it.kind })

        fun getInstance(project: Project): EffectiveTables = project.service()

        /** The masked or previewed value [ref] defines: vault values, `vault_*` names and vault files are never shown. */
        @Nls
        fun valueText(ref: VarSourceRef): String = when {
            ref.isVault -> message("effective.value.vault")
            ref.preview != null -> ref.preview
            ValueSummary.isVaultFileName(ref.file.name) -> message("effective.value.hidden.file")
            else -> message("effective.value.hidden.name")
        }

        /** `L5 playbook group_vars/all`, `L6 env group_vars/keycloak`, `L2 role defaults of postfix`. */
        @Nls
        fun layerText(ref: VarSourceRef): String = message("effective.layer.${ref.layer.name}", ref.group ?: ref.host ?: ref.role ?: ref.play?.name.orEmpty())
    }
}
