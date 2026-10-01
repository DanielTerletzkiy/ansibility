package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.filetype.J2Mappings
import org.jetbrains.yaml.YAMLFileType

/**
 * Hover and Ctrl+B on loop variables (plan F2.4 "the same targets as F1.2 and F1.4", F1.7, X78): members of `item`
 * and of a `loop_control.loop_var` document and navigate to the nested option of the iterated variable's element, in
 * tasks (the task's own loop) and in templates (the loops of the rendering tasks); the bare variable goes to the loop.
 */
class VarLoopItemTest : VarsTestCase() {
    private val template = "golden/roles/grafana/templates/nginx/main.site.conf.j2"
    private val nginxTasks = "golden/roles/grafana/tasks/nginx.yml"
    private val alerting = "golden/roles/grafana/tasks/alerting.yml"
    private val spec = "golden/roles/grafana/meta/argument_specs.yml"

    override fun setUp() {
        super.setUp()
        copyInfra("golden/roles/grafana")
    }

    private fun targets(path: String, line: Int, marker: String, delta: Int = 1): List<String> =
        gotoTargets(path, offsetAt(path, line, marker, delta)).map(::describe)

    fun testItemMemberInATemplateDocumentsTheNestedOptionOfTheIteratedVariable() {
        val card = text(html(hover(template, offsetAt(template, 14, "cert_file", 2))))
        assertTrue(card, card.startsWith("grafana_nginx_sites.floating.ssl.cert_file : str"))
        assertTrue(card, "Server certificate filename under /etc/ssl." in card)
        assertTrue(card, "item is one element of grafana_nginx_sites, iterated by the task at roles/grafana/tasks/nginx.yml:33." in card)
        val hint = hint(hover(template, offsetAt(template, 14, "cert_file", 2)))
        assertTrue(hint, hint.startsWith("grafana_nginx_sites.floating.ssl.cert_file: str"))
        assertTrue(hint, "grafana (required)" in hint)
    }

    fun testItemMemberInATemplateNavigatesToTheNestedOptionKey() {
        assertEquals(listOf("$spec:364"), targets(template, 14, "cert_file", 2))
        assertEquals("a member of a member", listOf("$spec:355"), targets(template, 8, "ssl.port", 1))
    }

    fun testBareItemInATemplateGoesToTheLoopOfTheRenderingTask() {
        // X78: golden/roles/grafana/templates/nginx/main.site.conf.j2:14 → golden/roles/grafana/tasks/nginx.yml:43.
        val targets = gotoTargets(template, offsetAt(template, 14, "item.floating", 1))
        assertEquals(listOf("$nginxTasks:43"), targets.map(::describe))
        val target = targets.single() as VarTargetElement
        assertEquals("item", target.name)
        assertEquals("loop of item · roles/grafana/tasks/nginx.yml:43", target.locationString)

        val card = text(html(hover(template, offsetAt(template, 14, "item.floating", 1))))
        assertTrue("the bare item documents the iterated variable: $card", card.startsWith("grafana_nginx_sites : list[dict]"))
        assertTrue(card, "item is one element of grafana_nginx_sites" in card)
    }

    fun testItemMemberInATaskUsesTheTasksLoop() {
        val card = text(html(hover(nginxTasks, offsetAt(nginxTasks, 29, "client_cert_ca", 2))))
        assertTrue(card, card.startsWith("grafana_nginx_sites.floating.ssl.client_cert_ca"))
        assertTrue(card, "iterated by the task at roles/grafana/tasks/nginx.yml:20." in card)
        val key = targets(nginxTasks, 29, "client_cert_ca", 2).single()
        assertTrue(key, key.startsWith("$spec:"))
        assertEquals(
            "client_cert_ca:",
            runReadActionBlocking { psi(spec).viewProvider.contents.toString().lines()[key.substringAfterLast(':').toInt() - 1].trim() },
        )
        assertEquals("the bare item goes to the task's loop", listOf("$nginxTasks:31"), targets(nginxTasks, 29, "item.floating", 1))
    }

    fun testLoopVarNamedItems() {
        val card = text(html(hover(alerting, offsetAt(alerting, 33, "grafana_alert.name", "grafana_alert.".length + 1))))
        assertTrue(card, card.startsWith("grafana_alerting.name : str"))
        assertTrue(card, "grafana_alert is one element of grafana_alerting" in card)
        assertEquals(listOf("$spec:255"), targets(alerting, 33, "grafana_alert.name", "grafana_alert.".length + 1))
        assertEquals("the bare loop_var goes to the loop", listOf("$alerting:34"), targets(alerting, 33, "grafana_alert.name", 1))
    }

    fun testInsideTheLoopExpressionTheLoopVariableIsNotDefined() {
        val file = "golden/roles/grafana/tasks/self.yml"
        createFile(file, "- ansible.builtin.debug:\n    msg: hi\n  loop: \"{{ item | default([]) }}\"\n")
        val site = classify(file, offsetAt(file, 3, "item", 1))
        assertNotNull(site)
        assertNull(runReadActionBlocking { LoopItems.bindingAt(project, vf(file), offsetAt(file, 3, "item", 1), "item") })
    }

    fun testUndocumentedLoopsKeepTheLoopNoteAlsoInTemplates() {
        copyVarsData("site")
        val tasks = "site/roles/web/tasks/main.yml"
        val loop = text(html(hover(tasks, offsetAt(tasks, 12, "item", 1))))
        assertTrue(loop, "loop variable of the task at roles/web/tasks/main.yml:10" in loop)
        assertEquals("an untyped loop: Ctrl+B goes to the loop keyword", listOf("$tasks:13"), targets(tasks, 12, "item", 1))

        // A template rendered in a loop over a literal list: the note names the rendering task.
        createFile("site/roles/web/tasks/render.yml", "- ansible.builtin.template:\n    src: page.j2\n    dest: /x\n  loop: [a, b]\n")
        val page = "site/roles/web/templates/page.j2"
        createFile(page, "{{ item }}\n")
        val note = text(html(hover(page, offsetAt(page, 1, "item", 1))))
        assertTrue(note, "loop variable of the task at roles/web/tasks/render.yml:1" in note)
        assertEquals(listOf("site/roles/web/tasks/render.yml:4"), targets(page, 1, "item", 1))
    }

    fun testTemplatesOfAnyFileTypeAndTheCardsLinks() {
        for (type in listOf<FileType>(YAMLFileType.YML, PlainTextFileType.INSTANCE, AnsibleJinjaFileType)) {
            withJ2As(type) {
                assertEquals(type, psi(template).fileType)
                val target = hover(template, offsetAt(template, 14, "item.floating", 1))
                val card = text(html(target))
                assertTrue(card, card.startsWith("grafana_nginx_sites : list[dict]"))
                assertEquals(listOf("$spec:364"), targets(template, 14, "cert_file", 2))
                // The Options table links to the element's sub-options, relative to the iterated variable.
                val sub = inBackgroundReadAction { VarDocumentationLinkHandler().resolveTarget(target, VarLinks.option(listOf("floating", "ssl"))) }!!
                val subCard = text(html(sub))
                assertTrue(subCard, subCard.startsWith("grafana_nginx_sites.floating.ssl : dict"))
            }
        }
    }

    fun testOtherNamesKeepTheirOwnCards() {
        val card = text(html(hover(template, offsetAt(template, 11, "nginx_http2", 1))))
        assertTrue(card, card.startsWith("nginx_http2"))
        assertFalse(card, "is one element of" in card)
    }

    /**
     * Types `.j2` files inside roots as [type] for [action] (`*.j2` mapped to it, with "Keep YAML for .j2" on since the
     * M5 overrider claims them otherwise; [de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType] keeps the claim
     * over a `*.j2 → YAML` mapping), then restores the mapping and the settings.
     */
    private fun withJ2As(type: FileType, action: () -> Unit) = J2Mappings.withJ2As(type, action)
}
