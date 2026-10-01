package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.codeInsight.generation.CommenterDataHolder
import com.intellij.codeInsight.generation.CommenterWithLineSuffix
import com.intellij.codeInsight.generation.SelfManagingCommenter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

/**
 * Comment with Line/Block Comment in Ansible Jinja templates (plan F2.1): `{# … #}`, the only comment Jinja removes
 * from the output, spelled as the repos do (`{# x #}`). The platform also asks it for the outer-language lines of a
 * template (its rule for template languages), so Ctrl+/ on a YAML line of a `*.yml.j2` writes `{# key: value #}`
 * rather than a YAML comment that would still be rendered.
 *
 * It manages the comments itself, because Jinja comments have whitespace markers and live next to tags:
 * - a line counts as commented when one comment spans it; uncommenting also removes the markers of `{#- … -#}` and
 *   one space inside each delimiter;
 * - a block comment never starts or ends inside a `{{ … }}` or `{% … %}` tag: the range grows to whole tags, since a
 *   comment inside a tag is a syntax error;
 * - the spaces inside the delimiters do not depend on the outer language's code style.
 */
class AnsibleJinjaCommenter : CommenterWithLineSuffix, SelfManagingCommenter<CommenterDataHolder> {
    override fun getLineCommentPrefix(): String = PREFIX

    override fun getLineCommentSuffix(): String = SUFFIX

    override fun getBlockCommentPrefix(): String = OPEN

    override fun getBlockCommentSuffix(): String = CLOSE

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun createLineCommentingState(startLine: Int, endLine: Int, document: Document, file: PsiFile): CommenterDataHolder =
        SelfManagingCommenter.EMPTY_STATE

    override fun createBlockCommentingState(selectionStart: Int, selectionEnd: Int, document: Document, file: PsiFile): CommenterDataHolder =
        SelfManagingCommenter.EMPTY_STATE

    override fun isLineCommented(line: Int, offset: Int, document: Document, data: CommenterDataHolder): Boolean =
        lineComment(document, line, offset) != null

    override fun commentLine(line: Int, offset: Int, document: Document, data: CommenterDataHolder) {
        val end = maxOf(offset, trimmedLineEnd(document, line))
        document.insertString(end, SUFFIX)
        document.insertString(offset, PREFIX)
    }

    override fun uncommentLine(line: Int, offset: Int, document: Document, data: CommenterDataHolder) {
        lineComment(document, line, offset)?.let { remove(document, it) }
    }

    override fun getCommentPrefix(line: Int, document: Document, data: CommenterDataHolder): String = PREFIX

    override fun getBlockCommentRange(selectionStart: Int, selectionEnd: Int, document: Document, data: CommenterDataHolder): TextRange? =
        tokens(document.charsSequence).firstOrNull {
            it.type == T.COMMENT && it.start <= selectionStart && selectionEnd <= it.end && it.end - it.start >= 4
        }?.let { TextRange(it.start, it.end) }

    override fun getBlockCommentPrefix(selectionStart: Int, document: Document, data: CommenterDataHolder): String = PREFIX

    override fun getBlockCommentSuffix(selectionEnd: Int, document: Document, data: CommenterDataHolder): String = SUFFIX

    override fun uncommentBlockComment(startOffset: Int, endOffset: Int, document: Document, data: CommenterDataHolder) {
        delimiters(document.charsSequence, startOffset, endOffset)?.let { remove(document, it) }
    }

    override fun insertBlockComment(startOffset: Int, endOffset: Int, document: Document, data: CommenterDataHolder): TextRange {
        var start = startOffset
        var end = endOffset
        for (tag in tagRanges(document.charsSequence)) {
            if (tag.startOffset < start && start < tag.endOffset) start = tag.startOffset
            if (tag.startOffset < end && end < tag.endOffset) end = tag.endOffset
        }
        document.insertString(end, SUFFIX)
        document.insertString(start, PREFIX)
        return TextRange(start, end + PREFIX.length + SUFFIX.length)
    }

    /** The delimiters to remove for a comment: the opening and the closing range. */
    private class Delimiters(val open: TextRange, val close: TextRange)

    private fun remove(document: Document, delimiters: Delimiters) {
        document.deleteString(delimiters.close.startOffset, delimiters.close.endOffset)
        document.deleteString(delimiters.open.startOffset, delimiters.open.endOffset)
    }

    /** The comment spanning [line] from [offset] (its first non-blank character) to the line end, or null. */
    private fun lineComment(document: Document, line: Int, offset: Int): Delimiters? {
        val end = trimmedLineEnd(document, line)
        if (end - offset < 4) return null
        val text = document.charsSequence
        // exactly one comment: the first `#}` after the opening is the one at the line end
        val firstClose = indexOf(text, CLOSE, offset + 2, end)
        if (firstClose != end - 2) return null
        return delimiters(text, offset, end)
    }

    /**
     * The delimiters of the comment `text[start, end)`: `{#`, an optional marker and one optional space; one optional
     * space, an optional marker and `#}`. Null when the range is not a comment.
     */
    private fun delimiters(text: CharSequence, start: Int, end: Int): Delimiters? {
        if (end - start < 4 || !matches(text, start, OPEN) || !matches(text, end - 2, CLOSE)) return null
        var openEnd = start + 2
        if (openEnd < end - 2 && text[openEnd] in MARKERS) openEnd++
        if (openEnd < end - 2 && text[openEnd] == ' ') openEnd++
        var closeStart = end - 2
        if (closeStart > openEnd && text[closeStart - 1] in MARKERS) closeStart--
        if (closeStart > openEnd && text[closeStart - 1] == ' ') closeStart--
        return Delimiters(TextRange(start, openEnd), TextRange(closeStart, end))
    }

    /** The ranges of the `{{ … }}` and `{% … %}` tags of [text] (template mode). */
    private fun tagRanges(text: CharSequence): List<TextRange> {
        val ranges = ArrayList<TextRange>()
        var tagStart = -1
        for (token in tokens(text)) {
            when (token.type) {
                T.VAR_START, T.BLOCK_START -> tagStart = token.start
                T.VAR_END, T.BLOCK_END -> if (tagStart >= 0) {
                    ranges += TextRange(tagStart, token.end)
                    tagStart = -1
                }
            }
        }
        if (tagStart >= 0) ranges += TextRange(tagStart, text.length)
        return ranges
    }

    private fun tokens(text: CharSequence): Sequence<JinjaScannedToken> = sequence {
        val lexer = AnsibleJinjaLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            yield(JinjaScannedToken(type, lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
    }

    private fun trimmedLineEnd(document: Document, line: Int): Int {
        val start = document.getLineStartOffset(line)
        var end = document.getLineEndOffset(line)
        val text = document.charsSequence
        while (end > start && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--
        return end
    }

    private fun matches(text: CharSequence, at: Int, s: String): Boolean =
        at >= 0 && at + s.length <= text.length && text.subSequence(at, at + s.length).contentEquals(s)

    private fun indexOf(text: CharSequence, s: String, from: Int, to: Int): Int {
        var i = from
        while (i + s.length <= to) {
            if (matches(text, i, s)) return i
            i++
        }
        return -1
    }

    private companion object {
        const val OPEN = "{#"
        const val CLOSE = "#}"
        const val PREFIX = "{# "
        const val SUFFIX = " #}"
        val MARKERS = setOf('-', '+')
    }
}
