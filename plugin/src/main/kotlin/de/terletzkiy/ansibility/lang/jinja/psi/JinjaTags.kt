package de.terletzkiy.ansibility.lang.jinja.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

/** `{{ … }}`: prints an expression (Jinja also accepts a bare tuple here). */
class JinjaOutputTag(node: ASTNode) : JinjaPsiElement(node) {
    /** `{{`, `{{-` or `{{+`. */
    val startDelimiter: PsiElement
        get() = firstChild

    /** `}}` or `-}}`; null when the tag is cut off by the end of the text. */
    val endDelimiter: PsiElement?
        get() = childToken(T.VAR_END)

    /** The printed expression. */
    val expression: JinjaExpression?
        get() = expressionChildren.firstOrNull()

    val isClosed: Boolean
        get() = endDelimiter != null

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitOutputTag(this)
    }
}

/** The content of one branch of a block statement: outer text, output tags, comments and nested statements. */
class JinjaBody(node: ASTNode) : JinjaPsiElement(node) {
    /** The statements directly in this body. */
    val statements: List<JinjaStatement>
        get() = childrenOf(JinjaStatement::class.java)

    /** The output tags directly in this body. */
    val outputTags: List<JinjaOutputTag>
        get() = childrenOf(JinjaOutputTag::class.java)

    /** The tag before this body: the opening, `elif` or `else` tag it belongs to. */
    val ownerTag: JinjaTag?
        get() {
            var previous = prevSibling
            while (previous != null && previous !is JinjaTag) previous = previous.prevSibling
            return previous as? JinjaTag
        }

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitBody(this)
    }
}

/** `{% if cond %}` or `{% elif cond %}`. */
class JinjaIfTag(node: ASTNode) : JinjaTagElement(node) {
    /** The condition (Jinja allows a tuple here, never an inline `if`). */
    val condition: JinjaExpression?
        get() = expressionChildren.firstOrNull()

    /** This is an `elif` tag. */
    val isElif: Boolean
        get() = node.elementType == AnsibleJinjaElementTypes.ELIF_TAG
}

/** `{% else %}` of an `if` or a `for`. */
class JinjaElseTag(node: ASTNode) : JinjaTagElement(node)

/** `{% endif %}`, `{% endfor %}`, `{% endblock name %}` … */
class JinjaEndTag(node: ASTNode) : JinjaTagElement(node) {
    /** The end keyword's token type (`ENDIF_KEYWORD` …). */
    val keywordType: IElementType?
        get() = tagKeyword?.node?.elementType

    /** The block name repeated after `endblock`, if any. */
    val blockName: String?
        get() = tagKeyword?.let { nextToken(it.node, IDENTIFIERS) }?.text

    private companion object {
        val IDENTIFIERS = TokenSet.create(T.IDENTIFIER)
    }
}

/** `{% for target in iterable [if condition] [recursive] %}`. */
class JinjaForTag(node: ASTNode) : JinjaTagElement(node) {
    /** The loop variable(s): a name or a tuple such as `key, value`. */
    val target: JinjaTarget?
        get() = childOf(JinjaTarget::class.java)

    val targetNames: List<JinjaTargetName>
        get() = target?.targetNames.orEmpty()

    /** The iterated expression (after `in`). */
    val iterable: JinjaExpression?
        get() = expressionAfter(T.IN_KEYWORD)

    /** The loop filter after `if`, which skips items. */
    val filterCondition: JinjaExpression?
        get() = expressionAfter(T.IF_KEYWORD)

    /** `recursive`: `loop(…)` may be called in the body. */
    val isRecursive: Boolean
        get() = hasChildToken(T.RECURSIVE_KEYWORD)
}

/** The opening tag of a block set: `{% set target | filters %}`. */
class JinjaSetTag(node: ASTNode) : JinjaTagElement(node) {
    val target: JinjaTarget?
        get() = childOf(JinjaTarget::class.java)

    /** The outermost filter applied to the body (the last one written), if any. */
    val filter: JinjaFilterCall?
        get() = childOf(JinjaFilterCall::class.java)
}

/** `{% macro name(params) %}`. */
class JinjaMacroTag(node: ASTNode) : JinjaTagElement(node) {
    val nameElement: JinjaTargetName?
        get() = childOf(JinjaTargetName::class.java)

    val parameterList: JinjaParameterList?
        get() = childOf(JinjaParameterList::class.java)
}

/** `{% call(params) macro(args) %}`. */
class JinjaCallTag(node: ASTNode) : JinjaTagElement(node) {
    /** The parameters the macro's `caller(…)` passes to the body. */
    val parameterList: JinjaParameterList?
        get() = childOf(JinjaParameterList::class.java)

    /** The macro call. */
    val call: JinjaCallExpression?
        get() = expressionChildren.firstOrNull() as? JinjaCallExpression
}

/** `{% filter name(args) | other %}`. */
class JinjaFilterTag(node: ASTNode) : JinjaTagElement(node) {
    /** The outermost (last written) filter. */
    val filter: JinjaFilterCall?
        get() = childOf(JinjaFilterCall::class.java)
}

/** `{% with a = 1, b = x %}`. */
class JinjaWithTag(node: ASTNode) : JinjaTagElement(node) {
    val assignments: List<JinjaWithAssignment>
        get() = childrenOf(JinjaWithAssignment::class.java)
}

/** `{% block name [scoped] [required] %}`. */
class JinjaBlockTag(node: ASTNode) : JinjaTagElement(node) {
    /** The block name token. */
    val blockNameElement: PsiElement?
        get() = tagKeyword?.let { nextToken(it.node, IDENTIFIERS) }

    val blockName: String?
        get() = blockNameElement?.text

    val isScoped: Boolean
        get() = modifiers().contains("scoped")

    val isRequired: Boolean
        get() = modifiers().contains("required")

    private fun modifiers(): List<String> {
        val name = blockNameElement ?: return emptyList()
        return generateSequence(name.nextSibling) { it.nextSibling }
            .filter { it.node.elementType == T.IDENTIFIER }
            .map { it.text }
            .toList()
    }

    private companion object {
        val IDENTIFIERS = TokenSet.create(T.IDENTIFIER)
    }
}

/** `{% raw %}`. */
class JinjaRawTag(node: ASTNode) : JinjaTagElement(node)
