package de.terletzkiy.ansibility.lang.jinja

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import java.nio.file.Files
import java.nio.file.Path

/** One lexed token with the state the lexer reported at its start. */
internal data class LexedToken(val type: IElementType, val start: Int, val end: Int, val state: Int)

/** Lexes [text] from [start] with [initialState] until the end. */
internal fun lex(
    text: CharSequence,
    mode: JinjaLexMode = JinjaLexMode.TEMPLATE,
    start: Int = 0,
    initialState: Int = 0,
): List<LexedToken> {
    val lexer = AnsibleJinjaLexer(mode)
    lexer.start(text, start, text.length, initialState)
    val tokens = ArrayList<LexedToken>()
    while (true) {
        val type = lexer.tokenType ?: break
        tokens += LexedToken(type, lexer.tokenStart, lexer.tokenEnd, lexer.state)
        lexer.advance()
    }
    return tokens
}

/** A compact dump, one `TYPE 'text'` line per token; whitespace tokens are left out unless [withWhitespace]. */
internal fun dump(text: CharSequence, mode: JinjaLexMode = JinjaLexMode.TEMPLATE, withWhitespace: Boolean = false): String =
    lex(text, mode)
        .filter { withWhitespace || it.type != TokenType.WHITE_SPACE }
        .joinToString("\n") { "${it.type} '${escape(text.subSequence(it.start, it.end))}'" }

private fun escape(s: CharSequence): String = s.toString().replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

/** Asserts that restarting at every token start with that token's state reproduces the rest of the token stream. */
internal fun assertRestartable(text: CharSequence, mode: JinjaLexMode = JinjaLexMode.TEMPLATE) {
    val all = lex(text, mode)
    // tokens must tile the input without gaps
    var expectedStart = 0
    for (token in all) {
        check(token.start == expectedStart) { "gap or overlap before token $token" }
        check(token.end > token.start) { "empty token $token" }
        expectedStart = token.end
    }
    check(expectedStart == text.length) { "tokens end at $expectedStart, text length is ${text.length}" }
    for ((index, token) in all.withIndex()) {
        val restarted = lex(text, mode, token.start, token.state)
        val expected = all.subList(index, all.size)
        if (restarted != expected) {
            val firstDiff = restarted.zip(expected).indexOfFirst { (a, b) -> a != b }
            throw AssertionError(
                "restart at offset ${token.start} (token #$index ${token.type}, state ${token.state}) differs " +
                    "at token #${index + firstDiff}: expected ${expected.getOrNull(firstDiff)}, got ${restarted.getOrNull(firstDiff)}",
            )
        }
    }
}

/**
 * The significant tokens of [text] in the normalized form of `testData/jinja/reference/generate.py`, which writes the
 * same form from jinja2's own lexer: outer text, raw bodies and whitespace are left out, `{% raw %}`/`{% endraw %}`
 * become `raw_begin`/`raw_end`, a comment is `comment`, and code tokens are `name|string|integer|float|operator<TAB>text`.
 */
internal fun jinjaReferenceTokens(text: String, mode: JinjaLexMode): List<String> {
    val tokens = lex(text, mode).filter { it.type != TokenType.WHITE_SPACE }
    val out = ArrayList<String>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        val value = text.substring(token.start, token.end)
        val type = token.type
        val nextType = tokens.getOrNull(i + 1)?.type
        with(AnsibleJinjaTokenTypes) {
            when {
                type == TEXT || type == RAW_TEXT -> Unit
                type == BLOCK_START && (nextType == RAW_KEYWORD || nextType == ENDRAW_KEYWORD) -> {
                    out += if (nextType == RAW_KEYWORD) "raw_begin" else "raw_end"
                    while (i < tokens.size && tokens[i].type != BLOCK_END) i++
                }
                type == VAR_START -> out += "variable_begin"
                type == VAR_END -> out += "variable_end"
                type == BLOCK_START -> out += "block_begin"
                type == BLOCK_END -> out += "block_end"
                type == COMMENT -> out += "comment"
                type == STRING -> out += "string\t" + escape(value)
                type == INTEGER -> out += "integer\t$value"
                type == FLOAT -> out += "float\t$value"
                type == IDENTIFIER || type == FILTER_NAME || type == TEST_NAME || type in KEYWORDS -> out += "name\t$value"
                type == BAD_CHARACTER -> out += "BAD_CHARACTER\t$value"
                else -> out += "operator\t$value"
            }
        }
        i++
    }
    return out
}

/**
 * Compares each golden file with its actual text. Missing golden files are all written first and the test then fails,
 * so a new golden file is always reviewed before it is committed.
 */
internal fun assertSameAsGolden(expectations: Map<Path, String>) {
    val created = expectations.filterKeys { !Files.exists(it) }
    for ((path, actual) in created) {
        Files.createDirectories(path.parent)
        Files.writeString(path, actual)
    }
    for ((path, actual) in expectations - created.keys) {
        org.junit.Assert.assertEquals("golden file $path", Files.readString(path), actual)
    }
    if (created.isNotEmpty()) throw AssertionError("golden files created, review them and re-run: ${created.keys}")
}

/** The Jinja test data root, `plugin/src/test/testData/jinja` (tests run with the `plugin` project as working dir). */
internal val jinjaTestData: Path = Path.of("src/test/testData/jinja").toAbsolutePath().also {
    check(Files.isDirectory(it)) { "test data not found at $it" }
}
