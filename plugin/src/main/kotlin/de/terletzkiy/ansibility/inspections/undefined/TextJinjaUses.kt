package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefs
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaRefsResult
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaVarRef

/**
 * The text-level fallback of [PsiJinjaUses] for Jinja text that has no Ansible Jinja PSI (a `.j2` file another file
 * type owns, a YAML scalar the platform has not injected): references, locals and `is defined` guards from
 * [JinjaRefs.analyze] (the shared text analysis of the `ansible.var.use` index and the text locator), plus what the
 * shared analysis does not record, read from the tokens with the same lexer: the facts of conditions that are
 * constant while the variable is undefined (`{% if x | default(false) %}`, through [JinjaTokenExpressions] and
 * [ConditionFacts], per `if`/`elif`/`else` branch and inline `and`/`or`/`if`), `| mandatory`, tolerant type tests (up
 * to 2.18), a `default` later in a filter chain (from 2.19), `default(…)` arguments, `for` iterables and the tag to
 * wrap. It follows the rules of [PsiJinjaUses] (a parity test pins that on the fixture).
 *
 * Deliberately left out (FU2): the members read by name ([JinjaRefsResult.indirectReferences]). A `hostvars[h].x` or
 * `map('extract', hostvars, 'x')` member is some host's variable, and even `hostvars[inventory_hostname]` lacks play
 * and role variables, so it is never a use of the current host's `x`. `vars['x']` and `lookup('vars', 'x')` do read the
 * current host's `x`, but under other rules (only a `default=` argument guards the lookup, `| default` does not) and
 * the PSI front end has no such shapes, so judging them here would break the parity. A braced implicit expression is
 * analysed as the template it is first rendered as; its outside names ([JinjaRefs.analyzeBracedExpression]) are
 * indexed for usages but not checked, for the same parity reason.
 */
internal object TextJinjaUses {
    /** The uses of [text] analysed in [mode] (a template, or one bare expression), in source order. */
    fun collect(text: CharSequence, mode: JinjaLexMode, rules: GuardRules): List<RawUse> {
        val analysis = JinjaRefs.analyze(text, mode)
        if (analysis.references.isEmpty()) return emptyList()
        val tokens = JinjaTokenExpressions.tokens(text, mode)
        val starts = IntArray(tokens.size) { tokens[it].start }
        val bodies = conditionalBodies(tokens)
        val facts = TagFacts(text, tokens, mode, rules)
        val uses = ArrayList<RawUse>()
        for (reference in analysis.references) {
            ProgressManager.checkCanceled()
            if (reference.called) continue
            val index = starts.binarySearch(reference.nameRange.startOffset)
            if (index < 0) continue
            use(text, tokens, index, reference, rules, mode, bodies, facts)?.let(uses::add)
        }
        return uses
    }

    private fun use(
        text: CharSequence,
        tokens: List<JinjaTok>,
        index: Int,
        reference: JinjaVarRef,
        rules: GuardRules,
        mode: JinjaLexMode,
        bodies: List<TextRange>,
        facts: TagFacts,
    ): RawUse? {
        val after = chainEnd(tokens, index)
        val chainEnd = tokens[after - 1].end
        // like the PSI path, look through parentheses that hold exactly the access: `(x) | default('')`
        var first = index
        var next = after
        while (tokens.getOrNull(first - 1)?.type == T.LPAREN && tokens.getOrNull(next)?.type == T.RPAREN) {
            first--
            next++
        }
        var guarded = reference.guarded || reference.guardedByCondition || reference.name in facts.at(index)
        val following = tokens.getOrNull(next)
        var mandatory = false
        when (following?.type) {
            T.IS_KEYWORD -> {
                val nameIndex = if (tokens.getOrNull(next + 1)?.type == T.NOT_KEYWORD) next + 2 else next + 1
                val test = tokens.getOrNull(nameIndex)?.takeIf { it.type == T.TEST_NAME && tokens.getOrNull(nameIndex + 1)?.type != T.DOT }
                val name = test?.let { text(text, it) }
                if (rules.typeTestsTolerateUndefined && name in GuardRules.TOLERANT_TESTS) return null
                if (name in GuardRules.DEFINED_TESTS) guarded = true
            }
            T.PIPE -> {
                val name = filterName(text, tokens, next + 1).first
                mandatory = name in GuardRules.MANDATORY_FILTERS
                if (name in GuardRules.DEFAULT_FILTERS) guarded = true
                if (rules.undefinedPassesFilters && defaultInChain(text, tokens, next)) guarded = true
            }
        }
        if (inDefaultArgument(text, tokens, index)) guarded = true
        return RawUse(
            name = reference.name,
            nameRange = reference.nameRange,
            pathRange = reference.range,
            chainEnd = chainEnd,
            guarded = guarded,
            mandatory = mandatory,
            iterable = isForIterable(tokens, first, next),
            conditional = bodies.any { it.containsOffset(reference.nameRange.startOffset) } || inlineConditional(tokens, first, next),
            statement = if (mode == JinjaLexMode.TEMPLATE) singleTag(text, tokens, index) else null,
        )
    }

    /** The index after the postfix chain (`.name`, `[…]`, `(…)`) of the name at [index]. */
    private fun chainEnd(tokens: List<JinjaTok>, index: Int): Int {
        var j = index + 1
        while (j < tokens.size) {
            j = when (tokens[j].type) {
                T.DOT -> {
                    val segment = tokens.getOrNull(j + 1)?.type
                    if (segment == T.IDENTIFIER || segment == T.INTEGER) j + 2 else return j
                }
                T.LBRACKET, T.LPAREN -> matchingClose(tokens, j).let { if (it < 0) return tokens.size else it + 1 }
                else -> return j
            }
        }
        return j
    }

    /**
     * The bodies that render only when something holds: of `{% if %}` (all branches, `elif` conditions included),
     * `{% for %}` (body and `else`), `{% macro %}` and `{% call %}`, from the end of the opening tag to the start of the
     * matching end tag (the text end when it is missing).
     */
    private fun conditionalBodies(tokens: List<JinjaTok>): List<TextRange> {
        val bodies = ArrayList<TextRange>()
        val open = ArrayList<Pair<IElementType, Int>>()
        for (i in tokens.indices) {
            if (tokens[i].type != T.BLOCK_START) continue
            val keyword = tokens.getOrNull(i + 1)?.type ?: continue
            OPENERS[keyword]?.let { end ->
                val tagEnd = (i + 1 until tokens.size).firstOrNull { tokens[it].type == T.BLOCK_END }?.let { tokens[it].end } ?: return@let
                open += end to tagEnd
            }
            if (keyword in OPENERS.values) {
                val match = open.indexOfLast { it.first == keyword }
                if (match >= 0) {
                    bodies += TextRange(open[match].second, tokens[i].start)
                    while (open.size > match) open.removeAt(open.lastIndex)
                }
            }
        }
        val end = tokens.lastOrNull()?.end ?: 0
        open.forEach { (_, start) -> if (start <= end) bodies += TextRange(start, end) }
        return bodies
    }

    /**
     * Whether the access `tokens[first, next)` sits in a branch of an inline `if` or on the right of `and`/`or` within its
     * tag, the way the PSI nests those operators: siblings in an argument list, tuple, list or dict (after a `,` or `:`)
     * do not count, enclosing groups do.
     */
    private fun inlineConditional(tokens: List<JinjaTok>, first: Int, next: Int): Boolean {
        var start = first
        while (start > 0 && tokens[start - 1].type != T.VAR_START && tokens[start - 1].type != T.BLOCK_START) start--
        val statement = tokens.getOrNull(start - 1)?.type == T.BLOCK_START
        val forTag = statement && tokens.getOrNull(start)?.type == T.FOR_KEYWORD
        val lower = if (statement) start + 1 else start
        var depth = 0
        var sibling = false
        for (k in first - 1 downTo lower) {
            when (tokens[k].type) {
                T.RPAREN, T.RBRACKET, T.RBRACE -> depth++
                T.LPAREN, T.LBRACKET, T.LBRACE -> if (depth > 0) depth-- else sibling = false
                T.COMMA, T.COLON -> if (depth == 0) sibling = true
                T.AND_KEYWORD, T.OR_KEYWORD, T.ELSE_KEYWORD -> if (depth == 0 && !sibling) return true
                T.IF_KEYWORD -> if (depth == 0) sibling = true
            }
        }
        if (forTag) return false
        depth = 0
        sibling = false
        for (k in next until tokens.size) {
            when (tokens[k].type) {
                T.VAR_END, T.BLOCK_END -> return false
                T.LPAREN, T.LBRACKET, T.LBRACE -> depth++
                T.RPAREN, T.RBRACKET, T.RBRACE -> if (depth > 0) depth-- else sibling = false
                T.COMMA, T.COLON -> if (depth == 0) sibling = true
                T.IF_KEYWORD -> if (depth == 0 && !sibling) return true
            }
        }
        return false
    }

    /** The dotted filter name starting at [at] (`ansible.builtin.mandatory`) and the index after it. */
    private fun filterName(text: CharSequence, tokens: List<JinjaTok>, at: Int): Pair<String?, Int> {
        if (tokens.getOrNull(at)?.type != T.FILTER_NAME) return null to at
        val name = StringBuilder(text(text, tokens[at]))
        var k = at + 1
        while (tokens.getOrNull(k)?.type == T.DOT && tokens.getOrNull(k + 1)?.type == T.FILTER_NAME) {
            name.append('.').append(text(text, tokens[k + 1]))
            k += 2
        }
        return name.toString() to k
    }

    /** `| f(…) | g | default(…)` from the pipe at [pipe]: a plain `default`/`d` somewhere along the chain. */
    private fun defaultInChain(text: CharSequence, tokens: List<JinjaTok>, pipe: Int): Boolean {
        var k = pipe
        while (tokens.getOrNull(k)?.type == T.PIPE) {
            val (name, after) = filterName(text, tokens, k + 1)
            if (name == null) return false
            if (name in GuardRules.DEFAULT_FILTERS) return true
            k = after
            if (tokens.getOrNull(k)?.type == T.LPAREN) k = matchingClose(tokens, k).let { if (it < 0) return false else it + 1 }
        }
        return false
    }

    /** Whether the token at [index] is inside the parentheses of a plain `| default(…)`/`| d(…)` of its tag. */
    private fun inDefaultArgument(text: CharSequence, tokens: List<JinjaTok>, index: Int): Boolean {
        var depth = 0
        var k = index - 1
        while (k >= 0) {
            when (tokens[k].type) {
                T.VAR_START, T.BLOCK_START, T.VAR_END, T.BLOCK_END -> return false
                T.RPAREN, T.RBRACKET, T.RBRACE -> depth++
                T.LBRACKET, T.LBRACE -> if (depth > 0) depth--
                T.LPAREN -> if (depth > 0) {
                    depth--
                } else {
                    val name = tokens.getOrNull(k - 1)
                    val plain = name?.type == T.FILTER_NAME && tokens.getOrNull(k - 2)?.type == T.PIPE
                    if (plain && text(text, name) in GuardRules.DEFAULT_FILTERS) return true
                }
            }
            k--
        }
        return false
    }

    /** `{% for … in <use> %}`: the token before the use is `in`, the chain ends the iterable, and the tag is a `for`. */
    private fun isForIterable(tokens: List<JinjaTok>, index: Int, after: Int): Boolean {
        if (tokens.getOrNull(index - 1)?.type != T.IN_KEYWORD) return false
        val end = tokens.getOrNull(after)?.type
        if (end != T.BLOCK_END && end != T.IF_KEYWORD && end != T.RECURSIVE_KEYWORD) return false
        var k = index - 1
        while (k >= 0 && tokens[k].type != T.BLOCK_START) k--
        return tokens.getOrNull(k + 1)?.type == T.FOR_KEYWORD
    }

    /**
     * The tag around the token at [index] when it is a whole statement of its own: an output tag, or a statement tag
     * that opens no block (`set x = …`, `do`, `include`, `import`, `from`, `extends`). Null inside block tags, whose
     * end tags the text level does not pair.
     */
    private fun singleTag(text: CharSequence, tokens: List<JinjaTok>, index: Int): TextRange? {
        var start = index
        while (start >= 0 && tokens[start].type != T.VAR_START && tokens[start].type != T.BLOCK_START) {
            if (tokens[start].type == T.VAR_END || tokens[start].type == T.BLOCK_END) return null
            start--
        }
        if (start < 0) return null
        var end = index
        while (end < tokens.size && tokens[end].type != T.VAR_END && tokens[end].type != T.BLOCK_END) end++
        if (end >= tokens.size) return null
        if (tokens[start].type == T.BLOCK_START) {
            val keyword = tokens.getOrNull(start + 1)?.type
            val single = keyword in SINGLE_TAG_KEYWORDS && (keyword != T.SET_KEYWORD || (start + 1 until end).any { tokens[it].type == T.ASSIGN })
            if (!single) return null
        }
        return TextRange(tokens[start].start, tokens[end].end).takeIf { it.endOffset <= text.length }
    }

    private fun matchingClose(tokens: List<JinjaTok>, open: Int): Int {
        var depth = 0
        for (i in open until tokens.size) {
            when (tokens[i].type) {
                T.LPAREN, T.LBRACKET, T.LBRACE -> depth++
                T.RPAREN, T.RBRACKET, T.RBRACE -> {
                    depth--
                    if (depth == 0) return i
                }
                T.VAR_END, T.BLOCK_END -> return -1
            }
        }
        return -1
    }

    private fun text(text: CharSequence, tok: JinjaTok): String = text.subSequence(tok.start, tok.end).toString()

    /** Block openers whose bodies are conditional, with their end keywords. */
    private val OPENERS: Map<IElementType, IElementType> = mapOf(
        T.IF_KEYWORD to T.ENDIF_KEYWORD,
        T.FOR_KEYWORD to T.ENDFOR_KEYWORD,
        T.MACRO_KEYWORD to T.ENDMACRO_KEYWORD,
        T.CALL_KEYWORD to T.ENDCALL_KEYWORD,
    )

    private val SINGLE_TAG_KEYWORDS: Set<IElementType> = setOf(
        T.SET_KEYWORD, T.DO_KEYWORD, T.INCLUDE_KEYWORD, T.IMPORT_KEYWORD, T.FROM_KEYWORD, T.EXTENDS_KEYWORD,
    )
}

/**
 * The definedness facts [TextJinjaUses] adds to `JinjaRefs`' guards, per tag of a text: the facts of the enclosing
 * `{% if %}`/`{% elif %}`/`{% else %}` branches (a macro body does not inherit the conditions around the macro, as in
 * [PsiJinjaUses]) and of the inline `and`/`or`/`if` around a token inside its tag, all through [ConditionFacts].
 */
private class TagFacts(
    private val text: CharSequence,
    private val tokens: List<JinjaTok>,
    private val mode: JinjaLexMode,
    private val rules: GuardRules,
) {
    /** One tag: its delimiter-free token range and the branch facts of the statements around it. */
    private class Tag(val from: Int, val to: Int, val facts: Set<String>) {
        var expressions: List<JinjaTokenExpressions.Node>? = null
    }

    /** An open block statement; [branch] holds the facts of its current `if` branch. */
    private class Frame(val keyword: IElementType) {
        val conditions = ArrayList<Cond>()
        var branch: Set<String> = emptySet()
    }

    private val tags: List<Tag> = if (mode == JinjaLexMode.EXPRESSION) listOf(Tag(0, tokens.size, emptySet())) else templateTags()
    private val starts = IntArray(tags.size) { tags[it].from }

    /** The names the conditions around the token at [index] prove defined. */
    fun at(index: Int): Set<String> {
        val position = starts.binarySearch(index).let { if (it >= 0) it else -it - 2 }
        val tag = tags.getOrNull(position)?.takeIf { index in it.from until it.to } ?: return emptySet()
        val expressions = tag.expressions ?: JinjaTokenExpressions.expressionsOf(text, tokens, tag.from, tag.to).also { tag.expressions = it }
        val node = expressions.firstOrNull { index in it } ?: return tag.facts
        val inline = JinjaTokenExpressions.inlineFacts(node, index, rules)
        return if (inline.isEmpty()) tag.facts else tag.facts + inline
    }

    private fun templateTags(): List<Tag> {
        val result = ArrayList<Tag>()
        val frames = ArrayList<Frame>()
        var i = 0
        while (i < tokens.size) {
            ProgressManager.checkCanceled()
            val type = tokens[i].type
            if (type != T.VAR_START && type != T.BLOCK_START) {
                i++
                continue
            }
            val close = if (type == T.VAR_START) T.VAR_END else T.BLOCK_END
            var end = i + 1
            while (end < tokens.size && tokens[end].type != close && tokens[end].type != T.VAR_START && tokens[end].type != T.BLOCK_START) end++
            val from = i + 1
            if (type == T.VAR_START) {
                result += Tag(from, end, outer(frames))
            } else {
                statement(from, end, frames)?.let(result::add)
            }
            i = if (end < tokens.size && tokens[end].type == close) end + 1 else end
        }
        return result
    }

    /** Updates [frames] for the statement tag `[from, to)` and returns the tag (null for tags without expressions). */
    private fun statement(from: Int, to: Int, frames: MutableList<Frame>): Tag? {
        if (from >= to) return null
        val keyword = tokens[from].type
        return when (keyword) {
            T.IF_KEYWORD -> {
                val tag = Tag(from, to, outer(frames))
                val condition = condition(from + 1, to)
                frames += Frame(T.IF_KEYWORD).apply {
                    conditions += condition
                    branch = ConditionFacts.of(condition, rules).whenTrue
                }
                tag
            }
            T.ELIF_KEYWORD -> {
                val frame = frames.lastOrNull()?.takeIf { it.keyword == T.IF_KEYWORD } ?: return Tag(from, to, outer(frames))
                val earlier = falseFacts(frame)
                val tag = Tag(from, to, outer(frames.subList(0, frames.size - 1)) + earlier)
                val condition = condition(from + 1, to)
                frame.conditions += condition
                frame.branch = earlier + ConditionFacts.of(condition, rules).whenTrue
                tag
            }
            T.ELSE_KEYWORD -> {
                frames.lastOrNull()?.takeIf { it.keyword == T.IF_KEYWORD }?.let { it.branch = falseFacts(it) }
                null
            }
            in CLOSERS -> {
                val opener = CLOSERS.getValue(keyword)
                val match = frames.indexOfLast { it.keyword == opener }
                if (match >= 0) while (frames.size > match) frames.removeAt(frames.lastIndex)
                null
            }
            else -> {
                val tag = Tag(from, to, outer(frames))
                val opens = keyword in BLOCK_OPENERS && (keyword != T.SET_KEYWORD || (from + 1 until to).none { tokens[it].type == T.ASSIGN })
                if (opens) frames += Frame(keyword)
                tag
            }
        }
    }

    private fun condition(from: Int, to: Int): Cond =
        JinjaTokenExpressions.expression(text, tokens, from, to)?.let(JinjaTokenExpressions::cond) ?: Cond.Opaque

    /** What reaching a later branch of [frame] proves: each of its conditions so far was false. */
    private fun falseFacts(frame: Frame): Set<String> = frame.conditions.flatMapTo(HashSet()) { ConditionFacts.of(it, rules).whenFalse }

    /** The branch facts of [frames] from the innermost one out to the nearest macro (whose body does not inherit). */
    private fun outer(frames: List<Frame>): Set<String> {
        val facts = HashSet<String>()
        for (frame in frames.asReversed()) {
            if (frame.keyword == T.MACRO_KEYWORD) break
            facts += frame.branch
        }
        return facts
    }

    private companion object {
        /** End keywords and the block keyword each closes. */
        val CLOSERS: Map<IElementType, IElementType> = mapOf(
            T.ENDIF_KEYWORD to T.IF_KEYWORD, T.ENDFOR_KEYWORD to T.FOR_KEYWORD, T.ENDMACRO_KEYWORD to T.MACRO_KEYWORD,
            T.ENDCALL_KEYWORD to T.CALL_KEYWORD, T.ENDFILTER_KEYWORD to T.FILTER_KEYWORD, T.ENDWITH_KEYWORD to T.WITH_KEYWORD,
            T.ENDBLOCK_KEYWORD to T.BLOCK_KEYWORD, T.ENDSET_KEYWORD to T.SET_KEYWORD, T.ENDRAW_KEYWORD to T.RAW_KEYWORD,
        )

        /** Keywords whose tag opens a block (`set` only without `=`). */
        val BLOCK_OPENERS: Set<IElementType> = setOf(
            T.FOR_KEYWORD, T.MACRO_KEYWORD, T.CALL_KEYWORD, T.FILTER_KEYWORD, T.WITH_KEYWORD, T.BLOCK_KEYWORD, T.SET_KEYWORD, T.RAW_KEYWORD,
        )
    }
}
