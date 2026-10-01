package de.terletzkiy.ansibility.lang.jinja.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

// ==================================================================================================== block statements

/** The base of statements with tags and bodies (`if`, `for`, `macro` …). */
abstract class JinjaBlockStatementElement(node: ASTNode) : JinjaPsiElement(node), JinjaStatement {
    override val openingTag: JinjaTag?
        get() = childOf(JinjaTag::class.java)

    override val endTag: JinjaEndTag?
        get() = childOf(JinjaEndTag::class.java)

    override val bodies: List<JinjaBody>
        get() = childrenOf(JinjaBody::class.java)

    /** The first body: the content right after the opening tag. */
    val body: JinjaBody?
        get() = childOf(JinjaBody::class.java)

    /** The body that follows [tag] (a direct child), or null. */
    protected fun bodyAfter(tag: PsiElement?): JinjaBody? {
        var next = tag?.nextSibling
        while (next != null) {
            if (next is JinjaBody) return next
            if (next is JinjaTag) return null
            next = next.nextSibling
        }
        return null
    }

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitStatement(this)
    }
}

/** `{% if %}…{% elif %}…{% else %}…{% endif %}`. Does not open a scope. */
class JinjaIfStatement(node: ASTNode) : JinjaBlockStatementElement(node) {
    /** One branch: its tag, the condition (null for `else`) and its body. */
    data class Branch(val tag: JinjaTag, val condition: JinjaExpression?, val body: JinjaBody?)

    /** The `if` tag. */
    val ifTag: JinjaIfTag?
        get() = childrenOf(JinjaIfTag::class.java).firstOrNull { !it.isElif }

    /** The condition of the `if` tag. */
    val condition: JinjaExpression?
        get() = ifTag?.condition

    val elifTags: List<JinjaIfTag>
        get() = childrenOf(JinjaIfTag::class.java).filter { it.isElif }

    val elseTag: JinjaElseTag?
        get() = childOf(JinjaElseTag::class.java)

    /** The body of the `else` branch. */
    val elseBody: JinjaBody?
        get() = bodyAfter(elseTag)

    /** The `if`, `elif` and `else` branches in order. */
    val branches: List<Branch>
        get() = childrenOf(JinjaTag::class.java).filter { it !is JinjaEndTag }.map { tag ->
            Branch(tag, (tag as? JinjaIfTag)?.condition, bodyAfter(tag))
        }

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitIfStatement(this)
    }
}

/** `{% for … in … %}…[{% else %}…]{% endfor %}`. The body opens a scope; the `else` body runs in the outer one. */
class JinjaForStatement(node: ASTNode) : JinjaBlockStatementElement(node) {
    val forTag: JinjaForTag?
        get() = childOf(JinjaForTag::class.java)

    /** The loop variable(s). */
    val target: JinjaTarget?
        get() = forTag?.target

    val targetNames: List<JinjaTargetName>
        get() = forTag?.targetNames.orEmpty()

    val iterable: JinjaExpression?
        get() = forTag?.iterable

    /** The loop filter (`for x in xs if cond`). */
    val filterCondition: JinjaExpression?
        get() = forTag?.filterCondition

    val isRecursive: Boolean
        get() = forTag?.isRecursive == true

    val elseTag: JinjaElseTag?
        get() = childOf(JinjaElseTag::class.java)

    /** The body run when the iterable is empty. */
    val elseBody: JinjaBody?
        get() = bodyAfter(elseTag)

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitForStatement(this)
    }
}

/** `{% set target | filters %}…{% endset %}`: the rendered body (filtered) is assigned. */
class JinjaSetBlockStatement(node: ASTNode) : JinjaBlockStatementElement(node), JinjaAssignment {
    val setTag: JinjaSetTag?
        get() = childOf(JinjaSetTag::class.java)

    override val target: JinjaTarget?
        get() = setTag?.target

    /** The outermost filter applied to the body, if any. */
    val filter: JinjaFilterCall?
        get() = setTag?.filter

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitSetBlockStatement(this)
    }
}

/** `{% macro name(params) %}…{% endmacro %}`. The body opens a scope with the parameters, `varargs`, `kwargs`, `caller`. */
class JinjaMacro(node: ASTNode) : JinjaBlockStatementElement(node) {
    val macroTag: JinjaMacroTag?
        get() = childOf(JinjaMacroTag::class.java)

    /** The macro name as a binding. */
    val nameElement: JinjaTargetName?
        get() = macroTag?.nameElement

    override fun getName(): String? = nameElement?.name

    val parameterList: JinjaParameterList?
        get() = macroTag?.parameterList

    val parameters: List<JinjaParameter>
        get() = parameterList?.parameters.orEmpty()

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitMacro(this)
    }
}

/** `{% call(params) macro(args) %}…{% endcall %}`. */
class JinjaCallBlock(node: ASTNode) : JinjaBlockStatementElement(node) {
    val callTag: JinjaCallTag?
        get() = childOf(JinjaCallTag::class.java)

    val parameterList: JinjaParameterList?
        get() = callTag?.parameterList

    val call: JinjaCallExpression?
        get() = callTag?.call

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitCallBlock(this)
    }
}

/** `{% filter f %}…{% endfilter %}`. */
class JinjaFilterBlock(node: ASTNode) : JinjaBlockStatementElement(node) {
    val filterTag: JinjaFilterTag?
        get() = childOf(JinjaFilterTag::class.java)

    /** The outermost filter. */
    val filter: JinjaFilterCall?
        get() = filterTag?.filter

    /** The filters in application order (first written first). */
    val filters: List<JinjaFilterCall>
        get() = generateSequence(filter) { it.operand as? JinjaFilterCall }.toList().asReversed()

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitFilterBlock(this)
    }
}

/** `{% with a = 1 %}…{% endwith %}`. */
class JinjaWithStatement(node: ASTNode) : JinjaBlockStatementElement(node) {
    val withTag: JinjaWithTag?
        get() = childOf(JinjaWithTag::class.java)

    val assignments: List<JinjaWithAssignment>
        get() = withTag?.assignments.orEmpty()

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitWithStatement(this)
    }
}

/** `{% block name %}…{% endblock %}`. */
class JinjaBlockStatement(node: ASTNode) : JinjaBlockStatementElement(node) {
    val blockTag: JinjaBlockTag?
        get() = childOf(JinjaBlockTag::class.java)

    val blockName: String?
        get() = blockTag?.blockName

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitBlockStatement(this)
    }
}

/** `{% raw %}…{% endraw %}`: the body is output verbatim and never parsed (Go templates, `{{ … }}` examples). */
class JinjaRawStatement(node: ASTNode) : JinjaBlockStatementElement(node) {
    /** The opaque body token, null for an empty raw block. */
    val rawText: PsiElement?
        get() = childToken(T.RAW_TEXT)

    /** The verbatim body. */
    val content: String
        get() = rawText?.text.orEmpty()

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitRawStatement(this)
    }
}

// ==================================================================================================== single-tag statements

/** The base of statements that consist of one tag. */
abstract class JinjaSingleTagStatement(node: ASTNode) : JinjaPsiElement(node), JinjaStatement, JinjaTag {
    override val openingTag: JinjaTag
        get() = this

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitStatement(this)
    }
}

/** `{% set x = value %}`, `{% set a, b = 1, 2 %}`, `{% set ns.attr = value %}`. */
class JinjaSetStatement(node: ASTNode) : JinjaSingleTagStatement(node), JinjaAssignment {
    override val target: JinjaTarget?
        get() = childOf(JinjaTarget::class.java)

    /** The `ns.attr` target, if this sets a namespace attribute. */
    val namespaceTarget: JinjaNamespaceTarget?
        get() = target as? JinjaNamespaceTarget

    /** The assigned value (after `=`). */
    val value: JinjaExpression?
        get() = expressionAfter(T.ASSIGN)

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitSetStatement(this)
    }
}

/** `include`, `import` and `from … import`: a template name plus the optional `with context` / `without context` clause. */
abstract class JinjaTemplateReferenceStatement(node: ASTNode) : JinjaSingleTagStatement(node) {
    /** The template name expression. */
    val template: JinjaExpression?
        get() = expressionChildren.firstOrNull()

    /** The template name when it is a string literal. */
    val templateName: String?
        get() = (template as? JinjaLiteral)?.stringValue

    /** `with context` (true), `without context` (false), or null when not written. */
    val explicitContext: Boolean?
        get() {
            val tokens = node.getChildren(null).filter { it.elementType != T.WHITE_SPACE }
            val context = tokens.indexOfLast { it.elementType == T.IDENTIFIER && it.text == "context" }
            val before = tokens.getOrNull(context - 1) ?: return null
            return when {
                before.elementType == T.WITH_KEYWORD -> true
                before.elementType == T.IDENTIFIER && before.text == "without" -> false
                else -> null
            }
        }

    /** Whether the template sees the current context: Jinja's default is true for `include`, false for imports. */
    abstract val withContext: Boolean
}

/** `{% include template [ignore missing] [with|without context] %}`. */
class JinjaIncludeStatement(node: ASTNode) : JinjaTemplateReferenceStatement(node) {
    val ignoreMissing: Boolean
        get() = node.getChildren(null).any { it.elementType == T.IDENTIFIER && it.text == "missing" }

    override val withContext: Boolean
        get() = explicitContext ?: true

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitIncludeStatement(this)
    }
}

/** `{% import template as name [with|without context] %}`. */
class JinjaImportStatement(node: ASTNode) : JinjaTemplateReferenceStatement(node) {
    /** The module variable after `as`. */
    val alias: JinjaTargetName?
        get() = childOf(JinjaTargetName::class.java)

    override val withContext: Boolean
        get() = explicitContext ?: false

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitImportStatement(this)
    }
}

/** `{% from template import a, b as c [with|without context] %}`. */
class JinjaFromImportStatement(node: ASTNode) : JinjaTemplateReferenceStatement(node) {
    val importedNames: List<JinjaImportedName>
        get() = childrenOf(JinjaImportedName::class.java)

    override val withContext: Boolean
        get() = explicitContext ?: false

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitFromImportStatement(this)
    }
}

/** `{% extends template %}`. */
class JinjaExtendsStatement(node: ASTNode) : JinjaSingleTagStatement(node) {
    val template: JinjaExpression?
        get() = expressionChildren.firstOrNull()
}

/** `{% do expression %}` (the `do` extension Ansible enables). */
class JinjaDoStatement(node: ASTNode) : JinjaSingleTagStatement(node) {
    val expression: JinjaExpression?
        get() = expressionChildren.firstOrNull()
}

/** `{% break %}` or `{% continue %}` (the `loopcontrols` extension Ansible enables). */
class JinjaLoopControlStatement(node: ASTNode) : JinjaSingleTagStatement(node) {
    val isBreak: Boolean
        get() = node.elementType == AnsibleJinjaElementTypes.BREAK_STATEMENT
}

/**
 * A tag Jinja core does not define (`{% print x %}`, `{% autoescape %}`, extension tags). Its arguments are parsed as
 * comma-separated expressions when they are expressions, else kept as plain tokens.
 */
class JinjaGenericStatement(node: ASTNode) : JinjaSingleTagStatement(node) {
    /** The parsed argument expressions. */
    val expressions: List<JinjaExpression>
        get() = expressionChildren
}
