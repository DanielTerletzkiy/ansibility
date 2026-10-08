package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.index.RootFamily

/**
 * The data layer of plan amendment R20, D153: [VarViews] (VarServiceImpl per root and view), [VarUsageQuery] and
 * [RootFamily] with a [MoleculeView]. [MoleculeView.EXCLUDE] leaves out Molecule files and, judged by cause, the
 * `include_vars` keys of a production file only a converge play loads; the view-less [VarService] stays everything.
 */
class VarViewsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        add("ansible.cfg", "[defaults]\n")
        add("environments/prod/hosts.yml", "all:\n  children:\n    web:\n      hosts:\n        web1:\n")
        add("site.yml", "- hosts: web\n  roles: [web]\n  tasks:\n    - name: Shared vars\n      ansible.builtin.include_vars: shared/both.yml\n")
        add("roles/web/defaults/main.yml", "web_port: 80\n")
        add(TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ web_port }}\"\n")
        add(MOLECULE, "---\ndriver:\n  name: default\nprovisioner:\n  name: ansible\n  inventory:\n    group_vars:\n      all:\n        web_port: 8080\n")
        add(
            CONVERGE,
            """
            - hosts: all
              vars:
                web_port: 9090
              roles: [web]
              tasks:
                - name: Test vars
                  ansible.builtin.include_vars: ../../../../shared/extra.yml
                - name: Shared vars
                  ansible.builtin.include_vars: ../../../../shared/both.yml
                - name: Read
                  ansible.builtin.debug:
                    msg: "{{ web_port }}"
            """.trimIndent(),
        )
        add("shared/extra.yml", "extra_only_in_tests: 1\n")
        add("shared/both.yml", "both_loaded: 1\n")
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    private fun add(path: String, text: String) {
        myFixture.addFileToProject(path, text.trimEnd() + "\n")
    }

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun root(): AnsibleRoot = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots().single() }

    private fun relative(file: VirtualFile): String = VfsUtilCore.getRelativePath(file, myFixture.tempDirFixture.getFile("")!!)!!

    /** `path kind` of every definition of [name] in [view]. */
    private fun definitions(name: String, view: MoleculeView): List<String> = runReadActionBlocking {
        VarViews.symbol(project, root(), name, view).definitions.map { "${relative(it.location.file)} ${it.kind}" }
    }

    private fun names(view: MoleculeView): Collection<String> = runReadActionBlocking { VarViews.allNames(project, root(), view) }

    fun testExcludeLeavesOutMoleculeFilesAndKeepsTheViewlessServiceWhole() {
        val all = listOf(
            "roles/web/defaults/main.yml ROLE_DEFAULT",
            "$CONVERGE PLAY_VARS",
            "$MOLECULE MOLECULE_INVENTORY",
        )
        assertSameElements(definitions("web_port", MoleculeView.INCLUDE), all)
        assertEquals(listOf("roles/web/defaults/main.yml ROLE_DEFAULT"), definitions("web_port", MoleculeView.EXCLUDE))
        val viewless = runReadActionBlocking { VarService.getInstance(project).symbol(root(), "web_port") }
        assertEquals("the contract's symbol is INCLUDE", runReadActionBlocking { VarViews.symbol(project, root(), "web_port", MoleculeView.INCLUDE) }, viewless)
    }

    fun testIncludeVarsCountByCause() {
        assertEquals(listOf("shared/extra.yml INCLUDE_VARS"), definitions("extra_only_in_tests", MoleculeView.INCLUDE))
        assertEquals("only converge loads it", emptyList<String>(), definitions("extra_only_in_tests", MoleculeView.EXCLUDE))
        assertEquals("site.yml loads it too", listOf("shared/both.yml INCLUDE_VARS"), definitions("both_loaded", MoleculeView.EXCLUDE))
        assertTrue("extra_only_in_tests" in names(MoleculeView.INCLUDE))
        assertFalse("extra_only_in_tests" in names(MoleculeView.EXCLUDE))
        assertTrue("both_loaded" in names(MoleculeView.EXCLUDE))
        assertTrue("web_port" in names(MoleculeView.EXCLUDE))
    }

    fun testUsesAndTheRootFamilyFollowTheView() {
        val query = VarUsageQuery.getInstance(project)
        val included = runReadActionBlocking { query.usages(root(), "web_port").map { relative(it.location.file) } }
        assertSameElements(included, TASKS, CONVERGE)
        assertEquals(listOf(TASKS), runReadActionBlocking { query.usages(root(), "web_port", MoleculeView.EXCLUDE).map { relative(it.location.file) } })
        assertEquals(emptyList<Any>(), runReadActionBlocking { query.usagesIn(root(), vf(CONVERGE), "web_port", MoleculeView.EXCLUDE) })
        runReadActionBlocking {
            val context = AnsibleWorkspace.getInstance(project).contextOf(vf(CONVERGE))!!
            assertTrue(RootFamily.of(project, root()).admits(vf(CONVERGE), context))
            assertFalse(RootFamily.of(project, root(), view = MoleculeView.EXCLUDE).admits(vf(CONVERGE), context))
        }
    }

    /** Both views are cached per root and refresh after an edit of a Molecule file. */
    fun testBothViewsRefreshAfterAnEdit() {
        assertEquals(emptyList<String>(), definitions("added_in_tests", MoleculeView.INCLUDE))
        assertEquals(emptyList<String>(), definitions("added_in_tests", MoleculeView.EXCLUDE))
        val document = FileDocumentManager.getInstance().getDocument(vf(MOLECULE))!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "        added_in_tests: 1\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals(listOf("$MOLECULE ${VarDefKind.MOLECULE_INVENTORY}"), definitions("added_in_tests", MoleculeView.INCLUDE))
        assertEquals(emptyList<String>(), definitions("added_in_tests", MoleculeView.EXCLUDE))
        assertTrue("added_in_tests" in names(MoleculeView.INCLUDE))
        assertFalse("added_in_tests" in names(MoleculeView.EXCLUDE))
    }

    private companion object {
        const val TASKS = "roles/web/tasks/main.yml"
        const val MOLECULE = "roles/web/molecule/default/molecule.yml"
        const val CONVERGE = "roles/web/molecule/default/converge.yml"
    }
}
