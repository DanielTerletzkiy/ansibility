package de.terletzkiy.ansibility.dispatch

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.codeInsight.navigation.targetPresentation
import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.browsers.WebBrowser
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import java.io.File
import java.nio.file.Path
import java.util.Collections

/**
 * Spike S3: in 262, Ctrl+B on a module key whose handler returns a [WebDocTarget] ends in [WebDocTarget.navigate]
 * and thus in the browser, through the platform's own Go to Declaration action (single target: raw navigation
 * request). The browser is replaced by a recorder.
 */
@RequiresInfraFixture
class WebDocTargetNavigationTest : DispatchTestCase() {

    /** Records every URL the IDE would open instead of starting a browser. */
    private class RecordingBrowserLauncher : BrowserLauncher() {
        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun open(url: String) {
            urls += url
        }

        override fun browse(file: File) {
            urls += file.toURI().toString()
        }

        override fun browse(file: Path) {
            urls += file.toUri().toString()
        }

        override fun browse(url: String, browser: WebBrowser?, project: Project?) {
            urls += url
        }
    }

    private val browser = RecordingBrowserLauncher()

    override fun setUp() {
        super.setUp()
        copyFixture(GOLDEN_HAPROXY)
        ApplicationManager.getApplication().replaceService(BrowserLauncher::class.java, browser, testRootDisposable)
        classifiers(RecordingClassifier { _, _ -> AnsibleSite.ModuleKey(TEMPLATE_FQCN, rangeOf(CONFIGURE, TEMPLATE_FQCN)) })
    }

    private fun webTarget(): WebDocTarget = runReadActionBlocking {
        WebDocTarget(psi(CONFIGURE).findElementAt(offsetOf(CONFIGURE, TEMPLATE_FQCN))!!, TEMPLATE_URL, TEMPLATE_FQCN)
    }

    fun testGotoDeclarationActionOpensTheDocsPage() {
        navigation(RecordingNavigation { site, file ->
            val module = site as AnsibleSite.ModuleKey
            listOf(WebDocTarget(file.findElementAt(module.range.startOffset)!!, TEMPLATE_URL, module.fqcn))
        })
        myFixture.configureFromTempProjectFile(CONFIGURE)
        myFixture.editor.caretModel.moveToOffset(offsetOf(CONFIGURE, TEMPLATE_FQCN, 5))

        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(listOf(TEMPLATE_URL), browser.urls)
        assertEquals("the editor stays on the task file", vf(CONFIGURE), FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile)
    }

    fun testCtrlHoverUnderlinesTheModuleKeyAsNavigatable() {
        navigation(RecordingNavigation { site, file ->
            val module = site as AnsibleSite.ModuleKey
            listOf(WebDocTarget(file.findElementAt(module.range.startOffset)!!, TEMPLATE_URL, module.fqcn))
        })
        myFixture.configureFromTempProjectFile(CONFIGURE)
        val offset = offsetOf(CONFIGURE, TEMPLATE_FQCN, 4)

        val editor = myFixture.editor
        val file = myFixture.file
        val data = inBackgroundReadAction { GotoDeclarationOrUsageHandler2.getCtrlMouseData(editor, file, offset) }

        assertNotNull(data)
        assertTrue(data!!.isNavigatable)
        assertTrue(data.ranges.toString(), data.ranges.any { offset in it.startOffset..it.endOffset })
        assertTrue("hovering never opens the browser", browser.urls.isEmpty())
    }

    fun testNavigateOpensTheUrlDirectly() {
        webTarget().navigate(true)
        assertEquals(listOf(TEMPLATE_URL), browser.urls)
    }

    fun testPresentation() {
        val target = webTarget()
        assertEquals("Open ansible.builtin.template docs", target.name)
        assertEquals("Open ansible.builtin.template docs", target.presentation!!.presentableText)
        assertEquals(TEMPLATE_URL, target.presentation!!.locationString)
        assertTrue(target.canNavigate())
        assertFalse("never treated as a source location", target.canNavigateToSource())
        assertTrue(runReadActionBlocking { target.isValid })
        assertEquals(project, target.project)
        assertEquals(webTarget(), target)
    }

    fun testSeveralTargetsIncludingTheWebTargetReachThePlatform() {
        val defaultsKey = key(GOLDEN_DEFAULTS, "haproxy_log_path")
        navigation(RecordingNavigation { _, _ -> listOf(webTarget(), defaultsKey) })
        myFixture.configureFromTempProjectFile(CONFIGURE)

        val found = runReadActionBlocking {
            GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, offsetOf(CONFIGURE, TEMPLATE_FQCN, 1))
        }

        assertEquals(listOf<PsiElement>(webTarget(), defaultsKey), found.toList())
        assertTrue("nothing is opened before the user picks a target", browser.urls.isEmpty())
        // How the platform chooser renders the web target.
        val element: PsiElement = webTarget()
        val presentation = runReadActionBlocking { targetPresentation(element) }
        assertEquals("Open ansible.builtin.template docs", presentation.presentableText)
    }
}
