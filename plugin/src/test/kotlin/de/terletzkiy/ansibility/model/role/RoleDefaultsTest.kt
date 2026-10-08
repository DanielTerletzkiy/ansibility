package de.terletzkiy.ansibility.model.role

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.semantics.yaml.YScalar

/**
 * "The role default" (plan amendment R23, D173): the files ansible-core loads from `defaults/` (`Role._load_role_yaml`,
 * `DataLoader.find_vars_files`), the winner of a name (later files and later duplicate keys win), dependency defaults
 * behind the role's own, and where a new default goes.
 */
class RoleDefaultsTest : BasePlatformTestCase() {
    private fun file(path: String, text: String = "") = myFixture.tempDirFixture.createFile(path, text)

    private fun dir(role: String): VirtualFile = myFixture.findFileInTempDir("$ROOT/roles/$role") ?: error("no role $role")

    private fun names(role: String, subdir: String = RoleLayout.DEFAULTS, from: String? = null): List<String> =
        runReadActionBlocking { RoleDefaults.loadedFiles(dir(role), subdir, from).map { VfsUtilCore.getRelativePath(it, dir(role))!! } }

    override fun setUp() {
        super.setUp()
        file("$ROOT/ansible.cfg", "[defaults]\nroles_path = roles\n")
        file("$ROOT/site.yml", "- hosts: all\n  roles: [both, dir, yaml, app]\n")
        file("$ROOT/roles/both/defaults/main.yml", "both_size: 64\n")
        file("$ROOT/roles/both/defaults/main/10.yml", "both_size: 128\n")
        file("$ROOT/roles/both/defaults/extra.yml", "both_size: 1\n")
        file("$ROOT/roles/both/defaults/extra2/b.yml", "both_size: 2\n")
        file("$ROOT/roles/both/defaults/extra2/a.yml", "both_size: 3\n")
        file("$ROOT/roles/dir/defaults/main/10-a.yml", "dir_version: 1.1\ndir_port: 1\ndir_port: 2\n")
        file("$ROOT/roles/dir/defaults/main/a/x.yml", "dir_version: 1.2\n")
        file("$ROOT/roles/dir/defaults/main/a.yml", "dir_version: '1.3'\n")
        file("$ROOT/roles/dir/defaults/main/z", "dir_last: true\n")
        file("$ROOT/roles/dir/defaults/main/20.json", "{\"dir_json\": 1}\n")
        file("$ROOT/roles/dir/defaults/main/.hidden.yml", "dir_version: hidden\n")
        file("$ROOT/roles/dir/defaults/main/backup.yml~", "dir_version: backup\n")
        file("$ROOT/roles/dir/defaults/main/notes.txt", "dir_version: notes\n")
        file("$ROOT/roles/dir/defaults/main/sub.d/y.yml", "dir_version: sub\n")
        file("$ROOT/roles/yaml/defaults/main.yaml", "yaml_value: 1\n")
        file("$ROOT/roles/yaml/vars/main.yml", "yaml_var: 1\n")
        file("$ROOT/roles/app/meta/main.yml", "dependencies:\n  - base\n  - other\n")
        file("$ROOT/roles/app/defaults/main.yml", "app_own: 1\n")
        file("$ROOT/roles/base/defaults/main.yml", "app_shared: base\nvault_app_password: abc\n")
        file("$ROOT/roles/other/defaults/main.yml", "app_shared: other\n")
        file("$ROOT/roles/locked/defaults/main.yml", "\$ANSIBLE_VAULT;1.1;AES256\n61626364\n")
        file("$ROOT/roles/locked/tasks/main.yml", "- ansible.builtin.debug:\n    msg: hi\n")
        file("$ROOT/roles/bare/tasks/main.yml", "- ansible.builtin.debug:\n    msg: hi\n")
        file("$ROOT/roles/vaulted/defaults/main/10-main.yml", "vaulted_port: 80\n")
        file("$ROOT/roles/vaulted/defaults/main/20-secret.yml", "\$ANSIBLE_VAULT;1.1;AES256\n61626364\n")
        file("$ROOT/roles/vaulted/defaults/main/vault.yml", "vaulted_password: plain\n")
        file("$ROOT/roles/jsononly/defaults/main.json", "{\"jsononly_a\": 1}\n")
        file("$ROOT/roles/empty/defaults/main.yml", "---\n# nothing yet\n")
        file("$ROOT/roles/top/meta/main.yml", "dependencies:\n  - left\n  - right\n")
        file("$ROOT/roles/left/meta/main.yml", "dependencies:\n  - shared\n")
        file("$ROOT/roles/right/meta/main.yml", "dependencies:\n  - shared\n")
        file("$ROOT/roles/left/defaults/main.yml", "top_x: left\n")
        file("$ROOT/roles/shared/defaults/main.yml", "top_x: shared\n")
        file("$ROOT/roles/right/defaults/main.yml", "top_y: right\n")
        file("$ROOT/roles/later/meta/main.yml", "dependencies:\n  - base\n  - locked\n")
        for (role in listOf("top", "left", "right", "shared", "later")) file("$ROOT/roles/$role/tasks/main.yml", "- ansible.builtin.debug:\n    msg: hi\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testAMainFileHidesTheDirectoryAndOtherFilesNeedDefaultsFrom() {
        assertEquals(listOf("defaults/main.yml"), names("both"))
        assertEquals(listOf("defaults/extra.yml"), names("both", from = "extra"))
        assertEquals(listOf("defaults/extra.yml"), names("both", from = "extra.yml"))
        assertEquals("a directory named by defaults_from loads sorted", listOf("defaults/extra2/a.yml", "defaults/extra2/b.yml"), names("both", from = "extra2"))
        assertEquals("a templated defaults_from cannot be known", listOf("defaults/main.yml"), names("both", from = "{{ x }}"))
        assertEquals(listOf("defaults/main.yaml"), names("yaml"))
        assertEquals(listOf("vars/main.yml"), names("yaml", RoleLayout.VARS))
        assertEquals(emptyList<String>(), names("yaml", RoleLayout.VARS, from = "missing"))
    }

    fun testADirectoryLoadsSortedPerLevelRecursivelyAndSkipsWhatAnsibleSkips() {
        // `a` (the directory) sorts before `a.yml` per level, as `sorted(os.listdir())` does in each directory.
        assertEquals(
            listOf("defaults/main/10-a.yml", "defaults/main/20.json", "defaults/main/a/x.yml", "defaults/main/a.yml", "defaults/main/z"),
            names("dir"),
        )
    }

    fun testTheLastFileAndTheLastDuplicateKeyWin() {
        val scan = runReadActionBlocking { RoleDefaults.scan(project, dir("dir")) }
        val version = scan.defaults.getValue("dir_version")
        assertEquals("1.3", (version.value as YScalar).text)
        assertEquals("a.yml", version.file.name)
        assertEquals("2", (scan.defaults.getValue("dir_port").value as YScalar).text)
        assertEquals(listOf("dir_port", "dir_json", "dir_version", "dir_last"), scan.defaults.keys.toList())
        assertFalse(scan.opaque)
        val both = runReadActionBlocking { RoleDefaults.of(project, dir("both"), "both_size") }!!
        assertEquals("64", (both.value as YScalar).text)
        assertEquals("both_size: 64\n".indexOf("both_size"), both.keyOffset)
    }

    private fun appendTarget(role: String): String = runReadActionBlocking {
        when (val target = RoleDefaults.appendTarget(project, dir(role))) {
            is RoleDefaults.AppendTarget.Existing -> VfsUtilCore.getRelativePath(target.file, dir(role))!!
            RoleDefaults.AppendTarget.Create -> "create"
            RoleDefaults.AppendTarget.None -> "none"
        }
    }

    fun testWhereANewDefaultGoes() {
        assertEquals("defaults/main.yml", appendTarget("both"))
        assertEquals("defaults/main/z", appendTarget("dir"))
        assertEquals("none yet: defaults/main.yml is to be created", "create", appendTarget("bare"))
        assertEquals("an empty main file takes the line", "defaults/main.yml", appendTarget("empty"))
    }

    fun testANewDefaultNeverGoesIntoAVaultOrJsonFile() {
        // vault.yml (a vault file name) and 20-secret.yml (a whole-file vault) sort after 10-main.yml.
        assertEquals("defaults/main/10-main.yml", appendTarget("vaulted"))
        assertEquals("a whole-file vault as the only file", "none", appendTarget("locked"))
        assertEquals("YAML appended to JSON breaks it", "none", appendTarget("jsononly"))
    }

    fun testAWinnerLoadedBeforeAnUnreadableFileMayBeOverridden() {
        runReadActionBlocking {
            val scan = RoleDefaults.scan(project, dir("vaulted"))
            assertTrue(scan.opaque)
            assertTrue("20-secret.yml loads after 10-main.yml", scan.mayBeOverridden(scan.defaults.getValue("vaulted_port")))
            assertFalse(scan.mayBeOverridden(scan.defaults.getValue("vaulted_password")))
            val root = AnsibleWorkspace.getInstance(project).rootFor(dir("vaulted"))!!
            val vaulted = RoleRegistry.getInstance(project).role(root, "vaulted")!!
            assertTrue((RoleDefaults.lookup(project, root, vaulted, "vaulted_port") as RoleDefaults.Lookup.Own).uncertain)
            val later = RoleRegistry.getInstance(project).role(root, "later")!!
            val shared = RoleDefaults.lookup(project, root, later, "app_shared") as RoleDefaults.Lookup.Dependency
            assertTrue("the vault of a dependency applied later may override it", shared.uncertain)
        }
    }

    fun testASharedDependencyIsAppliedAgainWhereItRecurs() {
        // ansible-core 2.21: `top` → left → shared, right → shared applies shared, left, shared, right: shared's top_x wins.
        runReadActionBlocking {
            val root = AnsibleWorkspace.getInstance(project).rootFor(dir("top"))!!
            val top = RoleRegistry.getInstance(project).role(root, "top")!!
            assertEquals(listOf("left", "shared", "right"), RoleDefaults.dependencies(project, root, top).map { it.ref.name })
            val x = RoleDefaults.lookup(project, root, top, "top_x") as RoleDefaults.Lookup.Dependency
            assertEquals("shared", (x.default.value as YScalar).text)
        }
    }

    fun testDependencyDefaultsCountOnlyWithoutAnOwnDefault() {
        runReadActionBlocking {
            val root = AnsibleWorkspace.getInstance(project).rootFor(dir("app"))!!
            val app = RoleRegistry.getInstance(project).role(root, "app")!!
            assertEquals(listOf("base", "other"), RoleDefaults.dependencies(project, root, app).map { it.ref.name })
            assertInstanceOf(RoleDefaults.lookup(project, root, app, "app_own"), RoleDefaults.Lookup.Own::class.java)
            val shared = RoleDefaults.lookup(project, root, app, "app_shared") as RoleDefaults.Lookup.Dependency
            assertEquals("the later dependency wins", "other", (shared.default.value as YScalar).text)
            assertTrue(RoleDefaults.lookup(project, root, app, "vault_app_password").let { it is RoleDefaults.Lookup.Dependency && it.default.isSecret })
            assertFalse((RoleDefaults.lookup(project, root, app, "app_missing") as RoleDefaults.Lookup.None).opaque)
            val locked = RoleRegistry.getInstance(project).role(root, "locked")!!
            assertTrue("a whole-file vault proves nothing", (RoleDefaults.lookup(project, root, locked, "anything") as RoleDefaults.Lookup.None).opaque)
        }
    }

    private companion object {
        const val ROOT = "site"
    }
}
