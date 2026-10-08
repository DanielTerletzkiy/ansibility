package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ChainOutcome
import de.terletzkiy.ansibility.api.ChainStep
import de.terletzkiy.ansibility.api.EffectiveBreakdown
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.OutcomeGroup
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.MoleculeVisibility
import de.terletzkiy.ansibility.context.host.Location
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.resolve.include.IncludeBindings
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * The block and task vars (level 15) that apply at a card's position: the plan's ExecutionView has them "for caret
 * queries only and never cached" (amendment R7/R8, A.14), and the model's single-name evaluation leaves them out, so the
 * card adds them where they apply. Host-independent: a task's `vars:` give every host that runs the task the same
 * definition, which beats every static layer below level 15 and loses to role params and extra vars.
 *
 * - [applied]: the innermost task or `block:` whose `vars:` set the name and enclose the position (a reference inside
 *   the task, or the key itself), or, in a role template, the `vars:` of the task that renders it when every render
 *   context sets the name through one and the same definition;
 * - [partial]: in a template, the task vars of the render contexts that set the name when not all of them do (or not
 *   through one definition): they apply to those renders only, so the card names them without changing the outcomes.
 * - [included]: the definitions the include tasks that run the file give the name ([IncludeBindings]); with
 *   [includedEverywhere] no host counts as "not set" here. On an include task's own `vars:` key, the definitions the
 *   other include tasks give the files that include runs.
 *
 * Precedence as ansible-core applies it: the `vars:` of dynamic includes (`include_tasks`, `include_role`) are include
 * params, merged after task vars, `include_vars`, `set_fact` and role params ([includeParam]): when every include path
 * gives the name that way, they win over the task's own `vars:` and every static layer below extra vars; an import's
 * `vars:` are task vars, below the task's own. Includers in Molecule files never count for a production file (plan
 * amendment R20, [MoleculeView.forAnalysis]).
 *
 * Values are the index's vault-safe previews; nothing here decrypts.
 */
internal data class TaskVars(
    val applied: VarSourceRef?,
    val partial: List<VarSourceRef>,
    /**
     * The definitions through which the include tasks that run the card's file give it the name (their `vars:` keys,
     * the `loop_var`/`index_var` of a looping include; for a template, those of its rendering tasks' files), when the
     * position has no own task var of the name. They apply on their include paths, so "Set in" never calls them "not
     * for" a host; one `vars:` definition that every path goes through is [applied] as well.
     */
    val included: List<VarSourceRef> = emptyList(),
    /** Every include path gives the name: hosts without a static definition are not "not set" here. */
    val includedEverywhere: Boolean = false,
    /**
     * [applied] is an include param (a dynamic include's `vars:` that every path goes through), or, without [applied],
     * every path gives the name through some dynamic include's `vars:`: they beat every layer below extra vars.
     */
    val includeParam: Boolean = false,
) {
    /**
     * [breakdown] with [applied] in force (or [breakdown] itself without it): every outcome whose winner sits below
     * level 15 is won by [applied] instead, the old winner becoming the runner-up; outcomes won higher up shadow
     * [applied]. Hosts without any static definition get [applied] too. Outcomes with one winner merge, largest first.
     */
    fun applyTo(breakdown: EffectiveBreakdown, scope: HostScope): EffectiveBreakdown {
        val local = applied ?: return when {
            // Every path sets it through include params of several definitions: they win over every static outcome
            // below extra vars, which the Effective section then names instead of a single winner.
            includeParam -> EffectiveBreakdown(breakdown.name, breakdown.groups.filter(::aboveIncludeParams), emptyList(), breakdown.molecule.filter(::aboveIncludeParams))
            // Every include path sets it (per path, through different definitions or a loop): nothing is "not set".
            includedEverywhere && breakdown.undefinedOn.isNotEmpty() -> EffectiveBreakdown(breakdown.name, breakdown.groups, emptyList(), breakdown.molecule)
            else -> breakdown
        }
        val undefined = breakdown.undefinedOn.toSet()
        val undefinedPlays = scope.targets.filter { it.host in undefined }.mapNotNull { it.play }.distinct()
        val fromUndefined = if (undefined.isEmpty()) emptyList() else listOf(OutcomeGroup(local, breakdown.undefinedOn, emptyList(), undefinedPlays))
        return EffectiveBreakdown(
            name = breakdown.name,
            groups = merge(breakdown.groups.map(::withLocal) + fromUndefined),
            undefinedOn = emptyList(),
            molecule = merge(breakdown.molecule.map(::withLocal)),
        )
    }

    /**
     * One context's outcome with [applied] in force: the winner and the definitions it shadows, runner-up first, from the
     * context's static [winner] (null: no static definition) and [shadowed]; null when nothing defines the name there.
     */
    fun outcome(winner: VarSourceRef?, shadowed: List<VarSourceRef>): Pair<VarSourceRef, List<VarSourceRef>>? {
        val local = applied ?: included.firstOrNull()?.takeIf { includeParam } ?: return winner?.let { it to shadowed }
        if (winner == null || beats(local, winner)) return local to listOfNotNull(winner) + shadowed
        return winner to byPrecedence(shadowed + local)
    }

    /** [steps] (an Explain chain, lowest precedence first) with [applied] inserted at its level, winning when it beats the winner. */
    fun applyTo(steps: List<ChainStep>): List<ChainStep> {
        val local = applied ?: return steps
        val winner = steps.lastOrNull { it.outcome == ChainOutcome.WINNER }?.source
        val wins = winner == null || beats(local, winner)
        val adjusted = if (wins) steps.map { if (it.outcome == ChainOutcome.WINNER) ChainStep(it.source, ChainOutcome.SHADOWED) else it } else steps
        val at = adjusted.indexOfFirst { it.source.layer.level > local.layer.level }.let { if (it < 0) adjusted.size else it }
        return adjusted.subList(0, at) + ChainStep(local, if (wins) ChainOutcome.WINNER else ChainOutcome.SHADOWED) + adjusted.subList(at, adjusted.size)
    }

    /** Whether [applied] (level 15, or an include param) beats [winner]. */
    private fun beats(local: VarSourceRef, winner: VarSourceRef): Boolean =
        winner.layer.level < (if (includeParam) VarsLayer.EXTRA_VARS.level else local.layer.level)

    /** An outcome include params do not override: won by extra vars. */
    private fun aboveIncludeParams(group: OutcomeGroup): Boolean = group.winner?.layer == VarsLayer.EXTRA_VARS

    private fun withLocal(group: OutcomeGroup): OutcomeGroup {
        val (winner, shadowed) = outcome(group.winner, group.shadowed) ?: return group
        return OutcomeGroup(winner, group.hosts, shadowed, group.plays)
    }

    private fun merge(groups: List<OutcomeGroup>): List<OutcomeGroup> {
        class Merged(val winner: VarSourceRef?) {
            val hosts = LinkedHashSet<HostKey>()
            val shadowed = LinkedHashMap<Location, VarSourceRef>()
            val plays = LinkedHashSet<PlayRef>()
        }
        val merged = LinkedHashMap<Location?, Merged>()
        for (group in groups) {
            val entry = merged.getOrPut(group.winner?.let(Location::of)) { Merged(group.winner) }
            entry.hosts += group.hosts
            group.shadowed.forEach { entry.shadowed.putIfAbsent(Location.of(it), it) }
            entry.plays += group.plays
        }
        return merged.values
            .map { OutcomeGroup(it.winner, it.hosts.toList(), byPrecedence(it.shadowed.values.toList()), it.plays.toList()) }
            .sortedByDescending { it.hosts.size }
    }

    companion object {
        val NONE = TaskVars(null, emptyList())

        /** The definition kinds a task's or a block's own `vars:` mapping writes. */
        private val KINDS = setOf(VarDefKind.TASK_VARS, VarDefKind.BLOCK_VARS)

        /** The definition kinds an include task gives the included file: `vars:` (role params of `include_role`) and loop names. */
        private val INCLUDED_KINDS = setOf(VarDefKind.TASK_VARS, VarDefKind.BLOCK_VARS, VarDefKind.INCLUDE_PARAMS, VarDefKind.LOOP_VAR, VarDefKind.INDEX_VAR)

        /** Files whose positions sit inside tasks. */
        private val TASK_KINDS = setOf(
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS,
        )

        /** Runner-up first (the highest level first), ties in the order given. */
        private fun byPrecedence(refs: List<VarSourceRef>): List<VarSourceRef> = refs.sortedByDescending { it.layer.level }

        /**
         * The block and task vars of [name] that apply at [offset] of [file] (negative: the whole file, which no task
         * encloses), as a card shown in [file] sees them ([MoleculeView.of] [file]: outside Molecule, while Molecule is
         * hidden, a template rendered by a converge task gets none of that task's vars), with what the include tasks that
         * run [file] give it ([MoleculeView.forAnalysis]). Call in a read action in smart mode; reads the file's PSI and,
         * for templates, the render contexts.
         */
        fun at(project: Project, root: AnsibleRoot, file: VirtualFile, offset: Int, name: String): TaskVars {
            if (offset < 0) return NONE
            val view = MoleculeView.of(project, file)
            val all = VarViews.symbol(project, root, name, view).definitions
            val definitions = all.filter { it.kind in KINDS }
            val includable = all.filter { it.kind in INCLUDED_KINDS }
            if (includable.isEmpty()) return NONE
            val own = when (AnsibleWorkspace.getInstance(project).contextOf(file)?.kind) {
                in TASK_KINDS -> enclosing(project, definitions, file, offset, reference = true)?.let { TaskVars(ref(it), emptyList()) } ?: NONE
                FileKind.ROLE_TEMPLATE -> if (definitions.isEmpty()) NONE else rendering(project, definitions, file, name, view)
                else -> NONE
            }
            val includeView = MoleculeView.forAnalysis(project, file)
            val applied = own.applied ?: return included(project, includable, file, name, includeView, own)
            // On an include task's own `vars:` key: the other include tasks that run the same files give the name there too.
            siblings(project, includable, applied, name)?.let { return it }
            // Include params beat the task's own `vars:` when every include path gives the name that way.
            return included(project, includable, file, name, includeView, own).takeIf { it.includeParam } ?: own
        }

        /**
         * What the include tasks that run [file] give [name] ([IncludeBindings.coverageAt]): their definitions, whether
         * every include path gives it, and, when every path's winning includer gives it through one and the same `vars:`
         * definition, that definition [applied] (an include param when the includers are dynamic). [own] (the card's
         * own task vars, if any) stays when no include param overrides it.
         */
        private fun included(project: Project, definitions: List<VarDefinition>, file: VirtualFile, name: String, view: MoleculeView, own: TaskVars): TaskVars {
            val coverage = IncludeBindings.coverageAt(project, file, name, view)
            if (!coverage.provided) return own
            val locations = coverage.providers.flatMapTo(HashSet()) { it.definitionsOf(name) }
            val refs = definitions.filter { it.location in locations }.distinctBy { it.location }.map(::ref)
            val everywhere = coverage.everywhere
            // `include_role` vars are role params, which the model already evaluates; a loop name has no level-15 value.
            val varsOnly = coverage.winners.none { winner -> winner.role || name in winner.loopNames }
            val winnerLocations = coverage.winners.flatMapTo(HashSet()) { it.definitionsOf(name) }
            val single = refs.filter { it.location() in winnerLocations }.singleOrNull()?.takeIf { everywhere && own.partial.isEmpty() && varsOnly }
            val params = coverage.dynamicEverywhere && varsOnly
            if (own.applied != null && !params) return own
            return TaskVars(single, own.partial, refs, everywhere, includeParam = params)
        }

        /**
         * On a key of an include task's own `vars:` ([applied] is one): the definitions every include task that runs the
         * same files gives them [name] with (this one among them), so "Set in" shows the others apply through the
         * include instead of "not for" a host; null for any other task var.
         */
        private fun siblings(project: Project, definitions: List<VarDefinition>, applied: VarSourceRef, name: String): TaskVars? {
            val reached = IncludeBindings.filesRunByVarsKey(project, SourceLocation(applied.file, applied.offset)).ifEmpty { return null }
            val coverages = reached.map { IncludeBindings.coverageAt(project, it, name, MoleculeView.forAnalysis(project, it)) }
            val locations = coverages.flatMapTo(HashSet()) { coverage -> coverage.providers.flatMap { it.definitionsOf(name) } }
            val refs = definitions.filter { it.location in locations }.distinctBy { it.location }.map(::ref)
            return TaskVars(applied, emptyList(), refs.filter { it.location() != applied.location() })
        }

        private fun VarSourceRef.location(): SourceLocation = SourceLocation(file, offset)

        /**
         * The definition [location] names when it is still a block or task var of [name] in [root] (an Explain link's
         * position, which an edit may have moved), as a source.
         */
        fun definitionAt(project: Project, root: AnsibleRoot, name: String, location: SourceLocation): VarSourceRef? =
            VarService.getInstance(project).symbol(root, name).definitions
                .firstOrNull { (it.kind in KINDS || it.kind == VarDefKind.INCLUDE_PARAMS) && it.location == location }?.let(::ref)

        /** The value [ref] writes (for the runtime-default chain of a task var), or null when its key is gone. */
        fun valueOf(project: Project, ref: VarSourceRef): YValue? =
            VarLocations.keyValueAt(project, SourceLocation(ref.file, ref.offset))?.let(PsiYValueAdapter::valueOf)

        /**
         * Every render context of [template] that [view] sees ([MoleculeVisibility.contextsInView]: no converge or verify
         * task while Molecule is hidden) sets [name] through one task var: it applies; otherwise those that do are partial.
         */
        private fun rendering(project: Project, definitions: List<VarDefinition>, template: VirtualFile, name: String, view: MoleculeView): TaskVars {
            val contexts = MoleculeVisibility.contextsInView(project, view, TemplateContextService.getInstance(project).renderContexts(template))
            if (contexts.none { name in it.taskVars }) return NONE
            val found = contexts.map { context ->
                ProgressManager.checkCanceled()
                if (name !in context.taskVars) null else enclosing(project, definitions, context.taskSite.file, context.taskSite.offset, reference = false)
            }
            val distinct = found.filterNotNull().distinctBy { it.location }
            return if (found.all { it != null } && distinct.size == 1) TaskVars(ref(distinct.single()), emptyList()) else TaskVars(null, distinct.map(::ref))
        }

        /**
         * The innermost task or block of [file] that encloses [offset] and whose `vars:` mapping sets one of
         * [definitions], or null. With [reference], a position inside a definition's own value does not see that
         * definition (`x: "{{ x }}"` refers to the value below it, not to itself).
         */
        private fun enclosing(project: Project, definitions: List<VarDefinition>, file: VirtualFile, offset: Int, reference: Boolean): VarDefinition? {
            val own = definitions.filter { it.location.file == file }
            if (own.isEmpty()) return null
            val yaml = YamlFiles.yamlFile(project, file) ?: return null
            var mapping = PsiTreeUtil.getParentOfType(yaml.findElementAt(offset), YAMLMapping::class.java, false)
            while (mapping != null) {
                ProgressManager.checkCanceled()
                val vars = (mapping.getKeyValueByKey(VARS)?.value as? YAMLMapping)?.keyValues.orEmpty()
                for (keyValue in vars) {
                    val definition = own.firstOrNull { it.location.offset == keyValue.key?.textRange?.startOffset } ?: continue
                    if (reference && inValue(keyValue, offset)) continue
                    return definition
                }
                mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java, true)
            }
            return null
        }

        private fun inValue(keyValue: YAMLKeyValue, offset: Int): Boolean = keyValue.value?.textRange?.containsOffset(offset) == true

        private fun ref(definition: VarDefinition): VarSourceRef = VarSourceRef(
            file = definition.location.file,
            offset = definition.location.offset,
            layer = definition.layer ?: VarsLayer.BLOCK_TASK_VARS,
            group = null,
            host = null,
            preview = definition.preview,
            isVault = definition.valueShape == ValueShape.VAULT,
            role = definition.roleName,
        )

        private const val VARS = "vars"
    }
}
