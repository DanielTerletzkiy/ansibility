package de.terletzkiy.ansibility.docs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.dispatch.AnsibleDocumentationTargetProvider
import de.terletzkiy.ansibility.dispatch.AnsibleGotoDeclarationHandler
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.AppSettings
import de.terletzkiy.ansibility.settings.ModuleNavigationTarget
import java.util.concurrent.TimeUnit

/**
 * Base of the docs area's platform tests: sub-trees of the sanitised infra fixture (line numbers identical to the
 * real repo) plus the synthetic `testData/docs` files become Ansible roots of the light project. `golden/docker`
 * is copied along, so the golden root targets ansible-core 2.18.8 through its Dockerfile pins, as in the repo.
 *
 * Lookups go through the real dispatch entry points and the registered extensions. The application settings and
 * the target-version override hook are restored after every test (the light project and the application outlive it).
 */
abstract class DocsTestCase : BasePlatformTestCase() {
    private lateinit var savedSettings: AppSettings
    private lateinit var savedOverride: (AnsibleRoot) -> CoreVersion?
    private val overrides = HashMap<String, CoreVersion>()

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        savedSettings = AnsibilityAppSettings.getInstance().settings
        savedOverride = TargetVersionDetector.getInstance(project).overrideFor
        copyInfra(GOLDEN_DOCKER)
    }

    override fun tearDown() {
        try {
            AnsibilityAppSettings.getInstance().update { savedSettings }
            TargetVersionDetector.getInstance(project).overrideFor = savedOverride
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Copies `infra/<path>` to the same path in the project and re-detects the roots. */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    /** Copies the synthetic files of `testData/docs/golden` into the project's `golden/`. */
    protected fun copyDocsData() {
        myFixture.copyDirectoryToProject("docs/golden", "golden")
        refreshRoots()
    }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    /** Overrides the target ansible-core of the root at [rootPath] through the settings hook of [TargetVersionDetector]. */
    protected fun overrideTarget(rootPath: String, version: CoreVersion) {
        overrides[vf(rootPath).path] = version
        val previous = savedOverride
        TargetVersionDetector.getInstance(project).overrideFor = { root -> overrides[root.dir.path] ?: previous(root) }
    }

    protected fun moduleNavigation(target: ModuleNavigationTarget) {
        AnsibilityAppSettings.getInstance().update { it.copy(docs = it.docs.copy(moduleNavigation = target)) }
    }

    protected fun root(path: String): AnsibleRoot = AnsibleWorkspace.getInstance(project).rootFor(vf(path)) ?: error("no root for $path")

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun psi(path: String): PsiFile = PsiManager.getInstance(project).findFile(vf(path)) ?: error("no PSI for $path")

    /** The offset of [marker] on 1-based [line] of [path], plus [delta]; fails when the line does not contain it. */
    protected fun at(path: String, line: Int, marker: String, delta: Int = 0): Int = runReadActionBlocking {
        val document = PsiDocumentManager.getInstance(project).getDocument(psi(path)) ?: error("no document for $path")
        val start = document.getLineStartOffset(line - 1)
        val text = document.getText(TextRange(start, document.getLineEndOffset(line - 1)))
        val index = text.indexOf(marker)
        check(index >= 0) { "'$marker' is not on line $line of $path: '$text'" }
        start + index + delta
    }

    /** The offset of the first [marker] in [path], plus [delta]. */
    protected fun offsetOf(path: String, marker: String, delta: Int = 0): Int {
        val index = runReadActionBlocking { psi(path).text.indexOf(marker) }
        check(index >= 0) { "'$marker' not found in $path" }
        return index + delta
    }

    protected fun rangeAt(path: String, line: Int, marker: String): TextRange = TextRange.from(at(path, line, marker), marker.length)

    protected fun classify(path: String, offset: Int): AnsibleSite? = runReadActionBlocking { TaskSiteClassifier().classify(psi(path), offset) }

    /** The documentation targets of the plugin's one documentation entry point, as the platform asks it. */
    protected fun docTargets(path: String, offset: Int): List<DocumentationTarget> =
        inBackgroundReadAction { AnsibleDocumentationTargetProvider().documentationTargets(psi(path), offset) }

    /** The single documentation target at [offset]. */
    protected fun docTarget(path: String, offset: Int): DocumentationTarget = docTargets(path, offset).single()

    protected fun documentation(target: DocumentationTarget): DocumentationData =
        inBackgroundReadAction { target.computeDocumentation() } as DocumentationData

    protected fun hint(target: DocumentationTarget): String? = inBackgroundReadAction { target.computeDocumentationHint() }

    /** The Go to Declaration targets of the plugin's one handler. */
    protected fun gotoTargets(path: String, offset: Int): List<PsiElement> = inBackgroundReadAction {
        val file = psi(path)
        AnsibleGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, null)?.toList().orEmpty()
    }

    /** Runs [action] in a read action on a pooled thread, as the platform runs documentation and navigation lookups. */
    protected fun <T> inBackgroundReadAction(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(action) }.get(60, TimeUnit.SECONDS)

    companion object {
        const val GOLDEN_DOCKER = "golden/docker"
        const val HAPROXY = "golden/roles/haproxy"
        const val PERCONA = "golden/roles/percona"
        const val KEYCLOAK_TASKS = "golden/roles/keycloak/tasks"
        const val CONFIGURE = "$HAPROXY/tasks/configure.yml"
        const val HAPROXY_MAIN = "$HAPROXY/tasks/main.yml"
        const val HAPROXY_APT = "$HAPROXY/tasks/apt.yml"
        const val HAPROXY_HANDLERS = "$HAPROXY/handlers/main.yml"
        const val HAPROXY_DEFAULTS = "$HAPROXY/defaults/main.yml"
        const val PERCONA_DATABASE = "$PERCONA/tasks/database.yml"
        const val KEYCLOAK_FLOWS = "$KEYCLOAK_TASKS/authentication_flows.yml"
        const val DEMO_TASKS = "golden/roles/docs_demo/tasks/main.yml"
        const val DEMO_DEFAULTS = "golden/roles/docs_demo/defaults/main.yml"
        const val DEMO_PLAYBOOK = "golden/playbooks/playbook-docs-demo.yml"

        const val DOCS_11 = "https://docs.ansible.com/ansible/11/"
        const val TEMPLATE_URL = "${DOCS_11}collections/ansible/builtin/template_module.html"
        const val PINNED_SOURCE = "docs: ansible-core 2.18.8 bundled + pinned collections"

        private val EXTERNAL_URL = Regex("""externalUrl=(.*?), linkUrls=""")

        /**
         * The external URL (Shift+F1, the popup's browser icon) of a documentation result. The platform keeps it in
         * a module-internal field, so it is read from the result's data-class text.
         */
        fun externalUrl(data: DocumentationData): String? {
            val text = EXTERNAL_URL.find(data.toString())?.groupValues?.get(1) ?: error("no link data in $data")
            return text.takeUnless { it == "null" }
        }

        private val BLOCK_TAG = Regex("""</?(p|div|pre|table|tr|td|br|li|ul|hr|h\d)\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val NUMERIC_ENTITY = Regex("&#(\\d+);")

        /**
         * [html] as the text a reader sees: block elements separate words, inline elements (code, links, bold,
         * icons) do not; entities are decoded and whitespace is collapsed.
         */
        fun plain(html: String): String = html
            .replace(BLOCK_TAG, " ")
            .replace(Regex("<[^>]+>"), "")
            .replace(NUMERIC_ENTITY) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
