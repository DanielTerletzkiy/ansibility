package de.terletzkiy.ansibility.model.inventory

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.EffectiveVarsService
import de.terletzkiy.ansibility.api.InventoryService
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData

/**
 * Base for the inventory and effective-vars platform tests: copies sub-trees of the sanitised infra fixture
 * (`testData/infra`, line numbers preserved) or of `testData/model-inventory` into the test project, re-scans the
 * roots and offers path and line helpers.
 */
abstract class InventoryTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    protected val inventories: InventoryService get() = InventoryService.getInstance(project)

    protected val effective: EffectiveVarsService get() = EffectiveVarsService.getInstance(project)

    /** Copies `testData/<from>` to `<to>` in the project. */
    protected fun copyTree(from: String, to: String) {
        myFixture.copyDirectoryToProject(from, to)
    }

    /** Copies `testData/infra/<path>` (a directory) to the same path in the project. */
    protected fun copyInfraTree(path: String) = copyTree("${InfraTestData.INFRA}/$path", path)

    /** Copies `testData/infra/<path>` (a file) to the same path in the project. */
    protected fun copyInfraFile(path: String) {
        myFixture.copyFileToProject("${InfraTestData.INFRA}/$path", path)
    }

    /** Re-scans the roots after the test files were created. */
    protected fun refreshRoots() {
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected fun root(path: String): AnsibleRoot =
        AnsibleWorkspace.getInstance(project).roots().singleOrNull { it.dir == vf(path) } ?: error("no root at $path")

    /** The project-relative path of [file]. */
    protected fun rel(file: VirtualFile): String = file.path.substringAfter("/src/")

    /** The 1-based line of [offset] in [file]. */
    protected fun line(file: VirtualFile, offset: Int): Int =
        FileDocumentManager.getInstance().getDocument(file)!!.getLineNumber(offset) + 1

    protected fun at(location: SourceLocation?): String = location?.let { "${rel(it.file)}:${line(it.file, it.offset)}" } ?: "none"

    protected fun at(ref: VarSourceRef): String = "${rel(ref.file)}:${line(ref.file, ref.offset)}"
}
