package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndexContributor
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetExclusionCondition
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetRegistrar

/**
 * Keeps vault password files out of the IDE's indexes (plan amendment R30, D223; R30 review fixes, decision 1): for
 * every content root, an exclusion condition ([PasswordFileCondition]) over the entries of
 * [IndexExclusionSettings.rules] as they are when the platform builds the root's file sets. A file it excludes is not
 * in the project content, has no index data (word, trigram and every other index) and is not tracked by Local History,
 * also after it changes on disk. It can still be opened and edited, and vault code reads password files through
 * `java.nio`, never through the VFS or an index.
 *
 * A condition, not the platform's name patterns: those also match every folder name up to the content root, so a
 * password file's name would take folders (an inventory named like it) and their vars and vault files out of the
 * project. The condition is asked for the file and each folder above it, and answers yes only for a file an entry names
 * that is no vars or vault file and no script ([KeepOutRules.excludes]).
 *
 * Registered at application level and run by the platform whenever it builds the file sets of a content root (when a
 * project opens, or a content root is added or changed), so a changed list applies after reopening the project (D225).
 * It depends on no other entity: the entries come from application settings, not from the workspace model.
 *
 * Experimental API (DEV.md rule 3): the whole package `com.intellij.workspaceModel.core.fileIndex`
 * (`WorkspaceFileIndexContributor`, `WorkspaceFileSetRegistrar`, `WorkspaceFileSetExclusionCondition`) is marked
 * experimental in its `package-info` in 262, although none of the classes carries the annotation itself. No public,
 * non-experimental API excludes files without writing the project model (`.iml` exclude patterns).
 */
class PasswordFileExcluder : WorkspaceFileIndexContributor<ContentRootEntity> {
    override val entityClass: Class<ContentRootEntity> get() = ContentRootEntity::class.java

    override fun registerFileSets(entity: ContentRootEntity, registrar: WorkspaceFileSetRegistrar, storage: EntityStorage) {
        registrar.registerExclusionCondition(entity.url, PasswordFileCondition(IndexExclusionSettings.getInstance().rules()), entity)
    }
}

/**
 * The exclusion condition of [PasswordFileExcluder]: [KeepOutRules.excludes] of the entries it was built with. Equal
 * when the entries are, as the platform compares file sets by their conditions. Experimental API (see
 * [PasswordFileExcluder]).
 */
class PasswordFileCondition(val rules: KeepOutRules) : WorkspaceFileSetExclusionCondition {
    override fun shouldExclude(file: VirtualFile): Boolean = rules.excludes(file)

    override fun equals(other: Any?): Boolean = other is PasswordFileCondition && other.rules == rules

    override fun hashCode(): Int = rules.hashCode()

    override fun toString(): String = "PasswordFileCondition($rules)"
}
