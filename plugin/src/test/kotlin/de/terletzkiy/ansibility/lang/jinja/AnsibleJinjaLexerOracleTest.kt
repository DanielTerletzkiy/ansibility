package de.terletzkiy.ansibility.lang.jinja

import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * Compares the lexer with jinja2 3.1's own lexer: `testData/jinja/reference/` holds token streams written by
 * `generate.py` from jinja2, for the synthetic templates and for `snippets.txt` (the research edge cases).
 */
class AnsibleJinjaLexerOracleTest {
    private val reference = jinjaTestData.resolve("reference")

    @Test
    fun `synthetic templates lex exactly like jinja2`() {
        val templates = jinjaTestData.resolve("templates").listDirectoryEntries().filter { it.extension == "j2" }
        assertTrue(templates.size >= 15)
        for (template in templates) {
            val expected = reference.resolve(template.name.removeSuffix(".j2") + ".tokens").readLines()
            assertEquals(template.name, expected, jinjaReferenceTokens(template.readText(), JinjaLexMode.TEMPLATE))
        }
    }

    @Test
    fun `edge case snippets lex exactly like jinja2`() {
        val snippets = reference.resolve("snippets.txt").readText().split("--- ").drop(1).map { block ->
            val mode = JinjaLexMode.valueOf(block.substringBefore('\n').trim())
            mode to block.substringAfter('\n').removeSuffix("\n")
        }
        val expected = reference.resolve("snippets.tokens").readLines().fold(mutableListOf<MutableList<String>>()) { acc, line ->
            if (line.startsWith("### ")) acc += mutableListOf<String>() else acc.last() += line
            acc
        }
        assertEquals("snippet count", expected.size, snippets.size)
        for ((index, snippet) in snippets.withIndex()) {
            val (mode, text) = snippet
            assertEquals("snippet #$index: $text", expected[index], jinjaReferenceTokens(text, mode))
            assertRestartable(text, mode)
        }
    }
}
