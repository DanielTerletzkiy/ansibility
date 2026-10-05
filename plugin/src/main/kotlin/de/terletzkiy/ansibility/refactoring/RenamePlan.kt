package de.terletzkiy.ansibility.refactoring

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.annotations.Nls

/** One replacement of a rename: [range] of [file] becomes [replacement]. */
internal data class RenameEdit(val file: VirtualFile, val range: TextRange, val replacement: String)

/**
 * What a rename changes: text [edits] in any number of files and, for a role, the directory to rename last.
 * [skipped] counts the occurrences whose text no longer names the old name (stale index, odd quoting); they stay.
 */
internal class RenamePlan(
    val edits: List<RenameEdit>,
    val directory: Pair<VirtualFile, String>? = null,
    val skipped: Int = 0,
) {
    val files: Int get() = (edits.map { it.file } + listOfNotNull(directory?.first)).distinct().size

    /** Applies the plan as one undoable command over every touched file. EDT. */
    fun apply(project: Project, @Nls commandName: String) {
        WriteCommandAction.writeCommandAction(project).withName(commandName).withGlobalUndo().run<RuntimeException> {
            val documents = FileDocumentManager.getInstance()
            for ((file, fileEdits) in edits.groupBy { it.file }) {
                val document = documents.getDocument(file) ?: continue
                for (edit in fileEdits.sortedByDescending { it.range.startOffset }) {
                    if (edit.range.endOffset <= document.textLength) document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            directory?.let { (dir, name) -> dir.rename(this, name) }
        }
    }

    companion object {
        /**
         * The edit renaming [old] to [new] inside [range] of [text]: the whole range when it is the name, else the one
         * occurrence of the name as a whole word inside it (a quoted key, `vars['x']`); null when the range does not name it.
         */
        fun edit(file: VirtualFile, text: CharSequence, range: TextRange, old: String, new: String): RenameEdit? {
            if (range.endOffset > text.length) return null
            val inRange = text.subSequence(range.startOffset, range.endOffset).toString()
            if (inRange == old) return RenameEdit(file, range, new)
            val at = wordIndex(inRange, old)
            if (at < 0) return null
            return RenameEdit(file, TextRange.from(range.startOffset + at, old.length), new)
        }

        /** The first index of [word] in [text] that no identifier character touches, or -1. */
        fun wordIndex(text: String, word: String): Int {
            var from = 0
            while (true) {
                val at = text.indexOf(word, from)
                if (at < 0) return -1
                val before = text.getOrNull(at - 1)
                val after = text.getOrNull(at + word.length)
                if (!isNameChar(before) && !isNameChar(after)) return at
                from = at + 1
            }
        }

        private fun isNameChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

        /** Drops edits that overlap an earlier one (two searches may report the same name). */
        fun distinct(edits: List<RenameEdit>): List<RenameEdit> {
            val result = ArrayList<RenameEdit>()
            for ((_, inFile) in edits.groupBy { it.file }) {
                var end = -1
                for (edit in inFile.sortedBy { it.range.startOffset }) {
                    if (edit.range.startOffset < end) continue
                    result += edit
                    end = edit.range.endOffset
                }
            }
            return result
        }
    }
}
