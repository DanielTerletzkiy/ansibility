package de.terletzkiy.ansibility.semantics.json

import java.io.Reader
import java.io.Writer
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A small, strict JSON (RFC 8259) reader and writer without dependencies.
 *
 * Parsed values are plain Kotlin values:
 * - objects become [Map]`<String, Any?>` (insertion-ordered; a repeated key keeps its last value, as Python's `json` does),
 * - arrays become [List]`<Any?>`,
 * - strings become [String],
 * - integers become [Long], or [BigInteger] when they do not fit,
 * - numbers with a fraction or an exponent become [Double],
 * - `true`/`false` become [Boolean], and `null` becomes `null`.
 *
 * "Strict" means the grammar of RFC 8259 and nothing else: no comments, no trailing commas, no single quotes,
 * no `NaN`/`Infinity`, no leading zeros, no unescaped control characters, no byte-order mark and nothing but
 * whitespace after the value. Errors are reported as [JsonParseException] with line and column.
 *
 * The reader pulls characters through a fixed buffer, so it can parse straight from a (gzip) stream; object keys
 * are de-duplicated while parsing, which keeps multi-megabyte documentation snapshots compact in memory.
 */
object Json {
    /** Maximum nesting of arrays and objects accepted by the reader (documentation snapshots nest about 15 deep). */
    const val MAX_DEPTH: Int = 512

    /** Parses one JSON document from [text]. */
    fun parse(text: CharSequence): Any? = JsonReader(CharSequenceSource(text)).readDocument()

    /** Parses one JSON document from [reader]. The reader is consumed but not closed. */
    fun parse(reader: Reader): Any? = JsonReader(ReaderSource(reader)).readDocument()

    /** Serialises [value] as JSON text; [pretty] indents with two spaces like Python's `json.dumps(indent=2)`. */
    fun write(value: Any?, pretty: Boolean = false): String = StringBuilder().also { write(value, it, pretty) }.toString()

    /**
     * Serialises [value] to [out]. Supported values: `null`, [Boolean], [Number] (finite only), [CharSequence],
     * [Char], [Map] with [String] keys, [Iterable], [Array] and primitive arrays of numbers or booleans.
     *
     * @throws IllegalArgumentException for unsupported values, non-string keys and non-finite numbers.
     */
    fun write(value: Any?, out: Appendable, pretty: Boolean = false) {
        JsonWriter(out, pretty).writeValue(value, 0)
    }

    /** Serialises [value] to [writer] (not closed). */
    fun write(value: Any?, writer: Writer, pretty: Boolean = false) {
        write(value, writer as Appendable, pretty)
        writer.flush()
    }

    /** Parses [text] and returns it as an object, failing when the document is not a JSON object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: CharSequence): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonParseException("Expected a JSON object at the top level", 0, 1, 1)

    /** Reader overload of [parseObject]. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(reader: Reader): Map<String, Any?> =
        parse(reader) as? Map<String, Any?> ?: throw JsonParseException("Expected a JSON object at the top level", 0, 1, 1)
}

/** A syntax error in JSON input. [offset] is the zero-based character index; [line] and [column] are one-based. */
class JsonParseException(
    val reason: String,
    val offset: Long,
    val line: Int,
    val column: Int,
) : RuntimeException("$reason at line $line, column $column")

// ------------------------------------------------------------------------------------------------ reading

/** A character source with one character of look-ahead and position tracking. */
private abstract class CharSource {
    var offset: Long = 0
        private set
    var line: Int = 1
        private set
    var column: Int = 1
        private set

    /** The next character without consuming it, or -1 at the end of input. */
    abstract fun peek(): Int

    protected abstract fun advance()

    /** Consumes and returns the next character, or -1 at the end of input. */
    fun next(): Int {
        val c = peek()
        if (c < 0) return c
        advance()
        offset++
        if (c == '\n'.code) {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }
}

private class CharSequenceSource(private val text: CharSequence) : CharSource() {
    private var index = 0
    override fun peek(): Int = if (index < text.length) text[index].code else -1
    override fun advance() {
        index++
    }
}

private class ReaderSource(private val reader: Reader) : CharSource() {
    private val buffer = CharArray(16 * 1024)
    private var length = 0
    private var index = 0

    override fun peek(): Int {
        if (index >= length) {
            length = reader.read(buffer, 0, buffer.size)
            index = 0
            if (length <= 0) {
                length = 0
                return -1
            }
        }
        return buffer[index].code
    }

    override fun advance() {
        index++
    }
}

private class JsonReader(private val source: CharSource) {
    private val chars = StringBuilder()
    private val keys = HashMap<String, String>()

    fun readDocument(): Any? {
        if (source.peek() == 0xFEFF) fail("Unexpected byte-order mark")
        skipWhitespace()
        val value = readValue(0)
        skipWhitespace()
        if (source.peek() >= 0) fail("Unexpected ${describe(source.peek())} after the JSON value")
        return value
    }

    private fun readValue(depth: Int): Any? {
        return when (val c = source.peek()) {
            '{'.code -> readObject(depth + 1)
            '['.code -> readArray(depth + 1)
            '"'.code -> readString()
            't'.code -> readLiteral("true", true)
            'f'.code -> readLiteral("false", false)
            'n'.code -> readLiteral("null", null)
            else -> if (c == '-'.code || c in '0'.code..'9'.code) readNumber() else fail("Unexpected ${describe(c)}")
        }
    }

    private fun readObject(depth: Int): Map<String, Any?> {
        checkDepth(depth)
        source.next()
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (source.peek() == '}'.code) {
            source.next()
            return result
        }
        while (true) {
            skipWhitespace()
            if (source.peek() != '"'.code) fail("Expected a string key but found ${describe(source.peek())}")
            val raw = readString()
            val key = keys.getOrPut(raw) { raw }
            skipWhitespace()
            expect(':')
            skipWhitespace()
            result[key] = readValue(depth)
            skipWhitespace()
            when (source.peek()) {
                ','.code -> source.next()
                '}'.code -> {
                    source.next()
                    return result
                }
                else -> fail("Expected ',' or '}' but found ${describe(source.peek())}")
            }
        }
    }

    private fun readArray(depth: Int): List<Any?> {
        checkDepth(depth)
        source.next()
        val result = ArrayList<Any?>()
        skipWhitespace()
        if (source.peek() == ']'.code) {
            source.next()
            return result
        }
        while (true) {
            skipWhitespace()
            result += readValue(depth)
            skipWhitespace()
            when (source.peek()) {
                ','.code -> source.next()
                ']'.code -> {
                    source.next()
                    result.trimToSize()
                    return result
                }
                else -> fail("Expected ',' or ']' but found ${describe(source.peek())}")
            }
        }
    }

    private fun readString(): String {
        source.next() // opening quote
        chars.setLength(0)
        while (true) {
            val c = source.next()
            when {
                c < 0 -> fail("Unterminated string")
                c == '"'.code -> return chars.toString()
                c == '\\'.code -> readEscape()
                c < 0x20 -> fail("Unescaped control character U+%04X in string".format(c))
                else -> chars.append(c.toChar())
            }
        }
    }

    private fun readEscape() {
        when (val c = source.next()) {
            '"'.code -> chars.append('"')
            '\\'.code -> chars.append('\\')
            '/'.code -> chars.append('/')
            'b'.code -> chars.append('\b')
            'f'.code -> chars.append('\u000C')
            'n'.code -> chars.append('\n')
            'r'.code -> chars.append('\r')
            't'.code -> chars.append('\t')
            'u'.code -> {
                var code = 0
                repeat(4) {
                    val h = source.next()
                    val digit = Character.digit(h, 16)
                    if (h < 0 || digit < 0) fail("Invalid \\u escape")
                    code = code * 16 + digit
                }
                chars.append(code.toChar())
            }
            else -> fail("Invalid escape ${if (c < 0) "at end of input" else "\\" + c.toChar()}")
        }
    }

    private fun readNumber(): Number {
        chars.setLength(0)
        var integral = true
        if (source.peek() == '-'.code) chars.append(source.next().toChar())
        val first = source.peek()
        if (first !in '0'.code..'9'.code) fail("Expected a digit but found ${describe(first)}")
        if (first == '0'.code) {
            chars.append(source.next().toChar())
            if (source.peek() in '0'.code..'9'.code) fail("Leading zeros are not allowed")
        } else {
            readDigits()
        }
        if (source.peek() == '.'.code) {
            integral = false
            chars.append(source.next().toChar())
            if (source.peek() !in '0'.code..'9'.code) fail("Expected a digit after the decimal point")
            readDigits()
        }
        if (source.peek() == 'e'.code || source.peek() == 'E'.code) {
            integral = false
            chars.append(source.next().toChar())
            if (source.peek() == '+'.code || source.peek() == '-'.code) chars.append(source.next().toChar())
            if (source.peek() !in '0'.code..'9'.code) fail("Expected a digit in the exponent")
            readDigits()
        }
        val text = chars.toString()
        if (!integral) return text.toDouble()
        return text.toLongOrNull() ?: BigInteger(text)
    }

    private fun readDigits() {
        while (source.peek() in '0'.code..'9'.code) chars.append(source.next().toChar())
    }

    private fun readLiteral(word: String, value: Any?): Any? {
        for (expected in word) {
            val c = source.next()
            if (c != expected.code) fail("Invalid literal, expected '$word'")
        }
        return value
    }

    private fun skipWhitespace() {
        while (true) {
            when (source.peek()) {
                ' '.code, '\t'.code, '\n'.code, '\r'.code -> source.next()
                else -> return
            }
        }
    }

    private fun expect(char: Char) {
        val c = source.peek()
        if (c != char.code) fail("Expected '$char' but found ${describe(c)}")
        source.next()
    }

    private fun checkDepth(depth: Int) {
        if (depth > Json.MAX_DEPTH) fail("Nesting deeper than ${Json.MAX_DEPTH} levels")
    }

    private fun describe(c: Int): String = when {
        c < 0 -> "end of input"
        c < 0x20 || c == 0xFEFF -> "character U+%04X".format(c)
        else -> "'${c.toChar()}'"
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message, source.offset, source.line, source.column)
}

// ------------------------------------------------------------------------------------------------ writing

private class JsonWriter(private val out: Appendable, private val pretty: Boolean) {
    fun writeValue(value: Any?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is CharSequence -> writeString(value)
            is Char -> writeString(value.toString())
            is Number -> writeNumber(value)
            is Map<*, *> -> writeObject(value, depth)
            is Iterable<*> -> writeArray(value.iterator(), depth)
            is Array<*> -> writeArray(value.iterator(), depth)
            is IntArray -> writeArray(value.iterator(), depth)
            is LongArray -> writeArray(value.iterator(), depth)
            is DoubleArray -> writeArray(value.iterator(), depth)
            is BooleanArray -> writeArray(value.iterator(), depth)
            else -> throw IllegalArgumentException("Cannot serialise ${value::class.qualifiedName} as JSON")
        }
    }

    private fun writeObject(map: Map<*, *>, depth: Int) {
        if (map.isEmpty()) {
            out.append("{}")
            return
        }
        out.append('{')
        var first = true
        for ((key, value) in map) {
            require(key is String) { "JSON object keys must be strings, got ${key?.let { it::class.qualifiedName }}" }
            if (!first) out.append(',')
            first = false
            newline(depth + 1)
            writeString(key)
            out.append(if (pretty) ": " else ":")
            writeValue(value, depth + 1)
        }
        newline(depth)
        out.append('}')
    }

    private fun writeArray(items: Iterator<*>, depth: Int) {
        if (!items.hasNext()) {
            out.append("[]")
            return
        }
        out.append('[')
        var first = true
        for (item in items) {
            if (!first) out.append(',')
            first = false
            newline(depth + 1)
            writeValue(item, depth + 1)
        }
        newline(depth)
        out.append(']')
    }

    private fun newline(depth: Int) {
        if (!pretty) return
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    private fun writeNumber(number: Number) {
        when (number) {
            is Double -> {
                require(number.isFinite()) { "JSON cannot represent $number" }
                out.append(number.toString())
            }
            is Float -> {
                require(number.isFinite()) { "JSON cannot represent $number" }
                out.append(number.toDouble().toString())
            }
            is BigDecimal -> out.append(number.toString())
            is BigInteger, is Long, is Int, is Short, is Byte -> out.append(number.toString())
            else -> {
                val d = number.toDouble()
                require(d.isFinite()) { "JSON cannot represent $number" }
                out.append(number.toString())
            }
        }
    }

    private fun writeString(text: CharSequence) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' || c == ' ' || c == ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
