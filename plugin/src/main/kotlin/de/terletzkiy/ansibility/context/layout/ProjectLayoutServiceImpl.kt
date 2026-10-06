package de.terletzkiy.ansibility.context.layout

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.api.RootLayout
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.PlaybookProbe
import de.terletzkiy.ansibility.settings.layout.LayoutSettings
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's [ProjectLayoutService]: one [LayoutResolver] run per root, cached until the workspace structure or
 * the project roots or the Layout settings change, or a file the resolver read (an INI candidate, a probed playbook) is saved.
 */
class ProjectLayoutServiceImpl(private val project: Project) : ProjectLayoutService {
    private class Entry(val stamp: Long, val inputs: List<Pair<VirtualFile, Long>>, val layout: RootLayout)

    private val entries = ConcurrentHashMap<VirtualFile, Entry>()
    private val tracker = SimpleModificationTracker()

    override val modificationTracker: ModificationTracker get() = tracker

    override fun layout(root: AnsibleRoot): RootLayout {
        if (root.kind == RootKind.ROLE_LIBRARY || project.isDisposed) return RootLayout.empty(root)
        val stamp = stamp()
        entries[root.dir]?.let { entry ->
            if (entry.stamp == stamp && entry.layout.root == root && entry.inputs.all { (f, s) -> f.isValid && f.modificationStamp == s }) {
                return entry.layout
            }
        }
        val inputs = ArrayList<Pair<VirtualFile, Long>>()
        val cfgDir = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir ?: root.dir else root.dir
        val cfgFile = cfgDir.findChild(AnsibleLayout.ANSIBLE_CFG)?.takeIf { !it.isDirectory }
        val cfg = AnsibleWorkspaceImpl.getInstance(project)?.configOf(root)
        val resolver = LayoutResolver(
            readText = ::readText,
            isPlaybook = { file -> inputs += file to file.modificationStamp; PlaybookProbe.looksLikePlaybook(file) },
            onRead = { file -> inputs += file to file.modificationStamp },
        )
        val settings = LayoutSettings.getInstance(project).of(root.dir)
        val layout = readLocked { resolver.resolve(root, cfg, cfgFile, settings) }
        val previous = entries.put(root.dir, Entry(stamp, inputs, layout))
        if (previous != null && previous.layout.fingerprint != layout.fingerprint) tracker.incModificationCount()
        return layout
    }

    private fun stamp(): Long =
        AnsibleWorkspace.getInstance(project).structureTracker.modificationCount + ProjectRootManager.getInstance(project).modificationCount +
            LayoutSettings.getInstance(project).modificationTracker.modificationCount

    private fun readText(file: VirtualFile): String? {
        if (file.length > MAX_INVENTORY_SIZE) return null
        return try {
            VfsUtilCore.loadText(file)
        } catch (e: IOException) {
            LOG.debug("Cannot read ${file.path}", e)
            null
        }
    }

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        private val LOG = logger<ProjectLayoutServiceImpl>()
        private const val MAX_INVENTORY_SIZE = 2L * 1024 * 1024
    }
}
