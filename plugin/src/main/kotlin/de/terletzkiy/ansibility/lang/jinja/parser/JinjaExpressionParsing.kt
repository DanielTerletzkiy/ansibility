package de.terletzkiy.ansibility.lang.jinja.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import de.terletzkiy.ansibility.lang.jinja.AnsibilityJinjaBundle
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaElementTypes as E
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.AnsibilityJinjaBundle"

/**
 * The expression grammar of `jinja2/parser.py` 3.1, ported one-to-one onto a [PsiBuilder]:
 *
 * ```
 * tuple      := expression (',' expression)* ','?          (parse_tuple)
 * expression := or ('if' or ('else' expression)?)*          (parse_condexpr)
 * or         := and ('or' and)*
 * and        := not ('and' not)*
 * not        := 'not' not | compare
 * compare    := math1 (('==' | '!=' | '<' | '<=' | '>' | '>=' | 'in' | 'not' 'in') math1)*
 * math1      := concat (('+' | '-') concat)*
 * concat     := math2 ('~' math2)*
 * math2      := pow (('*' | '/' | '//' | '%') pow)*
 * pow        := unary ('**' unary)*
 * unary      := ('-' | '+') unary-without-filters | primary postfix*  — then filters and tests: ('|' filter | 'is' test | call)*
 * postfix    := '.' (name | integer) | '[' subscript (',' subscript)* ']' | '(' arguments ')'
 * filter     := name ('.' name)* ('(' arguments ')')?
 * test       := 'not'? name ('.' name)* ('(' arguments ')' | primary postfix*)?
 * ```
 *
 * Keywords are contextual in Jinja; the lexer already classifies them by position, and the few clause keywords that
 * can also be plain names (`as`, `import`, `recursive`, `with`) are re-mapped to identifiers where a name is expected.
 *
 * Errors never cross a tag and never cascade: a parse method stops at the first token it cannot use, no operator
 * or postfix loop goes on after an error was reported inside it, and the template parser skips the rest of the tag up
 * to its end delimiter without a second error.
 */
internal open class JinjaExpressionParsing(protected val builder: PsiBuilder) {
    /** The number of errors reported so far; lets callers avoid a second error for the same problem. */
    protected var errorCount: Int = 0

    // ------------------------------------------------------------------------------------------------ tuples

    /**
     * `parse_tuple` without parentheses: one expression, or a tuple when a comma follows (wrapped in a
     * `TUPLE_EXPRESSION`). Stops before a tag end, `)` and [extraEnd]. Returns false when nothing was parsed.
     */
    fun parseTuple(withCondExpr: Boolean = true, extraEnd: TokenSet = TokenSet.EMPTY): Boolean {
        val tuple = builder.mark()
        val (count, comma) = parseTupleItems(withCondExpr, extraEnd)
        if (comma) tuple.done(E.TUPLE_EXPRESSION) else tuple.drop()
        return count > 0 || comma
    }

    /** [parseTuple], reporting "expression expected" when nothing was parsed and no other error was reported. */
    fun parseTupleOrError(withCondExpr: Boolean = true, extraEnd: TokenSet = TokenSet.EMPTY) {
        val before = errorCount
        if (!parseTuple(withCondExpr, extraEnd) && errorCount == before) error("parser.expected.expression")
    }

    /** The items of a tuple: returns their number and whether a comma was seen. */
    private fun parseTupleItems(withCondExpr: Boolean, extraEnd: TokenSet): Pair<Int, Boolean> {
        val before = errorCount
        var count = 0
        var comma = false
        while (!isTupleEnd(extraEnd)) {
            if (parseExpression(withCondExpr) == null) {
                error("parser.expected.expression")
                break
            }
            count++
            if (!at(T.COMMA) || errorCount != before) break
            advance()
            comma = true
        }
        return count to comma
    }

    /** Jinja's `is_tuple_end`: a tag end, `)` or one of [extraEnd]. */
    protected fun isTupleEnd(extraEnd: TokenSet = TokenSet.EMPTY): Boolean =
        builder.eof() || builder.tokenType in TUPLE_ENDS || builder.tokenType in extraEnd

    // ------------------------------------------------------------------------------------------------ expressions

    /** One expression (`parse_expression`); null when the current token cannot start one. */
    fun parseExpression(withCondExpr: Boolean = true): PsiBuilder.Marker? = if (withCondExpr) parseCondExpr() else parseOr()

    /** [parseExpression], reporting "expression expected" when nothing was parsed and no other error was reported. */
    fun parseExpressionOrError(withCondExpr: Boolean = true) {
        val before = errorCount
        if (parseExpression(withCondExpr) == null && errorCount == before) error("parser.expected.expression")
    }

    private fun parseCondExpr(): PsiBuilder.Marker? {
        val before = errorCount
        var left = parseOr() ?: return null
        while (at(T.IF_KEYWORD) && errorCount == before) {
            val conditional = left.precede()
            advance()
            requireOperand(parseOr())
            if (at(T.ELSE_KEYWORD)) {
                advance()
                requireOperand(parseCondExpr())
            }
            conditional.done(E.CONDITIONAL_EXPRESSION)
            left = conditional
        }
        return left
    }

    private fun parseOr(): PsiBuilder.Marker? = parseBinary(OR, ::parseAnd)

    private fun parseAnd(): PsiBuilder.Marker? = parseBinary(AND, ::parseNot)

    private fun parseNot(): PsiBuilder.Marker? {
        if (!at(T.NOT_KEYWORD)) return parseCompare()
        val not = builder.mark()
        advance()
        requireOperand(parseNot())
        not.done(E.UNARY_EXPRESSION)
        return not
    }

    private fun parseCompare(): PsiBuilder.Marker? {
        val before = errorCount
        val left = parseMath1() ?: return null
        var compare: PsiBuilder.Marker? = null
        while (errorCount == before) {
            when {
                builder.tokenType in COMPARE_OPERATORS || at(T.IN_KEYWORD) -> {
                    if (compare == null) compare = left.precede()
                    advance()
                }
                at(T.NOT_KEYWORD) && builder.lookAhead(1) == T.IN_KEYWORD -> {
                    if (compare == null) compare = left.precede()
                    advance()
                    advance()
                }
                else -> break
            }
            requireOperand(parseMath1())
        }
        compare?.done(E.COMPARE_EXPRESSION)
        return compare ?: left
    }

    private fun parseMath1(): PsiBuilder.Marker? = parseBinary(MATH1, ::parseConcat)

    private fun parseConcat(): PsiBuilder.Marker? = parseBinary(CONCAT, ::parseMath2)

    private fun parseMath2(): PsiBuilder.Marker? = parseBinary(MATH2, ::parsePow)

    private fun parsePow(): PsiBuilder.Marker? = parseBinary(POW, ::parseUnaryWithFilters)

    /** A left-associative chain of [operators] between [operand]s. */
    private inline fun parseBinary(operators: TokenSet, operand: () -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val before = errorCount
        var left = operand() ?: return null
        while (builder.tokenType in operators && errorCount == before) {
            val binary = left.precede()
            advance()
            requireOperand(operand())
            binary.done(E.BINARY_EXPRESSION)
            left = binary
        }
        return left
    }

    private fun parseUnaryWithFilters(): PsiBuilder.Marker? = parseUnary(withFilter = true)

    /** `parse_unary`: a sign applies to the unary expression after it without its filters; filters apply to the result. */
    private fun parseUnary(withFilter: Boolean): PsiBuilder.Marker? {
        val before = errorCount
        var node = if (at(T.MINUS) || at(T.PLUS)) {
            val unary = builder.mark()
            advance()
            requireOperand(parseUnary(withFilter = false))
            unary.done(E.UNARY_EXPRESSION)
            unary
        } else {
            parsePostfix(parsePrimary() ?: return null)
        }
        if (withFilter && errorCount == before) node = parseFilterExpr(node)
        return node
    }

    /** `parse_primary`: a name, a constant, a string run, a parenthesized expression or tuple, a list or a dict. */
    protected fun parsePrimary(): PsiBuilder.Marker? {
        val type = builder.tokenType ?: return null
        return when {
            type == T.IDENTIFIER || type in NAME_KEYWORDS -> {
                remapToIdentifier()
                leaf(E.VARIABLE_REFERENCE)
            }
            type in T.CONSTANTS || type in T.NUMBERS -> leaf(E.LITERAL)
            type == T.STRING -> {
                val literal = builder.mark()
                while (at(T.STRING)) advance()
                literal.done(E.LITERAL)
                literal
            }
            type == T.LPAREN -> parseParenthesized()
            type == T.LBRACKET -> parseList()
            type == T.LBRACE -> parseDict()
            else -> null
        }
    }

    private fun leaf(type: IElementType): PsiBuilder.Marker {
        val marker = builder.mark()
        advance()
        marker.done(type)
        return marker
    }

    /** `(` tuple `)`: a parenthesized expression, or a tuple when empty or a comma is present. */
    private fun parseParenthesized(): PsiBuilder.Marker {
        val marker = builder.mark()
        val before = errorCount
        advance()
        val (count, comma) = parseTupleItems(withCondExpr = true, extraEnd = TokenSet.EMPTY)
        close(T.RPAREN, ")", before)
        marker.done(if (comma || count == 0) E.TUPLE_EXPRESSION else E.PARENTHESIZED_EXPRESSION)
        return marker
    }

    private fun parseList(): PsiBuilder.Marker {
        val list = builder.mark()
        val before = errorCount
        advance()
        while (!at(T.RBRACKET) && !atTagEndOrEof()) {
            if (parseExpression() == null) {
                error("parser.expected.expression")
                break
            }
            if (!at(T.COMMA)) break
            advance()
        }
        close(T.RBRACKET, "]", before)
        list.done(E.LIST_EXPRESSION)
        return list
    }

    private fun parseDict(): PsiBuilder.Marker {
        val dict = builder.mark()
        val before = errorCount
        advance()
        while (!at(T.RBRACE) && !atTagEndOrEof()) {
            val entry = builder.mark()
            if (parseExpression() == null) {
                entry.drop()
                error("parser.expected.expression")
                break
            }
            if (expect(T.COLON, ":")) requireOperand(parseExpression())
            entry.done(E.DICT_ENTRY)
            if (!at(T.COMMA)) break
            advance()
        }
        close(T.RBRACE, "}", before)
        dict.done(E.DICT_EXPRESSION)
        return dict
    }

    // ------------------------------------------------------------------------------------------------ postfix

    /** `parse_postfix`: attribute access, subscripts and calls after a primary. */
    protected fun parsePostfix(start: PsiBuilder.Marker): PsiBuilder.Marker {
        val before = errorCount
        var node = start
        while (errorCount == before) {
            node = when (builder.tokenType) {
                T.DOT -> {
                    val access = node.precede()
                    advance()
                    if (at(T.IDENTIFIER) || at(T.INTEGER)) advance() else error("parser.expected.attribute")
                    access.done(E.MEMBER_ACCESS)
                    access
                }
                T.LBRACKET -> parseSubscript(node)
                T.LPAREN -> parseCall(node)
                else -> return node
            }
        }
        return node
    }

    private fun parseSubscript(qualifier: PsiBuilder.Marker): PsiBuilder.Marker {
        val subscription = qualifier.precede()
        val before = errorCount
        advance()
        val items = builder.mark()
        var comma = false
        while (!at(T.RBRACKET) && !atTagEndOrEof()) {
            if (!parseSubscribed()) {
                error("parser.expected.expression")
                break
            }
            if (!at(T.COMMA)) break
            advance()
            comma = true
        }
        if (comma) items.done(E.TUPLE_EXPRESSION) else items.drop()
        close(T.RBRACKET, "]", before)
        subscription.done(E.SUBSCRIPTION)
        return subscription
    }

    /** `parse_subscribed`: an expression or a slice `[start]:[stop][:[step]]`. */
    private fun parseSubscribed(): Boolean {
        val slice = builder.mark()
        if (!at(T.COLON)) {
            if (parseExpression() == null) {
                slice.drop()
                return false
            }
            if (!at(T.COLON)) {
                slice.drop()
                return true
            }
        }
        advance()
        if (!at(T.COLON) && !atSliceEnd()) requireOperand(parseExpression())
        if (at(T.COLON)) {
            advance()
            if (!atSliceEnd()) requireOperand(parseExpression())
        }
        slice.done(E.SLICE)
        return true
    }

    private fun atSliceEnd(): Boolean = at(T.RBRACKET) || at(T.COMMA) || atTagEndOrEof()

    protected fun parseCall(callee: PsiBuilder.Marker): PsiBuilder.Marker {
        val call = callee.precede()
        parseArgumentList()
        call.done(E.CALL_EXPRESSION)
        return call
    }

    /**
     * `parse_call_args` at `(`: positional arguments, `name=value`, `*args`, `**kwargs`, with Jinja's ordering rules
     * (no positional argument after a keyword or unpacked one, nothing after `**kwargs`).
     */
    protected fun parseArgumentList() {
        val list = builder.mark()
        val before = errorCount
        advance()
        var keywords = false
        var star = false
        var doubleStar = false
        var first = true
        while (!at(T.RPAREN) && !atTagEndOrEof()) {
            if (!first) {
                if (!expect(T.COMMA, ",")) break
                if (at(T.RPAREN)) break
            }
            first = false
            when {
                at(T.MUL) || at(T.POW) -> {
                    val double = at(T.POW)
                    if (doubleStar || (!double && star)) error("parser.invalid.arguments")
                    val argument = builder.mark()
                    advance()
                    requireOperand(parseExpression())
                    argument.done(if (double) E.DOUBLE_STAR_ARGUMENT else E.STAR_ARGUMENT)
                    if (double) doubleStar = true else star = true
                }
                atName() && builder.lookAhead(1) == T.ASSIGN -> {
                    if (doubleStar) error("parser.invalid.arguments")
                    val argument = builder.mark()
                    remapToIdentifier()
                    advance()
                    advance()
                    requireOperand(parseExpression())
                    argument.done(E.KEYWORD_ARGUMENT)
                    keywords = true
                }
                else -> {
                    if (keywords || star || doubleStar) error("parser.invalid.arguments")
                    if (parseExpression() == null) {
                        error("parser.expected.expression")
                        break
                    }
                }
            }
        }
        close(T.RPAREN, ")", before)
        list.done(E.ARGUMENT_LIST)
    }

    // ------------------------------------------------------------------------------------------------ filters, tests

    /** `parse_filter_expr`: filters, tests and calls after a unary expression. */
    private fun parseFilterExpr(start: PsiBuilder.Marker): PsiBuilder.Marker {
        val before = errorCount
        var node = start
        while (errorCount == before) {
            node = when (builder.tokenType) {
                T.PIPE -> parseFilters(node, startInline = false)!!
                T.IS_KEYWORD -> parseTest(node)
                T.LPAREN -> parseCall(node)
                else -> return node
            }
        }
        return node
    }

    /**
     * `parse_filter`: a chain of `| name(args)`. With [startInline] the first filter has no `|` (`{% filter f %}`);
     * with a null [operand] the first filter has no operand (filter blocks and block `set`). Returns the outermost
     * filter call, or [operand] when there was none.
     */
    protected fun parseFilters(operand: PsiBuilder.Marker?, startInline: Boolean): PsiBuilder.Marker? {
        var node = operand
        var inline = startInline
        while (at(T.PIPE) || inline) {
            val filter = node?.precede() ?: builder.mark()
            if (!inline) advance()
            inline = false
            if (!parseDottedName(T.FILTER_NAME, E.FILTER_REFERENCE)) error("parser.expected.filter.name")
            if (at(T.LPAREN)) parseArgumentList()
            filter.done(E.FILTER_CALL)
            node = filter
        }
        return node
    }

    /** `parse_test`: `is [not] name` with parenthesized arguments or one argument without parentheses. */
    private fun parseTest(operand: PsiBuilder.Marker): PsiBuilder.Marker {
        val test = operand.precede()
        advance()
        if (at(T.NOT_KEYWORD)) advance()
        if (!parseDottedName(T.TEST_NAME, E.TEST_REFERENCE)) error("parser.expected.test.name")
        when {
            at(T.LPAREN) -> parseArgumentList()
            builder.tokenType in TEST_ARGUMENT_STARTS -> parsePrimary()?.let(::parsePostfix)
            at(T.IS_KEYWORD) -> error("parser.chained.tests")
        }
        test.done(E.TEST_EXPRESSION)
        return test
    }

    /** `name ('.' name)*` of [segment] tokens, as a [nameType] node. */
    private fun parseDottedName(segment: IElementType, nameType: IElementType): Boolean {
        if (!at(segment)) return false
        val name = builder.mark()
        advance()
        while (at(T.DOT)) {
            advance()
            if (at(segment)) {
                advance()
            } else {
                error("parser.expected.name")
                break
            }
        }
        name.done(nameType)
        return true
    }

    // ------------------------------------------------------------------------------------------------ targets

    /**
     * `parse_assign_target` with tuples: a name, `(…)` of targets, or (with [withNamespace]) `ns.attr`; a comma makes
     * a `TARGET_TUPLE`. Stops before [extraEnd] (`in` of a `for`). Returns false when no target was found.
     */
    fun parseTargets(extraEnd: TokenSet = TokenSet.EMPTY, withNamespace: Boolean = false): Boolean {
        val tuple = builder.mark()
        var count = 0
        var comma = false
        while (!isTupleEnd(extraEnd)) {
            if (!parseTarget(withNamespace)) {
                if (count > 0) error("parser.expected.target")
                break
            }
            count++
            if (!at(T.COMMA)) break
            advance()
            comma = true
        }
        if (comma) tuple.done(E.TARGET_TUPLE) else tuple.drop()
        return count > 0
    }

    private fun parseTarget(withNamespace: Boolean): Boolean {
        when {
            atName() -> {
                val target = builder.mark()
                remapToIdentifier()
                advance()
                if (withNamespace && at(T.DOT)) {
                    advance()
                    if (at(T.IDENTIFIER)) advance() else error("parser.expected.attribute")
                    target.done(E.NAMESPACE_TARGET)
                } else {
                    target.done(E.TARGET_NAME)
                }
                return true
            }
            at(T.LPAREN) -> {
                val tuple = builder.mark()
                val before = errorCount
                advance()
                var first = true
                while (!at(T.RPAREN) && !atTagEndOrEof()) {
                    if (!first && !expect(T.COMMA, ",")) break
                    if (at(T.RPAREN)) break
                    first = false
                    if (!parseTarget(withNamespace = false)) {
                        error("parser.expected.target")
                        break
                    }
                }
                close(T.RPAREN, ")", before)
                tuple.done(E.TARGET_TUPLE)
                return true
            }
            else -> return false
        }
    }

    /** A single name being bound (`name_only` targets: macro name, parameters, aliases). */
    fun parseTargetName(): Boolean {
        if (!atName()) return false
        val target = builder.mark()
        remapToIdentifier()
        advance()
        target.done(E.TARGET_NAME)
        return true
    }

    // ------------------------------------------------------------------------------------------------ helpers

    protected fun at(type: IElementType): Boolean = builder.tokenType === type

    /** An identifier, or a clause keyword that is a plain name in this position. */
    protected fun atName(): Boolean = builder.tokenType == T.IDENTIFIER || builder.tokenType in NAME_KEYWORDS

    protected fun atTagEndOrEof(): Boolean = builder.eof() || at(T.VAR_END) || at(T.BLOCK_END)

    protected fun advance() {
        builder.advanceLexer()
    }

    /** Re-maps a clause keyword used as a plain name (`as`, `import`, `recursive`, `with`) to an identifier. */
    protected fun remapToIdentifier() {
        if (builder.tokenType in NAME_KEYWORDS) builder.remapCurrentToken(T.IDENTIFIER)
    }

    /** Consumes [type] or reports "'[text]' expected". */
    protected fun expect(type: IElementType, text: String): Boolean {
        if (at(type)) {
            advance()
            return true
        }
        error("parser.expected.token", text)
        return false
    }

    /**
     * Consumes the closing [type] of a bracketed construct; reports "'[text]' expected" only when no error was reported
     * inside the construct (one mistake, one error).
     */
    protected fun close(type: IElementType, text: String, errorsBefore: Int) {
        if (at(type)) advance() else if (errorCount == errorsBefore) error("parser.expected.token", text)
    }

    /** Reports "expression expected" when an operator has no operand. */
    private fun requireOperand(operand: PsiBuilder.Marker?) {
        if (operand == null) error("parser.expected.expression")
    }

    /** Reports an error at the current token. */
    protected fun error(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) {
        builder.error(AnsibilityJinjaBundle.message(key, *params))
        errorCount++
    }

    /** Marks [marker] as an error element. */
    protected fun errorElement(marker: PsiBuilder.Marker, @PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) {
        marker.error(AnsibilityJinjaBundle.message(key, *params))
        errorCount++
    }

    protected companion object {
        val TUPLE_ENDS: TokenSet = TokenSet.create(T.VAR_END, T.BLOCK_END, T.RPAREN)
        val OR: TokenSet = TokenSet.create(T.OR_KEYWORD)
        val AND: TokenSet = TokenSet.create(T.AND_KEYWORD)
        val COMPARE_OPERATORS: TokenSet = TokenSet.create(T.EQEQ, T.NE, T.LT, T.LE, T.GT, T.GE)
        val MATH1: TokenSet = TokenSet.create(T.PLUS, T.MINUS)
        val CONCAT: TokenSet = TokenSet.create(T.TILDE)
        val MATH2: TokenSet = TokenSet.create(T.MUL, T.DIV, T.FLOORDIV, T.MOD)
        val POW: TokenSet = TokenSet.create(T.POW)

        /** Clause keywords Jinja reads as plain names wherever a name is expected. */
        val NAME_KEYWORDS: TokenSet = TokenSet.create(T.AS_KEYWORD, T.IMPORT_KEYWORD, T.RECURSIVE_KEYWORD, T.WITH_KEYWORD)

        /** Tokens that start the single argument of a test without parentheses (`divisibleby 3`, `sameas none`). */
        val TEST_ARGUMENT_STARTS: TokenSet = TokenSet.create(
            T.IDENTIFIER, T.STRING, T.INTEGER, T.FLOAT, T.LBRACKET, T.LBRACE, T.TRUE_KEYWORD, T.FALSE_KEYWORD, T.NONE_KEYWORD,
        )
    }
}
