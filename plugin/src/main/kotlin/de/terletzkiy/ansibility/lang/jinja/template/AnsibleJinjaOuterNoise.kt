package de.terletzkiy.ansibility.lang.jinja.template

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes

/**
 * Whether [element] belongs to the outer-language tree of an Ansible Jinja template file, or to a fragment another
 * plugin injected into that tree (the Dockerfile plugin injects Shell into `RUN`/`CMD`, whose text then includes the
 * Jinja tags).
 */
internal fun isInOuterTemplateTree(element: PsiElement): Boolean {
    val file = element.containingFile ?: return false
    return isOuterTemplateFile(topLevelFile(file))
}

/** The outer-language root of an Ansible Jinja template. */
private fun isOuterTemplateFile(file: PsiFile): Boolean =
    file.viewProvider is AnsibleJinjaFileViewProvider && !file.language.isKindOf(AnsibleJinjaLanguage)

/** The host file of an injected fragment, else [file] itself. */
private fun topLevelFile(file: PsiFile): PsiFile = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file) ?: file

/**
 * Hides the outer language's syntax errors in Ansible Jinja templates (plan A.5). The outer tree is parsed from the
 * text with every Jinja tag cut out, which leaves fragments such as `  :` for `{{ name }}:` and duplicate keys from
 * `{% if %}`/`{% else %}` branches; those are not errors of the rendered file. Jinja's own errors stay visible.
 */
class AnsibleJinjaOuterErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean = !isInOuterTemplateTree(element)
}

/**
 * Hides warnings and errors of the outer language's annotators and inspections in Ansible Jinja templates: every
 * highlight of severity weak warning or above that starts or ends in outer text (`TEXT` or a raw body of the Jinja
 * tree), or comes from a fragment injected into the outer tree, for the same reason as [AnsibleJinjaOuterErrorFilter].
 * Highlights inside Jinja tags (Jinja syntax errors, Jinja inspections) and plain colouring stay.
 */
class AnsibleJinjaOuterHighlightFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || highlightInfo.severity < HighlightSeverity.WEAK_WARNING) return true
        val top = topLevelFile(file)
        val provider = top.viewProvider as? AnsibleJinjaFileViewProvider ?: return true
        // a fragment injected into the outer tree: its offsets are fragment offsets, and all of it is outer text
        if (top != file) return !isOuterTemplateFile(top)
        val jinja = provider.getPsi(AnsibleJinjaLanguage) ?: return true
        val start = highlightInfo.startOffset
        val end = highlightInfo.endOffset
        return !isOuterText(jinja, start) && !(end > start && isOuterText(jinja, end - 1))
    }

    /** The Jinja tree's own leaf at [offset] (`PsiFile.findElementAt` would answer from the outer tree). */
    private fun isOuterText(jinja: PsiFile, offset: Int): Boolean =
        jinja.node.findLeafElementAt(offset)?.elementType in AnsibleJinjaTokenTypes.OUTER_TEXT
}

/**
 * Silences YAML inspections (duplicate keys and friends) on the outer YAML tree of `*.yml.j2` and `*.yaml.j2` templates,
 * for the same reason as [AnsibleJinjaOuterErrorFilter]. Registered for YAML, the one outer language whose plugin
 * Ansibility depends on.
 */
class AnsibleJinjaOuterInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean = isInOuterTemplateTree(element)

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY
}

/**
 * The formatter of Ansible Jinja templates: one leaf block over the whole file, so Reformat Code never changes template
 * text (plan F2.1). Re-indenting the outer language would change what the template renders.
 */
class AnsibleJinjaFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        return FormattingModelProvider.createFormattingModelForPsiFile(file, WholeFileBlock(file.textRange), formattingContext.codeStyleSettings)
    }

    /** A leaf block: no children, no spacing, no indent; the formatter has nothing to change. */
    private class WholeFileBlock(private val range: TextRange) : Block {
        override fun getTextRange(): TextRange = range

        override fun getSubBlocks(): List<Block> = emptyList()

        override fun getWrap(): Wrap? = null

        override fun getIndent(): Indent = Indent.getNoneIndent()

        override fun getAlignment(): Alignment? = null

        override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

        override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)

        override fun isIncomplete(): Boolean = false

        override fun isLeaf(): Boolean = true
    }
}
