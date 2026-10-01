package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CombineVarsTest {
    private fun vars(text: String): Map<String, YValue> = YamlText.map(text.trimIndent()).entries.associate { it.key.text to it.value }

    /** A plain view of a value for assertions (scalars as text). */
    private fun plain(value: YValue?): Any? = when (value) {
        is YMap -> value.entries.associate { it.key.text to plain(it.value) }
        is YSeq -> value.items.map { plain(it) }
        is YScalar -> value.text
        else -> null
    }

    @Test
    fun `replace keeps the later value whole`() {
        val out = CombineVars.combine(
            vars("a: {x: 1, y: 1}\nkeep: 1"),
            vars("a: {y: 2}\nnew: 2"),
            HashBehaviour.REPLACE,
        )
        assertEquals(listOf("a", "keep", "new"), out.keys.toList())
        assertEquals(mapOf("y" to "2"), plain(out["a"]))
    }

    @Test
    fun `merge merges dictionaries recursively and replaces lists and scalars`() {
        val out = CombineVars.combine(
            vars(
                """
                a: {x: 1, nested: {p: 1, q: 1}, list: [1, 2]}
                s: {k: v}
                """,
            ),
            vars(
                """
                a: {y: 2, nested: {q: 2}, list: [3]}
                s: scalar
                """,
            ),
            HashBehaviour.MERGE,
        )
        assertEquals(mapOf("x" to "1", "nested" to mapOf("p" to "1", "q" to "2"), "list" to listOf("3"), "y" to "2"), plain(out["a"]))
        assertEquals("scalar", plain(out["s"]))
        assertNull((CombineVars.mergeHash(YamlText.map("a: 1"), YamlText.map("b: 2"))).range)
    }

    @Test
    fun `merge keeps the key order of the lower dictionary`() {
        val merged = CombineVars.mergeHash(YamlText.map("b: 1\na: 1"), YamlText.map("c: 2\nb: 2"))
        assertEquals(listOf("b", "a", "c"), merged.keys)
    }

    @Test
    fun `hash behaviour parsing`() {
        assertEquals(HashBehaviour.MERGE, HashBehaviour.parse(" Merge "))
        assertEquals(HashBehaviour.REPLACE, HashBehaviour.parse("replace"))
        assertNull(HashBehaviour.parse("append"))
    }
}
