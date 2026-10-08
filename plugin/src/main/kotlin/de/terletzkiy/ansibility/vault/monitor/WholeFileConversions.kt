package de.terletzkiy.ansibility.vault.monitor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.VaultFileShape
import de.terletzkiy.ansibility.semantics.vault.VaultShapeKind
import de.terletzkiy.ansibility.vault.envelope.WholeFileShapes
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle.message
import de.terletzkiy.ansibility.vault.ui.VaultUiFeedback
import org.jetbrains.annotations.Nls

/**
 * "Convert to Whole-File Vault" for several files at once (plan amendment R21, D165: the Vault tab and the vault
 * notification), with the rule of ANS-V107's quick fix: each file is classified again from its document
 * (`semantics.vault.VaultFileShape`, the path facts of `WholeFileShapes.contextOf`) and rewritten only when it is a
 * wrapped envelope that unwraps to a well-formed whole-file vault; the tag or preamble lines, the indentation and the
 * trailing blanks go, the header and payload digits stay, the file keeps its line separator. One undoable command per
 * file, saved at once so the monitoring sees it; no password, nothing decrypted. A byte order mark is left to File |
 * File Properties | Remove BOM. A summary names the files (never content).
 */
object WholeFileConversions {
    /** Converts the [files] that can be converted and reports what was done and left alone; the converted files. EDT. */
    fun convert(project: Project, files: List<VirtualFile>): List<VirtualFile> {
        ThreadingAssertions.assertEventDispatchThread()
        val converted = ArrayList<VirtualFile>()
        val skipped = ArrayList<String>()
        val documents = FileDocumentManager.getInstance()
        for (file in files.distinct()) {
            if (!file.isValid || file.isDirectory || !file.isWritable) {
                skipped += message("monitor.convert.skipped.read.only", file.name)
                continue
            }
            val document = documents.getDocument(file)
            if (document == null || !document.isWritable) {
                skipped += message("monitor.convert.skipped.read.only", file.name)
                continue
            }
            val shape = VaultFileShape.classify(document.charsSequence, WholeFileShapes.contextOf(project, file))
            val reason = whyNot(shape)
            if (reason != null) {
                skipped += message(reason, file.name)
                continue
            }
            val unwrapped = shape!!.unwrapped!!
            WriteCommandAction.writeCommandAction(project)
                .withName(message("monitor.convert.command", file.name))
                .run<RuntimeException> { document.replaceString(0, document.textLength, unwrapped) }
            documents.saveDocument(document)
            converted += file
        }
        VaultUiFeedback.info(project, null, summary(converted, skipped))
        return converted
    }

    /** The message key of why [shape] is not converted, or null when it is. */
    private fun whyNot(shape: VaultFileShape?): String? = when {
        shape == null || !shape.kind.isWrapped -> "monitor.convert.skipped.none"
        shape.kind == VaultShapeKind.BYTE_ORDER_MARK -> "monitor.convert.skipped.bom"
        shape.inner !is EnvelopeParse.Ok || shape.unwrapped == null -> "monitor.convert.skipped.malformed"
        else -> null
    }

    @Nls
    private fun summary(converted: List<VirtualFile>, skipped: List<String>): String = listOfNotNull(
        if (converted.isEmpty()) message("monitor.convert.none") else message("monitor.convert.done", converted.size, converted.joinToString(", ") { it.name }),
        skipped.takeIf { it.isNotEmpty() }?.let { message("monitor.convert.skipped", it.joinToString("; ")) },
    ).joinToString(" ")
}
