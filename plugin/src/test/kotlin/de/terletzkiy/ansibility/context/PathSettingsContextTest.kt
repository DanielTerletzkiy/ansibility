package de.terletzkiy.ansibility.context

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.context.ContextTestTree.PLATFORM
import de.terletzkiy.ansibility.context.ContextTestTree.SANDBOX_SITE
import de.terletzkiy.ansibility.context.ContextTestTree.WT_FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.WT_GOLDEN
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.PathSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/** The path settings (plan "Coexistence & settings", Paths) change what [AnsibleWorkspaceImpl] reports, at query time. */
class PathSettingsContextTest : BasePlatformTestCase() {
    private lateinit var workspace: AnsibleWorkspaceImpl
    private lateinit var settings: AnsibilityProjectSettings

    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        workspace = AnsibleWorkspaceImpl.getInstance(project)!!
        workspace.structureChanged()
        settings = AnsibilityProjectSettings.getInstance(project)
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun root(path: String): AnsibleRoot = workspace.roots().single { it.dir == vf(path) }

    private fun paths(transform: (PathSettings) -> PathSettings) {
        settings.update { it.copy(paths = transform(it.paths)) }
    }

    private fun allFiles(): List<VirtualFile> {
        val files = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(vf(""), null) { file -> if (!file.isDirectory) files += file; true }
        return files
    }

    // ------------------------------------------------------------------ ignored paths

    fun testIgnoredPathsHaveNoContext() {
        val postfixTasks = vf("$FALCON/roles/postfix/tasks/main.yml")
        val haproxyTasks = vf("$GOLDEN/roles/haproxy/tasks/apt.yml")
        assertEquals(FileKind.ROLE_TASKS, workspace.contextOf(postfixTasks)?.kind)

        paths { it.copy(extraIgnoredPaths = it.extraIgnoredPaths + "repos/falcon/ansible/roles/postfix/**") }
        assertNull("globs are relative to the project directory", workspace.contextOf(postfixTasks))
        assertNull(workspace.contextOf(vf("$FALCON/roles/postfix/defaults/main.yml")))
        assertEquals("the rest of the root is unaffected", FileKind.PLAYBOOK, workspace.contextOf(vf("$FALCON/playbook-setup-system.yml"))?.kind)
        assertEquals(FileKind.ROLE_TASKS, workspace.contextOf(haproxyTasks)?.kind)

        paths { it.copy(extraIgnoredPaths = PathSettings.DEFAULT_IGNORED_PATHS) }
        assertEquals(FileKind.ROLE_TASKS, workspace.contextOf(postfixTasks)?.kind)
    }

    fun testFileNameGlobs() {
        paths { it.copy(extraIgnoredPaths = listOf("**/notes.yml")) }
        assertNull(workspace.contextOf(vf("$FALCON/notes.yml")))
        assertNotNull(workspace.contextOf(vf("$FALCON/site.yml")))
    }

    fun testTheBaselineSkipsStayWithoutTheDefaultGlobs() {
        paths { it.copy(extraIgnoredPaths = emptyList()) }
        assertNull(workspace.contextOf(vf("$FALCON/.ansible/roles/cached/tasks/main.yml")))
        assertNull(workspace.contextOf(vf("patches/roles/nginx/defaults/main.yml")))
    }

    fun testIgnoredDirectoriesHideTheirRoots() {
        assertTrue(workspace.roots().any { it.dir == vf(SANDBOX_SITE) })
        paths { it.copy(extraIgnoredPaths = it.extraIgnoredPaths + "sandbox/**") }
        assertFalse("the root walk does not enter ignored directories", workspace.roots().any { it.dir == vf(SANDBOX_SITE) })
        assertNull(workspace.rootFor(vf("$SANDBOX_SITE/playbook-deploy.yml")))
        assertEquals(listOf("wt-2"), workspace.detachedWorktrees().map { it.name })
    }

    // ------------------------------------------------------------------ detached rule

    fun testDetachedRuleOffKeepsWorktreeRootsAttached() {
        assertTrue(root(WT_FALCON).detached)
        paths { it.copy(detachedRule = false) }
        assertFalse(root(WT_FALCON).detached)
        assertFalse(root(WT_GOLDEN).detached)
        assertFalse(root(SANDBOX_SITE).detached)
        assertNull(workspace.worktreeOf(root(WT_FALCON)))
        assertTrue("no worktree is reported", workspace.detachedWorktrees().isEmpty())
        assertEquals("same labels fall back to their paths", "checkouts/.claude/worktrees/wt-2/repos/falcon/ansible", root(WT_FALCON).displayName)
        assertEquals(FALCON, root(FALCON).displayName)
        assertEquals("checkouts/.claude/worktrees/wt-2/golden", root(WT_GOLDEN).displayName)
        assertEquals(WT_FALCON, relative(workspace.contextOf(vf("$WT_FALCON/roles/postfix/tasks/main.yml"))!!.root.dir))

        paths { it.copy(detachedRule = true) }
        assertTrue(root(WT_FALCON).detached)
        assertEquals("[wt-2] falcon", root(WT_FALCON).displayName)
    }

    // ------------------------------------------------------------------ molecule support

    fun testMoleculeSupportOffReportsNoMoleculeKinds() {
        val config = vf("$PLATFORM/roles/docker-registry/molecule/cleanup/molecule.yml")
        assertEquals(FileKind.MOLECULE_CONFIG, workspace.contextOf(config)?.kind)
        val moleculeKinds = setOf(FileKind.MOLECULE_CONFIG, FileKind.MOLECULE_PLAYBOOK, FileKind.MOLECULE_TASKS, FileKind.MOLECULE_VARS)

        paths { it.copy(moleculeSupport = false) }
        val context = workspace.contextOf(config)!!
        assertEquals(FileKind.OTHER, context.kind)
        assertEquals("the file still belongs to its role", "docker-registry", context.roleName)
        assertNull(context.moleculeScenarioDir)
        assertNull(context.layer)
        val kinds = allFiles().mapNotNull { workspace.contextOf(it)?.kind }
        assertTrue(kinds.isNotEmpty())
        assertTrue("no molecule kind in $kinds", kinds.none { it in moleculeKinds })
        assertEquals("role files keep their kinds", FileKind.ROLE_TASKS, workspace.contextOf(vf("$GOLDEN/roles/chronod/tasks/main.yml"))?.kind)

        paths { it.copy(moleculeSupport = true) }
        assertEquals(FileKind.MOLECULE_CONFIG, workspace.contextOf(config)?.kind)
        assertEquals(FileKind.MOLECULE_VARS, workspace.contextOf(vf("$GOLDEN/roles/chronod/molecule/vars/vars.yml"))?.kind)
    }

    fun testMoleculeSupportOffHidesTheMoleculeInventories() {
        val inventories = InventoryService.getInstance(project)
        assertEquals(listOf("cleanup"), inventories.moleculeInventories(root(PLATFORM)).map { it.scenarioDir.name })
        assertEquals(listOf("chronod"), inventories.moleculeInventories(root(GOLDEN)).map { it.roleName })
        paths { it.copy(moleculeSupport = false) }
        assertEmpty(inventories.moleculeInventories(root(PLATFORM)))
        assertEmpty(inventories.moleculeInventories(root(GOLDEN)))
        paths { it.copy(moleculeSupport = true, extraIgnoredPaths = it.extraIgnoredPaths + "golden/roles/chronod/molecule/**") }
        assertEmpty("an ignored scenario has no inventory", inventories.moleculeInventories(root(GOLDEN)))
        assertEquals(1, inventories.moleculeInventories(root(PLATFORM)).size)
    }

    // ------------------------------------------------------------------ caching

    fun testCachedContextsFollowTheSettingsTracker() {
        val file = vf("$FALCON/roles/postfix/tasks/main.yml")
        assertSame(workspace.contextOf(file), workspace.contextOf(file))
        val before = settings.modificationTracker.modificationCount
        paths { it.copy(extraIgnoredPaths = listOf("repos/**")) }
        assertTrue(settings.modificationTracker.modificationCount > before)
        assertNull(workspace.contextOf(file))
        assertFalse("roots below an ignored directory are gone", workspace.roots().any { it.dir == vf(FALCON) })
    }

    private fun relative(file: VirtualFile): String = VfsUtilCore.getRelativePath(file, vf(""))!!
}
