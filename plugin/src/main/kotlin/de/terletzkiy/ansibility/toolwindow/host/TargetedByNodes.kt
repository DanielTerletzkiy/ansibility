package de.terletzkiy.ansibility.toolwindow.host

import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayInfo
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.PlayRoleEntry
import de.terletzkiy.ansibility.context.host.PlayMatch
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.DetailItem
import de.terletzkiy.ansibility.toolwindow.model.DetailsBuilder
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeDetails
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.NodePresentation
import de.terletzkiy.ansibility.toolwindow.model.PlayMatches
import de.terletzkiy.ansibility.toolwindow.model.RootSnapshot
import de.terletzkiy.ansibility.toolwindow.model.SourceLabels
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowModels
import de.terletzkiy.ansibility.toolwindow.model.TreeContext

/**
 * `Targeted by  27 plays · 6 playbooks` under a host (plan amendment R7/R8 F8.7, WU HA7a, part 1 of ex-X43): the plays
 * whose `hosts:` pattern matches the host in its environment, grouped by playbook in play-graph order (playbooks by
 * path, plays in file order) with their playbook dir, each with the roles it applies in execution order. Plays of the
 * nested playbook roots inside the root count too (their playbook dir differs). Plays whose pattern is a template are
 * listed apart: they may run here, which static evaluation cannot know.
 */
class TargetedByNode(parent: HostNode) : AnsibleTreeNode(parent, "targeted-by") {
    val host: HostNode = parent

    private class Plays(val matching: List<PlayMatch>, val templated: List<PlayMatch>)

    /** Every play on the host with its roles: Expand All leaves them collapsed. */
    override val expandsWithAll: Boolean get() = false

    /** The plays on the host and the templated ones, or null without the host context. Needs a read action. */
    private fun plays(): Plays? {
        val project = project ?: return null
        val reads = ToolWindowModels.getInstance(project).reads ?: return null
        val root = host.env.root.root
        val all = reads.playMatches(root)
        val key = reads.hostKey(root, host.env.name, host.host.name)
        return Plays(all.filter { it.runsOn(key.environment, key.host) }, all.filter { it.templated })
    }

    override fun presentation(): NodePresentation {
        val plays = plays() ?: return NodePresentation(message("targeted.name"), message("effective.unavailable"), icon = NodeIcon.FOLDER)
        val playbooks = plays.matching.map { it.play.file }.distinct().size
        val extra = if (plays.matching.isEmpty()) message("targeted.none") else message("targeted.extra", plays.matching.size, playbooks)
        return NodePresentation(message("targeted.name"), extra, listOf(message("targeted.tooltip", host.host.name, host.env.name)), NodeIcon.FOLDER)
    }

    override fun children(context: TreeContext): List<AnsibleTreeNode> {
        val plays = plays() ?: return emptyList()
        val project = project ?: return emptyList()
        val graph = PlayGraph.getInstance(project)
        val byPlaybook = plays.matching.groupBy { it.play.file }
        val nodes = ArrayList<AnsibleTreeNode>()
        for ((file, matches) in byPlaybook) {
            nodes += TargetPlaybookNode(this, host.env.root, file, matches.map { TargetPlay(it.play, graph.play(it.play), graph.rolesOfPlay(it.play)) })
        }
        if (plays.templated.isNotEmpty()) {
            nodes += TemplatedPlaysNode(this, host.env.root, plays.templated.map { TargetPlay(it.play, graph.play(it.play), graph.rolesOfPlay(it.play)) })
        }
        return nodes
    }

    override fun details(): NodeDetails? {
        val project = project ?: return null
        val plays = plays() ?: return null
        val details = DetailsBuilder(message("targeted.details.title", host.host.name), message("details.subtitle.environment", host.env.root.root.displayName, host.env.name))
        val graph = PlayGraph.getInstance(project)
        for ((file, matches) in plays.matching.groupBy { it.play.file }) {
            details.section(
                message("targeted.details.section", host.env.root.relativePath(file)),
                matches.map { match ->
                    val info = graph.play(match.play)
                    DetailItem(
                        EffectiveTargets.playName(match.play),
                        listOfNotNull(match.play.hostsPattern?.let { message("play.extra", it) }, PlayMatches.rolesText(graph.rolesOfPlay(match.play))).joinToString(" · "),
                        info?.let { NavigationTarget(it.location.file, it.location.offset) },
                    )
                },
            )
        }
        return details.build()
    }
}

/** A play with what the tree shows of it, read once in the background. */
class TargetPlay(val ref: PlayRef, val info: PlayInfo?, val roles: List<PlayRoleEntry>)

/** Small wording helpers of the Targeted by nodes. */
internal object EffectiveTargets {
    fun playName(play: PlayRef): String = EffectiveTexts.playName(play)

    /** `playbook dir ansible` / `playbook dir ansible/danger_zone/database`. */
    fun playbookDirText(root: RootSnapshot, dir: VirtualFile): String = message("targeted.playbook.dir", SourceLabels.playbookDir(root, dir))
}

/** `playbook-setup-system.yml  5 plays · playbook dir ansible`: the plays of one playbook that run on the host. */
class TargetPlaybookNode(parent: AnsibleTreeNode, val root: RootSnapshot, val file: VirtualFile, val plays: List<TargetPlay>) :
    AnsibleTreeNode(parent, "playbook:${file.path}") {
    override fun presentation(): NodePresentation {
        val dir = plays.firstOrNull()?.ref?.playbookDir ?: file.parent
        val extra = listOfNotNull(message("targeted.plays", plays.size), dir?.let { EffectiveTargets.playbookDirText(root, it) }).joinToString(" · ")
        return NodePresentation(root.relativePath(file), extra, listOf(file.presentableUrl), NodeIcon.PLAYBOOK)
    }

    override val target: NavigationTarget get() = NavigationTarget(file)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = plays.map { TargetPlayNode(this, root, it) }
}

/** `System  hosts: system · roles: system, system-access, system-apt +4`; children are its roles in execution order. */
class TargetPlayNode(parent: AnsibleTreeNode, val root: RootSnapshot, val play: TargetPlay) : AnsibleTreeNode(parent, "play:${play.ref.file.path}#${play.ref.playIndex}") {
    override fun presentation(): NodePresentation {
        val extra = listOfNotNull(play.ref.hostsPattern?.let { message("play.extra", it) }, PlayMatches.rolesText(play.roles)).joinToString(" · ")
        return NodePresentation(EffectiveTargets.playName(play.ref), extra.ifEmpty { null }, listOf(play.ref.file.presentableUrl), NodeIcon.PLAY)
    }

    override val target: NavigationTarget? get() = play.info?.let { NavigationTarget(it.location.file, it.location.offset) }
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = play.roles.isEmpty()

    override fun children(context: TreeContext): List<AnsibleTreeNode> = PlayMatches.roleNodes(this, root, play.roles)
}

/** `Templated hosts: patterns  2 plays may also run here`: plays whose hosts static evaluation cannot know. */
class TemplatedPlaysNode(parent: AnsibleTreeNode, val root: RootSnapshot, val plays: List<TargetPlay>) : AnsibleTreeNode(parent, "templated") {
    override fun presentation() = NodePresentation(
        message("targeted.templated.name"),
        message("targeted.templated.extra", plays.size),
        listOf(message("targeted.templated.tooltip")),
        NodeIcon.FOLDER,
    )

    override fun children(context: TreeContext): List<AnsibleTreeNode> = plays.map { TargetPlayNode(this, root, it) }
}
