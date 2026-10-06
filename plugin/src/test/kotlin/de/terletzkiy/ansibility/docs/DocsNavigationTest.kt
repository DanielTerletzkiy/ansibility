package de.terletzkiy.ansibility.docs

import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.browsers.WebBrowser
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.LocalAnsibleInstall
import de.terletzkiy.ansibility.api.LocalAnsibleRuntime
import de.terletzkiy.ansibility.dispatch.WebDocTarget
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.settings.ModuleNavigationTarget
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** F5.5 Ctrl+B to the web docs with X18 anchors, through the plugin's Go to Declaration handler (M2 acceptance 8, 9). */
@RequiresInfraFixture
class DocsNavigationTest : DocsTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY, PERCONA)
        copyDocsData()
    }

    private fun webUrl(path: String, offset: Int): String {
        val target = gotoTargets(path, offset).single()
        return (target as? WebDocTarget ?: error("not a web target: $target")).url
    }

    fun testTemplateModuleAtConfigureLine3() {
        // golden/roles/haproxy/tasks/configure.yml:3 → versioned per D11 (core 2.18 → Ansible 11)
        val target = gotoTargets(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template", 4)).single() as WebDocTarget
        assertEquals("https://docs.ansible.com/ansible/11/collections/ansible/builtin/template_module.html", target.url)
        assertEquals("Open ansible.builtin.template docs", target.name)
    }

    fun testOptionAnchors() {
        // configure.yml:5 → #parameter-dest (X18)
        assertEquals("$TEMPLATE_URL#parameter-dest", webUrl(CONFIGURE, at(CONFIGURE, 5, "dest", 1)))
        val container = "${DOCS_11}collections/community/docker/docker_container_module.html"
        assertEquals("$container#parameter-healthcheck/interval", webUrl(DEMO_TASKS, offsetOf(DEMO_TASKS, "interval:")))
        assertEquals("$container#parameter-mounts/type", webUrl(DEMO_TASKS, offsetOf(DEMO_TASKS, "- type: bind", 3)))
        assertEquals("an undocumented key opens its documented parent", "$container#parameter-env", webUrl(DEMO_TASKS, offsetOf(DEMO_TASKS, "FOO:")))
        assertEquals("aliases use the documented name", "${DOCS_11}collections/ansible/builtin/file_module.html#parameter-path", webUrl(DEMO_TASKS, offsetOf(DEMO_TASKS, "path: \"/srv")))
    }

    fun testCanonicalPagesOfRedirectsAndAliases() {
        assertEquals(
            "${DOCS_11}collections/ansible/mysql/mysql_user_module.html",
            webUrl(PERCONA_DATABASE, at(PERCONA_DATABASE, 3, "community.mysql.mysql_user", 2)),
        )
        assertEquals(
            "${DOCS_11}collections/ansible/builtin/systemd_service_module.html",
            webUrl(HAPROXY_HANDLERS, at(HAPROXY_HANDLERS, 3, "ansible.builtin.systemd", 2)),
        )
    }

    fun testKeywordSections() {
        val page = "${DOCS_11}reference_appendices/playbooks_keywords.html"
        assertEquals("$page#task", webUrl(HAPROXY_MAIN, at(HAPROXY_MAIN, 10, "when")))
        assertEquals("$page#play", webUrl(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "hosts:")))
        assertEquals("$page#role", webUrl(DEMO_PLAYBOOK, offsetOf(DEMO_PLAYBOOK, "tags: [demo]")))
        assertEquals("$page#block", webUrl(DEMO_TASKS, offsetOf(DEMO_TASKS, "rescue:")))
    }

    fun testKeywordTargetsNameTheKeyword() {
        val target = gotoTargets(HAPROXY_MAIN, at(HAPROXY_MAIN, 10, "when")).single() as WebDocTarget
        assertEquals("Open the when keyword docs", target.name)
        assertEquals("Open the when keyword docs", target.presentableText)
        assertEquals("when", target.fqcn)
    }

    fun testVersionedByTheTarget() {
        overrideTarget("golden", CoreVersion(2, 21, 4))
        assertEquals(
            "https://docs.ansible.com/ansible/14/collections/ansible/builtin/template_module.html",
            webUrl(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template")),
        )
    }

    fun testNothingForUnknownModulesAndOtherSites() {
        assertEmpty(gotoTargets(DEMO_TASKS, offsetOf(DEMO_TASKS, "acme.custom.no_such_module")))
        assertEmpty("variable keys are not ours", gotoTargets(DEMO_TASKS, offsetOf(DEMO_TASKS, "demo_dirs:", 2)))
    }

    fun testQuickDocSettingFallsThrough() {
        moduleNavigation(ModuleNavigationTarget.QUICK_DOC)
        assertEmpty(gotoTargets(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template")))
        assertEmpty(gotoTargets(CONFIGURE, at(CONFIGURE, 5, "dest")))
        assertEquals("keywords still open their page", 1, gotoTargets(HAPROXY_MAIN, at(HAPROXY_MAIN, 10, "when")).size)
    }

    fun testModuleSourceSettingFallsBackToTheWebWithoutALocalInstall() {
        moduleNavigation(ModuleNavigationTarget.MODULE_SOURCE)
        replaceRuntime(null)
        assertEquals(TEMPLATE_URL, webUrl(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template")))
    }

    fun testModuleSourceSettingOpensTheLocalSource() {
        val sitePackages = FileUtil.createTempDirectory("ansibility-site", null, true).toPath()
        val source = sitePackages.resolve("ansible/modules/template.py")
        Files.createDirectories(source.parent)
        Files.writeString(source, "# template module\n")
        replaceRuntime(LocalAnsibleInstall(CoreVersion(2, 21, 4), "/usr/bin/ansible", sitePackages.resolve("ansible").toString()))
        moduleNavigation(ModuleNavigationTarget.MODULE_SOURCE)

        val target = gotoTargets(CONFIGURE, at(CONFIGURE, 3, "ansible.builtin.template")).single()
        assertInstanceOf(target, PsiFile::class.java)
        assertEquals(source.toRealPath(), Path.of((target as PsiFile).virtualFile.path).toRealPath())
    }

    fun testGotoDeclarationActionOpensTheBrowser() {
        val browser = RecordingBrowserLauncher()
        ApplicationManager.getApplication().replaceService(BrowserLauncher::class.java, browser, testRootDisposable)
        myFixture.configureFromTempProjectFile(CONFIGURE)
        myFixture.editor.caretModel.moveToOffset(at(CONFIGURE, 5, "dest", 2))

        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(listOf("$TEMPLATE_URL#parameter-dest"), browser.urls)
    }

    private fun replaceRuntime(install: LocalAnsibleInstall?) {
        val runtime = object : LocalAnsibleRuntime {
            override fun localInstall(project: Project): LocalAnsibleInstall? = install
            override val probeTracker: ModificationTracker = ModificationTracker.NEVER_CHANGED
        }
        ApplicationManager.getApplication().replaceService(LocalAnsibleRuntime::class.java, runtime, testRootDisposable)
    }

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
}
