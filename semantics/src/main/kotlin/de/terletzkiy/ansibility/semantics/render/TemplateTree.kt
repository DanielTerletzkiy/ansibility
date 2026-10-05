package de.terletzkiy.ansibility.semantics.render

import de.terletzkiy.ansibility.semantics.typeflow.JinjaExpr
import de.terletzkiy.ansibility.semantics.typeflow.JinjaExprParser
import de.terletzkiy.ansibility.semantics.typeflow.JinjaToken
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTokenKind
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTokenizer

/** One node of a [TemplateTree]; [start]/[end] are its range in the template source. */
sealed interface TNode {
    val start: Int
    val end: Int

    /** Outer text after whitespace control (never empty). */
    data class Text(val text: String, override val start: Int, override val end: Int) : TNode

    /** `{{ expr }}`; [expr] null when the tag does not parse ([error] says why). */
    data class Output(val expr: JinjaExpr?, override val start: Int, override val end: Int, val error: String? = null) : TNode

    data class Branch(val condition: JinjaExpr, val body: List<TNode>, val start: Int, val end: Int)

    /** `{% if %}…{% elif %}…{% else %}…{% endif %}`. */
    data class If(val branches: List<Branch>, val otherwise: List<TNode>?, override val start: Int, override val end: Int) : TNode

    /** `{% for a, b in iter if filter recursive %}…{% else %}…{% endfor %}`. */
    data class For(
        val targets: List<String>,
        val iter: JinjaExpr,
        val filter: JinjaExpr?,
        val recursive: Boolean,
        val body: List<TNode>,
        val otherwise: List<TNode>?,
        override val start: Int,
        override val end: Int,
    ) : TNode

    /** One target of `set`: a name, or `ns.attr`. */
    data class SetTarget(val name: String, val attribute: String? = null)

    /** `{% set a, b = value %}`. */
    data class Set(val targets: List<SetTarget>, val value: JinjaExpr, override val start: Int, override val end: Int) : TNode

    /** `{% set name | filters %}…{% endset %}`; [filters] apply to [CAPTURE] (the captured text). */
    data class SetBlock(val target: SetTarget, val filters: JinjaExpr?, val body: List<TNode>, override val start: Int, override val end: Int) : TNode

    data class Param(val name: String, val default: JinjaExpr?)

    /** `{% macro name(params) %}…{% endmacro %}`. */
    data class Macro(val name: String, val params: List<Param>, val body: List<TNode>, override val start: Int, override val end: Int) : TNode

    /** `{% call(params) macro(args) %}…{% endcall %}`. */
    data class CallBlock(val call: JinjaExpr, val params: List<Param>, val body: List<TNode>, override val start: Int, override val end: Int) : TNode

    /** `{% filter f | g %}…{% endfilter %}`; [filters] apply to [CAPTURE]. */
    data class FilterBlock(val filters: JinjaExpr, val body: List<TNode>, override val start: Int, override val end: Int) : TNode

    /** `{% with a = 1, b = 2 %}…{% endwith %}`. */
    data class With(val assignments: List<Pair<String, JinjaExpr>>, val body: List<TNode>, override val start: Int, override val end: Int) : TNode

    /** `{% include expr [ignore missing] [with|without context] %}`. */
    data class Include(val template: JinjaExpr, val ignoreMissing: Boolean, val withContext: Boolean, override val start: Int, override val end: Int) : TNode

    /** `{% import expr as alias [with context] %}`. */
    data class Import(val template: JinjaExpr, val alias: String, val withContext: Boolean, override val start: Int, override val end: Int) : TNode

    /** `{% from expr import a, b as c [with context] %}`. */
    data class FromImport(val template: JinjaExpr, val names: List<Pair<String, String>>, val withContext: Boolean, override val start: Int, override val end: Int) : TNode

    /** A tag the renderer reports instead of rendering: unknown tags, `extends`/`block`, syntax errors. */
    data class Problem(val message: String, val notEmulated: Boolean, override val start: Int, override val end: Int) : TNode

    companion object {
        /** The name filters of `{% filter %}` and block `set` are applied to. */
        const val CAPTURE: String = "\u0000capture"
    }
}

/** The Jinja environment options of a render (the template module's defaults, plan A.17). */
data class EnvOptions(
    val trimBlocks: Boolean = true,
    val lstripBlocks: Boolean = false,
    val newlineSequence: String = "\n",
    val keepTrailingNewline: Boolean = false,
)

/**
 * A parsed template: [nodes] after whitespace control. [header] holds the `#jinja2:` overrides (already applied to
 * [env]); [notEmulated] is set when the template needs something the one lexer cannot do (custom delimiters, line
 * statements), so the whole output is a placeholder.
 */
class TemplateTree(
    val source: String,
    val nodes: List<TNode>,
    val env: EnvOptions,
    val notEmulated: String?,
    /** Whether the source has any template syntax (2.19+ copies a file without any verbatim). */
    val hasSyntax: Boolean,
) {
    companion object {
        private val HEADER_KEYS = setOf("trim_blocks", "lstrip_blocks", "newline_sequence", "keep_trailing_newline")
        private val DELIMITER_KEYS = setOf(
            "block_start_string", "block_end_string", "variable_start_string", "variable_end_string",
            "comment_start_string", "comment_end_string", "line_statement_prefix", "line_comment_prefix",
        )

        /**
         * Parses [source] (a template file, or a templated YAML value) with [tokenizer] under [env]. A first line
         * `#jinja2: key: value, …` overrides [env] for the keys it names (and is not rendered).
         */
        fun parse(source: String, tokenizer: JinjaTokenizer, env: EnvOptions = EnvOptions(), rawStrings: Boolean = false, escapedQuoteEnds: Boolean = true): TemplateTree {
            var effective = env
            var notEmulated: String? = null
            var offset = 0
            if (source.startsWith("#jinja2:")) {
                val lineEnd = source.indexOf('\n').let { if (it < 0) source.length else it + 1 }
                for ((key, value) in headerPairs(source.substring("#jinja2:".length, lineEnd).trim())) {
                    effective = when (key) {
                        "trim_blocks" -> effective.copy(trimBlocks = value == "True")
                        "lstrip_blocks" -> effective.copy(lstripBlocks = value == "True")
                        "keep_trailing_newline" -> effective.copy(keepTrailingNewline = value == "True")
                        "newline_sequence" -> effective.copy(newlineSequence = unquote(value).replace("\\r", "\r").replace("\\n", "\n"))
                        in DELIMITER_KEYS -> effective.also { notEmulated = "custom Jinja delimiters ($key)" }
                        else -> effective
                    }
                }
                offset = lineEnd
            }
            val body = source.substring(offset)
            val tokens = tokenizer.tokenize(body).map { if (offset == 0) it else it.copy(start = it.start + offset) }
            val hasSyntax = tokens.any { it.kind != JinjaTokenKind.TEXT }
            val nodes = Builder(tokens, effective, rawStrings, escapedQuoteEnds, source.length).build()
            return TemplateTree(source, nodes, effective, notEmulated, hasSyntax)
        }

        private fun headerPairs(text: String): List<Pair<String, String>> =
            text.split(',').mapNotNull { pair ->
                val colon = pair.indexOf(':')
                if (colon < 0) null else pair.substring(0, colon).trim() to pair.substring(colon + 1).trim()
            }.filter { it.first in HEADER_KEYS || it.first in DELIMITER_KEYS }

        private fun unquote(value: String): String =
            if (value.length >= 2 && (value[0] == '\'' || value[0] == '"') && value.last() == value[0]) value.substring(1, value.length - 1) else value
    }

    /** A tag of the token stream with its whitespace markers. */
    private class Tag(val kind: Kind, val keyword: String?, val inner: List<JinjaToken>, val start: Int, val end: Int, val open: Char?, val close: Char?) {
        enum class Kind { VAR, BLOCK, COMMENT }
    }

    private class Builder(private val tokens: List<JinjaToken>, private val env: EnvOptions, private val rawStrings: Boolean, private val escapedQuoteEnds: Boolean, private val sourceLength: Int) {
        /** Text pieces and tags in order, whitespace control applied to the texts. */
        private val items = ArrayList<Any>()

        fun build(): List<TNode> {
            collect()
            val reader = Reader(items)
            val nodes = reader.nodes(emptySet())
            while (reader.hasMore()) {
                val tag = reader.next() as Tag
                reader.problems += TNode.Problem("Encountered unknown tag '${tag.keyword}'.", false, tag.start, tag.end)
                nodes.addAll(reader.nodes(emptySet()))
            }
            nodes.addAll(reader.problems)
            return nodes
        }

        private fun collect() {
            var text: StringBuilder? = null
            var textStart = 0
            var textEnd = 0
            var previous: Tag? = null
            var previousRawBegin = false
            var lineStart = true

            fun flush(next: Tag?) {
                val buffer = text ?: return
                var value = buffer.toString()
                if (next != null) {
                    if (next.open == '-') {
                        value = value.trimEnd()
                    } else if (env.lstripBlocks && next.kind != Tag.Kind.VAR && next.open != '+') {
                        val lineBegin = value.lastIndexOf('\n') + 1
                        if ((lineBegin > 0 || lineStart) && value.substring(lineBegin).all { it == ' ' || it == '\t' }) value = value.substring(0, lineBegin)
                    }
                }
                if (value.isNotEmpty()) items += TNode.Text(value, textStart, textEnd)
                text = null
            }

            var i = 0
            val last = tokens.size - 1
            while (i < tokens.size) {
                val token = tokens[i]
                when (token.kind) {
                    JinjaTokenKind.TEXT, JinjaTokenKind.RAW_TEXT -> {
                        var value = token.text
                        if (i == last && !env.keepTrailingNewline && value.endsWith('\n') && token.end >= sourceLength) value = value.dropLast(1)
                        if (text == null) {
                            val prev = previous
                            if (prev != null && prev.close == '-') {
                                value = value.trimStart()
                            } else if (prev != null && env.trimBlocks && prev.kind != Tag.Kind.VAR && prev.close != '+' && !previousRawBegin) {
                                if (value.startsWith('\n')) value = value.substring(1)
                            }
                            text = StringBuilder()
                            textStart = token.start
                        }
                        text!!.append(value)
                        textEnd = token.end
                    }
                    JinjaTokenKind.COMMENT -> {
                        val t = token.text
                        val tag = Tag(Tag.Kind.COMMENT, null, emptyList(), token.start, token.end, t.getOrNull(2)?.takeIf { it == '-' || it == '+' }, t.getOrNull(t.length - 3)?.takeIf { it == '-' || it == '+' })
                        flush(tag)
                        lineStart = false
                        previous = tag
                        previousRawBegin = false
                    }
                    JinjaTokenKind.VAR_START, JinjaTokenKind.BLOCK_START -> {
                        val closeKind = if (token.kind == JinjaTokenKind.VAR_START) JinjaTokenKind.VAR_END else JinjaTokenKind.BLOCK_END
                        var j = i + 1
                        while (j < tokens.size && tokens[j].kind != closeKind && tokens[j].kind != JinjaTokenKind.TEXT &&
                            tokens[j].kind != JinjaTokenKind.VAR_START && tokens[j].kind != JinjaTokenKind.BLOCK_START
                        ) j++
                        val closed = j < tokens.size && tokens[j].kind == closeKind
                        val closeToken = if (closed) tokens[j] else null
                        val inner = tokens.subList(i + 1, if (closed) j else j)
                        val isVar = token.kind == JinjaTokenKind.VAR_START
                        val tag = Tag(
                            if (isVar) Tag.Kind.VAR else Tag.Kind.BLOCK,
                            if (isVar) null else inner.firstOrNull()?.text,
                            if (isVar) inner else inner.drop(1),
                            token.start,
                            closeToken?.end ?: (inner.lastOrNull()?.end ?: token.end),
                            token.text.getOrNull(2)?.takeIf { it == '-' || it == '+' },
                            closeToken?.text?.firstOrNull()?.takeIf { it == '-' || it == '+' },
                        )
                        flush(tag)
                        lineStart = false
                        if (!closed) {
                            items += TNode.Problem("unexpected end of template, expected '${if (isVar) "}}" else "%}"}'", false, tag.start, tag.end)
                        } else if (tag.kind == Tag.Kind.BLOCK && (tag.keyword == "raw" || tag.keyword == "endraw")) {
                            // raw delimiters only frame verbatim text; trim_blocks applies after `endraw` only
                        } else {
                            items += tag
                        }
                        previousRawBegin = tag.kind == Tag.Kind.BLOCK && tag.keyword == "raw"
                        previous = tag
                        i = if (closed) j else j - 1
                    }
                    else -> {
                        flush(null)
                        items += TNode.Problem("unexpected '${token.text}'", false, token.start, token.end)
                    }
                }
                i++
            }
            flush(null)
        }

        /** Builds nested nodes from [items]. */
        private inner class Reader(private val items: List<Any>) {
            var pos = 0
            val problems = ArrayList<TNode.Problem>()

            fun hasMore() = pos < items.size

            fun next(): Any = items[pos++]

            private fun peekTag(): Tag? = items.getOrNull(pos) as? Tag

            /** Nodes up to (not including) a block tag whose keyword is in [stop]. */
            fun nodes(stop: kotlin.collections.Set<String>): MutableList<TNode> {
                val out = ArrayList<TNode>()
                while (pos < items.size) {
                    val item = items[pos]
                    if (item is Tag && item.kind == Tag.Kind.BLOCK && item.keyword in stop) return out
                    pos++
                    when (item) {
                        is TNode -> out += item
                        is Tag -> out += node(item)
                    }
                }
                return out
            }

            private fun node(tag: Tag): TNode = try {
                if (tag.kind == Tag.Kind.VAR) output(tag) else statement(tag)
            } catch (e: JinjaExprParser.SyntaxError) {
                TNode.Problem("template syntax error in '${tag.keyword ?: "{{ }}"}' tag", false, tag.start, tag.end)
            } catch (e: NumberFormatException) {
                TNode.Problem("template syntax error in '${tag.keyword ?: "{{ }}"}' tag", false, tag.start, tag.end)
            }

            private fun output(tag: Tag): TNode {
                if (tag.inner.isEmpty()) return TNode.Output(null, tag.start, tag.end, "Expected an expression, got 'end of print statement'")
                val parser = cursor(tag.inner)
                val expr = parser.topLevel()
                if (!parser.atEnd) throw JinjaExprParser.SyntaxError()
                return TNode.Output(expr, tag.start, tag.end)
            }

            private fun cursor(tokens: List<JinjaToken>) = JinjaExprParser.cursor(tokens).also { it.rawStrings = rawStrings; it.escapedQuoteEnds = escapedQuoteEnds }

            /** The body up to one of [ends]; returns the body and the closing tag (null at the end of the template). */
            private fun body(vararg ends: String): Pair<MutableList<TNode>, Tag?> {
                val body = nodes(ends.toSet())
                if (!hasMore()) return body to null
                return body to (next() as Tag)
            }

            private fun unclosed(tag: Tag, end: String): TNode.Problem =
                TNode.Problem("Unexpected end of template. Jinja was looking for the following tags: '$end'.", false, tag.start, tag.end)

            private fun statement(tag: Tag): TNode {
                val p = cursor(tag.inner)
                return when (tag.keyword) {
                    "if" -> ifNode(tag, p)
                    "for" -> forNode(tag, p)
                    "set" -> setNode(tag, p)
                    "macro" -> macroNode(tag, p)
                    "call" -> callNode(tag, p)
                    "filter" -> {
                        val filters = filterChain(tag.inner)
                        val (body, end) = body("endfilter")
                        if (end == null) problems += unclosed(tag, "endfilter")
                        TNode.FilterBlock(filters, body, tag.start, end?.end ?: tag.end)
                    }
                    "with" -> {
                        val assignments = ArrayList<Pair<String, JinjaExpr>>()
                        while (!p.atEnd) {
                            if (assignments.isNotEmpty()) p.expectOp(",")
                            val name = p.next().takeIf { it.kind == JinjaTokenKind.NAME }?.text ?: throw JinjaExprParser.SyntaxError()
                            p.expectOp("=")
                            assignments += name to p.condExpr()
                        }
                        val (body, end) = body("endwith")
                        if (end == null) problems += unclosed(tag, "endwith")
                        TNode.With(assignments, body, tag.start, end?.end ?: tag.end)
                    }
                    "include" -> {
                        val template = p.condExpr()
                        var ignoreMissing = false
                        if (p.isWord("ignore") && p.isWord("missing", 1)) {
                            p.pos += 2
                            ignoreMissing = true
                        }
                        val withContext = context(p, default = true)
                        end(p)
                        TNode.Include(template, ignoreMissing, withContext, tag.start, tag.end)
                    }
                    "import" -> {
                        val template = p.condExpr()
                        if (!p.skipWord("as")) throw JinjaExprParser.SyntaxError()
                        val alias = p.next().text
                        val withContext = context(p, default = false)
                        end(p)
                        TNode.Import(template, alias, withContext, tag.start, tag.end)
                    }
                    "from" -> {
                        val template = p.condExpr()
                        if (!p.skipWord("import")) throw JinjaExprParser.SyntaxError()
                        val names = ArrayList<Pair<String, String>>()
                        var withContext = false
                        while (!p.atEnd) {
                            if ((p.isWord("with") || p.isWord("without")) && p.isWord("context", 1)) {
                                withContext = p.next().text == "with"
                                p.pos++
                                break
                            }
                            if (names.isNotEmpty()) p.expectOp(",")
                            if (p.atEnd) break
                            val name = p.next().text
                            val alias = if (p.skipWord("as")) p.next().text else name
                            names += name to alias
                        }
                        end(p)
                        TNode.FromImport(template, names, withContext, tag.start, tag.end)
                    }
                    "extends", "block" -> {
                        if (tag.keyword == "block") body("endblock")
                        TNode.Problem("template inheritance ({% ${tag.keyword} %})", true, tag.start, tag.end)
                    }
                    "do", "break", "continue", "trans", "debug" -> TNode.Problem("Encountered unknown tag '${tag.keyword}'.", false, tag.start, tag.end)
                    else -> TNode.Problem("Encountered unknown tag '${tag.keyword}'.", false, tag.start, tag.end)
                }
            }

            private fun end(p: JinjaExprParser) {
                if (!p.atEnd) throw JinjaExprParser.SyntaxError()
            }

            private fun context(p: JinjaExprParser, default: Boolean): Boolean {
                if ((p.isWord("with") || p.isWord("without")) && p.isWord("context", 1)) {
                    val with = p.next().text == "with"
                    p.pos++
                    return with
                }
                return default
            }

            private fun ifNode(tag: Tag, p: JinjaExprParser): TNode {
                val branches = ArrayList<TNode.Branch>()
                var condition = p.topLevel().also { end(p) }
                var branchStart = tag.start
                var otherwise: List<TNode>? = null
                var closeEnd = tag.end
                while (true) {
                    val (body, close) = body("elif", "else", "endif")
                    if (close == null) {
                        branches += TNode.Branch(condition, body, branchStart, tag.end)
                        problems += unclosed(tag, "endif")
                        return TNode.If(branches, otherwise, tag.start, tag.end)
                    }
                    branches += TNode.Branch(condition, body, branchStart, close.start)
                    when (close.keyword) {
                        "elif" -> {
                            val cp = cursor(close.inner)
                            condition = cp.topLevel().also { end(cp) }
                            branchStart = close.start
                        }
                        "else" -> {
                            val (elseBody, endTag) = body("endif")
                            otherwise = elseBody
                            if (endTag == null) problems += unclosed(tag, "endif")
                            closeEnd = endTag?.end ?: close.end
                            break
                        }
                        else -> {
                            closeEnd = close.end
                            break
                        }
                    }
                }
                return TNode.If(branches, otherwise, tag.start, closeEnd)
            }

            private fun forNode(tag: Tag, p: JinjaExprParser): TNode {
                val targets = ArrayList<String>()
                val parenthesised = p.skipOp("(")
                while (true) {
                    val token = p.next()
                    if (token.kind != JinjaTokenKind.NAME) throw JinjaExprParser.SyntaxError()
                    targets += token.text
                    if (!p.skipOp(",")) break
                    if (p.isKeyword("in") || p.isOp(")")) break
                }
                if (parenthesised) p.expectOp(")")
                if (!p.skipKeyword("in")) throw JinjaExprParser.SyntaxError()
                val iter = p.tuple(withCondExpr = false)
                val filter = if (p.skipKeyword("if")) p.condExpr() else null
                val recursive = p.skipWord("recursive")
                end(p)
                val (body, close) = body("else", "endfor")
                var otherwise: List<TNode>? = null
                var closeEnd = close?.end ?: tag.end
                if (close == null) {
                    problems += unclosed(tag, "endfor")
                } else if (close.keyword == "else") {
                    val (elseBody, endTag) = body("endfor")
                    otherwise = elseBody
                    if (endTag == null) problems += unclosed(tag, "endfor")
                    closeEnd = endTag?.end ?: close.end
                }
                return TNode.For(targets, iter, filter, recursive, body, otherwise, tag.start, closeEnd)
            }

            private fun setTarget(p: JinjaExprParser): TNode.SetTarget {
                val name = p.next().takeIf { it.kind == JinjaTokenKind.NAME }?.text ?: throw JinjaExprParser.SyntaxError()
                if (p.skipOp(".")) return TNode.SetTarget(name, p.next().text)
                return TNode.SetTarget(name)
            }

            private fun setNode(tag: Tag, p: JinjaExprParser): TNode {
                val targets = arrayListOf(setTarget(p))
                while (p.skipOp(",")) targets += setTarget(p)
                if (p.skipOp("=")) {
                    val value = p.topLevel()
                    end(p)
                    return TNode.Set(targets, value, tag.start, tag.end)
                }
                if (targets.size != 1) throw JinjaExprParser.SyntaxError()
                val filters = if (p.isOp("|")) filterChain(tag.inner.subList(p.pos + 1, tag.inner.size)) else null
                if (filters == null) end(p)
                val (body, close) = body("endset")
                if (close == null) problems += unclosed(tag, "endset")
                return TNode.SetBlock(targets.single(), filters, body, tag.start, close?.end ?: tag.end)
            }

            /** `f | g(1)` as filters on [TNode.CAPTURE]. */
            private fun filterChain(tokens: List<JinjaToken>): JinjaExpr {
                val at = tokens.firstOrNull()?.start ?: 0
                val synthetic = listOf(JinjaToken(JinjaTokenKind.NAME, TNode.CAPTURE, at), JinjaToken(JinjaTokenKind.OPERATOR, "|", at)) + tokens
                val parser = cursor(synthetic)
                val expr = parser.topLevel()
                if (!parser.atEnd) throw JinjaExprParser.SyntaxError()
                return expr
            }

            private fun params(p: JinjaExprParser): List<TNode.Param> {
                val params = ArrayList<TNode.Param>()
                p.expectOp("(")
                while (!p.isOp(")")) {
                    if (params.isNotEmpty()) {
                        p.expectOp(",")
                        if (p.isOp(")")) break
                    }
                    val name = p.next().takeIf { it.kind == JinjaTokenKind.NAME }?.text ?: throw JinjaExprParser.SyntaxError()
                    val default = if (p.skipOp("=")) p.condExpr() else null
                    params += TNode.Param(name, default)
                }
                p.expectOp(")")
                return params
            }

            private fun macroNode(tag: Tag, p: JinjaExprParser): TNode {
                val name = p.next().takeIf { it.kind == JinjaTokenKind.NAME }?.text ?: throw JinjaExprParser.SyntaxError()
                val params = if (p.isOp("(")) params(p) else emptyList()
                end(p)
                val (body, close) = body("endmacro")
                if (close == null) problems += unclosed(tag, "endmacro")
                return TNode.Macro(name, params, body, tag.start, close?.end ?: tag.end)
            }

            private fun callNode(tag: Tag, p: JinjaExprParser): TNode {
                val params = if (p.isOp("(")) params(p) else emptyList()
                val call = p.condExpr()
                end(p)
                val (body, close) = body("endcall")
                if (close == null) problems += unclosed(tag, "endcall")
                return TNode.CallBlock(call, params, body, tag.start, close?.end ?: tag.end)
            }
        }
    }
}
