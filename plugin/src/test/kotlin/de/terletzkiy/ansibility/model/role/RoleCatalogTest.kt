package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.drift.DriftFixture

/**
 * [RoleCatalog] (plan amendment R9, F9.3): copies across roots, the reference, display order, attribution of each role
 * directory to the innermost root (nested roots and `roles_path` reach other roots' roles dirs), detached worktrees.
 */
@RequiresInfraFixture
class RoleCatalogTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = ModelFixture.testDataPath

    private val catalog: RoleCatalog get() = RoleCatalog.getInstance(project)

    private fun vf(path: String): VirtualFile = ModelFixture.file(myFixture, path)

    private fun owners(name: String): List<String> = catalog.copies(name).map { it.root.displayName }

    fun testInfraFixtureCopies() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        ModelFixture.rescan(project)
        val snapshot = catalog.snapshot()

        assertEquals(listOf("golden", "falcon"), owners("haproxy"))
        assertEquals("the detached worktree's haproxy is never a copy", 2, snapshot.copies("haproxy").size)
        assertNull(snapshot.copyContaining(vf("${InfraTestData.WORKTREE_DIR}/golden/roles/haproxy/tasks/main.yml")))
        assertEquals(listOf("golden", "heron"), owners("keycloak"))
        assertEquals("platform's grafana holds one template only, which is no role", listOf("golden", "falcon"), owners("grafana"))
        assertEquals(listOf("golden", "falcon"), owners("docker"))
        assertEquals(listOf("falcon"), owners("jenkins-agent-docker"))
        assertEquals(listOf("wren"), owners("app-wren-mono"))
        assertEquals(listOf("platform"), owners("puppet-migration"))
        assertEquals(listOf("golden"), owners("percona"))

        val dangerZone = catalog.copies("clone-percona-to-primary").single()
        assertEquals(RootKind.NESTED_PLAYBOOK, dangerZone.root.kind)
        assertEquals("pelican › danger_zone/database", dangerZone.root.displayName)
        assertFalse(dangerZone.isReference)
        assertNull(snapshot.reference("clone-percona-to-primary"))
        assertEquals(17, snapshot.copiesOf(dangerZone.root).size)

        val golden = snapshot.reference("haproxy")!!
        assertTrue(golden.isReference)
        assertEquals(RootKind.ROLE_LIBRARY, golden.root.kind)
        assertEquals(golden.root.dir, golden.ref.rootDir)
        assertEquals(vf("golden/roles/haproxy"), golden.dir)
        assertEquals(18, snapshot.copiesOf(golden.root).size)

        assertEquals("names measured on the fixture", 38, snapshot.names.size)
        assertEquals("18 golden + 9 falcon + heron + platform + wren + 17 danger zone", 47, snapshot.copyCount)
        assertEquals(
            listOf("docker", "grafana", "haproxy", "keycloak", "loki", "nginx", "postfix", "system", "totp-token"),
            snapshot.names.filter { snapshot.copies(it).size > 1 },
        )
        assertEquals(9, snapshot.sharedNameCount)
    }

    fun testLookupsByDirectoryFileAndPath() {
        DriftFixture.copy(myFixture)
        val web = vf("repos/mol/ansible/roles/web")
        val copy = catalog.copyOf(web)!!
        assertEquals("web", copy.name)
        assertEquals("mol", copy.root.displayName)
        val file = vf("repos/mol/ansible/roles/web/molecule/default/verify.yml")
        assertEquals(copy, catalog.copyContaining(file))
        assertEquals(copy, catalog.snapshot().copyContainingPath(file.path))
        assertEquals("a deleted file's path still finds its copy", copy, catalog.snapshot().copyContainingPath("${web.path}/tasks/gone.yml"))
        assertEquals(copy, catalog.snapshot().copyContainingPath(web.path))
        assertNull(catalog.snapshot().copyContainingPath(web.parent.path))
        assertNull(catalog.copyContaining(vf("repos/mol/ansible/ansible.cfg")))
        assertEquals("web", catalog.reference("web")?.name)
        assertEquals(vf("golden/roles/web"), catalog.reference("web")?.dir)
    }

    fun testNestedRootsAndRolesPathsAttributeEachDirectoryOnce() {
        DriftFixture.copy(myFixture)
        DriftFixture.write(myFixture, "repos/same/ansible/danger_zone/db/playbook-db.yml", "---\n- hosts: all\n  roles:\n    - dbrole\n")
        DriftFixture.write(myFixture, "repos/same/ansible/danger_zone/db/roles/dbrole/tasks/main.yml", "---\n- name: Db\n  ansible.builtin.meta: noop\n")
        DriftFixture.write(myFixture, "repos/mol/ansible/ansible.cfg", "[defaults]\nroles_path = ./roles:../../same/ansible/roles\n")
        ModelFixture.rescan(project)

        val workspace = AnsibleWorkspace.getInstance(project)
        val nested = workspace.roots().single { it.kind == RootKind.NESTED_PLAYBOOK }
        val mol = workspace.roots().single { it.displayName == "mol" }
        val registry = RoleRegistry.getInstance(project)
        assertTrue("the nested root also reaches its parent's roles", registry.roles(nested).any { it.name == "web" })
        assertTrue("mol's roles_path reaches same's roles", registry.roles(mol).any { it.dir == vf("repos/same/ansible/roles/solo") })

        val snapshot = catalog.snapshot()
        assertEquals(listOf("golden") + DriftFixture.WEB_REPOS, owners("web"))
        assertEquals(listOf("golden", "mol", "same"), owners("base"))
        assertEquals(listOf("same › danger_zone/db"), owners("dbrole"))
        assertEquals("same's solo stays same's, although mol reaches it", listOf("same", "tasks"), owners("solo"))
        assertEquals("14 copies plus dbrole: no directory counted twice", 15, snapshot.copyCount)
        assertEquals(snapshot.copyCount, snapshot.names.sumOf { snapshot.copies(it).size })
        assertEquals("ref names the owning root", vf("golden"), catalog.copyOf(vf("golden/roles/web"))!!.ref.rootDir)
    }

    fun testOrderIsLibrariesThenProjectsThenNestedRoots() {
        DriftFixture.copy(myFixture)
        DriftFixture.write(myFixture, "repos/aaa/ansible/ansible.cfg", "[defaults]\n")
        DriftFixture.write(myFixture, "repos/aaa/ansible/danger_zone/db/playbook-db.yml", "---\n- hosts: all\n  roles: []\n")
        DriftFixture.write(myFixture, "repos/aaa/ansible/danger_zone/db/roles/base/tasks/main.yml", "---\n[]\n")
        DriftFixture.write(myFixture, "repos/zzz/ansible/ansible.cfg", "[defaults]\n")
        DriftFixture.write(myFixture, "repos/zzz/ansible/roles/base/tasks/main.yml", "---\n[]\n")
        ModelFixture.rescan(project)
        assertEquals(listOf("golden", "mol", "same", "zzz", "aaa › danger_zone/db"), owners("base"))
    }

    fun testTheCatalogFollowsStructureChanges() {
        DriftFixture.copy(myFixture)
        val first = catalog.snapshot()
        assertSame("cached until the structure changes", first, catalog.snapshot())
        val stamp = catalog.modificationTracker.modificationCount
        DriftFixture.write(myFixture, "repos/spec/ansible/roles/extra/tasks/main.yml", "---\n[]\n")
        ModelFixture.rescan(project)
        assertTrue(catalog.modificationTracker.modificationCount > stamp)
        assertNotSame(first, catalog.snapshot())
        assertEquals(listOf("spec"), owners("extra"))
    }
}
