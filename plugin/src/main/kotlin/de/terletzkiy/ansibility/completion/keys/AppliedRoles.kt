package de.terletzkiy.ansibility.completion.keys

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.Inventory
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.semantics.inventory.HostPattern
import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph

/**
 * The roles whose variables a vars-like mapping is most likely meant for, which rank first in key completion
 * (plan F4.2):
 *
 * - `group_vars`/`host_vars` files and `hosts.yml` group vars or host entries: the roles of every play of the root
 *   whose `hosts:` pattern ([HostPattern], ansible-core's rules) selects one of the group's hosts (or the host) in the
 *   file's environment, or in every environment for playbook-level `group_vars`/`host_vars`;
 * - task-file and playbook sections: the roles the section is written for (see [VarContainer.roles]);
 * - otherwise, in role files (`defaults/`, `vars/`, tasks, molecule): the role itself.
 *
 * Only roles of the same root count. The play-based part is cached per group or host ([KeyCatalogs.rolesForHosts])
 * until a playbook, a role's `meta/main.yml` or a `hosts.yml` of the root changes. Call in a read action.
 */
internal object AppliedRoles {
    /** Where `meta/main.yml` dependencies are declared (they expand into the plays that apply a role). */
    private val META_MAIN = listOf("meta/main.yml", "meta/main.yaml")

    fun of(request: VarsCompletionRequest, container: VarContainer): Set<String> {
        val context = request.context
        val result = LinkedHashSet<String>()
        // A section written for particular roles (role parameters, include_role vars) is not about the file's own role.
        if (container.roles.isNotEmpty()) result += container.roles else context.roleName?.let(result::add)
        val hostsOwner = when (context.kind) {
            FileKind.GROUP_VARS, FileKind.HOST_VARS -> Owner(context.environment, context.group, context.host)
            FileKind.INVENTORY -> Owner(context.environment, container.group, container.host).takeIf { container.group != null || container.host != null }
            else -> null
        }
        if (hostsOwner != null) result += forHosts(request.project, request.root, hostsOwner)
        return result
    }

    /** A group or host of one environment (null: every environment of the root). */
    private data class Owner(val environment: String?, val group: String?, val host: String?)

    private fun forHosts(project: Project, root: AnsibleRoot, owner: Owner): Set<String> =
        KeyCatalogs.getInstance(project).rolesForHosts(KeyCatalogs.HostsKey(root, owner.environment, owner.group, owner.host)) {
            computeForHosts(project, root, owner)
        }

    private fun computeForHosts(project: Project, root: AnsibleRoot, owner: Owner): KeyCatalogs.Tracked<Set<String>> {
        val inventories = InventoryService.getInstance(project).inventories(root)
        val files = ArrayList<VirtualFile>()
        inventories.mapTo(files) { it.hostsFile }
        for (role in RoleRegistry.getInstance(project).roles(root)) {
            META_MAIN.firstNotNullOfOrNull { role.dir.findFileByRelativePath(it) }?.let(files::add)
        }
        val targets = inventories
            .filter { owner.environment == null || it.environment == owner.environment }
            .map { inventory -> graphOf(inventory).let { graph -> graph to hostsOf(graph, owner) } }
            .filter { it.second.isNotEmpty() }
        val result = LinkedHashSet<String>()
        if (targets.isEmpty()) return KeyCatalogs.Tracked(result, files)
        val workspace = AnsibleWorkspace.getInstance(project)
        val plays = PlayGraph.getInstance(project)
        for (playbook in plays.playbooks(root)) {
            ProgressManager.checkCanceled()
            // Molecule playbooks run against their scenario's inventory, never against the environments.
            if (workspace.contextOf(playbook)?.kind != FileKind.PLAYBOOK) continue
            files += playbook
            for (play in plays.playsOf(playbook)) {
                val pattern = play.ref.hostsPattern ?: continue
                if (JinjaBearing.hasTemplateMarkers(pattern)) continue
                val applies = targets.any { (graph, hosts) -> HostPattern.resolve(graph, pattern).hosts.any(hosts::contains) }
                if (!applies) continue
                play.roles.mapNotNullTo(result) { entry -> entry.role?.takeIf { it.rootDir == root.dir }?.name }
            }
        }
        return KeyCatalogs.Tracked(result, files)
    }

    private fun hostsOf(graph: InventoryGraph, owner: Owner): Set<String> = when {
        owner.host != null -> setOfNotNull(owner.host.takeIf { it in graph.hosts })
        owner.group != null -> graph.hostsOf(owner.group).toSet()
        else -> emptySet()
    }

    /** The group/host graph of [inventory], enough for host patterns (inline variables are not needed). */
    private fun graphOf(inventory: Inventory): InventoryGraph = InventoryGraph(
        groups = inventory.groups.mapValues { (_, group) ->
            InventoryGraph.Group(
                name = group.name, parents = group.parents, children = group.children, hosts = group.hosts,
                varSections = emptyList(), depth = group.depth, priority = group.priority, definitions = emptyList(),
            )
        },
        hosts = inventory.hosts.mapValues { (_, host) -> InventoryGraph.Host(host.name, host.groups, emptyList(), emptyList()) },
        problems = emptyList(),
    )
}
