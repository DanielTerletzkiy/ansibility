package de.terletzkiy.ansibility.vault.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.monitor.SecretFix
import de.terletzkiy.ansibility.vault.monitor.SecretTexts
import de.terletzkiy.ansibility.vault.monitor.WholeFileConversions
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import org.jetbrains.annotations.Nls

/**
 * The fix a stopped commit offers (plan amendment R21, D167) for its [files]: "Encrypt Files…" (Encrypt File in the
 * working tree, then you commit again) or, when no file can be encrypted, "Convert to Whole-File Vault".
 */
class CommitFix(val fix: SecretFix, val files: List<VirtualFile>) {
    /** The title of the problem's details action. */
    @get:Nls
    val actionText: String
        get() = when (fix) {
            SecretFix.ENCRYPT -> message("commit.action.encrypt")
            SecretFix.CONVERT -> message("commit.action.convert")
        }

    /** Encrypts or converts the [files] that are still valid (Encrypt File asks its questions first). EDT. */
    fun apply(project: Project) {
        ThreadingAssertions.assertEventDispatchThread()
        val valid = files.filter { it.isValid }
        if (valid.isEmpty() || project.isDisposed) return
        when (fix) {
            SecretFix.ENCRYPT -> VaultFileOperations.getInstance(project).encrypt(valid)
            SecretFix.CONVERT -> WholeFileConversions.convert(project, valid)
        }
    }

    override fun toString(): String = "CommitFix($fix, ${files.map { it.name }})"

    companion object {
        /** The fix for [secrets]: Encrypt Files… when some file can be encrypted, else Convert…, else null. */
        fun of(secrets: List<CommitSecret>): CommitFix? {
            for (fix in listOf(SecretFix.ENCRYPT, SecretFix.CONVERT)) {
                val files = secrets.filter { it.fix == fix }.mapNotNull { it.file }.distinct()
                if (files.isNotEmpty()) return CommitFix(fix, files)
            }
            return null
        }
    }
}

/** The words of the commit check (plan amendment R21, D167): kinds, counts and file names; never content. */
object CommitSecretTexts {
    /**
     * The problem text: "Ansibility Vault: 2 files would be committed with a plaintext private key: web.key, db.key",
     * one part per [CommitSecretKind] in its order, joined by "; ". With [restage] (a finding of the staging area and a
     * fix that changes the working tree) it adds that the fixed files must be staged again.
     */
    @Nls
    fun text(secrets: List<CommitSecret>, restage: Boolean = false): String {
        val parts = secrets.groupBy { it.kind }.toSortedMap().map { (kind, ofKind) ->
            val files = ofKind.distinctBy { it.path }
            message("commit.problem.${kind.key}", files.size, SecretTexts.names(files.map { it.name }))
        }
        val text = message("commit.problem", parts.joinToString("; "))
        return if (restage) text + message("commit.problem.restage") else text
    }
}
