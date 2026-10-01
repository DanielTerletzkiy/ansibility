package de.terletzkiy.ansibility.yaml

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values were measured with PyYAML 6.0.3, the loader under ansible-core. */
class YamlScalarDecoderTest {
    private fun flow(text: String) = YamlScalarDecoder.decodeFlow(text)

    private fun block(text: String, parentIndent: Int = 0) = YamlScalarDecoder.decodeBlock(text, parentIndent)

    @Test
    fun doubleQuotedEscapes() {
        assertEquals("tab\there", flow("\"tab\\there\""))
        assertEquals("A ä \uD83D\uDE00", flow("\"\\x41 \\u00e4 \\U0001F600\""))
        assertEquals(
            "\u0000 \u0007 \u001B \u0085 \u00A0 \u2028 \u2029 / \" \\",
            flow("\"\\0 \\a \\e \\N \\_ \\L \\P \\/ \\\" \\\\\""),
        )
    }

    @Test
    fun singleQuotesOnlyEscapeTheQuote() {
        assertEquals("it's", flow("'it''s'"))
        assertEquals("C:\\temp\\new \"x\"", flow("'C:\\temp\\new \"x\"'"))
    }

    @Test
    fun flowScalarsFoldLineBreaks() {
        assertEquals("multi line   folded\npara", flow("\"multi\n  line   folded\n\n  para\""))
        assertEquals("trail next", flow("\"trail   \n  next\""))
        assertEquals("continued", flow("\"cont\\\n  inued\""))
        assertEquals("a b", flow("\"a\\\n  \\ b\""))
    }

    /** PyYAML raises a ScannerError for these (Ansible cannot load the file); the decoder keeps the text. */
    @Test
    fun malformedFlowScalarsDoNotThrow() {
        assertEquals("unterminated", flow("\"unterminated"))
        assertEquals("\\q", flow("\"\\q\""))
        assertEquals("\\x4", flow("\"\\x4\""))
        assertEquals("", flow("\""))
    }

    @Test
    fun literalChomping() {
        assertEquals("a\nb\n", block("|\n  a\n  b\n"))
        assertEquals("a\nb", block("|-\n  a\n  b\n"))
        assertEquals("a\n\n\n", block("|+\n  a\n\n\n"))
        assertEquals("a\n", block("|\n  a\n\n\n"))
        assertEquals("a", block("|\n  a"))
    }

    @Test
    fun foldedLines() {
        assertEquals("a b\nc\n", block(">\n  a\n  b\n\n  c\n"))
        assertEquals("a\n  more\nb", block(">-\n  a\n    more\n  b\n"))
    }

    @Test
    fun indentationIndicatorIsRelativeToTheParent() {
        assertEquals("  two\n", block("|2\n    two\n"))
        assertEquals(" x\n", block("|2\n     x\n", parentIndent = 2))
        // At document level the indentation starts from column 1.
        assertEquals(" x\n", block("|1\n  x\n", parentIndent = -1))
    }

    @Test
    fun leadingBlankLinesAndHeaderComments() {
        assertEquals("\nafter\n", block("|\n\n  after\n"))
        assertEquals("text\n", block("| # comment\n  text\n"))
    }

    @Test
    fun documentLevelBlockScalar() {
        assertEquals("top\n  indented\n", block("|\n  top\n    indented\n", parentIndent = -1))
    }

    @Test
    fun plainScalarsFold() {
        assertEquals("plain  with   spaces", YamlScalarDecoder.decodePlain("plain  with   spaces"))
        assertEquals("first second\nthird", YamlScalarDecoder.decodePlain("first\n  second\n\n  third"))
        assertEquals("a\n\nb", YamlScalarDecoder.decodePlain("a\n\n\n  b"))
    }
}
