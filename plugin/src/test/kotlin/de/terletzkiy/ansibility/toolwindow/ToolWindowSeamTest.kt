package de.terletzkiy.ansibility.toolwindow

import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import de.terletzkiy.ansibility.api.ToolWindowNodeContributor
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.toolwindow.host.HostNodeContributor
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.EnvironmentNode
import de.terletzkiy.ansibility.toolwindow.model.GroupNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.NodePresentation
import de.terletzkiy.ansibility.toolwindow.model.RootNode
import de.terletzkiy.ansibility.toolwindow.model.TreeContext

/**
 * The CT0 `toolWindowNodeContributor` seam as the tree wires it (HA7a): root, environment, group and host nodes append
 * the contributed children after their built-in ones, a contributor returning a node of another parent is rejected,
 * and a contributed node with a key that is already taken is dropped.
 */
class ToolWindowSeamTest : ToolWindowTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$FALCON", FALCON)
        refreshRoots()
    }

    /** A contributed node: `<kind> extra` under its parent. */
    private class Extra(parent: AnsibleTreeNode, segment: String) : AnsibleTreeNode(parent, segment) {
        override fun presentation() = NodePresentation("extra:${key.substringAfterLast('/')}", icon = NodeIcon.FOLDER)

        override val isLeaf: Boolean get() = true

        override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
    }

    /** Contributes one node to every root, environment, group and host node, named after the parent's kind. */
    private val everywhere = ToolWindowNodeContributor { parent ->
        val kind = when (parent) {
            is RootNode -> "root"
            is EnvironmentNode -> "env"
            is GroupNode -> "group"
            is HostNode -> "host"
            else -> return@ToolWindowNodeContributor emptyList()
        }
        listOf(Extra(parent, "test:$kind"))
    }

    fun testContributedChildrenFollowTheBuiltInOnes() {
        ExtensionTestUtil.maskExtensions(ToolWindowNodeContributor.EP_NAME, listOf(everywhere), testRootDisposable)
        assertEquals(listOf("Shared (playbook-level) vars", "Environments", "Playbooks", "Roles (9)", "extra:test:root"), names(path("falcon")))
        assertEquals(listOf("Groups", "Hosts", "extra:test:env"), names(path("falcon", "Environments", "prod")))
        assertEquals("extra:test:group", names(path("falcon", "Environments", "prod", "Groups", "keycloak")).last())
        assertEquals("extra:test:group", names(path("falcon", "Environments", "prod", "Groups", "all")).last())
        val host = path("falcon", "Environments", "prod", "Hosts", "prod-prod1")
        assertEquals("extra:test:host", names(host).last())
        assertEquals("the built-in sources stay first", 11, names(host).indexOf("extra:test:host"))
        assertEquals("other nodes get nothing", listOf("Groups", "Hosts", "extra:test:env"), names(path("falcon", "Environments", "prod")))
        assertTrue(names(path("falcon", "Environments")).none { it.startsWith("extra:") })
        assertTrue(names(path("falcon", "Playbooks")).none { it.startsWith("extra:") })
    }

    fun testOurOwnContributorAddsEffectiveVarsAndTargetedByToHostsOnly() {
        ExtensionTestUtil.maskExtensions(ToolWindowNodeContributor.EP_NAME, listOf(HostNodeContributor(), everywhere), testRootDisposable)
        val host = path("falcon", "Environments", "prod", "Hosts", "prod-prod1")
        assertEquals("in EP order", listOf("Effective vars", "Targeted by", "extra:test:host"), names(host).takeLast(3))
        assertEquals(listOf("Groups", "Hosts", "extra:test:env"), names(path("falcon", "Environments", "prod")))
        assertEquals("a host under a group gets them too", listOf("Effective vars", "Targeted by", "extra:test:host"), names(path("falcon", "Environments", "prod", "Groups", "keycloak", "prod-prod1")).takeLast(3))
    }

    fun testAContributorReturningAForeignNodeIsRejected() {
        val prod2 = path("falcon", "Environments", "prod", "Hosts", "prod-prod2")
        val foreign = ToolWindowNodeContributor { parent -> if (parent is HostNode) listOf(Extra(parent, "test:ok"), Extra(prod2, "test:foreign")) else emptyList() }
        ExtensionTestUtil.maskExtensions(ToolWindowNodeContributor.EP_NAME, listOf(foreign, everywhere), testRootDisposable)
        val host = path("falcon", "Environments", "prod", "Hosts", "prod-prod1")
        var result: List<AnsibleTreeNode> = emptyList()
        val error = LoggedErrorProcessor.executeAndReturnLoggedError { result = children(host) }
        assertNotNull("the misbehaving contributor is reported", error)
        assertTrue(generateSequence(error) { it.cause }.mapNotNull { it.message }.any { "prod-prod2/test:foreign" in it })
        val names = result.map { it.presentation().name }
        assertFalse("its nodes are all dropped, even the good one", names.any { it == "extra:test:ok" || it == "extra:test:foreign" })
        assertEquals("the built-ins and the other contributors stay", "extra:test:host", names.last())
        assertTrue(result.all { it.parent == host })
    }

    fun testAContributedNodeWithATakenKeyIsDropped() {
        val twice = ToolWindowNodeContributor { parent -> if (parent is EnvironmentNode) listOf(Extra(parent, "test:same"), Extra(parent, "test:same")) else emptyList() }
        ExtensionTestUtil.maskExtensions(ToolWindowNodeContributor.EP_NAME, listOf(twice), testRootDisposable)
        assertEquals(listOf("Groups", "Hosts", "extra:test:same"), names(path("falcon", "Environments", "prod")))
    }
}
