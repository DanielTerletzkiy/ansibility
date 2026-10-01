package de.terletzkiy.ansibility.semantics.json

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigInteger

class YValueJsonTest {
    @Test
    fun `YAML 1_1 values become the JSON Python would print`() {
        val yaml = YamlText.parse(
            """
            a: yes
            b: 0644
            c: 3.10
            d: "3.10"
            e: ~
            f: [1, two, 1e3]
            g: {nested: true}
            h:
            i: 12345678901234567890123
            1: int key
            true: bool key
            vault: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              3131
            """.trimIndent(),
        )
        val expected = linkedMapOf(
            "a" to true,
            "b" to 420L,
            "c" to 3.1,
            "d" to "3.10",
            "e" to null,
            "f" to listOf(1L, "two", "1e3"),
            "g" to mapOf("nested" to true),
            "h" to null,
            "i" to BigInteger("12345678901234567890123"),
            "1" to "int key",
            "true" to "bool key",
            "vault" to mapOf("__ansible_vault" to ""),
        )
        assertEquals(expected, YValueJson.toJson(yaml))
    }

    @Test
    fun `fromJson builds values that resolve back to the same Python values`() {
        val json = Json.parse("""{"s":"yes","n":null,"b":false,"i":42,"f":1.5,"big":1e20,"small":1e-7,"l":["0644",7]}""")
        val y = YValueJson.fromJson(json) as YMap
        assertEquals(Resolved.Str("yes"), (y["s"] as YScalar).resolved)
        assertEquals(Resolved.Null, (y["n"] as YScalar).resolved)
        assertEquals(Resolved.Bool(false), (y["b"] as YScalar).resolved)
        assertEquals(Resolved.Int(BigInteger.valueOf(42)), (y["i"] as YScalar).resolved)
        assertEquals(Resolved.Float(1.5), (y["f"] as YScalar).resolved)
        assertEquals(Resolved.Float(1e20), (y["big"] as YScalar).resolved)
        assertEquals(Resolved.Float(1e-7), (y["small"] as YScalar).resolved)
        assertEquals(Resolved.Str("0644"), ((y["l"] as YSeq).items[0] as YScalar).resolved)
        assertEquals(json, YValueJson.toJson(y), "fromJson and toJson are inverse")
    }

    @Test
    fun `floats are spelled like Python repr`() {
        val cases = mapOf(
            3.1 to "3.1", 1e16 to "1e+16", 1e15 to "1000000000000000.0", 0.0001 to "0.0001", 1e-5 to "1e-05",
            -2.5 to "-2.5", 0.0 to "0.0", 123456.789 to "123456.789", 1.5e300 to "1.5e+300",
        )
        for ((value, repr) in cases) assertEquals(repr, YValueJson.pythonFloatRepr(value), "repr($value)")
        assertEquals("1.0e+16", YValueJson.yamlFloat(1e16))
        assertEquals(".inf", YValueJson.yamlFloat(Double.POSITIVE_INFINITY))
    }
}
