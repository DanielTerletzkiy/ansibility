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
import de.terletzkiy.ansibility.context.host.Location
import de.terletzkiy.ansibility.model.task.YamlFiles
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
 *
 * Values are the index's vault-safe previews; nothing here decrypts.
 */
internal data class TaskVars(val applied: VarSourceRef?, val partial: List<VarSourceRef>) {
    /**
     * [breakdown] with [applied] in force (or [breakdown] itself without it): every outcome whose winner sits below
     * level 15 is won by [applied] instead, the old winner becoming the runner-up; outcomes won higher up shadow
     * [applied]. Hosts without any static definition get [applied] too. Outcomes with one winner merge, largest first.
     */
    fun applyTo(breakdown: EffectiveBreakdown, scope: HostScope): EffectiveBreakdown {
        val local = applied ?: return breakdown
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
        val local = applied ?: return winner?.let { it to shadowed }
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

        /** Files whose positions sit inside tasks. */
        private val TASK_KINDS = setOf(
            FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS,
        )

        /** Whether [local] (level 15) beats [winner]: everything below block and task vars. */
        private fun beats(local: VarSourceRef, winner: VarSourceRef): Boolean = winner.layer.level < local.layer.level

        /** Runner-up first (the highest level first), ties in the order given. */
        private fun byPrecedence(refs: List<VarSourceRef>): List<VarSourceRef> = refs.sortedByDescending { it.layer.level }

        /**
         * The block and task vars of [name] that apply at [offset] of [file] (negative: the whole file, which no task
         * encloses). Call in a read action in smart mode; reads the file's PSI and, for templates, the render contexts.
         */
        fun at(project: Project, root: AnsibleRoot, file: VirtualFile, offset: Int, name: String): TaskVars {
            if (offset < 0) return NONE
            val definitions = VarService.getInstance(project).symbol(root, name).definitions.filter { it.kind in KINDS }
            if (definitions.isEmpty()) return NONE
            return when (AnsibleWorkspace.getInstance(project).contextOf(file)?.kind) {
                in TASK_KINDS -> enclosing(project, definitions, file, offset, reference = true)?.let { TaskVars(ref(it), emptyList()) } ?: NONE
                FileKind.ROLE_TEMPLATE -> rendering(project, definitions, file, name)
                else -> NONE
            }
        }

        /**
         * The definition [location] names when it is still a block or task var of [name] in [root] (an Explain link's
         * position, which an edit may have moved), as a source.
         */
        fun definitionAt(project: Project, root: AnsibleRoot, name: String, location: SourceLocation): VarSourceRef? =
            VarService.getInstance(project).symbol(root, name).definitions.firstOrNull { it.kind in KINDS && it.location == location }?.let(::ref)

        /** The value [ref] writes (for the runtime-default chain of a task var), or null when its key is gone. */
        fun valueOf(project: Project, ref: VarSourceRef): YValue? =
            VarLocations.keyValueAt(project, SourceLocation(ref.file, ref.offset))?.let(PsiYValueAdapter::valueOf)

        /** Every render context of [template] sets [name] through one task var: it applies; otherwise those that do are partial. */
        private fun rendering(project: Project, definitions: List<VarDefinition>, template: VirtualFile, name: String): TaskVars {
            val contexts = TemplateContextService.getInstance(project).renderContexts(template)
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
