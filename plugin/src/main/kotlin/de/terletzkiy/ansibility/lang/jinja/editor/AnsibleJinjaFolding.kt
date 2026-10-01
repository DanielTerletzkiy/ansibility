package de.terletzkiy.ansibility.lang.jinja.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBlockStatementElement

/**
 * Folding of Ansible Jinja templates (plan F2.1): every block statement (`if`, `for`, `macro`, `call`, `filter`,
 * `with`, `block`, `raw` and block `set`) whose body spans lines folds between the end of its opening tag and the
 * start of its end tag, shown as `{% if x %}...{% endif %}`; multi-line `{# … #}` comments fold to `{#...#}`. A block
 * without its end tag does not fold. Nothing is collapsed by default.
 *
 * Only the Jinja tree is read, so it works while indexing; the outer language folds its own tree.
 */
class AnsibleJinjaFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root !is AnsibleJinjaFile) return FoldingDescriptor.EMPTY_ARRAY
        val descriptors = ArrayList<FoldingDescriptor>()
        for (statement in PsiTreeUtil.findChildrenOfType(root, JinjaBlockStatementElement::class.java)) {
            ProgressManager.checkCanceled()
            val opening = statement.openingTag ?: continue
            val end = statement.endTag ?: continue
            val range = TextRange(opening.textRange.endOffset, end.textRange.startOffset)
            if (spansLines(document, range)) descriptors += FoldingDescriptor(statement.node, range, null, BODY_PLACEHOLDER)
        }
        PsiTreeUtil.processElements(root) { element ->
            if (element.elementType == AnsibleJinjaTokenTypes.COMMENT && spansLines(document, element.textRange)) {
                descriptors += FoldingDescriptor(element.node, element.textRange, null, COMMENT_PLACEHOLDER)
            }
            true
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String =
        if (node.elementType == AnsibleJinjaTokenTypes.COMMENT) COMMENT_PLACEHOLDER else BODY_PLACEHOLDER

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private fun spansLines(document: Document, range: TextRange): Boolean =
        !range.isEmpty && range.endOffset <= document.textLength &&
            document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)

    private companion object {
        const val BODY_PLACEHOLDER = "..."
        const val COMMENT_PLACEHOLDER = "{#...#}"
    }
}
