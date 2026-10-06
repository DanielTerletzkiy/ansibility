package de.terletzkiy.ansibility.render

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.JinjaBlockSite
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.TemplatedValueSite
import de.terletzkiy.ansibility.context.host.HostContextTestCase
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.render.hover.RenderedValueCardSection
import de.terletzkiy.ansibility.settings.AnsibilityWorkspaceState
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/** Rendered values in hover (plan amendment R11, F11.1) on falcon's `keepalived` role (prod-prod1 and prod-prod2). */
@RequiresInfraFixture
class RenderedValueHoverTest : HostContextTestCase() {
    override fun addFixtureFiles() {
        add("$ROLE/defaults/main.yml", "---\nkeepalived_priority: 100\n")
        add(
            TASKS,
            """
            ---
            - name: "Deploy {{ item | default('NO-ITEM') }}"
              ansible.builtin.template:
                src: "checks/{{ item.template }}"
                dest: "/etc/keepalived/{{ item.name }}.sh"
              loop:
                - { name: a, template: check.sh.j2 }
                - { name: b, template: check.sh.j2 }
              loop_control:
                label: "{{ item.name }}"
            - name: Report
              ansible.builtin.debug:
                msg: "priority={{ keepalived_priority }} on {{ inventory_hostname }}"
              when: keepalived_priority | int > 50
            """,
        )
        add(TEMPLATE, "port={{ keepalived_priority + 1 }}\n")
        add(
            BLOCKS,
            "{% set peers = ['p1', 'p2', 'p3', 'p4'] %}\n{% for p in peers %}\n{{ p }}\n{% else %}\nnone\n{% endfor %}\n" +
                "{% if keepalived_priority > 50 %}\nhigh\n{% endif %}\n{% do x.append(1) %}\n",
        )
    }

    override fun tearDown() {
        try {
            AnsibilityWorkspaceState.getInstance(project).loadState(AnsibilityWorkspaceState.StateBean())
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testLiteralPartOfALoopedValueRendersEveryItem() {
        val html = hover(TASKS, "/etc/keepalived/", 2)
        assertTrue(html, "/etc/keepalived/a.sh" in html)
        assertTrue(html, "/etc/keepalived/b.sh" in html)
        assertTrue(html, "2 items" in html)
        assertTrue(html, "item 1 (a)" in html)
    }

    fun testTaskNameIsTemplatedBeforeTheLoop() {
        val site = site(TASKS, "Deploy", 1)
        assertFalse(site.loopApplies)
        val html = hover(TASKS, "Deploy", 1)
        assertTrue(html, "Deploy NO-ITEM" in html)
    }

    fun testValuePerHostAndConditionOutcome() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        val html = hover(TASKS, "priority=", 1)
        assertTrue(html, "on prod-prod1" in html)
        val condition = site(TASKS, "| int", 1)
        assertTrue(condition.expression)
        assertTrue(hover(TASKS, "| int", 1), "True" in hover(TASKS, "| int", 1))
    }

    fun testTemplateSpanRendersThroughItsTask() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        val site = site(TEMPLATE, "+ 1", 0)
        assertEquals("{{ keepalived_priority + 1 }}", site.text)
        val html = hover(TEMPLATE, "+ 1", 0)
        assertTrue(html, "151" in html)
    }

    fun testVariableCardGetsTheWholeValueRendered() {
        val file = vf(TASKS)
        val text = FileDocumentManager.getInstance().getDocument(file)!!.text
        val offset = text.indexOf("item.name }}.sh") + 1
        val chunk = runReadActionBlocking {
            RenderedValueCardSection().section(CardSubject.Variable(root(FALCON), "item", listOf("name"), null), CardContext(project, file, offset))
        }
        assertNotNull(chunk)
        assertTrue(chunk.toString(), "/etc/keepalived/a.sh" in chunk.toString())
    }

    fun testIfTagShowsTheConditionOutcome() {
        context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        val html = block(BLOCKS, "if keepalived", 1)
        assertTrue(html, "True" in html)
        val end = block(BLOCKS, "endif", 1)
        assertTrue(end, "Ends {% if keepalived_priority &gt; 50 %}" in end || "Ends {% if keepalived_priority > 50 %}" in end)
    }

    fun testForSetElseAndUnknownTags() {
        val loop = block(BLOCKS, "for p", 1)
        assertTrue(loop, "Loops p over peers" in loop)
        assertTrue(loop, ">4<" in loop || "<code>4</code>" in loop)
        val set = block(BLOCKS, "set peers", 1)
        assertTrue(set, "p1" in set)
        val otherwise = block(BLOCKS, "else", 1)
        assertTrue(otherwise, "sequence is empty" in otherwise)
        val unknown = block(BLOCKS, "do x", 1)
        assertTrue(unknown, "Encountered unknown tag &#39;do&#39;" in unknown)
    }

    private fun block(path: String, anchor: String, shift: Int): String = runReadActionBlocking {
        val file = vf(path)
        val offset = FileDocumentManager.getInstance().getDocument(file)!!.text.indexOf(anchor) + shift
        val psi = psiManager.findFile(file)!!
        val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOf { it.classify(psi, offset) }
        assertTrue(site.toString(), site is JinjaBlockSite)
        val target = SiteDocumentation.EP_NAME.extensionList.firstNotNullOf { it.documentation(site, psi) }
        target.computeDocumentation().toString() + target.computeDocumentationHint()
    }

    private fun site(path: String, anchor: String, shift: Int): TemplatedValueSite = runReadActionBlocking {
        val file = vf(path)
        val offset = FileDocumentManager.getInstance().getDocument(file)!!.text.indexOf(anchor) + shift
        val psi = psiManager.findFile(file)!!
        SiteClassifier.EP_NAME.extensionList.firstNotNullOf { it.classify(psi, offset) } as TemplatedValueSite
    }

    private fun hover(path: String, anchor: String, shift: Int): String {
        val site = site(path, anchor, shift)
        return runReadActionBlocking {
            val psi = psiManager.findFile(vf(path))!!
            val target = SiteDocumentation.EP_NAME.extensionList.firstNotNullOf { it.documentation(site, psi) }
            target.computeDocumentation().toString() + target.computeDocumentationHint()
        }
    }

    private companion object {
        const val ROLE = "$FALCON/roles/keepalived"
        const val TASKS = "$ROLE/tasks/main.yml"
        const val TEMPLATE = "$ROLE/templates/checks/check.sh.j2"
        const val BLOCKS = "$ROLE/templates/blocks.conf.j2"
    }
}
