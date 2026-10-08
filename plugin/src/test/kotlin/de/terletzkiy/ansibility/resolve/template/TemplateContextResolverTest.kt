package de.terletzkiy.ansibility.resolve.template

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RenderKind
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.semantics.schema.OptionType

/** [TemplateContextResolver] (plan A.7 query-time rules, F2.3): which tasks render a template. */
@RequiresInfraFixture
class TemplateContextResolverTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    private fun refresh() = (AnsibleWorkspaceImpl.getInstance(project) ?: error("no workspace")).structureChanged()

    private fun create(path: String, text: String): VirtualFile = myFixture.tempDirFixture.createFile(path, text.trimIndent() + "\n").also { refresh() }

    private fun contexts(path: String): List<RenderContext> = runReadActionBlocking {
        TemplateContextService.getInstance(project).renderContexts(myFixture.findFileInTempDir(path) ?: error("missing $path"))
    }

    private fun line(context: RenderContext): Int = runReadActionBlocking {
        val text = String(context.site.file.contentsToByteArray())
        text.substring(0, context.site.offset).count { it == '\n' } + 1
    }

    private val tasks = "site/roles/web/tasks/main.yml"

    override fun setUp() {
        super.setUp()
        create(
            "site/roles/web/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  web_sites:
                    type: list
                    elements: dict
                    options:
                      name: {type: str, required: true}
                      port: {type: int}
                  web_main_template: {type: str}
            """,
        )
        create("site/roles/web/defaults/main.yml", "web_sites: []\nweb_main_template: templates/whole.j2")
        for (name in listOf("a.conf.j2", "b.conf.j2", "whole.j2", "outer.j2", "part.j2", "imported.j2", "shared.j2", "lk.j2", "orphan.j2", "sites/one.conf.j2", "sites/two.conf.j2", "sites/readme.txt")) {
            val text = when (name) {
                "outer.j2" -> "{% include 'part.j2' %}\n{% import 'imported.j2' as m %}\n{% from 'shared.j2' import x with context %}\n"
                else -> "{{ item }}\n"
            }
            create("site/roles/web/templates/$name", text)
        }
        create(
            tasks,
            """
            - name: Static with prefix
              ansible.builtin.template:
                src: templates/a.conf.j2
                dest: /etc/a.conf
            - name: Static without prefix
              vars:
                task_level: 1
              ansible.builtin.template:
                src: b.conf.j2
                dest: /etc/b.conf
            - name: Whole variable
              ansible.builtin.template:
                src: "{{ web_main_template }}"
                dest: /etc/whole
            - name: Outer, in a loop
              block:
                - name: Render outer
                  ansible.builtin.template:
                    src: templates/outer.j2
                    dest: /etc/outer
                  loop: "{{ web_sites }}"
              vars:
                block_level: 2
            - name: Dynamic without choices
              ansible.builtin.template:
                src: "templates/sites/{{ item.name }}.conf.j2"
                dest: "/etc/sites/{{ item.name }}"
              loop: "{{ web_sites }}"
            - name: Lookup
              ansible.builtin.debug:
                msg: "{{ lookup('template', 'lk.j2', template_vars=dict(extra=1, other=[1, 2])) }}"
            """,
        )
    }

    fun testStaticSrcResolvesLikeFindNeedle() {
        val a = contexts("site/roles/web/templates/a.conf.j2").single()
        assertEquals(RenderKind.STATIC, a.kind)
        assertEquals("web", a.role?.name)
        assertEquals(1, line(a))
        assertNull(a.loop)
        val b = contexts("site/roles/web/templates/b.conf.j2").single()
        assertEquals(5, line(b))
        assertEquals(listOf("task_level"), b.taskVars)
    }

    fun testWholeVariableSrcUsesTheVariablesValues() {
        val whole = contexts("site/roles/web/templates/whole.j2").single()
        assertEquals(RenderKind.WHOLE_VAR, whole.kind)
        assertEquals(11, line(whole))
    }

    fun testTheDocumentedSpecDefaultIsNoCandidateValue() {
        // Plan amendment R23 (D175): ansible-core never applies an argument_specs `default:`, so it names no template.
        create("site/roles/doc/meta/argument_specs.yml", "argument_specs:\n  main:\n    options:\n      doc_template: {type: str, default: templates/documented.j2}")
        create("site/roles/doc/templates/documented.j2", "{{ item }}")
        create("site/roles/doc/tasks/main.yml", "- name: Whole\n  ansible.builtin.template:\n    src: \"{{ doc_template }}\"\n    dest: /etc/doc")
        assertEquals(emptyList<RenderContext>(), contexts("site/roles/doc/templates/documented.j2"))
    }

    fun testDynamicPrefixWithoutChoicesUsesTheDirectoryListing() {
        for (name in listOf("one", "two")) {
            val context = contexts("site/roles/web/templates/sites/$name.conf.j2").single()
            assertEquals(RenderKind.DYNAMIC_PREFIX, context.kind)
            assertEquals(OptionType.Int, context.loop?.item?.options?.get("port")?.type)
            assertEquals("web_sites", context.loop?.sourceVariable)
        }
        assertEquals("the suffix must match", emptyList<RenderContext>(), contexts("site/roles/web/templates/sites/readme.txt"))
    }

    fun testIncludeInheritsTheIncludingTemplatesContexts() {
        val outer = contexts("site/roles/web/templates/outer.j2").single()
        assertEquals("item", outer.loop?.loopVar)
        assertEquals(listOf("block_level"), outer.taskVars)

        val part = contexts("site/roles/web/templates/part.j2").single()
        assertEquals(RenderKind.INCLUDE, part.kind)
        assertEquals(listOf("outer.j2"), part.via.map { it.name })
        assertEquals("item", part.loop?.loopVar)
        assertEquals(OptionType.Str, part.loop?.item?.options?.get("name")?.type)
        assertEquals("the rendering task stays the task", outer.taskSite, part.taskSite)

        assertEquals("a plain import passes no context", emptyList<RenderContext>(), contexts("site/roles/web/templates/imported.j2"))
        assertEquals("from … import … with context does", listOf("outer.j2"), contexts("site/roles/web/templates/shared.j2").single().via.map { it.name })
    }

    fun testLookupTemplateVarsAreTaskVars() {
        val lookup = contexts("site/roles/web/templates/lk.j2").single()
        assertEquals(RenderKind.LOOKUP, lookup.kind)
        assertEquals(listOf("extra", "other"), lookup.taskVars)
    }

    fun testTemplatesWithoutRendersHaveNoContext() {
        assertEquals(emptyList<RenderContext>(), contexts("site/roles/web/templates/orphan.j2"))
    }

    fun testOtherRootsNeverRenderThisRootsTemplates() {
        create("other/ansible.cfg", "[defaults]\n")
        create("other/roles/web/tasks/main.yml", "- name: Same name\n  ansible.builtin.template:\n    src: templates/orphan.j2\n    dest: /x\n")
        create("other/roles/web/templates/orphan.j2", "x\n")
        assertEquals(emptyList<RenderContext>(), contexts("site/roles/web/templates/orphan.j2"))
        assertEquals(1, contexts("other/roles/web/templates/orphan.j2").size)
    }

    fun testDynamicPrefixWithChoicesOnTheGrafanaFixture() {
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/golden/roles/grafana", "golden/roles/grafana")
        refresh()
        val context = contexts("golden/roles/grafana/templates/nginx/main.site.conf.j2").single()
        assertEquals(RenderKind.DYNAMIC_PREFIX, context.kind)
        assertEquals(33, line(context))
        assertEquals("grafana_nginx_sites", context.loop?.sourceVariable)
        assertEquals(setOf("port", "cert_file", "key_file", "trusted_intermediate", "client_cert_ca", "client_cert_ca_src"),
            context.loop?.item?.options?.get("floating")?.options?.get("ssl")?.options?.keys)
        assertEquals(1, contexts("golden/roles/grafana/templates/nginx/main.site.mcp.conf.j2").size)
        assertEquals("a static src elsewhere", RenderKind.STATIC, contexts("golden/roles/grafana/templates/nginx/logrotate.conf.j2").single().kind)
    }

    fun testFileglobLoopOnTheAlloyFixture() {
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/golden/roles/alloy", "golden/roles/alloy")
        refresh()
        val context = contexts("golden/roles/alloy/templates/config-base.alloy.j2").single()
        assertEquals(RenderKind.FILEGLOB, context.kind)
        assertEquals(9, line(context))
        assertEquals("alloy", context.role?.name)
        assertTrue(contexts("golden/roles/alloy/templates/systemd.override.conf.j2").none { it.kind == RenderKind.FILEGLOB })
    }

    fun testTemplateVarKeysParsing() {
        assertEquals(listOf("grafana_alert"), TemplateContextResolver.templateVarKeys("x.j2', template_vars={'grafana_alert': grafana_alert}) | trim", 0))
        assertEquals(listOf("a", "b"), TemplateContextResolver.templateVarKeys("x.j2\", template_vars=dict(a=1, b=f(c=2))) }}", 0))
        assertEquals("escaped quotes of double-quoted YAML", listOf("k"), TemplateContextResolver.templateVarKeys("x.j2\\\", template_vars={\\\"k\\\": 1}) }}", 0))
        assertEquals("another call's template_vars is not ours", emptyList<String>(), TemplateContextResolver.templateVarKeys("x.j2') ~ lookup('template', 'y', template_vars=dict(z=1))", 0))
        assertEquals(emptyList<String>(), TemplateContextResolver.templateVarKeys("x.j2')", 0))
    }

    fun testSrcHelpers() {
        assertEquals("templates/nginx/", TemplateContextResolver.staticPrefix("templates/nginx/{{ item.template }}"))
        assertEquals(".conf.j2", TemplateContextResolver.staticSuffix("templates/{{ x }}.conf.j2"))
        assertEquals("", TemplateContextResolver.staticSuffix("{{ a }}/{{ b }}"))
        assertTrue(TemplateContextResolver.singleExpression("templates/{{ x }}.j2"))
        assertFalse(TemplateContextResolver.singleExpression("{{ a }}/{{ b }}"))
        assertTrue(TemplateSearch.globRegex("config-*.alloy.j2").matches("config-base.alloy.j2"))
        assertFalse(TemplateSearch.globRegex("config-*.alloy.j2").matches("systemd.override.conf.j2"))
        assertTrue(TemplateSearch.globRegex("[!a]?.j2").matches("bc.j2"))
        assertFalse(TemplateSearch.globRegex("[!a]?.j2").matches("ac.j2"))
    }
}
