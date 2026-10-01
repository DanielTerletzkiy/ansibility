package de.terletzkiy.ansibility.semantics.value

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class PyValueTest {
    private fun load(yaml: String): PyValue = PyValue.fromYValue(YamlText.map("v: $yaml")["v"]!!)

    @Test
    fun `scalars load with YAML 1_1 typing`() {
        assertEquals(PyValue.None, load(""))
        assertEquals(PyValue.None, load("~"))
        assertEquals(PyValue.Bool(true), load("yes"))
        assertEquals(PyValue.Str("y"), load("y"))
        assertEquals(PyValue.Int(420), load("0644"))
        assertEquals(PyValue.Int(80), load("1:20"))
        assertEquals(PyValue.Float(3.1), load("3.10"))
        assertEquals(PyValue.Str("1e3"), load("1e3"))
        assertEquals(PyValue.Str("3.2"), load("\"3.2\""))
        assertEquals(PyValue.Date("2024-01-01"), load("2024-01-01"))
        assertEquals(PyValue.Str("2024-1-1"), load("2024-1-1"), "the resolver wants two-digit month and day")
        assertEquals("date", load("2024-01-01").typeName)
        assertEquals("datetime", load("2001-12-15T02:59:43.1Z").typeName)
    }

    @Test
    fun `containers keep order and merge equal keys like a Python dict`() {
        val dict = load("{1: a, 1.0: b, x: [1, two]}") as PyValue.Dict
        assertEquals(listOf(PyValue.Int(1), PyValue.Str("x")), dict.keys)
        assertEquals(PyValue.Str("b"), dict[PyValue.Float(1.0)])
        assertEquals(PyValue.List(listOf(PyValue.Int(1), PyValue.Str("two"))), dict["x"])
        val bools = load("{true: x, 1: y}") as PyValue.Dict
        assertEquals(listOf(PyValue.Bool(true) to PyValue.Str("y")), bools.entries)
    }

    @Test
    fun `opaque values`() {
        val vault = YamlText.map("v: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  6162\n")["v"]!!
        assertEquals(PyValue.Vault, PyValue.fromYValue(vault))
        assertEquals(PyValue.Unloadable, load("="))
        assertEquals(PyValue.Unloadable, load("2024-02-30"), "PyYAML's datetime.date(2024, 2, 30) raises")
        assertEquals(PyValue.Unloadable, load("1".repeat(4301)), "int() refuses more than 4300 digits")
        assertEquals(PyValue.Int(BigInteger("1".repeat(4300))), load("1".repeat(4300)))
        val template = YamlText.map("v: \"{{ x }}\"")["v"]!!
        assertEquals(PyValue.Templated("{{ x }}"), PyValue.fromYValue(template) { "{{" in it.text })
        assertEquals(PyValue.Str("{{ x }}"), PyValue.fromYValue(template))
    }

    @Test
    fun `python equality`() {
        assertTrue(PyValue.Int(1).pyEquals(PyValue.Float(1.0)))
        assertTrue(PyValue.Bool(true).pyEquals(PyValue.Int(1)))
        assertTrue(PyValue.Float(-0.0).pyEquals(PyValue.Int(0)))
        assertFalse(PyValue.Float(Double.NaN).pyEquals(PyValue.Float(Double.NaN)))
        assertTrue(PyValue.Float(Double.POSITIVE_INFINITY).pyEquals(PyValue.Float(Double.POSITIVE_INFINITY)))
        assertFalse(PyValue.Str("1").pyEquals(PyValue.Int(1)))
        assertTrue(load("{a: 1, b: 2}").pyEquals(load("{b: 2.0, a: true}")))
        assertFalse(load("[1, 2]").pyEquals(load("[2, 1]")))
        assertTrue(PyValue.Date("2001-12-15 2:59:43.10").pyEquals(PyValue.Date("2001-12-15T02:59:43.1")))
        assertFalse(PyValue.Date("2024-01-01").pyEquals(PyValue.Date("2024-01-01 00:00:00")), "date != datetime")
        assertTrue(PyValue.Date("2001-12-14 21:59:43.10 -5").pyEquals(PyValue.Date("2001-12-15T02:59:43.1Z")))
        assertFalse(PyValue.Vault.pyEquals(PyValue.Vault))
        assertEquals(PyValue.Int(1).pyHash(), PyValue.Float(1.0).pyHash())
        assertEquals(PyValue.Bool(false).pyHash(), PyValue.Float(0.0).pyHash())
    }

    @Test
    fun `truthiness and hashability`() {
        assertFalse(PyValue.Str("").isTruthy)
        assertFalse(PyValue.Float(0.0).isTruthy)
        assertTrue(PyValue.Float(Double.NaN).isTruthy)
        assertFalse(PyValue.List(emptyList()).isTruthy)
        assertTrue(PyValue.Date("2024-01-01").isTruthy)
        assertFalse(PyValue.List(emptyList()).isHashable)
        assertTrue(PyValue.Date("2024-01-01").isHashable)
        assertThrows(IllegalArgumentException::class.java) { PyValue.Dict.of(listOf(PyValue.List(emptyList()) to PyValue.None)) }
        assertThrows(IllegalArgumentException::class.java) { PyValue.Date("not a date") }
        assertEquals(PyValue.Str("x"), PyValue.fromYValue(YScalar("x", ScalarStyle.SINGLE_QUOTED)))
    }
}
