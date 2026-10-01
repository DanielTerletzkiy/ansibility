package de.terletzkiy.ansibility.lang.jinja.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

/**
 * A name being bound: a `for` loop variable, a `set` or `with` target, a macro name or parameter, a call-block
 * parameter, or an `import … as` alias. Template locals resolve to these.
 */
class JinjaTargetName(node: ASTNode) : JinjaPsiElement(node), JinjaTarget {
    /** The name token. */
    val nameIdentifier: PsiElement
        get() = firstChild

    /** The bound name. */
    override fun getName(): String = nameIdentifier.text

    override val targetNames: List<JinjaTargetName>
        get() = listOf(this)

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitTargetName(this)
    }
}

/** `ns.attr` as the target of `{% set ns.attr = … %}`: writes an attribute of a `namespace()` object. */
class JinjaNamespaceTarget(node: ASTNode) : JinjaPsiElement(node), JinjaTarget {
    /** The namespace variable token (`ns`). */
    val namespaceElement: PsiElement
        get() = firstChild

    val namespaceName: String
        get() = namespaceElement.text

    /** The attribute token after the dot, null when it is missing. */
    val attributeElement: PsiElement?
        get() = node.findChildByType(T.DOT)?.let { nextToken(it, IDENTIFIERS) }

    val attributeName: String?
        get() = attributeElement?.text

    /** A namespace attribute target binds no plain name. */
    override val targetNames: List<JinjaTargetName>
        get() = emptyList()

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitNamespaceTarget(this)
    }

    private companion object {
        val IDENTIFIERS = TokenSet.create(T.IDENTIFIER)
    }
}

/** `k, v` or `(k, v)` as an assignment target; may nest: `for (a, (b, c)) in …`. */
class JinjaTargetTuple(node: ASTNode) : JinjaPsiElement(node), JinjaTarget {
    /** The direct element targets. */
    val targets: List<JinjaTarget>
        get() = childrenOf(JinjaTarget::class.java)

    override val targetNames: List<JinjaTargetName>
        get() = PsiTreeUtil.findChildrenOfType(this, JinjaTargetName::class.java).toList()
}

/** `(a, b=1)`: the parameters of a macro or of a call block. */
class JinjaParameterList(node: ASTNode) : JinjaPsiElement(node) {
    val parameters: List<JinjaParameter>
        get() = childrenOf(JinjaParameter::class.java)
}

/** One parameter `name` or `name=default`. */
class JinjaParameter(node: ASTNode) : JinjaPsiElement(node) {
    val nameElement: JinjaTargetName?
        get() = childOf(JinjaTargetName::class.java)

    override fun getName(): String? = nameElement?.name

    /** The default value after `=`. */
    val defaultValue: JinjaExpression?
        get() = expressionAfter(T.ASSIGN)
}

/** `name` or `name as alias` in `{% from 'x' import name as alias %}`. */
class JinjaImportedName(node: ASTNode) : JinjaPsiElement(node) {
    /** The token of the imported name. */
    val importedNameElement: PsiElement
        get() = firstChild

    val importedName: String
        get() = importedNameElement.text

    /** The alias after `as`, if any. */
    val alias: JinjaTargetName?
        get() = childOf(JinjaTargetName::class.java)

    /** The name the template sees: the alias, else the imported name. */
    val boundName: String
        get() = alias?.name ?: importedName
}

/** `a = expr` inside `{% with … %}`. */
class JinjaWithAssignment(node: ASTNode) : JinjaPsiElement(node) {
    val target: JinjaTarget?
        get() = childOf(JinjaTarget::class.java)

    val value: JinjaExpression?
        get() = expressionAfter(T.ASSIGN)
}
