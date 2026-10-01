package de.terletzkiy.ansibility.lang.jinja.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

/** A bare name in load context: `item`, `haproxy_servers`, `lookup` in `lookup('file', …)`. */
class JinjaVariableReference(node: ASTNode) : JinjaExpressionElement(node) {
    /** The name token. */
    val nameIdentifier: PsiElement
        get() = firstChild

    /** The referenced name. */
    override fun getName(): String = nameIdentifier.text

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitVariableReference(this)
    }
}

/** Attribute access `x.attr`, also `x.0` (Jinja reads `.0` as item `0`). */
class JinjaMemberAccess(node: ASTNode) : JinjaExpressionElement(node) {
    /** The expression before the dot. */
    val qualifier: JinjaExpression?
        get() = expressionChildren.firstOrNull()

    /** The attribute name or number token after the dot; null when it is missing. */
    val memberNameElement: PsiElement?
        get() = node.findChildByType(T.DOT)?.let { dot -> nextToken(dot, MEMBER_TOKENS) }

    /** The attribute name (or number) as written. */
    val memberName: String?
        get() = memberNameElement?.text

    /** The attribute is a number: `item.0`. */
    val isIndex: Boolean
        get() = memberNameElement?.node?.elementType == T.INTEGER

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitMemberAccess(this)
    }

    private companion object {
        val MEMBER_TOKENS: TokenSet = TokenSet.create(T.IDENTIFIER, T.INTEGER)
    }
}

/** Item access `x[key]`, `x[1:-1]`, `x[a, b]`. */
class JinjaSubscription(node: ASTNode) : JinjaExpressionElement(node) {
    /** The expression before `[`. */
    val qualifier: JinjaExpression?
        get() = expressionChildren.firstOrNull()

    /** What is inside the brackets: an expression, a [JinjaSlice] or a [JinjaTupleExpression] of several. */
    val index: JinjaExpression?
        get() = expressionAfter(T.LBRACKET)

    /** The subscript is a slice. */
    val isSlice: Boolean
        get() = index is JinjaSlice

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitSubscription(this)
    }
}

/** `start:stop:step` inside brackets; each part is optional. */
class JinjaSlice(node: ASTNode) : JinjaExpressionElement(node) {
    val start: JinjaExpression?
        get() = part(0)

    val stop: JinjaExpression?
        get() = part(1)

    val step: JinjaExpression?
        get() = part(2)

    private fun part(index: Int): JinjaExpression? {
        var colons = 0
        var child = node.firstChildNode
        while (child != null) {
            when {
                child.elementType == T.COLON -> colons++
                colons == index && child.psi is JinjaExpression -> return child.psi as JinjaExpression
            }
            if (colons > index) return null
            child = child.treeNext
        }
        return null
    }
}

/** A call `f(args)`, including method calls such as `x.items()` and `range(3)`. */
class JinjaCallExpression(node: ASTNode) : JinjaExpressionElement(node) {
    /** The called expression. */
    val callee: JinjaExpression?
        get() = expressionChildren.firstOrNull()

    val argumentList: JinjaArgumentList?
        get() = childOf(JinjaArgumentList::class.java)

    /** The called function or method name: `lookup` in `lookup(…)`, `items` in `d.items()`; null otherwise. */
    val calleeName: String?
        get() = when (val callee = callee) {
            is JinjaVariableReference -> callee.name
            is JinjaMemberAccess -> callee.memberName
            else -> null
        }

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitCallExpression(this)
    }
}

/** `(a, b, key=value, *args, **kwargs)` of a call, filter or test. */
class JinjaArgumentList(node: ASTNode) : JinjaPsiElement(node) {
    /** Positional arguments in order. */
    val positionalArguments: List<JinjaExpression>
        get() = expressionChildren

    val keywordArguments: List<JinjaKeywordArgument>
        get() = childrenOf(JinjaKeywordArgument::class.java)

    /** `*args`. */
    val starArgument: JinjaStarArgument?
        get() = childrenOf(JinjaStarArgument::class.java).firstOrNull { !it.isDoubleStar }

    /** `**kwargs`. */
    val doubleStarArgument: JinjaStarArgument?
        get() = childrenOf(JinjaStarArgument::class.java).firstOrNull { it.isDoubleStar }

    /** The keyword argument called [name], if any. */
    fun keywordArgument(name: String): JinjaKeywordArgument? = keywordArguments.firstOrNull { it.name == name }

    /** The list is closed by `)`. */
    val isClosed: Boolean
        get() = hasChildToken(T.RPAREN)
}

/** `name=value` in an argument list. */
class JinjaKeywordArgument(node: ASTNode) : JinjaPsiElement(node) {
    val nameElement: PsiElement
        get() = firstChild

    override fun getName(): String = nameElement.text

    val value: JinjaExpression?
        get() = expressionChildren.firstOrNull()
}

/** `*args` or `**kwargs` in an argument list. */
class JinjaStarArgument(node: ASTNode) : JinjaPsiElement(node) {
    val isDoubleStar: Boolean
        get() = node.elementType == AnsibleJinjaElementTypes.DOUBLE_STAR_ARGUMENT

    val value: JinjaExpression?
        get() = expressionChildren.firstOrNull()
}

/** The dotted name of a filter or test (`default`, `ansible.builtin.splitext`). */
abstract class JinjaDottedName(node: ASTNode, private val segmentType: IElementType) : JinjaPsiElement(node) {
    /** The name segments as tokens. */
    val segments: List<PsiElement>
        get() = node.getChildren(TokenSet.create(segmentType)).map { it.psi }

    /** The name with its segments joined by `.`. */
    override fun getName(): String = segments.joinToString(".") { it.text }

    /** The last segment: `splitext` in `ansible.builtin.splitext`. */
    val shortName: String
        get() = segments.lastOrNull()?.text.orEmpty()

    /** The name is a fully qualified collection name (it has dots). */
    val isQualified: Boolean
        get() = segments.size > 1
}

/** The name of a filter in a [JinjaFilterCall]. */
class JinjaFilterName(node: ASTNode) : JinjaDottedName(node, T.FILTER_NAME)

/** The name of a test in a [JinjaTestExpr]. */
class JinjaTestName(node: ASTNode) : JinjaDottedName(node, T.TEST_NAME)

/**
 * A filter application `operand | name(args)`. Chains nest to the left: in `x | a | b` the operand of `b` is the
 * `a` call. The first filter of `{% filter f %}` and of a block `{% set x | f %}` has no operand.
 */
class JinjaFilterCall(node: ASTNode) : JinjaExpressionElement(node) {
    /** The filtered expression; null for the operand-less filters of filter blocks and block sets. */
    val operand: JinjaExpression?
        get() = expressionBefore(T.PIPE).takeIf { node.findChildByType(T.PIPE) != null }

    val filterNameElement: JinjaFilterName?
        get() = childOf(JinjaFilterName::class.java)

    /** The filter name as written, dotted names joined: `ansible.builtin.splitext`. */
    val filterName: String?
        get() = filterNameElement?.name

    val argumentList: JinjaArgumentList?
        get() = childOf(JinjaArgumentList::class.java)

    /** The positional arguments after the operand (empty without parentheses). */
    val arguments: List<JinjaExpression>
        get() = argumentList?.positionalArguments.orEmpty()

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitFilterCall(this)
    }
}

/** A test `operand is [not] name args`: `x is defined`, `x is not none`, `n is divisibleby 3`, `v is version('2', '>=')`. */
class JinjaTestExpr(node: ASTNode) : JinjaExpressionElement(node) {
    val operand: JinjaExpression?
        get() = expressionBefore(T.IS_KEYWORD)

    /** `is not`. */
    val isNegated: Boolean
        get() = hasChildToken(T.NOT_KEYWORD)

    val testNameElement: JinjaTestName?
        get() = childOf(JinjaTestName::class.java)

    /** The test name as written, dotted names joined. */
    val testName: String?
        get() = testNameElement?.name

    /** The parenthesized arguments, if written. */
    val argumentList: JinjaArgumentList?
        get() = childOf(JinjaArgumentList::class.java)

    /** The arguments: from the argument list, or the single argument without parentheses (`divisibleby 3`). */
    val arguments: List<JinjaExpression>
        get() = argumentList?.positionalArguments ?: listOfNotNull(expressionAfter(T.IS_KEYWORD))

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitTestExpr(this)
    }
}

/** `not x`, `-x` or `+x`. */
class JinjaUnaryExpression(node: ASTNode) : JinjaExpressionElement(node) {
    /** The operator token. */
    val operatorElement: PsiElement
        get() = firstChild

    val operatorType: IElementType
        get() = operatorElement.node.elementType

    /** The operator is `not`. */
    val isNot: Boolean
        get() = operatorType == T.NOT_KEYWORD

    val operand: JinjaExpression?
        get() = expressionChildren.firstOrNull()
}

/** A binary operation: `and`, `or`, arithmetic, `~` (string concatenation) or `**`. */
class JinjaBinaryExpression(node: ASTNode) : JinjaExpressionElement(node) {
    val left: JinjaExpression?
        get() = expressionChildren.firstOrNull()

    val right: JinjaExpression?
        get() = expressionChildren.getOrNull(1)

    /** The operator token. */
    val operatorElement: PsiElement?
        get() = childToken(OPERATORS)

    val operatorType: IElementType?
        get() = operatorElement?.node?.elementType

    private companion object {
        val OPERATORS: TokenSet = TokenSet.create(
            T.AND_KEYWORD, T.OR_KEYWORD, T.PLUS, T.MINUS, T.TILDE, T.MUL, T.DIV, T.FLOORDIV, T.MOD, T.POW,
        )
    }
}

/** A comparison chain: `a == b`, `0 < x <= 10`, `'x' in group_names`, `x not in y`. */
class JinjaCompareExpression(node: ASTNode) : JinjaExpressionElement(node) {
    /** The compared expressions, in order. */
    val operands: List<JinjaExpression>
        get() = expressionChildren

    /** The operators between [operands]: `==`, `!=`, `<`, `<=`, `>`, `>=`, `in`, `not in`. */
    val operators: List<String>
        get() {
            val result = ArrayList<String>()
            var pendingNot = false
            var child = node.firstChildNode
            while (child != null) {
                when (child.elementType) {
                    T.NOT_KEYWORD -> pendingNot = true
                    T.IN_KEYWORD -> {
                        result += if (pendingNot) "not in" else "in"
                        pendingNot = false
                    }
                    T.EQEQ, T.NE, T.LT, T.LE, T.GT, T.GE -> result += child.text
                }
                child = child.treeNext
            }
            return result
        }
}

/** `a if cond else b`; `else` is optional (an undefined value when the condition is false). */
class JinjaConditionalExpression(node: ASTNode) : JinjaExpressionElement(node) {
    /** The value when [condition] holds (before `if`). */
    val thenExpression: JinjaExpression?
        get() = expressionBefore(T.IF_KEYWORD)

    /** The expression after `if`. */
    val condition: JinjaExpression?
        get() = expressionAfter(T.IF_KEYWORD)

    /** The expression after `else`, if any. */
    val elseExpression: JinjaExpression?
        get() = expressionAfter(T.ELSE_KEYWORD)
}

/** A constant: string (adjacent strings concatenate, as in Jinja), integer, float, boolean or none. */
class JinjaLiteral(node: ASTNode) : JinjaExpressionElement(node) {
    /** The kind of constant. */
    enum class Kind { STRING, INTEGER, FLOAT, BOOLEAN, NONE }

    val kind: Kind
        get() = when (firstChild.node.elementType) {
            T.STRING -> Kind.STRING
            T.INTEGER -> Kind.INTEGER
            T.FLOAT -> Kind.FLOAT
            T.TRUE_KEYWORD, T.FALSE_KEYWORD -> Kind.BOOLEAN
            else -> Kind.NONE
        }

    /** The string value with escapes decoded and adjacent strings joined; null for other kinds. */
    val stringValue: String?
        get() {
            if (kind != Kind.STRING) return null
            return node.getChildren(STRINGS).joinToString("") { JinjaStrings.unquote(it.text) }
        }

    /** The boolean value; null for other kinds. */
    val booleanValue: Boolean?
        get() = when (firstChild.node.elementType) {
            T.TRUE_KEYWORD -> true
            T.FALSE_KEYWORD -> false
            else -> null
        }

    override fun accept(visitor: JinjaElementVisitor) {
        visitor.visitLiteral(this)
    }

    private companion object {
        val STRINGS: TokenSet = TokenSet.create(T.STRING)
    }
}

/** `[a, b]`. */
class JinjaListLiteral(node: ASTNode) : JinjaExpressionElement(node) {
    val elements: List<JinjaExpression>
        get() = expressionChildren
}

/** `(a, b)`, `()`, `(a,)`, or a bare `a, b` where Jinja allows a tuple (output tags, `set` values, `for` iterables). */
class JinjaTupleExpression(node: ASTNode) : JinjaExpressionElement(node) {
    val elements: List<JinjaExpression>
        get() = expressionChildren

    /** The tuple is written with parentheses. */
    val isParenthesized: Boolean
        get() = firstChild?.node?.elementType == T.LPAREN
}

/** `{key: value, …}`. */
class JinjaDictLiteral(node: ASTNode) : JinjaExpressionElement(node) {
    val entries: List<JinjaDictEntry>
        get() = childrenOf(JinjaDictEntry::class.java)
}

/** `key: value` in a dict literal. */
class JinjaDictEntry(node: ASTNode) : JinjaPsiElement(node) {
    val key: JinjaExpression?
        get() = expressionBefore(T.COLON)

    val value: JinjaExpression?
        get() = expressionAfter(T.COLON)
}

/** `(expr)`. */
class JinjaParenthesizedExpression(node: ASTNode) : JinjaExpressionElement(node) {
    val expression: JinjaExpression?
        get() = expressionChildren.firstOrNull()
}

/** The first token of one of [types] after [from] among its following siblings, skipping whitespace and comments only. */
internal fun nextToken(from: ASTNode, types: TokenSet): PsiElement? {
    var next = from.treeNext
    while (next != null) {
        val type = next.elementType
        if (type in types) return next.psi
        if (type != T.WHITE_SPACE && type !in T.COMMENTS) return null
        next = next.treeNext
    }
    return null
}
