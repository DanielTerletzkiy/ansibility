package de.terletzkiy.ansibility.semantics.typeflow

import de.terletzkiy.ansibility.semantics.value.PyValue
import java.math.BigInteger

/**
 * A port of the expression part of `jinja2/parser.py` (3.1): `parse_tuple` → `parse_condexpr` → `parse_or` →
 * `parse_and` → `parse_not` → `parse_compare` → `parse_math1` → `parse_concat` → `parse_math2` → `parse_pow` →
 * `parse_unary` → `parse_primary` + `parse_postfix` + `parse_filter_expr`.
 *
 * It reads the tokens of one `{{ … }}` tag (delimiters excluded). Anything outside the supported grammar (and every
 * syntax error) makes [parse] return null, which the evaluator treats as an unknown value.
 */
internal class JinjaExprParser private constructor(private val tokens: List<JinjaToken>) {
    private var pos = 0

    private class SyntaxError : RuntimeException(null, null, false, false)

    private fun peek(offset: Int = 0): JinjaToken? = tokens.getOrNull(pos + offset)

    private fun next(): JinjaToken = tokens.getOrNull(pos++) ?: throw SyntaxError()

    private fun isOp(text: String, offset: Int = 0): Boolean = peek(offset)?.let { it.kind == JinjaTokenKind.OPERATOR && it.text == text } == true

    private fun isKeyword(text: String, offset: Int = 0): Boolean = peek(offset)?.let { it.kind == JinjaTokenKind.KEYWORD && it.text == text } == true

    private fun skipOp(text: String): Boolean = isOp(text).also { if (it) pos++ }

    private fun skipKeyword(text: String): Boolean = isKeyword(text).also { if (it) pos++ }

    private fun expectOp(text: String): JinjaToken {
        if (!isOp(text)) throw SyntaxError()
        return next()
    }

    private val lastEnd: Int get() = tokens[pos - 1].end

    /** `parse_tuple(with_condexpr=True)` up to the end of the tokens: what an output tag holds. */
    private fun topLevel(): JinjaExpr {
        val first = condExpr()
        if (!isOp(",")) return first
        val items = arrayListOf(first)
        while (skipOp(",")) {
            if (peek() == null) break
            items += condExpr()
        }
        return JinjaExpr.TupleLiteral(items, first.start, lastEnd)
    }

    private fun condExpr(): JinjaExpr {
        var expr = or()
        while (skipKeyword("if")) {
            val condition = or()
            val otherwise = if (skipKeyword("else")) condExpr() else null
            expr = JinjaExpr.Conditional(expr, condition, otherwise, expr.start, lastEnd)
        }
        return expr
    }

    private fun or(): JinjaExpr {
        var left = and()
        while (skipKeyword("or")) {
            val right = and()
            left = JinjaExpr.Binary("or", left, right, left.start, right.end)
        }
        return left
    }

    private fun and(): JinjaExpr {
        var left = not()
        while (skipKeyword("and")) {
            val right = not()
            left = JinjaExpr.Binary("and", left, right, left.start, right.end)
        }
        return left
    }

    private fun not(): JinjaExpr {
        if (isKeyword("not")) {
            val start = next().start
            val operand = not()
            return JinjaExpr.Unary("not", operand, start, operand.end)
        }
        return compare()
    }

    private fun compare(): JinjaExpr {
        var left = math1()
        while (true) {
            val operator = when {
                peek()?.kind == JinjaTokenKind.OPERATOR && peek()!!.text in COMPARE_OPERATORS -> next().text
                skipKeyword("in") -> "in"
                isKeyword("not") && isKeyword("in", 1) -> {
                    pos += 2
                    "not in"
                }
                else -> return left
            }
            val right = math1()
            left = JinjaExpr.Binary(operator, left, right, left.start, right.end)
        }
    }

    private fun math1(): JinjaExpr {
        var left = concat()
        while (isOp("+") || isOp("-")) {
            val operator = next().text
            val right = concat()
            left = JinjaExpr.Binary(operator, left, right, left.start, right.end)
        }
        return left
    }

    private fun concat(): JinjaExpr {
        val first = math2()
        if (!isOp("~")) return first
        val parts = arrayListOf(first)
        while (skipOp("~")) parts += math2()
        return JinjaExpr.Concat(parts, first.start, parts.last().end)
    }

    private fun math2(): JinjaExpr {
        var left = pow()
        while (isOp("*") || isOp("/") || isOp("//") || isOp("%")) {
            val operator = next().text
            val right = pow()
            left = JinjaExpr.Binary(operator, left, right, left.start, right.end)
        }
        return left
    }

    private fun pow(): JinjaExpr {
        var left = unary(withFilter = true)
        while (skipOp("**")) {
            val right = unary(withFilter = true)
            left = JinjaExpr.Binary("**", left, right, left.start, right.end)
        }
        return left
    }

    private fun unary(withFilter: Boolean): JinjaExpr {
        var node = if (isOp("-") || isOp("+")) {
            val token = next()
            val operand = unary(withFilter = false)
            JinjaExpr.Unary(token.text, operand, token.start, operand.end)
        } else {
            primary()
        }
        node = postfix(node)
        if (withFilter) node = filterExpr(node)
        return node
    }

    private fun primary(): JinjaExpr {
        val token = next()
        return when (token.kind) {
            JinjaTokenKind.NAME -> JinjaExpr.Name(token.text, token.start, token.end)
            JinjaTokenKind.KEYWORD -> when (token.text) {
                "true", "True" -> JinjaExpr.Const(PyValue.Bool(true), token.start, token.end)
                "false", "False" -> JinjaExpr.Const(PyValue.Bool(false), token.start, token.end)
                "none", "None" -> JinjaExpr.Const(PyValue.None, token.start, token.end)
                else -> throw SyntaxError()
            }
            JinjaTokenKind.STRING -> {
                // Adjacent string literals concatenate, as in Python.
                val text = StringBuilder(decodeString(token.text))
                var end = token.end
                while (peek()?.kind == JinjaTokenKind.STRING) {
                    val more = next()
                    text.append(decodeString(more.text))
                    end = more.end
                }
                JinjaExpr.Const(PyValue.Str(text.toString()), token.start, end)
            }
            JinjaTokenKind.INTEGER -> JinjaExpr.Const(PyValue.Int(parseInteger(token.text)), token.start, token.end)
            JinjaTokenKind.FLOAT -> JinjaExpr.Const(PyValue.Float(parseFloat(token.text)), token.start, token.end)
            JinjaTokenKind.OPERATOR -> when (token.text) {
                "(" -> parenthesised(token)
                "[" -> {
                    val items = items("]")
                    JinjaExpr.ListLiteral(items, token.start, lastEnd)
                }
                "{" -> dict(token)
                else -> throw SyntaxError()
            }
            else -> throw SyntaxError()
        }
    }

    /** `parse_tuple(explicit_parentheses=True)`: `()` is an empty tuple, `(a)` is `a`, `(a,)` and `(a, b)` tuples. */
    private fun parenthesised(open: JinjaToken): JinjaExpr {
        if (skipOp(")")) return JinjaExpr.TupleLiteral(emptyList(), open.start, lastEnd)
        val first = condExpr()
        if (skipOp(")")) return first
        val items = arrayListOf(first)
        while (skipOp(",")) {
            if (isOp(")")) break
            items += condExpr()
        }
        expectOp(")")
        return JinjaExpr.TupleLiteral(items, open.start, lastEnd)
    }

    /** Comma-separated expressions up to [close] (a trailing comma is allowed). */
    private fun items(close: String): List<JinjaExpr> {
        val items = ArrayList<JinjaExpr>()
        while (!isOp(close)) {
            if (items.isNotEmpty()) {
                expectOp(",")
                if (isOp(close)) break
            }
            items += condExpr()
        }
        expectOp(close)
        return items
    }

    private fun dict(open: JinjaToken): JinjaExpr {
        val entries = ArrayList<Pair<JinjaExpr, JinjaExpr>>()
        while (!isOp("}")) {
            if (entries.isNotEmpty()) {
                expectOp(",")
                if (isOp("}")) break
            }
            val key = condExpr()
            expectOp(":")
            entries += key to condExpr()
        }
        expectOp("}")
        return JinjaExpr.DictLiteral(entries, open.start, lastEnd)
    }

    private fun postfix(start: JinjaExpr): JinjaExpr {
        var node = start
        while (true) {
            node = when {
                isOp(".") -> {
                    pos++
                    val attribute = next()
                    when (attribute.kind) {
                        JinjaTokenKind.NAME -> JinjaExpr.Attribute(node, attribute.text, node.start, attribute.end)
                        JinjaTokenKind.INTEGER -> JinjaExpr.Subscript(
                            node, JinjaExpr.Const(PyValue.Int(parseInteger(attribute.text)), attribute.start, attribute.end), node.start, attribute.end,
                        )
                        else -> throw SyntaxError()
                    }
                }
                isOp("[") -> subscript(node)
                isOp("(") -> call(node)
                else -> return node
            }
        }
    }

    /**
     * `parse_subscript` for `x[…]`: one subscribed expression is the key; a slice (`x[1:]`, `x[::2]`) or several
     * comma-separated subscripts (a tuple key) have no single key ([JinjaExpr.Subscript.key] is null).
     */
    private fun subscript(target: JinjaExpr): JinjaExpr {
        expectOp("[")
        val keys = ArrayList<JinjaExpr?>()
        while (!isOp("]")) {
            if (keys.isNotEmpty()) expectOp(",")
            keys += subscribed()
        }
        expectOp("]")
        return JinjaExpr.Subscript(target, keys.singleOrNull(), target.start, lastEnd)
    }

    /** `parse_subscribed`: an expression, or null for a slice `[start]:[stop][:[step]]`. */
    private fun subscribed(): JinjaExpr? {
        if (!isOp(":")) {
            val node = condExpr()
            if (!isOp(":")) return node
        }
        expectOp(":")
        if (!isOp(":") && !isOp("]") && !isOp(",")) condExpr()
        if (skipOp(":")) {
            if (!isOp("]") && !isOp(",")) condExpr()
        }
        return null
    }

    private fun call(callee: JinjaExpr): JinjaExpr {
        val (args, kwargs) = callArgs()
        return JinjaExpr.Call(callee, args, kwargs, callee.start, lastEnd)
    }

    /** `parse_call_args`: positional and keyword arguments; `*args`/`**kwargs` are read and dropped. */
    private fun callArgs(): Pair<List<JinjaExpr>, Map<String, JinjaExpr>> {
        expectOp("(")
        val args = ArrayList<JinjaExpr>()
        val kwargs = LinkedHashMap<String, JinjaExpr>()
        var first = true
        while (!isOp(")")) {
            if (!first) {
                expectOp(",")
                if (isOp(")")) break
            }
            first = false
            when {
                isOp("*") || isOp("**") -> {
                    pos++
                    condExpr()
                }
                peek()?.kind == JinjaTokenKind.NAME && isOp("=", 1) -> {
                    val name = next().text
                    pos++
                    kwargs[name] = condExpr()
                }
                else -> args += condExpr()
            }
        }
        expectOp(")")
        return args to kwargs
    }

    private fun filterExpr(start: JinjaExpr): JinjaExpr {
        var node = start
        while (true) {
            node = when {
                isOp("|") -> filter(node)
                isKeyword("is") -> test(node)
                isOp("(") -> call(node)
                else -> return node
            }
        }
    }

    private fun filter(target: JinjaExpr): JinjaExpr {
        expectOp("|")
        val name = dottedName()
        if (!isOp("(")) return JinjaExpr.Filter(target, name, emptyList(), emptyMap(), target.start, lastEnd)
        val (args, kwargs) = callArgs()
        return JinjaExpr.Filter(target, name, args, kwargs, target.start, lastEnd)
    }

    private fun test(target: JinjaExpr): JinjaExpr {
        if (!skipKeyword("is")) throw SyntaxError()
        val negated = skipKeyword("not")
        val name = dottedName()
        val next = peek()
        when {
            isOp("(") -> callArgs()
            next != null && startsTestArgument(next) -> postfix(primary())
        }
        return JinjaExpr.Test(target, name, negated, target.start, lastEnd)
    }

    /** `x is divisibleby 3`: a test takes one argument without parentheses when it is a primary. */
    private fun startsTestArgument(token: JinjaToken): Boolean = when (token.kind) {
        JinjaTokenKind.NAME, JinjaTokenKind.STRING, JinjaTokenKind.INTEGER, JinjaTokenKind.FLOAT -> true
        JinjaTokenKind.OPERATOR -> token.text == "[" || token.text == "{"
        JinjaTokenKind.KEYWORD -> token.text in setOf("true", "True", "false", "False", "none", "None")
        else -> false
    }

    /** A filter or test name, FQCN segments joined with dots. */
    private fun dottedName(): String {
        val first = next()
        if (first.kind != JinjaTokenKind.NAME) throw SyntaxError()
        val name = StringBuilder(first.text)
        while (isOp(".") && peek(1)?.kind == JinjaTokenKind.NAME) {
            pos++
            name.append('.').append(next().text)
        }
        return name.toString()
    }

    companion object {
        private val COMPARE_OPERATORS = setOf("==", "!=", "<", ">", "<=", ">=")

        /** The expression of one output tag ([tokens] without delimiters), or null when it is not supported or invalid. */
        fun parse(tokens: List<JinjaToken>): JinjaExpr? {
            if (tokens.isEmpty() || tokens.any { it.kind == JinjaTokenKind.BAD }) return null
            val parser = JinjaExprParser(tokens)
            return try {
                val expr = parser.topLevel()
                if (parser.pos != tokens.size) null else expr
            } catch (e: SyntaxError) {
                null
            } catch (e: NumberFormatException) {
                null
            }
        }

        /** Jinja's integer value: `int(text.replace("_", ""), 0)`. */
        internal fun parseInteger(text: String): BigInteger {
            val digits = text.replace("_", "").lowercase()
            return when {
                digits.startsWith("0x") -> BigInteger(digits.substring(2), 16)
                digits.startsWith("0o") -> BigInteger(digits.substring(2), 8)
                digits.startsWith("0b") -> BigInteger(digits.substring(2), 2)
                else -> BigInteger(digits)
            }
        }

        /** Jinja's float value: `float(text.replace("_", ""))`. */
        internal fun parseFloat(text: String): Double = text.replace("_", "").toDouble()

        /**
         * Jinja's string value: the text between the quotes with Python's `unicode-escape` decoding (`\n`, `\t`,
         * `\\`, `\'`, `\"`, `\xNN`, `\uNNNN`, `\UNNNNNNNN`, octal escapes, an escaped line break); other escapes keep
         * their backslash. An unterminated literal is a syntax error.
         */
        internal fun decodeString(literal: String): String {
            if (literal.length < 2 || literal.last() != literal.first()) throw SyntaxError()
            val body = literal.substring(1, literal.length - 1).replace("\r\n", "\n").replace('\r', '\n')
            val out = StringBuilder(body.length)
            var i = 0
            while (i < body.length) {
                val c = body[i++]
                if (c != '\\' || i >= body.length) {
                    out.append(c)
                    continue
                }
                when (val e = body[i++]) {
                    '\n' -> Unit
                    '\\', '\'', '"' -> out.append(e)
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'a' -> out.append('\u0007')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'v' -> out.append('\u000b')
                    'x', 'u', 'U' -> {
                        val width = when (e) {
                            'x' -> 2
                            'u' -> 4
                            else -> 8
                        }
                        val hex = body.substring(i, minOf(body.length, i + width))
                        if (hex.length != width || hex.any { Character.digit(it, 16) < 0 }) throw SyntaxError()
                        out.appendCodePoint(hex.toInt(16))
                        i += width
                    }
                    in '0'..'7' -> {
                        var value = e - '0'
                        var count = 1
                        while (count < 3 && i < body.length && body[i] in '0'..'7') {
                            value = value * 8 + (body[i++] - '0')
                            count++
                        }
                        out.appendCodePoint(value)
                    }
                    else -> out.append('\\').append(e)
                }
            }
            return out.toString()
        }
    }
}
