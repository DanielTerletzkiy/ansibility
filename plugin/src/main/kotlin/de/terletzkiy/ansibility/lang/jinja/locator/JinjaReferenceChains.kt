package de.terletzkiy.ansibility.lang.jinja.locator

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCallExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaLiteral
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMemberAccess
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaNamespaceTarget
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSubscription
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaVariableReference
import de.terletzkiy.ansibility.lang.jinja.scopes.JinjaBinding
import de.terletzkiy.ansibility.lang.jinja.scopes.JinjaScopes

/**
 * A variable reference with its constant accessor chain, read from the Jinja PSI: `item.floating.ssl['cert_file']` is
 * the reference [name] `item` with [path] `[floating, ssl, cert_file]`.
 *
 * The chain follows the rules of the text-level analysis (`lang.jinja.refs.JinjaVarRef.attrPath`): `.attr`, `.0`,
 * `['key']`, `["key"]` and `[0]` extend it; it stops at the first dynamic subscript, slice, call or method call
 * (`x.items()` has an empty path, `hostvars[host].ansible_host` too). `{% set ns.attr = … %}` reads `ns` with the
 * path `[attr]`.
 */
class JinjaReferenceChain internal constructor(
    /** The variable reference, or the namespace target of `{% set ns.attr = … %}`. */
    val element: PsiElement,
    val name: String,
    val path: List<String>,
    /** The end offset of each [path] segment: after the attribute name, or after the closing `]`. */
    val segmentEnds: List<Int>,
    /** The root name. */
    val nameRange: TextRange,
    /** From the root name through the last [path] segment. */
    val range: TextRange,
    /** The template local the name resolves to; null for a context variable. */
    val binding: JinjaBinding?,
) {
    /**
     * The end offsets of the segments the caret [offset] covers, up to and including the segment under it: empty on
     * the root name, one entry on the first accessor, and so on (`vars.JinjaTextSites.coveredSegmentEnds`).
     */
    fun coveredSegmentEnds(offset: Int): List<Int> {
        if (path.isEmpty() || offset <= nameRange.endOffset) return emptyList()
        val index = segmentEnds.indexOfFirst { offset <= it }
        return if (index < 0) segmentEnds else segmentEnds.subList(0, index + 1)
    }

    override fun toString(): String = "JinjaReferenceChain(${(listOf(name) + path).joinToString(".")}, $range, local=${binding != null})"
}

/** The [JinjaReferenceChain]s of a Jinja file, cached until the file changes. */
object JinjaReferenceChains {
    private class Chains(val free: List<JinjaReferenceChain>, val local: List<JinjaReferenceChain>)

    private val CHAINS = Key.create<CachedValue<Chains>>("ansibility.jinja.referenceChains")

    /** Every chain of [file]: context variables first, then locals, each in source order. */
    fun of(file: AnsibleJinjaFile): List<JinjaReferenceChain> = chains(file).let { it.free + it.local }

    /**
     * The chain whose range contains [offset] (end inclusive, for a caret right after a name): a context variable's
     * before a local's, as the text-level `JinjaRefsResult.referenceAt`.
     */
    fun at(file: AnsibleJinjaFile, offset: Int): JinjaReferenceChain? {
        val chains = chains(file)
        return chains.free.firstOrNull { it.range.containsOffset(offset) } ?: chains.local.firstOrNull { it.range.containsOffset(offset) }
    }

    private fun chains(file: AnsibleJinjaFile): Chains = CachedValuesManager.getCachedValue(file, CHAINS) {
        val scopes = JinjaScopes.of(file)
        val all = ArrayList<JinjaReferenceChain>()
        for (use in scopes.nameUses) {
            ProgressManager.checkCanceled()
            when (use) {
                is JinjaVariableReference -> all += chainOf(use, scopes.resolve(use))
                is JinjaNamespaceTarget -> chainOf(use, scopes.resolve(use))?.let(all::add)
            }
        }
        all.sortBy { it.nameRange.startOffset }
        val (local, free) = all.partition { it.binding != null }
        CachedValueProvider.Result.create(Chains(free, local), file)
    }

    private fun chainOf(reference: JinjaVariableReference, binding: JinjaBinding?): JinjaReferenceChain {
        val path = ArrayList<String>()
        val ends = ArrayList<Int>()
        var current: PsiElement = reference
        while (true) {
            val parent = current.parent
            val segment = when {
                parent is JinjaMemberAccess && parent.qualifier === current -> memberSegment(parent)
                parent is JinjaSubscription && parent.qualifier === current -> subscriptSegment(parent)
                else -> null
            } ?: break
            path += segment.first
            ends += segment.second
            current = parent
        }
        val nameRange = reference.nameIdentifier.textRange
        return JinjaReferenceChain(
            reference, reference.name, path, ends, nameRange, TextRange(nameRange.startOffset, ends.lastOrNull() ?: nameRange.endOffset),
            binding,
        )
    }

    private fun chainOf(target: JinjaNamespaceTarget, binding: JinjaBinding?): JinjaReferenceChain? {
        val attribute = target.attributeElement ?: return null
        val nameRange = target.namespaceElement.textRange
        val end = attribute.textRange.endOffset
        return JinjaReferenceChain(
            target, target.namespaceName, listOf(attribute.text), listOf(end), nameRange, TextRange(nameRange.startOffset, end), binding,
        )
    }

    /** `.attr` or `.0` (as `"0"`, underscores dropped); null for a method call `x.m()` or a missing name. */
    private fun memberSegment(access: JinjaMemberAccess): Pair<String, Int>? {
        val member = access.memberNameElement ?: return null
        if ((access.parent as? JinjaCallExpression)?.callee === access) return null
        val text = if (access.isIndex) member.text.replace("_", "") else member.text
        return text to member.textRange.endOffset
    }

    /** `['key']`, `["key"]` or `[0]`: exactly one string or integer token between closed brackets; null otherwise. */
    private fun subscriptSegment(subscription: JinjaSubscription): Pair<String, Int>? {
        if (subscription.node.findChildByType(AnsibleJinjaTokenTypes.RBRACKET) == null) return null
        val key = subscription.index as? JinjaLiteral ?: return null
        val tokens = key.node.getChildren(null).filter { it.elementType != AnsibleJinjaTokenTypes.WHITE_SPACE }
        val token = tokens.singleOrNull() ?: return null
        val text = when (token.elementType) {
            AnsibleJinjaTokenTypes.STRING -> key.stringValue ?: return null
            AnsibleJinjaTokenTypes.INTEGER -> token.text.replace("_", "")
            else -> return null
        }
        return text to subscription.textRange.endOffset
    }
}
