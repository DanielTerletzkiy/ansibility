package de.terletzkiy.ansibility.toolwindow.host

import de.terletzkiy.ansibility.api.ToolWindowNodeContributor
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode

/**
 * Adds **Effective vars** and **Targeted by** under every host node of the Ansibility tool window (plan amendment R7/R8
 * F8.7, WU HA7a; `<toolWindowNodeContributor id="ansibilityHostEffective">` in the ha7 block of
 * `ansibility-inventory.xml`). R9's Environments tab reuses the host nodes, so it gets them too.
 *
 * Creating the two nodes computes nothing: their texts are computed when the host is expanded (the tree loads a host's
 * children only then), on the tree's background thread. Hosts of synthetic snapshots (no project) get none.
 */
class HostNodeContributor : ToolWindowNodeContributor {
    override fun children(parent: AnsibleTreeNode): List<AnsibleTreeNode> =
        if (parent is HostNode && parent.project != null) listOf(EffectiveVarsNode(parent), TargetedByNode(parent)) else emptyList()
}
