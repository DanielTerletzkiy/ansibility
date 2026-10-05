package de.terletzkiy.ansibility.toolwindow.model

import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.PlayRef
import de.terletzkiy.ansibility.api.PlayRoleEntry
import de.terletzkiy.ansibility.api.RoleEntryKind
import de.terletzkiy.ansibility.context.host.PlayMatch
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowTexts.joinCapped
import org.jetbrains.annotations.Nls

/**
 * Where a play runs (plan amendment R7/R8 F8.7, WU HA7b, part of ex-X43): its matched hosts per environment of the
 * root (the context model's play hits: ansible-core's host pattern rules, cached on the playbooks and `hosts.yml`
 * files), the environments it matches nothing in, and the roles it applies in execution order.
 *
 * Every function needs a read action (the tree's background invoker, the details pane's background read action).
 */
object PlayMatches {
    /** The matches of [play] in [root]'s environments, or null when the host context is not available. */
    fun of(project: Project, root: RootSnapshot, play: PlayRef): PlayMatch? =
        ToolWindowModels.getInstance(project).reads?.playMatch(root.root, play)

    /** The environment the Ansible context of [root] selects, or null for All (D33: presentation follows the selection). */
    fun selectedEnvironment(project: Project, root: RootSnapshot): String? =
        (AnsibleContextService.getInstance(project).selection(root.root).environment as? EnvironmentChoice.Named)?.name

    /** `2 hosts`, `no hosts` or `templated` after a play's `hosts:` pattern. */
    @Nls
    fun countText(match: PlayMatch): String =
        if (match.templated) message("play.matches.templated.short") else message("count.hosts", match.hostCount)

    /**
     * The children of [node]: one node per environment with matched hosts (the selected one in bold), one node naming
     * the environments without any, a note for a templated pattern, then the play's roles.
     */
    fun children(node: PlayNode, root: RootSnapshot, play: PlayRef, match: PlayMatch?): List<AnsibleTreeNode> = buildList {
        val project = node.project
        if (match != null && root.environments.isNotEmpty()) {
            if (match.templated) {
                add(PlayTemplatedNode(node))
            } else {
                val selected = project?.let { selectedEnvironment(it, root) }
                val empty = ArrayList<String>()
                for (env in root.environments) {
                    val hosts = match.hostsIn(env.name)
                    if (hosts.isEmpty()) empty += env.name else add(PlayEnvironmentNode(node, env, hosts, env.name == selected))
                }
                if (empty.isNotEmpty()) add(PlayNoHostsNode(node, empty))
            }
        }
        val roles = project?.let { PlayGraph.getInstance(it).rolesOfPlay(play) }.orEmpty()
        if (roles.isNotEmpty()) add(PlayRolesNode(node, root, roles))
    }

    /** The role entries of [play] as role nodes under [parent]. */
    fun roleNodes(parent: AnsibleTreeNode, root: RootSnapshot, roles: List<PlayRoleEntry>): List<AnsibleTreeNode> =
        roles.mapIndexed { index, entry -> PlayRoleNode(parent, root, entry, index) }

    /** `roles: system, system-access +5` for a play row, or null when it applies none. */
    @Nls
    fun rolesText(roles: List<PlayRoleEntry>): String? =
        roles.takeIf { it.isNotEmpty() }?.let { message("play.roles", joinCapped(it.map(PlayRoleEntry::name).distinct(), MAX_ROLES)) }

    private const val MAX_ROLES = 3
}

/** `prod  prod-prod1, prod-prod2`: the hosts a play matches in one environment; bold when it is the selected one. */
class PlayEnvironmentNode(
    parent: AnsibleTreeNode,
    val env: EnvironmentView,
    val hosts: List<String>,
    val selected: Boolean,
) : AnsibleTreeNode(parent, "match:${env.name}") {
    override fun presentation() = NodePresentation(
        env.name,
        joinCapped(hosts, MAX_HOSTS),
        listOfNotNull(message("play.matches.tooltip", hosts.size, env.name), if (selected) message("play.matches.selected") else null),
        NodeIcon.ENVIRONMENT,
        if (selected) NodeStyle.EMPHASIZED else NodeStyle.NORMAL,
    )

    override val target: NavigationTarget get() = NavigationTarget(env.hostsFile)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = hosts.map { MatchedHostNode(this, env, it) }

    private companion object {
        const val MAX_HOSTS = 6
    }
}

/** One matched host; it opens the host entry in the environment's `hosts.yml`. */
class MatchedHostNode(parent: AnsibleTreeNode, val env: EnvironmentView, val host: String) : AnsibleTreeNode(parent, "host:$host") {
    override fun presentation(): NodePresentation {
        val inventoryHost = env.host(host)
        val badge = inventoryHost?.let { HostBadge.of(project, env, it) }
        return NodePresentation(host, badge?.template, listOf(message("host.tooltip", host, env.name)), NodeIcon.HOST, badge = badge?.address)
    }

    override val target: NavigationTarget get() = NavigationTarget.of(env.host(host)?.location) ?: NavigationTarget(env.hostsFile)
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
}

/** `ops, test  no hosts`: the environments a play matches nothing in (a group that is not defined there is normal). */
class PlayNoHostsNode(parent: AnsibleTreeNode, val environments: List<String>) : AnsibleTreeNode(parent, "match:none") {
    override fun presentation() = NodePresentation(
        environments.joinToString(", "),
        message("details.note.no.hosts"),
        listOf(message("play.matches.none.tooltip")),
        NodeIcon.ENVIRONMENT,
    )

    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
}

/** A templated `hosts:` pattern: static evaluation cannot know the hosts. */
class PlayTemplatedNode(parent: AnsibleTreeNode) : AnsibleTreeNode(parent, "match:templated") {
    override fun presentation() = NodePresentation(message("play.matches.templated"), message("play.matches.templated.extra"), icon = NodeIcon.ENVIRONMENT)

    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
}

/** `Roles  7`: the roles a play applies, in execution order (dependencies expanded before their dependents). */
class PlayRolesNode(parent: AnsibleTreeNode, val root: RootSnapshot, val roles: List<PlayRoleEntry>) : AnsibleTreeNode(parent, "roles") {
    override fun presentation() = NodePresentation(message("play.roles.name"), roles.size.toString(), listOf(message("play.roles.tooltip")), NodeIcon.FOLDER)

    override fun children(context: TreeContext): List<AnsibleTreeNode> = PlayMatches.roleNodes(this, root, roles)
}

/**
 * One role a play applies: `postfix  roles:` / `include_role in tasks` / `dependency of system`. It opens the reference
 * (the role name in the playbook, the dependency in `meta/main.yml`), or the role directory when the reference has no
 * position.
 */
class PlayRoleNode(parent: AnsibleTreeNode, val root: RootSnapshot, val entry: PlayRoleEntry, index: Int) :
    AnsibleTreeNode(parent, "role:$index:${entry.name}") {
    override fun presentation(): NodePresentation {
        val kind = when (entry.kind) {
            RoleEntryKind.PLAY_ROLE -> message("role.kind.play")
            RoleEntryKind.INCLUDE_ROLE -> message("role.kind.include", entry.section.name.lowercase())
            RoleEntryKind.IMPORT_ROLE -> message("role.kind.import", entry.section.name.lowercase())
            RoleEntryKind.DEPENDENCY -> message("role.kind.dependency", entry.requiredBy.orEmpty())
        }
        val entryPoint = entry.entryPoint.takeIf { it != MAIN }?.let { message("role.entry.point", it) }
        val missing = if (entry.role == null) message("role.missing") else null
        return NodePresentation(
            entry.name,
            listOfNotNull(kind, entryPoint, missing).joinToString(" · "),
            listOfNotNull(entry.written.takeIf { it != entry.name }?.let { message("role.written", it) }, entry.role?.dir?.presentableUrl),
            NodeIcon.ROLE,
        )
    }

    override val target: NavigationTarget? get() = NavigationTarget.of(entry.location) ?: entry.role?.dir?.let { NavigationTarget(it) }
    override val navigatesOnDoubleClick: Boolean get() = true
    override val isLeaf: Boolean get() = true

    override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()

    private companion object {
        const val MAIN = "main"
    }
}
