package de.terletzkiy.ansibility.vars.usages

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import com.intellij.util.Processor
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * `usageTargetProvider` (`id="ansibilityVars"`, `order="first"`, F1.10): the search target of Find Usages (Alt+F7)
 * and Show Usages (Ctrl+Alt+F7) at a variable: a Jinja use in a template or in a YAML value, a bare `when:`
 * expression, a variable key, an argument_specs option, a `register:` or `loop_var:` value. Inside an injection the
 * platform passes the injected editor and fragment, elsewhere the host editor; [VarUsageSearch.symbolAt] maps both to
 * the host. The platform uses the first target only, hence `order="first"`.
 *
 * Runs in the actions' data rules on a background thread with a read lock: one classification, no search. Not
 * `DumbAware`: variables need the indexes, so while indexing the platform skips this provider and Find Usages shows
 * only its generic "no usages at cursor" hint.
 */
class VarUsageTargetProvider : UsageTargetProvider {
    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        val symbol = VarUsageSearch.symbolAt(file, editor.caretModel.offset) ?: return null
        return arrayOf(PsiElement2UsageTargetAdapter(symbol, true))
    }
}

/**
 * `findUsagesHandlerFactory` (`id="ansibilityVars"`, `order="first"`): searches our [VarSymbolElement] targets, and
 * the [YAMLKeyValue] of a top-level variable key, which is what Ctrl+B's "show usages" outcome hands the platform on a
 * role's own declaration (D-FU1, D-FU2, [de.terletzkiy.ansibility.vars.VarNavigation]). Structural checks only (the
 * platform calls it under a read lock, sometimes on the EDT).
 */
class VarFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean = when (element) {
        is VarSymbolElement -> true
        is YAMLKeyValue -> VarUsageSearch.symbolOfKey(element) != null
        else -> false
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val symbol = when (element) {
            is VarSymbolElement -> element
            is YAMLKeyValue -> VarUsageSearch.symbolOfKey(element)
            else -> null
        } ?: return null
        return VarFindUsagesHandler(symbol)
    }
}

/**
 * Finds the usages of one variable ([VarUsageSearch.process]); the primary element is always the symbol, so the
 * usage view reads "Variable environment_group · falcon" and the read/write detector sees our target.
 */
class VarFindUsagesHandler internal constructor(private val symbol: VarSymbolElement) : FindUsagesHandler(symbol) {
    override fun getPrimaryElements(): Array<PsiElement> = arrayOf(symbol)

    override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
        if (element != symbol) return true
        return VarUsageSearch.process(project, symbol, options.searchScope, processor)
    }

    /** Variables live in Jinja and YAML keys, never in plain text that a text search would add. */
    override fun isSearchForTextOccurrencesAvailable(psiElement: PsiElement, isSingleFile: Boolean): Boolean = false

    /** Highlighting goes through [VarHighlightUsagesHandlerFactory], never through PSI references (Jinja names have none). */
    override fun findReferencesToHighlight(target: PsiElement, searchScope: SearchScope): Collection<PsiReference> = emptyList()
}
