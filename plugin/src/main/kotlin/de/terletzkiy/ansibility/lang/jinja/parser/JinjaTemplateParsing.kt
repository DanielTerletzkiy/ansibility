package de.terletzkiy.ansibility.lang.jinja.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.WhitespacesBinders
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaElementTypes as E
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T

/**
 * The template grammar of `jinja2/parser.py` 3.1 (`subparse`, `parse_statement` and the `parse_<tag>` methods) on top
 * of [JinjaExpressionParsing].
 *
 * **Error recovery never cascades.**
 * - Inside a tag, the first unusable token ends the tag's grammar; the rest up to `%}` or `}}` becomes one error
 *   element (no second error when the tag already reported one).
 * - Block structure is recovered by the tag names alone: a body ends at a `{% … %}` whose tag name some open block is
 *   waiting for. An inner block missing its end tag gets a "'{% endfor %}' expected" error and yields to the outer
 *   block; a middle or end tag no open block is waiting for is one error element.
 * - A broken `{% set %}` header is treated as an inline set, so a missing `=` never swallows the rest of the file.
 * - Unknown tag names are generic statements, not errors (they may be extension tags).
 */
internal class JinjaTemplateParsing(builder: PsiBuilder) : JinjaExpressionParsing(builder) {
    /** The tag names each open block is waiting for, innermost last. */
    private val open = ArrayList<TokenSet>()

    /** Parses a whole template; consumes every token. */
    fun parseTemplate() {
        parseContent()
        while (!builder.eof()) advance()
    }

    /** Template content up to the end, or up to a `{%` tag whose name an open block waits for. */
    private fun parseContent() {
        var steps = 0
        while (!builder.eof()) {
            if (++steps and 0xFF == 0) ProgressManager.checkCanceled()
            when (builder.tokenType) {
                T.VAR_START -> parseOutputTag()
                T.BLOCK_START -> {
                    val keyword = builder.lookAhead(1)
                    if (keyword != null && open.any { keyword in it }) return
                    parseStatement(keyword)
                }
                // TEXT, and nothing else in template mode
                else -> advance()
            }
        }
    }

    private fun parseOutputTag() {
        val tag = builder.mark()
        val before = errorCount
        advance()
        parseTupleOrError()
        finish(T.VAR_END, "}}", before)
        tag.done(E.OUTPUT_TAG)
    }

    private fun parseStatement(keyword: IElementType?) {
        when (keyword) {
            T.IF_KEYWORD -> parseIf()
            T.FOR_KEYWORD -> parseFor()
            T.SET_KEYWORD -> parseSet()
            T.MACRO_KEYWORD -> parseMacro()
            T.CALL_KEYWORD -> parseCallBlock()
            T.FILTER_KEYWORD -> parseFilterBlock()
            T.WITH_KEYWORD -> parseWith()
            T.BLOCK_KEYWORD -> parseBlock()
            T.RAW_KEYWORD -> parseRaw()
            T.INCLUDE_KEYWORD -> parseInclude()
            T.IMPORT_KEYWORD -> parseImport()
            T.FROM_KEYWORD -> parseFromImport()
            T.EXTENDS_KEYWORD -> singleTag(E.EXTENDS_STATEMENT) { parseExpressionOrError() }
            T.DO_KEYWORD -> singleTag(E.DO_STATEMENT) { parseTupleOrError() }
            T.BREAK_KEYWORD -> singleTag(E.BREAK_STATEMENT) {}
            T.CONTINUE_KEYWORD -> singleTag(E.CONTINUE_STATEMENT) {}
            in MIDDLE_AND_END_KEYWORDS -> parseStrayTag()
            else -> parseGenericStatement()
        }
    }

    // ------------------------------------------------------------------------------------------------ block statements

    private fun parseIf() {
        val statement = builder.mark()
        blockTag(E.IF_TAG) { parseTupleOrError(withCondExpr = false) }
        parseBody(IF_BRANCH_ENDS)
        while (atTag(T.ELIF_KEYWORD)) {
            blockTag(E.ELIF_TAG) { parseTupleOrError(withCondExpr = false) }
            parseBody(IF_BRANCH_ENDS)
        }
        if (atTag(T.ELSE_KEYWORD)) {
            blockTag(E.ELSE_TAG) {}
            parseBody(ENDIF)
        }
        parseEndTag(T.ENDIF_KEYWORD, "endif")
        statement.done(E.IF_STATEMENT)
    }

    private fun parseFor() {
        val statement = builder.mark()
        blockTag(E.FOR_TAG) {
            if (!parseTargets(extraEnd = IN)) {
                error("parser.expected.target")
                return@blockTag
            }
            if (expect(T.IN_KEYWORD, "in")) {
                parseTupleOrError(withCondExpr = false, extraEnd = RECURSIVE)
                if (at(T.IF_KEYWORD)) {
                    advance()
                    parseExpressionOrError()
                }
                if (at(T.RECURSIVE_KEYWORD)) advance()
            }
        }
        parseBody(FOR_BODY_ENDS)
        if (atTag(T.ELSE_KEYWORD)) {
            blockTag(E.ELSE_TAG) {}
            parseBody(ENDFOR)
        }
        parseEndTag(T.ENDFOR_KEYWORD, "endfor")
        statement.done(E.FOR_STATEMENT)
    }

    /** `{% set target = value %}`, or a block set `{% set target | filters %}…{% endset %}`. */
    private fun parseSet() {
        val statement = builder.mark()
        val tag = builder.mark()
        val before = errorCount
        advance()
        advance()
        if (!parseTargets(withNamespace = true)) error("parser.expected.target")
        if (at(T.ASSIGN) || errorCount != before) {
            if (at(T.ASSIGN)) {
                advance()
                parseTupleOrError()
            }
            finish(T.BLOCK_END, "%}", before)
            tag.drop()
            statement.done(E.SET_STATEMENT)
            return
        }
        parseFilters(null, startInline = false)
        if (!at(T.BLOCK_END) && !at(T.COLON)) {
            // neither `=` nor a block-set header: an inline set missing its value
            error("parser.expected.token", "=")
            finish(T.BLOCK_END, "%}", before)
            tag.drop()
            statement.done(E.SET_STATEMENT)
            return
        }
        finish(T.BLOCK_END, "%}", before, allowColon = true)
        tag.done(E.SET_TAG)
        parseBody(ENDSET)
        parseEndTag(T.ENDSET_KEYWORD, "endset")
        statement.done(E.SET_BLOCK_STATEMENT)
    }

    private fun parseMacro() {
        val statement = builder.mark()
        blockTag(E.MACRO_TAG) {
            if (!parseTargetName()) error("parser.expected.macro.name")
            if (at(T.LPAREN)) parseParameterList() else if (errorCount == it) error("parser.expected.token", "(")
        }
        parseBody(ENDMACRO)
        parseEndTag(T.ENDMACRO_KEYWORD, "endmacro")
        statement.done(E.MACRO_STATEMENT)
    }

    private fun parseCallBlock() {
        val statement = builder.mark()
        blockTag(E.CALL_TAG) {
            if (at(T.LPAREN)) parseParameterList()
            val before = errorCount
            val call = parseExpression()
            when {
                call == null -> if (errorCount == before) error("parser.expected.call")
                builder.latestDoneMarker?.tokenType != E.CALL_EXPRESSION -> error("parser.expected.call")
            }
        }
        parseBody(ENDCALL)
        parseEndTag(T.ENDCALL_KEYWORD, "endcall")
        statement.done(E.CALL_STATEMENT)
    }

    private fun parseFilterBlock() {
        val statement = builder.mark()
        blockTag(E.FILTER_TAG) {
            if (at(T.FILTER_NAME)) parseFilters(null, startInline = true) else error("parser.expected.filter.name")
        }
        parseBody(ENDFILTER)
        parseEndTag(T.ENDFILTER_KEYWORD, "endfilter")
        statement.done(E.FILTER_STATEMENT)
    }

    private fun parseWith() {
        val statement = builder.mark()
        blockTag(E.WITH_TAG) {
            var first = true
            while (!atTagEndOrEof() && !at(T.COLON)) {
                if (!first && !expect(T.COMMA, ",")) break
                first = false
                val assignment = builder.mark()
                if (!parseTargets()) {
                    assignment.drop()
                    error("parser.expected.target")
                    break
                }
                if (expect(T.ASSIGN, "=")) parseExpressionOrError()
                assignment.done(E.WITH_ASSIGNMENT)
            }
        }
        parseBody(ENDWITH)
        parseEndTag(T.ENDWITH_KEYWORD, "endwith")
        statement.done(E.WITH_STATEMENT)
    }

    private fun parseBlock() {
        val statement = builder.mark()
        blockTag(E.BLOCK_TAG) {
            if (atName()) {
                remapToIdentifier()
                advance()
                if (atIdentifier("scoped")) advance()
                if (atIdentifier("required")) advance()
            } else {
                error("parser.expected.block.name")
            }
        }
        parseBody(ENDBLOCK)
        if (atTag(T.ENDBLOCK_KEYWORD)) {
            blockTag(E.END_TAG, allowColon = false) { if (at(T.IDENTIFIER)) advance() }
        } else {
            error("parser.missing.end.tag", "endblock")
        }
        statement.done(E.BLOCK_STATEMENT)
    }

    /** `{% raw %}` RAW_TEXT `{% endraw %}`; the lexer already made the body one opaque token. */
    private fun parseRaw() {
        val statement = builder.mark()
        blockTag(E.RAW_TAG, allowColon = false) {}
        if (at(T.RAW_TEXT)) advance()
        parseEndTag(T.ENDRAW_KEYWORD, "endraw")
        statement.done(E.RAW_STATEMENT)
    }

    /** A body up to a tag in [ends] (or one an outer block waits for); includes leading and trailing comments. */
    private fun parseBody(ends: TokenSet) {
        open += ends
        val body = builder.mark()
        try {
            parseContent()
        } finally {
            open.removeAt(open.lastIndex)
        }
        body.done(E.BODY)
        body.setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, WhitespacesBinders.GREEDY_RIGHT_BINDER)
    }

    /** The end tag `{% [keyword] %}`, or an "expected" error when the block ends without it. */
    private fun parseEndTag(keyword: IElementType, name: String) {
        if (atTag(keyword)) {
            blockTag(E.END_TAG, allowColon = false) {}
        } else {
            error("parser.missing.end.tag", name)
        }
    }

    // ------------------------------------------------------------------------------------------------ single-tag statements

    private fun parseInclude() = singleTag(E.INCLUDE_STATEMENT) {
        parseExpressionOrError()
        if (atIdentifier("ignore") && nextIsIdentifier("missing")) {
            advance()
            advance()
        }
        parseImportContext()
    }

    private fun parseImport() = singleTag(E.IMPORT_STATEMENT) {
        parseExpressionOrError()
        if (expect(T.AS_KEYWORD, "as") && !parseTargetName()) error("parser.expected.name")
        parseImportContext()
    }

    private fun parseFromImport() = singleTag(E.FROM_IMPORT_STATEMENT) {
        parseExpressionOrError()
        if (!expect(T.IMPORT_KEYWORD, "import")) return@singleTag
        var first = true
        while (true) {
            if (!first) {
                if (!at(T.COMMA)) break
                advance()
            }
            first = false
            if (atImportContext()) break
            if (!atName()) {
                error("parser.expected.name")
                break
            }
            val name = builder.mark()
            remapToIdentifier()
            advance()
            if (at(T.AS_KEYWORD)) {
                advance()
                if (!parseTargetName()) error("parser.expected.name")
            }
            name.done(E.IMPORTED_NAME)
        }
        parseImportContext()
    }

    /** `with context` / `without context`. */
    private fun parseImportContext() {
        if (atImportContext()) {
            advance()
            advance()
        }
    }

    private fun atImportContext(): Boolean = (at(T.WITH_KEYWORD) || atIdentifier("without")) && nextIsIdentifier("context")

    /**
     * A tag Jinja core does not define, or `{% %}`. Its arguments are kept as expressions when they parse as a
     * comma-separated list without errors, otherwise as plain tokens.
     */
    private fun parseGenericStatement() {
        val tag = builder.mark()
        val before = errorCount
        advance()
        if (atTagEndOrEof()) {
            error("parser.expected.tag.name")
        } else if (at(T.IDENTIFIER) && builder.tokenText in T.STATEMENT_KEYWORDS) {
            // `{% set = 1 %}`: the lexer reads a statement keyword followed by `=` as a name
            val name = builder.tokenText.orEmpty()
            val junk = builder.mark()
            while (!atTagEndOrEof()) advance()
            errorElement(junk, "parser.malformed.tag", name)
        } else {
            advance()
            if (!atTagEndOrEof()) {
                val attempt = builder.mark()
                var first = true
                while (!atTagEndOrEof()) {
                    if (!first && !expect(T.COMMA, ",")) break
                    first = false
                    if (parseExpression() == null) {
                        error("parser.expected.expression")
                        break
                    }
                }
                if (errorCount == before && atTagEndOrEof()) {
                    attempt.drop()
                } else {
                    attempt.rollbackTo()
                    errorCount = before
                    while (!atTagEndOrEof()) advance()
                }
            }
        }
        finish(T.BLOCK_END, "%}", before)
        tag.done(E.GENERIC_STATEMENT)
    }

    /** `{% elif %}`, `{% else %}` or `{% end… %}` that no open block waits for: one error element. */
    private fun parseStrayTag() {
        val tag = builder.mark()
        advance()
        val name = builder.tokenText.orEmpty()
        while (!atTagEndOrEof()) advance()
        if (at(T.BLOCK_END)) advance()
        errorElement(tag, "parser.unexpected.tag", name)
    }

    // ------------------------------------------------------------------------------------------------ tag helpers

    /**
     * One `{% keyword … %}` tag of a block statement as a [type] node: consumes the delimiter and the keyword, runs
     * [content] (which receives the error count at the tag start), then the optional `:` and `%}`.
     */
    private inline fun blockTag(type: IElementType, allowColon: Boolean = true, content: (Int) -> Unit) {
        val tag = builder.mark()
        val before = errorCount
        advance()
        advance()
        content(before)
        finish(T.BLOCK_END, "%}", before, allowColon)
        tag.done(type)
    }

    /** A single-tag statement as a [type] node; like [blockTag] without the optional `:`. */
    private inline fun singleTag(type: IElementType, content: () -> Unit) {
        val tag = builder.mark()
        val before = errorCount
        advance()
        advance()
        content()
        finish(T.BLOCK_END, "%}", before)
        tag.done(type)
    }

    /**
     * Ends a tag at [end]: Jinja allows a `:` before the `%}` of block-opening tags ([allowColon]). Tokens before the
     * end become one error element, reported only when the tag had no error yet.
     */
    private fun finish(end: IElementType, text: String, errorsBefore: Int, allowColon: Boolean = false) {
        if (allowColon && at(T.COLON)) advance()
        if (at(end)) {
            advance()
            return
        }
        if (builder.eof()) {
            if (errorCount == errorsBefore) error("parser.expected.token", text)
            return
        }
        val junk = builder.mark()
        while (!builder.eof() && !at(end) && builder.tokenType !in TEMPLATE_LEVEL) advance()
        if (errorCount == errorsBefore) errorElement(junk, "parser.unexpected.tag.content") else junk.drop()
        if (at(end)) advance()
    }

    private fun parseParameterList() {
        val list = builder.mark()
        val before = errorCount
        advance()
        var first = true
        while (!at(T.RPAREN) && !atTagEndOrEof()) {
            if (!first && !expect(T.COMMA, ",")) break
            first = false
            val parameter = builder.mark()
            if (!parseTargetName()) {
                parameter.drop()
                error("parser.expected.name")
                break
            }
            if (at(T.ASSIGN)) {
                advance()
                parseExpressionOrError()
            }
            parameter.done(E.PARAMETER)
        }
        close(T.RPAREN, ")", before)
        list.done(E.PARAMETER_LIST)
    }

    /** At `{%` followed by the tag name [keyword]. */
    private fun atTag(keyword: IElementType): Boolean = at(T.BLOCK_START) && builder.lookAhead(1) == keyword

    private fun atIdentifier(text: String): Boolean = at(T.IDENTIFIER) && builder.tokenText == text

    /** Whether the token after the current one is the identifier [text]. */
    private fun nextIsIdentifier(text: String): Boolean {
        if (builder.lookAhead(1) != T.IDENTIFIER) return false
        val probe = builder.mark()
        advance()
        val matches = builder.tokenText == text
        probe.rollbackTo()
        return matches
    }

    private companion object {
        val IN: TokenSet = TokenSet.create(T.IN_KEYWORD)
        val RECURSIVE: TokenSet = TokenSet.create(T.RECURSIVE_KEYWORD)
        val IF_BRANCH_ENDS: TokenSet = TokenSet.create(T.ELIF_KEYWORD, T.ELSE_KEYWORD, T.ENDIF_KEYWORD)
        val ENDIF: TokenSet = TokenSet.create(T.ENDIF_KEYWORD)
        val FOR_BODY_ENDS: TokenSet = TokenSet.create(T.ELSE_KEYWORD, T.ENDFOR_KEYWORD)
        val ENDFOR: TokenSet = TokenSet.create(T.ENDFOR_KEYWORD)
        val ENDSET: TokenSet = TokenSet.create(T.ENDSET_KEYWORD)
        val ENDMACRO: TokenSet = TokenSet.create(T.ENDMACRO_KEYWORD)
        val ENDCALL: TokenSet = TokenSet.create(T.ENDCALL_KEYWORD)
        val ENDFILTER: TokenSet = TokenSet.create(T.ENDFILTER_KEYWORD)
        val ENDWITH: TokenSet = TokenSet.create(T.ENDWITH_KEYWORD)
        val ENDBLOCK: TokenSet = TokenSet.create(T.ENDBLOCK_KEYWORD)

        /** Tag names that continue or end a block. */
        val MIDDLE_AND_END_KEYWORDS: TokenSet = TokenSet.create(
            T.ELIF_KEYWORD, T.ELSE_KEYWORD, T.ENDIF_KEYWORD, T.ENDFOR_KEYWORD, T.ENDSET_KEYWORD, T.ENDMACRO_KEYWORD,
            T.ENDCALL_KEYWORD, T.ENDFILTER_KEYWORD, T.ENDWITH_KEYWORD, T.ENDBLOCK_KEYWORD, T.ENDRAW_KEYWORD,
        )

        /** Tokens that only occur between tags; a tag's grammar never consumes them. */
        val TEMPLATE_LEVEL: TokenSet = TokenSet.create(T.TEXT, T.RAW_TEXT, T.VAR_START, T.BLOCK_START)
    }
}
