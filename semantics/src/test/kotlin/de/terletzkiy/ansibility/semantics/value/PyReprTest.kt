package de.terletzkiy.ansibility.semantics.value

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigInteger

class PyReprTest {
    @ParameterizedTest(name = "repr({0}) == {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "0.1|0.1",
            "3.2|3.2",
            "1.0|1.0",
            "100.0|100.0",
            "1.0E15|1000000000000000.0",
            "1.0E16|1e+16",
            "1.2345678901234568E16|1.2345678901234568e+16",
            "1.0E22|1e+22",
            "1.0E23|1e+23",
            "1.0E-4|0.0001",
            "1.0E-5|1e-05",
            "4.9E-324|5e-324",
            "2.2250738585072014E-308|2.2250738585072014e-308",
            "1.7976931348623157E308|1.7976931348623157e+308",
            "9.007199254740993E15|9007199254740992.0",
            "1.2345678912345679E8|123456789.12345679",
            "0.30000000000000004|0.30000000000000004",
            "-2.5|-2.5",
            "6.02E23|6.02e+23",
        ],
    )
    fun `float repr matches CPython`(value: Double, expected: String) {
        assertEquals(expected, PyRepr.floatRepr(value))
    }

    @Test
    fun `special floats`() {
        assertEquals("nan", PyRepr.floatRepr(Double.NaN))
        assertEquals("inf", PyRepr.floatRepr(Double.POSITIVE_INFINITY))
        assertEquals("-inf", PyRepr.floatRepr(Double.NEGATIVE_INFINITY))
        assertEquals("-0.0", PyRepr.floatRepr(-0.0))
        assertEquals("0.0", PyRepr.floatRepr(0.0))
    }

    @Test
    fun `str repr picks quotes and escapes like CPython`() {
        assertEquals("'abc'", PyRepr.strRepr("abc"))
        assertEquals("\"it's\"", PyRepr.strRepr("it's"))
        assertEquals("'say \"hi\"'", PyRepr.strRepr("say \"hi\""))
        assertEquals("'both \\' and \"'", PyRepr.strRepr("both ' and \""))
        assertEquals("'a\\\\b\\t\\n\\r'", PyRepr.strRepr("a\\b\t\n\r"))
        assertEquals("'\\x07\\x7f\\x85\\xa0'", PyRepr.strRepr("\u0007\u007f\u0085\u00a0"))
        assertEquals("'\\u200b\\u2028'", PyRepr.strRepr("\u200b\u2028"))
        assertEquals("'Ünïcödé 😀'", PyRepr.strRepr("Ünïcödé 😀"))
        assertEquals("'\\U000e0001'", PyRepr.strRepr(String(Character.toChars(0xE0001))))
    }

    @Test
    fun `containers dates and str`() {
        val dict = PyValue.Dict.of(
            listOf(
                PyValue.Str("a") to PyValue.List(listOf(PyValue.Int(1), PyValue.None, PyValue.Bool(true), PyValue.Float(1.5))),
                PyValue.Int(2) to PyValue.Date("2024-01-01"),
            ),
        )
        assertEquals("{'a': [1, None, True, 1.5], 2: datetime.date(2024, 1, 1)}", PyRepr.repr(dict))
        assertEquals("2024-01-01", PyRepr.str(PyValue.Date("2024-01-01")))
        assertEquals(
            "datetime.datetime(2001, 12, 14, 21, 59, 43, 100000, tzinfo=datetime.timezone(datetime.timedelta(days=-1, seconds=68400)))",
            PyRepr.repr(PyValue.Date("2001-12-14 21:59:43.10 -5")),
        )
        assertEquals("abc", PyRepr.str(PyValue.Str("abc")))
        assertEquals("[]", PyRepr.repr(PyValue.List(emptyList())))
        assertEquals("{}", PyRepr.repr(PyValue.Dict.EMPTY))
    }

    @Test
    fun `int str limit and opaque values`() {
        assertEquals("1".repeat(4300), PyRepr.repr(PyValue.Int(BigInteger("1".repeat(4300)))))
        val error = assertThrows(PyException::class.java) { PyRepr.repr(PyValue.Int(BigInteger.TEN.pow(4300))) }
        assertEquals("ValueError", error.pyClass)
        assertThrows(IndeterminateValueException::class.java) { PyRepr.repr(PyValue.List(listOf(PyValue.Vault))) }
        assertEquals("<list>", PyRepr.display(PyValue.List(listOf(PyValue.Vault))))
        assertEquals("<vault>", PyRepr.display(PyValue.Vault))
        assertEquals("'" + "x".repeat(58) + "…", PyRepr.display(PyValue.Str("x".repeat(100))))
    }
}
