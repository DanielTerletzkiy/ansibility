package de.terletzkiy.ansibility.context

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.role.RoleLayout

/** [RoleDirectories]: the one "is this a role" rule, and that the classifier, root detection and the registry agree on it. */
@RequiresInfraFixture
class RoleDirectoriesTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    private fun dir(path: String, vararg files: String): VirtualFile {
        for (file in files) myFixture.tempDirFixture.createFile("$path/$file", "")
        return myFixture.findFileInTempDir(path) ?: error("missing $path")
    }

    fun testWhatMakesARole() {
        assertTrue(RoleDirectories.isRole(dir("roles/a", "tasks/main.yml")))
        assertTrue("an empty tasks directory is enough", RoleDirectories.isRole(myFixture.tempDirFixture.findOrCreateDir("roles/b/tasks").parent))
        assertTrue(RoleDirectories.isRole(dir("roles/c", "meta/argument_specs.yml")))
        assertTrue(RoleDirectories.isRole(dir("roles/d", "meta/argument_specs.yaml")))
        assertTrue(RoleDirectories.isRole(dir("roles/e", "defaults/main.yml")))
        assertTrue(RoleDirectories.isRole(dir("roles/f", "defaults/main.json")))
        assertTrue(RoleDirectories.isRole(dir("roles/g", "defaults/main")))
        assertTrue("a defaults/main directory", RoleDirectories.isRole(dir("roles/h", "defaults/main/vars.yml")))
    }

    fun testWhatDoesNot() {
        assertFalse("role-state ghost", RoleDirectories.isRole(dir("roles/role-state", "callback_plugins/__pycache__/role_state.cpython-314.pyc")))
        assertFalse(RoleDirectories.isRole(dir("roles/i", "handlers/main.yml", "templates/site.conf.j2")))
        assertFalse("meta/main.yml alone", RoleDirectories.isRole(dir("roles/j", "meta/main.yml")))
        assertFalse("defaults without main", RoleDirectories.isRole(dir("roles/k", "defaults/other.yml")))
        assertFalse("defaults/main with a foreign extension", RoleDirectories.isRole(dir("roles/l", "defaults/main.txt")))
        assertFalse("a tasks file is not a tasks directory", RoleDirectories.isRole(dir("roles/m", "tasks")))
        assertFalse("a file", RoleDirectories.isRole(myFixture.tempDirFixture.createFile("roles/n.yml", "")))
    }

    fun testHasRoles() {
        dir("lib/roles/role-state", "callback_plugins/x.pyc")
        assertFalse(RoleDirectories.hasRoles(myFixture.findFileInTempDir("lib/roles")!!))
        dir("lib/roles/web", "tasks/main.yml")
        assertTrue(RoleDirectories.hasRoles(myFixture.findFileInTempDir("lib/roles")!!))
        assertTrue(RootDetector.hasRealRoles(myFixture.findFileInTempDir("lib/roles")!!))
    }

    /**
     * On the infra fixture: every directory under a roles dir is judged the same by the classifier, the registry's
     * layout and the shared rule, and every role directory that `contextOf` reports is listed by [RoleRegistry].
     */
    fun testClassifierRootDetectionAndRegistryAgreeOnTheInfraFixture() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        dir("golden/roles/templates-only", "templates/x.conf.j2", "handlers/main.yml")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        val workspace = AnsibleWorkspace.getInstance(project)
        val registry = RoleRegistry.getInstance(project)

        var roleDirs = 0
        for (root in workspace.roots()) {
            val listed = registry.roles(root).map { it.dir }.toSet()
            for (rolesDir in root.rolesDirs) {
                for (candidate in rolesDir.children.orEmpty().filter { it.isDirectory }) {
                    val isRole = RoleDirectories.isRole(candidate)
                    assertEquals(candidate.path, isRole, AnsibleFileClassifier.isRealRole(candidate))
                    assertEquals(candidate.path, isRole, RoleLayout.isRole(candidate))
                    if (isRole) roleDirs++
                }
            }
            val reported = HashSet<VirtualFile>()
            VfsUtilCore.iterateChildrenRecursively(root.dir, null) { file ->
                if (!file.isDirectory) workspace.contextOf(file)?.takeIf { it.root == root }?.roleDir?.let(reported::add)
                true
            }
            // Shadowed copies (a name found in an earlier roles dir) are not in roles(root) but resolve through roleOf.
            val unlisted = reported.filter { roleDir -> roleDir !in listed && registry.roleOf(roleDir)?.ref?.dir != roleDir }
            assertTrue("${root.displayName}: role dirs from contextOf missing in the registry: $unlisted", unlisted.isEmpty())
        }
        assertTrue("found $roleDirs roles", roleDirs > 20)
        val ghost = myFixture.findFileInTempDir("golden/roles/templates-only/handlers/main.yml")!!
        assertNull("files of a non-role directory have no context", workspace.contextOf(ghost))
        assertNull(registry.roleOf(ghost))
    }
}
