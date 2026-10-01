package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.completion.CompletionType
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.resolve.template.TemplateContextResolver

/**
 * Tiers T0–T6 and the root-wide fallback (plan A.5), members of locals and special variables, and the sites where
 * nothing of ours is offered, on a synthetic root: role `web` (spec, defaults, vars, tasks, templates) and role `db`,
 * applied together by one play.
 */
class JinjaVarCompletionTiersTest : JinjaCompletionTestCase() {
    private val webTasks = "site/roles/web/tasks/main.yml"

    override fun setUp() {
        super.setUp()
        createFile(
            "site/roles/web/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  web_sites:
                    type: list
                    elements: dict
                    required: true
                    options:
                      name: {type: str, required: true}
                      port: {type: int, default: 80}
                  web_mode: {type: str, choices: [a, b]}
            """,
        )
        createFile("site/roles/web/defaults/main.yml", "web_mode: a\nweb_extra_default: 1\n")
        createFile("site/roles/web/vars/main.yml", "web_internal: [x]\n")
        createFile("site/roles/db/defaults/main.yml", "db_port: 3306\n")
        createFile("site/roles/cache/defaults/main.yml", "cache_size: 64\n")
        createFile(
            "site/playbook.yml",
            """
            - hosts: all
              vars:
                play_level_var: 1
              roles:
                - web
                - db
            """,
        )
        createFile(
            webTasks,
            """
            - name: Early
              ansible.builtin.set_fact:
                web_fact: 1
            - name: Include
              ansible.builtin.include_tasks: sub.yml
            - name: Loop
              vars:
                task_var: 2
              ansible.builtin.debug:
                msg: "{{ PLACEHOLDER }}"
              loop: "{{ web_sites }}"
            - name: Render
              ansible.builtin.template:
                src: templates/site.conf.j2
                dest: /etc/site.conf
              loop: "{{ web_sites }}"
              vars:
                render_var: 3
              register: web_rendered
            """,
        )
        createFile("site/roles/web/tasks/sub.yml", "- name: Sub\n  ansible.builtin.command: /bin/true\n  register: web_sub_result\n")
        createFile("site/roles/web/tasks/late.yml", "- name: Late\n  ansible.builtin.command: /bin/true\n  register: web_late_result\n")
        createFile(
            "site/roles/web/templates/site.conf.j2",
            """
            {% set counter = 1 %}
            {% set ns = namespace(found=false) %}
            {% for srv in web_sites %}
            server {{ PLACEHOLDER }};
            {% endfor %}
            {% macro m(param_one) %}{{ par }}{% endmacro %}
            outer text
            """,
        )
        createFile("site/roles/web/templates/unrendered.j2", "{{ PLACEHOLDER }}\n")
    }

    fun testTaskTiersInOrder() {
        val items = completeAfterEdit(webTasks, 10, "PLACEHOLDER", "")
        val names = strings(items)
        assertContainsElements(names, "item", "task_var", "web_sites", "web_mode", "web_extra_default", "web_internal", "web_fact", "web_sub_result", "db_port", "play_level_var", "cache_size", "inventory_hostname")
        assertDoesntContain(names, "web_rendered", "render_var")
        assertTier(items, "item", Tier.LOOP)
        assertTier(items, "task_var", Tier.TASK)
        assertTier(items, "web_sites", Tier.ROLE)
        assertTier(items, "web_fact", Tier.ROLE)
        assertTier(items, "db_port", Tier.PLAY)
        assertTier(items, "play_level_var", Tier.PLAY)
        assertTier(items, "inventory_hostname", Tier.MAGIC)
        assertTier(items, "cache_size", Tier.ROOT)
        assertTrue("spec before defaults before vars before runtime",
            priority(item(items, "web_sites")) > priority(item(items, "web_extra_default")) &&
                priority(item(items, "web_extra_default")) > priority(item(items, "web_internal")) &&
                priority(item(items, "web_internal")) > priority(item(items, "web_fact")))
        assertEquals("dict | · loop over web_sites", describe(items, "item"))
        assertEquals(" · task vars", presentation(item(items, "task_var")).tailText)
        assertEquals("list[dict] | · required · web", describe(items, "web_sites"))
        assertTrue(presentation(item(items, "web_sites")).isItemTextBold)
        assertEquals("str | = a · web", describe(items, "web_mode"))
        assertEquals("list | = [x] · web vars", describe(items, "web_internal"))
        assertEquals(" · set_fact · web", presentation(item(items, "web_fact")).tailText)
        assertEquals("int | = 3306 · db", describe(items, "db_port"))
        assertEquals("int | = 64 · cache", describe(items, "cache_size"))
        assertTrue("task files not reached from main.yml count as earlier", "web_late_result" in names)
        assertDoesntContain(names, "ansible_managed")
    }

    fun testLoopItemMembersInTaskAndNoItemInTheLoopValue() {
        val members = completeAfterEdit(webTasks, 10, "PLACEHOLDER", "item.")
        assertEquals(listOf("name", "port"), strings(members))
        assertEquals("int | = 80", describe(members, "port"))
        reset(webTasks)
        val inLoop = completeAfterEdit(webTasks, 11, "web_sites", "it")
        assertDoesntContain(strings(inLoop), "item")
    }

    fun testTemplateLocalsLoopMembersAndNamespaces() {
        val template = "site/roles/web/templates/site.conf.j2"
        val items = completeAfterEdit(template, 4, "PLACEHOLDER", "")
        assertContainsElements(strings(items), "srv", "loop", "counter", "ns", "item", "render_var", "web_sites", "web_fact", "web_sub_result", "ansible_managed", "template_path")
        assertDoesntContain(strings(items), "web_rendered")
        assertTier(items, "srv", Tier.LOCAL)
        assertTier(items, "counter", Tier.LOCAL)
        assertTier(items, "item", Tier.LOOP)
        assertTier(items, "render_var", Tier.TASK)
        assertEquals("dict | · template local", describe(items, "srv"))
        assertEquals("namespace | · template local", describe(items, "ns"))
        val localCard = plain(html(popupDocumentation(item(items, "counter"))))
        assertTrue(localCard, "Jinja local (set), bound at roles/web/templates/site.conf.j2:1" in localCard)
        reset(template)

        val srv = completeAfterEdit(template, 4, "PLACEHOLDER", "srv.")
        assertEquals(listOf("name", "port"), strings(srv))
        reset(template)
        val loop = completeAfterEdit(template, 4, "PLACEHOLDER", "loop.")
        assertContainsElements(strings(loop), "index", "index0", "first", "last", "length", "revindex", "previtem")
        val loopCard = plain(html(popupDocumentation(item(loop, "index0"))))
        assertTrue(loopCard, "The current iteration, counting from 0." in loopCard)
        reset(template)
        val ns = completeAfterEdit(template, 4, "PLACEHOLDER", "ns.")
        assertEquals(listOf("found"), strings(ns))
        reset(template)
        val macro = completeAt(template, offsetAt(template, 6, "{{ par }}", "{{ par".length))
        assertContainsElements(strings(macro), "param_one")
    }

    fun testTemplateWithoutRenderContextFallsBackToRootWide() {
        val template = "site/roles/web/templates/unrendered.j2"
        assertEquals(emptyList<Any>(), inBackgroundReadAction { TemplateContextService.getInstance(project).renderContexts(vf(template)) })
        val items = completeAfterEdit(template, 1, "PLACEHOLDER", "")
        assertContainsElements(strings(items), "web_sites", "web_fact", "web_rendered", "db_port", "cache_size", "ansible_managed")
        assertDoesntContain(strings(items), "item")
    }

    fun testSpecialVariableMembers() {
        val version = completeAfterEdit(webTasks, 10, "PLACEHOLDER", "ansible_version.")
        assertContainsElements(strings(version), "full", "major", "minor")
        reset(webTasks)
        val host = completeAfterEdit(webTasks, 10, "PLACEHOLDER", "hostvars[inventory_hostname].ansible_")
        assertContainsElements(strings(host), "ansible_host", "ansible_port", "ansible_user", "ansible_facts")
    }

    fun testNothingOfOursOutsideVariableSites() {
        val template = "site/roles/web/templates/site.conf.j2"
        assertEquals(emptyList<String>(), strings(completeAt(template, offsetAt(template, 7, "outer", 3))))
        assertEquals("filter names are not variables", emptyList<String>(), strings(completeAfterEdit(webTasks, 10, "PLACEHOLDER", "web_sites | defa")))
        reset(webTasks)
        assertEquals("plain YAML values", emptyList<String>(), strings(completeAfterEdit(webTasks, 14, "templates/site.conf.j2", "templ")))
        reset(webTasks)
        assertEquals("vars-file keys", emptyList<String>(), strings(completeAt("site/roles/web/defaults/main.yml", 3)))
    }

    fun testRegistration() {
        assertTrue(CompletionSource.EP_NAME.extensionList.any { it is JinjaVarCompletionSource })
        assertTrue(LOOKUP_DOCS.extensionList.any { it is JinjaLookupDocumentationProvider })
        assertNotNull(TemplateContextResolver.getInstance(project))
    }

    fun testAutoPopupIsNotSkippedAtJinjaVariableSites() {
        val confidence = JinjaCompletionConfidence()
        fun answer(path: String, offset: Int): com.intellij.util.ThreeState {
            val file = psi(path)
            return inBackgroundReadAction { confidence.shouldSkipAutopopup(myFixture.editor, file.findElementAt(offset - 1) ?: file, file, offset) }
        }
        myFixture.configureFromExistingVirtualFile(vf(webTasks))
        assertEquals(com.intellij.util.ThreeState.NO, answer(webTasks, offsetAt(webTasks, 10, "PLACEHOLDER", 3)))
        assertEquals("a plain YAML value", com.intellij.util.ThreeState.UNSURE, answer(webTasks, offsetAt(webTasks, 14, "templates/site", 3)))
        assertEquals("a filter name", com.intellij.util.ThreeState.UNSURE, answer("site/roles/web/templates/site.conf.j2", offsetAt("site/roles/web/templates/site.conf.j2", 7, "outer", 2)))
        val beans = com.intellij.openapi.extensions.ExtensionPointName<com.intellij.codeInsight.completion.CompletionConfidenceEP>("com.intellij.completion.confidence").extensionList
        val ours = beans.indexOfFirst { it.implementationClass == JinjaCompletionConfidence::class.java.name }
        assertTrue("registered", ours >= 0)
        val skipInStrings = beans.indexOfFirst { it.implementationClass.endsWith("SkipAutopopupInStrings") }
        assertTrue("asked before the platform skips strings", skipInStrings < 0 || ours < skipInStrings)
    }

    fun testMemberAndKeyCharactersOpenThePopup() {
        assertTrue(com.intellij.codeInsight.editorActions.TypedHandlerDelegate.EP_NAME.extensionList.any { it is JinjaAutoPopupTypedHandler })
        myFixture.configureFromExistingVirtualFile(vf(webTasks))
        val start = offsetAt(webTasks, 10, "PLACEHOLDER")
        fun opens(text: String): Boolean {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val document = myFixture.editor.document
                val lineEnd = document.getLineEndOffset(document.getLineNumber(start))
                document.replaceString(start, lineEnd, "$text }}\"")
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            return inBackgroundReadAction { JinjaAutoPopupTypedHandler.opensPopup(psi(webTasks), start + text.length) }
        }
        assertTrue(opens("item."))
        assertTrue(opens("ansible_facts['"))
        assertTrue(opens("hostvars["))
        assertFalse("names use the platform's own auto-popup", opens("item"))
        assertFalse("a filter", opens("item | default('"))
        reset(webTasks)
    }

    fun testSmartCompletionIsNotOurs() {
        myFixture.configureFromExistingVirtualFile(vf(webTasks))
        myFixture.editor.caretModel.moveToOffset(offsetAt(webTasks, 10, "PLACEHOLDER"))
        val items = myFixture.complete(CompletionType.SMART)?.toList().orEmpty()
        assertEquals(emptyList<String>(), strings(items))
    }

    private fun assertTier(items: List<com.intellij.codeInsight.lookup.LookupElement>, name: String, tier: Tier) {
        val priority = priority(item(items, name))
        val next = Tier.entries.getOrNull(tier.ordinal - 1)?.base ?: Double.MAX_VALUE
        assertTrue("$name: $priority not in $tier", priority >= tier.base && priority < next)
    }
}
