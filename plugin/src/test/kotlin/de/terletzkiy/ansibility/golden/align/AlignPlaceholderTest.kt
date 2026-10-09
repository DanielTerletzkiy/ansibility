package de.terletzkiy.ansibility.golden.align

import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.BooleanGetter
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.vcsUtil.VcsUtil
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import java.nio.file.Files
import java.util.Base64
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * The placeholder rows of the merge workspace (plan amendment R24, D185) on the platform code the 262 Conflicts dialog
 * runs for every row (read from its bytecode; the dialog itself cannot be built headless): the synchronous refresh of
 * `AbstractVcsHelper`, the file tree (flat and by directory) and its renderer, the file status, the dirty scopes after
 * a resolution, the path the dialog groups and titles by, and the binary merge viewer a double click opens. None of
 * them may fail on a file that is not on disk, and none may touch the target.
 */
class AlignPlaceholderTest : GoldenLocalTestCase() {
    private fun snapshot(team: String): Map<String, String> {
        GoldenTestSupport.fsync()
        val dir = base.resolve(DriftFixture.roleDir(team))
        return Files.walk(dir).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate { it.relativeTo(dir).toString() to Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }
        }
    }

    fun testThePlatformPathsOfTheConflictsDialogLeaveTheTargetAlone() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val session = GoldenTestSupport.await { AlignService.getInstance(project).session(AlignRequest(roleCopy("missing"), roleCopy("golden"))) }
        val files: List<VirtualFile> = session.rows.map { it.file }
        val placeholders = files.filterIsInstance<AlignPlaceholder>()
        assertEquals("9 only in golden, 1 only in missing", 10, placeholders.size)
        val before = snapshot("missing")

        // AbstractVcsHelperImpl.showMergeDialogImpl: a synchronous refresh of the files in a write action.
        WriteAction.run<RuntimeException> { VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray()) }

        // The dialog's tree, flat and grouped by directory, rendered with its renderer (vcs.impl.shared is no compile
        // dependency of the plugin: reflection, tests only).
        val ui = "com.intellij.openapi.vcs.changes.ui."
        val factoryClass = Class.forName("${ui}ChangesGroupingPolicyFactory")
        val none = Class.forName("${ui}NoneChangesGroupingFactory").getField("INSTANCE").get(null)
        val support = Class.forName("${ui}ChangesGroupingSupport")
        val companion = support.getField("Companion").get(null)
        val byDirectory = companion.javaClass.getMethod("findFactory", String::class.java).invoke(companion, "directory")
        assertNotNull(byDirectory)
        val build = Class.forName("${ui}TreeModelBuilder").getMethod("buildFromVirtualFiles", Project::class.java, factoryClass, Collection::class.java)
        val rendererClass = Class.forName("${ui}ChangesBrowserNodeRenderer")
        for (grouping in listOf(none, byDirectory)) {
            val model = build.invoke(null, project, grouping, files) as DefaultTreeModel
            val listed = TreeUtil.treeNodeTraverser(model.root as DefaultMutableTreeNode).toList()
                .mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? VirtualFile }
            assertEquals("$grouping lists every row", files.toSet(), listed.toSet())
            val tree = Tree(model)
            TreeUtil.expandAll(tree)
            val flat = grouping === none
            val renderer = rendererClass.getConstructor(Project::class.java, BooleanGetter::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(project, BooleanGetter { flat }, false) as TreeCellRenderer
            for (row in 0 until tree.rowCount) {
                renderer.getTreeCellRendererComponent(tree, tree.getPathForRow(row).lastPathComponent, false, true, true, row, false)
            }
        }

        // The file status of the rows and the dirty scopes after a resolution.
        for (file in files) assertNotNull(FileStatusManager.getInstance(project).getStatus(file))
        VcsDirtyScopeManager.getInstance(project).filesDirty(files, emptyList())

        // The dialog titles and groups a row by its path: the placeholder's is where the file would be.
        val verify = placeholders.single { it.relPath == "molecule/default/verify.yml" }
        assertEquals("${roleDir("missing").path}/molecule/default/verify.yml", VcsUtil.getFilePath(verify).path)

        // A double click on a whole-only row opens the binary merge viewer with the row's file as output: whatever it
        // ends with, it writes the placeholder only.
        for (placeholder in placeholders) {
            val data = GoldenTestSupport.pooled { de.terletzkiy.ansibility.golden.vcs.impl.AlignMergeProvider(session).loadRevisions(placeholder) }
            for (result in listOf(MergeResult.LEFT, MergeResult.RIGHT, MergeResult.CANCEL)) {
                DiffRequestFactory.getInstance()
                    .createBinaryMergeRequest(project, placeholder, listOf(data.CURRENT, data.ORIGINAL, data.LAST), "merge", listOf("a", "b", "c"), null)
                    .applyResult(result)
            }
        }
        assertEquals("the target is untouched", before, snapshot("missing"))
        assertEquals("nothing was resolved", session.rows.size, session.outcome.open)
    }
}
