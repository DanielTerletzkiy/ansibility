package de.terletzkiy.ansibility.semantics.yaml

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

/** Expected values were measured with PyYAML 6.0.3 (`yaml.safe_load`), the loader under ansible-core. */
class Yaml11ResolverTest {
    private fun plain(text: String) = Yaml11Resolver.resolvePlain(text)

    private fun tagged(tag: String, text: String, style: ScalarStyle = ScalarStyle.PLAIN) =
        YScalar(text, style, tag).resolved

    private fun int(value: Long) = Resolved.Int(BigInteger.valueOf(value))

    @Test
    fun `yaml 1_1 traps behave like PyYAML`() {
        assertEquals(Resolved.Bool(true), plain("yes"))
        assertEquals(Resolved.Bool(false), plain("Off"))
        assertEquals(Resolved.Str("y"), plain("y"))
        assertEquals(Resolved.Int(BigInteger.valueOf(420)), plain("0644"))
        assertEquals(Resolved.Str("08"), plain("08"))
        assertEquals(Resolved.Str("0o644"), plain("0o644"))
        assertEquals(Resolved.Int(BigInteger.valueOf(80)), plain("1:20"))
        assertEquals(Resolved.Int(BigInteger.valueOf(31)), plain("0x1F"))
        assertEquals(Resolved.Float(3.2), plain("3.2"))
        assertEquals(Resolved.Float(3.1), plain("3.10"))
        assertEquals(Resolved.Str("1e3"), plain("1e3"))
        assertEquals(Resolved.Float(1000.0), plain("1.0e+3"))
        assertEquals(Resolved.Str("1.0e3"), plain("1.0e3"))
        assertEquals(Resolved.Timestamp("2024-01-01"), plain("2024-01-01"))
        assertEquals(Resolved.Null, plain("~"))
        assertEquals(Resolved.Null, plain(""))
        assertEquals(Resolved.Int(BigInteger.valueOf(1000)), plain("1_000"))
        assertEquals(Resolved.Unloadable, plain("="))
    }

    @Test
    fun `quoted scalars are strings and vault is opaque`() {
        val map = YamlText.map(
            """
            a: "3.2"
            b: 3.2
            c: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              6162
            d:
            """.trimIndent(),
        )
        assertEquals(Resolved.Str("3.2"), (map["a"] as YScalar).resolved)
        assertEquals(Resolved.Float(3.2), (map["b"] as YScalar).resolved)
        assert(map["c"] is YVault)
        assert(map["d"] is YEmpty)
    }

    @Test
    fun `int edge cases follow construct_yaml_int`() {
        assertEquals(int(1), plain("+0x_1"))
        assertEquals(int(0), plain("0_"))
        assertEquals(int(0), plain("-0"))
        assertEquals(int(15), plain("017"))
        assertEquals(int(7), plain("0_7"))
        assertEquals(int(1), plain("1__"))
        assertEquals(int(65), plain("1:5"))
        assertEquals(int(-90), plain("-1:30"))
        assertEquals(Resolved.Str("1:60"), plain("1:60"))
    }

    @Test
    fun `int literals whose digits vanish with the underscores are unloadable instead of crashing`() {
        assertEquals(Resolved.Unloadable, plain("0b_"))
        assertEquals(Resolved.Unloadable, plain("-0b_"))
        assertEquals(Resolved.Unloadable, plain("0x_"))
    }

    @Test
    fun `float edge cases follow the resolver regex`() {
        assertEquals(Resolved.Float(1.0), plain("1."))
        assertEquals(Resolved.Float(0.0), plain("0."))
        assertEquals(Resolved.Float(0.5), plain(".5"))
        assertEquals(Resolved.Str("-.5"), plain("-.5"))
        assertEquals(Resolved.Str("+.5"), plain("+.5"))
        assertEquals(Resolved.Float(Double.NEGATIVE_INFINITY), plain("-.inf"))
        assertEquals(Resolved.Float(Double.POSITIVE_INFINITY), plain("+.INF"))
        assertEquals(Resolved.Str("-.nan"), plain("-.nan"))
        assertEquals(Resolved.Str("._5"), plain("._5"))
        assertEquals(Resolved.Float(1000.5), plain("1_000.5"))
        assertEquals(Resolved.Str("1e-3"), plain("1e-3"))
        assertEquals(Resolved.Float(0.0015), plain("1.5E-3"))
        assertEquals(Resolved.Str("1.5E3"), plain("1.5E3"))
        assertEquals(Resolved.Float(1500.0), plain("+1.5e+3"))
        assertEquals(Resolved.Float(6.02e23), plain("6.02e+23"))
        assertEquals(Resolved.Float(90.5), plain("1:30.5"))
        assertEquals(Resolved.Float(-90.5), plain("-1:30.5"))
        assertEquals(Resolved.Float(685230.15), plain("190:20:30.15"))
        assertTrue((plain(".NaN") as Resolved.Float).value.isNaN())
    }

    @Test
    fun `bool and null spellings are case sensitive`() {
        assertEquals(Resolved.Bool(true), plain("YES"))
        assertEquals(Resolved.Str("yEs"), plain("yEs"))
        assertEquals(Resolved.Bool(false), plain("NO"))
        assertEquals(Resolved.Str("nO"), plain("nO"))
        assertEquals(Resolved.Str("Y"), plain("Y"))
        assertEquals(Resolved.Str("N"), plain("N"))
        assertEquals(Resolved.Str("oN"), plain("oN"))
        assertEquals(Resolved.Bool(true), plain("TRUE"))
        assertEquals(Resolved.Str("tRUE"), plain("tRUE"))
        assertEquals(Resolved.Null, plain("Null"))
        assertEquals(Resolved.Null, plain("NULL"))
        assertEquals(Resolved.Str("nULL"), plain("nULL"))
    }

    @Test
    fun `timestamps must be real dates`() {
        assertEquals(Resolved.Str("2024-1-1"), plain("2024-1-1"))
        assertEquals(Resolved.Timestamp("2001-12-14t21:59:43.10-05:00"), plain("2001-12-14t21:59:43.10-05:00"))
        assertEquals(Resolved.Timestamp("2002-12-14T21:59:43Z"), plain("2002-12-14T21:59:43Z"))
        assertEquals(Resolved.Unloadable, plain("2024-13-45"))
        assertEquals(Resolved.Unloadable, plain("2001-12-14 25:59:43"))
        assertEquals(Resolved.Unloadable, plain("0000-01-01"))
    }

    @Test
    fun `explicit standard tags construct like PyYAML`() {
        assertEquals(int(12), tagged("!!int", "  12 ", ScalarStyle.SINGLE_QUOTED))
        assertEquals(int(31), tagged("!!int", "0x1F"))
        assertEquals(int(5), tagged("!!int", "0b101"))
        assertEquals(int(15), tagged("!!int", "0o17"))
        assertEquals(int(10), tagged("!!int", "1_0"))
        assertEquals(int(7), tagged("tag:yaml.org,2002:int", "7", ScalarStyle.SINGLE_QUOTED))
        assertEquals(int(7), tagged("!<tag:yaml.org,2002:int>", "7", ScalarStyle.SINGLE_QUOTED))
        assertEquals(Resolved.Float(10.5), tagged("!!float", "1_0.5", ScalarStyle.SINGLE_QUOTED))
        assertEquals(Resolved.Float(1000.0), tagged("!!float", "1e3"))
        assertEquals(Resolved.Float(Double.POSITIVE_INFINITY), tagged("!!float", "inf"))
        assertEquals(Resolved.Float(Double.NEGATIVE_INFINITY), tagged("!!float", "-Infinity"))
        assertEquals(Resolved.Bool(true), tagged("!!bool", "YES"))
        assertEquals(Resolved.Null, tagged("!!null", ""))
        assertEquals(Resolved.Null, tagged("!!null", "anything"))
        assertEquals(Resolved.Str("1"), tagged("!!str", "1"))
        assertEquals(Resolved.Timestamp("2024-1-1"), tagged("!!timestamp", "2024-1-1"))
    }

    @Test
    fun `explicit tags that PyYAML cannot construct make the file unloadable`() {
        assertEquals(Resolved.Unloadable, tagged("!!int", "", ScalarStyle.SINGLE_QUOTED))
        assertEquals(Resolved.Unloadable, tagged("!!int", "-", ScalarStyle.SINGLE_QUOTED))
        assertEquals(Resolved.Unloadable, tagged("!!int", "abc"))
        assertEquals(Resolved.Unloadable, tagged("!!float", "0x1p3"))
        assertEquals(Resolved.Unloadable, tagged("!!float", "1d"))
        assertEquals(Resolved.Unloadable, tagged("!!float", "", ScalarStyle.SINGLE_QUOTED))
        assertEquals(Resolved.Unloadable, tagged("!!bool", "y"))
        assertEquals(Resolved.Unloadable, tagged("!!bool", "maybe"))
        assertEquals(Resolved.Unloadable, tagged("!!timestamp", "2024-02-30"))
        assertEquals(Resolved.Unloadable, tagged("!!timestamp", "nope"))
        assertEquals(Resolved.Unloadable, tagged("!!value", "="))
        assertEquals(Resolved.Unloadable, tagged("!!merge", "<<"))
        assertEquals(Resolved.Unloadable, tagged("!!seq", "x"))
        assertEquals(Resolved.Unloadable, tagged("!Ref", "something"))
    }

    @Test
    fun `ansible tags load as strings and the non-specific tag resolves implicitly`() {
        assertEquals(Resolved.Str("3"), tagged("!unsafe", "3"))
        assertEquals(Resolved.Str("x"), tagged("!vault", "x"))
        assertEquals(Resolved.Str("x"), tagged("!vault-encrypted", "x"))
        assertEquals(Resolved.Bool(true), tagged("!", "yes"))
        // PyYAML resolves `! '0644'` implicitly even though it is quoted.
        assertEquals(int(420), tagged("!", "0644", ScalarStyle.SINGLE_QUOTED))
        // Approximation: PyYAML builds bytes, which the model has no type for.
        assertEquals(Resolved.Str("aGk="), tagged("!!binary", "aGk="))
    }
}
