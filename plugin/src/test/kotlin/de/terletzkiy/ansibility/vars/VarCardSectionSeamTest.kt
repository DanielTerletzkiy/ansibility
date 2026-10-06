package de.terletzkiy.ansibility.vars

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.testFramework.ExtensionTestUtil
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The card seam (plan amendment R7/R8, CT0 → V3): the variable card calls every registered [CardSection] with
 * `CardSubject.Variable` and a `CardContext`, and puts TOP chunks directly after the definition block, SECTION rows
 * inside the sections table after the built-in rows and BOTTOM chunks after the table. The Ctrl-hover hint never runs
 * the sections.
 */
@RequiresInfraFixture
class VarCardSectionSeamTest : VarsTestCase() {
    /** A section that records its calls and marks its placement in the HTML. */
    private class Recording(override val placement: CardPlacement) : CardSection {
        val calls = CopyOnWriteArrayList<Pair<CardSubject, CardContext>>()

        override fun section(subject: CardSubject, context: CardContext): HtmlChunk {
            calls += subject to context
            val marker = "SEAM-${placement.name}"
            return if (placement == CardPlacement.SECTION) CardSection.row(marker, HtmlChunk.text("$marker-content")) else HtmlChunk.div().addText(marker)
        }
    }

    private val top = Recording(CardPlacement.TOP)
    private val section = Recording(CardPlacement.SECTION)
    private val bottom = Recording(CardPlacement.BOTTOM)
    private val all get() = listOf(top, section, bottom)

    override fun setUp() {
        super.setUp()
        ExtensionTestUtil.maskExtensions(CardSection.EP_NAME, all, testRootDisposable)
        copyInfra("repos/falcon")
    }

    private val defaults = "repos/falcon/ansible/roles/postfix/defaults/main.yml"
    private val prodVars = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"

    fun testEachPlacementLandsWhereItBelongs() {
        val html = html(hover(defaults, offsetAt(defaults, 2, "postfix_relayhost")))
        val definitionEnd = html.indexOf(DocumentationMarkup.DEFINITION_END)
        val sectionsStart = html.indexOf(DocumentationMarkup.SECTIONS_START)
        val sectionsEnd = html.lastIndexOf(DocumentationMarkup.SECTIONS_END)
        assertTrue(definitionEnd in 0 until sectionsStart)

        val topAt = html.indexOf("SEAM-TOP")
        assertTrue("TOP directly after the definition block", topAt > definitionEnd && html.substring(definitionEnd + DocumentationMarkup.DEFINITION_END.length, topAt) == "<div>")
        assertTrue("TOP before the description", topAt < sectionsStart && (html.indexOf(DocumentationMarkup.CONTENT_START).let { it < 0 || it > topAt }))

        val rowAt = html.indexOf("SEAM-SECTION")
        assertTrue("SECTION inside the table", rowAt in sectionsStart until sectionsEnd)
        for (builtIn in listOf("Runtime default", "Set in", "This definition")) {
            val at = html.indexOf(builtIn)
            if (at >= 0) assertTrue("after the built-in row $builtIn", rowAt > at)
        }
        assertTrue(html.contains("SEAM-SECTION-content"))

        val bottomAt = html.indexOf("SEAM-BOTTOM")
        assertTrue("BOTTOM after the table", bottomAt > sectionsEnd)
    }

    fun testDefinitionAndReferenceSubjectsAndContexts() {
        html(hover(defaults, offsetAt(defaults, 2, "postfix_relayhost")))
        val (definitionSubject, definitionContext) = section.calls.single()
        definitionSubject as CardSubject.Variable
        assertEquals("postfix_relayhost", definitionSubject.name)
        assertEmpty(definitionSubject.path)
        assertFalse(definitionSubject.local)
        assertEquals(vf("repos/falcon/ansible"), definitionSubject.root.dir)
        assertEquals("roles/postfix/defaults/main.yml:2", describe(definitionSubject.definition!!).substringAfter("ansible/"))
        assertEquals(vf(defaults), definitionContext.file)
        assertEquals(offsetAt(defaults, 2, "postfix_relayhost"), definitionContext.offset)
        assertEquals(project, definitionContext.project)
        assertTrue("every placement got the same subject", all.all { it.calls.single().first == definitionSubject })

        all.forEach { it.calls.clear() }
        val referenceOffset = offsetAt(prodVars, 471, "postfix_relayhost", 2)
        html(hover(prodVars, referenceOffset))
        val (referenceSubject, referenceContext) = section.calls.single()
        referenceSubject as CardSubject.Variable
        assertEquals("postfix_relayhost", referenceSubject.name)
        assertEquals(definitionSubject.root, referenceSubject.root)
        assertEquals(vf(prodVars), referenceContext.file)
    }

    fun testTheCtrlHoverHintNeverRunsSections() {
        val target = hover(defaults, offsetAt(defaults, 2, "postfix_relayhost"))
        assertTrue(hint(target).startsWith("postfix_relayhost"))
        assertTrue(all.all { it.calls.isEmpty() })
        html(target)
        assertTrue(all.all { it.calls.size == 1 })
    }

    fun testACardReachedThroughALinkHasNoOffset() {
        val target = hover(defaults, offsetAt(defaults, 2, "postfix_relayhost")) as VarDocumentationTarget
        val nested = target.withPath(listOf("anything"))
        assertEquals(-1, nested.cardContext().offset)
        assertEquals(-1, (nested.createPointer().dereference() as VarDocumentationTarget).cardContext().offset)
        assertEquals(offsetAt(defaults, 2, "postfix_relayhost"), target.cardContext().offset)
    }
}
