package de.terletzkiy.ansibility.lang.jinja.scopes

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMemberAccess
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaNamespaceTarget
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTemplateReferenceStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaVariableReference
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind

/**
 * A name bound inside a Jinja template or fragment (tier T0 of plan A.5): a `for` target, `loop`, a `set` or block
 * `set` target, a macro, its parameters and implicit names, a call-block parameter, a `with` assignment, an import
 * alias, or a `namespace()` attribute.
 *
 * Offsets are in the coordinates of the Jinja file (a template file, or an injected fragment including its prefix).
 */
class JinjaBinding internal constructor(
    val name: String,
    val kind: JinjaLocalKind,
    /**
     * The element that binds the name: a target or parameter name, the name of a `namespace(attr=…)` keyword argument,
     * the attribute token of `{% set ns.attr = … %}`, or, for implicit names (`loop`, `varargs`, `kwargs`, `caller`),
     * the keyword of the statement that opens the scope.
     */
    val element: PsiElement,
    /** The range of the binding name (the statement keyword for implicit names). */
    val definitionRange: TextRange,
    /** Where the name is visible: from its binding point to the end of the enclosing scope (end exclusive). */
    val scope: TextRange,
    /** For [JinjaLocalKind.NAMESPACE_ATTRIBUTE]: the namespace variable (`ns` in `ns.found`); null otherwise. */
    val owner: String?,
) {
    /** A bare name the template can read (namespace attributes are only reachable through their namespace). */
    val isBareName: Boolean get() = kind != JinjaLocalKind.NAMESPACE_ATTRIBUTE

    /** Whether the binding is visible at [offset]. */
    fun isVisibleAt(offset: Int): Boolean = offset >= scope.startOffset && offset < scope.endOffset

    override fun toString(): String = "JinjaBinding($name, $kind, $scope${owner?.let { ", owner=$it" } ?: ""})"
}

/**
 * The scopes of one Jinja file ([JinjaScopes.of]): every binding with its visibility, and the binding each variable
 * reference resolves to, following Jinja's own rules (see [JinjaScopes]).
 */
class JinjaScopeModel internal constructor(
    /** Every binding, in binding order. */
    val bindings: List<JinjaBinding>,
    private val references: Map<PsiElement, JinjaBinding?>,
) {
    /** The bindings visible at [offset], innermost (latest scope start) first; namespace attributes included. */
    fun visibleAt(offset: Int): List<JinjaBinding> =
        bindings.filter { it.isVisibleAt(offset) }.sortedByDescending { it.scope.startOffset }

    /** The bare names visible at [offset] (namespace attributes excluded). */
    fun visibleNamesAt(offset: Int): Set<String> = bindings.filter { it.isBareName && it.isVisibleAt(offset) }.mapTo(LinkedHashSet()) { it.name }

    /** The local [reference] reads, or null when it reads a context variable (which Ansible resolves). */
    fun resolve(reference: JinjaVariableReference): JinjaBinding? = references[reference]

    /** The namespace variable `ns` of `{% set ns.attr = … %}` as a local, or null when `ns` is no local. */
    fun resolve(target: JinjaNamespaceTarget): JinjaBinding? = references[target]

    /**
     * Every analysed name use, in source order: the [JinjaVariableReference]s, and the [JinjaNamespaceTarget]s of
     * `{% set ns.attr = … %}`, which read the namespace variable.
     */
    val nameUses: Collection<PsiElement> get() = references.keys

    /** The attributes of the namespace held by the local [namespace] that are visible at [offset]. */
    fun attributesOf(namespace: JinjaBinding, offset: Int): List<JinjaBinding> =
        bindings.filter { it.kind == JinjaLocalKind.NAMESPACE_ATTRIBUTE && it.owner == namespace.name && it.isVisibleAt(offset) }

    /**
     * The binding behind `qualifier.member`: a namespace attribute (`ns.found` with `ns = namespace(found=false)` or
     * after `{% set ns.found = … %}`), or null when the qualifier is no namespace local or has no such attribute.
     * `loop.index` and friends are not bindings; see [JinjaScopes.LOOP_ATTRIBUTES].
     */
    fun resolveMember(access: JinjaMemberAccess): JinjaBinding? {
        val qualifier = access.qualifier as? JinjaVariableReference ?: return null
        val member = access.memberName ?: return null
        val namespace = resolve(qualifier) ?: return null
        return attributesOf(namespace, access.textRange.startOffset).firstOrNull { it.name == member }
    }

    /**
     * The locals an included or imported template sees from [statement]: everything visible there for `include`
     * (which passes the context) and for `with context`, nothing for `import` and `from … import` by default.
     */
    fun contextPassedTo(statement: JinjaTemplateReferenceStatement): List<JinjaBinding> =
        if (statement.withContext) visibleAt(statement.textRange.startOffset) else emptyList()
}
