package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.GroupNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.LayerSourceNode
import de.terletzkiy.ansibility.toolwindow.model.NodeStyle
import de.terletzkiy.ansibility.toolwindow.model.PlayEnvironmentNode
import de.terletzkiy.ansibility.toolwindow.model.PlayNode
import de.terletzkiy.ansibility.toolwindow.model.PlaybookNode

/**
 * HA7b (plan amendment R7/R8 F8.7, part of ex-X43): matched hosts per environment under the Playbooks node (the
 * selected environment in bold), the roles of a play, and the reach details of groups and var files.
 */
@RequiresInfraFixture
class PlaybookMatchesTest : ToolWindowTestCase() {
    override fun setUp() {
        super.setUp()
        for (repo in listOf(FALCON, PLATFORM)) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$repo", repo)
        myFixture.addFileToProject("$FALCON/playbook-templated.yml", "- name: Dynamic\n  hosts: \"{{ target_hosts }}\"\n  tasks: []\n")
        refreshRoots()
    }

    private fun presentation(node: AnsibleTreeNode) = runReadActionBlocking { node.presentation() }

    private fun details(node: AnsibleTreeNode) = runReadActionBlocking { node.details() }!!

    private fun select(environment: String?) {
        val root = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }
        val choice = environment?.let { EnvironmentChoice.Named(it) } ?: EnvironmentChoice.All
        AnsibleContextService.getInstance(project).setSelection(root, RootContext(choice))
    }

    private val keepalived: AnsibleTreeNode get() = path("falcon", "Playbooks", "playbook-setup-system.yml", "KeepAliveD")

    fun testPlayShowsItsMatchedHostsPerEnvironment() {
        assertEquals("KeepAliveD  hosts: keepalived · 2 hosts", presentation(keepalived).text)
        assertEquals(listOf("prod  prod-prod1, prod-prod2", "ops, test  no hosts", "Roles  1"), texts(keepalived))
        val prod = path(keepalived, "prod") as PlayEnvironmentNode
        assertEquals(listOf("prod-prod1  192.0.2.29", "prod-prod2  192.0.2.30"), texts(prod))
        assertEquals("$FALCON/environments/prod/hosts.yml:7", describe(path(prod, "prod-prod1").target))
        assertEquals(listOf("keepalived  in roles: · role not found"), texts(path(keepalived, "Roles")))
        assertFalse("a play is no leaf any more", (keepalived as PlayNode).isLeaf)

        val system = path("falcon", "Playbooks", "playbook-setup-system.yml", "System")
        assertEquals(listOf("ops  ops-ops1", "prod  prod-prod1, prod-prod2", "test  test-test1", "Roles  7"), texts(system))
        assertEquals("Ping all hosts serially  hosts: all · 10 hosts", presentation(path("falcon", "Playbooks", "playbook-setup-system.yml", "Ping all hosts serially")).text)
    }

    fun testTheSelectedEnvironmentIsHighlighted() {
        assertEquals("All: nothing is bold", NodeStyle.NORMAL, presentation(path(keepalived, "prod")).style)
        select("prod")
        assertEquals(NodeStyle.EMPHASIZED, presentation(path(keepalived, "prod")).style)
        val details = details(keepalived)
        val matches = details.section("Matched hosts per environment")!!.items
        assertEquals(listOf("ops — no hosts", "prod — prod-prod1, prod-prod2 · your Ansible context", "test — no hosts"), matches.map { it.toString() })
        assertTrue(matches[1].emphasized)
        assertEquals(listOf("keepalived"), details.section("Roles in execution order")!!.items.map { it.text })
        assertTrue(details.section("Play")!!.items.any { it.text == "Playbook dir: ansible" })
        select("test")
        assertEquals(NodeStyle.NORMAL, presentation(path(keepalived, "prod")).style)
    }

    fun testATemplatedPatternMatchesNothingStatically() {
        val dynamic = path("falcon", "Playbooks", "playbook-templated.yml", "Dynamic")
        assertEquals("Dynamic  hosts: {{ target_hosts }} · templated", presentation(dynamic).text)
        assertEquals(listOf("templated hosts: pattern  the hosts are known only when the play runs"), texts(dynamic))
        val targeted = path("falcon", "Environments", "prod", "Hosts", "prod-prod1", "Targeted by")
        assertEquals("Templated hosts: patterns  1 play may also run here", texts(targeted).last())
        assertEquals(listOf("Dynamic  hosts: {{ target_hosts }}"), texts(path(targeted, "Templated hosts: patterns")))
    }

    fun testPlaybookDetailsListThePlaysPerEnvironment() {
        val details = details(path("falcon", "Playbooks", "playbook-setup-system.yml") as PlaybookNode)
        val plays = details.section("Plays")!!.items.map { it.toString() }
        assertEquals("KeepAliveD — hosts: keepalived · prod: 2 hosts", plays[4])
        assertEquals("System — hosts: system · ops: 1 host · prod: 2 hosts · test: 1 host", plays[2])
        assertEquals("$FALCON/playbook-setup-system.yml:39", describe(details.section("Plays")!!.items[4].target))
    }

    // ------------------------------------------------------------------ reach details

    fun testGroupReachSaysWhichPlaysAndRolesRunOnItsHosts() {
        val contracting = path("platform", "Environments", "prod", "Groups", "contracting") as GroupNode
        val details = details(contracting)
        val plays = details.section("Plays on its hosts")!!.items
        assertEquals("No play targets contracting directly; its hosts are reached via all.", plays.first().text)
        assertEquals(
            "playbook-puppet-migration.yml › Puppet migration — hosts: all · 2 of 2 hosts",
            plays.last().toString(),
        )
        assertEquals(
            listOf("debug — 2 of 2 hosts · Debug", "puppet-migration — 2 of 2 hosts · Puppet migration"),
            details.section("Roles on its hosts")!!.items.map { it.toString() },
        )

        val keepalivedGroup = details(path("falcon", "Environments", "prod", "Groups", "keepalived"))
        val direct = keepalivedGroup.section("Plays on its hosts")!!.items
        assertTrue(direct.first().text, direct.first().text.startsWith("Targeted directly by 1 play; "))
        val play = direct.single { it.text == "playbook-setup-system.yml › KeepAliveD" }
        assertTrue("the play naming the group is bold", play.emphasized)
        assertEquals("hosts: keepalived · 2 of 2 hosts · names this group", play.note)
        assertEquals("$FALCON/playbook-setup-system.yml:39", describe(play.target))
    }

    fun testVarFileDetailsSayWhereItTakesEffect() {
        val file = path("falcon", "Environments", "prod", "Groups", "all", "group_vars/all/vars.yml") as LayerSourceNode
        val effect = details(file).section("Effect on its hosts")!!.items
        assertEquals("Applies to 2 hosts · effective on 2 of 2", effect.first().text)
        val relayhost = effect.single { it.text == "postfix_relayhost" }
        assertEquals("shadowed on prod-prod1, prod-prod2 by group_vars/all/vars.yml:156", relayhost.note)
        assertEquals("$FALCON/environments/prod/group_vars/all/vars.yml:471", describe(relayhost.target))
        val fixedPublic = effect.single { it.text == "system_ip_fixed_public" }
        assertEquals("each host's own host_vars wins", "shadowed on prod-prod1, prod-prod2 by 2 different definitions", fixedPublic.note)

        val hostVars = path("falcon", "Environments", "prod", "Hosts", "prod-prod1", "host_vars/prod-prod1/vars.yml")
        assertEquals("Applies to 1 host · effective on 1 of 1", details(hostVars).section("Effect on its hosts")!!.items.first().text)
    }

    fun testHostBadgesAndDetailsOfASharedAddress() {
        val test1 = path("falcon", "Environments", "test", "Hosts", "test-test1") as HostNode
        assertEquals("test-test1  192.0.2.43  1 of 7 names on 192.0.2.43 · groups: all, app_mono, app_services, database +4", presentation(test1).text)
        assertEquals("192.0.2.43", presentation(test1).badge)
        val address = details(test1).section("Address (ansible_host)")!!.items
        assertEquals("1 of 7 names on 192.0.2.43", address[1].text)
        assertTrue(address[1].note!!.startsWith("test › preview-"))
    }
}
