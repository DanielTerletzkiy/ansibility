package de.terletzkiy.ansibility.navigation

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.ExtensionTestUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.CompletionSource
import java.util.Locale

/**
 * Completion of reference values through the plugin's completion entry point (plan F1.8, X50): handler names and
 * topics, task files, `*_from` files, template and copy sources, roles and template-name values; plus the latency
 * budget (plan Testing strategy 7: completion p95 < 100 ms).
 */
class RefsCompletionTest : RefsTestCase() {

    /** Creates [path] from [text] (with a `<caret>` marker), completes there and returns the lookup strings. */
    private fun complete(path: String, text: String): List<String> {
        val caret = text.indexOf(CARET)
        check(caret >= 0) { "no caret in $text" }
        createFile(path, text.replace(CARET, ""))
        return completeAt(path, caret)
    }

    private fun completeAt(path: String, offset: Int): List<String> {
        myFixture.configureFromTempProjectFile(path)
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun presentation(lookupString: String): LookupElementPresentation =
        LookupElementPresentation.renderElement(myFixture.lookupElements!!.single { it.lookupString == lookupString })

    private val taskWithNotify = "- ansible.builtin.template:\n    src: templates/haproxy.cfg.j2\n    dest: /etc/haproxy/haproxy.cfg\n  notify: <caret>\n"

    fun testHandlerNamesAndTopicsOfTheOwnRoleComeFirst() {
        copyInfra("golden/roles/haproxy")
        val items = complete("golden/roles/haproxy/tasks/new.yml", taskWithNotify)
        assertContainsElements(items, "Reload haproxy", "Restart rsyslog", "Reload systemd", "Reload systemd daemon", "Restart alloy")
        assertEquals("a name and a topic of one handler are one item", 1, items.count { it == "Reload systemd" })
        val reload = presentation("Reload haproxy")
        assertTrue("own role handlers are bold", reload.isItemTextBold)
        assertEquals("haproxy", reload.typeText)
        assertNotNull(reload.icon)
    }

    fun testHandlersOfThePlayScopeAndQualifiedForms() {
        copyInfra("golden/roles/haproxy", "golden/roles/keycloak")
        createFile("golden/playbooks/playbook-lb.yml", "- hosts: lb\n  roles:\n    - { role: keycloak }\n    - { role: haproxy }\n")
        val items = complete("golden/roles/haproxy/tasks/new.yml", taskWithNotify.replace("<caret>", "Rest<caret>"))
        assertContainsElements(items, "Restart rsyslog", "Restart otel-collector", "Restart alloy")
        val scope = presentation("Restart otel-collector")
        assertFalse("play-scope handlers are not bold", scope.isItemTextBold)
        assertEquals("keycloak", scope.typeText)
        assertEquals("own role first", "Restart", items.first().take(7))
        assertTrue(items.indexOf("Restart rsyslog") < items.indexOf("Restart otel-collector"))

        val qualified = complete("golden/roles/haproxy/tasks/other.yml", taskWithNotify.replace("<caret>", "\"keycloak : Re<caret>\""))
        assertContainsElements(qualified, "keycloak : Restart otel-collector", "keycloak : Reload nginx")
        assertDoesntContain(qualified, "Restart otel-collector")
    }

    fun testTaskFilesOfTheOwnRole() {
        copyInfra("golden/roles/haproxy")
        val items = complete("golden/roles/haproxy/tasks/new.yml", "- ansible.builtin.include_tasks: <caret>\n")
        assertContainsElements(items, "access.yml", "apt.yml", "configure.yml", "observability.yml", "sysctl.yml", "systemd.yml")
        assertDoesntContain(items, "new.yml")
        assertEquals("task file", presentation("configure.yml").typeText)
    }

    fun testTasksFromOfTheNamedRole() {
        copyInfra("golden/roles/keycloak", "golden/roles/haproxy")
        val items = complete(
            "golden/playbooks/playbook-configure.yml",
            "- hosts: all\n  tasks:\n    - ansible.builtin.include_role:\n        name: keycloak\n        tasks_from: c<caret>\n",
        )
        assertContainsElements(items, "configuration.yml", "clients.yml", "client_roles.yml")
        assertDoesntContain("only keycloak's task files", items, "configure.yml")
        assertEquals("tasks/ of keycloak", presentation("configuration.yml").typeText)
    }

    fun testTemplateAndCopySources() {
        copyInfra("golden/roles/haproxy")
        val templates = complete("golden/roles/haproxy/tasks/new.yml", "- ansible.builtin.template:\n    src: <caret>\n    dest: /x\n")
        assertContainsElements(
            templates,
            "templates/haproxy.cfg.j2", "templates/logrotate.conf.j2", "templates/observability/config.alloy.j2", "templates/rsyslog-haproxy.conf.j2",
        )
        val bare = complete("golden/roles/haproxy/tasks/bare.yml", "- ansible.builtin.template:\n    src: r<caret>\n    dest: /x\n")
        assertContainsElements("a prefix that is not templates/ gets bare names", bare, "rsyslog-haproxy.conf.j2")
        val copy = complete("golden/roles/haproxy/tasks/copy.yml", "- ansible.builtin.copy:\n    src: f<caret>\n    dest: /x\n  notify: Reload haproxy\n")
        assertTrue("the only file is inserted: $copy", copy.isEmpty() || "files/override.conf" in copy)
        if (copy.isEmpty()) assertTrue(myFixture.editor.document.text.contains("src: files/override.conf"))
    }

    fun testRoleNamesAndMoleculePaths() {
        copyInfra("golden/roles/haproxy", "golden/roles/keycloak", "golden/roles/grafana")
        val names = complete("golden/playbooks/playbook-new.yml", "- hosts: all\n  roles:\n    - <caret>\n")
        assertContainsElements(names, "haproxy", "keycloak", "grafana")
        assertEquals("role", presentation("haproxy").typeText)
        val paths = complete("golden/roles/haproxy/molecule/default/side.yml", "- hosts: all\n  roles:\n    - role: /ansible/roles/<caret>\n")
        assertContainsElements(paths, "/ansible/roles/haproxy", "/ansible/roles/keycloak", "/ansible/roles/grafana")
    }

    fun testTemplateNameValues() {
        copyInfra("repos/wren")
        val vars = "repos/wren/ansible/environments/prod/group_vars/all/vars.yml"
        val items = completeAt(vars, offsetAt(vars, 165, "frontend.protected", "frontend.".length))
        assertContainsElements(
            items,
            "frontend.protected.site.https.conf.j2", "frontend.protected.site.proxy.conf.j2", "frontend.site.https.conf.j2", "frontend.site.proxy.conf.j2",
        )
        assertDoesntContain("the typed prefix filters", items, "main.site.proxy.conf.j2")
        assertEquals("template", presentation("frontend.site.proxy.conf.j2").typeText)
    }

    fun testOtherSitesGetNothingFromUs() {
        copyInfra("golden/roles/haproxy")
        val items = complete("golden/roles/haproxy/tasks/new.yml", "- ansible.builtin.template:\n    src: templates/haproxy.cfg.j2\n    dest: /x\n  when: <caret>\n")
        assertDoesntContain(items, "Reload haproxy", "configure.yml", "templates/haproxy.cfg.j2")
    }

    /**
     * Plan Testing strategy 7 / M3 acceptance 10: completion p95 < 100 ms on the fixture (warm). The budget is checked
     * against the time this source spends per invocation (measured around it inside the real pipeline); the time of the
     * whole `completeBasic` call (lookup, other contributors, test framework) is reported alongside.
     */
    fun testLatency() {
        val settings = CodeInsightSettings.getInstance()
        val autoInsert = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            measureLatency()
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autoInsert
        }
    }

    private fun measureLatency() {
        val timing = TimingSource(RefsCompletionSource())
        val sources = CompletionSource.EP_NAME.extensionList.map { if (it is RefsCompletionSource) timing else it }
        ExtensionTestUtil.maskExtensions(CompletionSource.EP_NAME, sources, testRootDisposable)
        copyInfra("golden/roles/haproxy", "golden/roles/keycloak", "golden/roles/grafana", "golden/playbooks", "repos/wren")
        val vars = "repos/wren/ansible/environments/prod/group_vars/all/vars.yml"
        val cases = listOf(
            Triple("golden/roles/haproxy/tasks/p1.yml", taskWithNotify.replace("<caret>", "Re<caret>"), -1),
            Triple("golden/roles/haproxy/tasks/p2.yml", "- ansible.builtin.include_tasks: c<caret>\n", -1),
            Triple("golden/roles/haproxy/tasks/p3.yml", "- ansible.builtin.template:\n    src: templates/<caret>\n    dest: /x\n", -1),
            Triple("golden/playbooks/playbook-p4.yml", "- hosts: all\n  roles:\n    - k<caret>\n", -1),
            Triple(vars, "", offsetAt(vars, 165, "frontend.protected", "frontend.".length)),
        )
        val total = ArrayList<Long>()
        for ((path, text, at) in cases) {
            val caret = if (at >= 0) at else text.indexOf(CARET).also { createFile(path, text.replace(CARET, "")) }
            repeat(WARM_UP) { completeAt(path, caret); myFixture.lookup?.hideLookup(true) }
            timing.samples.clear().also { timing.recording = true }
            repeat(ROUNDS) {
                val start = System.nanoTime()
                completeAt(path, caret)
                total += (System.nanoTime() - start) / 1_000_000
                myFixture.lookup?.hideLookup(true)
            }
            timing.recording = false
            timing.all += timing.samples
        }
        val source = timing.all.sorted()
        val whole = total.sorted()
        println(
            "Role-navigation completion on the fixture (${source.size} runs): source p50 ${micros(source, 50)} ms, p95 ${micros(source, 95)} ms, " +
                "max ${micros(source, 100)} ms; whole completeBasic p50 ${percentile(whole, 50)} ms, p95 ${percentile(whole, 95)} ms",
        )
        assertTrue("source p95 ${micros(source, 95)} ms (budget 100 ms)", percentile(source, 95) < 100_000)
    }

    /** Wraps a source and records the microseconds each call takes while [recording]. */
    private class TimingSource(private val delegate: CompletionSource) : CompletionSource {
        val samples = ArrayList<Long>()
        val all = ArrayList<Long>()
        var recording = false

        override fun complete(site: AnsibleSite?, parameters: CompletionParameters, result: CompletionResultSet) {
            val start = System.nanoTime()
            try {
                delegate.complete(site, parameters, result)
            } finally {
                if (recording) samples += (System.nanoTime() - start) / 1_000
            }
        }
    }

    private fun percentile(sorted: List<Long>, p: Int): Long = sorted[((sorted.size * p) / 100).coerceAtMost(sorted.lastIndex)]

    private fun micros(sorted: List<Long>, p: Int): String = "%.1f".format(Locale.ROOT, percentile(sorted, p) / 1000.0)

    companion object {
        private const val CARET = "<caret>"
        private const val WARM_UP = 5
        private const val ROUNDS = 25
    }
}
