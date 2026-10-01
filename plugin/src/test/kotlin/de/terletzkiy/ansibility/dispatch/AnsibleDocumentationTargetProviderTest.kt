package de.terletzkiy.ansibility.dispatch

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.KeywordLevel

class AnsibleDocumentationTargetProviderTest : DispatchTestCase() {
    private val provider = AnsibleDocumentationTargetProvider()

    override fun setUp() {
        super.setUp()
        copyFixture(GOLDEN_HAPROXY)
    }

    private fun moduleSite() = AnsibleSite.ModuleKey(TEMPLATE_FQCN, rangeOf(CONFIGURE, TEMPLATE_FQCN))

    private fun targets(path: String, offset: Int): List<DocumentationTarget> =
        runReadActionBlocking { provider.documentationTargets(psi(path), offset) }

    private fun html(target: DocumentationTarget): DocumentationData =
        runReadActionBlocking { target.computeDocumentation() } as DocumentationData

    fun testFirstNonNullClassifierWinsAndLaterOnesAreNotAsked() {
        val silent = RecordingClassifier { _, _ -> null }
        val modules = RecordingClassifier { _, _ -> moduleSite() }
        val keywords = RecordingClassifier { _, _ -> AnsibleSite.KeywordKey("notify", KeywordLevel.TASK, rangeOf(CONFIGURE, "notify")) }
        classifiers(silent, modules, keywords)
        val docs = RecordingDocumentation { site, _ -> TestDocTarget(site.toString(), "<p>doc</p>", null) }
        documentation(docs)

        val offset = offsetOf(CONFIGURE, TEMPLATE_FQCN, 3)
        val result = targets(CONFIGURE, offset)

        assertEquals(1, result.size)
        assertEquals(listOf(moduleSite()), docs.sites)
        assertEquals(1, silent.calls.size)
        assertEquals(offset, silent.calls.single().second)
        assertEquals(1, modules.calls.size)
        assertTrue("classifiers after the first hit are not asked", keywords.calls.isEmpty())
    }

    fun testFirstNonNullDocumentationWins() {
        classifiers(RecordingClassifier { _, _ -> moduleSite() })
        val none = RecordingDocumentation { _, _ -> null }
        val module = TestDocTarget("module", "<p>module</p>", TEMPLATE_URL)
        val later = RecordingDocumentation { _, _ -> TestDocTarget("later", "<p>later</p>", null) }
        documentation(none, RecordingDocumentation { _, _ -> module }, later)

        assertEquals(listOf(module), targets(CONFIGURE, offsetOf(CONFIGURE, TEMPLATE_FQCN)))
        assertEquals(1, none.sites.size)
        assertTrue(later.sites.isEmpty())
    }

    fun testRendersTheFeatureDocumentation() {
        classifiers(RecordingClassifier { _, _ -> moduleSite() })
        documentation(RecordingDocumentation { site, _ ->
            val fqcn = (site as AnsibleSite.ModuleKey).fqcn
            TestDocTarget(fqcn, "<div class='definition'><pre>$fqcn</pre></div><div class='content'>Template a file out to a target host.</div>", TEMPLATE_URL)
        })

        val target = targets(CONFIGURE, offsetOf(CONFIGURE, TEMPLATE_FQCN)).single()
        val data = html(target)
        assertTrue(data.html, data.html.contains("<pre>ansible.builtin.template</pre>"))
        assertTrue(data.html, data.html.contains("Template a file out to a target host."))
        assertEquals(TEMPLATE_FQCN, runReadActionBlocking { target.computePresentation() }.presentableText)
    }

    fun testNothingIsClassifiedOutsideAnsibleRoots() {
        val classifier = RecordingClassifier { _, _ -> moduleSite() }
        classifiers(classifier)
        val docs = RecordingDocumentation { _, _ -> TestDocTarget("x", "<p>x</p>", null) }
        documentation(docs)
        createFile("outside/tasks/main.yml", "- name: Outside\n  ansible.builtin.template:\n    src: a\n")

        assertEmpty(targets("outside/tasks/main.yml", 20))
        assertTrue(classifier.calls.isEmpty())
        assertTrue(docs.sites.isEmpty())
    }

    fun testNoSiteMeansNoTarget() {
        classifiers(RecordingClassifier { _, _ -> null })
        val docs = RecordingDocumentation { _, _ -> TestDocTarget("x", "<p>x</p>", null) }
        documentation(docs)

        assertEmpty(targets(CONFIGURE, 0))
        assertTrue(docs.sites.isEmpty())
    }

    fun testIsRegisteredAndReachedThroughThePlatformLookup() {
        assertNotNull(DocumentationTargetProvider.EP_NAME.findExtension(AnsibleDocumentationTargetProvider::class.java))
        classifiers(RecordingClassifier { _, _ -> moduleSite() })
        val ours = TestDocTarget("module", "<p>module</p>", TEMPLATE_URL)
        documentation(RecordingDocumentation { _, _ -> ours })
        myFixture.configureFromTempProjectFile(CONFIGURE)
        val editor = myFixture.editor
        val file = myFixture.file
        val offset = offsetOf(CONFIGURE, TEMPLATE_FQCN, 2)

        // The platform collects offset targets on a background thread under a read lock, as the Quick Doc action does.
        val found = inBackgroundReadAction { IdeDocumentationTargetProvider.getInstance(project).documentationTargets(editor, file, offset) }
        assertEquals(listOf<DocumentationTarget>(ours), found)
    }
}
