package de.terletzkiy.ansibility.docs

import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.dochtml.MarkupHtml
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/** `O()`/`RV()` references that name a plugin open that plugin type's page (plan F5.1). */
@RequiresInfraFixture
class DocLinksTest : DocsTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra(HAPROXY)
    }

    private fun render(markup: String, module: String? = "ansible.builtin.copy"): String = inBackgroundReadAction {
        MarkupHtml.inline(markup, DocLinks(root(CONFIGURE), AnsibleDocService.getInstance(project), module))
    }

    fun testLookupOptionsOpenTheLookupPage() {
        val html = render("O(ansible.builtin.file#lookup:_terms)")
        assertTrue(html, html.contains("href=\"${DOCS_11}collections/ansible/builtin/file_lookup.html#parameter-_terms\""))
        assertFalse("not the file module's page", html.contains("file_module.html"))
    }

    fun testModuleReferencesAndThePageItself() {
        val html = render("O(src), O(community.docker.docker_container#module:mounts[].type) and RV(community.docker.docker_container#module:container)")
        assertTrue(html, html.contains("href=\"${DOCS_11}collections/ansible/builtin/copy_module.html#parameter-src\""))
        assertTrue(html, html.contains("href=\"${DOCS_11}collections/community/docker/docker_container_module.html#parameter-mounts/type\""))
        assertTrue(html, html.contains("href=\"${DOCS_11}collections/community/docker/docker_container_module.html#return-container\""))
    }

    fun testFilterAndTestOptionsOpenTheirPages() {
        val html = render("O(ansible.builtin.to_json#filter:indent) and O(ansible.builtin.version#test:strict)")
        assertTrue(html, html.contains("href=\"${DOCS_11}collections/ansible/builtin/to_json_filter.html#parameter-indent\""))
        assertTrue(html, html.contains("href=\"${DOCS_11}collections/ansible/builtin/version_test.html#parameter-strict\""))
    }

    fun testOtherPluginTypesAndPagelessReferencesStayCode() {
        val html = render("O(acme.demo.cb#callback:x), O(acme.demo.web#role:main:port) and O(mode)", module = null)
        assertEquals("<code>x</code>, <code>port</code> and <code>mode</code>", html)
    }
}
