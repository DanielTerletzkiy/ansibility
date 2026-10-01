package de.terletzkiy.ansibility.dochtml

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.markup.PluginRef

class MarkupHtmlTest : BasePlatformTestCase() {
    fun testCodeValuesAndEscaping() {
        assertEquals("quote <code>0644</code> &amp; <code>mode</code>", MarkupHtml.inline("quote C(0644) & O(mode)"))
    }

    fun testLinksAndBareUrls() {
        val html = MarkupHtml.inline("See U(https://example.org/x) and https://docs.example.org/a.html.")
        assertTrue(html, html.contains("<a href=\"https://example.org/x\">https://example.org/x</a>"))
        assertTrue(html, html.contains("<a href=\"https://docs.example.org/a.html\">https://docs.example.org/a.html</a>."))
    }

    fun testOptionLinksUseResolver() {
        val links = object : MarkupHtml.Links {
            override fun option(path: List<String>, plugin: String?) = "#parameter-" + path.joinToString("/")
        }
        assertEquals(
            "<a href=\"#parameter-healthcheck/interval\"><code>healthcheck.interval</code></a>",
            MarkupHtml.inline("O(healthcheck.interval)", links),
        )
    }

    fun testOptionAndReturnLinksReceiveThePluginWithItsType() {
        val seen = mutableListOf<PluginRef?>()
        val links = object : MarkupHtml.Links {
            override fun option(path: List<String>, plugin: PluginRef?): String {
                seen += plugin
                return "#o-" + (plugin?.type ?: "page") + "-" + path.joinToString("/")
            }

            override fun returnValue(path: List<String>, plugin: PluginRef?): String {
                seen += plugin
                return "#r-" + path.joinToString("/")
            }
        }
        val html = MarkupHtml.inline("O(ansible.builtin.file#lookup:_terms), O(mode) and RV(community.docker.docker_container#module:container)", links)
        assertEquals(listOf(PluginRef("ansible.builtin.file", "lookup"), null, PluginRef("community.docker.docker_container", "module")), seen)
        assertTrue(html, html.contains("<a href=\"#o-lookup-_terms\"><code>_terms</code></a>"))
        assertTrue(html, html.contains("<a href=\"#o-page-mode\"><code>mode</code></a>"))
        assertTrue(html, html.contains("<a href=\"#r-container\"><code>container</code></a>"))
    }

    fun testNameOnlyResolversStillGetTheFqcn() {
        val seen = mutableListOf<String?>()
        val links = object : MarkupHtml.Links {
            override fun option(path: List<String>, plugin: String?): String? {
                seen += plugin
                return null
            }
        }
        assertEquals("<code>_terms</code>", MarkupHtml.inline("O(ansible.builtin.file#lookup:_terms)", links))
        assertEquals(listOf<String?>("ansible.builtin.file"), seen)
    }

    fun testParagraphs() {
        assertEquals("<p>a</p><p><b>b</b></p>", MarkupHtml.paragraphs(listOf("a", "", "B(b)")))
    }
}
