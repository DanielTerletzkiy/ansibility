package de.terletzkiy.ansibility.dispatch

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.model.Pointer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.util.Collections
import java.util.concurrent.TimeUnit

/** A [SiteClassifier] double that answers with [answer] and records every call (file, offset). */
class RecordingClassifier(private val answer: (PsiFile, Int) -> AnsibleSite?) : SiteClassifier {
    val calls: MutableList<Pair<PsiFile, Int>> = Collections.synchronizedList(mutableListOf())

    override fun classify(file: PsiFile, offset: Int): AnsibleSite? {
        calls += file to offset
        return answer(file, offset)
    }
}

/** A [SiteDocumentation] double that records the sites it was asked for. */
class RecordingDocumentation(private val answer: (AnsibleSite, PsiFile) -> DocumentationTarget?) : SiteDocumentation {
    val sites: MutableList<AnsibleSite> = Collections.synchronizedList(mutableListOf())

    override fun documentation(site: AnsibleSite, file: PsiFile): DocumentationTarget? {
        sites += site
        return answer(site, file)
    }
}

/** A [SiteNavigation] double that records the sites it was asked for. */
class RecordingNavigation(private val answer: (AnsibleSite, PsiFile) -> List<PsiElement>) : SiteNavigation {
    val sites: MutableList<AnsibleSite> = Collections.synchronizedList(mutableListOf())

    override fun targets(site: AnsibleSite, file: PsiFile): List<PsiElement> {
        sites += site
        return answer(site, file)
    }
}

/** A [CompletionSource] double that adds [items] and records the sites it was called with (null included). */
class RecordingCompletionSource(private val items: List<String>) : CompletionSource {
    val sites: MutableList<AnsibleSite?> = Collections.synchronizedList(mutableListOf())

    override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
        sites += site
        for (item in items) result.addElement(LookupElementBuilder.create(item).withTypeText("ours"))
    }
}

/** A minimal documentation target, as a `siteDocumentation` feature would return it. */
class TestDocTarget(private val title: String, private val html: String, private val url: String?) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(title).presentation()

    override fun computeDocumentation(): DocumentationResult {
        val documentation = DocumentationResult.documentation(html)
        return if (url == null) documentation else documentation.externalUrl(url)
    }

    override fun toString(): String = "TestDocTarget($title)"
}

/**
 * Base for dispatch tests: sub-trees of the sanitised infra fixture become Ansible roots of the light project,
 * and the four internal extension points start empty (masked), so only the doubles a test installs take part.
 */
abstract class DispatchTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        classifiers()
        documentation()
        navigation()
        completionSources()
    }

    /** Copies `infra/<path>` for each path to the same path in the project and re-detects the roots. */
    protected fun copyFixture(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    /** Creates a file in the project (outside the fixture) and re-detects the roots. */
    protected fun createFile(path: String, text: String): VirtualFile =
        myFixture.tempDirFixture.createFile(path, text).also { refreshRoots() }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    /** Runs [action] in a read action on a pooled thread, as the platform runs navigation and doc lookups. */
    protected fun <T> inBackgroundReadAction(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> { runReadActionBlocking(action) }.get(30, TimeUnit.SECONDS)

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun psi(path: String): PsiFile = PsiManager.getInstance(project).findFile(vf(path)) ?: error("no PSI for $path")

    /** The offset of the first occurrence of [marker] in [path], plus [delta]. */
    protected fun offsetOf(path: String, marker: String, delta: Int = 0): Int {
        val index = psi(path).text.indexOf(marker)
        check(index >= 0) { "'$marker' not found in $path" }
        return index + delta
    }

    /** The range of the first occurrence of [marker] in [path]. */
    protected fun rangeOf(path: String, marker: String): TextRange = TextRange.from(offsetOf(path, marker), marker.length)

    /** The first key named [name] in [path]. */
    protected fun key(path: String, name: String): YAMLKeyValue =
        PsiTreeUtil.findChildrenOfType(psi(path), YAMLKeyValue::class.java).firstOrNull { it.keyText == name }
            ?: error("no key $name in $path")

    protected fun classifiers(vararg extensions: SiteClassifier) = mask(SiteClassifier.EP_NAME, extensions.toList())

    protected fun documentation(vararg extensions: SiteDocumentation) = mask(SiteDocumentation.EP_NAME, extensions.toList())

    protected fun navigation(vararg extensions: SiteNavigation) = mask(SiteNavigation.EP_NAME, extensions.toList())

    protected fun completionSources(vararg extensions: CompletionSource) = mask(CompletionSource.EP_NAME, extensions.toList())

    private val masks = mutableMapOf<String, Disposable>()

    /** Replaces the extensions of [point] with [extensions]; a masked point cannot be masked again, so the previous mask is lifted first. */
    private fun <T : Any> mask(point: ExtensionPointName<T>, extensions: List<T>) {
        masks.remove(point.name)?.let(Disposer::dispose)
        val disposable = Disposer.newDisposable(testRootDisposable, "mask ${point.name}")
        ExtensionTestUtil.maskExtensions(point, extensions, disposable)
        masks[point.name] = disposable
    }

    companion object {
        const val GOLDEN_HAPROXY = "golden/roles/haproxy"
        const val FALCON = "repos/falcon"
        const val CONFIGURE = "$GOLDEN_HAPROXY/tasks/configure.yml"
        const val GOLDEN_DEFAULTS = "$GOLDEN_HAPROXY/defaults/main.yml"
        const val FALCON_GROUP_VARS = "$FALCON/ansible/environments/prod/group_vars/all/vars.yml"
        const val FALCON_SPECS = "$FALCON/ansible/roles/haproxy/meta/argument_specs.yml"
        const val FALCON_DEFAULTS = "$FALCON/ansible/roles/haproxy/defaults/main.yml"
        const val TEMPLATE_FQCN = "ansible.builtin.template"
        const val TEMPLATE_URL = "https://docs.ansible.com/ansible/11/collections/ansible/builtin/template_module.html"
    }
}
