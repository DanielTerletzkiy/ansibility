package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndexContributor
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetRegistrar

/**
 * Plan amendment R30 tests: makes the platform build the file sets of every content root again, as reopening a project
 * does (D225), by adding a workspace file index contributor that registers nothing. [close] removes it again and waits
 * for the indexes, so the rebuild that follows does not spill into the next test (call it in `tearDown`).
 */
class FileSetRebuilds(private val project: Project) {
    private val added = ArrayList<Disposable>()

    /** Builds every content root's file sets again with the current exclusion list, and waits for the indexes. */
    fun rebuild() {
        val disposable = Disposer.newDisposable("R30 file set rebuild")
        EP.point.registerExtension(NothingContributor(), disposable)
        added += disposable
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    /** Removes what [rebuild] added (another rebuild) and waits for the indexes. */
    fun close() {
        if (added.isEmpty()) return
        added.forEach(Disposer::dispose)
        added.clear()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private class NothingContributor : WorkspaceFileIndexContributor<ContentRootEntity> {
        override val entityClass: Class<ContentRootEntity> get() = ContentRootEntity::class.java

        override fun registerFileSets(entity: ContentRootEntity, registrar: WorkspaceFileSetRegistrar, storage: EntityStorage) = Unit
    }

    private companion object {
        val EP = ExtensionPointName<WorkspaceFileIndexContributor<*>>("com.intellij.workspaceModel.fileIndexContributor")
    }
}
