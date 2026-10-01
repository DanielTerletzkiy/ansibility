package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/** The X75 "Show Ansible Context" lines of F8.1: Selection, Applies to, Effective scope, Playbook dir, Effective on. */
class ContextReportLinesTest : ContextSwitchingTestCase() {
    override fun addFixtureFiles() {
        add("repos/pelican/ansible/group_vars/all.yml", "---\npelican_playbook_level: true")
    }

    private fun lines(path: String, offset: Int? = null, variable: String? = null): Map<String, String> =
        runReadActionBlocking { ContextReportLines.lines(project, vf(path), offset, variable) }.associate { it.label to it.value }

    fun testTheLinesInAllMode() {
        val lines = lines(postfixTemplate, variable = "postfix_relayhost")
        assertEquals("env All · host All · play auto", lines["Selection"])
        assertEquals("role postfix → 4 hosts in ops, prod, test via playbook-setup-system.yml › System", lines["Applies to"])
        assertEquals("4 hosts × 1 play", lines["Effective scope"])
        assertEquals("repos/falcon/ansible", lines["Playbook dir"])
        assertTrue(lines.toString(), lines.getValue("Effective").matches(Regex("\\d+ values? on 4 hosts.*")))
        assertNull("no problem line for a valid selection", lines["Selection problem"])
    }

    fun testTheLinesForOneHost() {
        val keepalived = playKey("repos/falcon/ansible", systemPlaybook, "System")
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", keepalived))
        val lines = lines(postfixTemplate, variable = "postfix_relayhost")
        assertEquals("env prod · host prod-prod1 · play playbook-setup-system.yml › System", lines["Selection"])
        assertEquals("1 host × 1 play", lines["Effective scope"])
        assertEquals("group_vars/all/vars.yml:156 (L5)", lines["Effective on prod-prod1"])
    }

    fun testAStaleSelectionAndAnOverriddenScopeAreReported() {
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("prod"), "prod-db9"))
        val stale = lines("repos/falcon/ansible/ansible.cfg")
        assertEquals("env prod · host prod-db9 · play auto", stale["Selection"])
        assertEquals("prod-db9 is no longer in environments/prod/hosts.yml", stale["Selection problem"])
        assertEquals("the whole root (no inventory narrows this file)", stale["Applies to"])

        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("test"), "test-test1"))
        val overridden = lines(prod1Vars)
        assertEquals("host_vars of prod-prod1", overridden["Applies to"])
        assertEquals("1 host × 13 plays (the file scope overrides your Ansible context)", overridden["Effective scope"])
    }

    fun testDangerZonePlaysReportTheirPlaybookDir() {
        val clone = playKey(DANGER_ZONE, cloneToReplisync, "Clone to replisync")
        context.setSelection(root(DANGER_ZONE), RootContext(EnvironmentChoice.Named("prod"), "prod-replisync1", clone))
        val lines = lines(cloneToReplisync)
        assertEquals("repos/pelican/ansible/danger_zone/database · does not load ansible/group_vars", lines["Playbook dir"])
    }
}
