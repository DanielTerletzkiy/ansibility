package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.VaultFailure
import org.jetbrains.annotations.Nls
import org.jetbrains.yaml.psi.YAMLScalar

/** How a vault value action ended. Carries no data beyond an id label and a failure class. */
sealed interface VaultWriteOutcome {
    /**
     * The file now holds the new value; [identity] is the id whose secret encrypted it, or that verified a header-only
     * relabel ([relabelled]).
     */
    class Written(val identity: String, val relabelled: Boolean = false) : VaultWriteOutcome

    /** A vault operation failed (locked, wrong secret, cancelled prompt …); nothing was written. */
    class Failed(val failure: VaultFailure) : VaultWriteOutcome

    /** The value changed while the crypto ran; nothing was written. */
    data object Changed : VaultWriteOutcome

    /** The value or its file is gone, or the file is read-only; nothing was written. */
    data object Gone : VaultWriteOutcome
}

/**
 * The one write path of the vault value actions (F7.2–F7.6): finds the value again through its smart pointer,
 * checks that it still holds what the action read, and replaces one range of the real file's [Document] in a single
 * undoable [WriteCommandAction]. Writing through the Document keeps the file's exact layout (which
 * `YAMLElementGenerator` would not). The command names are bundle texts, so undo and redo never show a value, and the
 * undo stack of the real file holds only what the file holds: ciphertext, or the plaintext Decrypt to plain value
 * writes after its confirmation.
 */
internal object VaultValueWriter {
    /**
     * On the EDT: replaces what [replacement] computes for the current scalar of [ref], provided the scalar's loaded
     * text still equals [expected]. Returns [VaultWriteOutcome.Written] with [identity] on success.
     */
    fun write(
        project: Project,
        scalarOf: () -> YAMLScalar?,
        expected: String,
        @Nls commandName: String,
        identity: String,
        replacement: (YAMLScalar, CharSequence) -> VaultReplacement,
    ): VaultWriteOutcome {
        ThreadingAssertions.assertEventDispatchThread()
        if (project.isDisposed) return VaultWriteOutcome.Gone
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitAllDocuments()
        val scalar = scalarOf()?.takeIf { it.isValid } ?: return VaultWriteOutcome.Gone
        if (scalar.textValue != expected) return VaultWriteOutcome.Changed
        val psiFile = scalar.containingFile ?: return VaultWriteOutcome.Gone
        val document = psiFile.viewProvider.virtualFile.let(FileDocumentManager.getInstance()::getDocument) ?: return VaultWriteOutcome.Gone
        val change = replacement(scalar, document.charsSequence)
        var written = false
        WriteCommandAction.writeCommandAction(project, psiFile).withName(commandName).run<RuntimeException> {
            document.replaceString(change.range.startOffset, change.range.endOffset, change.text)
            documents.commitDocument(document)
            written = true
        }
        return if (written) VaultWriteOutcome.Written(identity) else VaultWriteOutcome.Gone
    }
}
