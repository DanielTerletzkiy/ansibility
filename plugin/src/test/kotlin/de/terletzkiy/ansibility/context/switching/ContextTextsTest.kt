package de.terletzkiy.ansibility.context.switching

import de.terletzkiy.ansibility.context.ContextPresentation
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/** The wording of F8.1: the built-in status text, selection labels, the file-scope segment and its "applies to" line. */
@RequiresInfraFixture
class ContextTextsTest : ContextSwitchingTestCase() {
    private fun target(path: String) = TargetVersionDetector.getInstance(project).targetVersion(root(path))

    fun testTheDefaultSelectionReadsAsBeforeHostAwareness() {
        val falcon = root("repos/falcon/ansible")
        assertEquals(status("falcon", " · All envs · core 2.18.8"), ContextTexts.statusText(falcon, target("repos/falcon/ansible"), RootContext.DEFAULT, stale = false))
        assertEquals(ContextPresentation.statusText(falcon, target("repos/falcon/ansible")), ContextTexts.statusText(falcon, target("repos/falcon/ansible"), RootContext.DEFAULT, false))
        val golden = root("golden")
        assertEquals("a role library has no env part", ContextPresentation.statusText(golden, target("golden")), ContextTexts.statusText(golden, target("golden"), RootContext.DEFAULT, false))
    }

    fun testTheStatusTextShowsEnvironmentAndHost() {
        val falcon = root("repos/falcon/ansible")
        val version = target("repos/falcon/ansible")
        assertEquals(status("falcon", " · prod · core 2.18.8"), ContextTexts.statusText(falcon, version, RootContext(EnvironmentChoice.Named("prod")), false))
        assertEquals(
            status("falcon", " · prod › prod-prod1 · core 2.18.8"),
            ContextTexts.statusText(falcon, version, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "playbook-setup-system.yml#4"), false),
        )
        assertEquals(
            "a vanished host is shown as stored and marked",
            status("falcon", " · prod › prod-db9 ⚠ · core 2.18.8"),
            ContextTexts.statusText(falcon, version, RootContext(EnvironmentChoice.Named("prod"), "prod-db9"), stale = true),
        )
    }

    fun testSelectionLabels() {
        val falcon = root("repos/falcon/ansible")
        val selection = RootContext(EnvironmentChoice.Named("prod"), "prod-prod1")
        assertEquals("falcon · prod › prod-prod1", ContextTexts.buttonText(falcon, selection))
        assertEquals("falcon · All envs", ContextTexts.buttonText(falcon, RootContext.DEFAULT))
        assertEquals("env prod · host prod-prod1 · play auto", ContextTexts.selectionLine(selection, null))
        assertEquals("env All · host All · play auto", ContextTexts.selectionLine(RootContext.DEFAULT, null))
        assertEquals(
            "env prod · host All · play playbook-setup-system.yml › KeepAliveD",
            ContextTexts.selectionLine(RootContext(EnvironmentChoice.Named("prod"), play = "playbook-setup-system.yml#4"), "playbook-setup-system.yml › KeepAliveD"),
        )
        assertEquals("a, b, c", ContextTexts.names(listOf("a", "b", "c")))
        assertEquals("a, b, c +2 more", ContextTexts.names(listOf("a", "b", "c", "d", "e")))
        assertEquals(listOf("no hosts", "1 host", "7 hosts"), listOf(0, 1, 7).map(ContextTexts::hostCount))
    }

    fun testTheFileScopeSegmentAndItsAppliesToLine() {
        val template = context.hostScope(vf(postfixTemplate))
        assertEquals("file: postfix → 4 hosts", ContextTexts.fileSegment(template))
        assertEquals("role postfix → 4 hosts in ops, prod, test via playbook-setup-system.yml › System", ContextTexts.appliesTo(template))

        val hostVars = context.hostScope(vf(prod1Vars))
        assertEquals("file: prod-prod1 → 1 host", ContextTexts.fileSegment(hostVars))
        assertEquals("host_vars of prod-prod1", ContextTexts.appliesTo(hostVars))

        val group = context.hostScope(vf("repos/platform/ansible/environments/prod/group_vars/contracting/mysql_users.yml"))
        assertEquals("file: contracting → 2 hosts", ContextTexts.fileSegment(group))
        assertEquals("group_vars/contracting → prod-mlflow1, prod-training1", ContextTexts.appliesTo(group))

        val hostsYml = context.hostScope(vf(falconProdHosts), offsetOf(falconProdHosts, "keepalived:"))
        assertEquals("group keepalived of prod → prod-prod1, prod-prod2", ContextTexts.appliesTo(hostsYml))

        val keepalived = context.hostScope(vf(systemPlaybook), offsetOf(systemPlaybook, "hosts: keepalived"))
        assertEquals("file: KeepAliveD → 2 hosts", ContextTexts.fileSegment(keepalived))
        assertEquals("play playbook-setup-system.yml › KeepAliveD → prod-prod1, prod-prod2", ContextTexts.appliesTo(keepalived))

        val cfg = context.hostScope(vf("repos/falcon/ansible/ansible.cfg"))
        assertNull("files that follow the selection have no segment", ContextTexts.fileSegment(cfg))
        assertEquals("the whole root (no inventory narrows this file)", ContextTexts.appliesTo(cfg))
    }

    fun testAnEmptyFileScopeSaysWhy() {
        val orphan = context.hostScope(vf("repos/platform/ansible/environments/prod/group_vars/app_platform.yml"))
        assertEquals("file: app_platform → no hosts", ContextTexts.fileSegment(orphan))
        assertEquals("group_vars/app_platform → no hosts", ContextTexts.appliesTo(orphan))
    }
}
