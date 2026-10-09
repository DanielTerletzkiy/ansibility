package de.terletzkiy.ansibility.golden.history

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Whether a role copy has local VCS changes (plan amendment R24, D188: Push warns "uncommitted changes in this role",
 * because a push would overwrite them). Implemented by `golden.vcs.impl.PushLocalChangesLookup`, which only the
 * optional fragment `ansibility-vcs.xml` registers (it loads with `com.intellij.modules.vcs`), so no always-loaded
 * class touches a VCS class. Callers use [hasLocalChanges] of the companion; without an extension the answer is null.
 */
interface LocalChangesLookup {
    /**
     * Whether the directory [dir] has local changes: a modified, added, deleted or moved file below it, or an
     * unversioned one. Null when unknown (no VCS manages it, the project is closing). Blocking: call on a background
     * thread, never on the EDT or under a read lock.
     */
    fun hasLocalChanges(project: Project, dir: VirtualFile): Boolean?

    companion object {
        private val LOG = logger<LocalChangesLookup>()

        val EP_NAME: ExtensionPointName<LocalChangesLookup> = ExtensionPointName("de.terletzkiy.ansibility.localChangesLookup")

        /** The first answer of the registered lookups; null without any (no VCS support) or when none knows. */
        fun hasLocalChanges(project: Project, dir: VirtualFile): Boolean? {
            if (project.isDisposed || !dir.isValid) return null
            for (lookup in EP_NAME.extensionList) {
                try {
                    lookup.hasLocalChanges(project, dir)?.let { return it }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.debug("Local changes of ${dir.path} failed (${e.javaClass.name})")
                }
            }
            return null
        }
    }
}
