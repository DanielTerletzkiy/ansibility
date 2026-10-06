package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.UseContainer
import de.terletzkiy.ansibility.index.UseEntry
import de.terletzkiy.ansibility.index.VarUseIndex
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * FU2 on the sanitised infra fixture: the 18 `hostvars` members and the 3 braced implicit expressions the inventory
 * report counts (research `find-usages-inventory.md`, U14 and U10b) are in `ansible.var.use`, flagged and scoped like
 * every other use, and reach [VarUsageQuery] as [VarUsage.indirect].
 */
@RequiresInfraFixture
class VarUseIndirectFixtureTest : BasePlatformTestCase() {
    private lateinit var support: IndexFixtureSupport

    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    override fun setUp() {
        super.setUp()
        support = IndexFixtureSupport(myFixture)
        for (tree in listOf("golden", "repos/falcon", "repos/pelican", "repos/heron")) support.copy(tree)
        support.refreshRoots()
    }

    /** Every entry of `ansible.var.use` in the copied trees with its file, as `path:line name`. */
    private fun entries(): List<Triple<VirtualFile, String, UseEntry>> {
        val base = myFixture.tempDirFixture.getFile("")!!
        val index = FileBasedIndex.getInstance()
        val result = ArrayList<Triple<VirtualFile, String, UseEntry>>()
        VfsUtilCore.iterateChildrenRecursively(base, null) { file ->
            if (!file.isDirectory) {
                for ((name, list) in index.getFileData(VarUseIndex.NAME, file, project)) list.mapTo(result) { Triple(file, name, it) }
            }
            true
        }
        return result
    }

    fun testTheHostvarsMembersOfTheFixtureAreIndirectUses() {
        val indirect = entries().filter { it.third.isIndirect }
        assertEquals(
            listOf(
                "golden/roles/loki/molecule/default/prepare.yml:12 loki_version",
                "golden/roles/loki/molecule/default/prepare.yml:26 loki_version",
                "repos/falcon/ansible/roles/loki/molecule/default/prepare.yml:12 loki_version",
                "repos/falcon/ansible/roles/loki/molecule/default/prepare.yml:26 loki_version",
                "$PELICAN_ROLES/clone-percona-to-primary/tasks/main.yml:100 ansible_host",
                "$PELICAN_ROLES/clone-percona-to-primary/tasks/main.yml:39 ansible_host",
                "$PELICAN_ROLES/clone-percona-to-primary/tasks/main.yml:73 ansible_host",
                "$PELICAN_ROLES/clone-percona-to-primary/tasks/main.yml:85 ansible_host",
                "$PELICAN_ROLES/clone-percona-to-replica/tasks/main.yml:115 ansible_host",
                "$PELICAN_ROLES/clone-percona-to-replica/tasks/main.yml:39 ansible_host",
                "$PELICAN_ROLES/clone-percona-to-replica/tasks/main.yml:73 ansible_host",
                "$PELICAN_ROLES/clone-percona-to-replica/tasks/main.yml:85 ansible_host",
                "$PELICAN_ROLES/resync-primary-from-replica/tasks/main.yml:32 ansible_host",
                "$PELICAN_ROLES/setup-percona-replica-replication/tasks/main.yml:46 ansible_host",
                "$PELICAN_ROLES/xtrabackup-percona-to-replica/tasks/check-space.yml:16 ansible_port",
                "$PELICAN_ROLES/xtrabackup-percona-to-replica/tasks/check-space.yml:21 ansible_host",
                "$PELICAN_ROLES/xtrabackup-percona-to-replica/tasks/main.yml:52 ansible_port",
                "$PELICAN_ROLES/xtrabackup-percona-to-replica/tasks/main.yml:53 ansible_host",
            ),
            indirect.map { (file, name, entry) -> "${support.describe(file, entry.offset)} $name" }.sorted(),
        )
        assertTrue(indirect.all { it.third.indirect == JinjaIndirection.HOSTVARS && !it.third.called && it.third.attrPath.isEmpty() })
        for ((file, name, entry) in indirect) {
            assertEquals("the offset points at the member", name, VfsUtilCore.loadText(file).substring(entry.offset, entry.offset + name.length))
        }
    }

    fun testIndirectUsesReachVarUsageQuery() {
        val usages = VarUsageQuery.getInstance(project)
        val database = support.root("repos/pelican/ansible/danger_zone/database")
        val host = usages.usages(database, "ansible_host").filter { it.indirect != null }
        assertEquals(12, host.size)
        assertTrue(host.all { it.indirect == JinjaIndirection.HOSTVARS && it.container == JinjaContainer.YAML_TEMPLATE })
        assertEquals(2, usages.usages(database, "ansible_port").count { it.indirect == JinjaIndirection.HOSTVARS })
        assertTrue("an indirect use counts as a use", usages.hasUsages(database, "ansible_port"))

        val golden = support.root("golden")
        val loki = usages.usages(golden, "loki_version")
        assertEquals(
            listOf("golden/roles/loki/molecule/default/prepare.yml:12", "golden/roles/loki/molecule/default/prepare.yml:26"),
            loki.filter { it.indirect == JinjaIndirection.HOSTVARS }.map { support.describe(it.location) },
        )
        assertTrue("direct uses stay direct", loki.filter { "/prepare.yml" !in it.location.file.path }.all { it.indirect == null })
        assertTrue("the root's family never reaches the falcon copy", loki.none { "/repos/" in it.location.file.path })
    }

    fun testTheBracedExpressionsOfTheFixtureRecordTheirOutsideNames() {
        val braced = entries().filter { (file, _, entry) -> entry.container == UseContainer.YAML_EXPRESSION && inBracedScalar(file, entry.offset) }
        assertEquals(
            listOf(
                "golden/roles/coolify/molecule/default/verify.yml:60 coolify_env_content",
                "golden/roles/keycloak/molecule/default/verify.yml:60 compose_content",
                "repos/heron/ansible/roles/keycloak/molecule/default/verify.yml:60 compose_content",
            ),
            braced.map { (file, name, entry) -> "${support.describe(file, entry.offset)} $name" }.sorted(),
        )
        assertEquals(listOf("content"), braced.single { it.second == "compose_content" && "/golden/" in it.first.path }.third.attrPath)

        val usages = VarUsageQuery.getInstance(project).usages(support.root("golden"), "coolify_env_content")
            .filter { support.describe(it.location) == "golden/roles/coolify/molecule/default/verify.yml:60" }
        assertEquals(listOf(JinjaContainer.YAML_EXPRESSION), usages.map { it.container })
        assertNull(usages.single().indirect)
    }

    /** Whether the YAML scalar at [offset] of [file] is an implicit expression with braces (its value has markers). */
    private fun inBracedScalar(file: VirtualFile, offset: Int): Boolean {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return false
        val scalar = PsiTreeUtil.findElementOfClassAtOffset(psi, offset, YAMLScalar::class.java, false) ?: return false
        return JinjaBearing.hasTemplateMarkers(scalar.textValue)
    }

    private companion object {
        const val PELICAN_ROLES = "repos/pelican/ansible/danger_zone/database/roles"
    }
}
