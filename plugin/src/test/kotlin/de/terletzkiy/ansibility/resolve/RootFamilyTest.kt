package de.terletzkiy.ansibility.resolve

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.index.RootFamily

/** Root scoping on a synthetic tree: nested roots, a worktree directory inside a root and `roles_path` outside it. */
class RootFamilyTest : BasePlatformTestCase() {
    private lateinit var support: IndexFixtureSupport

    override fun setUp() {
        super.setUp()
        support = IndexFixtureSupport(myFixture)
        mapOf(
            "site/ansible.cfg" to "[defaults]\nroles_path = ./roles:../shared/roles\n",
            "site/playbook-site.yml" to "- hosts: all\n  roles:\n    - common\n",
            "site/group_vars/all.yml" to "site_var: 1\n",
            "site/roles/local/defaults/main.yml" to "local_var: 1\n",
            // Not a root of its own (no ansible.cfg, no roles), but a worktree copy all the same.
            "site/.claude/worktrees/wt/group_vars/all.yml" to "site_var: 2\n",
            // A nested PROJECT root belongs to itself.
            "site/nested/ansible.cfg" to "[defaults]\n",
            "site/nested/group_vars/all.yml" to "site_var: 3\n",
            // A role library reached through roles_path.
            "shared/roles/common/defaults/main.yml" to "shared_var: 1\n",
            "shared/roles/common/tasks/main.yml" to "- ansible.builtin.debug:\n    msg: \"{{ site_var }}\"\n",
        ).forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        support.refreshRoots()
    }

    fun testScopeExcludesNestedRootsAndWorktreesAndIncludesRolesPath() {
        val site = support.root("site")
        val service = VarService.getInstance(project)
        assertEquals(listOf("site/group_vars/all.yml:1"), service.symbol(site, "site_var").definitions.map { support.describe(it.location) })
        assertEquals(listOf("site/nested/group_vars/all.yml:1"), service.symbol(support.root("site/nested"), "site_var").definitions.map { support.describe(it.location) })

        val shared = service.symbol(site, "shared_var").definitions.single()
        assertEquals("shared/roles/common/defaults/main.yml:1", support.describe(shared.location))
        assertEquals(VarDefKind.ROLE_DEFAULT, shared.kind)
        assertEquals("common", shared.roleName)
        assertEquals(setOf("site_var", "local_var", "shared_var"), service.allNames(site).toSet())

        val family = RootFamily.of(project, site)
        assertEquals(listOf(support.file("shared/roles")), family.familyDirs)
        assertFalse(family.scope.contains(support.file("site/.claude/worktrees/wt/group_vars/all.yml")))
        assertFalse(family.scope.contains(support.file("site/nested/group_vars/all.yml")))
        assertTrue(family.scope.contains(support.file("shared/roles/common/tasks/main.yml")))

        val usages = VarUsageQuery.getInstance(project).usages(site, "site_var")
        assertEquals(listOf("shared/roles/common/tasks/main.yml:2"), usages.map { support.describe(it.location) })
    }
}
