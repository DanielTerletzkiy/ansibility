package de.terletzkiy.ansibility.dispatch

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture

/**
 * X75 "render contexts (from M3)": the context report of a template lists the tasks that render it, each with how it
 * names the template, the task's `path:line`, its role, the templates it is included through and its loop, from
 * `api.TemplateContextService` (the real services on the infra fixture).
 */
@RequiresInfraFixture
class AnsibleContextRenderLinesTest : DispatchTestCase() {
    private val grafana = "golden/roles/grafana"
    private val siteTemplate = "$grafana/templates/nginx/main.site.conf.j2"

    override fun setUp() {
        super.setUp()
        copyFixture(grafana)
    }

    private fun report(path: String, offset: Int? = 0): AnsibleContextReport =
        inBackgroundReadAction { AnsibleContextReport.build(project, vf(path), offset) } ?: error("no report for $path")

    fun testDynamicSourceWithALoop() {
        val report = report(siteTemplate)
        assertEquals("1 rendering task", report.valueOf("Render contexts"))
        assertEquals(
            "dynamic src · roles/grafana/tasks/nginx.yml:33 · role grafana · loop item over grafana_nginx_sites",
            report.valueOf("Render context 1"),
        )
        val labels = report.lines.map { it.label }
        assertTrue("the render lines come before the site lines: $labels", labels.indexOf("Render contexts") < labels.indexOf("At caret"))
        assertTrue(report.asText(), report.asText().contains("Render context 1: dynamic src · roles/grafana/tasks/nginx.yml:33"))
    }

    fun testStaticSourceAndInheritedContexts() {
        createFile("$grafana/tasks/pages.yml", "- name: Render the page\n  ansible.builtin.template:\n    src: pages/outer.j2\n    dest: /etc/outer\n")
        createFile("$grafana/templates/pages/outer.j2", "{% include 'pages/inner.j2' %}\n")
        createFile("$grafana/templates/pages/inner.j2", "inner\n")

        assertEquals("static src · roles/grafana/tasks/pages.yml:1 · role grafana", report("$grafana/templates/pages/outer.j2").valueOf("Render context 1"))
        assertEquals(
            "included by another template · roles/grafana/tasks/pages.yml:1 · role grafana · via roles/grafana/templates/pages/outer.j2",
            report("$grafana/templates/pages/inner.j2").valueOf("Render context 1"),
        )
    }

    fun testTemplatesNoTaskRendersAndOtherFiles() {
        createFile("$grafana/templates/orphan.j2", "{{ grafana_version }}\n")
        assertEquals("none: no task in golden renders this template, so variables resolve root-wide", report("$grafana/templates/orphan.j2").valueOf("Render contexts"))
        assertNull("task files have no render contexts", report("$grafana/tasks/nginx.yml").valueOf("Render contexts"))
        assertNull("without a caret too", report("$grafana/tasks/nginx.yml", null).valueOf("Render contexts"))
    }

    fun testRenderKindNames() {
        val names = de.terletzkiy.ansibility.api.RenderKind.entries.map(SitePresentation::renderKindName)
        assertEquals(listOf("static src", "dynamic src", "src variable", "fileglob loop", "lookup('template')", "included by another template"), names)
        assertTrue(runReadActionBlocking { names.none { it.startsWith("!") } })
    }
}
