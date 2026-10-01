package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** human_to_bytes, convert_bool, path expansion and the CPython text/number primitives behind the checks. */
class HelpersTest {
    private fun bytes(text: String, unit: String? = null, bits: Boolean = false): CheckResult =
        HumanToBytes.convert(PyValue.Str(text), unit, bits)

    @Test
    fun `human_to_bytes`() {
        assertEquals(CheckResult.Accepted(PyValue.Int(1024)), bytes("1K"))
        assertEquals(CheckResult.Accepted(PyValue.Int(1024)), bytes("1KB"))
        assertEquals(CheckResult.Accepted(PyValue.Int(1048576)), bytes("1 megabyte"))
        assertEquals(CheckResult.Accepted(PyValue.Int(2)), bytes("2.5"), "round half to even")
        assertEquals(CheckResult.Accepted(PyValue.Int(4)), bytes("3.5"))
        assertEquals(CheckResult.Accepted(PyValue.Int(10485760)), bytes("10", unit = "M"))
        assertEquals(CheckResult.Accepted(PyValue.Int(1024)), bytes("1K\n"), "`\$` matches before a final newline")
        assertEquals(CheckResult.Accepted(PyValue.Int(1048576)), bytes("1Mb", bits = true))
        assertTrue(bytes("1Mb") is CheckResult.Rejected)
        assertTrue(bytes("1MB", bits = true) is CheckResult.Rejected)
        assertTrue(bytes("1X") is CheckResult.Rejected)
        assertTrue(bytes("-1K") is CheckResult.Rejected)
        assertTrue(bytes("5.K") is CheckResult.Rejected)
        assertEquals("OverflowError", (bytes("9".repeat(400)) as CheckResult.Crash).exceptionClass)
        assertTrue(HumanToBytes.convert(PyValue.Bool(true)) is CheckResult.Rejected)
    }

    @Test
    fun `boolean strict and lenient`() {
        assertEquals(CheckResult.Accepted(PyValue.Bool(true)), Booleans.boolean(PyValue.Str("  On ")))
        assertTrue(Booleans.boolean(PyValue.Str("maybe")) is CheckResult.Rejected)
        assertEquals(CheckResult.Accepted(PyValue.Bool(false)), Booleans.boolean(PyValue.Str("maybe"), strict = false))
        val list = PyValue.List(emptyList())
        assertTrue(Booleans.boolean(list, strict = false, CoreSemantics.PINNED) is CheckResult.Rejected, "2.18: unhashable raises")
        assertEquals(
            CheckResult.Accepted(PyValue.Bool(false)),
            Booleans.boolean(list, strict = false, CoreSemantics(CoreVersion(2, 21, 4))),
        )
    }

    @Test
    fun `path environment`() {
        val env = PathEnvironment(mapOf("HOME" to "/home/g/", "V" to "v", "E" to ""), userHomes = mapOf("root" to "/root"))
        assertEquals("/home/g", env.expandUser("~"))
        assertEquals("/home/g/x", env.expandUser("~/x"))
        assertEquals("/root/x", env.expandUser("~root/x"))
        assertEquals("~nobody/x", env.expandUser("~nobody/x"))
        assertEquals("/~", env.expandUser("/~"))
        assertEquals("v/v\$v", env.expandVars("\$V/\${V}\$\$V"))
        assertEquals("\${V", env.expandVars("\${V"))
        assertEquals("", env.expandVars("\$E"))
        assertEquals("\$Vx \${} \$1", env.expandVars("\$Vx \${} \$1"))
        assertEquals("~/x", PathEnvironment.EMPTY.expandUser("~/x"))
    }

    @Test
    fun `decimal and float string parsing follow CPython`() {
        fun int(text: String) = CheckType().int(PyValue.Str(text))
        assertEquals(CheckResult.Accepted(PyValue.Int(42)), int("_4_2_"))
        assertEquals(CheckResult.Accepted(PyValue.Int(42)), int("\u0085 42\u00a0"))
        assertEquals(CheckResult.Accepted(PyValue.Int(123)), int("\u0661\u0662\u0663"))
        assertEquals(CheckResult.Accepted(PyValue.Int(42)), int("420e-1"))
        assertTrue(int("1 2") is CheckResult.Rejected)
        assertTrue(int("\u200b1") is CheckResult.Rejected)
        assertEquals("OverflowError", (int("-Infinity") as CheckResult.Crash).exceptionClass)
        assertEquals("MemoryError", (int("1e99999999999") as CheckResult.Crash).exceptionClass)
        assertTrue(int("1e-99999999999") is CheckResult.Rejected)
        assertEquals(CheckResult.Accepted(PyValue.Int(0)), int("0e-99999999999"))

        assertEquals(1000.0001, PyNumbers.floatFromString("1_000.000_1"))
        assertEquals(Double.NEGATIVE_INFINITY, PyNumbers.floatFromString(" -iNF "))
        assertEquals(1.5, PyNumbers.floatFromString("\u0661.5"))
        assertEquals(100000.0, PyNumbers.floatFromString("1.e5"))
        for (bad in listOf("1__0", "_1", "1_", "1e", ".", "0x10", "in", "1_.5")) {
            val error = assertThrows(PyException::class.java) { PyNumbers.floatFromString(bad) }
            assertEquals("ValueError", error.pyClass, bad)
        }
        assertEquals("x", PyNumbers.strip("\u3000  x\u2029\u0085"))
        assertEquals("\u200bx", PyNumbers.strip("\u200bx"), "a zero-width space is not whitespace")
    }

    @Test
    fun `literal_eval subset`() {
        assertEquals("{'a': 'xy', 'b': '\\\\n'}", PyRepr.repr(PyLiteral.evalDict("{'a': 'x' 'y', \"b\": r'\\n'}")))
        assertEquals("{'a': 31, 'b': 1000, 'c': 5.0}", PyRepr.repr(PyLiteral.evalDict("{'a': 0x1F, 'b': 1_000, 'c': .5e1}")))
        assertEquals("{'a': 'AéA•'}", PyRepr.repr(PyLiteral.evalDict("{'a': '\\x41\\u00e9\\101\\N{BULLET}'}")))
        assertEquals("{'a': [1, {'b': None}], 'c': -1}", PyRepr.repr(PyLiteral.evalDict("{'a': [1, {'b': None},], 'c': -(1)}  # c")))
        assertEquals("{1: 'c'}", PyRepr.repr(PyLiteral.evalDict("{1: 'a', 1.0: 'b', True: 'c'}")))
        for (bad in listOf("{'a': x}", "{1, 2}", "{'a': 1} + 1", "{'a': 01}", "{'a': --1}", "{'a': f'x'}", "{**{}}",
            "{'a': [1, 2], [1]: 2}", "{'a': b'x' 'y'}", "{'a': -True}", "{'a': 1 - 2}", "{'a'\n: 1}\nx")) {
            assertThrows(PyException::class.java, { PyLiteral.evalDict(bad) }, bad)
        }
    }
}
