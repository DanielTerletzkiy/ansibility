package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData

/**
 * Copies sub-trees of the infra fixture into a light test project (line numbers preserved) and names locations as
 * `path:line` relative to the project, like the plan's acceptance examples.
 */
internal class IndexFixtureSupport(private val fixture: CodeInsightTestFixture) {
    /** Copies `infra/<source>` to `<target>` (default: the same path). */
    fun copy(source: String, target: String = source): VirtualFile =
        fixture.copyDirectoryToProject("${InfraTestData.INFRA}/$source", target)

    /**
     * Copies the synthetic detached worktree below `checkouts/`, where the platform does not exclude it on its own, so
     * it is indexed and detected as a detached root like the real `.claude/worktrees` copy of the target repo.
     */
    fun copyWorktree() = copy(InfraTestData.WORKTREE_DIR, "$WORKTREE_PARENT/${InfraTestData.WORKTREE_DIR}")

    /** Re-detects roots after copying. */
    fun refreshRoots() {
        AnsibleWorkspaceImpl.getInstance(fixture.project)?.structureChanged()
    }

    fun root(path: String): AnsibleRoot {
        val dir = fixture.findFileInTempDir(path) ?: error("missing $path")
        return AnsibleWorkspace.getInstance(fixture.project).roots().singleOrNull { it.dir == dir }
            ?: error("no root at $path; roots: ${AnsibleWorkspace.getInstance(fixture.project).roots().map { it.dir.path }}")
    }

    fun file(path: String): VirtualFile = fixture.findFileInTempDir(path) ?: error("missing $path")

    /** `path:line` of [location] relative to the test project. */
    fun describe(location: SourceLocation): String = describe(location.file, location.offset)

    fun describe(file: VirtualFile, offset: Int): String {
        val base = fixture.tempDirFixture.getFile("")!!
        val text = VfsUtilCore.loadText(file)
        return "${VfsUtilCore.getRelativePath(file, base)}:${StringUtil.offsetToLineNumber(text, offset) + 1}"
    }

    companion object {
        const val WORKTREE_PARENT = "checkouts"
        const val WORKTREE_GOLDEN = "$WORKTREE_PARENT/${InfraTestData.WORKTREE_DIR}/golden"
    }
}
