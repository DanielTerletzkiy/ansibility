package de.terletzkiy.ansibility.navigation

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.index.RenderEntry
import de.terletzkiy.ansibility.index.SrcKind
import de.terletzkiy.ansibility.inspections.references.NearestName

/** The pure helpers of the navigation area, and ansible-core's search orders on a small tree. */
class RefsHelpersTest : RefsTestCase() {

    fun testNearestName() {
        assertEquals("configuration", NearestName.of("configure", listOf("clients", "configuration", "deployment", "main")))
        assertEquals("configuration.yml", NearestName.of("configure.yml", listOf("configuration.yml", "clients.yml")))
        assertEquals("a transposition is one edit", "web", NearestName.of("wbe", listOf("web", "db")))
        assertEquals("Reload haproxy", NearestName.of("Reload haprox", listOf("Reload haproxy", "Restart haproxy", "Reload systemd")))
        assertNull("nothing close", NearestName.of("zzz", listOf("main", "configure")))
        assertNull("the value itself never counts", NearestName.of("main", listOf("main")))
        assertEquals(1, NearestName.distance("ab", "ba"))
        assertEquals(5, NearestName.distance("configure", "configuration"))
        assertEquals(8, NearestName.commonPrefix("configure", "Configuration"))
    }

    fun testQualifiedHandlerNames() {
        assertEquals("coolify" to "Reload oauth2-proxy", HandlerScope.qualified("coolify : Reload oauth2-proxy"))
        assertNull(HandlerScope.qualified("Reload oauth2-proxy"))
        assertNull("a colon without the spaced separator", HandlerScope.qualified("Reload a:b"))
        assertEquals("r : n", HandlerScope.qualify("r", "n"))
    }

    fun testLocateInsideQuotesAndFreeFormStrings() {
        val text = "notify: \"Reload x\"\ninclude_tasks: a.yml apply=b\n"
        val quoted = text.indexOf('"')
        assertEquals(TextRange(quoted + 1, quoted + 9), RefSites.locate(text, "Reload x", TextRange(quoted, quoted + 10)))
        val free = text.indexOf("a.yml")
        assertEquals(TextRange(free, free + 5), RefSites.locate(text, "a.yml", TextRange(free, text.length - 1)))
    }

    fun testSlotsFromRenders() {
        copyInfra("repos/wren")
        val renderer = vf("repos/wren/ansible/roles/app-wren-mono/tasks/nginx.yml")
        val entry = RenderEntry(0, 0, SrcKind.DYNAMIC_PREFIX, "{{ app_wren_mono_nginx_sites }}", "item", listOf("item", "floating", "template"))
        val slot = TemplateNames.slotOf("templates/nginx/{{ item.floating.template }}", entry, renderer)!!
        assertEquals(listOf("app_wren_mono_nginx_sites", TemplateNames.ITEM, "floating", "template"), slot.pattern)
        assertEquals("templates/nginx/", slot.prefix)
        assertEquals("templates/nginx/x.j2", slot.sourceFor("x.j2"))

        val plain = RenderEntry(0, 0, SrcKind.DYNAMIC_PREFIX, null, null, listOf("site_template"))
        assertEquals(listOf("site_template"), TemplateNames.slotOf("templates/{{ site_template }}.conf.j2", plain, renderer)!!.pattern)
        assertEquals(".conf.j2", TemplateNames.slotOf("templates/{{ site_template }}.conf.j2", plain, renderer)!!.suffix)
        assertNull("two expressions", TemplateNames.slotOf("templates/{{ a }}/{{ b }}", plain, renderer))
        assertNull("a whole-variable src", TemplateNames.slotOf("{{ a }}", RenderEntry(0, 0, SrcKind.WHOLE_VAR, dynamicVarPath = listOf("a")), renderer))
        assertNull("a literal loop", TemplateNames.slotOf("t/{{ item }}", RenderEntry(0, 0, SrcKind.DYNAMIC_PREFIX, "[a, b]", "item", listOf("item")), renderer))
    }

    fun testFindNeedleOrder() {
        createFile("r/roles/web/tasks/main.yml", "---\n")
        createFile("r/roles/web/tasks/sub/deep.yml", "---\n")
        createFile("r/roles/web/templates/a.j2", "x\n")
        createFile("r/roles/web/b.j2", "x\n")
        createFile("r/roles/web/tasks/c.j2", "x\n")
        val role = vf("r/roles/web")
        val tasks = vf("r/roles/web/tasks")
        fun first(source: String): String? = NeedleSearch.resolve(NeedleSearch.candidates("templates", source, role, tasks), false)?.let(::relative)
        assertEquals("r/roles/web/templates/a.j2", first("a.j2"))
        assertEquals("r/roles/web/templates/a.j2", first("templates/a.j2"))
        assertEquals("r/roles/web/b.j2", first("b.j2"))
        assertEquals("relative to the task file's directory", "r/roles/web/tasks/c.j2", first("c.j2"))
        assertNull(first("missing.j2"))
        val sub = NeedleSearch.candidates("templates", "a.j2", role, vf("r/roles/web/tasks/sub")).map { relative(it.base) + ":" + it.relative }
        assertEquals("a subdirectory of tasks also looks at the role", "r/roles/web:templates/a.j2", sub.first())
        assertTrue(sub.contains("r/roles/web/tasks:a.j2"))
    }

    fun testSitesOfOccurrences() {
        val range = TextRange(0, 1)
        assertEquals(AnsibleSite.TaskFileRef("haproxy", "x.yml", range), RefOccurrence(RefKind.TASK_INCLUDE, "x.yml", range).toSite("haproxy"))
        assertEquals(
            AnsibleSite.TaskFileRef("grafana", "alerting.yml", range),
            RefOccurrence(RefKind.TASKS_FROM, "alerting.yml", range, role = "/ansible/roles/grafana").toSite(null),
        )
        assertEquals(AnsibleSite.TemplateRef("files/a", true, range), RefOccurrence(RefKind.COPY_SRC, "files/a", range).toSite(null))
        assertEquals(AnsibleSite.HandlerRef("x", range), RefOccurrence(RefKind.LISTEN, "x", range).toSite(null))
        assertEquals(AnsibleSite.PlaybookFileRef("v.yml", range), RefOccurrence(RefKind.VARS_FILE, "v.yml", range).toSite("r"))
        assertEquals(AnsibleSite.PlaybookFileRef("p.yml", range), RefOccurrence(RefKind.IMPORT_PLAYBOOK, "p.yml", range).toSite(null))
        assertEquals("haproxy", RefOccurrence.roleNameOf("/ansible/roles/haproxy/"))
        assertTrue(RefOccurrence.isTemplated("a{{ b }}"))
        assertTrue(RefOccurrence.isTemplated("{% if %}"))
        assertFalse(RefOccurrence.isTemplated("a{b}"))
    }
}
