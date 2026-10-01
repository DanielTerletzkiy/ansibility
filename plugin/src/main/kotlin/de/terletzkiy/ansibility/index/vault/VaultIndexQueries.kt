package de.terletzkiy.ansibility.index.vault

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.index.InContext
import de.terletzkiy.ansibility.index.RootFamily

/** How many envelopes a root (or one label of it) has: the vault manager's "130 inline · 4 files". */
data class VaultCounts(
    /** Inline `!vault` values. */
    val inline: Int,
    /** Files holding at least one inline value. */
    val inlineFiles: Int,
    /** Whole-file vaults. */
    val files: Int,
    /** Envelopes of either kind that do not parse (ANS-V101–V103 territory). */
    val malformed: Int,
) {
    /** All envelopes. */
    val total: Int get() = inline + files

    companion object {
        val NONE = VaultCounts(0, 0, 0, 0)

        /** The counts of [entries]. */
        fun of(entries: List<InContext<VaultIndexEntry>>): VaultCounts {
            val inline = entries.filter { it.value.kind == VaultEnvelopeKind.INLINE }
            return VaultCounts(
                inline = inline.size,
                inlineFiles = inline.mapTo(HashSet()) { it.file }.size,
                files = entries.size - inline.size,
                malformed = entries.count { !it.value.wellFormed },
            )
        }
    }
}

/**
 * Root-scoped queries over `ansible.vault` ([VaultIndex]; plan A.7, DEV.md rule 6): every hit lies in a file of the
 * root's [RootFamily], never in another root or a detached worktree. Unlabelled envelopes are mapped to the root's
 * default id here, at query time.
 *
 * Call inside a read action in smart mode. Nothing here decrypts or reads a secret.
 */
object VaultIndexQueries {
    /** Every envelope of [root], ordered by file path and offset. */
    fun envelopes(project: Project, root: AnsibleRoot): List<InContext<VaultIndexEntry>> {
        val family = RootFamily.of(project, root)
        val keys = ArrayList<String>()
        FileBasedIndex.getInstance().processAllKeys(VaultIndex.NAME, { keys += it; true }, family.scope, null)
        return keys.flatMap { key ->
            ProgressManager.checkCanceled()
            AnsibleIndexQueries.values(project, VaultIndex.NAME, key, family)
        }.sortedWith(compareBy<InContext<VaultIndexEntry>> { it.file.path }.thenBy { it.value.offset })
    }

    /** The envelopes of [root] whose raw label is [rawLabel] (the empty string for unlabelled ones). */
    fun envelopesWithRawLabel(project: Project, root: AnsibleRoot, rawLabel: String): List<InContext<VaultIndexEntry>> =
        AnsibleIndexQueries.values(project, VaultIndex.NAME, rawLabel, RootFamily.of(project, root))
            .sortedWith(compareBy<InContext<VaultIndexEntry>> { it.file.path }.thenBy { it.value.offset })

    /** The envelopes of [file] in offset order, whatever its root (the index's own view of one file). */
    fun envelopes(project: Project, file: VirtualFile): List<VaultIndexEntry> =
        FileBasedIndex.getInstance().getFileData(VaultIndex.NAME, file, project).values.flatten().sortedBy { it.offset }

    /** The whole-file vaults of [root] (the tool window's Vault node). */
    fun wholeFileVaults(project: Project, root: AnsibleRoot): List<InContext<VaultIndexEntry>> =
        envelopes(project, root).filter { it.value.kind == VaultEnvelopeKind.FILE }

    /** The envelopes of [root] that do not parse. */
    fun malformed(project: Project, root: AnsibleRoot): List<InContext<VaultIndexEntry>> =
        envelopes(project, root).filter { !it.value.wellFormed }

    /** The counts of [root]. */
    fun counts(project: Project, root: AnsibleRoot): VaultCounts = VaultCounts.of(envelopes(project, root))

    /**
     * The counts of [root] per effective label: the raw label, or [defaultIdentity] for unlabelled envelopes (the
     * root's `vault_identity`, `default` unless `ansible.cfg` renames it). Labels in first-seen order.
     */
    fun countsByLabel(
        project: Project,
        root: AnsibleRoot,
        defaultIdentity: String = VaultStatusService.getInstance(project).config(root).defaultIdentity,
    ): Map<String, VaultCounts> =
        envelopes(project, root).groupBy { it.value.labelOrDefault(defaultIdentity) }.mapValues { (_, entries) -> VaultCounts.of(entries) }
}
