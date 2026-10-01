package de.terletzkiy.ansibility.resolve.loop

import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LiteralShapes]: the option-tree shape of literal YAML values (keys and types only). */
class LiteralShapesTest {
    private fun plain(text: String) = YScalar(text, ScalarStyle.PLAIN)
    private fun quoted(text: String) = YScalar(text, ScalarStyle.SINGLE_QUOTED)
    private fun map(vararg entries: Pair<String, de.terletzkiy.ansibility.semantics.yaml.YValue>) =
        YMap(entries.map { (k, v) -> YEntry(plain(k), v) })

    @Test
    fun scalarsKeepTheirYaml11Type() {
        assertEquals(OptionType.Int, LiteralShapes.of("a", plain("1")).type)
        assertEquals(OptionType.Str, LiteralShapes.of("a", quoted("1")).type)
        assertEquals(OptionType.Bool, LiteralShapes.of("a", plain("yes")).type)
        assertEquals(OptionType.Float, LiteralShapes.of("a", plain("3.2")).type)
        assertEquals("Jinja is only known at runtime", OptionType.Raw, LiteralShapes.of("a", quoted("{{ x }}")).type)
        assertEquals(OptionType.Raw, LiteralShapes.of("a", YEmpty()).type)
        assertEquals("a vault value decrypts to a string", OptionType.Str, LiteralShapes.of("a", YVault()).type)
    }

    @Test
    fun mappingsAndListsUnionTheirItems() {
        val list = YSeq(listOf(map("host" to plain("a"), "port" to plain("80")), map("host" to plain("b"), "tls" to plain("true"))))
        val shape = LiteralShapes.of("backends", list)
        assertEquals(OptionType.List, shape.type)
        assertEquals(OptionType.Dict, shape.elements)
        assertEquals(listOf("host", "port", "tls"), shape.options?.keys?.toList())
        assertEquals(OptionType.Bool, shape.options?.get("tls")?.type)

        val element = LiteralShapes.elementOf("item", list.items)!!
        assertEquals(OptionType.Dict, element.type)
        assertEquals("item", element.name)
        assertNull(LiteralShapes.elementOf("item", emptyList()))
    }

    @Test
    fun unionsWidenNumbersAndGiveRawOtherwise() {
        assertEquals(OptionType.Float, LiteralShapes.union(OptionSpec("a", OptionType.Int), OptionSpec("a", OptionType.Float)).type)
        assertEquals(OptionType.Raw, LiteralShapes.union(OptionSpec("a", OptionType.Int), OptionSpec("a", OptionType.Str)).type)
        assertEquals(OptionType.Str, LiteralShapes.union(OptionSpec("a", OptionType.Str), OptionSpec("a", OptionType.Str)).type)
        assertEquals(OptionType.Raw, LiteralShapes.elementOf("i", listOf(plain("1"), map("a" to plain("1"))))?.type)
    }

    @Test
    fun withItemsFlattensOneLevel() {
        val items = listOf(YSeq(listOf(plain("a"), plain("b"))), plain("c"))
        assertEquals(OptionType.Str, LiteralShapes.elementOf("item", items, flatten = true)?.type)
        assertEquals(OptionType.Raw, LiteralShapes.elementOf("item", items, flatten = false)?.type)
    }

    @Test
    fun deepNestingIsCut() {
        var value: de.terletzkiy.ansibility.semantics.yaml.YValue = plain("1")
        repeat(10) { value = map("k" to value) }
        var shape: OptionSpec? = LiteralShapes.of("root", value)
        var depth = 0
        while (shape?.options != null) {
            shape = shape.options?.get("k")
            depth++
        }
        assertEquals(LiteralShapes.MAX_DEPTH, depth)
        assertEquals(OptionType.Raw, shape?.type)
    }
}
