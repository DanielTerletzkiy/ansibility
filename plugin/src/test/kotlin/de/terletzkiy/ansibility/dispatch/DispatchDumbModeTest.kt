package de.terletzkiy.ansibility.dispatch

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.KeywordLevel
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * While indexing, quick doc and completion ask only `DumbAware` extensions (index-backed ones would fail), and
 * Go to Declaration asks all of them (the platform grants its handlers reliable index access).
 */
@RequiresInfraFixture
class DispatchDumbModeTest : DispatchTestCase() {

    private class DumbAwareClassifier(answer: (PsiFile, Int) -> AnsibleSite?) : SiteClassifier by RecordingClassifier(answer), DumbAware

    private class DumbAwareDocumentation(private val target: DocumentationTarget) : SiteDocumentation, DumbAware {
        override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget = target
    }

    private class DumbAwareSource(private val delegate: RecordingCompletionSource) : CompletionSource by delegate, DumbAware

    private val indexBacked = RecordingClassifier { _, _ -> AnsibleSite.KeywordKey("notify", KeywordLevel.TASK, rangeOf(CONFIGURE, "notify")) }
    private val module get() = AnsibleSite.ModuleKey(TEMPLATE_FQCN, rangeOf(CONFIGURE, TEMPLATE_FQCN))
    private val snapshotTarget = TestDocTarget("snapshot", "<p>from the bundled snapshot</p>", TEMPLATE_URL)
    private val indexTarget = TestDocTarget("index", "<p>from the index</p>", null)

    override fun setUp() {
        super.setUp()
        copyFixture(GOLDEN_HAPROXY)
        classifiers(indexBacked, DumbAwareClassifier { _, _ -> module })
        documentation(RecordingDocumentation { _, _ -> indexTarget }, DumbAwareDocumentation(snapshotTarget))
    }

    private fun docs(): List<DocumentationTarget> = runReadActionBlocking {
        AnsibleDocumentationTargetProvider().documentationTargets(psi(CONFIGURE), offsetOf(CONFIGURE, TEMPLATE_FQCN))
    }

    fun testQuickDocAsksOnlyDumbAwareExtensionsWhileIndexing() {
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(listOf<DocumentationTarget>(snapshotTarget), docs())
        }
        assertTrue("the index-backed classifier is skipped while indexing", indexBacked.calls.isEmpty())

        assertEquals("in smart mode the first extensions win again", listOf<DocumentationTarget>(indexTarget), docs())
        assertEquals(1, indexBacked.calls.size)
    }

    fun testGotoDeclarationAsksEveryExtensionWhileIndexing() {
        val target = key(GOLDEN_DEFAULTS, "haproxy_log_path")
        navigation(RecordingNavigation { _, _ -> listOf<PsiElement>(target) })
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val found = runReadActionBlocking {
                val offset = offsetOf(CONFIGURE, TEMPLATE_FQCN)
                AnsibleGotoDeclarationHandler().getGotoDeclarationTargets(psi(CONFIGURE).findElementAt(offset), offset, null)
            }
            assertEquals(listOf<PsiElement>(target), found?.toList())
        }
        assertEquals(1, indexBacked.calls.size)
    }

    fun testCompletionAsksOnlyDumbAwareSourcesWhileIndexing() {
        val indexSource = RecordingCompletionSource(listOf("haproxy_from_index"))
        val snapshotSource = RecordingCompletionSource(listOf("haproxy_from_snapshot", "haproxy_from_snapshot_2"))
        completionSources(indexSource, DumbAwareSource(snapshotSource))
        createFile("$GOLDEN_HAPROXY/tasks/dumb.yml", "- name: X\n  ansible.builtin.debug:\n    msg: \"{{  }}\"\n")
        myFixture.configureFromTempProjectFile("$GOLDEN_HAPROXY/tasks/dumb.yml")
        myFixture.editor.caretModel.moveToOffset(psi("$GOLDEN_HAPROXY/tasks/dumb.yml").text.indexOf("{{ ") + 3)

        var items = emptyList<String>()
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            myFixture.completeBasic()
            // Leaving dumb mode closes the lookup, so read it while indexing.
            items = myFixture.lookupElementStrings.orEmpty()
        }

        assertTrue(indexSource.sites.isEmpty())
        assertEquals(listOf<AnsibleSite?>(module), snapshotSource.sites)
        assertTrue(indexBacked.calls.isEmpty())
        assertContainsElements(items, "haproxy_from_snapshot", "haproxy_from_snapshot_2")
        assertDoesntContain(items, "haproxy_from_index")
    }
}
