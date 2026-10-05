package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.value.IndeterminateValueException
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyValue
import java.math.BigInteger

/**
 * `ast.literal_eval` as `check_type_dict` uses it: the fallback for strings starting with `{` that are not JSON.
 *
 * Covers the literal grammar (str/bytes literals with prefixes and implicit concatenation, int/float/imaginary
 * numbers, `True`/`False`/`None`/`...`, tuples, lists, sets, dicts, `set()`, unary `+`/`-` and complex `a ± bj`)
 * plus comments and line joining. Every other expression is rejected, as Python rejects it (a `SyntaxError` or
 * "malformed node" `ValueError`). A dict containing a tuple, set, bytes, complex or Ellipsis parses, but has no
 * [PyValue]; [evalDict] then throws [IndeterminateValueException].
 */
internal object PyLiteral {
    /**
     * `literal_eval(text)` when the result is a dict; raises `TypeError`/`ValueError`/`SyntaxError` for anything
     * Python would reject and `NotADict` (a `TypeError`) when the literal is valid but not a dict.
     */
    fun evalDict(text: String): PyValue.Dict {
        val tokens = Tokenizer(text.trimStart(' ', '\t')).tokens()
        val parser = Parser(tokens)
        val node = parser.expression()
        parser.expectEnd()
        val literal = convert(node)
        checkHashable(literal)
        if (literal !is Lit.LDict) throw PyException.typeError("literal is a ${literal.typeName}, not a dict")
        return toPyValue(literal) as PyValue.Dict
    }

    /**
     * `literal_eval(text)` of any literal; raises what Python raises, and [IndeterminateValueException] for a valid
     * literal [PyValue] cannot hold (a tuple, set, bytes …).
     */
    fun eval(text: String): PyValue {
        val tokens = Tokenizer(text.trimStart(' ', '\t')).tokens()
        val parser = Parser(tokens)
        val node = parser.expression()
        parser.expectEnd()
        val literal = convert(node)
        checkHashable(literal)
        return toPyValue(literal)
    }

    // ------------------------------------------------------------------------------------------ values

    private sealed interface Lit {
        val typeName: String

        data object LNone : Lit { override val typeName = "NoneType" }
        data class LBool(val value: Boolean) : Lit { override val typeName = "bool" }
        data class LInt(val value: BigInteger) : Lit { override val typeName = "int" }
        data class LFloat(val value: Double) : Lit { override val typeName = "float" }
        data class LComplex(val real: Double, val imag: Double) : Lit { override val typeName = "complex" }
        data class LStr(val value: String) : Lit { override val typeName = "str" }
        data class LBytes(val value: String) : Lit { override val typeName = "bytes" }
        data object LEllipsis : Lit { override val typeName = "ellipsis" }
        data class LTuple(val items: List<Lit>) : Lit { override val typeName = "tuple" }
        data class LList(val items: List<Lit>) : Lit { override val typeName = "list" }
        data class LSet(val items: List<Lit>) : Lit { override val typeName = "set" }
        data class LDict(val pairs: List<Pair<Lit, Lit>>) : Lit { override val typeName = "dict" }
    }

    private fun Lit.isHashable(): Boolean = when (this) {
        is Lit.LList, is Lit.LSet, is Lit.LDict -> false
        is Lit.LTuple -> items.all { it.isHashable() }
        else -> true
    }

    /** Building a set or dict hashes its elements/keys; an unhashable one is a `TypeError`. */
    private fun checkHashable(lit: Lit) {
        when (lit) {
            is Lit.LTuple -> lit.items.forEach(::checkHashable)
            is Lit.LList -> lit.items.forEach(::checkHashable)
            is Lit.LSet -> lit.items.forEach {
                checkHashable(it)
                if (!it.isHashable()) throw PyException.typeError("unhashable type: '${it.typeName}'")
            }
            is Lit.LDict -> lit.pairs.forEach { (k, v) ->
                checkHashable(k)
                if (!k.isHashable()) throw PyException.typeError("unhashable type: '${k.typeName}'")
                checkHashable(v)
            }
            else -> Unit
        }
    }

    private fun toPyValue(lit: Lit): PyValue = when (lit) {
        Lit.LNone -> PyValue.None
        is Lit.LBool -> PyValue.Bool(lit.value)
        is Lit.LInt -> PyValue.Int(lit.value)
        is Lit.LFloat -> PyValue.Float(lit.value)
        is Lit.LStr -> PyValue.Str(lit.value)
        is Lit.LList -> PyValue.List(lit.items.map(::toPyValue))
        is Lit.LDict -> PyValue.Dict.of(lit.pairs.map { (k, v) -> toPyValue(k) to toPyValue(v) })
        else -> throw IndeterminateValueException("contains a Python ${lit.typeName}, which YAML values never hold")
    }

    // ------------------------------------------------------------------------------------------ evaluation

    private sealed interface Node {
        data class Const(val value: Lit) : Node
        data class Name(val id: String) : Node
        data class Unary(val op: Char, val operand: Node) : Node
        data class Binary(val left: Node, val op: Char, val right: Node) : Node
        data class TupleNode(val items: List<Node>) : Node
        data class ListNode(val items: List<Node>) : Node
        data class SetNode(val items: List<Node>) : Node
        data class DictNode(val pairs: List<Pair<Node, Node>>) : Node
        data class Call(val function: Node, val argumentCount: Int) : Node
    }

    private fun malformed(): PyException = PyException.valueError("malformed node or string")

    /** `literal_eval`'s `_convert`. */
    private fun convert(node: Node): Lit = when (node) {
        is Node.Const -> node.value
        is Node.TupleNode -> Lit.LTuple(node.items.map(::convert))
        is Node.ListNode -> Lit.LList(node.items.map(::convert))
        is Node.SetNode -> Lit.LSet(node.items.map(::convert))
        is Node.Call -> if (node.function == Node.Name("set") && node.argumentCount == 0) Lit.LSet(emptyList()) else throw malformed()
        is Node.DictNode -> Lit.LDict(node.pairs.map { (k, v) -> convert(k) to convert(v) })
        is Node.Binary -> {
            val left = convertSignedNumber(node.left)
            val right = convertNumber(node.right)
            if (left !is Lit.LComplex && right is Lit.LComplex) {
                val real = (left as? Lit.LInt)?.value?.toDouble() ?: (left as Lit.LFloat).value
                if (node.op == '+') Lit.LComplex(real + right.real, right.imag) else Lit.LComplex(real - right.real, -right.imag)
            } else {
                throw malformed()
            }
        }
        else -> convertSignedNumber(node)
    }

    private fun convertSignedNumber(node: Node): Lit {
        if (node is Node.Unary) {
            return when (val operand = convertNumber(node.operand)) {
                is Lit.LInt -> if (node.op == '-') Lit.LInt(operand.value.negate()) else operand
                is Lit.LFloat -> if (node.op == '-') Lit.LFloat(-operand.value) else operand
                is Lit.LComplex -> if (node.op == '-') Lit.LComplex(-operand.real, -operand.imag) else operand
                else -> throw malformed()
            }
        }
        return convertNumber(node)
    }

    private fun convertNumber(node: Node): Lit {
        val value = (node as? Node.Const)?.value ?: throw malformed()
        if (value is Lit.LInt || value is Lit.LFloat || value is Lit.LComplex) return value
        throw malformed()
    }

    // ------------------------------------------------------------------------------------------ parsing

    private sealed interface Token {
        data class Str(val value: String, val bytes: Boolean, val formatted: Boolean) : Token
        data class Num(val value: Lit) : Token
        data class Name(val id: String) : Token
        data class Op(val text: String) : Token
        data object Newline : Token
        data object End : Token
    }

    private fun syntaxError(message: String) = PyException("SyntaxError", message)

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        private fun peek(): Token = tokens[pos]

        private fun next(): Token = tokens[pos++]

        private fun isOp(text: String): Boolean = (peek() as? Token.Op)?.text == text

        private fun expectOp(text: String) {
            if (!isOp(text)) throw syntaxError("expected '$text'")
            pos++
        }

        fun expectEnd() {
            while (peek() == Token.Newline) pos++
            if (peek() != Token.End) throw syntaxError("invalid syntax")
        }

        /** A sum of signed terms (the only binary operators literal_eval can accept). */
        fun expression(): Node {
            var left = unary()
            while (isOp("+") || isOp("-")) {
                val op = (next() as Token.Op).text[0]
                left = Node.Binary(left, op, unary())
            }
            return left
        }

        private fun unary(): Node {
            if (isOp("+") || isOp("-")) {
                val op = (next() as Token.Op).text[0]
                return Node.Unary(op, unary())
            }
            return primary()
        }

        private fun primary(): Node {
            var node = atom()
            while (isOp("(")) {
                pos++
                var count = 0
                while (!isOp(")")) {
                    expression()
                    count++
                    if (!isOp(",")) break
                    pos++
                }
                expectOp(")")
                node = Node.Call(node, count)
            }
            if (isOp("[") || isOp(".")) throw malformed() // subscript/attribute: never a literal
            return node
        }

        private fun atom(): Node = when (val token = next()) {
            is Token.Str -> strings(token)
            is Token.Num -> Node.Const(token.value)
            is Token.Name -> when (token.id) {
                "True" -> Node.Const(Lit.LBool(true))
                "False" -> Node.Const(Lit.LBool(false))
                "None" -> Node.Const(Lit.LNone)
                else -> Node.Name(token.id)
            }
            is Token.Op -> when (token.text) {
                "..." -> Node.Const(Lit.LEllipsis)
                "(" -> parenthesised()
                "[" -> Node.ListNode(items("]"))
                "{" -> braces()
                else -> throw syntaxError("invalid syntax")
            }
            else -> throw syntaxError("invalid syntax")
        }

        /** Adjacent string literals concatenate; mixing bytes and str is a SyntaxError, f-strings are malformed. */
        private fun strings(first: Token.Str): Node {
            val parts = mutableListOf(first)
            while (peek() is Token.Str) parts += next() as Token.Str
            if (parts.any { it.bytes } && !parts.all { it.bytes }) throw syntaxError("cannot mix bytes and nonbytes literals")
            if (parts.any { it.formatted }) throw malformed()
            val text = parts.joinToString("") { it.value }
            return Node.Const(if (first.bytes) Lit.LBytes(text) else Lit.LStr(text))
        }

        private fun parenthesised(): Node {
            if (isOp(")")) {
                pos++
                return Node.TupleNode(emptyList())
            }
            val first = expression()
            if (isOp(")")) {
                pos++
                return first
            }
            expectOp(",")
            val rest = items(")")
            return Node.TupleNode(listOf(first) + rest)
        }

        /** Comma-separated expressions up to [close] (a trailing comma is allowed); consumes [close]. */
        private fun items(close: String): List<Node> {
            val result = mutableListOf<Node>()
            while (!isOp(close)) {
                result += expression()
                if (!isOp(",")) break
                pos++
            }
            expectOp(close)
            return result
        }

        private fun braces(): Node {
            if (isOp("}")) {
                pos++
                return Node.DictNode(emptyList())
            }
            if (isOp("**") || isOp("*")) throw malformed()
            val first = expression()
            if (!isOp(":")) {
                val rest = if (isOp(",")) {
                    pos++
                    items("}")
                } else {
                    expectOp("}")
                    emptyList()
                }
                return Node.SetNode(listOf(first) + rest)
            }
            val pairs = mutableListOf<Pair<Node, Node>>()
            var key = first
            while (true) {
                expectOp(":")
                pairs += key to expression()
                if (isOp(",")) {
                    pos++
                    if (isOp("}")) break
                    if (isOp("**")) throw malformed()
                    key = expression()
                } else {
                    break
                }
            }
            expectOp("}")
            return Node.DictNode(pairs)
        }
    }

    private class Tokenizer(private val s: String) {
        private var i = 0
        private var depth = 0
        private val out = mutableListOf<Token>()

        fun tokens(): List<Token> {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == ' ' || c == '\t' || c == '\u000C' -> i++
                    c == '#' -> while (i < s.length && s[i] != '\n' && s[i] != '\r') i++
                    c == '\\' && (s.startsWith("\\\n", i) || s.startsWith("\\\r", i)) -> {
                        i += if (s.startsWith("\\\r\n", i)) 3 else 2
                    }
                    c == '\n' || c == '\r' -> {
                        i++
                        if (depth == 0) out += Token.Newline
                    }
                    isStringStart() -> out += string()
                    c in '0'..'9' || (c == '.' && i + 1 < s.length && s[i + 1] in '0'..'9') -> out += number()
                    c == '_' || Character.isLetter(c) -> out += name()
                    else -> out += operator()
                }
            }
            out += Token.End
            return out
        }

        private fun name(): Token {
            val start = i
            while (i < s.length && (s[i] == '_' || Character.isLetterOrDigit(s[i]))) i++
            return Token.Name(s.substring(start, i))
        }

        private fun operator(): Token {
            for (op in listOf("...", "**", "(", ")", "[", "]", "{", "}", ",", ":", "+", "-")) {
                if (s.startsWith(op, i)) {
                    when (op) {
                        "(", "[", "{" -> depth++
                        ")", "]", "}" -> depth = maxOf(0, depth - 1)
                    }
                    i += op.length
                    return Token.Op(op)
                }
            }
            // Any other character is an operator literal_eval never accepts.
            return Token.Op(s[i++].toString())
        }

        private fun isStringStart(): Boolean {
            var j = i
            while (j < s.length && j - i < 2 && s[j].lowercaseChar() in "rbuft") j++
            if (j >= s.length || (s[j] != '\'' && s[j] != '"')) return false
            val prefix = s.substring(i, j).lowercase()
            return prefix in VALID_PREFIXES
        }

        private fun string(): Token {
            val prefixEnd = generateSequence(i) { it + 1 }.first { s[it] == '\'' || s[it] == '"' }
            val prefix = s.substring(i, prefixEnd).lowercase()
            i = prefixEnd
            val quote = s[i]
            val triple = s.startsWith("$quote$quote$quote", i)
            i += if (triple) 3 else 1
            val raw = 'r' in prefix
            val bytes = 'b' in prefix
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw syntaxError("unterminated string literal")
                val c = s[i]
                if (triple && s.startsWith("$quote$quote$quote", i)) {
                    i += 3
                    break
                }
                if (!triple && c == quote) {
                    i++
                    break
                }
                if (!triple && (c == '\n' || c == '\r')) throw syntaxError("unterminated string literal")
                if (bytes && c.code > 0x7F) throw syntaxError("bytes can only contain ASCII literal characters")
                if (c == '\\') {
                    if (i + 1 >= s.length) throw syntaxError("unterminated string literal")
                    if (raw) {
                        out.append(c).append(s[i + 1])
                        i += 2
                    } else {
                        escape(out, bytes)
                    }
                    continue
                }
                out.append(c)
                i++
            }
            return Token.Str(out.toString(), bytes, 'f' in prefix || 't' in prefix)
        }

        /** One backslash escape of a non-raw literal, starting at the backslash. */
        private fun escape(out: StringBuilder, bytes: Boolean) {
            val e = s[i + 1]
            i += 2
            when (e) {
                '\n' -> Unit
                '\r' -> if (i < s.length && s[i] == '\n') i++
                '\\', '\'', '"' -> out.append(e)
                'a' -> out.append('\u0007')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'v' -> out.append('\u000B')
                in '0'..'7' -> {
                    var value = e - '0'
                    var count = 1
                    while (count < 3 && i < s.length && s[i] in '0'..'7') {
                        value = value * 8 + (s[i] - '0')
                        i++
                        count++
                    }
                    out.appendCodePoint(value)
                }
                'x' -> out.appendCodePoint(hexDigits(2, "truncated \\xXX escape"))
                'u' -> if (bytes) out.append("\\u") else out.appendCodePoint(hexDigits(4, "truncated \\uXXXX escape"))
                'U' -> if (bytes) {
                    out.append("\\U")
                } else {
                    val cp = hexDigits(8, "truncated \\UXXXXXXXX escape")
                    if (cp > 0x10FFFF) throw syntaxError("illegal Unicode character")
                    out.appendCodePoint(cp)
                }
                'N' -> if (bytes) out.append("\\N") else out.appendCodePoint(namedCharacter())
                else -> out.append('\\').append(e)
            }
        }

        private fun hexDigits(count: Int, message: String): Int {
            if (i + count > s.length) throw syntaxError(message)
            val text = s.substring(i, i + count)
            if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw syntaxError(message)
            i += count
            return text.toLong(16).toInt()
        }

        private fun namedCharacter(): Int {
            if (i >= s.length || s[i] != '{') throw syntaxError("malformed \\N character escape")
            val close = s.indexOf('}', i)
            if (close < 0) throw syntaxError("malformed \\N character escape")
            val name = s.substring(i + 1, close)
            i = close + 1
            return try {
                Character.codePointOf(name)
            } catch (e: IllegalArgumentException) {
                throw syntaxError("unknown Unicode character name")
            }
        }

        /** Python's number tokens: hex/octal/binary/decimal ints, point and exponent floats, imaginary numbers. */
        private fun number(): Token {
            val start = i
            if (s[i] == '0' && i + 1 < s.length && s[i + 1].lowercaseChar() in "xob") {
                val radix = when (s[i + 1].lowercaseChar()) {
                    'x' -> 16
                    'o' -> 8
                    else -> 2
                }
                i += 2
                val digits = StringBuilder()
                while (i < s.length && (s[i] == '_' || Character.digit(s[i], radix) >= 0)) {
                    if (s[i] == '_' && (i + 1 >= s.length || Character.digit(s[i + 1], radix) < 0)) throw invalidLiteral()
                    if (s[i] != '_') digits.append(s[i])
                    i++
                }
                if (digits.isEmpty() || (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_'))) throw invalidLiteral()
                return Token.Num(Lit.LInt(BigInteger(digits.toString(), radix)))
            }
            val intPart = digitRun()
            var isFloat = false
            var fraction = ""
            if (i < s.length && s[i] == '.') {
                isFloat = true
                i++
                fraction = digitRun()
            }
            var exponent = ""
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                val sign = if (i < s.length && (s[i] == '+' || s[i] == '-')) s[i++].toString() else ""
                val digits = digitRun()
                if (digits.isEmpty()) {
                    i = save
                    throw invalidLiteral()
                }
                exponent = "e$sign$digits"
                isFloat = true
            }
            val imaginary = i < s.length && (s[i] == 'j' || s[i] == 'J')
            if (imaginary) i++
            if (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) throw invalidLiteral()
            val text = s.substring(start, i)
            if (imaginary) return Token.Num(Lit.LComplex(0.0, (intPart + "." + fraction + exponent).toDouble()))
            if (isFloat) return Token.Num(Lit.LFloat(((intPart.ifEmpty { "0" }) + "." + fraction + exponent).toDouble()))
            if (intPart.length > 1 && intPart.startsWith("0") && intPart.any { it != '0' }) throw invalidLiteral()
            if (intPart.length > PyValue.INT_MAX_STR_DIGITS) throw PyException.valueError("Exceeds the limit for integer string conversion: $text")
            return Token.Num(Lit.LInt(BigInteger(intPart)))
        }

        /** Digits with single underscores between them; returns the digits without underscores. */
        private fun digitRun(): String {
            val out = StringBuilder()
            while (i < s.length && (s[i].isAsciiDigit() || s[i] == '_')) {
                if (s[i] == '_') {
                    if (out.isEmpty() || i + 1 >= s.length || !s[i + 1].isAsciiDigit()) throw invalidLiteral()
                } else {
                    out.append(s[i])
                }
                i++
            }
            return out.toString()
        }

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

        private fun invalidLiteral() = syntaxError("invalid decimal literal")

        companion object {
            private val VALID_PREFIXES = setOf("", "r", "u", "b", "br", "rb", "f", "fr", "rf", "t", "tr", "rt")
        }
    }
}
