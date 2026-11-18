package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndexContributor
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetRegistrar

/**
 * Keeps vault password files out of the IDE's indexes (plan amendment R30, D223): for every content root, the name
 * patterns of [IndexExclusionSettings.patterns] are registered as exclusion patterns, the mechanism the platform uses
 * for its own automatic excludes (`AutoExcludeInContentRootsWorkspaceFileIndexContributor`). A file whose name (or a
 * folder's name above it, below the content root) matches is excluded: not in the project content, no index data (word,
 * trigram and every other index), not tracked by Local History, also after it changes on disk. It can still be opened
 * and edited, and vault code reads password files through `java.nio`, never through the VFS or an index.
 *
 * Registered at application level and run by the platform whenever it builds the file sets of a content root (when a
 * project opens, or a content root is added or changed), so a changed list applies after reopening the project (D225).
 * It depends on no other entity: the patterns come from application settings, not from the workspace model.
 *
 * Experimental API (DEV.md rule 3): the whole package `com.intellij.workspaceModel.core.fileIndex`
 * (`WorkspaceFileIndexContributor`, `WorkspaceFileSetRegistrar`) is marked experimental in its `package-info` in 262,
 * although neither class carries the annotation itself; the plugin verifier counts 6 usages here. No public,
 * non-experimental API excludes files by name without writing the project model (`.iml` exclude patterns).
 */
class PasswordFileExcluder : WorkspaceFileIndexContributor<ContentRootEntity> {
    override val entityClass: Class<ContentRootEntity> get() = ContentRootEntity::class.java

    override fun registerFileSets(entity: ContentRootEntity, registrar: WorkspaceFileSetRegistrar, storage: EntityStorage) {
        registrar.registerExclusionPatterns(entity.url, IndexExclusionSettings.getInstance().patterns(), entity)
    }
}
