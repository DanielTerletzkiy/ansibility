package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil

/**
 * Structure-aware Enter in Jinja templates and in block scalars of Ansible YAML files (plan F2.7, X35):
 * - Enter right after the opening tag of a block (`if`, `for`, `macro`, `call`, `filter`, `with`, `block`, `raw`,
 *   block `set`) whose end tag is missing inserts the end tag on the line below, spelled like the opening tag
 *   (`{%- if x -%}` gets `{%- endif -%}`), and leaves the caret on an empty line between them. Only when "auto-insert
 *   end tags" is on and the rest of the line is empty.
 * - Enter between an opening tag and its end tag on one line (`{% if x %}|{% endif %}`) puts the end tag on its own
 *   line and the caret on an empty line between them.
 *
 * The new lines get the indentation of the opening tag's line; template text has no further indentation rules (the
 * formatter never changes templates). A missing end tag is one that leaves the block kind unbalanced
 * ([JinjaTagScanner.unclosed]), so `{% if %}` inside an `if` gets its own `{% endif %}`.
 */
class AnsibleJinjaEnterHandler : EnterHandlerDelegate, DumbAware {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): Result {
        val host = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        if (host.isViewer || host.selectionModel.hasSelection() || host.caretModel.caretCount > 1) return Result.Continue
        val offset = host.caretModel.offset
        val text = host.document.charsSequence
        val tagEnd = skipHorizontalWhitespaceBack(text, offset)
        // cheap check first: the caret follows a `%}`
        if (tagEnd < 2 || text[tagEnd - 1] != '}' || text[tagEnd - 2] != '%') return Result.Continue
        val context = JinjaTypingContext.find(file.project, editor, file, tagEnd - 1) ?: return Result.Continue
        if (!context.keepsLines) return Result.Continue
        val tags = context.tags()
        val index = tags.indexOfFirst { it.isClosed && it.range.endOffset == tagEnd }
        if (index < 0) return Result.Continue
        val opener = tags[index]
        val kind = JinjaBlockKind.openedBy(opener) ?: return Result.Continue
        val indent = lineIndent(host.document, opener.range.startOffset)
        val next = tags.getOrNull(index + 1)
        val insertion = when {
            next != null && next.range.startOffset == offset && JinjaBlockKind.closedBy(next) == kind -> "\n$indent\n$indent"
            !isRestOfLineBlank(host.document, offset) || !JinjaTypingContext.settings().autoInsertEndTags -> return Result.Continue
            JinjaTagScanner.unclosed(tags, kind) == 0 -> return Result.Continue
            else -> "\n$indent\n$indent${opener.endTagText(kind)}"
        }
        host.document.insertString(offset, insertion)
        host.caretModel.moveToOffset(offset + 1 + indent.length)
        host.selectionModel.removeSelection()
        return Result.Stop
    }

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): Result = Result.Continue

    private fun skipHorizontalWhitespaceBack(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        return i
    }

    /** The leading spaces and tabs of the line containing [offset]. */
    private fun lineIndent(document: Document, offset: Int): String {
        val start = document.getLineStartOffset(document.getLineNumber(offset))
        val text = document.charsSequence
        var end = start
        while (end < offset && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }

    private fun isRestOfLineBlank(document: Document, offset: Int): Boolean {
        val end = document.getLineEndOffset(document.getLineNumber(offset))
        return document.charsSequence.subSequence(offset, end).isBlank()
    }
}
