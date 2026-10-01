package de.terletzkiy.ansibility.lang.jinja.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes

/** Any element of the Ansible Jinja PSI. */
interface JinjaElement : PsiElement

/** An expression: a name, literal, operation, filter or test application, call, attribute or item access. */
interface JinjaExpression : JinjaElement

/**
 * A `{% … %}` tag: the opening, middle or end tag of a block statement, or a whole single-tag statement.
 * The accessors read the tag's own tokens.
 */
interface JinjaTag : JinjaElement {
    /** `{%`, `{%-` or `{%+`. */
    val startDelimiter: PsiElement?
        get() = node.findChildByType(AnsibleJinjaTokenTypes.BLOCK_START)?.psi

    /** `%}`, `-%}` or `+%}`; null for a tag cut off by the end of the text. */
    val endDelimiter: PsiElement?
        get() = node.findChildByType(AnsibleJinjaTokenTypes.BLOCK_END)?.psi

    /** The tag name token (`for`, `endif`, `print` …): the first token after [startDelimiter]. */
    val tagKeyword: PsiElement?
        get() {
            val start = startDelimiter ?: return null
            val next = PsiTreeUtil.skipWhitespacesAndCommentsForward(start) ?: return null
            return next.takeIf { it.node.elementType != AnsibleJinjaTokenTypes.BLOCK_END && it.firstChild == null }
        }

    /** The tag name as written, or null for `{% %}`. */
    val tagName: String?
        get() = tagKeyword?.text

    /** The tag has its closing `%}`. */
    val isClosed: Boolean
        get() = endDelimiter != null

    /** `{%-`: whitespace before the tag is stripped. */
    val trimsBefore: Boolean
        get() = startDelimiter?.text?.endsWith('-') == true

    /** `-%}`: whitespace after the tag is stripped. */
    val trimsAfter: Boolean
        get() = endDelimiter?.text?.startsWith('-') == true
}

/** A statement: a block statement with tags and bodies, or a single-tag statement. */
interface JinjaStatement : JinjaElement {
    /** The tag that opens the statement; the statement itself for single-tag statements. */
    val openingTag: JinjaTag?

    /** The `{% end… %}` tag, or null for single-tag statements and for a block missing its end tag. */
    val endTag: JinjaEndTag?
        get() = null

    /** The bodies of a block statement in source order (one per branch); empty for single-tag statements. */
    val bodies: List<JinjaBody>
        get() = emptyList()
}

/** A statement that binds names: inline and block `set`. */
interface JinjaAssignment : JinjaStatement {
    /** The assignment target: a name, `ns.attr` or a tuple of names. */
    val target: JinjaTarget?

    /** The names the statement binds (none for a namespace attribute target). */
    val targetNames: List<JinjaTargetName>
        get() = target?.targetNames.orEmpty()
}

/** An assignment target: [JinjaTargetName], [JinjaNamespaceTarget] or [JinjaTargetTuple]. */
interface JinjaTarget : JinjaElement {
    /** The plain names bound by this target, in source order. */
    val targetNames: List<JinjaTargetName>
}

/** The base of every Ansible Jinja PSI class; dispatches [JinjaElementVisitor]s. */
abstract class JinjaPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), JinjaElement {
    final override fun accept(visitor: PsiElementVisitor) {
        if (visitor is JinjaElementVisitor) accept(visitor) else super.accept(visitor)
    }

    /** Calls the most specific `visit…` method of [visitor]. */
    open fun accept(visitor: JinjaElementVisitor) {
        visitor.visitJinjaElement(this)
    }

    /** The first direct child token of [type]. */
    protected fun childToken(type: IElementType): PsiElement? = node.findChildByType(type)?.psi

    /** The first direct child token of one of [types]. */
    protected fun childToken(types: TokenSet): PsiElement? = node.findChildByType(types)?.psi

    /** Whether a direct child token of [type] exists. */
    protected fun hasChildToken(type: IElementType): Boolean = node.findChildByType(type) != null

    /** Direct children of class [T], in source order. */
    protected fun <T : PsiElement> childrenOf(type: Class<T>): List<T> = PsiTreeUtil.getChildrenOfTypeAsList(this, type)

    /** The first direct child of class [T]. */
    protected fun <T : PsiElement> childOf(type: Class<T>): T? = PsiTreeUtil.getChildOfType(this, type)

    /** The direct expression children, in source order. */
    protected val expressionChildren: List<JinjaExpression>
        get() = childrenOf(JinjaExpression::class.java)

    /** The first direct expression child after the direct child token of [type], or null. */
    protected fun expressionAfter(type: IElementType): JinjaExpression? {
        val token = node.findChildByType(type) ?: return null
        var next = token.treeNext
        while (next != null) {
            val psi = next.psi
            if (psi is JinjaExpression) return psi
            next = next.treeNext
        }
        return null
    }

    /** The first direct expression child before the direct child token of [type] (or the first one if no such token). */
    protected fun expressionBefore(type: IElementType): JinjaExpression? {
        val stop = node.findChildByType(type)
        var child = node.firstChildNode
        while (child != null && child != stop) {
            val psi = child.psi
            if (psi is JinjaExpression) return psi
            child = child.treeNext
        }
        return null
    }
}

/** A [JinjaPsiElement] that is a [JinjaExpression]. */
abstract class JinjaExpressionElement(node: ASTNode) : JinjaPsiElement(node), JinjaExpression {
    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitExpression(this)
    }
}

/** A [JinjaPsiElement] that is a [JinjaTag] of a block statement. */
abstract class JinjaTagElement(node: ASTNode) : JinjaPsiElement(node), JinjaTag {
    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitTag(this)
    }
}

/**
 * Visitor over the Ansible Jinja PSI. Each method falls back to the next more general one (a for statement is a
 * statement, a filter call an expression …), ending in [visitJinjaElement] and [visitElement].
 */
open class JinjaElementVisitor : PsiElementVisitor() {
    open fun visitJinjaElement(element: JinjaElement) = visitElement(element)

    open fun visitExpression(expression: JinjaExpression) = visitJinjaElement(expression)
    open fun visitVariableReference(reference: JinjaVariableReference) = visitExpression(reference)
    open fun visitMemberAccess(access: JinjaMemberAccess) = visitExpression(access)
    open fun visitSubscription(subscription: JinjaSubscription) = visitExpression(subscription)
    open fun visitCallExpression(call: JinjaCallExpression) = visitExpression(call)
    open fun visitFilterCall(filter: JinjaFilterCall) = visitExpression(filter)
    open fun visitTestExpr(test: JinjaTestExpr) = visitExpression(test)
    open fun visitLiteral(literal: JinjaLiteral) = visitExpression(literal)

    open fun visitTargetName(target: JinjaTargetName) = visitJinjaElement(target)
    open fun visitNamespaceTarget(target: JinjaNamespaceTarget) = visitJinjaElement(target)

    open fun visitOutputTag(tag: JinjaOutputTag) = visitJinjaElement(tag)
    open fun visitTag(tag: JinjaTag) = visitJinjaElement(tag)
    open fun visitBody(body: JinjaBody) = visitJinjaElement(body)

    open fun visitStatement(statement: JinjaStatement) = visitJinjaElement(statement)
    open fun visitIfStatement(statement: JinjaIfStatement) = visitStatement(statement)
    open fun visitForStatement(statement: JinjaForStatement) = visitStatement(statement)
    open fun visitSetStatement(statement: JinjaSetStatement) = visitStatement(statement)
    open fun visitSetBlockStatement(statement: JinjaSetBlockStatement) = visitStatement(statement)
    open fun visitMacro(macro: JinjaMacro) = visitStatement(macro)
    open fun visitCallBlock(block: JinjaCallBlock) = visitStatement(block)
    open fun visitFilterBlock(block: JinjaFilterBlock) = visitStatement(block)
    open fun visitWithStatement(statement: JinjaWithStatement) = visitStatement(statement)
    open fun visitBlockStatement(statement: JinjaBlockStatement) = visitStatement(statement)
    open fun visitRawStatement(statement: JinjaRawStatement) = visitStatement(statement)
    open fun visitIncludeStatement(statement: JinjaIncludeStatement) = visitStatement(statement)
    open fun visitImportStatement(statement: JinjaImportStatement) = visitStatement(statement)
    open fun visitFromImportStatement(statement: JinjaFromImportStatement) = visitStatement(statement)
}
