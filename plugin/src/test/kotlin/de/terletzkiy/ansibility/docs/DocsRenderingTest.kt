package de.terletzkiy.ansibility.docs

import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.DocSource
import de.terletzkiy.ansibility.api.DocSourceKind
import de.terletzkiy.ansibility.api.ResolvedModuleDoc
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.Deprecation
import de.terletzkiy.ansibility.semantics.schema.ModuleDoc
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.schema.RouteResolution

/**
 * Rendering details the bundled snapshots do not exercise (option deprecations, `no_log`, local sources that miss
 * the target, markup links to other plugin types), with a synthetic module served by a wrapped [AnsibleDocService].
 */
@RequiresInfraFixture
class DocsRenderingTest : DocsTestCase() {
    private lateinit var root: AnsibleRoot

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
        root = root(CONFIGURE)
        val real = AnsibleDocService.getInstance(project)
        val fake = object : AnsibleDocService by real {
            override fun moduleDoc(root: AnsibleRoot, fqcn: String): ResolvedModuleDoc? =
                if (fqcn == THING) thing() else real.moduleDoc(root, fqcn)
        }
        project.replaceService(AnsibleDocService::class.java, fake, testRootDisposable)
    }

    private fun thing(): ResolvedModuleDoc {
        val level = OptionSpec("level", type = OptionType.Int)
        val options = mapOf(
            "token" to OptionSpec("token", required = true, noLog = true, description = listOf("The API token.")),
            "old" to OptionSpec(
                "old",
                deprecated = Deprecation(why = "Use O(token).", removedIn = "3.0.0", removedFromCollection = "acme.demo"),
                versionAdded = "1.1.0",
                description = listOf("See P(ansible.builtin.file#lookup), P(acme.demo.cb#callback) and RV(result.id)."),
            ),
            "nested" to OptionSpec("nested", type = OptionType.Dict, options = mapOf("inner" to OptionSpec("inner", type = OptionType.Dict, options = mapOf("level" to level)))),
        )
        val doc = ModuleDoc(
            fqcn = THING,
            shortDescription = "Manage a thing",
            description = listOf("Works with M(ansible.builtin.copy) and O(nested.inner)."),
            options = options,
            deprecated = Deprecation(why = "Superseded.", alternative = "acme.demo.new_thing", removedIn = "4.0.0", removedFromCollection = "acme.demo"),
        )
        val source = DocSource(DocSourceKind.LOCAL, CoreVersion(2, 21, 4), "local ansible-core 2.21.4", matchesTarget = false, mapOf("acme.demo" to "1.2.3"))
        return ResolvedModuleDoc(RouteResolution(THING, THING, listOf(THING)), doc, source, "${DOCS_11}collections/acme/demo/thing_module.html")
    }

    private fun optionHtml(vararg path: String): String {
        val target = inBackgroundReadAction { OptionDocumentationTarget.create(project, root, THING, path.toList()) }!!
        return documentation(target).html
    }

    fun testModuleCardFromALocalSourceThatMissesTheTarget() {
        val data = documentation(ModuleDocumentationTarget(project, root, THING))
        val text = plain(data.html)
        assertTrue(text, text.startsWith("acme.demo.thing module · acme.demo 1.2.3 · core 2.21.4"))
        assertTrue(text, text.contains("Deprecated. Removal in acme.demo 4.0.0. Superseded. Alternative: acme.demo.new_thing"))
        assertTrue(data.html, data.html.contains("href=\"psi_element://ansibility-module/acme.demo.new_thing\""))
        assertTrue(text, text.contains("Required options: token (str)"))
        assertTrue(text, text.endsWith("docs: local ansible-core 2.21.4 · target ansible-core 2.18.8"))
        assertTrue("O() links to the page's anchor", data.html.contains("href=\"${DOCS_11}collections/acme/demo/thing_module.html#parameter-nested/inner\""))
        assertEquals("${DOCS_11}collections/acme/demo/thing_module.html", externalUrl(data))
    }

    fun testOptionDeprecationVersionAndPluginLinks() {
        val html = optionHtml("old")
        val text = plain(html)
        assertTrue(text, text.contains("Deprecated. Removal in acme.demo 3.0.0. Use token."))
        assertTrue(text, text.contains("Version added: added in acme.demo 1.1.0"))
        assertTrue("lookups open their page", html.contains("href=\"${DOCS_11}collections/ansible/builtin/file_lookup.html\""))
        assertTrue("other plugin types stay code", html.contains("<code>acme.demo.cb</code>") && !html.contains("cb_callback"))
        assertTrue("RV() opens the return anchor", html.contains("href=\"${DOCS_11}collections/acme/demo/thing_module.html#return-result/id\""))
        assertTrue("O() in an option opens the option's anchor", html.contains("#parameter-token"))
    }

    fun testNoLogAndNestedTrees() {
        assertTrue(plain(optionHtml("token")), plain(optionHtml("token")).contains("The value is not logged (no_log)."))
        val nested = optionHtml("nested")
        assertTrue(plain(nested), plain(nested).contains("Sub-options (1): inner : dict level : int"))
        assertTrue(nested.contains("href=\"psi_element://ansibility-option/acme.demo.thing/nested/inner/level\""))
        val inner = plain(optionHtml("nested", "inner"))
        assertTrue(inner, inner.startsWith("nested.inner : dict"))
        assertNull(inBackgroundReadAction { OptionDocumentationTarget.create(project, root, THING, listOf("nested", "nope")) })
    }

    private companion object {
        const val THING = "acme.demo.thing"
    }
}
