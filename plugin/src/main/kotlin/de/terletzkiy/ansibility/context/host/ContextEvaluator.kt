package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.ChainOutcome
import de.terletzkiy.ansibility.api.ChainStep
import de.terletzkiy.ansibility.api.EffectiveBreakdown
import de.terletzkiy.ansibility.api.EffectiveVars
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.OutcomeGroup
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.PrecedenceChain
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.model.effective.ExecutionInputs
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.model.effective.HostView
import de.terletzkiy.ansibility.model.effective.HostViews
import de.terletzkiy.ansibility.model.effective.MoleculeViews
import de.terletzkiy.ansibility.model.effective.SourceOrigin
import de.terletzkiy.ansibility.model.effective.VarSourceRefs
import de.terletzkiy.ansibility.semantics.precedence.EffectiveVar

/**
 * One evaluation context ready for single-name evaluation: the inventory view of the target's host, the execution
 * inputs of its play and running role, and the origins of both.
 */
internal class Evaluation(
    val target: EvalTarget,
    /** The root the context belongs to (execution sources and runtime markers are looked up there). */
    val root: AnsibleRoot,
    val host: HostView,
    val inputs: ExecutionInputs,
) {
    val origins: Map<String, SourceOrigin> = host.origins + inputs.origins

    /** `PrecedenceEngine.effectiveOf`: the value of [name] for a task of the context. */
    fun effectiveOf(name: String): EffectiveVar? = host.engine.effectiveOf(name, host.view, inputs.sources)

    fun ref(effective: EffectiveVar): VarSourceRef? = VarSourceRefs.ref(effective.winner, origins)
}

/** A definition's position, the identity outcomes are grouped by (never its value, so vault values group too). */
internal data class Location(val file: VirtualFile, val offset: Int) {
    companion object {
        fun of(ref: VarSourceRef) = Location(ref.file, ref.offset)
    }
}

/**
 * Evaluates variables over evaluation targets (plan amendment R7/R8, A.14 "Per-host evaluation"): the
 * [EffectiveBreakdown] of a scope, the "Explain precedence" chain of one target and the full views. Call in a read
 * action; [explain] needs smart mode for its runtime markers.
 */
internal class ContextEvaluator(private val project: Project, private val model: ContextModel) {
    /** The context of [target] in [scopeRoot] (null: the root its host key names) with [runningRole], or null when the host is gone. */
    fun evaluation(target: EvalTarget, runningRole: String?, scopeRoot: AnsibleRoot? = null): Evaluation? {
        val inventoryRoot = scopeRoot ?: model.rootOfKey(target.host.root) ?: return null
        val view = if (target.host.isMolecule) {
            val molecule = model.molecule(model.inventoryRoot(inventoryRoot), target.host.environment) ?: return null
            MoleculeViews.getInstance(project).view(model.workspace.rootFor(molecule.scenarioDir) ?: inventoryRoot, molecule, target.host.host)
        } else {
            HostViews.getInstance(project).view(inventoryRoot, target.host.environment, target.host.host, target.playbookDir)
        } ?: return null
        val executionRoot = executionRoot(target.play, inventoryRoot)
        val inputs = ExecutionSources.getInstance(project).inputs(executionRoot, target.play, runningRole)
        return Evaluation(target, executionRoot, view, inputs)
    }

    /**
     * The root whose execution sources [play] uses: the root of its file (a nested root's own plays), unless that is a
     * detached worktree and [inventoryRoot] is not; [inventoryRoot] without a play.
     */
    fun executionRoot(play: PlayRef?, inventoryRoot: AnsibleRoot): AnsibleRoot =
        play?.let { model.workspace.rootFor(it.file) }?.takeIf { !it.detached || inventoryRoot.detached } ?: inventoryRoot

    /**
     * The outcome of [name] on [targets], grouped by winner location (largest group first); [molecule] targets give
     * the separate molecule outcomes. A host that several contexts evaluate appears in each outcome it reaches, and in
     * [EffectiveBreakdown.undefinedOn] only when no context defines the name.
     */
    fun breakdown(name: String, targets: List<EvalTarget>, molecule: List<EvalTarget>, runningRole: String?, scopeRoot: AnsibleRoot): EffectiveBreakdown {
        val (envTargets, moleculeTargets) = (targets + molecule).distinct().partition { !it.host.isMolecule }
        val undefined = LinkedHashSet<HostKey>()
        val groups = outcomes(name, envTargets, runningRole, scopeRoot, undefined)
        val moleculeGroups = outcomes(name, moleculeTargets, runningRole, scopeRoot, LinkedHashSet())
        return EffectiveBreakdown(name, groups, undefined.toList(), moleculeGroups)
    }

    private fun outcomes(name: String, targets: List<EvalTarget>, runningRole: String?, scopeRoot: AnsibleRoot, undefined: MutableSet<HostKey>): List<OutcomeGroup> {
        class Group(val winner: VarSourceRef?) {
            val hosts = LinkedHashSet<HostKey>()
            val shadowed = LinkedHashMap<Location, VarSourceRef>()
            val plays = LinkedHashSet<PlayRef>()
        }
        val groups = LinkedHashMap<Location, Group>()
        val defined = HashSet<HostKey>()
        val seen = LinkedHashSet<HostKey>()
        for (target in targets) {
            ProgressManager.checkCanceled()
            seen += target.host
            val evaluation = evaluation(target, runningRole, scopeRoot) ?: continue
            val effective = evaluation.effectiveOf(name) ?: continue
            val winner = evaluation.ref(effective) ?: continue
            defined += target.host
            val group = groups.getOrPut(Location.of(winner)) { Group(winner) }
            group.hosts += target.host
            target.play?.let(group.plays::add)
            for (shadow in effective.shadowed) {
                val ref = VarSourceRefs.ref(shadow, evaluation.origins) ?: continue
                group.shadowed.putIfAbsent(Location.of(ref), ref)
            }
        }
        seen.filterTo(undefined) { it !in defined }
        return groups.values
            .map { OutcomeGroup(it.winner, it.hosts.toList(), it.shadowed.values.toList(), it.plays.toList()) }
            .sortedByDescending { it.hosts.size }
    }

    /** The ordered chain of every definition of [name] that [target] loads. */
    fun explain(target: EvalTarget, name: String, runningRole: String?): PrecedenceChain {
        val evaluation = evaluation(target, runningRole) ?: return PrecedenceChain(name, target, runningRole, emptyList(), emptyList(), emptyList())
        val effective = evaluation.effectiveOf(name)
        val steps = effective?.definitions?.mapNotNull { definition ->
            val ref = VarSourceRefs.ref(definition, evaluation.origins) ?: return@mapNotNull null
            val outcome = when {
                definition === effective.winner -> ChainOutcome.WINNER
                definition in effective.mergedFrom -> ChainOutcome.MERGED
                else -> ChainOutcome.SHADOWED
            }
            ChainStep(ref, outcome)
        }.orEmpty()
        val markers = if (target.play != null || runningRole != null) {
            ExecutionSources.getInstance(project).runtimeMarkers(evaluation.root, evaluation.inputs, name)
        } else {
            emptyList()
        }
        return PrecedenceChain(name, target, runningRole, steps, markers, evaluation.inputs.unknownSources)
    }

    /** The full inventory view of [target] (levels 3–10). */
    fun inventoryView(target: EvalTarget): EffectiveVars? = evaluation(target, runningRole = null)?.host?.vars

    /** The full execution view of [target] with [runningRole]; null without a play. */
    fun executionView(target: EvalTarget, runningRole: String?): EffectiveVars? {
        if (target.play == null) return null
        val evaluation = evaluation(target, runningRole) ?: return null
        val view = evaluation.host.engine.executionView(evaluation.host.view, evaluation.inputs.sources)
        val entries = view.vars.values.sortedBy { it.name }.mapNotNull { VarSourceRefs.entry(it, evaluation.origins) }
        return EffectiveVars(target.host.host, target.host.environment, entries)
    }
}
