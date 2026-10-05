package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProviderEx
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind
import de.terletzkiy.ansibility.vars.JinjaTextSites
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * `usageTypeProvider` (`id="ansibilityVars"`, `order="first"`): the Find tool window's usage-type groups for the usages
 * of our variables (`Read: template`, `Set: group_vars · prod`, `Spec: argument spec` …). The group of each usage was
 * decided by the search that produced it ([VarSymbolElement.kinds]); usages of other targets are left to others.
 */
class VarUsageTypeProvider : UsageTypeProviderEx {
    override fun getUsageType(element: PsiElement): UsageType? = null

    override fun getUsageType(element: PsiElement, targets: Array<out UsageTarget>): UsageType? {
        for (target in targets) {
            val symbol = (target as? PsiElement2UsageTargetAdapter)?.element as? VarSymbolElement ?: continue
            symbol.kinds[element]?.let { return it.usageType }
        }
        return null
    }
}

/**
 * `readWriteAccessDetector` (`id="ansibilityVars"`, `order="first"`): the usages of our variables are writes where a
 * value is set (a variable key, a `register:`/`loop_var:`/`index_var:` value, a `vars_prompt` entry's `name:`, a loop
 * keyword, a Jinja local's binding) and reads everywhere else, which gives the usage view its read/write icons and
 * filters. The platform asks with the usage's element only (a leaf of the host file), so the answer comes from the
 * element's place: in a template only a local's binding is a write (YAML keys of a template typed as YAML are template
 * text), elsewhere a YAML key or a name-valued scalar.
 */
class VarReadWriteAccessDetector : ReadWriteAccessDetector() {
    override fun isReadWriteAccessible(element: PsiElement): Boolean = element is VarSymbolElement

    override fun isDeclarationWriteAccess(element: PsiElement): Boolean = element is VarSymbolElement

    override fun getReferenceAccess(referencedElement: PsiElement, reference: PsiReference): Access = Access.Read

    override fun getExpressionAccess(expression: PsiElement): Access = if (isWrite(expression)) Access.Write else Access.Read

    private fun isWrite(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val context = AnsibleWorkspace.getInstance(element.project).contextOf(virtualFile) ?: return false
        if (JinjaTextSites.isTemplateFile(virtualFile, context)) return isLocalBinding(file, element)
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return false
        val key = keyValue.key
        if (key != null && key.textRange.contains(element.textRange)) return true
        val value = keyValue.value
        if (value !is YAMLScalar || !PsiTreeUtil.isAncestor(value, element, false)) return false
        return keyValue.keyText in NAME_VALUE_KEYS || keyValue.keyText == PROMPT_NAME && isPromptEntry(keyValue)
    }

    /** True for the `name:` of a `vars_prompt` entry, which names the variable the prompt sets. */
    private fun isPromptEntry(keyValue: YAMLKeyValue): Boolean {
        val item = keyValue.parent?.parent as? YAMLSequenceItem ?: return false
        return ((item.parent as? YAMLSequence)?.parent as? YAMLKeyValue)?.keyText == VARS_PROMPT
    }

    /** True when [element] starts at the binding of a Jinja local (`{% set x %}`, a `for` target, a macro parameter). */
    private fun isLocalBinding(file: PsiFile, element: PsiElement): Boolean {
        val start = element.textRange?.startOffset ?: return false
        val analysis = JinjaTextSites.analysisAt(file, start) ?: return false
        return analysis.result.locals.any { it.kind != JinjaLocalKind.LOOP && analysis.toHost(it.definitionRange.startOffset) == start }
    }

    private companion object {
        /** Keys whose scalar value is the name of a variable they set. */
        val NAME_VALUE_KEYS = setOf("register", "loop_var", "index_var")

        /** A play's `vars_prompt` list, whose entries' [PROMPT_NAME] values are variable names. */
        const val VARS_PROMPT = "vars_prompt"
        const val PROMPT_NAME = "name"
    }
}

/**
 * `elementDescriptionProvider` (`id="ansibilityVars"`, `order="first"`): "variable" as the type of our targets in the
 * usage view and the Find Usages dialog, the name as their short and long name, and "Variable name · root" as the
 * target node's text.
 */
class VarElementDescriptionProvider : ElementDescriptionProvider {
    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
        if (element !is VarSymbolElement) return null
        return when (location) {
            is UsageViewTypeLocation -> AnsibilityUsagesBundle.message("symbol.type")
            is UsageViewNodeTextLocation -> element.presentableText
            is UsageViewLongNameLocation, is UsageViewShortNameLocation -> element.name
            else -> element.name
        }
    }
}
