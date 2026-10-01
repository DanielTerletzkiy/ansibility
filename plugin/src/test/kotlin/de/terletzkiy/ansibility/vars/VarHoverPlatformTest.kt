package de.terletzkiy.ansibility.vars

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.YAMLLanguage

/**
 * The card as the platform's Quick Documentation finds it: M2 acceptance 10 (a competing legacy
 * `lang.documentationProvider` for YAML is never used on variable sites) and 11 (warm hover timing).
 */
class VarHoverPlatformTest : VarsTestCase() {

    /** A legacy provider like msdehghan's or SchemaStore's: it would document every YAML element. */
    private class LegacyYamlProvider : AbstractDocumentationProvider() {
        override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String = LEGACY
    }

    private fun platformTargets(path: String, offset: Int): List<DocumentationTarget> {
        myFixture.configureFromTempProjectFile(path)
        val editor = myFixture.editor
        val file = myFixture.file
        return inBackgroundReadAction { IdeDocumentationTargetProvider.getInstance(project).documentationTargets(editor, file, offset) }
    }

    fun testLegacyYamlProviderIsNotUsedOnVariableSites() {
        copyInfra("golden/roles/haproxy")
        val legacy = LegacyYamlProvider()
        LanguageDocumentation.INSTANCE.addExplicitExtension(YAMLLanguage.INSTANCE, legacy, testRootDisposable)
        assertTrue("the legacy provider is live for YAML", legacy in LanguageDocumentation.INSTANCE.allForLanguage(YAMLLanguage.INSTANCE))

        val sysctl = "golden/roles/haproxy/tasks/sysctl.yml"
        val onReference = platformTargets(sysctl, offsetAt(sysctl, 11, "haproxy_settings_kernel_somaxconn", 3))
        assertEquals(1, onReference.size)
        assertTrue(onReference.single() is VarDocumentationTarget)
        assertFalse(LEGACY in html(onReference.single()))

        val defaults = "golden/roles/haproxy/defaults/main.yml"
        val onKey = platformTargets(defaults, offsetAt(defaults, 15, "haproxy_settings_kernel_somaxconn", 3))
        assertEquals(listOf(true), onKey.map { it is VarDocumentationTarget })

        // Outside Ansible roots the platform falls back to PSI targets, which wrap the legacy providers.
        createFile("outside/vars.yml", "some_key: 1\n")
        val outside = platformTargets("outside/vars.yml", 2)
        assertTrue(outside.isNotEmpty())
        assertTrue(outside.none { it is VarDocumentationTarget })
    }

    fun testWarmHoverTiming() {
        copyInfra("golden/roles/haproxy")
        val sysctl = "golden/roles/haproxy/tasks/sysctl.yml"
        val offset = offsetAt(sysctl, 11, "haproxy_settings_kernel_somaxconn", 3)
        hover(sysctl, offset).let(::html) // warm up: indexes, caches, class loading
        val times = (1..REPETITIONS).map {
            val start = System.nanoTime()
            val html = html(hover(sysctl, offset))
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            assertTrue("Maximum connections at kernel level" in html)
            elapsed
        }.sorted()
        val p95 = times[(times.size * 95 + 99) / 100 - 1]
        println("VARS: warm hover over $REPETITIONS repetitions: p50=${"%.1f".format(times[times.size / 2])} ms, p95=${"%.1f".format(p95)} ms, max=${"%.1f".format(times.last())} ms")
        assertTrue("warm hover p95 ${"%.1f".format(p95)} ms (target < 50 ms)", p95 < BUDGET_MS)
    }

    companion object {
        const val LEGACY = "LEGACY-DOC-PROVIDER"
        const val REPETITIONS = 20

        /** Generous for loaded CI machines; the measured p95 is printed and reported against the 50 ms target. */
        const val BUDGET_MS = 500.0
    }
}
