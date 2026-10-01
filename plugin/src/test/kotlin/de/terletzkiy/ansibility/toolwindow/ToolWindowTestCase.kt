package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.toolwindow.model.AnsibleTreeNode
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.TreeContext
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceNode
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshot
import de.terletzkiy.ansibility.toolwindow.model.WorkspaceSnapshotBuilder

/**
 * Base for the tool window tests: copies the sanitised infra fixture (line numbers identical to the real repo) into
 * the light project, with the synthetic detached worktree below `checkouts/` (the platform itself excludes
 * `<project>/.claude/worktrees`), and walks the headless tree model by node names.
 */
abstract class ToolWindowTestCase : BasePlatformTestCase() {
    override fun getTestDataPath(): String = InfraTestData.testDataPath.toString()

    /** Copies the whole fixture plus the worktree copy and re-detects the roots. */
    protected fun copyWholeFixture() {
        myFixture.copyDirectoryToProject(InfraTestData.INFRA, "")
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/${InfraTestData.WORKTREE_DIR}", "$WORKTREE_PARENT/${InfraTestData.WORKTREE_DIR}")
        refreshRoots()
    }

    /** Copies `infra/<path>` for each path to the same project path and re-detects the roots. */
    protected fun copyInfra(vararg paths: String) {
        for (path in paths) myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/$path", path)
        refreshRoots()
    }

    protected fun copyInfraFile(path: String) {
        myFixture.copyFileToProject("${InfraTestData.INFRA}/$path", path)
    }

    protected fun refreshRoots() {
        (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
    }

    protected fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    protected val context: TreeContext get() = TreeContext { PlayGraph.getInstance(project).playsOf(it) }

    protected fun snapshot(): WorkspaceSnapshot = runReadActionBlocking { WorkspaceSnapshotBuilder.build(project) }

    protected fun workspaceNode(): WorkspaceNode = WorkspaceNode(snapshot())

    protected fun children(node: AnsibleTreeNode): List<AnsibleTreeNode> = runReadActionBlocking { node.children(context) }

    protected fun names(node: AnsibleTreeNode): List<String> = children(node).map { it.presentation().name }

    protected fun texts(node: AnsibleTreeNode): List<String> = children(node).map { it.presentation().text }

    /** The node reached from [start] by following children with these presentation names. */
    protected fun path(start: AnsibleTreeNode, vararg names: String): AnsibleTreeNode {
        var node = start
        for (name in names) {
            node = children(node).firstOrNull { it.presentation().name == name }
                ?: error("no child '$name' under '${node.presentation().name}': ${names(node)}")
        }
        return node
    }

    protected fun path(vararg names: String): AnsibleTreeNode = path(workspaceNode(), *names)

    /** `path:line` of a navigation target relative to the project. */
    protected fun describe(target: NavigationTarget?): String {
        target ?: return "none"
        val base = myFixture.tempDirFixture.getFile("")!!
        val relative = VfsUtilCore.getRelativePath(target.file, base) ?: target.file.path
        if (target.file.isDirectory) return "$relative/"
        val text = VfsUtilCore.loadText(target.file)
        return "$relative:${StringUtil.offsetToLineNumber(text, target.offset) + 1}"
    }

    /** Every node below [node] (depth first), [node] included. */
    protected fun walk(node: AnsibleTreeNode, into: MutableList<AnsibleTreeNode> = ArrayList()): List<AnsibleTreeNode> {
        into += node
        for (child in children(node)) walk(child, into)
        return into
    }

    /** An indented dump of the tree below [node], [depth] levels deep. */
    protected fun dump(node: AnsibleTreeNode, depth: Int, indent: String = ""): String = buildString {
        append(indent).append(node.presentation().text).append('\n')
        if (depth > 0) for (child in children(node)) append(dump(child, depth - 1, "$indent  "))
    }

    companion object {
        const val WORKTREE_PARENT: String = "checkouts"
        const val FALCON: String = "repos/falcon/ansible"
        const val PLATFORM: String = "repos/platform/ansible"
        const val PELICAN: String = "repos/pelican/ansible"
        const val DANGER_ZONE: String = "repos/pelican/ansible/danger_zone/database"
    }
}
