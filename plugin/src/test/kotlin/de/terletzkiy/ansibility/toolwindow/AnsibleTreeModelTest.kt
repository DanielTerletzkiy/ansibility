package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.toolwindow.model.GroupNode
import de.terletzkiy.ansibility.toolwindow.model.HostNode
import de.terletzkiy.ansibility.toolwindow.model.LayerSource
import de.terletzkiy.ansibility.toolwindow.model.LayerSourceNode
import de.terletzkiy.ansibility.toolwindow.model.NodeIcon
import de.terletzkiy.ansibility.toolwindow.model.PlayNode
import de.terletzkiy.ansibility.toolwindow.model.RootNode
import de.terletzkiy.ansibility.toolwindow.model.ToolWindowDetails
import de.terletzkiy.ansibility.toolwindow.model.WorktreeNode

/**
 * The headless tree model on the whole sanitised infra fixture (plan M3 acceptance 9). The fixture holds a subset of
 * the repos: five project roots with `ansible.cfg` (falcon, heron, pelican, platform, wren), the golden role library, the pelican
 * danger-zone nested root and the synthetic detached worktree.
 */
class AnsibleTreeModelTest : ToolWindowTestCase() {
    override fun setUp() {
        super.setUp()
        copyWholeFixture()
    }

    // ------------------------------------------------------------------ roots

    fun testRootsOfTheFixture() {
        val top = children(workspaceNode())
        assertEquals(
            listOf("falcon", "heron", "pelican", "pelican › danger_zone/database", "platform", "wren", "golden", "Detached worktree: wt-demo"),
            top.map { it.presentation().name },
        )
        val kinds = top.filterIsInstance<RootNode>().associate { it.root.root.displayName to it.root.kind }
        assertEquals(listOf("falcon", "heron", "pelican", "platform", "wren"), kinds.filterValues { it == RootKind.PROJECT }.keys.toList())
        assertEquals(RootKind.NESTED_PLAYBOOK, kinds["pelican › danger_zone/database"])
        assertEquals(RootKind.ROLE_LIBRARY, kinds["golden"])
        val worktree = top.last() as WorktreeNode
        assertTrue("a single leaf, never its roots", worktree.isLeaf)
        assertEquals(listOf("[wt-demo] golden"), worktree.worktree.roots.map { it.displayName })
        assertEquals("$WORKTREE_PARENT/${InfraTestData.WORKTREE_DIR}/", describe(worktree.target))
        assertTrue("no detached root is shown as a root", top.filterIsInstance<RootNode>().none { it.root.root.detached })
    }

    fun testRootLabels() {
        assertEquals("falcon  core 2.18.8 (docker pin) · 9 roles · 3 envs", path("falcon").presentation().text)
        assertEquals("platform  core 2.18.8 (majority pin, guessed) · 1 role · 1 env", path("platform").presentation().text)
        assertEquals(
            "pelican › danger_zone/database  nested playbook root — own plays skip ansible/group_vars; imported plays load them",
            path("pelican › danger_zone/database").presentation().text,
        )
        assertEquals("golden  role library · 18 roles · 5 playbooks", path("golden").presentation().text)
        assertEquals(NodeIcon.PROJECT_ROOT, path("falcon").presentation().icon)
        assertEquals(NodeIcon.ROLE_LIBRARY, path("golden").presentation().icon)
        assertEquals(listOf("Shared (playbook-level) vars", "Environments", "Playbooks", "Roles (9)"), names(path("falcon")))
        assertEquals(listOf("Environments", "Playbooks", "Roles (17)"), names(path("pelican › danger_zone/database")))
        assertEquals("Environments  shared with pelican: prod", path("pelican › danger_zone/database", "Environments").presentation().text)
        assertEquals(listOf("Playbooks", "Roles (18)"), names(path("golden")))
        assertEquals("roots without inventories have no Environments node", listOf("Roles (1)"), names(path("heron")))
    }

    fun testRootDetails() {
        val falcon = (path("falcon") as RootNode).details()
        assertEquals("falcon", falcon.title)
        assertEquals("Project root (ansible.cfg)", falcon.subtitle)
        val items = falcon.section("Root")!!.items.map { it.text }
        assertTrue(items.toString(), items.contains("Environments: ops, prod, test"))
        assertTrue(items.toString(), items.contains("Roles: 9"))
        assertTrue(items.toString(), items.any { it.startsWith("Target ansible-core: 2.18.8") })
        assertNull("default precedence needs no note", falcon.section("Precedence"))

        val nested = (path("pelican › danger_zone/database") as RootNode).details()
        val nestedItems = nested.section("Root")!!.items.map { it.text }
        assertTrue(nestedItems.toString(), nestedItems.contains("Environments: shared with pelican"))
        assertTrue(nestedItems.toString(), nestedItems.any { it.startsWith("Plays defined here load no playbook-level group_vars of ansible/group_vars") })
    }

    fun testSharedPlaybookLevelVars() {
        val shared = path("falcon", "Shared (playbook-level) vars")
        assertEquals("Shared (playbook-level) vars  ansible/group_vars · 2 files · beat the group_vars and host_vars of every environment", shared.presentation().text)
        assertEquals(
            listOf(
                "ansible/group_vars/all/vars.yml  L5 playbook group_vars/all — beats env group_vars/all (L4)",
                "ansible/group_vars/all/vault.yml  L5 playbook group_vars/all — beats env group_vars/all (L4)",
            ),
            texts(shared),
        )
        val vault = children(shared).last() as LayerSourceNode
        assertEquals(NodeIcon.VAULT_FILE, vault.presentation().icon)
        assertTrue(vault.presentation().tooltip.first().startsWith("Level 5: group_vars/all next to the playbook"))

        val details = vault.details()
        assertEquals(listOf("Every environment (playbook-level)", "Group: all"), details.section("Scope")!!.items.take(2).map { it.text })
        assertEquals(
            "a playbook-level file applies to the hosts of every environment",
            listOf("ops", "prod", "test"),
            details.section("Hosts it applies to")!!.items.map { it.text },
        )
        assertEquals("prod-prod1, prod-prod2", details.section("Hosts it applies to")!!.items[1].note)
    }

    // ------------------------------------------------------------------ falcon prod › Hosts › prod-prod1

    fun testFalconProdHostListsItsSourcesInLoadOrder() {
        val host = path("falcon", "Environments", "prod", "Hosts", "prod-prod1")
        assertEquals("prod-prod1  192.0.2.29  groups: all, app_mono, app_services, database +7", host.presentation().text)
        assertEquals(
            listOf(
                "hosts.yml: ansible_user, ansible_port  L3 inventory file vars of all",
                "hosts.yml: inventory_docs_client_structure  L3 inventory file vars of app_mono",
                "group_vars/all/vars.yml  L4 env group_vars/all",
                "group_vars/all/vault.yml  L4 env group_vars/all",
                "ansible/group_vars/all/vars.yml  L5 playbook group_vars/all — beats env group_vars/all (L4)",
                "ansible/group_vars/all/vault.yml  L5 playbook group_vars/all — beats env group_vars/all (L4)",
                "group_vars/keycloak/vars.yml  L6 env group_vars/keycloak — beats group_vars/all (L4, L5)",
                "group_vars/keycloak/vault.yml  L6 env group_vars/keycloak — beats group_vars/all (L4, L5)",
                "hosts.yml: ansible_host  L8 inventory file vars of host prod-prod1 — beats every group level",
                "host_vars/prod-prod1/vars.yml  L9 env host_vars/prod-prod1 — beats every group level (L3–L7)",
                "host_vars/prod-prod1/vault.yml  L9 env host_vars/prod-prod1 — beats every group level (L3–L7)",
            ),
            sourceTexts(host),
        )
        assertEquals("HA7a: Effective vars and Targeted by follow the sources", listOf("Effective vars", "Targeted by"), names(host).takeLast(2))
        val levels = sources(host).map { it.source.level }
        assertEquals("load order never goes down a level", levels.sorted(), levels)

        val details = (host as HostNode).details()
        assertEquals("Host prod-prod1", details.title)
        assertEquals("falcon › prod", details.subtitle)
        assertEquals("192.0.2.29", details.section("Address (ansible_host)")!!.items.single().text)
        assertEquals(sourceTexts(host).map { it.substringBefore("  ") }, details.section("Var files in load order")!!.items.map { it.text })
        assertEquals("all", details.section("Groups in the order they apply")!!.items.first().text)
    }

    fun testFalconProdGroups() {
        val groups = path("falcon", "Environments", "prod", "Groups")
        assertEquals(
            listOf(
                "all", "database", "database_primary", "app_mono", "system", "app_services", "keycloak", "oauth2_proxy", "haproxy",
                "keepalived", "monitoring_client",
            ),
            names(groups),
        )
        assertEquals(
            "all  inline (2): ansible_user, ansible_port · group_vars/all/{vars,vault}.yml (L4) · ansible/group_vars/all/{vars,vault}.yml (L5) · → prod-prod1, prod-prod2",
            path(groups, "all").presentation().text,
        )
        assertEquals("app_mono  inline (1): inventory_docs_client_structure · → prod-prod1, prod-prod2", path(groups, "app_mono").presentation().text)
        assertEquals("keycloak  group_vars/keycloak/{vars,vault}.yml (L6) · → prod-prod1, prod-prod2", path(groups, "keycloak").presentation().text)
        assertEquals(
            "all: its sources, then the hosts listed under all.hosts (its groups are the siblings)",
            listOf(
                "hosts.yml: ansible_user, ansible_port", "group_vars/all/vars.yml", "group_vars/all/vault.yml",
                "ansible/group_vars/all/vars.yml", "ansible/group_vars/all/vault.yml", "prod-prod1", "prod-prod2",
            ),
            names(path(groups, "all")),
        )
        assertEquals(
            "a host under a group lists its own sources",
            sourceTexts(path("falcon", "Environments", "prod", "Hosts", "prod-prod1")),
            sourceTexts(path(groups, "keycloak", "prod-prod1")),
        )
    }

    // ------------------------------------------------------------------ platform prod

    fun testPlatformContractingAnalyticsMlflowRenders() {
        val mlflow = path("platform", "Environments", "prod", "Groups", "contracting", "analytics", "prod-mlflow1")
        assertEquals(
            "the templated address is evaluated with the playbook dir, the template follows in grey",
            "prod-mlflow1  192.0.2.15  {{ host_ips['prod-mlflow1'] }} · groups: all, app_mlflow, app_services, chronod +7",
            mlflow.presentation().text,
        )
        val contracting = path("platform", "Environments", "prod", "Groups", "contracting")
        assertEquals("contracting  group_vars/contracting/{mysql_users,vault}.yml (L6) · → prod-mlflow1, prod-training1", contracting.presentation().text)
        assertEquals(
            listOf("group_vars/contracting/mysql_users.yml", "group_vars/contracting/vault.yml", "analytics"),
            names(contracting),
        )
        assertEquals(listOf("prod-mlflow1", "prod-training1"), names(path(contracting, "analytics")))
    }

    fun testContractingDetails() {
        val contracting = path("platform", "Environments", "prod", "Groups", "contracting") as GroupNode
        val details = contracting.details()
        assertEquals("Group contracting", details.title)
        assertEquals("platform › prod · depth 1 · priority 1", details.subtitle)
        assertEquals(listOf("all"), details.section("Parents")!!.items.map { it.text })
        assertEquals(listOf("analytics"), details.section("Children")!!.items.map { it.text })
        assertEquals(
            listOf(
                "group_vars/contracting/mysql_users.yml — L6 env group_vars/contracting — beats group_vars/all (L4, L5)",
                "group_vars/contracting/vault.yml — L6 env group_vars/contracting — beats group_vars/all (L4, L5)",
            ),
            details.section("Var files in load order")!!.items.map { it.toString() },
        )
        val mysqlUsers = details.section("Var files in load order")!!.items.first()
        assertEquals("$PLATFORM/environments/prod/group_vars/contracting/mysql_users.yml:1", describe(mysqlUsers.target))
        val hosts = details.section("Hosts it applies to")!!.items
        assertEquals(listOf("prod-mlflow1", "prod-training1"), hosts.map { it.text })
        assertTrue(hosts.first().note!!.endsWith("via analytics"))
        assertTrue(hosts.first().note!!.startsWith("{{ host_ips['prod-mlflow1'] }}"))

        val order = details.section("Order groups are applied (sort_groups: depth, priority, name)")!!.items
        assertEquals("1. all", order.first().text)
        val selected = order.single { it.emphasized }
        assertTrue(selected.text, selected.text.endsWith(". contracting"))
        assertEquals("depth 1 · priority 1 · this group", selected.note)
        assertTrue("analytics (depth 2) applies after every depth-1 group", order.last().text.endsWith(". analytics"))
        assertEquals("$PLATFORM/environments/prod/hosts.yml:78", describe(selected.target))
    }

    fun testTrainingHostVarsListFiveFilesInLoadOrder() {
        val training = path("platform", "Environments", "prod", "Hosts", "prod-training1")
        val hostVars = sourceTexts(training).filter { it.startsWith("host_vars/") }
        assertEquals(
            listOf("chronod.yml", "mysql.yml", "users.yml", "vars.yml", "vault.yml").map {
                "host_vars/prod-training1/$it  L9 env host_vars/prod-training1 — beats every group level (L3–L7)"
            },
            hostVars,
        )
        assertEquals("host files load last", hostVars, sourceTexts(training).takeLast(5))
        val address = (training as HostNode).details().section("Address (ansible_host)")!!.items.single()
        assertEquals("the details show the evaluated address", "192.0.2.14", address.text)
        assertEquals("evaluated from {{ host_ips['prod-training1'] }} with the playbook dir", address.note)
        val file = sources(training).last()
        val details = file.details()
        assertEquals("host_vars/prod-training1/vault.yml", details.title)
        assertEquals(listOf("Environment: prod", "Host: prod-training1"), details.section("Scope")!!.items.take(2).map { it.text })
        assertEquals(listOf("prod-training1"), details.section("Hosts it applies to")!!.items.map { it.text })
        assertEquals("Level 9", details.section("Layer")!!.items[1].text)
    }

    fun testPlatformAllShowsTheAnsibleCfgConnectionSettingsAsLevelOne() {
        val all = path("platform", "Environments", "prod", "Groups", "all")
        val first = children(all).first() as LayerSourceNode
        assertEquals("L1 ansible.cfg: remote_user=provisioner, remote_port=2222", first.presentation().name)
        assertEquals("connection settings, not variables", first.presentation().extra)
        assertEquals(1, first.source.level)
        assertEquals("$PLATFORM/ansible.cfg:3", describe(first.target))
        val details = first.details()
        assertEquals(listOf("remote_user = provisioner", "remote_port = 2222"), details.section("Connection settings")!!.items.map { it.text })
        assertTrue("only platform sets them", children(path("falcon", "Environments", "prod", "Groups", "all")).none { (it as? LayerSourceNode)?.source is LayerSource.Connection })
        assertTrue("never on other groups", children(path("platform", "Environments", "prod", "Groups", "system")).none { (it as? LayerSourceNode)?.source is LayerSource.Connection })
    }

    fun testOrphanVarFilesAreNotLoaded() {
        val details = (path("platform", "Environments", "prod", "Groups", "all") as GroupNode).env.let { env ->
            val orphan = env.inventory.varFiles.single { it.group == "app_platform" }
            ToolWindowDetails.source(env.root, env, LayerSource.File(orphan))
        }
        val applies = details.section("Hosts it applies to")!!.items.single()
        assertEquals("no hosts", applies.text)
        assertEquals("Group app_platform is not defined in prod/hosts.yml, so ansible-core never loads this file there.", applies.note)
        val shown = walk(path("platform", "Environments", "prod", "Groups")).filterIsInstance<LayerSourceNode>().map { it.presentation().name }
        assertFalse("orphans are not in the group tree", shown.any { it.contains("app_platform") })
    }

    // ------------------------------------------------------------------ navigation (plan F6.3)

    fun testNavigationTargets() {
        val prod = arrayOf("falcon", "Environments", "prod")
        assertEquals("$FALCON/ansible.cfg:1", describe(path("falcon").target))
        assertEquals("$FALCON/environments/prod/hosts.yml:1", describe(path(*prod).target))
        assertEquals("the all.hosts entry", "$FALCON/environments/prod/hosts.yml:7", describe(path(*prod, "Hosts", "prod-prod1").target))
        assertEquals("the group key", "$FALCON/environments/prod/hosts.yml:18", describe(path(*prod, "Groups", "app_mono").target))
        assertEquals("inline vars jump to their group", "$FALCON/environments/prod/hosts.yml:18", describe(path(*prod, "Groups", "app_mono", "hosts.yml: inventory_docs_client_structure").target))
        assertEquals("$FALCON/environments/prod/group_vars/keycloak/vars.yml:1", describe(path(*prod, "Groups", "keycloak", "group_vars/keycloak/vars.yml").target))
        assertEquals("$PLATFORM/environments/prod/hosts.yml:80", describe(path("platform", "Environments", "prod", "Groups", "contracting", "analytics").target))
        assertEquals("golden/", describe(path("golden").target))
        assertEquals("$DANGER_ZONE/", describe(path("pelican › danger_zone/database").target))
        assertEquals("$PELICAN/environments/", describe(path("pelican › danger_zone/database", "Environments").target))

        val play = path("pelican › danger_zone/database", "Playbooks", "playbook-clone-to-replisync.yml", "Clone to replisync") as PlayNode
        assertEquals("hosts: replisync · 1 host", play.presentation().extra)
        assertEquals(play.play.location.file, play.target.file)
        assertEquals(play.play.location.offset, play.target.offset)
        assertEquals("$DANGER_ZONE/playbook-clone-to-replisync.yml:1", describe(path("pelican › danger_zone/database", "Playbooks", "playbook-clone-to-replisync.yml").target))

        val doubleClickOpens = listOf(path(*prod, "Hosts", "prod-prod1"), path(*prod, "Groups", "keycloak"), path(*prod, "Groups", "keycloak", "group_vars/keycloak/vars.yml"), play)
        assertTrue(doubleClickOpens.all { it.navigatesOnDoubleClick })
        assertFalse("roots and environments expand on double-click; F4 opens them", path("falcon").navigatesOnDoubleClick || path(*prod).navigatesOnDoubleClick)
    }

    // ------------------------------------------------------------------ refresh (plan F6.4)

    fun testEditingHostsYmlUpdatesTheModel() {
        val hostsFile = vf("$FALCON/environments/prod/hosts.yml")
        assertFalse("canary" in names(path("falcon", "Environments", "prod", "Groups")))
        val text = VfsUtilCore.loadText(hostsFile)
        WriteAction.runAndWait<Exception> {
            hostsFile.setBinaryContent((text + "canary:\n  hosts:\n    prod-prod2:\n  vars:\n    canary_flag: true\n").toByteArray())
        }
        // The workspace's background VFS listener bumps the structure tracker; a loaded document is reloaded and committed.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val groups = path("falcon", "Environments", "prod", "Groups")
        assertEquals("canary  inline (1): canary_flag · → prod-prod2", path(groups, "canary").presentation().text)
        assertEquals("prod  environments/prod/hosts.yml · 12 groups · 2 hosts", path("falcon", "Environments", "prod").presentation().text)
        assertTrue(
            "the new group is in the host's load order",
            texts(path("falcon", "Environments", "prod", "Hosts", "prod-prod2")).contains("hosts.yml: canary_flag  L3 inventory file vars of canary"),
        )
    }

    fun testNodesAreEqualByPathSoRefreshesKeepExpansion() {
        val before = path("falcon", "Environments", "prod", "Groups", "keycloak")
        val after = path("falcon", "Environments", "prod", "Groups", "keycloak")
        assertNotSame(before, after)
        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
        val underGroup = path("falcon", "Environments", "prod", "Groups", "keycloak", "prod-prod1")
        val underHosts = path("falcon", "Environments", "prod", "Hosts", "prod-prod1")
        assertFalse("the same host under two parents is two nodes", underGroup == underHosts)
    }

    // ------------------------------------------------------------------ sweep

    /** Every node and details pane of the fixture renders, and no vault payload ever reaches the tool window. */
    fun testSweepRendersEveryNodeWithoutVaultValues() {
        val started = System.nanoTime()
        val nodes = walk(workspaceNode())
        val texts = ArrayList<String>()
        for (node in nodes) {
            val presentation = node.presentation()
            texts += presentation.text
            texts += presentation.tooltip
            node.details()?.let { details ->
                texts += listOfNotNull(details.title, details.subtitle)
                details.sections.forEach { section -> section.items.forEach { texts += it.toString() } }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("TOOLWINDOW: fixture sweep: ${nodes.size} nodes, ${texts.size} texts in $millis ms")
        assertTrue("walked ${nodes.size} nodes", nodes.size > 500)
        val leaks = texts.filter { InfraTestData.containsVaultPayload(it) || it.contains("\$ANSIBLE_VAULT") || it.contains("!vault") }
        assertTrue(leaks.take(5).toString(), leaks.isEmpty())
        val missingKeys = texts.filter { MISSING_MESSAGE.containsMatchIn(it) }
        assertTrue("every message key exists: ${missingKeys.take(5)}", missingKeys.isEmpty())
        assertTrue("every node has a name", nodes.all { it.presentation().name.isNotBlank() })
        assertTrue("keys are unique", nodes.groupBy { it.key }.none { it.value.size > 1 })
    }

    fun testSnapshotBuildTime() {
        snapshot()
        val times = (1..20).map {
            refreshRoots()
            val started = System.nanoTime()
            snapshot()
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        println("TOOLWINDOW: snapshot rebuild after a structure change: median ${"%.1f".format(times[times.size / 2])} ms, max ${"%.1f".format(times.last())} ms")
        assertTrue("a rebuild takes well under the 1 s refresh budget: $times", times[times.size / 2] < 500)
    }

    private companion object {
        /** How DynamicBundle renders a key that is missing from the bundle. */
        val MISSING_MESSAGE = Regex("![a-z][a-zA-Z0-9_.]+!")
    }
}
