package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.editorActions.TabOutScopesTracker
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes

/**
 * Closes Jinja tags while typing (plan F2.7, X35), in Ansible Jinja templates and in scalars of Ansible YAML files
 * ([JinjaTypingContext]), when "auto-close `{{ }}` and `{% %}`" is on:
 * - `{{` becomes `{{ | }}`, `{%` becomes `{% | %}` and `{#` becomes `{# | #}` (`|` is the caret); a `}` the outer
 *   language's brace pairing put after the first `{` is reused, and Tab jumps over the inserted end;
 * - typing the end delimiter right before the one that is already there moves over it instead of doubling it
 *   (`{{ x| }}` and `}` gives `{{ x }|}`), and a space typed right after `{{ ` is not doubled;
 * - `-` or `+` right after `{% ` of an empty tag becomes the whitespace marker: `{%- | %}`; typed before the end,
 *   `-` and `%}` join to `-%}`.
 *
 * Nothing happens when the tag is already closed on the line, inside a Jinja tag or comment, or with a selection.
 */
class AnsibleJinjaTypedHandler : TypedHandlerDelegate(), DumbAware {
    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (c !in HANDLED) return Result.CONTINUE
        if (!JinjaTypingContext.settings().autoCloseDelimiters) return Result.CONTINUE
        val host = InjectedLanguageEditorUtil.getTopLevelEditor(editor)
        if (host.isViewer || !host.isInsertMode || host.selectionModel.hasSelection()) return Result.CONTINUE
        val typing = Typing(project, host, file, c)
        val handled = typing.typeOver() || typing.autoClose() || typing.whitespaceMarker() || typing.swallowSpace()
        return if (handled) Result.STOP else Result.CONTINUE
    }

    /** One typed character at the host caret. */
    private class Typing(val project: Project, val editor: Editor, val file: PsiFile, val c: Char) {
        val offset: Int = editor.caretModel.offset
        val text: CharSequence = editor.document.charsSequence

        /**
         * `}`, `%` or `#` over the end delimiter of the tag the caret is in. Before the space inserted with the end
         * (`{{ x| }}`) both are typed over; when the caret already follows whitespace or a whitespace marker
         * (`{{ x |␣}}`, `{%- if x -|␣%}`), that inserted space goes away, so the result is `{{ x }}` and `-%}`.
         */
        fun typeOver(): Boolean {
            if (c !in CLOSER_STARTS) return false
            val closerStart = when {
                c != '}' && startsWith(offset, "$c}") || c == '}' && startsWith(offset, "}}") -> offset
                startsWith(offset, " $c}") -> offset + 1
                // inside the end delimiter: `{{ x }|}`, `{% x %|}`
                c == '}' && offset > 0 && text[offset - 1] in CLOSER_STARTS && startsWith(offset, "}") -> offset - 1
                else -> return false
            }
            val context = JinjaTypingContext.find(project, editor, file, closerStart) ?: return false
            if (!isEndDelimiterAt(context, closerStart)) return false
            when {
                closerStart <= offset -> editor.caretModel.moveToOffset(offset + 1)
                offset > 0 && (text[offset - 1].isWhitespace() || text[offset - 1] in MARKERS) -> {
                    editor.document.deleteString(offset, offset + 1)
                    editor.caretModel.moveToOffset(offset + 1)
                }
                else -> editor.caretModel.moveToOffset(offset + 2)
            }
            return true
        }

        /** A space right after a tag start and its inserted space (`{{ |`): habitually typed, never wanted twice. */
        fun swallowSpace(): Boolean {
            if (c != ' ' || offset < 3 || text[offset - 1] != ' ') return false
            val markerLength = if (text[offset - 2] in MARKERS) 1 else 0
            val start = offset - 3 - markerLength
            if (start < 0 || text[start] != '{' || text[start + 1] !in OPENER_SECONDS) return false
            val context = JinjaTypingContext.find(project, editor, file, start) ?: return false
            val token = context.tokenAt(start) ?: return false
            val isTagStart = when (token.type) {
                AnsibleJinjaTokenTypes.VAR_START, AnsibleJinjaTokenTypes.BLOCK_START -> token.end == offset - 1
                AnsibleJinjaTokenTypes.COMMENT -> true
                else -> false
            }
            return token.start == start && isTagStart
        }

        /** The second character of `{{`, `{%` or `{#` typed after an outer-text `{`. */
        fun autoClose(): Boolean {
            val closer = CLOSERS[c] ?: return false
            if (offset < 1 || text[offset - 1] != '{' || offset >= 2 && text[offset - 2] == '{') return false
            if (isClosedOnLine(closer)) return false
            val context = JinjaTypingContext.find(project, editor, file, offset - 1) ?: return false
            if (!context.isOuterText(offset - 1)) return false
            // `{|}`: the outer language paired the first brace; its `}` becomes the end of the new tag
            val reuse = offset < context.region.endOffset && text[offset] == '}'
            val inserted = "$c  " + if (reuse) closer.dropLast(1) else closer
            editor.document.insertString(offset, inserted)
            editor.caretModel.moveToOffset(offset + 2)
            val tagEnd = offset + inserted.length + if (reuse) 1 else 0
            TabOutScopesTracker.getInstance().registerEmptyScope(editor, offset + 2, tagEnd)
            return true
        }

        /** `-` or `+` in an empty `{% | %}`: `{%- | %}`. */
        fun whitespaceMarker(): Boolean {
            if (c != '-' && c != '+') return false
            if (offset < 3 || !startsWith(offset - 3, "{% ") || !startsWith(offset, " %}")) return false
            if (offset >= 4 && text[offset - 4] == '{') return false
            val context = JinjaTypingContext.find(project, editor, file, offset - 3) ?: return false
            val start = context.tokenAt(offset - 3) ?: return false
            if (start.type != AnsibleJinjaTokenTypes.BLOCK_START || start.start != offset - 3 || start.end != offset - 1) return false
            editor.document.insertString(offset - 1, c.toString())
            editor.caretModel.moveToOffset(offset + 1)
            return true
        }

        /** Whether the rest of the line already has [closer] before the next tag start: the tag is being retyped. */
        private fun isClosedOnLine(closer: String): Boolean {
            val document = editor.document
            val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
            var i = offset
            while (i < lineEnd - 1) {
                if (text[i] == closer[0] && text[i + 1] == closer[1]) return true
                if (text[i] == '{' && text[i + 1] in OPENER_SECONDS) return false
                i++
            }
            return false
        }

        /**
         * Whether the end delimiter of a tag (`}}`, `%}`, `#}` of a comment) has its `}}`, `%}` or `#}` at
         * [closerStart]; a whitespace marker before it (`-%}`) belongs to the delimiter.
         */
        private fun isEndDelimiterAt(context: JinjaTypingContext, closerStart: Int): Boolean {
            val token = context.tokenAt(closerStart) ?: return false
            if (token.end != closerStart + 2) return false
            return when (text[closerStart]) {
                '}' -> token.type == AnsibleJinjaTokenTypes.VAR_END
                '%' -> token.type == AnsibleJinjaTokenTypes.BLOCK_END
                '#' -> token.type == AnsibleJinjaTokenTypes.COMMENT && token.start < closerStart - 1
                else -> false
            }
        }

        private fun startsWith(at: Int, prefix: String): Boolean =
            at >= 0 && at + prefix.length <= text.length && text.subSequence(at, at + prefix.length).contentEquals(prefix)
    }

    private companion object {
        val HANDLED = setOf('{', '%', '#', '}', '-', '+', ' ')

        /** Whitespace markers of tag delimiters. */
        val MARKERS = setOf('-', '+')

        /** The end delimiter for the second character of a tag start. */
        val CLOSERS = mapOf('{' to "}}", '%' to "%}", '#' to "#}")

        /** Characters that start an end delimiter before its `}`. */
        val CLOSER_STARTS = setOf('}', '%', '#')

        /** Second characters of a tag start. */
        val OPENER_SECONDS = setOf('{', '%', '#')
    }
}
