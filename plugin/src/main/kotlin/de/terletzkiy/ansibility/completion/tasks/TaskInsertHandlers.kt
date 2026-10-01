package de.terletzkiy.ansibility.completion.tasks

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.editor.Document

/** What a completed key's value usually looks like, which decides what its insert handler adds after the key. */
internal enum class ValueShape {
    /** No value at all (`ansible.builtin.ping:`): only the colon. */
    NONE,

    /** A scalar on the key's line: `: `. */
    SCALAR,

    /** A nested mapping: `:`, a line break and the child indent. */
    MAPPING,

    /** A list of mappings (`tasks:`, `block:`, `list[dict]` options): `:`, a line break, the child indent and `- `. */
    SEQUENCE,
}

/**
 * Completes a key (module, option or keyword) with the text that makes it a key (plan F5.7): the colon, and for
 * nested values a line break with the child indent (the key's column plus the file's YAML indent size), after which
 * the completion popup opens again for the nested keys.
 *
 * Nothing is added when the key already has its colon (the caret then moves past `: `) or when other text follows
 * on the line (completion inside an existing key, a trailing comment). In a flow mapping (`{…}`) the suffix is a
 * plain `: `.
 */
internal class KeyInsertHandler(private val shape: ValueShape, private val flow: Boolean) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val chars = document.charsSequence
        val tail = context.tailOffset
        val lineEnd = document.getLineEndOffset(document.getLineNumber(tail))
        var next = tail
        while (next < lineEnd && (chars[next] == ' ' || chars[next] == '\t')) next++
        if (next < lineEnd && chars[next] == ':') {
            var caret = next + 1
            if (caret < lineEnd && chars[caret] == ' ') caret++
            context.editor.caretModel.moveToOffset(caret)
            return
        }
        val text = if (flow) {
            if (next < lineEnd && chars[next] != ',' && chars[next] != '}') return
            ": "
        } else {
            if (next < lineEnd) return
            suffix(document, context)
        }
        document.insertString(tail, text)
        context.editor.caretModel.moveToOffset(tail + text.length)
        if (!flow && (shape == ValueShape.MAPPING || shape == ValueShape.SEQUENCE)) {
            context.commitDocument()
            AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }

    private fun suffix(document: Document, context: InsertionContext): String = when (shape) {
        ValueShape.NONE -> ":"
        ValueShape.SCALAR -> ": "
        ValueShape.MAPPING -> ":\n" + " ".repeat(childIndent(document, context))
        ValueShape.SEQUENCE -> ":\n" + " ".repeat(childIndent(document, context)) + "- "
    }

    /** The key's column plus the YAML indent size of the file. */
    private fun childIndent(document: Document, context: InsertionContext): Int {
        val start = context.startOffset
        val column = start - document.getLineStartOffset(document.getLineNumber(start))
        val size = CodeStyle.getIndentOptions(context.file).INDENT_SIZE.takeIf { it > 0 } ?: DEFAULT_INDENT
        return column + size
    }

    private companion object {
        const val DEFAULT_INDENT = 2
    }
}

/**
 * Completes a value that YAML would not read as the string it is (`'yes'`, `'0644'`, `'null'`) in single quotes,
 * so the module receives the documented choice rather than a boolean, number or null.
 */
internal object QuotingInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val quoted = "'" + item.lookupString.replace("'", "''") + "'"
        context.document.replaceString(context.startOffset, context.tailOffset, quoted)
        context.editor.caretModel.moveToOffset(context.startOffset + quoted.length)
    }
}
