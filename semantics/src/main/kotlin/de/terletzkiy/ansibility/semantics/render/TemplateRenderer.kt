package de.terletzkiy.ansibility.semantics.render

import de.terletzkiy.ansibility.semantics.typeflow.JinjaExpr
import de.terletzkiy.ansibility.semantics.typeflow.JinjaTokenizer
import de.terletzkiy.ansibility.semantics.value.PyValue
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.semantics.yaml.YVault
import java.math.BigInteger

/**
 * The template renderer (plan amendment R11, A.17): a Jinja interpreter with Python semantics over the plugin's one
 * Jinja lexer, with Ansible's environment and an explicit [RValue.Hole] for everything unproven. Pure: it never
 * decrypts, never runs a process, never touches the network; files come only through [loader] and [lookups].
 *
 * One instance renders one subject; it is not thread-safe. [cancel] is called regularly and may throw.
 */
class TemplateRenderer(
    private val tokenizer: JinjaTokenizer,
    private val scope: RenderScope,
    private val options: RenderOptions,
    private val lookups: LookupResolver = LookupResolver.NONE,
    private val loader: TemplateLoader = TemplateLoader.NONE,
    private val cancel: () -> Unit = {},
) {
    private class BudgetExceeded(val what: String) : RuntimeException(null, null, false, false)

    /** A variable scope; [isolated] frames (imports without context) see only the globals, not the task's variables. */
    private class Frame(val parent: Frame?, val isolated: Boolean = false, val exports: Frame? = null) {
        val vars = HashMap<String, RValue>()

        fun find(name: String): RValue? = vars[name] ?: parent?.find(name)
    }

    private val tuples get() = options.tuplesAsLists
    private var steps = 0
    private val globals = HashMap<String, RValue>()
    private val resolving = HashSet<String>()
    private var lazyDepth = 0
    private var includeDepth = 0
    private var macroDepth = 0

    // output state
    private val buffers = ArrayList<StringBuilder>()
    private val segments = ArrayList<RenderSegment>()
    private val problems = ArrayList<RenderProblem>()
    private var placeholders = 0
    private var unknownBranches = 0
    private var uncertain = 0
    private var currentFile: String? = null

    /** Top-level emissions, for the 2.19+ "single non-string node" trailing-newline rule. */
    private var emissions = 0
    private var lastNative: RValue? = null

    private val out: StringBuilder get() = buffers.last()

    private val capturing: Boolean get() = buffers.size > 1

    // ============================================================================================ entry points

    /** Renders a template file (the `template` module): output text with Ansible's trailing-newline rule. */
    fun renderTemplate(tree: TemplateTree, file: String? = null): Rendered {
        currentFile = file
        buffers += StringBuilder()
        if (tree.notEmulated != null) {
            val hole = Placeholder.notEmulated(tree.notEmulated)
            placeholders++
            out.append(hole.text)
            segments += RenderSegment(0, out.length, SegmentKind.PLACEHOLDER, file, 0, tree.source.length, hole.label)
            return result(null)
        }
        if (options.native && !tree.hasSyntax) {
            out.append(tree.source)
            segments += RenderSegment(0, out.length, SegmentKind.TEXT, file, 0, tree.source.length)
            return result(null)
        }
        val root = Frame(null)
        runGuarded(tree.source.length) { exec(tree.nodes, root) }
        val sourceNewlines = trailingNewlines(tree.source)
        val singleValue = emissions == 1 && lastNative != null && lastNative !is RValue.Str
        if (options.native && (emissions == 0 || singleValue) && errorsFree()) return result(lastNative)
        val have = trailingNewlines(out)
        if (sourceNewlines > have) {
            val sequence = if (options.native) "\n" else tree.env.newlineSequence
            val start = out.length
            repeat(sourceNewlines - have) { out.append(sequence) }
            segments += RenderSegment(start, out.length, SegmentKind.TEXT, file, tree.source.length, tree.source.length)
        }
        return result(null)
    }

    /** Renders a templated YAML value (a task argument, a loop, a variable): its native value per the core's rules. */
    fun renderValue(text: String): Rendered {
        buffers += StringBuilder()
        var value = valueOf(text, Frame(null), "")
        try {
            requireStorable(value)
        } catch (e: RenderError) {
            value = RValue.Undefined("", e.message ?: "error")
        }
        if (value is RValue.Undefined && problems.none { it.fatal }) error(value.message, 0, text.length)
        val printed = try {
            if (value is RValue.Undefined) "" else RValues.str(value, tuples)
        } catch (e: RenderError) {
            ""
        }
        out.append(printed)
        if (RValues.holeIn(value) != null) placeholders++
        return result(value)
    }

    /** `when:`: the truth of [text] (a bare expression; `{{ }}` inside is rendered first). Null value: unknown. */
    fun renderCondition(text: String): Rendered {
        buffers += StringBuilder()
        val source = if (text.contains("{{") || text.contains("{%")) RValues.str(valueOf(text, Frame(null), ""), tuples) else text
        val tree = TemplateTree.parse("{{ $source }}", tokenizer, options.env, rawStrings = false)
        val output = tree.nodes.singleOrNull() as? TNode.Output
        if (output?.expr == null) {
            error("template error while templating string: $text", 0, text.length)
            return result(null)
        }
        val value = runGuarded(text.length) { eval(output.expr, Frame(null)) }
        val decided: RValue? = when {
            value == null -> null
            value is RValue.Hole -> value
            value is RValue.Undefined -> {
                error(value.message, 0, text.length)
                null
            }
            options.native && value !is RValue.Bool -> {
                error("Conditional result (${RValues.str(value, tuples)}) was derived from value of type '${value.typeName}'. Conditionals must have a boolean result.", 0, text.length)
                null
            }
            else -> RValues.bool(RValues.truthy(value))
        }
        decided?.let { out.append(RValues.str(it, tuples)) }
        if (decided is RValue.Hole) placeholders++
        return result(decided)
    }

    private fun result(value: RValue?): Rendered =
        Rendered(buffers.first().toString(), value, segments.toList(), problems.toList(), placeholders, unknownBranches)

    private fun errorsFree() = problems.none { it.fatal }

    private fun <T> runGuarded(length: Int, block: () -> T): T? = try {
        block()
    } catch (e: BudgetExceeded) {
        val hole = Placeholder(Placeholder.Kind.BUDGET, e.what)
        val start = out.length
        out.append(hole.text)
        placeholders++
        segments += RenderSegment(start, out.length, SegmentKind.PLACEHOLDER, currentFile, 0, length, hole.label)
        null
    }

    private fun trailingNewlines(text: CharSequence): Int {
        var count = 0
        var i = text.length - 1
        while (i >= 0) {
            when {
                text[i] == '\n' -> {
                    count++
                    i--
                    if (i >= 0 && text[i] == '\r') i--
                }
                text[i] == '\r' -> {
                    count++
                    i--
                }
                else -> return count
            }
        }
        return count
    }

    private fun step() {
        if (++steps > options.maxSteps) throw BudgetExceeded("more than ${options.maxSteps} steps")
        if (steps and 255 == 0) cancel()
    }

    // ============================================================================================ emitting

    private fun emit(text: String, kind: SegmentKind, srcStart: Int, srcEnd: Int, note: String? = null) {
        if (text.isEmpty() && kind != SegmentKind.PLACEHOLDER && kind != SegmentKind.ERROR) return
        if (out.length + text.length > options.maxOutput) throw BudgetExceeded("output larger than ${options.maxOutput / 1024} KiB")
        val start = out.length
        out.append(text)
        if (!capturing) segments += RenderSegment(start, out.length, kind, currentFile, srcStart, srcEnd, note)
    }

    private fun error(message: String, srcStart: Int, srcEnd: Int) {
        if (uncertain > 0) {
            problems += RenderProblem("fails if taken: $message", currentFile, srcStart, srcEnd, fatal = false)
            emit("⟨fails if taken: $message⟩", SegmentKind.ERROR, srcStart, srcEnd, message)
            return
        }
        problems += RenderProblem(message, currentFile, srcStart, srcEnd, fatal = true)
        emit("⟨error: $message⟩", SegmentKind.ERROR, srcStart, srcEnd, message)
    }

    private fun countEmission(value: RValue?) {
        if (capturing || includeDepth > 0) return
        emissions++
        lastNative = value
    }

    // ============================================================================================ statements

    private fun exec(nodes: List<TNode>, frame: Frame) {
        for (node in nodes) {
            step()
            execNode(node, frame)
        }
    }

    private fun execNode(node: TNode, frame: Frame) {
        when (node) {
            is TNode.Text -> {
                emit(node.text, SegmentKind.TEXT, node.start, node.end)
                countEmission(RValue.Str(node.text))
            }
            is TNode.Output -> output(node, frame)
            is TNode.If -> ifNode(node, frame)
            is TNode.For -> forNode(node, frame)
            is TNode.Set -> guarded(node) {
                val value = eval(node.value, frame)
                assign(node.targets, value, frame, node)
            }
            is TNode.SetBlock -> guarded(node) {
                var value: RValue = RValue.Str(capture { exec(node.body, Frame(frame)) })
                node.filters?.let { filters -> value = withCapture(filters, value, frame) }
                assign(listOf(node.target), value, frame, node)
            }
            is TNode.Macro -> {
                val macro = macro(node.name, node.params, node.body, frame)
                frame.vars[node.name] = macro
                frame.exports?.vars?.set(node.name, macro)
            }
            is TNode.CallBlock -> guarded(node) {
                val caller = macro("caller", node.params, node.body, frame)
                val call = node.call as? JinjaExpr.Call ?: throw RenderError("expected a macro call")
                val callee = eval(call.callee, frame)
                val args = call.args.map { eval(it, frame) }
                val kwargs = call.kwargs.mapValues { eval(it.value, frame) } + ("caller" to caller)
                val result = invoke(callee, args, kwargs)
                printValue(result, node.start, node.end)
            }
            is TNode.FilterBlock -> guarded(node) {
                val text = capture { exec(node.body, Frame(frame)) }
                printValue(withCapture(node.filters, RValue.Str(text), frame), node.start, node.end)
            }
            is TNode.With -> {
                val inner = Frame(frame)
                guarded(node) { node.assignments.forEach { (name, expr) -> inner.vars[name] = eval(expr, inner) } }
                exec(node.body, inner)
            }
            is TNode.Include -> include(node, frame)
            is TNode.Import -> guarded(node) {
                frame.vars[node.alias] = module(eval(node.template, frame), node.withContext, frame, node) ?: return@guarded
            }
            is TNode.FromImport -> guarded(node) {
                val module = module(eval(node.template, frame), node.withContext, frame, node) ?: return@guarded
                for ((name, alias) in node.names) {
                    frame.vars[alias] = (module as? RValue.Obj)?.attributes?.get(name)
                        ?: RValue.Undefined(name, "the template does not export the requested name '$name'")
                }
            }
            is TNode.Problem -> if (node.notEmulated) {
                val hole = Placeholder.notEmulated(node.message)
                placeholders++
                emit(hole.text, SegmentKind.PLACEHOLDER, node.start, node.end, hole.label)
            } else {
                error(node.message, node.start, node.end)
            }
        }
    }

    /** Runs [block]; an error becomes an error marker at [node], a hole leaves the assignment unknown. */
    private fun guarded(node: TNode, block: () -> Unit) {
        try {
            block()
        } catch (e: RenderError) {
            error(e.message ?: "error", node.start, node.end)
        } catch (e: HoleSignal) {
            if (node is TNode.Set) assign(node.targets, e.hole, null, node)
        }
    }

    private fun output(node: TNode.Output, frame: Frame) {
        val expr = node.expr ?: return error(node.error ?: "template syntax error", node.start, node.end)
        try {
            val value = eval(expr, frame)
            printValue(value, node.start, node.end)
            countEmission(value)
        } catch (e: RenderError) {
            error(e.message ?: "error", node.start, node.end)
            countEmission(null)
        }
    }

    /** 2.19+ refuses to print or store what is not data (a raw `range`, a bound method). */
    private fun requireStorable(value: RValue) {
        if (!options.native) return
        if (value is RValue.Callable && value.owner != null) {
            throw RenderError("Error rendering template: Type '${value.typeName}' is unsupported for variable storage.")
        }
        val bad = when (value) {
            is RValue.List -> value.range || value.items.any { it is RValue.List && it.range }
            is RValue.Dict -> value.map.values.any { it is RValue.List && it.range }
            else -> false
        }
        if (bad) throw RenderError("Error rendering template: Type 'range' is unsupported for variable storage.")
    }

    private fun printValue(value: RValue, start: Int, end: Int) {
        if (value is RValue.Undefined && value.lenient && !options.native) return emit("", SegmentKind.VALUE, start, end, null)
        if (value is RValue.Undefined) throw RenderError(value.message)
        requireStorable(value)
        val hole = RValues.holeIn(value)
        if (value is RValue.Hole) {
            placeholders++
            emit(value.placeholder.text, SegmentKind.PLACEHOLDER, start, end, value.placeholder.label)
            return
        }
        val text = if (value == RValue.None && !classicNative) "" else RValues.str(value, tuples)
        if (hole != null) placeholders++
        emit(text, if (hole != null) SegmentKind.PLACEHOLDER else SegmentKind.VALUE, start, end, hole?.placeholder?.label)
    }

    private fun capture(block: () -> Unit): String {
        val buffer = StringBuilder()
        buffers += buffer
        try {
            block()
        } finally {
            buffers.removeAt(buffers.size - 1)
        }
        return buffer.toString()
    }

    private fun withCapture(filters: JinjaExpr, value: RValue, frame: Frame): RValue {
        val inner = Frame(frame)
        inner.vars[TNode.CAPTURE] = value
        return eval(filters, inner)
    }

    /** Runs [block] as output whose condition is unknown: everything in it is an unknown-branch segment. */
    private fun unknownBranch(note: String, start: Int, end: Int, block: () -> Unit) {
        unknownBranches++
        uncertain++
        val outStart = out.length
        try {
            block()
        } finally {
            uncertain--
        }
        if (!capturing) segments += RenderSegment(outStart, out.length, SegmentKind.UNKNOWN_BRANCH, currentFile, start, end, note)
    }

    private fun ifNode(node: TNode.If, frame: Frame) {
        for ((index, branch) in node.branches.withIndex()) {
            val decision = try {
                decide(eval(branch.condition, frame))
            } catch (e: RenderError) {
                error(e.message ?: "error", branch.start, branch.end)
                return
            }
            when (decision) {
                true -> return exec(branch.body, frame)
                false -> continue
                null -> {
                    val reason = holeReason(branch.condition, frame)
                    for (rest in node.branches.drop(index)) {
                        unknownBranch("only if ${describe(rest.condition)} · depends on $reason", rest.start, rest.end) { exec(rest.body, frame) }
                    }
                    node.otherwise?.let { unknownBranch("else branch · depends on $reason", node.start, node.end) { exec(it, frame) } }
                    return
                }
            }
        }
        node.otherwise?.let { exec(it, frame) }
    }

    /** The truth of a condition value; null when it is a hole. */
    private fun decide(value: RValue): Boolean? = try {
        RValues.truthy(value)
    } catch (e: HoleSignal) {
        null
    }

    private fun holeReason(expr: JinjaExpr, frame: Frame): String =
        (try {
            eval(expr, frame)
        } catch (e: RenderError) {
            null
        } as? RValue.Hole)?.placeholder?.text ?: "an unknown value"

    private fun describe(expr: JinjaExpr): String = when (expr) {
        is JinjaExpr.Name -> expr.name
        is JinjaExpr.Attribute -> describe(expr.target) + "." + expr.attribute
        is JinjaExpr.Test -> describe(expr.target) + " is " + (if (expr.negated) "not " else "") + expr.name
        is JinjaExpr.Unary -> expr.operator + " " + describe(expr.operand)
        else -> "the condition"
    }

    private fun forNode(node: TNode.For, frame: Frame) {
        val iterable = try {
            eval(node.iter, frame)
        } catch (e: RenderError) {
            return error(e.message ?: "error", node.start, node.end)
        }
        val items = try {
            RValues.iterate(iterable)
        } catch (e: RenderError) {
            return error(e.message ?: "error", node.start, node.end)
        } catch (e: HoleSignal) {
            null
        }
        if (items == null) {
            val hole = iterable as? RValue.Hole ?: RValues.holeIn(iterable) ?: RValue.Hole(Placeholder.unknown("unknown sequence"))
            unknownBranch("repeated for each item of ${describe(node.iter)} · ${hole.placeholder.text}", node.start, node.end) {
                val inner = Frame(frame)
                node.targets.forEach { inner.vars[it] = hole }
                inner.vars["loop"] = RValue.Hole(hole.placeholder)
                exec(node.body, inner)
            }
            node.otherwise?.let { unknownBranch("else branch (empty sequence) · ${hole.placeholder.text}", node.start, node.end) { exec(it, frame) } }
            return
        }
        loop(node, items, frame, depth = 1)
    }

    private fun loop(node: TNode.For, all: List<RValue>, frame: Frame, depth: Int) {
        val items = if (node.filter == null) all else all.filter { item ->
            val probe = Frame(frame)
            bindTargets(node.targets, item, probe)
            decide(eval(node.filter, probe)) ?: true
        }
        if (items.size > options.maxLoopItems) throw BudgetExceeded("a loop over more than ${options.maxLoopItems} items")
        if (items.isEmpty()) {
            node.otherwise?.let { exec(it, frame) }
            return
        }
        var previousChanged: RValue? = null
        for ((index, item) in items.withIndex()) {
            step()
            val inner = Frame(frame)
            val loopObj = RValue.Obj("LoopContext")
            val attrs = loopObj.attributes
            attrs["index"] = RValues.int(index + 1)
            attrs["index0"] = RValues.int(index)
            attrs["revindex"] = RValues.int(items.size - index)
            attrs["revindex0"] = RValues.int(items.size - index - 1)
            attrs["first"] = RValues.bool(index == 0)
            attrs["last"] = RValues.bool(index == items.size - 1)
            attrs["length"] = RValues.int(items.size)
            attrs["depth"] = RValues.int(depth)
            attrs["depth0"] = RValues.int(depth - 1)
            attrs["previtem"] = if (index > 0) items[index - 1] else RValue.Undefined("previtem", "there is no previous item")
            attrs["nextitem"] = if (index < items.size - 1) items[index + 1] else RValue.Undefined("nextitem", "there is no next item")
            attrs["cycle"] = RValue.Callable("cycle") { args, _ ->
                if (args.isEmpty()) throw RenderError("no items for cycling given")
                args[index % args.size]
            }
            attrs["changed"] = RValue.Callable("changed") { args, _ ->
                val value = RValue.Tuple(args)
                val changed = previousChanged == null || !RValues.pyEquals(previousChanged!!, value)
                previousChanged = value
                RValues.bool(changed)
            }
            if (node.recursive) {
                attrs["__call__"] = RValue.Callable("loop") { args, _ ->
                    val children = args.firstOrNull() ?: throw RenderError("loop() takes one argument")
                    RValue.Str(capture { loop(node, RValues.iterate(children), frame, depth + 1) })
                }
            }
            inner.vars["loop"] = loopObj
            bindTargets(node.targets, item, inner)
            exec(node.body, inner)
        }
    }

    private fun bindTargets(targets: List<String>, item: RValue, frame: Frame) {
        if (targets.size == 1) {
            frame.vars[targets[0]] = item
            return
        }
        val parts = when (item) {
            is RValue.Hole -> List(targets.size) { item }
            else -> RValues.sequence(item) ?: (item as? RValue.Str)?.let { RValues.iterate(it) }
                ?: throw RenderError("cannot unpack non-iterable ${item.typeName} object")
        }
        if (parts.size != targets.size) {
            throw RenderError(if (parts.size > targets.size) "too many values to unpack (expected ${targets.size})" else "not enough values to unpack (expected ${targets.size}, got ${parts.size})")
        }
        targets.forEachIndexed { i, name -> frame.vars[name] = parts[i] }
    }

    private fun assign(targets: List<TNode.SetTarget>, value: RValue, frame: Frame?, node: TNode) {
        val f = frame ?: return
        if (targets.size == 1) {
            setTarget(targets[0], value, f)
            return
        }
        val parts = if (value is RValue.Hole) List(targets.size) { value } else RValues.sequence(value)
            ?: throw RenderError("cannot unpack non-iterable ${value.typeName} object")
        if (parts.size != targets.size) throw RenderError("not enough values to unpack (expected ${targets.size}, got ${parts.size})")
        targets.forEachIndexed { i, t -> setTarget(t, parts[i], f) }
    }

    private fun setTarget(target: TNode.SetTarget, value: RValue, frame: Frame) {
        // a name set inside an unknown branch is unknown after it
        val stored = if (uncertain > 0) RValue.Hole(Placeholder.unknown("${target.name}: set in an unknown branch")) else value
        if (target.attribute == null) {
            frame.vars[target.name] = stored
            frame.exports?.vars?.set(target.name, stored)
            return
        }
        val ns = lookup(target.name, frame)
        if (ns !is RValue.Obj || !ns.mutable) throw RenderError("cannot assign attribute on non-namespace object")
        ns.attributes[target.attribute] = stored
    }

    // ============================================================================================ macros, include, import

    private fun macro(name: String, params: List<TNode.Param>, body: List<TNode>, defining: Frame): RValue.Callable =
        RValue.Callable(name) { args, kwargs ->
            if (++macroDepth > 64) throw BudgetExceeded("macro recursion deeper than 64")
            try {
                val inner = Frame(defining)
                val rest = kwargs.toMutableMap()
                params.forEachIndexed { i, param ->
                    inner.vars[param.name] = when {
                        i < args.size -> args[i]
                        param.name in rest -> rest.remove(param.name)!!
                        param.default != null -> eval(param.default, inner)
                        else -> RValue.Undefined(param.name, "parameter '${param.name}' was not provided")
                    }
                }
                inner.vars["varargs"] = RValue.List(args.drop(params.size))
                rest["caller"]?.let { inner.vars["caller"] = it }
                rest.remove("caller")
                inner.vars["kwargs"] = RValue.Dict.of(rest.map { RValue.Str(it.key) to it.value })
                RValue.Str(capture { exec(body, inner) })
            } finally {
                macroDepth--
            }
        }

    private fun include(node: TNode.Include, frame: Frame) {
        val names = try {
            when (val template = eval(node.template, frame)) {
                is RValue.Hole -> {
                    placeholders++
                    emit(Placeholder.unknown("include of ${template.placeholder.text}").text, SegmentKind.PLACEHOLDER, node.start, node.end, template.placeholder.label)
                    return
                }
                else -> RValues.sequence(template)?.map { RValues.str(it, tuples) } ?: listOf(RValues.str(template, tuples))
            }
        } catch (e: RenderError) {
            return error(e.message ?: "error", node.start, node.end)
        }
        if (includeDepth >= options.maxDepth) throw BudgetExceeded("includes nested deeper than ${options.maxDepth}")
        val tried = ArrayList<String>()
        for (name in names) {
            when (val loaded = loader.load(name, currentFile)) {
                is Loaded.Found -> {
                    val tree = TemplateTree.parse(loaded.source.replace("\r\n", "\n"), tokenizer, options.env)
                    val saved = currentFile
                    currentFile = loaded.id
                    includeDepth++
                    try {
                        exec(tree.nodes, if (node.withContext) Frame(frame) else Frame(null, isolated = true))
                    } finally {
                        includeDepth--
                        currentFile = saved
                    }
                    return
                }
                is Loaded.Refused -> {
                    val hole = Placeholder.notEmulated("include '$name': ${loaded.reason}")
                    placeholders++
                    emit(hole.text, SegmentKind.PLACEHOLDER, node.start, node.end, hole.label)
                    return
                }
                is Loaded.Missing -> tried += loaded.tried
            }
        }
        if (!node.ignoreMissing) error("TemplateNotFound: ${names.joinToString(", ")}", node.start, node.end)
    }

    /** The exports (macros, top-level `set`s) of an imported template. */
    private fun module(template: RValue, withContext: Boolean, frame: Frame, node: TNode): RValue? {
        if (template is RValue.Hole) return template
        val name = RValues.str(template, tuples)
        val loaded = loader.load(name, currentFile)
        if (loaded !is Loaded.Found) {
            error(if (loaded is Loaded.Refused) "import '$name': ${loaded.reason}" else "TemplateNotFound: $name", node.start, node.end)
            return null
        }
        if (includeDepth >= options.maxDepth) throw BudgetExceeded("imports nested deeper than ${options.maxDepth}")
        val tree = TemplateTree.parse(loaded.source.replace("\r\n", "\n"), tokenizer, options.env)
        val exports = Frame(null)
        val moduleFrame = Frame(if (withContext) frame else null, isolated = !withContext, exports = exports)
        val saved = currentFile
        currentFile = loaded.id
        includeDepth++
        try {
            capture { exec(tree.nodes, moduleFrame) }
        } finally {
            includeDepth--
            currentFile = saved
        }
        return RValue.Obj("TemplateModule", LinkedHashMap(exports.vars))
    }

    // ============================================================================================ names

    private fun lookup(name: String, frame: Frame): RValue {
        var f: Frame? = frame
        var isolated = false
        while (f != null) {
            f.vars[name]?.let { return it }
            if (f.isolated) isolated = true
            f = f.parent
        }
        // Jinja resolves the context (the task's variables) before the environment's globals (`range`, `lookup`).
        val builtin = builtin(name)
        if (isolated) return builtin ?: RValue.Undefined.name(name)
        val value = globals[name] ?: resolveGlobal(name).also { globals[name] = it }
        val unbound = value is RValue.Undefined || value is RValue.Hole && value.placeholder.kind == Placeholder.Kind.UNKNOWN
        return if (builtin != null && unbound) builtin else value
    }

    private fun resolveGlobal(name: String): RValue {
        if (name in resolving) return RValue.Hole(Placeholder.unknown("$name: recursive loop detected in template string"))
        resolving += name
        try {
            return when (val binding = scope.lookup(name)) {
                is Binding.Computed -> RValues.copy(binding.value)
                is Binding.Unknown -> RValue.Hole(binding.placeholder)
                is Binding.Undefined -> if (binding.proven) RValue.Undefined.name(name) else RValue.Hole(Placeholder.unknown("$name: ${binding.why ?: "not proven"}"))
                is Binding.Yaml -> fromYaml(binding.value, binding.label, binding.unsafe, binding.json)
            }
        } finally {
            resolving -= name
        }
    }

    /** A loaded value as the template sees it: Jinja-bearing strings rendered (lazily per name, recursively). */
    private fun fromYaml(value: YValue, label: String, unsafe: Boolean, json: Boolean): RValue = when (value) {
        is YVault -> RValue.Hole(Placeholder.secret(label))
        is YEmpty -> RValue.None
        is YSeq -> RValue.List(value.items.map { fromYaml(it, label, unsafe, json) })
        is YMap -> {
            val py = PyValue.fromYValue(YMap(value.entries.map { it.copy(value = YEmpty()) }))
            if (py !is PyValue.Dict) RValue.Hole(Placeholder.unknown("$label: unloadable"))
            else RValue.Dict.of(value.entries.map { RValues.fromPy(PyValue.fromYValue(it.key)) to fromYaml(it.value, label, unsafe, json) })
        }
        is YScalar -> {
            val py = PyValue.fromYValue(value)
            val isUnsafe = unsafe || value.tag == "!unsafe"
            when {
                py is PyValue.Str && !isUnsafe && isTemplate(py.value) -> lazyValue(py.value, label)
                py is PyValue.Str -> RValue.Str(py.value, origin = if (isUnsafe) "AnsibleUnsafeText" else if (json) null else "AnsibleUnicode")
                else -> RValues.fromPy(py)
            }
        }
    }

    private fun isTemplate(text: String) = text.contains("{{") || text.contains("{%") || text.contains("{#")

    private fun lazyValue(text: String, label: String): RValue {
        if (lazyDepth >= options.maxDepth) return RValue.Hole(Placeholder.unknown("$label: templates nested deeper than ${options.maxDepth}"))
        lazyDepth++
        val savedBuffers = ArrayList(buffers)
        val savedUncertain = uncertain
        try {
            buffers.clear()
            buffers += StringBuilder()
            uncertain = 0
            val problemCount = problems.size
            val segmentCount = segments.size
            val value = valueOf(text, Frame(null), label)
            // errors of a lazy value surface where it is used, as an undefined value (so `default()` still works)
            val failure = problems.drop(problemCount).firstOrNull { it.fatal }
            while (problems.size > problemCount) problems.removeAt(problems.size - 1)
            while (segments.size > segmentCount) segments.removeAt(segments.size - 1)
            return failure?.let { RValue.Undefined(label, it.message) } ?: value
        } finally {
            buffers.clear()
            buffers.addAll(savedBuffers)
            uncertain = savedUncertain
            lazyDepth--
        }
    }

    /**
     * A templated YAML value under the core's templating rules ([de.terletzkiy.ansibility.semantics.typeflow.TemplatingRules]):
     * 2.19+ keeps a single expression's native value; 2.18 keeps it only for a bare `{{ name }}` (and for lists,
     * dicts and bools, which `literal_eval` gives back), everything else is a string.
     */
    private fun valueOf(text: String, frame: Frame, label: String): RValue {
        val tree = TemplateTree.parse(text, tokenizer, options.env.copy(keepTrailingNewline = true), rawStrings = true, escapedQuoteEnds = !options.native)
        val single = tree.nodes.singleOrNull() as? TNode.Output
        if (single?.expr != null) {
            val value = try {
                eval(single.expr, frame)
            } catch (e: RenderError) {
                return RValue.Undefined(label, e.message ?: "error")
            }
            if (value is RValue.Undefined && value.lenient && !options.native) return RValue.Str("")
            if (options.native || value is RValue.Hole || value is RValue.Undefined) return value
            if (classicNative) return if (value is RValue.Str && !value.native) nativeConcat(value) else value
            val bare = single.expr is JinjaExpr.Name
            return when {
                value is RValue.Str -> if (value.native) value else classicConcat(value)
                bare -> value
                value is RValue.List || value is RValue.Dict || value is RValue.Bool -> value
                // Ansible's `finalize` prints None as an empty string.
                else -> RValue.Str(if (value == RValue.None) "" else RValues.str(value, tuples))
            }
        }
        val start = out.length
        val segmentCount = segments.size
        val problemCount = problems.size
        exec(tree.nodes, frame)
        val printed = out.substring(start)
        out.setLength(start)
        val inner = segments.drop(segmentCount)
        while (segments.size > segmentCount) segments.removeAt(segments.size - 1)
        problems.drop(problemCount).firstOrNull { it.fatal }?.let { return RValue.Undefined(label, it.message) }
        if (inner.any { it.kind == SegmentKind.PLACEHOLDER || it.kind == SegmentKind.UNKNOWN_BRANCH }) {
            val hole = inner.firstOrNull { it.kind == SegmentKind.PLACEHOLDER }?.note
            return RValue.Hole(Placeholder.unknown("$label: ${hole ?: "depends on an unknown value"}").let { if (printed.contains("\uD83D\uDD12")) Placeholder.secret(label) else it })
        }
        return when {
            options.native -> RValue.Str(printed)
            classicNative -> nativeConcat(RValue.Str(printed))
            else -> classicConcat(RValue.Str(printed))
        }
    }

    /** 2.18 with `jinja2_native`: Ansible's `ansible_native_concat` `literal_eval`s any string result that is not a string literal. */
    private val classicNative: Boolean get() = !options.native && options.jinja2Native

    private fun nativeConcat(value: RValue.Str): RValue = try {
        RValues.fromPy(de.terletzkiy.ansibility.semantics.coerce.PyLiteral.eval(value.value)).let { if (it is RValue.Str) value else it }
    } catch (e: de.terletzkiy.ansibility.semantics.value.IndeterminateValueException) {
        RValue.Hole(Placeholder.notEmulated("a Python literal with a tuple or set"))
    } catch (e: Exception) {
        value
    }

    /** 2.18's `ansible_eval_concat`: a result that looks like a list, dict or bool is `literal_eval`ed back. */
    private fun classicConcat(value: RValue.Str): RValue {
        val text = value.value
        if (!(text.startsWith("{") || text.startsWith("[") || text == "True" || text == "False")) return value
        return try {
            RValues.fromPy(de.terletzkiy.ansibility.semantics.coerce.PyLiteral.eval(text))
        } catch (e: de.terletzkiy.ansibility.semantics.value.IndeterminateValueException) {
            RValue.Hole(Placeholder.notEmulated("a Python literal with a tuple or set"))
        } catch (e: Exception) {
            value
        }
    }

    private fun builtin(name: String): RValue? = when (name) {
        "range" -> RValue.Callable("range") { args, _ -> Globals.range(args) }
        "dict" -> RValue.Callable("dict") { args, kwargs ->
            val d = RValue.Dict()
            args.firstOrNull()?.let { (it as? RValue.Dict)?.entries?.forEach { (k, v) -> d[k] = v } }
            kwargs.forEach { (k, v) -> d[k] = v }
            d
        }
        "namespace" -> RValue.Callable("namespace") { args, kwargs ->
            val attrs = LinkedHashMap<String, RValue>()
            (args.firstOrNull() as? RValue.Dict)?.entries?.forEach { (k, v) -> attrs[RValues.str(k, tuples)] = v }
            attrs.putAll(kwargs)
            RValue.Obj("Namespace", attrs, mutable = true)
        }
        "joiner" -> RValue.Callable("joiner") { args, _ ->
            val sep = args.firstOrNull()?.let { RValues.str(it, tuples) } ?: ", "
            var used = false
            RValue.Callable("joiner") { _, _ -> RValue.Str(if (used) sep else "").also { used = true } }
        }
        "cycler" -> RValue.Callable("cycler") { args, _ ->
            var pos = 0
            val obj = RValue.Obj("Cycler")
            obj.attributes["next"] = RValue.Callable("next") { _, _ -> args[pos % args.size].also { pos++ } }
            obj.attributes["reset"] = RValue.Callable("reset") { _, _ -> RValue.None.also { pos = 0 } }
            obj
        }
        "lookup" -> RValue.Callable("lookup") { args, kwargs -> lookupCall(args, kwargs, wantlist = false) }
        "query", "q" -> RValue.Callable(name) { args, kwargs -> lookupCall(args, kwargs, wantlist = true) }
        "now" -> RValue.Callable("now") { _, _ -> RValue.Hole(Placeholder(Placeholder.Kind.CONTROLLER, "now()")) }
        "lipsum" -> RValue.Callable("lipsum") { _, _ -> RValue.Hole(Placeholder.notEmulated("lipsum()")) }
        "undef" -> RValue.Callable("undef") { args, _ -> RValue.Undefined("undef", args.firstOrNull()?.let { RValues.str(it, tuples) } ?: "undefined") }
        else -> null
    }

    private fun lookupCall(args: List<RValue>, kwargs: Map<String, RValue>, wantlist: Boolean): RValue {
        val plugin = args.firstOrNull() ?: throw RenderError("lookup() requires a plugin name")
        if (plugin is RValue.Hole) return RValue.Hole(Placeholder.notEmulated("lookup of ${plugin.placeholder.text}"))
        val name = RValues.str(plugin, tuples).substringAfterLast('.')
        val terms = args.drop(1)
        val want = wantlist || (kwargs["wantlist"]?.let { RValues.truthy(it) } ?: false)
        val options = kwargs - "wantlist"
        RValues.firstHole(terms + options.values)?.let { return it }
        val result: RValue = Lookups.pure(name, terms, options, { lookupName(it) }, tuples)
            ?: lookups.lookup(name, terms, options)
            ?: return RValue.Hole(Placeholder.notEmulated("lookup('$name')"))
        if (result is RValue.Hole || result is RValue.Undefined) return result
        val list = RValues.sequence(result)?.map(::lookupResult) ?: return lookupResult(result)
        if (want) return RValue.List(list)
        if (list.all { it is RValue.Str }) return lookupResult(RValue.Str(list.joinToString(",") { (it as RValue.Str).value }))
        return if (list.size == 1) list[0] else RValue.List(list)
    }

    /** 2.18 marks every string a lookup returns unsafe (`wrap_var`). */
    private fun lookupResult(value: RValue): RValue =
        if (!options.native && value is RValue.Str && !value.native) RValue.Str(value.value, origin = "AnsibleUnsafeText") else value

    private fun lookupName(name: String): RValue = lookup(name, Frame(null))

    // ============================================================================================ expressions

    /** Evaluates [expr]; a hole in any operand makes the result that hole. */
    private fun eval(expr: JinjaExpr, frame: Frame): RValue = try {
        step()
        evalInner(expr, frame)
    } catch (h: HoleSignal) {
        h.hole
    }

    private fun evalInner(expr: JinjaExpr, frame: Frame): RValue = when (expr) {
        is JinjaExpr.Name -> lookup(expr.name, frame)
        is JinjaExpr.Const -> RValues.fromPy(expr.value)
        is JinjaExpr.ListLiteral -> RValue.List(expr.items.map { eval(it, frame) })
        is JinjaExpr.TupleLiteral -> RValue.Tuple(expr.items.map { eval(it, frame) })
        is JinjaExpr.DictLiteral -> {
            val d = RValue.Dict()
            for ((k, v) in expr.entries) {
                val key = eval(k, frame)
                RValues.requireKnown(key)
                d[RValues.requireDefined(key)] = eval(v, frame)
            }
            d
        }
        is JinjaExpr.Attribute -> Access.attribute(eval(expr.target, frame), expr.attribute, options)
        is JinjaExpr.Subscript -> subscript(expr, frame)
        is JinjaExpr.Filter -> filter(expr, frame)
        is JinjaExpr.Test -> test(expr, frame)
        is JinjaExpr.Call -> call(expr, frame)
        is JinjaExpr.Conditional -> {
            when (decide(RValues.requireDefined(eval(expr.condition, frame)))) {
                true -> eval(expr.then, frame)
                false -> expr.otherwise?.let { eval(it, frame) }
                    ?: RValue.Undefined("if", "the inline if-expression on line 1 evaluated to false and no else section was defined.", lenient = true)
                null -> RValues.firstHole(eval(expr.condition, frame)) ?: RValue.Hole(Placeholder.unknown("unknown condition"))
            }
        }
        is JinjaExpr.Concat -> {
            val parts = expr.parts.map { eval(it, frame) }
            parts.forEach { RValues.requireDefined(it) }
            RValues.requireKnown(*parts.toTypedArray())
            RValue.Str(parts.joinToString("") { if (it == RValue.None) "None" else RValues.str(it, tuples) })
        }
        is JinjaExpr.Binary -> binary(expr, frame)
        is JinjaExpr.Unary -> {
            val operand = eval(expr.operand, frame)
            when (expr.operator) {
                "not" -> RValues.bool(!RValues.truthy(RValues.requireDefined(operand)))
                "-" -> Ops.negate(operand)
                else -> Ops.positive(operand)
            }
        }
    }

    private fun subscript(expr: JinjaExpr.Subscript, frame: Frame): RValue {
        val target = eval(expr.target, frame)
        val slice = expr.slice
        if (slice != null) {
            val bounds = slice.map { b -> b?.let { eval(it, frame) } }
            RValues.requireKnown(target, *bounds.filterNotNull().toTypedArray())
            return Access.slice(RValues.requireDefined(target), bounds)
        }
        val key = expr.key?.let { eval(it, frame) } ?: throw RenderError("tuple subscripts are not supported")
        return Access.item(target, key, options)
    }

    private fun binary(expr: JinjaExpr.Binary, frame: Frame): RValue {
        when (expr.operator) {
            "and" -> {
                val left = RValues.requireDefined(eval(expr.left, frame))
                return when (decide(left)) {
                    false -> left
                    true -> eval(expr.right, frame)
                    null -> left
                }
            }
            "or" -> {
                val left = RValues.requireDefined(eval(expr.left, frame))
                return when (decide(left)) {
                    true -> left
                    false -> eval(expr.right, frame)
                    null -> left
                }
            }
        }
        val left = eval(expr.left, frame)
        val right = eval(expr.right, frame)
        RValues.requireKnown(left, right)
        RValues.requireDefined(left)
        RValues.requireDefined(right)
        return when (expr.operator) {
            "==" -> RValues.bool(RValues.pyEquals(left, right))
            "!=" -> RValues.bool(!RValues.pyEquals(left, right))
            "<" -> RValues.bool(RValues.compare(left, right, "<") < 0)
            ">" -> RValues.bool(RValues.compare(left, right, ">") > 0)
            "<=" -> RValues.bool(RValues.compare(left, right, "<=") <= 0)
            ">=" -> RValues.bool(RValues.compare(left, right, ">=") >= 0)
            "in" -> RValues.bool(RValues.contains(right, left))
            "not in" -> RValues.bool(!RValues.contains(right, left))
            else -> Ops.arithmetic(expr.operator, left, right, tuples)
        }
    }

    private fun call(expr: JinjaExpr.Call, frame: Frame): RValue {
        val callee = eval(expr.callee, frame)
        val args = expr.args.map { eval(it, frame) }
        val kwargs = expr.kwargs.mapValues { eval(it.value, frame) }
        return invoke(callee, args, kwargs)
    }

    private fun invoke(callee: RValue, args: List<RValue>, kwargs: Map<String, RValue>): RValue = when (callee) {
        is RValue.Callable -> callee.call(args, kwargs)
        is RValue.Hole -> callee
        is RValue.Undefined -> throw RenderError(callee.message)
        is RValue.Obj -> (callee.attributes["__call__"] as? RValue.Callable)?.call?.invoke(args, kwargs)
            ?: throw RenderError("'${callee.kind}' object is not callable")
        else -> throw RenderError("'${callee.typeName}' object is not callable")
    }

    private val filterContext = object : FilterContext {
        override val options: RenderOptions get() = this@TemplateRenderer.options
        override fun str(value: RValue): String = RValues.str(value, tuples)
        override fun filter(name: String, value: RValue, args: List<RValue>, kwargs: Map<String, RValue>): RValue =
            Filters.apply(name, value, args, kwargs, this)
        override fun test(name: String, value: RValue, args: List<RValue>, kwargs: Map<String, RValue>): RValue =
            Tests.apply(name, value, args, kwargs, this)
        override fun call(callee: RValue, args: List<RValue>, kwargs: Map<String, RValue>): RValue = invoke(callee, args, kwargs)
    }

    private fun filter(expr: JinjaExpr.Filter, frame: Frame): RValue {
        val value = eval(expr.target, frame)
        val args = expr.args.map { eval(it, frame) }
        val kwargs = expr.kwargs.mapValues { eval(it.value, frame) }
        return Filters.apply(expr.name, value, args, kwargs, filterContext)
    }

    private fun test(expr: JinjaExpr.Test, frame: Frame): RValue {
        val value = eval(expr.target, frame)
        val args = expr.args.map { eval(it, frame) }
        val kwargs = expr.kwargs.mapValues { eval(it.value, frame) }
        val result = Tests.apply(expr.name, value, args, kwargs, filterContext)
        if (!expr.negated || result is RValue.Hole) return result
        return RValues.bool(!RValues.truthy(result))
    }

    /** `range()`. */
    private object Globals {
        fun range(args: List<RValue>): RValue {
            RValues.requireKnown(*args.toTypedArray())
            val ints = args.map { (RValues.number(it) as? BigInteger)?.toLong() ?: throw RenderError("'${it.typeName}' object cannot be interpreted as an integer") }
            val (start, stop, step) = when (ints.size) {
                1 -> Triple(0L, ints[0], 1L)
                2 -> Triple(ints[0], ints[1], 1L)
                3 -> Triple(ints[0], ints[1], ints[2])
                else -> throw RenderError("range expected at most 3 arguments, got ${ints.size}")
            }
            if (step == 0L) throw RenderError("range() arg 3 must not be zero")
            val count = if (step > 0) maxOf(0L, (stop - start + step - 1) / step) else maxOf(0L, (start - stop - step - 1) / -step)
            if (count > 100_000) throw RenderError("Range too big. The sandbox blocks ranges larger than MAX_RANGE (100000).")
            val items: MutableList<RValue> = (0 until count).mapTo(ArrayList()) { RValue.Int(start + it * step) }
            return RValue.List(items, range = true)
        }
    }
}
