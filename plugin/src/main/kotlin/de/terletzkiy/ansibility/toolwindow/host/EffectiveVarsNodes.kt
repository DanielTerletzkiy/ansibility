package de.terletzkiy.ansibility.toolwindow.host

import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.context.host.PlayKeys
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.DetailItem
import de.terletzkiy.ansibility.toolwindow.model.DetailsBuilder
import de.terletzkiy.ansibility.toolwindow.model.DetailsContent
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.LayerTexts
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeDetails
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.NodePresentation
import de.terletzkiy.ansibility.toolwindow.model.NodeStyle
import de.terletzkiy.ansibility.toolwindow.model.SourceLabels
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowTexts
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import org.jetbrains.annotations.Nls

/** One entry of the play selector: a [choice] and how it reads. */
data class PlayChoiceItem(val choice: PlayChoice, @Nls val text: String) {
    override fun toString(): String = text
}

/**
 * The details-pane content of a host's Effective vars (plan amendment R7/R8 F8.7): the [table] and the play selector's
 * [choices], [selected] being the one in effect.
 *
 * Two contents are equal when they show the same computed rows (one cache entry of [EffectiveTables]) with the same
 * selector, so the details pane keeps the table it shows (expansion, selection, scroll position) when the tree only
 * re-renders.
 */
class EffectiveVarsContent(val table: EffectiveTable, val choices: List<PlayChoiceItem>, val selected: PlayChoiceItem) : DetailsContent {
    override fun equals(other: Any?): Boolean =
        other is EffectiveVarsContent && other.table.rows === table.rows && other.table.markersKnown == table.markersKnown &&
            other.table.context.host == table.context.host && other.choices == choices && other.selected == selected

    override fun hashCode(): Int = System.identityHashCode(table.rows) * 31 + selected.hashCode()
}

/** Wording of an [EffectiveContext]. */
object EffectiveTexts {
    /** `inventory view`, `all 27 plays`, `its only play System`, `play System`, `play System (Ansible context)`. */
    @Nls
    fun contextText(context: EffectiveContext): String {
        if (context.allPlays) return allPlaysText(context.evaluated)
        val play = context.play ?: return message("effective.context.inventory")
        return if (context.fromContext) message("effective.context.play.selected", playName(play)) else message("effective.context.play", playName(play))
    }

    /** What Auto evaluates for [context]'s host, whatever is chosen now: the context's play, else every play on the host. */
    @Nls
    fun autoText(context: EffectiveContext): String {
        if (context.choice == PlayChoice.Auto || context.problem != null) return contextText(context)
        context.autoPlay?.let { return message("effective.context.play.selected", playName(it)) }
        return if (context.plays.isEmpty()) message("effective.context.inventory") else allPlaysText(context.plays.map { it.play })
    }

    /** `all 27 plays`, or `its only play System` for one. */
    @Nls
    private fun allPlaysText(plays: List<PlayRef>): String =
        plays.singleOrNull()?.let { message("effective.context.only", playName(it)) } ?: message("effective.context.all", plays.size)

    /** A play as the selector lists it: `playbook-setup-system.yml › System`. */
    @Nls
    fun playLabel(context: EffectiveContext, play: PlayRef): String =
        message("details.play.label", PlayKeys.of(context.root, play).substringBeforeLast('#'), playName(play))

    @Nls
    fun playName(play: PlayRef): String = play.name?.takeIf { it.isNotBlank() } ?: message("play.unnamed", play.playIndex + 1)

    /** `System, Debug +3`: the names of [plays], capped. */
    @Nls
    fun playNames(plays: List<PlayRef>): String = ToolWindowTexts.joinCapped(plays.map(::playName), MAX_PLAY_NAMES)

    /**
     * Over several plays: `set in 3 of 5 plays` when some evaluated plays do not define [row]'s variable, and
     * `another value in 2 plays` (`2 other values in 3 plays`) when some let other definitions win. Null for one play
     * and for the inventory view.
     */
    @Nls
    fun spreadText(context: EffectiveContext, row: EffectiveRow): String? {
        if (!context.allPlays || context.evaluated.size < 2) return null
        val parts = ArrayList<String>(2)
        if (row.definedIn < context.evaluated.size) parts += message("effective.plays.defined", row.definedIn, context.evaluated.size)
        if (row.others.isNotEmpty()) parts += message("effective.plays.others", row.others.size, row.others.sumOf { it.plays.size })
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The play selector's entries for [context]: Auto (saying what it resolves to), the inventory view, then each play on the host. */
    fun choices(context: EffectiveContext): List<PlayChoiceItem> = buildList {
        add(PlayChoiceItem(PlayChoice.Auto, message("effective.choice.auto", autoText(context))))
        add(PlayChoiceItem(PlayChoice.Inventory, message("effective.choice.inventory")))
        for (match in context.plays) add(PlayChoiceItem(PlayChoice.Play(PlayKeys.of(context.root, match.play)), playLabel(context, match.play)))
    }

    /** The entry of [choices] for the choice in effect ([PlayChoice.Auto] for a stale play). */
    fun selected(context: EffectiveContext, choices: List<PlayChoiceItem>): PlayChoiceItem {
        val choice = if (context.problem != null) PlayChoice.Auto else context.choice
        return choices.firstOrNull { it.choice == choice } ?: choices.first()
    }

    private const val MAX_PLAY_NAMES = 3
}

/**
 * `Effective vars  412 · all 27 plays` under a host (plan amendment R7/R8 F8.7, WU HA7a, ex-X39): every variable of the
 * host with its winning definition, in the evaluation the play selector chose. Children are the variables; selecting the
 * node shows the table with the play selector in the details pane. Computed when the host is expanded, never before.
 */
class EffectiveVarsNode(parent: HostNode) : AnsibleTreeNode(parent, "effective") {
    val host: HostNode = parent

    /** The table in effect, or null without the host context. Needs a read action. */
    fun table(): EffectiveTable? = project?.let { EffectiveTables.getInstance(it).tableOf(host.env.root.root, host.env.name, host.host.name) }

    /** A few hundred variables per host: Expand All leaves them collapsed. */
    override val expandsWithAll: Boolean get() = false

    override fun presentation(): NodePresentation {
        val table = table() ?: return NodePresentation(message("effective.name"), message("effective.unavailable"), icon = NodeIcon.FOLDER)
        val context = table.context
        val extra = listOfNotNull(table.rows.size.toString(), EffectiveTexts.contextText(context), context.problem).joinToString(" · ")
        val dirs = context.playbookDirs.joinToString(", ") { SourceLabels.playbookDir(host.env.root, it) }
        val tooltip = listOfNotNull(
            message("effective.tooltip", host.host.name, host.env.name),
            dirs.takeIf { it.isNotEmpty() }?.let { message("effective.tooltip.playbook.dir", it) },
            if (context.allPlays) message("effective.tooltip.all.plays") else null,
            if (context.evaluated.isNotEmpty() && !table.markersKnown) message("effective.markers.unknown") else null,
        )
        return NodePresentation(message("effective.name"), extra, tooltip, NodeIcon.FOLDER)
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        val table = table() ?: return emptyList()
        return table.rows.map { EffectiveVarNode(this, table.context, it) }
    }

    override fun details(): NodeDetails? {
        val table = table() ?: return null
        val context = table.context
        val details = DetailsBuilder(
            message("effective.details.title", host.host.name),
            message("effective.details.subtitle", host.env.root.root.displayName, host.env.name, EffectiveTexts.contextText(context)),
        )
        details.section(message("effective.details.section.problems"), listOfNotNull(context.problem?.let { DetailItem(it) }))
        val choices = EffectiveTexts.choices(context)
        return details.build(EffectiveVarsContent(table, choices, EffectiveTexts.selected(context, choices)))
    }
}

/**
 * One variable: `postfix_relayhost = relayinternal.mx.example.de  L5 playbook group_vars/all ·
 * group_vars/all/vars.yml:156 · 1 shadowed`. Vault values are masked. Double-click opens the winning definition;
 * children are the definitions that win in other plays (Auto over every play), the definitions it shadows (struck
 * through) and the tasks that may replace it at runtime.
 */
class EffectiveVarNode(parent: AnsibleTreeNode, val context: EffectiveContext, val row: EffectiveRow) : AnsibleTreeNode(parent, "var:${row.name}") {
    override fun presentation(): NodePresentation {
        val winner = row.winner
        val extra = listOfNotNull(
            winner.layerText,
            winner.source,
            EffectiveTexts.spreadText(context, row),
            row.shadowed.size.takeIf { it > 0 }?.let { message("effective.shadowed.count", it) },
            if (row.markers.isNotEmpty()) message("effective.runtime.flag") else null,
        ).joinToString(" · ")
        val tooltip = listOfNotNull(
            message("effective.var.tooltip.type", message("effective.type.${winner.kind.name}")),
            LayerTexts.tooltip(winner.layer, winner.owner),
            winner.location.file.presentableUrl,
            EffectiveTexts.spreadText(context, row)?.let { message("effective.var.tooltip.plays", EffectiveTexts.playNames(row.plays)) },
        )
        return NodePresentation(message("effective.var.name", row.name, winner.value), extra, tooltip, NodeIcon.VARIABLE)
    }

    /** The winning definition. */
    override val target: NavigationTarget get() = NavigationTarget(row.winner.location.file, row.winner.location.offset)
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = row.others.isEmpty() && row.shadowed.isEmpty() && row.markers.isEmpty()

    override fun children(context: TreeContext): List<AnsibleTreeNode> =
        row.others.mapIndexed { index, outcome -> PlayOutcomeNode(this, this.context, outcome, index) } +
            row.shadowed.mapIndexed { index, cell -> ShadowedDefinitionNode(this, cell, index) } +
            row.markers.mapIndexed { index, marker -> RuntimeMarkerNode(this, marker, index) }

    override fun details(): NodeDetails {
        val details = DetailsBuilder(row.name, message("effective.details.subtitle", context.root.displayName, context.host.environment, EffectiveTexts.contextText(context)))
        // Over several plays, the winner's plays are named only when it does not win in all of them.
        val inPlays = if (EffectiveTexts.spreadText(context, row) != null) message("effective.outcome.plays", row.plays.size, EffectiveTexts.playNames(row.plays)) else null
        details.section(message("effective.var.section.winner"), cellItem(row.winner, inPlays))
        details.section(
            message("effective.var.section.others"),
            row.others.map { cellItem(it.winner, message("effective.outcome.plays", it.plays.size, EffectiveTexts.playNames(it.plays))) },
        )
        details.section(message("effective.var.section.shadowed"), row.shadowed.map { cellItem(it, null) })
        details.section(
            message("effective.var.section.runtime"),
            row.markers.map { DetailItem(message("effective.marker.${it.kind.name}"), it.source, NavigationTarget(it.location.file, it.location.offset)) },
        )
        return details.build()
    }

    private fun cellItem(cell: DefinitionCell, plays: String?): DetailItem {
        val note = listOfNotNull(message("effective.type.${cell.kind.name}"), cell.layerText, cell.source, plays, if (cell.merged) message("effective.merged") else null)
        return DetailItem(cell.value, note.joinToString(" · "), NavigationTarget(cell.location.file, cell.location.offset))
    }
}

/**
 * Auto over every play: a definition that wins in other plays than the row's winner,
 * `"" L2 role defaults of postfix · roles/postfix/defaults/main.yml:2 · in 1 play: System`.
 */
class PlayOutcomeNode(parent: AnsibleTreeNode, val context: EffectiveContext, val outcome: PlayOutcome, index: Int) :
    AnsibleTreeNode(parent, "outcome:$index:${outcome.winner.location.file.path}:${outcome.winner.location.offset}") {
    override fun presentation(): NodePresentation {
        val cell = outcome.winner
        val extra = listOf(cell.layerText, cell.source, message("effective.outcome.plays", outcome.plays.size, EffectiveTexts.playNames(outcome.plays))).joinToString(" · ")
        // Play names repeat across playbooks ("Debug"); the tooltip names each play with its playbook.
        val plays = ToolWindowTexts.joinCapped(outcome.plays.map { EffectiveTexts.playLabel(context, it) }, MAX_TOOLTIP_PLAYS)
        val tooltip = listOf(
            message("effective.outcome.tooltip"),
            message("effective.var.tooltip.plays", plays),
            message("effective.var.tooltip.type", message("effective.type.${cell.kind.name}")),
            cell.location.file.presentableUrl,
        )
        return NodePresentation(cell.value, extra, tooltip, NodeIcon.VARIABLE)
    }

    override val target: NavigationTarget get() = NavigationTarget(outcome.winner.location.file, outcome.winner.location.offset)
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()

    private companion object {
        const val MAX_TOOLTIP_PLAYS = 8
    }
}

/** A definition the winner shadows, struck through: `relay.mx.example.de  L4 env group_vars/all · …:471`. */
class ShadowedDefinitionNode(parent: AnsibleTreeNode, val cell: DefinitionCell, index: Int) :
    AnsibleTreeNode(parent, "shadowed:$index:${cell.location.file.path}:${cell.location.offset}") {
    override fun presentation(): NodePresentation {
        val extra = listOfNotNull(cell.layerText, cell.source, if (cell.merged) message("effective.merged") else null).joinToString(" · ")
        val tooltip = listOf(
            if (cell.merged) message("effective.merged.tooltip") else message("effective.shadowed.tooltip"),
            message("effective.var.tooltip.type", message("effective.type.${cell.kind.name}")),
            cell.location.file.presentableUrl,
        )
        return NodePresentation(cell.value, extra, tooltip, NodeIcon.SHADOWED, if (cell.merged) NodeStyle.NORMAL else NodeStyle.STRUCK)
    }

    override val target: NavigationTarget get() = NavigationTarget(cell.location.file, cell.location.offset)
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
}

/** `may be replaced at runtime by set_fact  roles/x/tasks/main.yml:12`: the value is only known when the play runs. */
class RuntimeMarkerNode(parent: AnsibleTreeNode, val marker: MarkerCell, index: Int) : AnsibleTreeNode(parent, "runtime:$index:${marker.kind.name}") {
    override fun presentation() = NodePresentation(
        message("effective.marker.${marker.kind.name}"),
        marker.source,
        listOf(message("effective.marker.tooltip"), marker.location.file.presentableUrl),
        NodeIcon.RUNTIME,
    )

    override val target: NavigationTarget get() = NavigationTarget(marker.location.file, marker.location.offset)
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
}
