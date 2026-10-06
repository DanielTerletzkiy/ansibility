package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.index.VarDefIndex
import de.terletzkiy.ansibility.index.VarUseIndex

/**
 * The caret highlighting's per-file data ([OpenFileIndexData]) is the indexes' data: for every file of the falcon
 * fixture (vars files, inventories, tasks, playbooks, templates, molecule files) the entries the indexers compute from
 * the open PSI equal those of `ansible.var.use` and `ansible.var.def`, also after an unsaved edit.
 */
@RequiresInfraFixture
class OpenFileIndexDataTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra("repos/falcon")
    }

    fun testEveryFileHasTheIndexedEntries() {
        val files = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(vf("repos/falcon"), null) { file -> if (!file.isDirectory) files += file; true }
        var uses = 0
        var definitions = 0
        runReadActionBlocking {
            val index = FileBasedIndex.getInstance()
            for (file in files) {
                val data = OpenFileIndexData.of(project, file)
                assertEquals("uses of ${file.path}", index.getFileData(VarUseIndex.NAME, file, project), data.uses)
                assertEquals("definitions of ${file.path}", index.getFileData(VarDefIndex.NAME, file, project), data.definitions)
                uses += data.uses.values.sumOf { it.size }
                definitions += data.definitions.values.sumOf { it.size }
            }
        }
        assertTrue("the fixture has uses ($uses) and definitions ($definitions) in ${files.size} files", uses > 1000 && definitions > 500)
    }

    fun testAnUnsavedEditIsSeenAtOnce() {
        val path = "repos/falcon/ansible/environments/prod/group_vars/keycloak/vars.yml"
        myFixture.configureFromTempProjectFile(path)
        val before = runReadActionBlocking { OpenFileIndexData.of(project, vf(path)) }
        assertSame("cached until the file changes", before, runReadActionBlocking { OpenFileIndexData.of(project, vf(path)) })
        assertNull(before.uses["fresh_probe_name"])
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.insertString(document.textLength, "fresh_probe_key: \"{{ fresh_probe_name }}\"\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        runReadActionBlocking {
            val after = OpenFileIndexData.of(project, vf(path))
            assertEquals(1, after.uses["fresh_probe_name"]?.size)
            assertEquals(1, after.definitions["fresh_probe_key"]?.size)
            assertEquals(FileBasedIndex.getInstance().getFileData(VarUseIndex.NAME, vf(path), project), after.uses)
            assertEquals(FileBasedIndex.getInstance().getFileData(VarDefIndex.NAME, vf(path), project), after.definitions)
        }
    }
}
