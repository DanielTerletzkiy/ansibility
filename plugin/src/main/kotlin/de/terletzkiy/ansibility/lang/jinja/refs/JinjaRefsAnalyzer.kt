package de.terletzkiy.ansibility.lang.jinja.refs

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaLexer
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

/**
 * The implementation of [JinjaRefs]: lexes once, groups the significant tokens of each `{{ }}` / `{% %}` tag, and walks
 * the tags in order with a stack of frames that mirrors Jinja's scopes (`for`, `macro`, `call`, `filter`, `with`,
 * `block` and block `set` open one; `if` does not, but carries the guards of its current branch). Each free `hostvars`,
 * `vars`, `lookup`, `query` or `q` is also checked for a member it reads by name ([JinjaIndirectRef]).
 *
 * [masked] are the ranges of [text] that [JinjaBracedExpressions] filled in for braced parts: a string literal that
 * overlaps one has no known value (no constant accessor, member name or lookup term). Every other string is taken as
 * written, whatever characters it holds.
 */
internal class JinjaRefsAnalyzer(
    private val text: CharSequence,
    private val mode: JinjaLexMode,
    private val masked: List<TextRange> = emptyList(),
) {
    private class Tok(val type: IElementType, val start: Int, val end: Int)

    private enum class TagKind { OUTPUT, STATEMENT, EXPRESSION }

    private class Tag(val kind: TagKind, val start: Int, val end: Int, val toks: List<Tok>)

    private enum class FrameKind { ROOT, IF, FOR, FOR_ELSE, MACRO, CALL, FILTER, WITH, BLOCK, SET_BLOCK }

    private class Frame(var kind: FrameKind) {
        val visible = HashMap<String, LocalBuilder>()
        val owned = ArrayList<LocalBuilder>()

        /** For IF frames: root names known to be defined in the current branch. */
        var branchGuards: Set<String> = emptySet()

        /** For IF frames: root names known to be defined once every condition so far was false. */
        var whenAllFalse: Set<String> = emptySet()

        /** For SET_BLOCK frames: the names bound at `{% endset %}`. */
        var pendingTargets: List<Tok> = emptyList()

        val scoping: Boolean get() = kind != FrameKind.IF
    }

    private class LocalBuilder(
        val name: String,
        val kind: JinjaLocalKind,
        val definitionRange: TextRange,
        val scopeStart: Int,
        val owner: String?,
        val frame: Frame,
    ) {
        var scopeEnd = -1
        lateinit var built: JinjaLocal
    }

    private class RefBuilder(
        val name: String,
        val path: List<String>,
        val nameRange: TextRange,
        val range: TextRange,
        val guarded: Boolean,
        val guardedByCondition: Boolean,
        val called: Boolean,
        val local: LocalBuilder?,
    )

    /**
     * What a condition tells about definedness when it is true and when it is false: root names, plus
     * [memberGuard] keys for tested `hostvars`/`vars` members (which never equal a name, so direct references are not
     * affected by them).
     */
    private class Guards(val whenTrue: Set<String>, val whenFalse: Set<String>) {
        fun negate() = Guards(whenFalse, whenTrue)
    }

    /** The postfix chain after a name: its constant [path] (ending at text offset [pathEnd]) and the index after it. */
    private class Chain(val path: List<String>, val pathEnd: Int, val called: Boolean, val end: Int)

    /** A constant member name (`.x`, `['x']` or a `'x'` argument): its text range and the token index after it. */
    private class MemberName(val name: String, val range: TextRange, val next: Int)

    private val frames = ArrayList<Frame>()
    private val localBuilders = ArrayList<LocalBuilder>()
    private val refBuilders = ArrayList<RefBuilder>()
    private val indirectRefs = ArrayList<JinjaIndirectRef>()
    private val filterSites = ArrayList<JinjaNameSite>()
    private val testSites = ArrayList<JinjaNameSite>()

    fun analyze(): JinjaRefsResult {
        frames += Frame(FrameKind.ROOT)
        for (tag in collectTags()) {
            ProgressManager.checkCanceled()
            when (tag.kind) {
                TagKind.OUTPUT, TagKind.EXPRESSION -> expression(tag.toks, 0, tag.toks.size, activeGuards())
                TagKind.STATEMENT -> statement(tag)
            }
            collectNameSites(tag.toks)
        }
        while (frames.isNotEmpty()) closeLocals(frames.removeAt(frames.lastIndex), text.length)

        val locals = localBuilders.map { builder ->
            val end = maxOf(builder.scopeStart, builder.scopeEnd)
            JinjaLocal(builder.name, builder.kind, builder.definitionRange, TextRange(builder.scopeStart, end), builder.owner)
                .also { builder.built = it }
        }
        val refs = refBuilders.sortedBy { it.nameRange.startOffset }.map {
            JinjaVarRef(it.name, it.path, it.nameRange, it.range, it.guarded, it.guardedByCondition, it.called, it.local?.built)
        }
        return JinjaRefsResult(
            references = refs.filter { it.local == null },
            localReferences = refs.filter { it.local != null },
            locals = locals,
            filterNames = filterSites.sortedBy { it.range.startOffset },
            testNames = testSites.sortedBy { it.range.startOffset },
            indirectReferences = indirectRefs.sortedBy { it.nameRange.startOffset },
        )
    }

    // ------------------------------------------------------------------------------------------------ tags

    private fun collectTags(): List<Tag> {
        val lexer = AnsibleJinjaLexer(mode)
        lexer.start(text)
        val tags = ArrayList<Tag>()
        if (mode == JinjaLexMode.EXPRESSION) {
            val toks = ArrayList<Tok>()
            while (true) {
                val type = lexer.tokenType ?: break
                if (type != T.WHITE_SPACE) toks += Tok(type, lexer.tokenStart, lexer.tokenEnd)
                lexer.advance()
            }
            tags += Tag(TagKind.EXPRESSION, 0, text.length, toks)
            return tags
        }
        var current: ArrayList<Tok>? = null
        var kind = TagKind.OUTPUT
        var start = 0
        while (true) {
            val type = lexer.tokenType ?: break
            when (type) {
                T.VAR_START, T.BLOCK_START -> {
                    current = ArrayList()
                    kind = if (type == T.VAR_START) TagKind.OUTPUT else TagKind.STATEMENT
                    start = lexer.tokenStart
                }
                T.VAR_END, T.BLOCK_END -> current?.let {
                    tags += Tag(kind, start, lexer.tokenEnd, it)
                    current = null
                }
                T.WHITE_SPACE, T.TEXT, T.RAW_TEXT, T.COMMENT -> Unit
                else -> current?.add(Tok(type, lexer.tokenStart, lexer.tokenEnd))
            }
            lexer.advance()
        }
        current?.let { tags += Tag(kind, start, text.length, it) }
        return tags
    }

    // ------------------------------------------------------------------------------------------------ statements

    private fun statement(tag: Tag) {
        val toks = tag.toks
        val n = toks.size
        if (n == 0) return
        val guards = activeGuards()
        when (toks[0].type) {
            T.IF_KEYWORD -> {
                expression(toks, 1, n, guards)
                val condition = conditionGuards(toks, 1, n)
                push(FrameKind.IF).apply {
                    branchGuards = condition.whenTrue
                    whenAllFalse = condition.whenFalse
                }
            }
            T.ELIF_KEYWORD -> {
                val frame = frames.last()
                if (frame.kind == FrameKind.IF) {
                    expression(toks, 1, n, activeGuards(skip = frame) + frame.whenAllFalse)
                    val condition = conditionGuards(toks, 1, n)
                    frame.branchGuards = frame.whenAllFalse + condition.whenTrue
                    frame.whenAllFalse = frame.whenAllFalse + condition.whenFalse
                } else {
                    expression(toks, 1, n, guards)
                }
            }
            T.ELSE_KEYWORD -> {
                val frame = frames.last()
                when (frame.kind) {
                    FrameKind.IF -> frame.branchGuards = frame.whenAllFalse
                    FrameKind.FOR -> {
                        // the loop's `else` runs in the outer scope: targets, `loop` and body sets end here
                        closeLocals(frame, tag.start)
                        frame.visible.clear()
                        frame.kind = FrameKind.FOR_ELSE
                    }
                    else -> Unit
                }
            }
            T.ENDIF_KEYWORD -> popTo(tag.start, FrameKind.IF)
            T.FOR_KEYWORD -> forStatement(tag, guards)
            T.ENDFOR_KEYWORD -> popTo(tag.start, FrameKind.FOR, FrameKind.FOR_ELSE)
            T.SET_KEYWORD -> setStatement(tag, guards)
            T.ENDSET_KEYWORD -> endSet(tag)
            T.MACRO_KEYWORD -> macroStatement(tag, guards)
            T.ENDMACRO_KEYWORD -> popTo(tag.start, FrameKind.MACRO)
            T.CALL_KEYWORD -> callStatement(tag, guards)
            T.ENDCALL_KEYWORD -> popTo(tag.start, FrameKind.CALL)
            T.FILTER_KEYWORD -> {
                expression(toks, 1, n, guards)
                push(FrameKind.FILTER)
            }
            T.ENDFILTER_KEYWORD -> popTo(tag.start, FrameKind.FILTER)
            T.WITH_KEYWORD -> withStatement(tag, guards)
            T.ENDWITH_KEYWORD -> popTo(tag.start, FrameKind.WITH)
            T.BLOCK_KEYWORD -> push(FrameKind.BLOCK)
            T.ENDBLOCK_KEYWORD -> popTo(tag.start, FrameKind.BLOCK)
            T.INCLUDE_KEYWORD, T.EXTENDS_KEYWORD -> expression(toks, 1, contextClauseStart(toks, 1, n), guards)
            T.IMPORT_KEYWORD -> importStatement(tag, guards)
            T.FROM_KEYWORD -> fromStatement(tag, guards)
            T.DO_KEYWORD -> expression(toks, 1, n, guards)
            T.RAW_KEYWORD, T.ENDRAW_KEYWORD, T.BREAK_KEYWORD, T.CONTINUE_KEYWORD -> Unit
            // an unknown tag (`{% trans %}`): its arguments are expressions
            T.IDENTIFIER -> expression(toks, 1, n, guards)
            else -> expression(toks, 0, n, guards)
        }
    }

    private fun forStatement(tag: Tag, guards: Set<String>) {
        val toks = tag.toks
        val n = toks.size
        val inIndex = findTopLevel(toks, 1, n, T.IN_KEYWORD)
        val targets = (1 until if (inIndex >= 0) inIndex else n).map { toks[it] }.filter { it.type == T.IDENTIFIER }
        var filterIndex = -1
        if (inIndex >= 0) {
            filterIndex = findTopLevel(toks, inIndex + 1, n, T.IF_KEYWORD)
            val recursiveIndex = findTopLevel(toks, inIndex + 1, n, T.RECURSIVE_KEYWORD)
            val iterableEnd = listOf(filterIndex, recursiveIndex).filter { it >= 0 }.minOrNull() ?: n
            // the iterable is evaluated in the outer scope
            expression(toks, inIndex + 1, iterableEnd, guards)
        }
        val frame = push(FrameKind.FOR)
        val targetScopeStart = if (filterIndex >= 0) toks[filterIndex].start else tag.end
        for (target in targets) define(frame, target, JinjaLocalKind.FOR_TARGET, targetScopeStart)
        if (filterIndex >= 0) {
            val recursiveIndex = findTopLevel(toks, filterIndex + 1, n, T.RECURSIVE_KEYWORD)
            expression(toks, filterIndex + 1, if (recursiveIndex >= 0) recursiveIndex else n, guards)
        }
        define(frame, "loop", JinjaLocalKind.LOOP, range(toks[0]), tag.end)
    }

    private fun setStatement(tag: Tag, guards: Set<String>) {
        val toks = tag.toks
        val n = toks.size
        val scope = nearestScope()
        val assign = findTopLevel(toks, 1, n, T.ASSIGN)
        if (assign < 0) {
            // block set: `{% set x | filter %}…{% endset %}` binds x after endset
            val pipe = findTopLevel(toks, 1, n, T.PIPE)
            val targets = (1 until if (pipe >= 0) pipe else n).map { toks[it] }.filter { it.type == T.IDENTIFIER }
            if (pipe >= 0) expression(toks, pipe, n, guards)
            push(FrameKind.SET_BLOCK).pendingTargets = targets
            return
        }
        // the value is evaluated before the names are bound: `{% set x = x + 1 %}` reads the outer x
        expression(toks, assign + 1, n, guards)
        var k = 1
        val names = ArrayList<Tok>()
        while (k < assign) {
            val tok = toks[k]
            if (tok.type == T.IDENTIFIER) {
                if (k + 2 < assign && toks[k + 1].type == T.DOT && toks[k + 2].type == T.IDENTIFIER) {
                    namespaceAttributeAssignment(tok, toks[k + 2], tag, guards)
                    k += 3
                    continue
                }
                names += tok
            }
            k++
        }
        for (name in names) define(scope, name, JinjaLocalKind.SET, tag.end)
        val single = names.singleOrNull()
        if (single != null && assign + 2 < n && toks[assign + 1].type == T.IDENTIFIER &&
            textOf(toks[assign + 1]) == "namespace" && toks[assign + 2].type == T.LPAREN
        ) {
            val open = assign + 2
            val close = matchingClose(toks, open, n).let { if (it < 0) n else it }
            var depth = 0
            for (i in open + 1 until close) {
                when (toks[i].type) {
                    T.LPAREN, T.LBRACKET, T.LBRACE -> depth++
                    T.RPAREN, T.RBRACKET, T.RBRACE -> if (depth > 0) depth--
                    T.IDENTIFIER -> if (depth == 0 && i + 1 < close && toks[i + 1].type == T.ASSIGN) {
                        define(
                            scope, textOf(toks[i]), JinjaLocalKind.NAMESPACE_ATTRIBUTE, range(toks[i]), tag.end,
                            owner = textOf(single), visible = false,
                        )
                    }
                }
            }
        }
    }

    /** `{% set ns.attr = … %}`: reads `ns` and adds `attr` to the namespace, visible wherever `ns` is. */
    private fun namespaceAttributeAssignment(ns: Tok, attr: Tok, tag: Tag, guards: Set<String>) {
        val name = textOf(ns)
        val nsLocal = lookup(name)
        refBuilders += RefBuilder(
            name = name, path = listOf(textOf(attr)), nameRange = range(ns), range = TextRange(ns.start, attr.end),
            guarded = false, guardedByCondition = name in guards, called = false, local = nsLocal,
        )
        define(
            nsLocal?.frame ?: frames.first(), textOf(attr), JinjaLocalKind.NAMESPACE_ATTRIBUTE, range(attr), tag.end,
            owner = name, visible = false,
        )
    }

    private fun endSet(tag: Tag) {
        val index = frames.indexOfLast { it.kind == FrameKind.SET_BLOCK }
        if (index <= 0) return
        val targets = frames[index].pendingTargets
        popTo(tag.start, FrameKind.SET_BLOCK)
        val scope = nearestScope()
        for (target in targets) define(scope, target, JinjaLocalKind.BLOCK_SET, tag.end)
    }

    private fun macroStatement(tag: Tag, guards: Set<String>) {
        val toks = tag.toks
        val n = toks.size
        val nameTok = toks.getOrNull(1)?.takeIf { it.type == T.IDENTIFIER }
        if (nameTok != null) define(nearestScope(), nameTok, JinjaLocalKind.MACRO, tag.start)
        val params = if (n > 2 && toks[2].type == T.LPAREN) parameters(toks, 2, n, guards).first else emptyList()
        val frame = push(FrameKind.MACRO)
        for (param in params) define(frame, param, JinjaLocalKind.MACRO_PARAMETER, tag.end)
        for (implicit in MACRO_IMPLICITS) define(frame, implicit, JinjaLocalKind.MACRO_IMPLICIT, range(toks[0]), tag.end)
    }

    private fun callStatement(tag: Tag, guards: Set<String>) {
        val toks = tag.toks
        val n = toks.size
        var params = emptyList<Tok>()
        var callStart = 1
        if (n > 1 && toks[1].type == T.LPAREN) {
            val (found, close) = parameters(toks, 1, n, guards)
            params = found
            callStart = close + 1
        }
        expression(toks, callStart, n, guards)
        val frame = push(FrameKind.CALL)
        for (param in params) define(frame, param, JinjaLocalKind.CALL_PARAMETER, tag.end)
    }

    /**
     * The parameter list `(a, b=default)` opening at [open]: the parameter names and the index of the closing
     * parenthesis. Default values are analysed as expressions of the enclosing scope.
     */
    private fun parameters(toks: List<Tok>, open: Int, n: Int, guards: Set<String>): Pair<List<Tok>, Int> {
        val close = matchingClose(toks, open, n).let { if (it < 0) n else it }
        val params = ArrayList<Tok>()
        for ((from, to) in splitTopLevel(toks, open + 1, close, T.COMMA)) {
            if (from >= to || toks[from].type != T.IDENTIFIER) {
                expression(toks, from, to, guards)
                continue
            }
            params += toks[from]
            if (from + 1 < to && toks[from + 1].type == T.ASSIGN) expression(toks, from + 2, to, guards)
        }
        return params to close
    }

    private fun withStatement(tag: Tag, guards: Set<String>) {
        val toks = tag.toks
        val names = ArrayList<Tok>()
        for ((from, to) in splitTopLevel(toks, 1, toks.size, T.COMMA)) {
            if (to - from >= 2 && toks[from].type == T.IDENTIFIER && toks[from + 1].type == T.ASSIGN) {
                names += toks[from]
                expression(toks, from + 2, to, guards)
            } else {
                expression(toks, from, to, guards)
            }
        }
        val frame = push(FrameKind.WITH)
        for (name in names) define(frame, name, JinjaLocalKind.WITH, tag.end)
    }

    private fun importStatement(tag: Tag, guards: Set<String>) {
        val toks = tag.toks
        val n = toks.size
        val asIndex = findTopLevel(toks, 1, n, T.AS_KEYWORD)
        expression(toks, 1, if (asIndex >= 0) asIndex else contextClauseStart(toks, 1, n), guards)
        val bound = toks.getOrNull(asIndex + 1)?.takeIf { asIndex >= 0 && it.type == T.IDENTIFIER } ?: return
        define(nearestScope(), bound, JinjaLocalKind.IMPORT, tag.end)
    }

    private fun fromStatement(tag: Tag, guards: Set<String>) {
        val toks = tag.toks
        val n = toks.size
        val importIndex = findTopLevel(toks, 1, n, T.IMPORT_KEYWORD)
        if (importIndex < 0) return expression(toks, 1, n, guards)
        expression(toks, 1, importIndex, guards)
        val scope = nearestScope()
        for ((from, to) in splitTopLevel(toks, importIndex + 1, contextClauseStart(toks, importIndex + 1, n), T.COMMA)) {
            if (from >= to) continue
            val bound = if (to - from >= 3 && toks[from + 1].type == T.AS_KEYWORD) toks[from + 2] else toks[from]
            if (bound.type == T.IDENTIFIER) define(scope, bound, JinjaLocalKind.IMPORT, tag.end)
        }
    }

    /** Where `ignore missing`, `with context` or `without context` starts in an include/import tag, else [to]. */
    private fun contextClauseStart(toks: List<Tok>, from: Int, to: Int): Int {
        for (i in from until to - 1) {
            val first = toks[i]
            val second = textOf(toks[i + 1])
            val firstText = if (first.type == T.IDENTIFIER || first.type == T.WITH_KEYWORD) textOf(first) else continue
            if (firstText == "ignore" && second == "missing") return i
            if ((firstText == "with" || firstText == "without") && second == "context") return i
        }
        return to
    }

    // ------------------------------------------------------------------------------------------------ expressions

    /**
     * Records the references in `toks[from, to)`. [guards] are root names known to be defined here; the function
     * splits inline `if`/`else`, `or` and `and` at the top level to learn more from the conditions it evaluates first.
     */
    private fun expression(toks: List<Tok>, from: Int, to: Int, guards: Set<String>) {
        var f = from
        var t = to
        while (t - f >= 2 && toks[f].type == T.LPAREN && matchingClose(toks, f, t) == t - 1) {
            f++
            t--
        }
        if (f >= t) return
        val ifIndex = findTopLevel(toks, f, t, T.IF_KEYWORD)
        if (ifIndex > f) {
            val elseIndex = findTopLevel(toks, ifIndex + 1, t, T.ELSE_KEYWORD)
            val conditionEnd = if (elseIndex >= 0) elseIndex else t
            val condition = conditionGuards(toks, ifIndex + 1, conditionEnd)
            expression(toks, f, ifIndex, guards + condition.whenTrue)
            expression(toks, ifIndex + 1, conditionEnd, guards)
            if (elseIndex >= 0) expression(toks, elseIndex + 1, t, guards + condition.whenFalse)
            return
        }
        val disjuncts = splitTopLevel(toks, f, t, T.OR_KEYWORD)
        if (disjuncts.size > 1) {
            var known = guards
            for ((a, b) in disjuncts) {
                expression(toks, a, b, known)
                known = known + conditionGuards(toks, a, b).whenFalse
            }
            return
        }
        val conjuncts = splitTopLevel(toks, f, t, T.AND_KEYWORD)
        if (conjuncts.size > 1) {
            var known = guards
            for ((a, b) in conjuncts) {
                expression(toks, a, b, known)
                known = known + conditionGuards(toks, a, b).whenTrue
            }
            return
        }
        for (i in f until t) {
            val tok = toks[i]
            if (tok.type != T.IDENTIFIER) continue
            if (i > 0 && toks[i - 1].type == T.DOT) continue // attribute
            if (i + 1 < toks.size && toks[i + 1].type == T.ASSIGN) continue // keyword argument or assignment target
            reference(toks, i, t, guards)
        }
    }

    private fun reference(toks: List<Tok>, index: Int, to: Int, guards: Set<String>) {
        val root = toks[index]
        val chain = chain(toks, index + 1, root.end, to)
        val name = textOf(root)
        val local = lookup(name)
        val guarded = isGuard(toks, chain.end, to)
        refBuilders += RefBuilder(
            name = name, path = chain.path, nameRange = range(root), range = TextRange(root.start, chain.pathEnd),
            guarded = guarded, guardedByCondition = name in guards, called = chain.called, local = local,
        )
        if (local == null && name in INDIRECTION_ROOTS) indirect(toks, index, to, chain, guarded, guards)
    }

    /**
     * The postfix chain from `toks[first]` (`.name`, `[…]`, `(…)`) with its constant accessors: `.attr`, `['key']`,
     * `[0]` and `.0`, up to the first dynamic subscript, slice or method call. [start] is the text offset where the
     * chain's target ends (the path end when nothing constant follows). `called` is set when the chain starts with a call.
     */
    private fun chain(toks: List<Tok>, first: Int, start: Int, to: Int): Chain {
        val path = ArrayList<String>()
        var pathEnd = start
        var open = true
        var called = false
        var j = first
        chain@ while (j < to) {
            when (toks[j].type) {
                T.DOT -> {
                    val segment = toks.getOrNull(j + 1)?.takeIf { j + 1 < to && (it.type == T.IDENTIFIER || it.type == T.INTEGER) }
                        ?: break@chain
                    val methodCall = j + 2 < to && toks[j + 2].type == T.LPAREN
                    if (open && !methodCall) {
                        path += if (segment.type == T.INTEGER) textOf(segment).replace("_", "") else textOf(segment)
                        pathEnd = segment.end
                    } else {
                        open = false
                    }
                    j += 2
                }
                T.LBRACKET -> {
                    val close = matchingClose(toks, j, to)
                    val key = toks.getOrNull(j + 1)
                    if (open && close == j + 2 && key != null && (isConstantString(key) || key.type == T.INTEGER)) {
                        path += if (key.type == T.STRING) stringValue(key) else textOf(key).replace("_", "")
                        pathEnd = toks[close].end
                    } else {
                        open = false
                    }
                    if (close < 0) {
                        j = to
                        break@chain
                    }
                    j = close + 1
                }
                T.LPAREN -> {
                    if (j == first) called = true
                    open = false
                    val close = matchingClose(toks, j, to)
                    if (close < 0) {
                        j = to
                        break@chain
                    }
                    j = close + 1
                }
                else -> break@chain
            }
        }
        return Chain(path, pathEnd, called, j)
    }

    // ------------------------------------------------------------------------------------------------ indirect reads

    /**
     * Records the member a free `hostvars`, `vars`, `lookup`, `query` or `q` at [index] reads by name, if any (see
     * [JinjaIndirectRef]). [chain] is the root's postfix chain, [guarded] its direct guard.
     */
    private fun indirect(toks: List<Tok>, index: Int, to: Int, chain: Chain, guarded: Boolean, guards: Set<String>) {
        val root = toks[index]
        when (textOf(root)) {
            HOSTVARS -> {
                val extract = extractMember(toks, index, to)
                if (extract != null) {
                    indirectRefs += indirectRef(extract.first, JinjaIndirection.HOSTVARS, extract.second, root, guarded = false, byCondition = false)
                    return
                }
                val selectorEnd = hostSelectorEnd(toks, index + 1, to) ?: return
                val member = memberName(toks, selectorEnd, to) ?: return
                val path = chain(toks, member.next, member.range.endOffset, to)
                val byCondition = memberGuard(JinjaIndirection.HOSTVARS, member.name, hostKey(toks, index + 1, selectorEnd)) in guards
                indirectRefs += indirectRef(member, JinjaIndirection.HOSTVARS, path, root, guarded, byCondition)
            }
            VARS -> {
                val member = memberName(toks, index + 1, to) ?: return
                val path = chain(toks, member.next, member.range.endOffset, to)
                indirectRefs += indirectRef(member, JinjaIndirection.VARS, path, root, guarded, varsGuarded(member.name, guards))
            }
            else -> if (chain.called) varsLookup(toks, index, to, guards)
        }
    }

    /** The read of [member] through [via], with the constant accessors of [path] after it. */
    private fun indirectRef(
        member: MemberName,
        via: JinjaIndirection,
        path: Chain,
        root: Tok,
        guarded: Boolean,
        byCondition: Boolean,
    ): JinjaIndirectRef = JinjaIndirectRef(
        name = member.name, via = via, attrPath = path.path, nameRange = member.range,
        range = TextRange(member.range.startOffset, maxOf(member.range.endOffset, path.pathEnd)), rootRange = range(root),
        guarded = guarded, guardedByCondition = byCondition,
    )

    /** A `vars` read of [name] is guarded by a test of the same member or of the plain name (`x is defined`). */
    private fun varsGuarded(name: String, guards: Set<String>): Boolean = memberGuard(JinjaIndirection.VARS, name) in guards || name in guards

    /** The host selector `toks[from, to)` of a `hostvars` access as written, without whitespace (`[h]`, `.web1`). */
    private fun hostKey(toks: List<Tok>, from: Int, to: Int): String = (from until to).joinToString("") { textOf(toks[it]) }

    /**
     * The index after the host selector of `hostvars` starting at [at]: any subscript (`[h]`, `['web1']`,
     * `[groups['g'][0]]`) or a constant attribute (`.web1`), or null when no selector follows.
     */
    private fun hostSelectorEnd(toks: List<Tok>, at: Int, to: Int): Int? {
        if (at >= to) return null
        return when (toks[at].type) {
            T.DOT -> {
                val segment = toks.getOrNull(at + 1)?.type
                val constant = at + 1 < to && (segment == T.IDENTIFIER || segment == T.INTEGER)
                if (!constant || at + 2 < to && toks[at + 2].type == T.LPAREN) null else at + 2
            }
            T.LBRACKET -> matchingClose(toks, at, to).takeIf { it >= 0 }?.plus(1)
            else -> null
        }
    }

    /** The constant member accessor at [at]: `.x` (not a method call) or `['x']` with a plain variable name. */
    private fun memberName(toks: List<Tok>, at: Int, to: Int): MemberName? {
        if (at >= to) return null
        return when (toks[at].type) {
            T.DOT -> {
                val segment = toks.getOrNull(at + 1)?.takeIf { at + 1 < to && it.type == T.IDENTIFIER } ?: return null
                if (at + 2 < to && toks[at + 2].type == T.LPAREN) return null
                MemberName(textOf(segment), range(segment), at + 2)
            }
            T.LBRACKET -> {
                if (matchingClose(toks, at, to) != at + 2) return null
                val body = variableNameString(toks[at + 1]) ?: return null
                MemberName(textOf(body), body, at + 3)
            }
            else -> null
        }
    }

    /**
     * `hosts | map('extract', hostvars, <keys>)` or `host | extract(hostvars, <keys>)` with the `hostvars` at [index]:
     * the member (the first key) and the constant keys after it with the end offset of the last one. `<keys>` is a
     * string or a list literal (`['ansible_default_ipv4', 'address']`), as `extract`'s `morekeys` argument.
     */
    private fun extractMember(toks: List<Tok>, index: Int, to: Int): Pair<MemberName, Chain>? {
        val before = toks.getOrNull(index - 1)?.type
        val viaMap = before == T.COMMA && index >= 4 && toks[index - 2].let { isConstantString(it) && stringValue(it) in EXTRACT_FILTERS } &&
            toks[index - 3].type == T.LPAREN && filterNameEndingAt(toks, index - 4) == "map"
        val direct = before == T.LPAREN && index >= 2 && filterNameEndingAt(toks, index - 2) in EXTRACT_FILTERS
        if (!viaMap && !direct) return null
        if (index + 2 >= to || toks[index + 1].type != T.COMMA) return null
        val at = index + 2
        val argumentEnd = { next: Int -> next < to && (toks[next].type == T.COMMA || toks[next].type == T.RPAREN) }
        return when (toks[at].type) {
            T.STRING -> {
                val body = variableNameString(toks[at])?.takeIf { argumentEnd(at + 1) } ?: return null
                MemberName(textOf(body), body, at + 1) to Chain(emptyList(), body.endOffset, called = false, end = at + 1)
            }
            T.LBRACKET -> {
                val close = matchingClose(toks, at, to)
                if (close < 0 || !argumentEnd(close + 1)) return null
                val elements = splitTopLevel(toks, at + 1, close, T.COMMA)
                val (first, firstEnd) = elements.first()
                if (firstEnd - first != 1) return null
                val body = variableNameString(toks[first]) ?: return null
                val path = ArrayList<String>()
                var pathEnd = body.endOffset
                for ((from, end) in elements.drop(1)) {
                    val key = toks.getOrNull(from)?.takeIf { end - from == 1 && (isConstantString(it) || it.type == T.INTEGER) } ?: break
                    path += if (key.type == T.STRING) stringValue(key) else textOf(key).replace("_", "")
                    pathEnd = key.end
                }
                MemberName(textOf(body), body, close + 1) to Chain(path, pathEnd, called = false, end = close + 1)
            }
            else -> null
        }
    }

    /**
     * `lookup('vars', 'a', 'b', default='')`, `query('vars', 'a')`, `q('ansible.builtin.vars', 'a')` with the callee at
     * [index]: one [JinjaIndirection.VARS] read per constant term. A `default=` argument guards them; dynamic terms are
     * skipped.
     */
    private fun varsLookup(toks: List<Tok>, index: Int, to: Int, guards: Set<String>) {
        val open = index + 1
        val close = matchingClose(toks, open, to)
        if (close < 0) return
        val arguments = splitTopLevel(toks, open + 1, close, T.COMMA)
        val (pluginFrom, pluginTo) = arguments.first()
        if (pluginTo - pluginFrom != 1 || !isConstantString(toks[pluginFrom]) || stringValue(toks[pluginFrom]) !in VARS_LOOKUPS) return
        var hasDefault = false
        val terms = ArrayList<MemberName>()
        for ((from, end) in arguments.drop(1)) {
            if (from >= end) continue
            if (end - from >= 2 && toks[from].type == T.IDENTIFIER && toks[from + 1].type == T.ASSIGN) {
                if (textOf(toks[from]) == "default") hasDefault = true
                continue
            }
            if (end - from != 1) continue
            val body = variableNameString(toks[from]) ?: continue
            terms += MemberName(textOf(body), body, from + 1)
        }
        for (term in terms) {
            val noPath = Chain(emptyList(), term.range.endOffset, called = false, end = term.next)
            indirectRefs += indirectRef(term, JinjaIndirection.VARS, noPath, toks[index], hasDefault, varsGuarded(term.name, guards))
        }
    }

    /** The dotted filter name whose last segment is `toks[last]` (`ansible.builtin.extract`), or null. */
    private fun filterNameEndingAt(toks: List<Tok>, last: Int): String? {
        if (toks.getOrNull(last)?.type != T.FILTER_NAME) return null
        var first = last
        while (first >= 2 && toks[first - 1].type == T.DOT && toks[first - 2].type == T.FILTER_NAME) first -= 2
        return (first..last step 2).joinToString(".") { textOf(toks[it]) }
    }

    /**
     * The [memberGuard] key of the `hostvars`/`vars` member that `toks[from, end)` accesses (`hostvars[h].x`,
     * `vars['x']`), or null.
     */
    private fun testedMember(toks: List<Tok>, from: Int, end: Int): String? {
        val root = textOf(toks[from])
        if (lookup(root) != null) return null
        return when (root) {
            HOSTVARS -> {
                val selectorEnd = hostSelectorEnd(toks, from + 1, end) ?: return null
                memberName(toks, selectorEnd, end)?.let { memberGuard(JinjaIndirection.HOSTVARS, it.name, hostKey(toks, from + 1, selectorEnd)) }
            }
            VARS -> memberName(toks, from + 1, end)?.let { memberGuard(JinjaIndirection.VARS, it.name) }
            else -> null
        }
    }

    /** The body range of a terminated, constant string literal that is exactly a plain variable name (no escapes), or null. */
    private fun variableNameString(tok: Tok): TextRange? {
        if (!isConstantString(tok) || tok.end - tok.start < 3 || text[tok.end - 1] != text[tok.start]) return null
        val body = TextRange(tok.start + 1, tok.end - 1)
        return body.takeIf { isVariableName(textOf(it)) }
    }

    /** A string literal whose value is known: it overlaps no [masked] braced part. */
    private fun isConstantString(tok: Tok): Boolean =
        tok.type == T.STRING && masked.none { it.startOffset < tok.end && tok.start < it.endOffset }

    /** Whether `toks[at]` starts `is [not] defined|undefined` or `| default|d`. */
    private fun isGuard(toks: List<Tok>, at: Int, to: Int): Boolean {
        if (at >= to) return false
        return when (toks[at].type) {
            T.IS_KEYWORD -> {
                var k = at + 1
                if (k < to && toks[k].type == T.NOT_KEYWORD) k++
                k < to && toks[k].type == T.TEST_NAME && textOf(toks[k]) in DEFINED_TESTS && !isDotted(toks, k, to)
            }
            T.PIPE -> {
                val k = at + 1
                k < to && toks[k].type == T.FILTER_NAME && textOf(toks[k]) in DEFAULT_FILTERS && !isDotted(toks, k, to)
            }
            else -> false
        }
    }

    /** The index after the postfix chain (`.name`, `[…]`, `(…)`) of the name at [index]. */
    private fun chainEnd(toks: List<Tok>, index: Int, to: Int): Int {
        var j = index + 1
        while (j < to) {
            j = when (toks[j].type) {
                T.DOT -> {
                    val segment = toks.getOrNull(j + 1)?.type
                    if (j + 1 < to && (segment == T.IDENTIFIER || segment == T.INTEGER)) j + 2 else return j
                }
                T.LBRACKET, T.LPAREN -> matchingClose(toks, j, to).let { if (it < 0) return to else it + 1 }
                else -> return j
            }
        }
        return j
    }

    private fun isDotted(toks: List<Tok>, index: Int, to: Int) = index + 1 < to && toks[index + 1].type == T.DOT

    /** What the condition `toks[from, to)` tells about definedness. Only `is [not] defined|undefined` terms count. */
    private fun conditionGuards(toks: List<Tok>, from: Int, to: Int): Guards {
        var f = from
        var t = to
        while (t - f >= 2 && toks[f].type == T.LPAREN && matchingClose(toks, f, t) == t - 1) {
            f++
            t--
        }
        if (f >= t || findTopLevel(toks, f, t, T.IF_KEYWORD) >= 0) return NO_GUARDS
        val disjuncts = splitTopLevel(toks, f, t, T.OR_KEYWORD)
        if (disjuncts.size > 1) {
            return Guards(emptySet(), disjuncts.flatMapTo(HashSet()) { (a, b) -> conditionGuards(toks, a, b).whenFalse })
        }
        val conjuncts = splitTopLevel(toks, f, t, T.AND_KEYWORD)
        if (conjuncts.size > 1) {
            return Guards(conjuncts.flatMapTo(HashSet()) { (a, b) -> conditionGuards(toks, a, b).whenTrue }, emptySet())
        }
        var negated = false
        while (f < t && toks[f].type == T.NOT_KEYWORD) {
            negated = !negated
            f++
        }
        if (f >= t) return NO_GUARDS
        val atom = when {
            toks[f].type == T.LPAREN && matchingClose(toks, f, t) == t - 1 -> conditionGuards(toks, f + 1, t - 1)
            toks[f].type == T.IDENTIFIER && !(f > 0 && toks[f - 1].type == T.DOT) -> definedTest(toks, f, t)
            else -> NO_GUARDS
        }
        return if (negated) atom.negate() else atom
    }

    /** `path is [not] defined|undefined` spanning exactly `toks[from, to)`. */
    private fun definedTest(toks: List<Tok>, from: Int, to: Int): Guards {
        val isIndex = chainEnd(toks, from, to)
        if (isIndex >= to || toks[isIndex].type != T.IS_KEYWORD) return NO_GUARDS
        var k = isIndex + 1
        val not = k < to && toks[k].type == T.NOT_KEYWORD
        if (not) k++
        if (k != to - 1 || toks[k].type != T.TEST_NAME) return NO_GUARDS
        val positive = when (textOf(toks[k])) {
            "defined" -> !not
            "undefined" -> not
            else -> return NO_GUARDS
        }
        val root = textOf(toks[from])
        val names = testedMember(toks, from, isIndex)?.let { setOf(root, it) } ?: setOf(root)
        return if (positive) Guards(names, emptySet()) else Guards(emptySet(), names)
    }

    // ------------------------------------------------------------------------------------------------ filters and tests

    private fun collectNameSites(toks: List<Tok>) {
        var i = 0
        while (i < toks.size) {
            val tok = toks[i]
            if (tok.type != T.FILTER_NAME && tok.type != T.TEST_NAME) {
                i++
                continue
            }
            var last = i
            val name = StringBuilder(textOf(tok))
            while (last + 2 < toks.size && toks[last + 1].type == T.DOT && toks[last + 2].type == tok.type) {
                last += 2
                name.append('.').append(textOf(toks[last]))
            }
            val site = JinjaNameSite(name.toString(), TextRange(tok.start, toks[last].end))
            if (tok.type == T.FILTER_NAME) {
                filterSites += site
                stringNamedSite(site.name, toks, last + 1)
            } else {
                testSites += site
            }
            i = last + 1
        }
    }

    /** `map('basename')`, `select('defined')`, `selectattr('attr', 'equalto', …)`: names passed as strings. */
    private fun stringNamedSite(filter: String, toks: List<Tok>, open: Int) {
        if (toks.getOrNull(open)?.type != T.LPAREN) return
        fun stringArgument(index: Int): Tok? {
            val tok = toks.getOrNull(index)?.takeIf { it.type == T.STRING } ?: return null
            val next = toks.getOrNull(index + 1)?.type
            return tok.takeIf { next == T.COMMA || next == T.RPAREN }
        }
        when (filter) {
            "map" -> stringArgument(open + 1)?.let { filterSites += quotedSite(it) }
            "select", "reject" -> stringArgument(open + 1)?.let { testSites += quotedSite(it) }
            "selectattr", "rejectattr" ->
                if (stringArgument(open + 1) != null) stringArgument(open + 3)?.let { testSites += quotedSite(it) }
        }
    }

    private fun quotedSite(tok: Tok): JinjaNameSite {
        val terminated = tok.end - tok.start >= 2 && text[tok.end - 1] == text[tok.start]
        return JinjaNameSite(stringValue(tok), TextRange(tok.start + 1, if (terminated) tok.end - 1 else tok.end), viaString = true)
    }

    // ------------------------------------------------------------------------------------------------ frames

    private fun push(kind: FrameKind): Frame = Frame(kind).also { frames += it }

    private fun nearestScope(): Frame = frames.last { it.scoping }

    /** Pops up to and including the innermost frame of one of [kinds]; unmatched end tags are ignored. */
    private fun popTo(offset: Int, vararg kinds: FrameKind) {
        val index = frames.indexOfLast { it.kind in kinds }
        if (index <= 0) return
        while (frames.size > index) closeLocals(frames.removeAt(frames.lastIndex), offset)
    }

    private fun closeLocals(frame: Frame, offset: Int) {
        for (local in frame.owned) if (local.scopeEnd < 0) local.scopeEnd = offset
        frame.owned.clear()
    }

    /** Root names known to be defined by the enclosing `if` branches, up to the innermost macro body. */
    private fun activeGuards(skip: Frame? = null): Set<String> {
        var result = emptySet<String>()
        for (index in frames.indices.reversed()) {
            val frame = frames[index]
            if (frame.kind == FrameKind.MACRO) break
            if (frame.kind == FrameKind.IF && frame !== skip) result = result + frame.branchGuards
        }
        return result
    }

    private fun define(frame: Frame, tok: Tok, kind: JinjaLocalKind, scopeStart: Int) =
        define(frame, textOf(tok), kind, range(tok), scopeStart)

    private fun define(
        frame: Frame,
        name: String,
        kind: JinjaLocalKind,
        definitionRange: TextRange,
        scopeStart: Int,
        owner: String? = null,
        visible: Boolean = true,
    ) {
        val local = LocalBuilder(name, kind, definitionRange, scopeStart, owner, frame)
        frame.owned += local
        if (visible) frame.visible[name] = local
        localBuilders += local
    }

    private fun lookup(name: String): LocalBuilder? {
        for (index in frames.indices.reversed()) frames[index].visible[name]?.let { return it }
        return null
    }

    // ------------------------------------------------------------------------------------------------ token helpers

    private fun textOf(tok: Tok): String = text.subSequence(tok.start, tok.end).toString()

    private fun textOf(range: TextRange): String = text.subSequence(range.startOffset, range.endOffset).toString()

    private fun range(tok: Tok) = TextRange(tok.start, tok.end)

    /** The value of a string literal: quotes removed, backslash escapes resolved. */
    private fun stringValue(tok: Tok): String {
        val raw = textOf(tok)
        val quote = raw[0]
        val body = if (raw.length >= 2 && raw.last() == quote && !endsWithEscape(raw)) raw.substring(1, raw.length - 1) else raw.substring(1)
        if ('\\' !in body) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.append(c)
                i++
                continue
            }
            out.append(
                when (val escaped = body[i + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    else -> escaped
                },
            )
            i += 2
        }
        return out.toString()
    }

    /** Whether the closing quote of [raw] is itself escaped (`'abc\'`, unterminated). */
    private fun endsWithEscape(raw: String): Boolean {
        var backslashes = 0
        var i = raw.length - 2
        while (i > 0 && raw[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    private fun findTopLevel(toks: List<Tok>, from: Int, to: Int, type: IElementType): Int {
        var depth = 0
        for (i in from until to) {
            val t = toks[i].type
            if (depth == 0 && t == type) return i
            when (t) {
                T.LPAREN, T.LBRACKET, T.LBRACE -> depth++
                T.RPAREN, T.RBRACKET, T.RBRACE -> if (depth > 0) depth--
            }
        }
        return -1
    }

    /** `toks[from, to)` split at top-level [separator] tokens, as half-open index pairs. */
    private fun splitTopLevel(toks: List<Tok>, from: Int, to: Int, separator: IElementType): List<Pair<Int, Int>> {
        val parts = ArrayList<Pair<Int, Int>>()
        var start = from
        var depth = 0
        for (i in from until to) {
            when (toks[i].type) {
                T.LPAREN, T.LBRACKET, T.LBRACE -> depth++
                T.RPAREN, T.RBRACKET, T.RBRACE -> if (depth > 0) depth--
                separator -> if (depth == 0) {
                    parts += start to i
                    start = i + 1
                }
            }
        }
        parts += start to to
        return parts
    }

    /** The index of the bracket closing the one at [open] (any bracket kind counts), or -1. */
    private fun matchingClose(toks: List<Tok>, open: Int, to: Int): Int {
        var depth = 0
        for (i in open until to) {
            when (toks[i].type) {
                T.LPAREN, T.LBRACKET, T.LBRACE -> depth++
                T.RPAREN, T.RBRACKET, T.RBRACE -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private companion object {
        val NO_GUARDS = Guards(emptySet(), emptySet())
        val DEFINED_TESTS = setOf("defined", "undefined")
        val DEFAULT_FILTERS = setOf("default", "d")
        val MACRO_IMPLICITS = listOf("varargs", "kwargs", "caller")

        const val HOSTVARS = "hostvars"
        const val VARS = "vars"

        /** Free names whose accesses can read another variable by name. */
        val INDIRECTION_ROOTS = setOf(HOSTVARS, VARS, "lookup", "query", "q")

        /** The `vars` lookup under all its names. */
        val VARS_LOOKUPS = setOf("vars", "ansible.builtin.vars", "ansible.legacy.vars")

        /** The `extract` filter under all its names. */
        val EXTRACT_FILTERS = setOf("extract", "ansible.builtin.extract", "ansible.legacy.extract")

        /**
         * The guard key that `<member access> is defined` adds for [member] read through [via], for `hostvars` on the
         * [host] selector as written (`hostvars[h].x is defined` says nothing about `hostvars[other].x`); the `:` keeps
         * it apart from every variable name, so direct references never match it.
         */
        fun memberGuard(via: JinjaIndirection, member: String, host: String? = null): String =
            if (host == null) "${via.name}:$member" else "${via.name}:$host:$member"

        /** A plain Python identifier, which every Ansible variable name is (`ansible-host` is not one). */
        fun isVariableName(text: CharSequence): Boolean =
            text.isNotEmpty() && (text[0].isLetter() || text[0] == '_') && text.all { it.isLetterOrDigit() || it == '_' }
    }
}
