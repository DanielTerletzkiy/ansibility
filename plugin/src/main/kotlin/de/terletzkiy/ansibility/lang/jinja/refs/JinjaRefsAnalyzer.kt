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
 * `block` and block `set` open one; `if` does not, but carries the guards of its current branch).
 */
internal class JinjaRefsAnalyzer(private val text: CharSequence, private val mode: JinjaLexMode) {
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

    /** What a condition tells about definedness when it is true and when it is false. */
    private class Guards(val whenTrue: Set<String>, val whenFalse: Set<String>) {
        fun negate() = Guards(whenFalse, whenTrue)
    }

    private val frames = ArrayList<Frame>()
    private val localBuilders = ArrayList<LocalBuilder>()
    private val refBuilders = ArrayList<RefBuilder>()
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
        val path = ArrayList<String>()
        var pathEnd = root.end
        var open = true
        var called = false
        var j = index + 1
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
                    if (open && close == j + 2 && key != null && (key.type == T.STRING || key.type == T.INTEGER)) {
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
                    if (j == index + 1) called = true
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
        val name = textOf(root)
        refBuilders += RefBuilder(
            name = name, path = path, nameRange = range(root), range = TextRange(root.start, pathEnd),
            guarded = isGuard(toks, j, to), guardedByCondition = name in guards, called = called, local = lookup(name),
        )
    }

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
        val name = setOf(textOf(toks[from]))
        return if (positive) Guards(name, emptySet()) else Guards(emptySet(), name)
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
    }
}
