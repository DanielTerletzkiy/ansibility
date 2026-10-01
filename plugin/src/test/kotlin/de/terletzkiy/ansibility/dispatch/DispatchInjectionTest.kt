package de.terletzkiy.ansibility.dispatch

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.registerExtension
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.JinjaContainer
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Injected fragments (Jinja inside YAML scalars from M5, simulated here by plain text between `{{` and `}}`):
 * every dispatcher classifies the host file at the host offset, as the `api.Sites` range contract requires.
 */
class DispatchInjectionTest : DispatchTestCase() {

    /** Injects plain text into the part of a YAML scalar between the first `{{` and `}}`. */
    private class BracesInjector : MultiHostInjector {
        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            val scalar = context as? YAMLScalar ?: return
            val text = scalar.text
            val start = text.indexOf("{{")
            val end = text.indexOf("}}")
            if (start < 0 || end <= start + 2) return
            registrar.startInjecting(PlainTextLanguage.INSTANCE)
                .addPlace(null, null, scalar, TextRange(start + 2, end))
                .doneInjecting()
        }

        override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(YAMLScalar::class.java)
    }

    private lateinit var classifier: RecordingClassifier
    private val marker = "haproxy_log_path }}"

    override fun setUp() {
        super.setUp()
        copyFixture(GOLDEN_HAPROXY)
        project.registerExtension(MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME, BracesInjector(), testRootDisposable)
        classifier = RecordingClassifier { _, _ ->
            AnsibleSite.VarRef("haproxy_log_path", emptyList(), JinjaContainer.YAML_TEMPLATE, rangeOf(CONFIGURE, "haproxy_log_path"))
        }
        classifiers(classifier)
    }

    /** The injected file around the host offset of [marker] + [delta], and the matching offset inside it. */
    private fun injectedPosition(delta: Int): Pair<PsiFile, Int> = runReadActionBlocking {
        val hostOffset = offsetOf(CONFIGURE, marker, delta)
        val manager = InjectedLanguageManager.getInstance(project)
        val element = manager.findInjectedElementAt(psi(CONFIGURE), hostOffset) ?: error("no injection at $hostOffset")
        val injected = element.containingFile
        assertTrue(manager.isInjectedFragment(injected))
        val injectedOffset = (0..injected.textLength).single { manager.injectedToHost(injected, it) == hostOffset }
        injected to injectedOffset
    }

    private fun assertClassifiedOnHost(delta: Int) {
        val (file, offset) = classifier.calls.single()
        assertEquals(psi(CONFIGURE), file)
        assertEquals(offsetOf(CONFIGURE, marker, delta), offset)
    }

    fun testHostPositionMapsInjectedOffsets() {
        val (injected, offset) = injectedPosition(4)
        val position = runReadActionBlocking { SiteDispatch.hostPosition(injected, offset) }
        assertEquals(psi(CONFIGURE), position.file)
        assertEquals(offsetOf(CONFIGURE, marker, 4), position.offset)
        val host = runReadActionBlocking { SiteDispatch.hostPosition(psi(CONFIGURE), 7) }
        assertEquals(HostPosition(psi(CONFIGURE), 7), host)
    }

    fun testDocumentationClassifiesTheHost() {
        val target = TestDocTarget("haproxy_log_path", "<p>var</p>", null)
        documentation(RecordingDocumentation { _, _ -> target })
        val (injected, offset) = injectedPosition(3)

        val targets = runReadActionBlocking { AnsibleDocumentationTargetProvider().documentationTargets(injected, offset) }

        assertEquals(listOf(target), targets)
        assertClassifiedOnHost(3)
    }

    fun testGotoDeclarationClassifiesTheHost() {
        val defaultsKey = key(GOLDEN_DEFAULTS, "haproxy_log_path")
        val navigation = RecordingNavigation { _, _ -> listOf(defaultsKey) }
        navigation(navigation)
        val (injected, offset) = injectedPosition(5)

        val targets = runReadActionBlocking {
            AnsibleGotoDeclarationHandler().getGotoDeclarationTargets(injected.findElementAt(offset), offset, null)
        }

        assertEquals(listOf<PsiElement>(defaultsKey), targets?.toList())
        assertClassifiedOnHost(5)
    }
}
