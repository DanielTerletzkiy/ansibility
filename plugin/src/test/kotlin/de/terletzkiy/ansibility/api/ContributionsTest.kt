package de.terletzkiy.ansibility.api

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.NodePresentation
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import java.util.concurrent.atomic.AtomicInteger

/** The collectors of the presentation extension points: EP order, null results, misbehaving contributors. */
class ContributionsTest : BasePlatformTestCase() {

    private val file: VirtualFile = LightVirtualFile("vars.yml")

    private fun root() = AnsibleRoot(file, RootKind.PROJECT, detached = false, parentDir = null, rolesDirs = emptyList(), environmentsDir = null, displayName = "falcon")

    private class Section(override val placement: CardPlacement, private val text: String?) : CardSection {
        val subjects = ArrayList<CardSubject>()

        override fun section(subject: CardSubject, context: CardContext): HtmlChunk? {
            subjects += subject
            return text?.let(HtmlChunk::text)
        }
    }

    fun testCardSectionsAreCollectedPerPlacementInOrder() {
        val top = Section(CardPlacement.TOP, "effective")
        val silent = Section(CardPlacement.SECTION, null)
        val definition = Section(CardPlacement.SECTION, "definition")
        val vault = Section(CardPlacement.SECTION, "vault")
        val bottom = Section(CardPlacement.BOTTOM, "bottom")
        ExtensionTestUtil.maskExtensions(CardSection.EP_NAME, listOf(top, silent, definition, vault, bottom), testRootDisposable)

        val subject = CardSubject.Variable(root(), "postfix_relayhost", emptyList(), definition = null)
        val context = CardContext(project, file, 12)
        assertEquals(listOf("effective"), CardSection.collect(subject, context, CardPlacement.TOP).map { it.toString() })
        assertEquals(listOf("definition", "vault"), CardSection.collect(subject, context, CardPlacement.SECTION).map { it.toString() })
        assertEquals(listOf("bottom"), CardSection.collect(subject, context, CardPlacement.BOTTOM).map { it.toString() })
        assertEquals("each section is asked for its own placement only", listOf(subject), definition.subjects)
        assertEquals(listOf(subject), silent.subjects)
    }

    fun testCardSectionDefaultsToTheSectionsTable() {
        val section = object : CardSection {
            override fun section(subject: CardSubject, context: CardContext): HtmlChunk? = null
        }
        assertEquals(CardPlacement.SECTION, section.placement)
    }

    fun testCardRowRendersLikeTheBuiltInRows() {
        val html = CardSection.row("Vault <id>", HtmlChunk.text("🔒 locked")).toString()
        assertTrue(html, html.startsWith("<tr><td"))
        assertTrue(html, html.contains("class=\"section\""))
        assertTrue("the title is escaped: $html", html.contains("<p>Vault &lt;id&gt;</p>"))
        assertTrue(html, html.endsWith("🔒 locked</td></tr>"))
    }

    fun testWidgetSegmentsKeepTheirOrderAndSkipNulls() {
        val action = object : AnAction("Unlock…") {
            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        val calls = AtomicInteger()
        val segments = listOf(
            ContextWidgetSegment { _, _ -> calls.incrementAndGet(); WidgetSegment("file: keepalived → 2 hosts") },
            ContextWidgetSegment { _, _ -> calls.incrementAndGet(); null },
            ContextWidgetSegment { _, f -> calls.incrementAndGet(); WidgetSegment("🔓 default", "id default (${f.name})", listOf(action)) },
        )
        ExtensionTestUtil.maskExtensions(ContextWidgetSegment.EP_NAME, segments, testRootDisposable)

        val result = ContextWidgetSegment.segments(project, file)
        assertEquals(listOf("file: keepalived → 2 hosts", "🔓 default"), result.map { it.text })
        assertEquals("id default (vars.yml)", result[1].tooltip)
        assertEquals(listOf(action), result[1].popupActions)
        assertNull(result[0].tooltip)
        assertEquals(3, calls.get())
    }

    private class Node(parent: AnsibleTreeNode?, segment: String) : AnsibleTreeNode(parent, segment) {
        override fun presentation() = NodePresentation(key, icon = NodeIcon.FOLDER)

        override fun children(context: TreeContext): List<AnsibleTreeNode> = emptyList()
    }

    fun testToolWindowChildrenAreAppendedInOrder() {
        val host = Node(null, "host:prod-prod1")
        val contributors = listOf(
            ToolWindowNodeContributor { parent -> listOf(Node(parent, "effective-vars")) },
            ToolWindowNodeContributor { emptyList() },
            ToolWindowNodeContributor { parent -> listOf(Node(parent, "targeted-by"), Node(parent, "vault")) },
        )
        ExtensionTestUtil.maskExtensions(ToolWindowNodeContributor.EP_NAME, contributors, testRootDisposable)

        assertEquals(
            listOf("host:prod-prod1/effective-vars", "host:prod-prod1/targeted-by", "host:prod-prod1/vault"),
            ToolWindowNodeContributor.childrenOf(host).map { it.key },
        )
    }

    fun testAContributorReturningForeignNodesContributesNothing() {
        val host = Node(null, "host:prod-prod1")
        val other = Node(null, "host:prod-prod2")
        val contributors = listOf(
            ToolWindowNodeContributor { parent -> listOf(Node(parent, "ok"), Node(other, "foreign")) },
            ToolWindowNodeContributor { parent -> listOf(Node(parent, "vault")) },
        )
        ExtensionTestUtil.maskExtensions(ToolWindowNodeContributor.EP_NAME, contributors, testRootDisposable)

        var children: List<AnsibleTreeNode> = emptyList()
        val error = LoggedErrorProcessor.executeAndReturnLoggedError { children = ToolWindowNodeContributor.childrenOf(host) }
        assertNotNull("the misbehaving contributor is reported", error)
        val messages = generateSequence(error) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.toString(), messages.any { "host:prod-prod2/foreign" in it })
        assertEquals(listOf("host:prod-prod1/vault"), children.map { it.key })
    }

    fun testListenerTopicsDeliverOnTheProjectBus() {
        val received = ArrayList<String>()
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(ContextWidgetSegmentListener.TOPIC, ContextWidgetSegmentListener { received += "segments" })
        connection.subscribe(VaultStatusListener.TOPIC, VaultStatusListener { received += "vault" })

        project.messageBus.syncPublisher(ContextWidgetSegmentListener.TOPIC).segmentsChanged()
        project.messageBus.syncPublisher(VaultStatusListener.TOPIC).vaultStatusChanged()
        assertEquals(listOf("segments", "vault"), received)
    }
}
