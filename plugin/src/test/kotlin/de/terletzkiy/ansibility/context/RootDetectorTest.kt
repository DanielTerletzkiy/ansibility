package de.terletzkiy.ansibility.context

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RootKind

/** [RootDetector] and [AnsibleFileClassifier] on their own, without the platform's exclusions. */
class RootDetectorTest : BasePlatformTestCase() {

    private fun base() = myFixture.findFileInTempDir("")!!

    private fun relative(path: String) = path.substringAfter("/src/")

    fun testClaudeWorktreeAtTheProjectRootIsDetachedWhenNotExcluded() {
        ContextTestTree.create(myFixture)
        val scan = RootDetector().detect(listOf(base()))
        val wt1 = scan.roots.single { relative(it.dir.path) == ContextTestTree.EXCLUDED_WT_FALCON }
        assertTrue(wt1.detached)
        assertEquals("[wt-1] falcon", wt1.displayName)
        assertEquals(listOf("wt-1", "wt-2", "feature-x"), scan.worktrees.map { it.name })
    }

    fun testBaseDirectoryItselfIsNeverTreatedAsAWorktree() {
        myFixture.tempDirFixture.createFile(".git", "gitdir: /r/.git/worktrees/opened-worktree\n")
        myFixture.tempDirFixture.createFile("site/ansible.cfg", "[defaults]\n")
        val scan = RootDetector().detect(listOf(base()))
        assertFalse("a project opened on a worktree is not detached", scan.roots.single().detached)
    }

    fun testRolesPathFromAnsibleCfg() {
        myFixture.tempDirFixture.createFile("site/ansible.cfg", "[defaults]\nroles_path = ./galaxy_roles:./missing:../shared_roles:~/roles\n")
        myFixture.tempDirFixture.createFile("site/roles/own/tasks/main.yml", "- ansible.builtin.ping:\n")
        myFixture.tempDirFixture.createFile("site/galaxy_roles/geerlingguy.docker/tasks/main.yml", "- ansible.builtin.ping:\n")
        myFixture.tempDirFixture.createFile("shared_roles/common/tasks/main.yml", "- ansible.builtin.ping:\n")
        val scan = RootDetector().detect(listOf(base()))
        val site = scan.roots.single { it.kind == RootKind.PROJECT }
        assertEquals(listOf("site/roles", "site/galaxy_roles", "shared_roles"), site.rolesDirs.map { relative(it.path) })
        val context = AnsibleFileClassifier().classify(myFixture.findFileInTempDir("site/galaxy_roles/geerlingguy.docker/tasks/main.yml")!!, site).context!!
        assertEquals(FileKind.ROLE_TASKS, context.kind)
        assertEquals("geerlingguy.docker", context.roleName)
    }

    fun testDuplicateNamesAreDisambiguatedByPath() {
        myFixture.tempDirFixture.createFile("a/x/ansible/ansible.cfg", "[defaults]\n")
        myFixture.tempDirFixture.createFile("b/x/ansible/ansible.cfg", "[defaults]\n")
        val names = RootDetector().detect(listOf(base())).roots.map { it.displayName }
        assertEquals(listOf("a/x/ansible", "b/x/ansible"), names)
    }

    fun testNestedPlaybookNeedsPlaybooksAndRoles() {
        myFixture.tempDirFixture.createFile("p/ansible.cfg", "[defaults]\n")
        myFixture.tempDirFixture.createFile("p/tools/roles/r/tasks/main.yml", "- ansible.builtin.ping:\n")
        myFixture.tempDirFixture.createFile("p/ops/maintenance/playbook-restart.yml", "- hosts: all\n")
        myFixture.tempDirFixture.createFile("p/ops/maintenance/roles/restart/tasks/main.yml", "- ansible.builtin.ping:\n")
        val scan = RootDetector().detect(listOf(base()))
        assertEquals(
            listOf("p" to RootKind.PROJECT, "p/ops/maintenance" to RootKind.NESTED_PLAYBOOK),
            scan.roots.map { relative(it.dir.path) to it.kind },
        )
        assertEquals("p › ops/maintenance", scan.roots[1].displayName)
    }

    fun testRolesDirWithOnlyGhostsIsNoLibrary() {
        myFixture.tempDirFixture.createFile("lib/roles/role-state/callback_plugins/__pycache__/x.pyc", "")
        myFixture.tempDirFixture.createFile("plans/roles/app-mlflow/README.md", "# plan\n")
        assertTrue(RootDetector().detect(listOf(base())).roots.isEmpty())
    }

    fun testExcludedDirectoriesAreNotWalked() {
        ContextTestTree.create(myFixture)
        val scan = RootDetector(isExcluded = { it.name == "repos" }).detect(listOf(base()))
        assertFalse(scan.roots.any { relative(it.dir.path).startsWith("repos/") })
        assertTrue(scan.roots.any { relative(it.dir.path) == ContextTestTree.GOLDEN })
    }

    fun testDirectoryBudgetTruncatesTheScan() {
        ContextTestTree.create(myFixture)
        val scan = RootDetector(maxDirectories = 3).detect(listOf(base()))
        assertTrue(scan.truncated)
        assertFalse(RootDetector().detect(listOf(base())).truncated)
    }

    fun testDepthLimit() {
        myFixture.tempDirFixture.createFile("a/b/c/d/ansible.cfg", "[defaults]\n")
        assertTrue(RootDetector(maxDepth = 3).detect(listOf(base())).roots.isEmpty())
        assertEquals(1, RootDetector(maxDepth = 4).detect(listOf(base())).roots.size)
    }
}
