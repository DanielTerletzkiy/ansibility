package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.NamedColorUtil

/**
 * The object behind a Jinja completion item: the candidate plus where it was offered, so the completion popup's
 * documentation ([JinjaLookupDocumentationProvider]) can show the same card as hover.
 */
internal class JinjaLookupItem(
    val candidate: JinjaCandidate,
    /** The root the item was offered in. */
    val rootDir: VirtualFile,
    /** The host file and offset of the completion. */
    val file: VirtualFile,
    val offset: Int,
) {
    override fun toString(): String = "JinjaLookupItem(${candidate.lookupString})"
}

/** Turns [JinjaCandidate]s into lookup elements (plan F1.3 presentation) ranked by tier. */
internal object JinjaLookupElements {
    fun create(candidate: JinjaCandidate, scope: JinjaCompletionScope): LookupElement {
        var builder = LookupElementBuilder.create(JinjaLookupItem(candidate, scope.root.dir, scope.file, scope.hostOffset), candidate.lookupString)
            .withPresentableText(candidate.presentable)
            .withIcon(candidate.icon)
            .withTypeText(candidate.typeText)
            .withBoldness(candidate.bold)
            .withStrikeoutness(candidate.deprecated)
        candidate.tail?.let { builder = builder.withTailText(it, true) }
        if (candidate.grey) builder = builder.withItemTextForeground(NamedColorUtil.getInactiveTextColor())
        when (val insert = candidate.insert) {
            InsertStyle.Plain -> Unit
            is InsertStyle.CloseKey -> builder = builder.withInsertHandler(CloseKeyHandler(insert.closingQuote))
            InsertStyle.CloseBracket -> builder = builder.withInsertHandler(CloseKeyHandler(""))
        }
        return PrioritizedLookupElement.withPriority(builder, candidate.priority)
    }

    /** After a subscript key: adds the closing quote ([closing], as the host file spells it) and `]` when missing. */
    private class CloseKeyHandler(private val closing: String) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val document = context.document
            var offset = context.tailOffset
            if (closing.isNotEmpty()) {
                if (!document.charsSequence.startsWith(closing, offset)) document.insertString(offset, closing)
                offset += closing.length
            }
            if (document.charsSequence.getOrNull(offset) != ']') document.insertString(offset, "]")
            context.editor.caretModel.moveToOffset(offset + 1)
        }

        private fun CharSequence.startsWith(prefix: String, at: Int): Boolean =
            at + prefix.length <= length && subSequence(at, at + prefix.length).toString() == prefix
    }
}
