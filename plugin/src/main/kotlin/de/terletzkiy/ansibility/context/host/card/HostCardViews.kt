package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.EffectiveBreakdown
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.OutcomeGroup
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.Location
import de.terletzkiy.ansibility.model.effective.VarSourceRefs
import de.terletzkiy.ansibility.model.inventory.ModelCache
import de.terletzkiy.ansibility.model.inventory.ModelCacheKind
import de.terletzkiy.ansibility.model.inventory.ModelInputs

/**
 * One outcome of a [CardView]: an [OutcomeGroup] of the breakdown with what the card adds to it.
 */
internal class CardOutcome(
    val group: OutcomeGroup,
    /** The first target of the scope whose winner is this outcome's (the Explain link); null for molecule companions. */
    val explainTarget: EvalTarget?,
    /** Each shadowed definition, runner-up first, with the hosts of this outcome on which it loads and loses. */
    val shadowed: List<Pair<VarSourceRef, List<HostKey>>>,
    /** Where a bare `{{ other }}` winner resolves in the outcome's contexts, with the hosts of each end. */
    val chains: List<Pair<ResolvedChain, List<HostKey>>>,
)

/** An address that several hosts of a [CardView] share while they get different outcomes, and the number of names on it. */
internal class SharedAddress(val address: String, val names: Int)

/**
 * What the host-aware card parts know about variable [name] seen from one file position (plan amendment R7/R8, F8.2):
 * the selection-aware [scope] ([de.terletzkiy.ansibility.api.AnsibleContextService.hostScope]), the block and task vars
 * in force there ([taskVars]), its [breakdown] and, per definition location, the hosts of the scope on which the
 * definition wins ([winsOn]) or loads and loses ([shadowedOn]). Holds locations and the engine's vault-safe previews
 * only, never a decrypted value.
 */
internal class CardView(
    val scope: HostScope,
    val name: String,
    /** The role whose tasks the scope's contexts run (a role file's or template's role), or null. */
    val runningRole: String?,
    /** The block and task vars (level 15) that apply at the position; [breakdown] and the outcomes include [TaskVars.applied]. */
    val taskVars: TaskVars,
    val breakdown: EffectiveBreakdown,
    /** The outcomes of the scope's own hosts (inventory hosts, or a molecule scenario's for molecule files), largest first. */
    val outcomes: List<CardOutcome>,
    /** The molecule outcomes that accompany a role scope (F8.10: a separate line). */
    val moleculeCompanions: List<CardOutcome>,
    val winsOn: Map<Location, Set<HostKey>>,
    val shadowedOn: Map<Location, Set<HostKey>>,
    /** The addresses on which hosts of different [outcomes] sit ("7 names share 192.0.2.43": ports per preview host). */
    val sharedAddresses: List<SharedAddress>,
) {
    /** Every host of the scope that the card talks about, molecule companions included, in scope order. */
    val hosts: List<HostKey> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        (scope.hosts + outcomes.flatMap { it.group.hosts } + breakdown.undefinedOn + moleculeCompanions.flatMap { it.group.hosts }).distinct()
    }

    /** The one host of a host-mode card (a selected host, a `host_vars` file), or null in All mode. */
    val singleHost: HostKey? get() = hosts.singleOrNull()
}

/**
 * The [CardView]s of the variable card (plan amendment R7/R8, F8.2), computed once per (scope, block and task vars,
 * name) and shared by the Effective section, the ranked "Set in" rows and the Explain card's other hosts of one card
 * (the Effect row reads the cached `definitionStatus`). Every part sees the card from the same position ([scopeOffset]).
 *
 * A [ModelCache] of kind [ModelCacheKind.PRESENTATION]: the key holds the selection-aware scope, the value is valid
 * while the views, play graphs and execution inputs it was evaluated from are (they are recorded as its inputs). The
 * evaluation is single-name (`PrecedenceEngine.effectiveOf`) over the scope's targets on the cached inventory views and
 * execution inputs, so a card costs well under a millisecond per target and never scans an index.
 *
 * Call in a read action in smart mode (the platform builds cards there); nothing here decrypts or runs a process.
 */
@Service(Service.Level.PROJECT)
class HostCardViews(private val project: Project) {
    private data class Key(val scope: HostScope, val taskVars: TaskVars, val name: String)

    private val cache = ModelCache<Key, CardView>(project, CACHE_NAME, ModelCacheKind.PRESENTATION, MAX_CACHED)

    /**
     * The view of variable [name] for a card shown from [context] ([CardContext.offset] -1: reached through a link);
     * [definition] is the documented definition of a definition card, null for a reference ([scopeOffset]).
     */
    internal fun view(definition: SourceLocation?, context: CardContext, name: String): CardView? =
        view(context.file, scopeOffset(definition, context), name)

    /**
     * The view of variable [name] seen from [file] at [offset] (negative: file level), or null in dumb mode, outside
     * every Ansible root, or when the model cannot answer (logged; the card then shows its root-wide rows only).
     */
    internal fun view(file: VirtualFile, offset: Int, name: String): CardView? {
        if (DumbService.isDumb(project)) return null
        if (AnsibleWorkspace.getInstance(project).rootFor(file) == null) return null
        val impl = AnsibleContextServiceImpl.getInstance(project) ?: return null
        return try {
            val scope = impl.cardScope(file, offset)
            val taskVars = TaskVars.at(project, scope.root, file, offset, name)
            cache.get(Key(scope, taskVars, name)) { compute(impl, scope, taskVars, name) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: IndexNotReadyException) {
            null
        } catch (e: RuntimeException) {
            LOG.warn("Ansibility: host-aware card of $name in ${file.path} failed (${e.javaClass.simpleName})", e)
            null
        }
    }

    private class Accumulator {
        var explainTarget: EvalTarget? = null
        val shadowed = LinkedHashMap<Location, Pair<VarSourceRef, LinkedHashSet<HostKey>>>()
        val chains = LinkedHashMap<Any, Pair<ResolvedChain, LinkedHashSet<HostKey>>>()
    }

    /** An outcome's identity: its winner's location, and whether its hosts are molecule hosts (never merged with inventory hosts). */
    private data class OutcomeKey(val winner: Location, val molecule: Boolean)

    private fun compute(impl: AnsibleContextServiceImpl, scope: HostScope, taskVars: TaskVars, name: String): CardView {
        val role = impl.runningRole(scope)
        val breakdown = taskVars.applyTo(impl.effective(scope, name), scope)
        // A task var's own value starts its chain; reading it makes the task file an input of the entry.
        val local = taskVars.applied
        val localValue = local?.let {
            ModelInputs.file(project, it.file)
            TaskVars.valueOf(project, it)
        }
        val accumulators = LinkedHashMap<OutcomeKey, Accumulator>()
        val shadowedOn = LinkedHashMap<Location, LinkedHashSet<HostKey>>()
        for (target in scope.targets) {
            ProgressManager.checkCanceled()
            val evaluation = impl.evaluator.evaluation(target, role, scope.root) ?: continue
            val effective = evaluation.effectiveOf(name)
            val staticWinner = effective?.let(evaluation::ref)
            val staticShadowed = effective?.shadowed?.mapNotNull { VarSourceRefs.ref(it, evaluation.origins) }.orEmpty()
            val (winner, shadowed) = taskVars.outcome(staticWinner, staticShadowed) ?: continue
            val accumulator = accumulators.getOrPut(OutcomeKey(Location.of(winner), target.host.isMolecule)) { Accumulator() }
            if (accumulator.explainTarget == null) accumulator.explainTarget = target
            for (ref in shadowed) {
                val location = Location.of(ref)
                accumulator.shadowed.getOrPut(location) { ref to LinkedHashSet() }.second += target.host
                shadowedOn.getOrPut(location) { LinkedHashSet() } += target.host
            }
            val value = if (winner === local) localValue else effective?.value
            value?.let { ValueChains.follow(evaluation, name, winner, it) }?.let { chain ->
                accumulator.chains.getOrPut(chain.key) { chain to LinkedHashSet() }.second += target.host
            }
        }
        val winsOn = LinkedHashMap<Location, LinkedHashSet<HostKey>>()
        for (group in breakdown.groups + breakdown.molecule) {
            val winner = group.winner ?: continue
            winsOn.getOrPut(Location.of(winner)) { LinkedHashSet() } += group.hosts
        }
        // Molecule companions are evaluated by the service only: every host of their outcome loads what it shadows.
        for (group in breakdown.molecule) {
            val winner = group.winner ?: continue
            if (accumulators.containsKey(OutcomeKey(Location.of(winner), true))) continue
            for (ref in group.shadowed) shadowedOn.getOrPut(Location.of(ref)) { LinkedHashSet() } += group.hosts
        }
        // A host on which a definition wins in one context does not count as shadowed by it in another.
        for ((location, hosts) in shadowedOn) winsOn[location]?.let(hosts::removeAll)

        fun outcome(group: OutcomeGroup, molecule: Boolean): CardOutcome {
            val winner = group.winner
            val accumulator = winner?.let { accumulators[OutcomeKey(Location.of(it), molecule)] }
            if (accumulator == null) {
                return CardOutcome(group, null, group.shadowed.map { it to group.hosts }, emptyList())
            }
            val hosts = group.hosts.toSet()
            return CardOutcome(
                group = group,
                explainTarget = accumulator.explainTarget,
                // Runner-up first: the highest layer first, ties in the order the contexts met them.
                shadowed = accumulator.shadowed.values
                    .sortedByDescending { (ref, _) -> ref.layer.level }
                    .map { (ref, on) -> ref to on.filter(hosts::contains) },
                chains = accumulator.chains.values.map { (chain, on) -> chain to on.filter(hosts::contains) },
            )
        }

        val ownMolecule = scope.targets.isNotEmpty() && scope.targets.all { it.host.isMolecule }
        val outcomes = if (ownMolecule) breakdown.molecule.map { outcome(it, molecule = true) } else breakdown.groups.map { outcome(it, molecule = false) }
        val companions = if (ownMolecule) emptyList() else breakdown.molecule.map { outcome(it, molecule = true) }
        return CardView(scope, name, role, taskVars, breakdown, outcomes, companions, winsOn, shadowedOn, sharedAddresses(impl, scope, outcomes))
    }

    /**
     * Each address that several hosts of [scope] share while they get different [outcomes], from the scope's inventory
     * facts (computed here, so a warm card reads them from the cached view instead of scanning every host's address).
     */
    private fun sharedAddresses(impl: AnsibleContextServiceImpl, scope: HostScope, outcomes: List<CardOutcome>): List<SharedAddress> {
        if (outcomes.size < 2) return emptyList()
        val outcomeOf = HashMap<HostKey, Int>()
        outcomes.forEachIndexed { index, outcome -> outcome.group.hosts.forEach { outcomeOf.putIfAbsent(it, index) } }
        val shared = ArrayList<SharedAddress>()
        for (environment in impl.inventoryFacts(scope).environments) {
            ProgressManager.checkCanceled()
            val byAddress = environment.hosts.filter { it.address != null && it.key in outcomeOf }.groupBy { it.address!! }
            for ((address, hosts) in byAddress) {
                if (hosts.size < 2 || hosts.mapNotNull { outcomeOf[it.key] }.distinct().size < 2) continue
                shared += SharedAddress(address, hosts.first().sharesAddressWith.size + 1)
            }
        }
        return shared
    }

    companion object {
        /** The [ModelCache] name of the card views ([de.terletzkiy.ansibility.model.inventory.ModelCaches.snapshot]). */
        const val CACHE_NAME: String = "presentation.cardViews"

        private const val MAX_CACHED = 512
        private val LOG = logger<HostCardViews>()

        fun getInstance(project: Project): HostCardViews = project.service()

        /**
         * The offset of [context]'s file whose host scope a card shows: the card's own, except that a definition card
         * reached through a link (offset -1) is seen from its [definition]'s key, so the play of a playbook's `vars:` key
         * or the group block of a `hosts.yml` entry still narrows it. A reference card reached through a link has no
         * position: it covers the whole file. The Effective section, the ranked "Set in" rows and the Explain card's
         * other hosts all use this one rule.
         */
        internal fun scopeOffset(definition: SourceLocation?, context: CardContext): Int =
            if (context.offset < 0 && definition != null && definition.file == context.file) definition.offset else context.offset
    }
}
