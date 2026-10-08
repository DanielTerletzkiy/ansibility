package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.DefinitionStatus
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.HostScope
import de.terletzkiy.ansibility.api.HostScopeOrigin
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.context.host.Location
import de.terletzkiy.ansibility.context.host.PlayKeys
import de.terletzkiy.ansibility.context.host.card.HostCardTexts.grayed
import de.terletzkiy.ansibility.context.host.card.HostCardTexts.message
import de.terletzkiy.ansibility.context.switching.ContextTexts
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.resolve.VarViews
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootKeys

/**
 * The **Effect** row of a definition card (plan amendment R7/R8, F8.2 "This definition gains a status line";
 * `cardSection` id `ansibilityHostDefinition`, SECTION, right after the card's built-in "This definition" row):
 *
 * - `✓ effective for prod-alias1, prod-mlflow1, prod-platform1 · ✗ shadowed for prod-training1 by
 *   environments/prod/host_vars/prod-training1/mysql.yml:63 (L9 beats L6)`;
 * - `✗ ineffective for prod-prod1, prod-prod2: shadowed by group_vars/all/vars.yml:156 (L5 beats L4)`;
 * - `applies to prod-mlflow1, prod-training1 (via child group analytics)` for a group whose hosts come from child groups;
 * - why no host loads the definition ("app_platform is not a group of prod: this file is never loaded");
 * - at most [MAX_WINNERS] winners, then "+142 more definitions win on 142 hosts" (a value per host on a large inventory).
 *
 * The status is [AnsibleContextService.definitionStatus]: every (env, host, play) context that loads the definition's
 * file, selection-free and cached. The row presents it (D32: presentation is selection-aware): with a selected
 * environment, host or play it covers the hosts of the definition file's [AnsibleContextService.hostScope] only (the
 * child-group line too), and says "not loaded for test › test-test1" when the selection loads the definition nowhere
 * (followed by the file's own hosts when Follow editor lets the file scope override the selection). Declarations (spec
 * options) get no row. Never decrypts.
 */
class DefinitionCardSection : CardSection {
    override val placement: CardPlacement get() = CardPlacement.SECTION

    override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
        val variable = subject as? CardSubject.Variable ?: return null
        val location = variable.definition ?: return null
        if (variable.local) return null
        val project = context.project
        if (DumbService.isDumb(project) || AnsibleWorkspace.getInstance(project).rootFor(location.file) == null) return null
        val definition = VarViews.symbol(project, variable.root, variable.name, MoleculeView.of(project, context.file)).definitions
            .firstOrNull { it.location == location } ?: return null
        if (definition.kind == VarDefKind.SPEC_OPTION) return null
        val service = AnsibleContextService.getInstance(project)
        val status = service.definitionStatus(definition)
        val scope = AnsibleContextServiceImpl.getInstance(project)?.cardScope(location.file, location.offset) ?: service.hostScope(location.file, location.offset)
        val lines = Renderer(project, variable.root, definition, status, scope).lines()
        if (lines.isEmpty()) return null
        return CardSection.row(message("card.effect.title"), HostCardTexts.lines(lines))
    }

    private class Renderer(
        private val project: Project,
        private val root: AnsibleRoot,
        private val definition: VarDefinition,
        private val status: DefinitionStatus,
        private val scope: HostScope,
    ) {
        fun lines(): List<HtmlChunk> {
            val lines = ArrayList<HtmlChunk>()
            if (status.winsOn.isEmpty() && status.shadowedOn.isEmpty()) {
                status.notLoadedReason?.let { lines += HtmlChunk.text(it) }
                return lines
            }
            val narrowed = scope.selection != RootContext.DEFAULT || scope.overriddenSelection
            val allowed: Set<HostKey>? = if (narrowed) scope.hosts.toSet() else null
            childGroups(allowed)?.let { lines += it }
            // Follow editor: a file scope disjoint from the selection wins; the row says so and covers the file's hosts.
            if (scope.overriddenSelection) lines += HtmlChunk.text(message("card.effect.not.loaded.for", selectionLabel()))
            val wins = status.winsOn.filter { allowed == null || it in allowed }
            val shadowed = status.shadowedOn.filterKeys { allowed == null || it in allowed }
            if (wins.isEmpty() && shadowed.isEmpty()) {
                if (!scope.overriddenSelection) lines += HtmlChunk.text(message("card.effect.not.loaded.for", selectionLabel()))
                return lines
            }
            val byWinner = LinkedHashMap<Location, Pair<VarSourceRef, MutableList<HostKey>>>()
            for ((host, winner) in shadowed) {
                ProgressManager.checkCanceled()
                byWinner.getOrPut(Location.of(winner)) { winner to ArrayList() }.second += host
            }
            // One line per winner; a definition shadowed per host on a large inventory lists the first ones and counts the rest.
            val shown = byWinner.values.take(MAX_WINNERS)
            val more = byWinner.values.drop(MAX_WINNERS).takeIf { it.isNotEmpty() }?.let { rest ->
                grayed(message("card.effect.more", rest.size, message("card.effective.hosts", rest.sumOf { it.second.size })))
            }
            if (wins.isNotEmpty()) {
                val parts = mutableListOf<HtmlChunk>(HtmlChunk.text(message("card.effect.effective", HostCardTexts.hostList(wins))))
                for ((winner, hosts) in shown) parts += shadowedBy("card.effect.shadowed", hosts, winner)
                more?.let(parts::add)
                lines += HostCardTexts.joined(parts)
            } else if (byWinner.size == 1) {
                val (winner, hosts) = byWinner.values.single()
                lines += shadowedBy("card.effect.ineffective", hosts, winner)
            } else {
                lines += HtmlChunk.text(message("card.effect.ineffective.several"))
                for ((winner, hosts) in shown) lines += HtmlChunk.fragment(HtmlChunk.nbsp(2), shadowedBy("card.effect.shadowed.by", hosts, winner))
                more?.let { lines += HtmlChunk.fragment(HtmlChunk.nbsp(2), it) }
            }
            return lines
        }

        /** `✗ shadowed for prod-training1 by <link> (L9 beats L6)` in the wording of [key]. */
        private fun shadowedBy(key: String, hosts: List<HostKey>, winner: VarSourceRef): HtmlChunk {
            val label = HostCardTexts.label(project, root, winner)
            val levels = levels(winner)
            val text = message(key, HostCardTexts.hostList(hosts), MARKER, levels)
            val at = text.indexOf(MARKER)
            if (at < 0) return HtmlChunk.text(text)
            return HtmlChunk.fragment(
                HtmlChunk.text(text.substring(0, at)),
                HostCardTexts.definitionLink(winner, label),
                HtmlChunk.text(text.substring(at + MARKER.length)),
            )
        }

        /** `L5 beats L4`, or `L6` when both sit at the same level (group depth, priority or name decide then). */
        private fun levels(winner: VarSourceRef): String {
            val own = definition.layer?.level
            val other = winner.layer.level
            return if (own == null || own == other) message("card.effect.level", other) else message("card.effect.beats", other, own)
        }

        /**
         * `applies to prod-mlflow1, prod-training1 (via child group analytics)` when the definition's `group_vars` group
         * reaches hosts through its child groups (a group that lists no hosts of its own); the hosts listed are those, and
         * only the [allowed] ones when the selection narrows the row (null: every host).
         */
        private fun childGroups(allowed: Set<HostKey>?): HtmlChunk? {
            val group = definition.group ?: return null
            if (definition.kind != VarDefKind.GROUP_VARS) return null
            val impl = AnsibleContextServiceImpl.getInstance(project) ?: return null
            val origin = impl.allHostsScope(definition.location.file).origin as? HostScopeOrigin.GroupVars ?: return null
            val inventoryRoot = impl.inventoryRoot(root)
            val rootKey = RootKeys.keyOf(project, inventoryRoot.dir)
            val environments = InventoryModels.getInstance(project).environments(inventoryRoot)
                .filter { origin.environment == null || it.name == origin.environment }
            val hosts = LinkedHashSet<HostKey>()
            val via = LinkedHashSet<String>()
            for (environment in environments) {
                val graph = environment.graph
                val node = graph.group(group) ?: continue
                val direct = node.hosts.toSet()
                val indirect = graph.hostsOf(group).filter { it !in direct && (allowed == null || HostKey(rootKey, environment.name, it) in allowed) }
                if (indirect.isEmpty()) continue
                indirect.mapTo(hosts) { HostKey(rootKey, environment.name, it) }
                node.children.filter { child -> graph.hostsOf(child).any { it in indirect } }.forEach(via::add)
            }
            if (hosts.isEmpty() || via.isEmpty()) return null
            return HtmlChunk.text(message("card.effect.applies.children", HostCardTexts.hostList(hosts), via.joinToString(", ")))
        }

        /** `test › test-test1`, `prod`, with the selected play (`prod › prod-prod1 · play System`). */
        private fun selectionLabel(): String {
            val selection = scope.selection
            val base = ContextTexts.environmentAndHost(selection)
            val play = selection.play?.let { PlayKeys.resolve(project, scope.root, it) } ?: return base
            return base + HostCardTexts.SEPARATOR + message("card.effective.play", HostCardTexts.playName(play))
        }
    }

    private companion object {
        /** Stands in for the link inside a message, so translations may move it. */
        const val MARKER = "\u0000link\u0000"

        /** Winners listed before "+n more definitions win on m hosts". */
        const val MAX_WINNERS = 8
    }
}
