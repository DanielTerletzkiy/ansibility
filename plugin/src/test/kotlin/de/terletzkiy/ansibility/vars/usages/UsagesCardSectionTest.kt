package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject

/**
 * The card's "Used in" row (F1.10, D-FU7; `cardSection` `ansibilityUsages`): counts from one index lookup, a link that
 * runs Find Usages, never on the Ctrl-hover hint, no row for Jinja locals and loop variables (`item` included).
 */
class UsagesCardSectionTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyVarsData("site")
    }

    fun testTheCardCountsTheUsesAndLinksToFindUsages() {
        val target = hover(TEMPLATE, offsetAt(TEMPLATE, 1, "web_port", 1))
        val html = html(target)
        assertEquals("Used in 1 template · 3 tasks · 2 conditions — Show usages", text(section(html, "Used in", "</tr>").let { "Used in$it" }).trim())
        val link = LINK.find(html)?.groupValues?.get(1) ?: error("no Show usages link in $html")

        assertNull("the link resolves to nothing (no card history entry)", runReadActionBlocking { UsagesCardLinkHandler().resolveLink(target, link) })
        // From the role's template: no molecule override while Molecule is hidden (R20/D153).
        assertEquals(VarFindUsagesTest.WEB_PORT_PRODUCTION, describeUsages(awaitUsageView()))
    }

    fun testTheRowComesLastAndNeverOnTheHint() {
        val target = hover(DEFAULTS, offsetAt(DEFAULTS, 3, "web_port", 1))
        val html = html(target)
        val usedIn = html.indexOf("Used in")
        assertTrue(html, usedIn > html.indexOf("Set in"))
        assertFalse("the Ctrl-hover hint has no sections", hint(target).contains("Used in"))
    }

    fun testAVariableWithoutUsesSaysSo() {
        val html = html(hover(DEFAULTS, offsetAt(DEFAULTS, 8, "web_unspecced", 1)))
        assertTrue(text(html), text(html).contains("Used in no uses in site — Show usages"))
    }

    /** Reads by name (FU2) are reads: `hostvars[h].x` in a template, `vars['x']` in a task, `lookup('vars', 'x')` in a condition. */
    fun testIndirectReadsCountAsReads() {
        createFile("site/roles/web/templates/indirect.j2", "{{ hostvars[h].web_unspecced }}\n")
        createFile(
            "site/roles/web/tasks/indirect.yml",
            """
            ---
            - name: By name
              ansible.builtin.debug:
                msg: "{{ vars['web_unspecced'] }}"
              when: lookup('vars', 'web_unspecced') | bool
            """.trimIndent() + "\n",
        )
        val html = html(hover(DEFAULTS, offsetAt(DEFAULTS, 8, "web_unspecced", 1)))
        assertTrue(text(html), text(html).contains("Used in 1 template · 1 task · 1 condition — Show usages"))
    }

    fun testJinjaLocalsGetNoRow() {
        assertFalse(text(html(hover(TEMPLATE, offsetAt(TEMPLATE, 5, "local_name", 1)))).contains("Used in"))
    }

    fun testTheSectionDirectly() {
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(TEMPLATE))!!.root }
        val context = CardContext(project, vf(TEMPLATE), -1)
        val section = CardSection.EP_NAME.extensionList.filterIsInstance<UsagesCardSection>().single()
        assertEquals(CardPlacement.SECTION, section.placement)
        val chunk = inBackgroundReadAction { section.section(CardSubject.Variable(root, "web_port", emptyList(), null), context) }
        assertTrue(chunk.toString().contains("psi_element://ansibility-usages/show/-1/"))
        assertNull(inBackgroundReadAction { section.section(CardSubject.Variable(root, "x", emptyList(), null, local = true), context) })
        assertNull("item is a loop's, never counted across the root", inBackgroundReadAction { section.section(CardSubject.Variable(root, "item", emptyList(), null), context) })
        assertNull(inBackgroundReadAction { section.section(CardSubject.Role(root, "web"), context) })
        assertEquals(
            UsagesCardLinks.Link(vf(TEMPLATE).url, -1, "web_port"),
            UsagesCardLinks.parse(UsagesCardLinks.show(vf(TEMPLATE), -1, "web_port")),
        )
        assertNull(UsagesCardLinks.parse("psi_element://ansibility-var/def/1/x"))
    }

    companion object {
        const val TEMPLATE = VarFindUsagesTest.TEMPLATE
        const val DEFAULTS = VarFindUsagesTest.DEFAULTS
        val LINK = Regex("""href=['"](psi_element://ansibility-usages/[^'"]+)['"]""")
    }
}
