package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaStrings
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

/** One significant token of a Jinja text (whitespace, outer text and comments are dropped). */
internal class JinjaTok(val type: IElementType, val start: Int, val end: Int)

/**
 * The token front end of [ConditionFacts] for the text-level fallback ([TextJinjaUses], `when:` values without an
 * injected fragment): a small recursive-descent parser of Jinja expressions (the precedence of Jinja's own parser:
 * inline `if`, `or`, `and`, `not`, comparisons, arithmetic and `~`, unary signs, postfix accessors and calls, filters
 * and tests) producing [Node]s with token ranges, and the [Cond] of a node, equal to what [JinjaConditions] builds from
 * the PSI of the same text.
 */
internal object JinjaTokenExpressions {
    /** The significant tokens of [text] lexed in [mode]. */
    fun tokens(text: CharSequence, mode: JinjaLexMode): List<JinjaTok> {
        val lexer = AnsibleJinjaLexer(mode)
        lexer.start(text)
        val tokens = ArrayList<JinjaTok>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != T.WHITE_SPACE && type != T.TEXT && type != T.RAW_TEXT && type != T.COMMENT) tokens += JinjaTok(type, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }

    /** A parsed expression over the tokens `[from, to)`. */
    sealed class Node(val from: Int, val to: Int) {
        /** The direct sub-expressions, in source order. */
        abstract val children: List<Node>

        operator fun contains(index: Int): Boolean = index in from until to
    }

    class IfElse(from: Int, to: Int, val then: Node, val condition: Node, val otherwise: Node?) : Node(from, to) {
        override val children: List<Node> get() = listOfNotNull(then, condition, otherwise)
    }

    class Binary(from: Int, to: Int, val operator: IElementType, val left: Node, val right: Node) : Node(from, to) {
        override val children: List<Node> get() = listOf(left, right)
    }

    class Unary(from: Int, to: Int, val operator: IElementType, val operand: Node) : Node(from, to) {
        override val children: List<Node> get() = listOf(operand)
    }

    class Compare(from: Int, to: Int, val operands: List<Node>, val operators: List<String>) : Node(from, to) {
        override val children: List<Node> get() = operands
    }

    /** `operand | name(arguments)`; [name] is null for a qualified filter. */
    class Filter(from: Int, to: Int, val operand: Node, val name: String?, val arguments: List<Node>) : Node(from, to) {
        override val children: List<Node> get() = listOf(operand) + arguments
    }

    /** `operand is [not] name arguments`; [name] is null for a qualified test. */
    class Test(from: Int, to: Int, val operand: Node, val name: String?, val negated: Boolean, val arguments: List<Node>) : Node(from, to) {
        override val children: List<Node> get() = listOf(operand) + arguments
    }

    class Literal(from: Int, to: Int, val value: Any?) : Node(from, to) {
        override val children: List<Node> get() = emptyList()
    }

    /** A constant the parser could not read (an out-of-range number). */
    class Opaque(from: Int, to: Int) : Node(from, to) {
        override val children: List<Node> get() = emptyList()
    }

    class Name(from: Int, to: Int, val name: String) : Node(from, to) {
        override val children: List<Node> get() = emptyList()
    }

    /** `base.name`, `base[index]` (any subscript or slice) or `base(arguments)` ([call]). */
    class Access(from: Int, to: Int, val base: Node, val call: Boolean, val parts: List<Node>) : Node(from, to) {
        override val children: List<Node> get() = listOf(base) + parts
    }

    /** `( expression )`. */
    class Group(from: Int, to: Int, val inner: Node) : Node(from, to) {
        override val children: List<Node> get() = listOf(inner)
    }

    /** A list (`[`), dict (`{`, keys and values in order) or tuple (`(`) display. */
    class Display(from: Int, to: Int, val bracket: IElementType, val elements: List<Node>) : Node(from, to) {
        override val children: List<Node> get() = elements
    }

    /**
     * The expressions of the tag whose tokens are `[from, to)` (delimiters excluded), in order. A `for` tag yields its
     * iterable and its loop filter (its targets are no expressions); other tags yield every expression a scan finds
     * (`set` targets and keyword names come out as plain [Name]s, which carry no facts).
     */
    fun expressionsOf(text: CharSequence, tokens: List<JinjaTok>, from: Int, to: Int): List<Node> {
        if (from >= to) return emptyList()
        if (tokens[from].type == T.FOR_KEYWORD) {
            val inIndex = (from until to).firstOrNull { tokens[it].type == T.IN_KEYWORD } ?: return emptyList()
            val parser = Parser(text, tokens, inIndex + 1, to)
            val iterable = parser.attempt { parser.parseOr() }
            val result = listOfNotNull(iterable)
            if (iterable == null || parser.peek() != T.IF_KEYWORD) return result
            parser.advance()
            return result + listOfNotNull(parser.attempt { parser.parseCondExpr() })
        }
        val start = if (tokens[from].type in STATEMENT_KEYWORDS) from + 1 else from
        val nodes = ArrayList<Node>()
        val parser = Parser(text, tokens, start, to)
        while (parser.position < to) {
            ProgressManager.checkCanceled()
            val node = parser.attempt { parser.parseCondExpr() }
            if (node != null) nodes += node else parser.advance()
        }
        return nodes
    }

    /** One expression over exactly `[from, to)`, or null when the tokens are not one expression. */
    fun expression(text: CharSequence, tokens: List<JinjaTok>, from: Int, to: Int): Node? {
        val parser = Parser(text, tokens, from, to)
        val node = parser.attempt { parser.parseCondExpr() } ?: return null
        return node.takeIf { parser.position == to }
    }

    /**
     * The names the inline conditions around token [index] inside [node] prove defined: the left side of an `and`
     * (true) or `or` (false) whose right side holds it, the condition of an inline `if` whose branch holds it.
     */
    fun inlineFacts(node: Node, index: Int, rules: GuardRules): Set<String> {
        val facts = HashSet<String>()
        var current: Node = node
        while (true) {
            val next = current.children.firstOrNull { index in it } ?: return facts
            when (current) {
                is IfElse -> when {
                    next === current.then -> facts += ConditionFacts.of(cond(current.condition), rules).whenTrue
                    next === current.otherwise -> facts += ConditionFacts.of(cond(current.condition), rules).whenFalse
                }
                is Binary -> if (next === current.right) {
                    when (current.operator) {
                        T.AND_KEYWORD -> facts += ConditionFacts.of(cond(current.left), rules).whenTrue
                        T.OR_KEYWORD -> facts += ConditionFacts.of(cond(current.left), rules).whenFalse
                    }
                }
                else -> Unit
            }
            current = next
        }
    }

    /** The [Cond] of [node], built like [JinjaConditions.cond] builds it from the PSI. */
    fun cond(node: Node?): Cond = when (node) {
        null -> Cond.Opaque
        is Group -> cond(node.inner)
        is Binary -> when (node.operator) {
            T.AND_KEYWORD -> Cond.And(cond(node.left), cond(node.right))
            T.OR_KEYWORD -> Cond.Or(cond(node.left), cond(node.right))
            else -> Cond.Value(term(node))
        }
        is Unary -> if (node.operator == T.NOT_KEYWORD) Cond.Not(cond(node.operand)) else Cond.Value(term(node))
        is Test -> definedTest(node) ?: Cond.Value(term(node))
        is IfElse -> Cond.Opaque
        else -> Cond.Value(term(node))
    }

    private fun definedTest(test: Test): Cond? {
        val positive = when (test.name) {
            "defined" -> !test.negated
            "undefined" -> test.negated
            else -> return null
        }
        val root = rootName(test.operand) ?: return Cond.Opaque
        return Cond.Defined(root, positive)
    }

    /** The root name of an accessor chain, calls included (`x` of `x.y['z'].w()`), as [JinjaConditions.rootName]. */
    private fun rootName(node: Node): String? {
        var current: Node = node
        while (true) {
            current = when (current) {
                is Name -> return current.name
                is Access -> current.base
                else -> return null
            }
        }
    }

    private fun term(node: Node): Term = when (node) {
        is Group -> term(node.inner)
        is Literal -> Term.Literal(node.value)
        is Display -> when (node.bracket) {
            T.LBRACKET -> {
                val elements = node.elements.map(::term)
                if (elements.all { it is Term.Literal }) Term.Literal(elements.map { (it as Term.Literal).value }) else Term.Unknown
            }
            T.LBRACE -> if (node.elements.isEmpty()) Term.Literal(emptyMap<Any?, Any?>()) else Term.Unknown
            else -> Term.Unknown
        }
        is Name -> Term.Variable(node.name)
        is Access -> accessRoot(node)?.let(Term::Variable) ?: Term.Unknown
        is Filter -> Term.Filter(term(node.operand), node.name, node.arguments.map(::term))
        is Compare -> Term.Compare(node.operands.map(::term), node.operators)
        is Test -> if (node.arguments.isEmpty()) Term.Test(term(node.operand), node.name, node.negated) else Term.Unknown
        else -> Term.Unknown
    }

    private fun accessRoot(node: Node): String? {
        var current: Node = node
        while (true) {
            current = when (current) {
                is Name -> return current.name
                is Access -> if (current.call) return null else current.base
                else -> return null
            }
        }
    }

    /** A Jinja integer literal (`1_000`, `0x1F`, `0o7`, `0b1`), or null. */
    fun integer(text: String): Long? {
        val digits = text.replace("_", "").lowercase()
        return when {
            digits.startsWith("0x") -> digits.substring(2).toLongOrNull(16)
            digits.startsWith("0o") -> digits.substring(2).toLongOrNull(8)
            digits.startsWith("0b") -> digits.substring(2).toLongOrNull(2)
            else -> digits.toLongOrNull()
        }
    }

    /** A Jinja float literal, or null. */
    fun float(text: String): Double? = text.replace("_", "").toDoubleOrNull()

    /** Keywords that open a statement tag (the expression scan starts after them). */
    private val STATEMENT_KEYWORDS: Set<IElementType> = setOf(
        T.IF_KEYWORD, T.ELIF_KEYWORD, T.SET_KEYWORD, T.MACRO_KEYWORD, T.CALL_KEYWORD, T.FILTER_KEYWORD, T.WITH_KEYWORD,
        T.INCLUDE_KEYWORD, T.IMPORT_KEYWORD, T.FROM_KEYWORD, T.EXTENDS_KEYWORD, T.BLOCK_KEYWORD, T.DO_KEYWORD,
    )

    private class ParseFailure : RuntimeException(null, null, false, false)

    /** Recursive descent over the tokens `[position, limit)`. */
    private class Parser(private val text: CharSequence, private val tokens: List<JinjaTok>, var position: Int, private val limit: Int) {
        fun peek(offset: Int = 0): IElementType? = (position + offset).takeIf { it < limit }?.let { tokens[it].type }

        fun advance() {
            position++
        }

        private fun expect(type: IElementType) {
            if (peek() != type) throw ParseFailure()
            advance()
        }

        private fun tokenText(index: Int): String = text.subSequence(tokens[index].start, tokens[index].end).toString()

        /** [parse] from the current position; on failure the position is restored and null returned. */
        fun <N : Node> attempt(parse: () -> N): N? {
            val start = position
            return try {
                parse()
            } catch (_: ParseFailure) {
                position = start
                null
            }
        }

        fun parseCondExpr(): Node {
            var node = parseOr()
            while (peek() == T.IF_KEYWORD) {
                advance()
                val condition = parseOr()
                val otherwise = if (peek() == T.ELSE_KEYWORD) {
                    advance()
                    parseCondExpr()
                } else {
                    null
                }
                node = IfElse(node.from, position, node, condition, otherwise)
            }
            return node
        }

        fun parseOr(): Node {
            var left = parseAnd()
            while (peek() == T.OR_KEYWORD) {
                advance()
                val right = parseAnd()
                left = Binary(left.from, position, T.OR_KEYWORD, left, right)
            }
            return left
        }

        private fun parseAnd(): Node {
            var left = parseNot()
            while (peek() == T.AND_KEYWORD) {
                advance()
                val right = parseNot()
                left = Binary(left.from, position, T.AND_KEYWORD, left, right)
            }
            return left
        }

        private fun parseNot(): Node {
            if (peek() != T.NOT_KEYWORD) return parseCompare()
            val start = position
            advance()
            val operand = parseNot()
            return Unary(start, position, T.NOT_KEYWORD, operand)
        }

        private fun parseCompare(): Node {
            val first = parseBinary(0)
            val operands = arrayListOf(first)
            val operators = ArrayList<String>()
            while (true) {
                val type = peek()
                when {
                    type in COMPARISONS -> {
                        operators += tokenText(position)
                        advance()
                    }
                    type == T.IN_KEYWORD -> {
                        operators += "in"
                        advance()
                    }
                    type == T.NOT_KEYWORD && peek(1) == T.IN_KEYWORD -> {
                        operators += "not in"
                        advance()
                        advance()
                    }
                    else -> break
                }
                operands += parseBinary(0)
            }
            return if (operators.isEmpty()) first else Compare(first.from, position, operands, operators)
        }

        /** The arithmetic levels: `+ -`, then `~`, then `* / // %`, then `**`. */
        private fun parseBinary(level: Int): Node {
            if (level == ARITHMETIC.size) return parseUnary(withFilter = true)
            var left = parseBinary(level + 1)
            while (peek() in ARITHMETIC[level]) {
                val operator = peek()!!
                advance()
                val right = parseBinary(level + 1)
                left = Binary(left.from, position, operator, left, right)
            }
            return left
        }

        private fun parseUnary(withFilter: Boolean): Node {
            val type = peek()
            var node = if (type == T.MINUS || type == T.PLUS) {
                val start = position
                advance()
                val operand = parseUnary(withFilter = false)
                Unary(start, position, type, operand)
            } else {
                parsePrimary()
            }
            node = parsePostfix(node)
            if (withFilter) node = parseFilterExpression(node)
            return node
        }

        private fun parsePrimary(): Node {
            val start = position
            return when (peek()) {
                T.IDENTIFIER -> {
                    advance()
                    Name(start, position, tokenText(start))
                }
                T.TRUE_KEYWORD -> constant(start, true)
                T.FALSE_KEYWORD -> constant(start, false)
                T.NONE_KEYWORD -> constant(start, null)
                T.STRING -> {
                    val value = StringBuilder()
                    while (peek() == T.STRING) {
                        value.append(JinjaStrings.unquote(tokenText(position)))
                        advance()
                    }
                    Literal(start, position, value.toString())
                }
                T.INTEGER -> {
                    advance()
                    integer(tokenText(start))?.let { Literal(start, position, it) } ?: Opaque(start, position)
                }
                T.FLOAT -> {
                    advance()
                    float(tokenText(start))?.let { Literal(start, position, it) } ?: Opaque(start, position)
                }
                T.LPAREN -> parseParenthesized()
                T.LBRACKET -> {
                    advance()
                    val elements = sequence(T.RBRACKET) { parseCondExpr() }
                    Display(start, position, T.LBRACKET, elements)
                }
                T.LBRACE -> {
                    advance()
                    val elements = ArrayList<Node>()
                    while (peek() != T.RBRACE) {
                        elements += parseCondExpr()
                        expect(T.COLON)
                        elements += parseCondExpr()
                        if (peek() == T.COMMA) advance() else break
                    }
                    expect(T.RBRACE)
                    Display(start, position, T.LBRACE, elements)
                }
                else -> throw ParseFailure()
            }
        }

        private fun constant(start: Int, value: Any?): Node {
            advance()
            return Literal(start, position, value)
        }

        /** `( )`, `( e )` or a tuple `( e, … )`. */
        private fun parseParenthesized(): Node {
            val start = position
            advance()
            if (peek() == T.RPAREN) {
                advance()
                return Display(start, position, T.LPAREN, emptyList())
            }
            val first = parseCondExpr()
            if (peek() == T.RPAREN) {
                advance()
                return Group(start, position, first)
            }
            val elements = arrayListOf(first)
            while (peek() == T.COMMA) {
                advance()
                if (peek() == T.RPAREN) break
                elements += parseCondExpr()
            }
            expect(T.RPAREN)
            return Display(start, position, T.LPAREN, elements)
        }

        /** Elements separated by commas (a trailing comma allowed) up to and including [close]. */
        private fun sequence(close: IElementType, element: () -> Node): List<Node> {
            val elements = ArrayList<Node>()
            while (peek() != close) {
                elements += element()
                if (peek() == T.COMMA) advance() else break
            }
            expect(close)
            return elements
        }

        private fun parsePostfix(base: Node): Node {
            var node = base
            while (true) {
                node = when (peek()) {
                    T.DOT -> {
                        advance()
                        if (peek() != T.IDENTIFIER && peek() != T.INTEGER) throw ParseFailure()
                        advance()
                        Access(node.from, position, node, call = false, parts = emptyList())
                    }
                    T.LBRACKET -> {
                        advance()
                        val parts = ArrayList<Node>()
                        while (peek() != T.RBRACKET) {
                            if (peek() == T.COLON || peek() == T.COMMA) advance() else parts += parseCondExpr()
                        }
                        expect(T.RBRACKET)
                        Access(node.from, position, node, call = false, parts = parts)
                    }
                    T.LPAREN -> {
                        val arguments = callArguments()
                        Access(node.from, position, node, call = true, parts = arguments)
                    }
                    else -> return node
                }
            }
        }

        /** `( a, k=v, *args, **kwargs )`: the argument values. */
        private fun callArguments(): List<Node> {
            expect(T.LPAREN)
            val arguments = ArrayList<Node>()
            while (peek() != T.RPAREN) {
                when {
                    peek() == T.IDENTIFIER && peek(1) == T.ASSIGN -> {
                        advance()
                        advance()
                    }
                    peek() == T.MUL || peek() == T.POW -> advance()
                }
                arguments += parseCondExpr()
                if (peek() == T.COMMA) advance() else break
            }
            expect(T.RPAREN)
            return arguments
        }

        /** Filters (`| name(args)`), tests (`is [not] name args`) and calls after a primary. */
        private fun parseFilterExpression(base: Node): Node {
            var node = base
            while (true) {
                node = when (peek()) {
                    T.PIPE -> {
                        advance()
                        val name = dottedName(T.FILTER_NAME)
                        val arguments = if (peek() == T.LPAREN) callArguments() else emptyList()
                        Filter(node.from, position, node, name, arguments)
                    }
                    T.IS_KEYWORD -> {
                        advance()
                        val negated = peek() == T.NOT_KEYWORD
                        if (negated) advance()
                        val name = dottedName(T.TEST_NAME)
                        val arguments = when {
                            peek() == T.LPAREN -> callArguments()
                            peek() in TEST_ARGUMENT_STARTS -> listOf(parsePostfix(parsePrimary()))
                            else -> emptyList()
                        }
                        Test(node.from, position, node, name, negated, arguments)
                    }
                    T.LPAREN -> {
                        val arguments = callArguments()
                        Access(node.from, position, node, call = true, parts = arguments)
                    }
                    else -> return node
                }
            }
        }

        /** A filter or test name (`bool`, `ansible.builtin.bool`); null for a qualified one. */
        private fun dottedName(type: IElementType): String? {
            if (peek() != type) throw ParseFailure()
            val first = tokenText(position)
            advance()
            var qualified = false
            while (peek() == T.DOT && peek(1) == type) {
                qualified = true
                advance()
                advance()
            }
            return if (qualified) null else first
        }
    }

    private val COMPARISONS: Set<IElementType> = setOf(T.EQEQ, T.NE, T.LT, T.LE, T.GT, T.GE)

    private val ARITHMETIC: List<Set<IElementType>> = listOf(
        setOf(T.PLUS, T.MINUS),
        setOf(T.TILDE),
        setOf(T.MUL, T.DIV, T.FLOORDIV, T.MOD),
        setOf(T.POW),
    )

    /** Tokens that start the single argument of a test written without parentheses (`is sameas none`). */
    private val TEST_ARGUMENT_STARTS: Set<IElementType> = setOf(
        T.IDENTIFIER, T.STRING, T.INTEGER, T.FLOAT, T.TRUE_KEYWORD, T.FALSE_KEYWORD, T.NONE_KEYWORD, T.LBRACKET, T.LBRACE,
    )
}
