package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.value.PyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckTypeTest {
    private val v2188 = CheckType(CoreSemantics(CoreVersion(2, 18, 8)))
    private val v2214 = CheckType(CoreSemantics(CoreVersion(2, 21, 4)))

    private fun load(yaml: String): PyValue = PyValue.fromYValue(YamlText.map("v: $yaml")["v"]!!)

    private fun accepted(result: CheckResult): String {
        assertTrue(result is CheckResult.Accepted, "expected Accepted, got $result")
        return PyRepr.repr((result as CheckResult.Accepted).coerced)
    }

    private fun assertRejected(result: CheckResult, errorClass: String = "TypeError") {
        assertTrue(result is CheckResult.Rejected, "expected Rejected, got $result")
        assertEquals(errorClass, (result as CheckResult.Rejected).errorClass)
    }

    @Test
    fun `int is Decimal based in both 2_18_8 and 2_21 (measured)`() {
        for (check in listOf(v2188, v2214)) {
            assertEquals("42", accepted(check.int(load("42.0"))))
            assertEquals("42", accepted(check.int(load("'42.0'"))))
            assertEquals("1000", accepted(check.int(load("'1e3'"))))
            assertEquals("644", accepted(check.int(load("'0644'"))), "a str is parsed as decimal, unlike YAML's octal")
            assertEquals("420", accepted(check.int(load("0644"))))
            assertEquals("True", accepted(check.int(load("true"))), "bool is an int subclass and passes unchanged")
            assertEquals("42", accepted(check.int(load("[0, [4, 2], 0]"))), "Decimal accepts the tuple form")
            assertRejected(check.int(load("42.5")))
            assertRejected(check.int(load("'0x1F'")))
            assertRejected(check.int(load("'nan'")))
            assertRejected(check.int(load("~")))
            val crash = check.int(load("'inf'"))
            assertEquals(CheckResult.Crash("cannot convert Infinity to integer", "OverflowError"), crash)
        }
    }

    @Test
    fun `str of None depends on the core version`() {
        assertRejected(v2188.str(PyValue.None))
        assertRejected(CheckType(CoreSemantics(CoreVersion(2, 19, 0))).str(PyValue.None))
        assertEquals("''", accepted(CheckType(CoreSemantics(CoreVersion(2, 19, 1))).str(PyValue.None)))
        assertEquals("''", accepted(v2214.str(PyValue.None)))
        assertRejected(v2188.path(PyValue.None))
        assertEquals("''", accepted(v2214.path(PyValue.None)))
    }

    @Test
    fun `str stringifies every other value`() {
        assertEquals("'3.2'", accepted(v2188.str(load("3.2"))))
        assertEquals("'3.1'", accepted(v2188.str(load("3.10"))))
        assertEquals("'True'", accepted(v2188.str(load("yes"))))
        assertEquals("'2024-01-01'", accepted(v2188.str(load("2024-01-01"))))
        assertEquals("\"[{'name': 'a'}]\"", accepted(v2188.str(load("[{name: a}]"))))
        assertRejected(v2188.str(load("3.2"), allowConversion = false))
        assertEquals(PyValue.Vault, (v2188.str(PyValue.Vault) as CheckResult.Accepted).coerced)
    }

    @Test
    fun `bool follows convert_bool`() {
        for (spelling in listOf("yes", "'yes'", "' Yes '", "'y'", "'t'", "'1'", "1", "1.0", "'TRUE'")) {
            assertEquals("True", accepted(v2188.bool(load(spelling))), spelling)
        }
        for (spelling in listOf("off", "'n'", "'f'", "0", "0.0", "-0.0", "'0'")) {
            assertEquals("False", accepted(v2188.bool(load(spelling))), spelling)
        }
        for (spelling in listOf("2", "'2'", "0.5", "''", "'enabled'", "~", "[]", "2024-01-01", "'1.0'")) {
            assertRejected(v2188.bool(load(spelling)))
        }
    }

    @Test
    fun `float list and dict`() {
        assertEquals("1.0", accepted(v2188.float(load("true"))))
        assertEquals("10.0", accepted(v2188.float(load("'1_0'"))))
        assertEquals("inf", accepted(v2188.float(load("'inf'"))))
        assertRejected(v2188.float(load("''")))
        assertRejected(v2188.float(load("'_1'")))
        assertEquals("['a', ' b']", accepted(v2188.list(load("'a, b'"))))
        assertEquals("['5']", accepted(v2188.list(load("5"))))
        assertEquals("['True']", accepted(v2188.list(load("true"))))
        assertRejected(v2188.list(load("{a: 1}")))
        assertRejected(v2188.list(PyValue.None))
        assertEquals("{'a': 1}", accepted(v2188.dict(load("'{\"a\": 1}'"))))
        assertEquals("{'a': 1}", accepted(v2188.dict(load("\"{'a': 1}\""))), "literal_eval fallback")
        assertEquals("{'a': '1', 'b': 'x y'}", accepted(v2188.dict(load("\"a=1, b='x y'\""))))
        assertRejected(v2188.dict(load("'{bad'")))
        assertRejected(v2188.dict(load("'a=1, b'")))
        assertRejected(v2188.dict(load("abc")))
        assertTrue(v2188.dict(load("\"{'a': (1, 2)}\"")) is CheckResult.Indeterminate, "tuples are outside the value model")
    }

    @Test
    fun `path expands against the given environment only`() {
        assertEquals("'~/x'", accepted(v2188.path(load("'~/x'"))))
        assertEquals("'\$HOME/x'", accepted(v2188.path(load("'\$HOME/x'"))))
        val env = CheckType(CoreSemantics.PINNED, PathEnvironment(mapOf("HOME" to "/home/u", "X" to "x")))
        assertEquals("'/home/u/x'", accepted(env.path(load("'~/x'"))))
        assertEquals("'x/\$UNSET/x'", accepted(env.path(load("'\${X}/\$UNSET/\$X'"))))
    }

    @Test
    fun `jsonarg bytes bits raw and invalid types`() {
        assertEquals("'{\"a\": [1, true, null]}'", accepted(v2188.jsonArg(load("{a: [1, true, null]}"))))
        assertRejected(v2188.jsonArg(load("[2024-01-01]")))
        assertEquals("'[\"2024-01-01\"]'", accepted(v2214.jsonArg(load("[2024-01-01]"))))
        assertRejected(v2188.jsonArg(load("5")))
        assertEquals("1536", accepted(v2188.bytes(load("'1.5K'"))))
        assertRejected(v2188.bytes(load("'1Mb'")))
        assertEquals("1048576", accepted(v2188.bits(load("'1Mb'"))))
        assertEquals("[1]", accepted(v2188.check(OptionType.Raw, load("[1]"))))
        assertRejected(v2188.check(OptionType.Invalid("string"), load("abc")))
    }

    @Test
    fun `opaque values are indeterminate unless the check cannot depend on them`() {
        assertTrue(v2188.int(PyValue.Vault) is CheckResult.Indeterminate)
        assertTrue(v2188.bool(PyValue.Templated("{{ x }}")) is CheckResult.Indeterminate)
        assertTrue(v2188.str(PyValue.Unloadable) is CheckResult.Indeterminate)
        assertTrue(v2188.str(PyValue.List(listOf(PyValue.Vault))) is CheckResult.Indeterminate)
        assertEquals(CheckResult.Accepted(PyValue.Templated("{{ x }}")), v2188.raw(PyValue.Templated("{{ x }}")))
    }
}
