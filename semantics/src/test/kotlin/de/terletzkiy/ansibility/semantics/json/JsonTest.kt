package de.terletzkiy.ansibility.semantics.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.StringReader
import java.math.BigInteger

class JsonTest {
    private fun both(text: String): Any? {
        val fromText = Json.parse(text)
        val fromReader = Json.parse(StringReader(text))
        assertEquals(fromText, fromReader, "text and reader sources must agree")
        return fromText
    }

    @Test
    fun `parses scalars`() {
        assertNull(both("null"))
        assertEquals(true, both("true"))
        assertEquals(false, both(" false "))
        assertEquals(0L, both("0"))
        assertEquals(-12L, both("-12"))
        assertEquals(1.5, both("1.5"))
        assertEquals(1000.0, both("1e3"))
        assertEquals(-2.5e-3, both("-2.5E-3"))
        assertEquals("", both("\"\""))
    }

    @Test
    fun `integers that do not fit a Long become BigInteger`() {
        assertEquals(Long.MAX_VALUE, both(Long.MAX_VALUE.toString()))
        assertEquals(BigInteger("92233720368547758080"), both("92233720368547758080"))
        assertEquals(BigInteger("-92233720368547758090"), both("-92233720368547758090"))
    }

    @Test
    fun `decodes all escapes`() {
        assertEquals("\"\\/\b\u000C\n\r\t", both("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\""))
        assertEquals("é€", both("\"\\u00e9\\u20AC\""))
        assertEquals("\uD83D\uDE00", both("\"\\ud83d\\ude00\""), "surrogate pairs pass through")
        assertEquals("ünïcödé", both("\"ünïcödé\""))
    }

    @Test
    fun `objects keep insertion order and the last duplicate`() {
        @Suppress("UNCHECKED_CAST")
        val map = both("""{"b": 1, "a": [true, null, {"x": "y"}], "b": 2}""") as Map<String, Any?>
        assertEquals(listOf("b", "a"), map.keys.toList())
        assertEquals(2L, map["b"])
        assertEquals(listOf(true, null, mapOf("x" to "y")), map["a"])
        assertEquals(emptyMap<String, Any?>(), both("{ }"))
        assertEquals(emptyList<Any?>(), both("[\n]"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "  ", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "[01]", "-", "1.", ".5", "1e", "+1", "NaN", "Infinity",
            "tru", "nul", "\"abc", "\"a\tb\"", "\"\\x\"", "\"\\u12\"", "{\"a\" 1}", "[1 2]", "1 2", "// c\n1",
            "{\"a\":1} x", "\uFEFF{}", "{1:2}",
        ],
    )
    fun `rejects invalid documents`(text: String) {
        assertThrows<JsonParseException> { Json.parse(text) }
        assertThrows<JsonParseException> { Json.parse(StringReader(text)) }
    }

    @Test
    fun `parseObject requires an object`() {
        assertEquals(mapOf("a" to 1L), Json.parseObject("{\"a\":1}"))
        assertThrows<JsonParseException> { Json.parseObject("[1]") }
        assertThrows<JsonParseException> { Json.parseObject(StringReader("null")) }
    }

    @Test
    fun `errors carry line and column`() {
        val error = assertThrows<JsonParseException> { Json.parse("{\n  \"a\": [1,\n  ]\n}") }
        assertEquals(3, error.line)
        assertEquals(3, error.column)
        assertTrue(error.message!!.contains("line 3"))
    }

    @Test
    fun `rejects excessive nesting`() {
        val deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1)
        assertThrows<JsonParseException> { Json.parse(deep) }
        val ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        assertTrue(Json.parse(ok) is List<*>)
    }

    @Test
    fun `writes compact and pretty JSON`() {
        val value = linkedMapOf("name" to "x\"y\n", "n" to 3, "f" to 1.5, "l" to listOf(true, null), "e" to emptyMap<String, Any>())
        assertEquals("""{"name":"x\"y\n","n":3,"f":1.5,"l":[true,null],"e":{}}""", Json.write(value))
        assertEquals(
            """
            {
              "name": "x\"y\n",
              "n": 3,
              "f": 1.5,
              "l": [
                true,
                null
              ],
              "e": {}
            }
            """.trimIndent(),
            Json.write(value, pretty = true),
        )
        assertEquals("\"\\u0001\\u2028\"", Json.write("\u0001\u2028"))
        assertEquals("[1,2]", Json.write(intArrayOf(1, 2)))
    }

    @Test
    fun `writer rejects what JSON cannot hold`() {
        assertThrows<IllegalArgumentException> { Json.write(Double.NaN) }
        assertThrows<IllegalArgumentException> { Json.write(mapOf(1 to 2)) }
        assertThrows<IllegalArgumentException> { Json.write(Any()) }
    }

    @Test
    fun `round trips`() {
        val text = """{"a":[1,-2.5,"s\\u00e9",{"b":null,"c":false}],"big":123456789012345678901234567890}"""
        val parsed = Json.parse(text)
        assertEquals(parsed, Json.parse(Json.write(parsed)))
        assertEquals(parsed, Json.parse(Json.write(parsed, pretty = true)))
    }

    @Test
    fun `parses a multi-megabyte document from a reader`() {
        val item = """{"description":["Some text with C(code) and O(option=value)."],"type":"str","required":false}"""
        val text = buildString {
            append("{")
            for (i in 0 until 40_000) {
                if (i > 0) append(',')
                append("\"option_").append(i).append("\":").append(item)
            }
            append("}")
        }
        assertTrue(text.length > 4_000_000)
        val start = System.nanoTime()
        val parsed = Json.parseObject(StringReader(text))
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(40_000, parsed.size)
        assertTrue(millis < 5_000, "parsing took $millis ms")
        // Keys are de-duplicated while parsing.
        @Suppress("UNCHECKED_CAST")
        val keys = parsed.values.take(2).map { (it as Map<String, Any?>).keys.first() }
        assertTrue(keys[0] === keys[1])
    }
}
