package de.terletzkiy.ansibility.index

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.resolve.IndexFixtureSupport
import de.terletzkiy.ansibility.resolve.IndexFixtureSupport.Companion.WORKTREE_GOLDEN

/** The six indexes through the real `FileBasedIndex`, with root-scoped queries on fixture sub-trees. */
@RequiresInfraFixture
class IndexQueriesFixtureTest : BasePlatformTestCase() {
    private lateinit var support: IndexFixtureSupport

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        support = IndexFixtureSupport(myFixture)
    }

    /** One copy of golden, falcon and the worktree for all index checks: copying and indexing is the expensive part. */
    fun testIndexesOnGoldenFalconAndWorktree() {
        support.copy("golden")
        support.copy("repos/falcon")
        support.copyWorktree()
        support.refreshRoots()
        checkHandlerIndex()
        checkModuleUseIndex()
        checkPlayIndex()
        checkTemplateUseIndex()
        checkIndexesSeeTheDetachedWorktreeOnlyFromItsOwnRoot()
    }

    private fun checkHandlerIndex() {
        val golden = support.root("golden")
        val reload = AnsibleIndexQueries.handlers(project, golden, "Reload haproxy")
        assertEquals(listOf("golden/roles/haproxy/handlers/main.yml:30"), reload.map { support.describe(it.file, it.value.offset) })
        assertFalse(reload.single().value.listen)
        val topic = AnsibleIndexQueries.handlers(project, golden, "Reload systemd")
        assertTrue(topic.any { it.value.listen })
        assertTrue("Reload haproxy" in AnsibleIndexQueries.handlerNames(project, golden))
        val falconHandlers = AnsibleIndexQueries.handlers(project, support.root("repos/falcon/ansible"), "Reload haproxy")
        assertTrue(falconHandlers.all { it.file.path.contains("/repos/falcon/") })
    }

    private fun checkModuleUseIndex() {
        val golden = support.root("golden")
        val uses = AnsibleIndexQueries.moduleUses(project, golden, "ansible.builtin.template").map { support.describe(it.file, it.value) }
        assertTrue(uses.toString(), "golden/roles/haproxy/tasks/configure.yml:3" in uses)
        assertTrue(uses.toString(), "golden/roles/haproxy/tasks/configure.yml:25" in uses)
        assertTrue(uses.none { it.startsWith("repos/") || it.startsWith("checkouts/") })
        val names = AnsibleIndexQueries.moduleNames(project, golden)
        assertTrue("ansible.builtin.template" in names && "ansible.builtin.systemd" in names)
    }

    private fun checkPlayIndex() {
        val falcon = support.root("repos/falcon/ansible")
        val plays = AnsibleIndexQueries.plays(project, falcon).filter { it.file.name == "playbook-setup-system.yml" }.map { it.value }
        assertEquals(listOf("Ping all hosts serially", "Debug", "System", "Monitoring Client", "KeepAliveD", "Proxy"), plays.map { it.name })
        assertEquals(listOf("all", "all", "system", "monitoring_client", "keepalived", "haproxy"), plays.map { it.hosts })
        val system = plays[2]
        assertEquals(
            listOf("system", "system-access", "system-apt", "system-heartbeat", "postfix", "inventory-docs-client", "iptables"),
            system.roles.map { it.name },
        )
        val file = support.file("repos/falcon/ansible/playbook-setup-system.yml")
        assertEquals("repos/falcon/ansible/playbook-setup-system.yml:52", support.describe(file, plays.last().roles.single().offset))
        assertTrue("golden has its own playbooks", AnsibleIndexQueries.plays(project, falcon).none { it.file.path.contains("/golden/") })
    }

    fun testNestedPlaybookRootsOwnTheirPlaysAndShareParentInventories() {
        support.copy("repos/pelican")
        support.refreshRoots()
        val pelican = support.root("repos/pelican/ansible")
        val dangerZone = support.root("repos/pelican/ansible/danger_zone/database")
        val pelicanPlays = AnsibleIndexQueries.plays(project, pelican).map { it.file.name }.toSet()
        assertEquals(setOf("playbook-setup-replisync.yml"), pelicanPlays)
        val zonePlays = AnsibleIndexQueries.plays(project, dangerZone)
        assertTrue(zonePlays.isNotEmpty() && zonePlays.all { it.file.path.contains("/danger_zone/database/") })

        val service = VarService.getInstance(project)
        val inherited = service.symbol(dangerZone, "ansible_user").definitions
        assertEquals(listOf("repos/pelican/ansible/environments/prod/hosts.yml:4"), inherited.map { support.describe(it.location) })
        assertEquals("all", inherited.single().group)
        assertEquals("prod", inherited.single().environment)
        val donorDefinitions = service.symbol(dangerZone, "percona_clone_donor_group").definitions
        assertEquals(
            listOf(
                "repos/pelican/ansible/danger_zone/database/playbook-clone-to-replisync.yml:21 PLAY_VARS",
                "repos/pelican/ansible/danger_zone/database/roles/clone-percona-to-replica/defaults/main.yml:6 ROLE_DEFAULT",
            ),
            donorDefinitions.map { "${support.describe(it.location)} ${it.kind}" },
        )
        val donor = donorDefinitions.single { it.kind == VarDefKind.ROLE_DEFAULT }
        assertTrue(donor.docComment!!.startsWith("Inventory group the datadir is cloned FROM."))
        assertTrue("the nested root's roles belong to itself", service.symbol(pelican, "percona_clone_donor_group").definitions.isEmpty())
    }

    private fun checkTemplateUseIndex() {
        val golden = support.root("golden")
        val static = AnsibleIndexQueries.renders(project, golden, "templates/haproxy.cfg.j2")
        assertEquals(listOf("golden/roles/haproxy/tasks/configure.yml:4"), static.map { support.describe(it.file, it.value.srcOffset) })
        assertEquals(SrcKind.STATIC, static.single().value.srcKind)
        val glob = AnsibleIndexQueries.renders(project, golden, "{{ item }}").map { it.value }.filter { it.srcKind == SrcKind.FILEGLOB }
        assertTrue(glob.any { it.globPattern == "/templates/config-*.alloy.j2" })
        val names = AnsibleIndexQueries.renderedNames(project, golden)
        assertTrue("templates/nginx/{{ item.floating.template }}" in names)
    }

    private fun checkIndexesSeeTheDetachedWorktreeOnlyFromItsOwnRoot() {
        val worktree = support.root(WORKTREE_GOLDEN)
        val index = FileBasedIndex.getInstance()
        val file = support.file("$WORKTREE_GOLDEN/roles/haproxy/defaults/main.yml")
        assertTrue("the worktree is indexed", "haproxy_stats_http_port" in index.getFileData(VarDefIndex.NAME, file, project))
        assertTrue(AnsibleIndexQueries.moduleUses(project, worktree, "ansible.builtin.debug").isNotEmpty())
        assertTrue(AnsibleIndexQueries.moduleUses(project, support.root("golden"), "ansible.builtin.debug").none { it.file.path.contains("checkouts/") })
    }
}
