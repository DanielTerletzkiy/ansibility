package de.terletzkiy.ansibility.context.switching

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.ContextWidgetSegment
import de.terletzkiy.ansibility.api.ContextWidgetSegmentListener
import de.terletzkiy.ansibility.api.WidgetSegment
import de.terletzkiy.ansibility.context.AnsibleContextWidget
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The X02 widget with the CT0 segment seam wired (F8.1): the built-in text follows the selection, segments come in EP
 * order after it, a [ContextWidgetSegmentListener.TOPIC] event, an inventory edit or a new target version refreshes
 * the widget, and a narrow status bar moves the segments into the tooltip.
 */
@RequiresInfraFixture
class ContextWidgetTest : ContextSwitchingTestCase() {
    /** A segment whose text tests change, as V8's lock state would. */
    private class TestSegment(var text: String, private val tooltip: String? = null) : ContextWidgetSegment {
        override fun segment(project: Project, file: VirtualFile): WidgetSegment = WidgetSegment(text, tooltip)
    }

    private fun widget(): AnsibleContextWidget {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val widget = AnsibleContextWidget(project, scope)
        Disposer.register(testRootDisposable) {
            Disposer.dispose(widget)
            scope.cancel()
        }
        widget.start()
        return widget
    }

    private fun waitForText(widget: AnsibleContextWidget, expected: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L
        while (widget.shownText != expected && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals(expected, widget.shownText)
    }

    fun testTheBuiltInTextFollowsTheEditorAndTheSelection() {
        myFixture.configureFromExistingVirtualFile(vf(postfixTemplate))
        val widget = widget()
        waitForText(widget, status("falcon", " · All envs · core 2.18.8 · file: postfix → 4 hosts"))
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        waitForText(widget, status("falcon", " · prod › prod-prod1 · core 2.18.8 · file: postfix → 4 hosts"))
        val tooltip = widget.shownTooltip.orEmpty()
        assertTrue(tooltip, tooltip.contains("Ansible context of falcon: env prod · host prod-prod1 · play auto"))
        assertTrue(tooltip, tooltip.contains("Applies to: role postfix → 4 hosts in ops, prod, test via playbook-setup-system.yml › System"))

        myFixture.configureFromExistingVirtualFile(vf("repos/falcon/ansible/ansible.cfg"))
        waitForText(widget, status("falcon", " · prod › prod-prod1 · core 2.18.8"))
    }

    fun testAVanishedHostIsShownAsStoredAndReported() {
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("prod"), "prod-db9"))
        myFixture.configureFromExistingVirtualFile(vf("repos/falcon/ansible/ansible.cfg"))
        val widget = widget()
        waitForText(widget, status("falcon", " · prod › prod-db9 ⚠ · core 2.18.8"))
        assertTrue(widget.shownTooltip.orEmpty(), widget.shownTooltip.orEmpty().contains("prod-db9 is no longer in environments/prod/hosts.yml"))
    }

    fun testSegmentsComeInExtensionPointOrderAfterTheBuiltInText() {
        val first = TestSegment("🔓 default", "vault ids")
        val last = TestSegment("scope heron⚠")
        ExtensionTestUtil.maskExtensions(ContextWidgetSegment.EP_NAME, listOf(first, FileScopeSegment(), last), testRootDisposable)
        myFixture.configureFromExistingVirtualFile(vf(prod1Vars))
        val widget = widget()
        waitForText(widget, status("falcon", " · All envs · core 2.18.8 · 🔓 default · file: prod-prod1 → 1 host · scope heron⚠"))
        assertTrue(widget.shownTooltip.orEmpty(), widget.shownTooltip.orEmpty().contains("vault ids"))
        assertEquals(listOf("🔓 default", "file: prod-prod1 → 1 host", "scope heron⚠"), widget.currentState!!.segments.map { it.text })
    }

    fun testTheRegisteredFileScopeSegmentComesFirst() {
        val registered = ContextWidgetSegment.EP_NAME.extensionList
        assertTrue(registered.toString(), registered.first() is FileScopeSegment)
        val later = TestSegment("🔓 default")
        ContextWidgetSegment.EP_NAME.point.registerExtension(later, testRootDisposable)
        assertEquals(listOf("file: prod-prod1 → 1 host", "🔓 default"), ContextWidgetSegment.segments(project, vf(prod1Vars)).map { it.text })
    }

    fun testTheSegmentTopicRefreshesTheWidget() {
        val segment = TestSegment("🔒 2 ids locked")
        ExtensionTestUtil.maskExtensions(ContextWidgetSegment.EP_NAME, listOf(segment), testRootDisposable)
        myFixture.configureFromExistingVirtualFile(vf("repos/falcon/ansible/ansible.cfg"))
        val widget = widget()
        waitForText(widget, status("falcon", " · All envs · core 2.18.8 · 🔒 2 ids locked"))
        segment.text = "🔓 default"
        val before = widget.refreshCount
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("no refresh without an event", before, widget.refreshCount)
        project.messageBus.syncPublisher(ContextWidgetSegmentListener.TOPIC).segmentsChanged()
        waitForText(widget, status("falcon", " · All envs · core 2.18.8 · 🔓 default"))
    }

    fun testEditingAnInventoryRefreshesTheFileScopeSegment() {
        myFixture.configureFromExistingVirtualFile(vf(postfixTemplate))
        val widget = widget()
        waitForText(widget, status("falcon", " · All envs · core 2.18.8 · file: postfix → 4 hosts"))
        val document = FileDocumentManager.getInstance().getDocument(vf(falconProdHosts))!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.text.indexOf("app_services:"), "    prod-prod3:\n")
            document.insertString(document.text.indexOf("    prod-prod2:\n      ansible_host"), "    prod-prod3:\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        waitForText(widget, status("falcon", " · All envs · core 2.18.8 · file: postfix → 5 hosts"))
    }

    fun testANewTargetVersionShowsAfterTheNextHighlightingPass() {
        myFixture.configureFromExistingVirtualFile(vf("repos/falcon/ansible/ansible.cfg"))
        val widget = widget()
        waitForText(widget, status("falcon", " · All envs · core 2.18.8"))
        val detector = TargetVersionDetector.getInstance(project)
        val previous = detector.overrideFor
        try {
            // As when the local ansible probe answers: the target version moves, and the daemon restarts and finishes.
            detector.overrideFor = { CoreVersion(2, 17, 0) }
            project.messageBus.syncPublisher(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC).daemonFinished(emptyList())
            waitForText(widget, status("falcon", " · All envs · core 2.17.0"))
        } finally {
            detector.overrideFor = previous
        }
    }

    fun testANarrowStatusBarMovesTheSegmentsIntoTheTooltip() {
        assertFalse("an unknown width is never narrow", WidgetTexts.isNarrow(0, 900))
        assertFalse(WidgetTexts.isNarrow(2400, 900))
        assertTrue(WidgetTexts.isNarrow(1200, 900))

        val state = AnsibleContextWidget.computeState(project, vf(postfixTemplate), withSegments = true)!!
        val wide = state.render(narrow = false)
        assertEquals(status("falcon", " · All envs · core 2.18.8 · file: postfix → 4 hosts"), wide.text)
        assertFalse("the segment text itself is not repeated", wide.tooltip.contains("<b>file: postfix"))

        val narrow = state.render(narrow = true)
        assertEquals(status("falcon", " · All envs · core 2.18.8"), narrow.text)
        assertTrue(narrow.tooltip, narrow.tooltip.contains("<b>file: postfix → 4 hosts</b>"))
        assertTrue(narrow.tooltip, narrow.tooltip.contains("Applies to: role postfix"))
        assertTrue(narrow.tooltip, narrow.tooltip.contains("Click to switch the Ansible context"))
    }

    fun testThePartsOfTheTextAreClickTargets() {
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        val state = AnsibleContextWidget.computeState(project, vf(postfixTemplate), withSegments = true)!!
        val parts = state.render(narrow = false).parts
        assertEquals(state.fullText, parts.joinToString("") { it.text })
        val text = state.fullText
        fun at(fragment: String) = WidgetTexts.partAt(parts, text.indexOf(fragment) + 1) { it.length }
        assertEquals(WidgetTexts.PartKind.ROOT, at("falcon")?.kind)
        assertEquals(WidgetTexts.PartKind.ENVIRONMENT, at("prod ›")?.kind)
        assertEquals(WidgetTexts.PartKind.HOST, at("prod-prod1")?.kind)
        assertEquals(WidgetTexts.PartKind.CORE, at("core")?.kind)
        assertEquals(WidgetTexts.Part("file: postfix → 4 hosts", WidgetTexts.PartKind.SEGMENT, 0), at("file:"))
        assertNull("past the end", WidgetTexts.partAt(parts, text.length + 5) { it.length })
        assertNull("left of the text", WidgetTexts.partAt(parts, -1) { it.length })

        val widget = widget()
        assertTrue(widget.popupGroup(state, at("prod ›")) is EnvironmentGroup)
        assertTrue(widget.popupGroup(state, at("prod-prod1")) is HostGroup)
        assertTrue(widget.popupGroup(state, at("falcon")) is ContextPopupGroup)
        assertTrue(widget.popupGroup(state, null) is ContextPopupGroup)
        val segmentActions = children(widget.popupGroup(state, at("file:")))
        assertEquals(state.segments.single().popupActions, segmentActions)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30
        const val POLL_MILLIS = 20L
    }
}
