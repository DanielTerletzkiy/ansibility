package de.terletzkiy.ansibility.semantics.render

import de.terletzkiy.ansibility.semantics.value.PyRepr
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Base64

/** What filters and tests need from the renderer: options, printing, and calls back into filters, tests and macros. */
interface FilterContext {
    val options: RenderOptions
    fun str(value: RValue): String
    fun filter(name: String, value: RValue, args: List<RValue>, kwargs: Map<String, RValue>): RValue
    fun test(name: String, value: RValue, args: List<RValue>, kwargs: Map<String, RValue>): RValue
    fun call(callee: RValue, args: List<RValue>, kwargs: Map<String, RValue>): RValue
}

/** Positional-or-keyword arguments of a filter call, Python style. */
internal class Args(private val args: List<RValue>, private val kwargs: Map<String, RValue>) {
    fun get(index: Int, name: String): RValue? = args.getOrNull(index) ?: kwargs[name]

    fun known(index: Int, name: String): RValue? = get(index, name)?.also { RValues.requireKnown(it); RValues.requireDefined(it) }

    fun str(index: Int, name: String, default: String? = null): String? = when (val v = known(index, name)) {
        null -> default
        RValue.None -> default
        is RValue.Str -> v.value
        else -> throw RenderError("$name must be a string, not ${v.typeName}")
    }

    fun int(index: Int, name: String, default: Int? = null): Int? = when (val v = known(index, name)) {
        null, RValue.None -> default
        is RValue.Int -> v.value.toInt()
        is RValue.Bool -> if (v.value) 1 else 0
        is RValue.Float -> v.value.toInt()
        is RValue.Str -> v.value.trim().toIntOrNull() ?: throw RenderError("invalid literal for int() with base 10: ${PyRepr.strRepr(v.value)}")
        else -> throw RenderError("$name must be an integer, not ${v.typeName}")
    }

    fun bool(index: Int, name: String, default: Boolean = false): Boolean = known(index, name)?.let { RValues.truthy(it) } ?: default

    val positional: List<RValue> get() = args
    val keywords: Map<String, RValue> get() = kwargs
}

/**
 * The filters of Jinja and ansible-core that are pure functions of their input. A filter the renderer cannot
 * reproduce exactly gives a not-emulated hole; a hole input gives that hole (the secret taint survives every filter).
 */
object Filters {
    /** Filters that run on controller state or randomness: never emulated. */
    private val NOT_EMULATED = setOf(
        "random", "shuffle", "password_hash", "expanduser", "expandvars", "realpath", "strftime", "to_datetime",
        "from_yaml", "from_yaml_all", "vault", "unvault", "comment", "wordwrap", "urlize", "pprint", "xmlattr",
        "striptags", "forceescape", "ipaddr", "ipv4", "ipv6", "ipwrap", "json_query", "random_mac", "win_basename",
        "win_dirname", "win_splitdrive", "groupby", "urlsplit",
    )

    fun apply(name: String, value: RValue, args: List<RValue>, kwargs: Map<String, RValue>, ctx: FilterContext): RValue {
        val short = shortName(name)
        val a = Args(args, kwargs)
        when (short) {
            "default", "d" -> return default(value, a)
            "mandatory" -> {
                if (value is RValue.Undefined) throw RenderError(a.str(0, "msg") ?: "Mandatory variable ${PyRepr.strRepr(value.name)} not defined.")
                return value
            }
            "safe" -> return value
        }
        if (short in NOT_EMULATED || name.contains('.') && !name.startsWith("ansible.builtin.")) return RValue.Hole(Placeholder.notEmulated("filter $name"))
        RValues.requireDefined(value)
        val tuples = ctx.options.tuplesAsLists
        return when (short) {
            // ---------------------------------------------------------------- shape-only (holes inside are fine)
            "length", "count" -> {
                RValues.requireKnown(value)
                RValues.int(
                    when (value) {
                        is RValue.Str -> value.value.codePointCount(0, value.value.length)
                        is RValue.List -> value.items.size
                        is RValue.Tuple -> value.items.size
                        is RValue.Dict -> value.map.size
                        else -> throw RenderError("object of type '${value.typeName}' has no len()")
                    },
                )
            }
            "first" -> shallow(value).firstOrNull() ?: RValue.Undefined("first", "No first item, sequence was empty.")
            "last" -> shallow(value).lastOrNull() ?: RValue.Undefined("last", "No last item, sequence was empty.")
            "list" -> RValue.List(shallow(value))
            "reverse" -> if (value is RValue.Str) RValue.Str(value.value.reversed()) else RValue.List(shallow(value).reversed())
            "batch" -> {
                val items = shallow(value)
                val n = a.int(0, "linecount") ?: throw RenderError("batch() missing linecount")
                if (n <= 0) throw RenderError("batch size must be positive")
                val fill = a.get(1, "fill_with")
                RValue.List(items.chunked(n).map { chunk -> RValue.List(if (fill != null && chunk.size < n) chunk + List(n - chunk.size) { fill } else chunk) })
            }
            "slice" -> {
                val items = shallow(value)
                val slices = a.int(0, "slices") ?: throw RenderError("slice() missing slices")
                val fill = a.get(1, "fill_with")
                val per = items.size / slices
                val extra = items.size % slices
                var offset = 0
                RValue.List(
                    (0 until slices).map { i ->
                        val start = offset + i * per
                        if (i < extra) offset++
                        val end = offset + (i + 1) * per
                        val part = items.subList(start, end).toMutableList()
                        if (fill != null && i >= extra) part += fill
                        RValue.List(part)
                    },
                )
            }
            "dict2items" -> {
                RValues.requireKnown(value)
                val dict = value as? RValue.Dict ?: throw RenderError("dict2items requires a dictionary, got ${value.typeName} instead.")
                val k = a.str(0, "key_name", "key")!!
                val v = a.str(1, "value_name", "value")!!
                RValue.List(dict.entries.map { (key, item) -> RValue.Dict.ofStrings(k to key, v to item) })
            }
            "items2dict" -> {
                RValues.requireKnown(value)
                val k = a.str(0, "key_name", "key")!!
                val v = a.str(1, "value_name", "value")!!
                val out = RValue.Dict()
                for (item in shallowKnown(value)) {
                    val d = item as? RValue.Dict ?: throw RenderError("items2dict requires a list of dictionaries, got ${item.typeName} instead.")
                    val key = d[k] ?: throw RenderError("items2dict requires each dictionary to have keys '$k' and '$v'")
                    RValues.requireKnown(key)
                    out[key] = d[v] ?: throw RenderError("items2dict requires each dictionary to have keys '$k' and '$v'")
                }
                out
            }
            "items" -> {
                RValues.requireKnown(value)
                val dict = value as? RValue.Dict ?: throw RenderError("Can only get item pairs from a mapping.")
                RValue.List(dict.entries.map { RValue.Tuple(listOf(it.first, it.second)) })
            }
            "combine" -> combine(value, a)
            "map" -> map(value, a, ctx)
            "select", "reject" -> RValue.List(shallow(value).filter { selects(it, a, 0, ctx) == (short == "select") })
            "selectattr", "rejectattr" -> {
                val attribute = a.str(0, "attribute") ?: throw RenderError("Missing parameter for attribute name")
                RValue.List(
                    shallow(value).filter { item ->
                        val field = attributeOf(item, attribute, ctx)
                        selects(field, a, 1, ctx) == (short == "selectattr")
                    },
                )
            }
            "attr" -> Access.attribute(value, a.str(0, "name") ?: "", ctx.options)
            "extract" -> {
                val container = a.known(0, "container") ?: throw RenderError("extract() missing container")
                var out = Access.item(container, value, ctx.options)
                a.get(1, "morekeys")?.let { more ->
                    for (key in if (more is RValue.List || more is RValue.Tuple) RValues.sequence(more)!! else listOf(more)) out = Access.item(out, key, ctx.options)
                }
                RValues.requireDefined(out)
            }
            "zip", "zip_longest" -> {
                val lists = listOf(shallow(value)) + a.positional.map { shallow(RValues.requireDefined(it)) }
                val n = if (short == "zip") lists.minOf { it.size } else lists.maxOf { it.size }
                val fill = a.keywords["fillvalue"] ?: RValue.None
                RValue.List((0 until n).map { i -> RValue.Tuple(lists.map { it.getOrNull(i) ?: fill }) })
            }
            "subelements" -> subelements(value, a)
            "flatten" -> {
                val levels = a.int(0, "levels")
                val skipNulls = a.bool(1, "skip_nulls", true)
                RValue.List(flatten(shallow(value), levels, skipNulls))
            }
            "ternary" -> {
                if (value == RValue.None && a.get(2, "none_val") != null) a.get(2, "none_val")!!
                else if (RValues.truthy(value)) a.get(0, "true_val") ?: RValue.None
                else a.get(1, "false_val") ?: RValue.None
            }
            "type_debug" -> {
                RValues.requireKnown(value)
                // 2.19+: an undefined item marks the whole container undefined.
                val marker = (value as? RValue.List)?.items?.firstOrNull { it is RValue.Undefined }
                if (ctx.options.native && marker != null) return marker
                RValue.Str(
                    when (value) {
                        is RValue.Str -> when {
                            value.origin == "Markup" -> "Markup"
                            ctx.options.native -> "str"
                            else -> value.origin ?: if (value.native) "NativeJinjaText" else "str"
                        }
                        else -> value.typeName
                    },
                )
            }
            else -> known(short, name, value, a, ctx, tuples).let { result ->
                // 2.18: a filter of an unsafe string returns an unsafe string.
                val unsafe = !ctx.options.native && (value as? RValue.Str)?.origin == "AnsibleUnsafeText"
                if (unsafe && result is RValue.Str && result.origin == null && !result.native) result.copy(origin = "AnsibleUnsafeText") else result
            }
        }
    }

    /** Filters that need their whole input known. */
    private fun known(short: String, name: String, value: RValue, a: Args, ctx: FilterContext, tuples: Boolean): RValue {
        RValues.requireKnown(value)
        return when (short) {
            "string" -> RValue.Str(ctx.str(value), native = true)
            "lower" -> RValue.Str(text(value, ctx).lowercase())
            "upper" -> RValue.Str(text(value, ctx).uppercase())
            "capitalize" -> RValue.Str(text(value, ctx).lowercase().replaceFirstChar { it.uppercaseChar() })
            "title" -> RValue.Str(
                WORD_BEGINNING.split(text(value, ctx)).filter { it.isNotEmpty() }
                    .joinToString("") { it.substring(0, 1).uppercase() + it.substring(1).lowercase() },
            )
            "trim" -> RValue.Str(PyStrings.strip(text(value, ctx), a.str(0, "chars"), left = true, right = true))
            "center" -> RValue.Str(PyStrings.pad(text(value, ctx), a.int(0, "width", 80)!!, ' ', "center"))
            "replace" -> {
                val old = a.str(0, "old") ?: throw RenderError("replace() missing old")
                val new = a.str(1, "new") ?: throw RenderError("replace() missing new")
                RValue.Str(PyStrings.replace(text(value, ctx), old, new, a.int(2, "count") ?: -1))
            }
            "wordcount" -> RValues.int(Regex("\\w+", RegexOption.IGNORE_CASE).findAll(text(value, ctx)).count())
            "truncate" -> truncate(text(value, ctx), a)
            "indent" -> indent(text(value, ctx), a)
            "e", "escape" -> if ((value as? RValue.Str)?.origin == "Markup") value else RValue.Str(htmlEscape(text(value, ctx)), origin = "Markup")
            "urlencode" -> urlencode(value, ctx)
            "join" -> {
                val separator = a.str(0, "d", "")!!
                val attribute = a.str(1, "attribute")
                val items = RValues.iterate(value).map { if (attribute != null) attributeOf(it, attribute, ctx) else it }
                RValues.requireKnown(*items.toTypedArray())
                RValue.Str(items.joinToString(separator) { ctx.str(RValues.requireDefined(it)) })
            }
            "split" -> {
                val sep = a.str(0, "sep")
                val max = a.int(1, "maxsplit", -1)!!
                RValue.List(PyStrings.split(text(value, ctx), sep, max, fromRight = false).map { RValue.Str(it) })
            }
            "int" -> toInt(value, a)
            "float" -> toFloat(value, a)
            "bool" -> toBool(value, ctx)
            "abs" -> when (value) {
                is RValue.Int -> RValue.Int(value.value.abs())
                is RValue.Float -> RValue.Float(kotlin.math.abs(value.value))
                is RValue.Bool -> RValues.int(if (value.value) 1 else 0)
                else -> throw RenderError("bad operand type for abs(): '${value.typeName}'")
            }
            "round" -> round(value, a)
            "log" -> {
                val x = (RValues.number(value) ?: throw RenderError("log() requires a number")).toDouble()
                val base = a.get(0, "base")?.let { RValues.number(it)?.toDouble() }
                RValue.Float(if (base == null) Math.log(x) else Math.log(x) / Math.log(base))
            }
            "pow" -> RValue.Float(Math.pow((RValues.number(value) ?: throw RenderError("pow() requires a number")).toDouble(), (a.get(0, "y")?.let(RValues::number) ?: throw RenderError("pow() requires a number")).toDouble()))
            "root" -> {
                val x = (RValues.number(value) ?: throw RenderError("root() requires a number")).toDouble()
                val base = a.get(0, "base")?.let { RValues.number(it)?.toDouble() } ?: 2.0
                RValue.Float(if (base == 2.0) Math.sqrt(x) else Math.pow(x, 1.0 / base))
            }
            "min", "max" -> {
                val attribute = a.str(1, "attribute")
                val items = RValues.iterate(value)
                if (items.isEmpty()) return RValue.Undefined(short, "No aggregated item, sequence was empty.")
                val caseSensitive = a.bool(0, "case_sensitive")
                val keyed = items.map { (if (attribute != null) attributeOf(it, attribute, ctx) else it) to it }
                val pick = keyed.reduce { best, next ->
                    val c = RValues.compare(sortKey(next.first, caseSensitive), sortKey(best.first, caseSensitive), "<")
                    if (short == "min" && c < 0 || short == "max" && c > 0) next else best
                }
                pick.second
            }
            "sum" -> {
                val attribute = a.str(0, "attribute")
                var total: RValue = a.known(1, "start") ?: RValues.int(0)
                for (item in RValues.iterate(value)) {
                    total = Ops.arithmetic("+", total, if (attribute != null) attributeOf(item, attribute, ctx) else item, tuples)
                }
                total
            }
            "sort" -> {
                val reverse = a.bool(0, "reverse")
                val caseSensitive = a.bool(1, "case_sensitive")
                val attributes = a.str(2, "attribute")?.split(',')
                val items = RValues.iterate(value)
                fun key(item: RValue): RValue = when {
                    attributes == null -> sortKey(item, caseSensitive)
                    attributes.size == 1 -> sortKey(attributeOf(item, attributes[0], ctx), caseSensitive)
                    else -> RValue.Tuple(attributes.map { sortKey(attributeOf(item, it, ctx), caseSensitive) })
                }
                val sorted = items.sortedWith { x, y -> RValues.compare(key(x), key(y), "<") }
                RValue.List(if (reverse) sorted.reversed() else sorted)
            }
            "dictsort" -> {
                val dict = value as? RValue.Dict ?: throw RenderError("You can only sort by either \"key\" or \"value\"")
                val caseSensitive = a.bool(0, "case_sensitive")
                val by = a.str(1, "by", "key")!!
                val reverse = a.bool(2, "reverse")
                val index = when (by) {
                    "key" -> 0
                    "value" -> 1
                    else -> throw RenderError("You can only sort by either \"key\" or \"value\"")
                }
                val sorted = dict.entries.sortedWith { x, y ->
                    RValues.compare(sortKey(if (index == 0) x.first else x.second, caseSensitive), sortKey(if (index == 0) y.first else y.second, caseSensitive), "<")
                }
                RValue.List((if (reverse) sorted.reversed() else sorted).map { RValue.Tuple(listOf(it.first, it.second)) })
            }
            "unique" -> {
                val caseSensitive = a.bool(0, "case_sensitive")
                val attribute = a.str(1, "attribute")
                RValue.List(unique(RValues.iterate(value), caseSensitive) { if (attribute != null) attributeOf(it, attribute, ctx) else it })
            }
            "union", "intersect", "difference", "symmetric_difference" -> setOperation(short, RValues.iterate(value), listArg(a, 0, "b"))
            "format" -> {
                val fmt = text(value, ctx)
                RValue.Str(if (a.keywords.isNotEmpty()) PyFormat.percent(fmt, RValue.Dict.of(a.keywords.map { RValue.Str(it.key) to it.value }), tuples) else PyFormat.percent(fmt, RValue.Tuple(a.positional), tuples))
            }
            "to_json" -> RValue.Str(Json.dumps(value, a, nice = false), native = true)
            "to_nice_json" -> RValue.Str(Json.dumps(value, a, nice = true), native = true)
            "tojson" -> RValue.Str(Json.htmlSafe(value, a), origin = "Markup")
            "from_json" -> {
                val parsed = try {
                    de.terletzkiy.ansibility.semantics.coerce.PyJson.loads(text(value, ctx))
                } catch (e: Exception) {
                    throw RenderError(e.message ?: "Expecting value")
                }
                RValues.fromPy(parsed)
            }
            "to_yaml", "to_nice_yaml" -> YamlDump.dump(value, nice = short == "to_nice_yaml", indent = a.int(0, "indent"), a)
                ?.let { RValue.Str(it, native = true) }
                ?: RValue.Hole(Placeholder.notEmulated("filter $name for this value"))
            "b64encode" -> RValue.Str(Base64.getEncoder().encodeToString(text(value, ctx).toByteArray(charset(a.str(0, "encoding", "utf-8")!!))))
            "b64decode" -> {
                val bytes = try {
                    Base64.getMimeDecoder().decode(text(value, ctx))
                } catch (e: IllegalArgumentException) {
                    throw RenderError("Incorrect padding")
                }
                RValue.Str(String(bytes, charset(a.str(0, "encoding", "utf-8")!!)))
            }
            "basename" -> RValue.Str(text(value, ctx).substringAfterLast('/'))
            "dirname" -> RValue.Str(dirname(text(value, ctx)))
            "splitext" -> {
                val path = text(value, ctx)
                val base = path.substringAfterLast('/')
                val dot = base.lastIndexOf('.')
                val split = if (dot <= 0 || base.substring(0, dot).all { it == '.' }) -1 else path.length - base.length + dot
                RValue.Tuple(if (split < 0) listOf(RValue.Str(path), RValue.Str("")) else listOf(RValue.Str(path.substring(0, split)), RValue.Str(path.substring(split))))
            }
            "path_join" -> {
                val parts = if (value is RValue.Str) listOf(value.value) else RValues.iterate(value).map { text(it, ctx) }
                RValue.Str(parts.fold("") { acc, part -> if (part.startsWith("/") || acc.isEmpty()) part else if (acc.endsWith("/")) acc + part else "$acc/$part" })
            }
            "relpath" -> RValue.Str(relpath(text(value, ctx), a.str(0, "start") ?: return RValue.Hole(Placeholder.notEmulated("relpath without start"))))
            "quote" -> RValue.Str(shellQuote(ctx.str(value)))
            "regex_replace" -> Regexes.replace(text(value, ctx), a)
            "regex_search" -> Regexes.search(text(value, ctx), a)
            "regex_findall" -> Regexes.findall(text(value, ctx), a)
            "regex_escape" -> {
                if (a.str(0, "re_type", "python") != "python") return RValue.Hole(Placeholder.notEmulated("regex_escape re_type"))
                RValue.Str(Regexes.escape(text(value, ctx)))
            }
            "hash" -> digest(ctx.str(value), a.str(0, "hashtype", "sha1")!!)
            "md5" -> digest(ctx.str(value), "md5")
            "sha1", "checksum" -> digest(ctx.str(value), "sha1")
            "to_uuid" -> RValue.Str(uuid5(a.str(0, "namespace", "361E6D51-FAEC-444A-9079-341386DA8E2E")!!, ctx.str(value)))
            "human_readable" -> humanReadable(value, a)
            "human_to_bytes" -> {
                val py = RValues.toPyValue(value) ?: throw RenderError("human_to_bytes() can't interpret ${value.typeName}")
                try {
                    RValue.Int(de.terletzkiy.ansibility.semantics.coerce.HumanToBytes.parse(py, a.str(0, "default_unit"), a.bool(1, "isbits")))
                } catch (e: Exception) {
                    throw RenderError("human_to_bytes() can't interpret following string: ${ctx.str(value)}")
                }
            }
            else -> RValue.Hole(Placeholder.notEmulated("filter $name"))
        }
    }

    fun shortName(name: String): String = name.removePrefix("ansible.builtin.").removePrefix("ansible.legacy.")

    // ---------------------------------------------------------------------------------------------------- helpers

    /** Jinja's `_word_beginning_split_re`: `title` capitalises after `-`, whitespace and opening brackets only. */
    private val WORD_BEGINNING = Regex("(?<=[-\\s({\\[<])|(?=[-\\s({\\[<])")

    /**
     * ansible-core's set filters: `list(set(a) | set(b))` and friends, falling back to order-keeping lists when an
     * item is unhashable. The order of a set of strings depends on the hash seed of the run, so it is not proven.
     */
    private fun setOperation(op: String, a: List<RValue>, b: List<RValue>): RValue {
        RValues.requireKnown(*(a + b).toTypedArray())
        if ((a + b).any { !hashable(it) }) {
            return RValue.List(
                when (op) {
                    "union" -> unique(a + b, true) { it }
                    "intersect" -> unique(a.filter { x -> b.any { RValues.pyEquals(x, it) } }, true) { it }
                    "difference" -> unique(a.filter { x -> b.none { RValues.pyEquals(x, it) } }, true) { it }
                    else -> {
                        val both = unique(a.filter { x -> b.any { RValues.pyEquals(x, it) } }, true) { it }
                        unique(a + b, true) { it }.filter { x -> both.none { RValues.pyEquals(x, it) } }
                    }
                },
            )
        }
        val ints = (a + b).all { it is RValue.Int || it is RValue.Bool }
        if (!ints) return RValue.Hole(Placeholder.unknown("$op: the order of a set varies per run"))
        val x = CPythonIntSet.of(a)
        val y = CPythonIntSet.of(b)
        return RValue.List(
            when (op) {
                "union" -> x.union(y)
                "intersect" -> x.intersection(y)
                "difference" -> x.difference(y)
                else -> x.symmetricDifference(y)
            }.items(),
        )
    }

    private fun hashable(value: RValue): Boolean = when (value) {
        is RValue.List, is RValue.Dict -> false
        is RValue.Tuple -> value.items.all(::hashable)
        else -> true
    }

    private fun default(value: RValue, a: Args): RValue {
        val fallback = a.get(0, "default_value") ?: RValues.EMPTY
        val boolean = a.get(1, "boolean")?.let { RValues.truthy(it) } ?: false
        return when {
            value is RValue.Undefined -> fallback
            value is RValue.Hole && boolean -> value
            boolean && !RValues.truthy(value) -> fallback
            else -> value
        }
    }

    /** The items of a list-like value; holes among the items are kept. */
    private fun shallow(value: RValue): List<RValue> {
        if (value is RValue.Hole) throw HoleSignal(value)
        return RValues.iterate(value)
    }

    private fun shallowKnown(value: RValue): List<RValue> = shallow(value).onEach { RValues.requireKnown(it) }

    private fun listArg(a: Args, index: Int, name: String): List<RValue> =
        RValues.iterate(a.known(index, name) ?: throw RenderError("missing required argument '$name'"))

    private fun text(value: RValue, ctx: FilterContext): String = (value as? RValue.Str)?.value ?: ctx.str(value)

    private fun sortKey(value: RValue, caseSensitive: Boolean): RValue =
        if (!caseSensitive && value is RValue.Str) RValue.Str(value.value.lowercase()) else value

    /** Jinja's `make_attrgetter`: dotted path, integer parts index; a missing part gives undefined. */
    private fun attributeOf(item: RValue, path: String, ctx: FilterContext): RValue {
        var out = item
        for (part in path.split('.')) {
            out = part.toIntOrNull()?.let { Access.item(out, RValues.int(it), ctx.options) } ?: Access.item(out, RValue.Str(part), ctx.options)
        }
        return out
    }

    private fun selects(value: RValue, a: Args, from: Int, ctx: FilterContext): Boolean {
        val positional = a.positional.drop(from)
        val result = if (positional.isEmpty()) {
            value
        } else {
            val test = (positional[0] as? RValue.Str)?.value ?: throw RenderError("test name must be a string")
            ctx.test(test, value, positional.drop(1), a.keywords)
        }
        return RValues.truthy(result)
    }

    private fun map(value: RValue, a: Args, ctx: FilterContext): RValue {
        val items = shallow(value)
        val attribute = a.keywords["attribute"]
        if (attribute != null) {
            val path = (attribute as? RValue.Str)?.value ?: (attribute as? RValue.Int)?.value?.toString() ?: throw RenderError("attribute must be a string")
            val fallback = a.keywords["default"]
            return RValue.List(items.map { item -> attributeOf(item, path, ctx).let { if (it is RValue.Undefined && fallback != null) fallback else it } })
        }
        val filter = (a.positional.firstOrNull() as? RValue.Str)?.value ?: throw RenderError("map requires a filter argument")
        return RValue.List(items.map { ctx.filter(filter, it, a.positional.drop(1), a.keywords) })
    }

    private fun combine(value: RValue, a: Args): RValue {
        val recursive = a.keywords["recursive"]?.let { RValues.truthy(it) } ?: false
        val listMerge = (a.keywords["list_merge"] as? RValue.Str)?.value ?: "replace"
        if (listMerge !in setOf("replace", "keep", "append", "prepend", "append_rp", "prepend_rp")) {
            throw RenderError("'list_merge' is invalid, must be one of 'replace', 'keep', 'append', 'prepend', 'append_rp', 'prepend_rp'")
        }
        val dicts = (listOf(value) + a.positional).flatMap { item ->
            when (item) {
                is RValue.Dict -> listOf(item)
                is RValue.List, is RValue.Tuple -> RValues.sequence(item)!!.map { it as? RValue.Dict ?: throw RenderError("combine expects dictionaries, got ${it.typeName}") }
                is RValue.Hole -> throw HoleSignal(item)
                is RValue.Undefined -> throw RenderError(item.message)
                else -> throw RenderError("combine expects dictionaries, got ${item.typeName}")
            }
        }
        var out = RValue.Dict()
        for (d in dicts) out = merge(out, d, recursive, listMerge)
        return out
    }

    private fun merge(x: RValue.Dict, y: RValue.Dict, recursive: Boolean, listMerge: String): RValue.Dict {
        val out = RValue.Dict.of(x.entries)
        for ((key, right) in y.entries) {
            val left = out[key]
            out[key] = when {
                left == null -> right
                recursive && left is RValue.Dict && right is RValue.Dict -> merge(left, right, true, listMerge)
                left is RValue.List && right is RValue.List -> when (listMerge) {
                    "keep" -> left
                    "append" -> RValue.List(left.items + right.items)
                    "prepend" -> RValue.List(right.items + left.items)
                    "append_rp" -> RValue.List(left.items.filter { l -> right.items.none { RValues.pyEquals(l, it) } } + right.items)
                    "prepend_rp" -> RValue.List(right.items + left.items.filter { l -> right.items.none { RValues.pyEquals(l, it) } })
                    else -> right
                }
                else -> right
            }
        }
        return out
    }

    private fun subelements(value: RValue, a: Args): RValue {
        val key = a.str(0, "subelements") ?: throw RenderError("subelements requires a key")
        val skipMissing = a.bool(1, "skip_missing")
        val items = if (value is RValue.Dict) value.map.values.toList() else shallow(value)
        val out = ArrayList<RValue>()
        for (item in items) {
            RValues.requireKnown(item)
            var sub: RValue? = item
            for (part in key.split('.')) {
                val dict = sub as? RValue.Dict ?: throw RenderError("the key $part should point to a dictionary")
                sub = dict[part]
                if (sub == null) {
                    if (skipMissing) break
                    throw RenderError("could not find '$part' key in iterated item")
                }
            }
            if (sub == null) continue
            RValues.requireKnown(sub)
            val list = sub as? RValue.List ?: throw RenderError("the key $key should point to a list, got ${sub.typeName}")
            for (element in list.items) out += RValue.Tuple(listOf(item, element))
        }
        return RValue.List(out)
    }

    private fun flatten(items: List<RValue>, levels: Int?, skipNulls: Boolean): List<RValue> {
        val out = ArrayList<RValue>()
        for (item in items) {
            if (skipNulls && (item == RValue.None || item is RValue.Str && item.value in setOf("None", "null"))) continue
            val inner = RValues.sequence(item)
            if (inner != null) {
                if (levels == null) out += flatten(inner, null, skipNulls)
                else if (levels >= 1) out += flatten(inner, levels - 1, skipNulls)
                else out += item
            } else {
                out += item
            }
        }
        return out
    }

    private fun unique(items: List<RValue>, caseSensitive: Boolean, key: (RValue) -> RValue): List<RValue> {
        val seen = ArrayList<RValue>()
        val out = ArrayList<RValue>()
        for (item in items) {
            RValues.requireKnown(item)
            val k = sortKey(key(item), caseSensitive)
            if (seen.none { RValues.pyEquals(it, k) }) {
                seen += k
                out += item
            }
        }
        return out
    }

    private fun toInt(value: RValue, a: Args): RValue {
        val default = a.get(0, "default") ?: RValues.int(0)
        val base = a.int(1, "base", 10)!!
        return when (value) {
            is RValue.Int -> value
            is RValue.Bool -> RValues.int(if (value.value) 1 else 0)
            is RValue.Float -> if (value.value.isFinite()) RValue.Int(BigDecimal(value.value).toBigInteger()) else default
            is RValue.Str -> {
                val s = value.value.trim().replace("_", "")
                val parsed = try {
                    val (sign, digits) = if (s.startsWith("-") || s.startsWith("+")) s.substring(0, 1) to s.substring(1) else "" to s
                    if (base == 0) {
                        val prefixed = mapOf('x' to 16, 'o' to 8, 'b' to 2)[digits.getOrNull(1)?.lowercaseChar()]?.takeIf { digits.startsWith("0") }
                        when {
                            prefixed != null -> BigInteger(sign + digits.substring(2), prefixed)
                            digits.length > 1 && digits.startsWith("0") && digits.any { it != '0' } -> null
                            else -> BigInteger(sign + digits)
                        }
                    } else {
                        val stripped = if (base == 16) digits.removePrefix("0x").removePrefix("0X") else if (base == 8) digits.removePrefix("0o").removePrefix("0O") else if (base == 2) digits.removePrefix("0b").removePrefix("0B") else digits
                        BigInteger(sign + stripped, base)
                    }
                } catch (e: NumberFormatException) {
                    null
                }
                parsed?.let { RValue.Int(it) }
                    ?: s.toDoubleOrNull()?.takeIf { it.isFinite() && base == 10 }?.let { RValue.Int(BigDecimal(it).toBigInteger()) }
                    ?: default
            }
            else -> default
        }
    }

    private fun toFloat(value: RValue, a: Args): RValue {
        val default = a.get(0, "default") ?: RValue.Float(0.0)
        return when (value) {
            is RValue.Float -> value
            is RValue.Int -> RValue.Float(value.value.toDouble())
            is RValue.Bool -> RValue.Float(if (value.value) 1.0 else 0.0)
            is RValue.Str -> try {
                RValue.Float(de.terletzkiy.ansibility.semantics.coerce.PyNumbers.floatFromString(value.value))
            } catch (e: Exception) {
                default
            }
            else -> default
        }
    }

    private fun toBool(value: RValue, ctx: FilterContext): RValue {
        if (value is RValue.Bool) return value
        if (value == RValue.None) return if (ctx.options.native) RValues.FALSE else RValue.None
        val probe = when (value) {
            is RValue.Str -> value.value.lowercase()
            is RValue.Int -> value.value.toString()
            is RValue.Float -> PyRepr.floatRepr(value.value)
            else -> return RValues.FALSE
        }
        return RValues.bool(probe in setOf("yes", "on", "1", "true", "1.0"))
    }

    private fun round(value: RValue, a: Args): RValue {
        val x = (RValues.number(value) ?: throw RenderError("must be real number, not ${value.typeName}")).toDouble()
        val precision = a.int(0, "precision", 0)!!
        val method = a.str(1, "method", "common")!!
        if (!x.isFinite()) return RValue.Float(x)
        return RValue.Float(
            when (method) {
                "common" -> BigDecimal(x).setScale(precision, RoundingMode.HALF_EVEN).toDouble()
                "ceil" -> Math.ceil(x * Math.pow(10.0, precision.toDouble())) / Math.pow(10.0, precision.toDouble())
                "floor" -> Math.floor(x * Math.pow(10.0, precision.toDouble())) / Math.pow(10.0, precision.toDouble())
                else -> throw RenderError("method must be common, ceil or floor")
            },
        )
    }

    private fun truncate(s: String, a: Args): RValue {
        val length = a.int(0, "length", 255)!!
        val killwords = a.bool(1, "killwords")
        val end = a.str(2, "end", "...")!!
        val leeway = a.int(3, "leeway", 5)!!
        if (s.length <= length + leeway) return RValue.Str(s)
        if (killwords) return RValue.Str(s.substring(0, length - end.length) + end)
        val cut = s.substring(0, maxOf(0, length - end.length))
        val space = cut.lastIndexOf(' ')
        return RValue.Str((if (space >= 0) cut.substring(0, space) else "") + end)
    }

    private fun indent(text: String, a: Args): RValue {
        val widthArg = a.known(0, "width")
        val indention = if (widthArg is RValue.Str) widthArg.value else " ".repeat(a.int(0, "width", 4)!!)
        val first = a.bool(1, "first")
        val blank = a.bool(2, "blank")
        val lines = splitLines(text + "\n")
        var out = if (blank) {
            lines.joinToString("\n$indention")
        } else {
            lines.first() + if (lines.size > 1) "\n" + lines.drop(1).joinToString("\n") { if (it.isEmpty()) it else indention + it } else ""
        }
        if (first) out = indention + out
        return RValue.Str(out)
    }

    /** `str.splitlines()`. */
    internal fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u001C' || c == '\u001D' || c == '\u001E' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                out += s.substring(start, i)
                if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&#34;").replace("'", "&#39;")

    private fun urlencode(value: RValue, ctx: FilterContext): RValue {
        fun quote(s: String, safe: String) = buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "_.-~" || c in safe) append(c) else append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
        return RValue.Str(
            when (value) {
                is RValue.Dict -> value.entries.joinToString("&") { (k, v) -> quote(ctx.str(k), "") + "=" + quote(ctx.str(v), "") }
                is RValue.Str -> quote(value.value, "/")
                else -> quote(ctx.str(value), "/")
            },
        )
    }

    private fun dirname(path: String): String {
        val i = path.lastIndexOf('/') + 1
        val head = path.substring(0, i)
        return if (head.isNotEmpty() && head != "/".repeat(head.length)) head.trimEnd('/') else head
    }

    private fun relpath(path: String, start: String): String {
        fun parts(p: String) = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (!path.startsWith("/") || !start.startsWith("/")) throw RenderError("relpath of relative paths is not emulated")
        val p = parts(path)
        val s = parts(start)
        var common = 0
        while (common < minOf(p.size, s.size) && p[common] == s[common]) common++
        val rel = List(s.size - common) { ".." } + p.drop(common)
        return if (rel.isEmpty()) "." else rel.joinToString("/")
    }

    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (Regex("[^\\w@%+=:,./-]", RegexOption.IGNORE_CASE).find(s) == null && s.all { it.code < 128 }) return s
        return "'" + s.replace("'", "'\"'\"'") + "'"
    }

    private fun digest(s: String, type: String): RValue {
        val algorithm = when (type) {
            "md5" -> "MD5"
            "sha1" -> "SHA-1"
            "sha224" -> "SHA-224"
            "sha256" -> "SHA-256"
            "sha384" -> "SHA-384"
            "sha512" -> "SHA-512"
            else -> return RValue.Hole(Placeholder.notEmulated("hash '$type'"))
        }
        val bytes = MessageDigest.getInstance(algorithm).digest(s.toByteArray(Charsets.UTF_8))
        return RValue.Str(bytes.joinToString("") { "%02x".format(it) })
    }

    private fun uuid5(namespace: String, name: String): String {
        val ns = try {
            java.util.UUID.fromString(namespace)
        } catch (e: IllegalArgumentException) {
            throw RenderError("Invalid value '$namespace' for 'namespace': badly formed hexadecimal UUID string")
        }
        val buffer = java.nio.ByteBuffer.allocate(16).putLong(ns.mostSignificantBits).putLong(ns.leastSignificantBits).array()
        val hash = MessageDigest.getInstance("SHA-1").digest(buffer + name.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = java.nio.ByteBuffer.wrap(hash, 0, 16)
        return java.util.UUID(bb.long, bb.long).toString()
    }

    private fun humanReadable(value: RValue, a: Args): RValue {
        val isBits = a.bool(0, "isbits")
        val unit = a.str(1, "unit")
        val n = (RValues.number(value) ?: throw RenderError("human_readable() can't interpret following string: ${RValues.repr(value, false)}")).toDouble()
        val suffix = if (isBits) "b" else "B"
        val units = listOf("Y" to 80, "Z" to 70, "E" to 60, "P" to 50, "T" to 40, "G" to 30, "M" to 20, "K" to 10, "" to 0)
        val (prefix, power) = if (unit != null) units.firstOrNull { it.first == unit.uppercase().take(1) } ?: ("" to 0) else units.firstOrNull { n >= Math.pow(2.0, it.second.toDouble()) } ?: ("" to 0)
        val scaled = n / Math.pow(2.0, power.toDouble())
        val unitText = if (prefix.isEmpty()) (if (isBits) "bits" else "Bytes") else prefix + suffix
        return RValue.Str(String.format(java.util.Locale.ROOT, "%.2f %s", scaled, unitText))
    }
}

/** `json.dumps` as `to_json`, `to_nice_json` and Jinja's `tojson` call it. */
internal object Json {
    fun dumps(value: RValue, a: Args, nice: Boolean): String {
        val indent = if (nice) a.int(0, "indent", 4) else a.keywords["indent"]?.let { (it as? RValue.Int)?.value?.toInt() }
        val sortKeys = a.keywords["sort_keys"]?.let { RValues.truthy(it) } ?: nice
        val ensureAscii = a.keywords["ensure_ascii"]?.let { RValues.truthy(it) } ?: true
        val separators = a.keywords["separators"]?.let { RValues.sequence(it) }
            ?.takeIf { s -> s.size == 2 && s.all { it is RValue.Str } }?.map { (it as RValue.Str).value }
        val itemSep = separators?.get(0) ?: if (nice || indent != null) "," else ", "
        val out = StringBuilder()
        encode(value, out, indent, 0, sortKeys, ensureAscii, itemSep, separators?.get(1) ?: ": ")
        return out.toString()
    }

    fun htmlSafe(value: RValue, a: Args): String {
        val out = StringBuilder()
        encode(value, out, a.int(0, "indent"), 0, sortKeys = true, ensureAscii = true, itemSep = ", ")
        return out.toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026").replace("'", "\\u0027")
    }

    private fun encode(value: RValue, out: StringBuilder, indent: Int?, level: Int, sortKeys: Boolean, ensureAscii: Boolean, itemSep: String, keySep: String = ": ") {
        fun newline(l: Int) {
            if (indent != null) out.append('\n').append(" ".repeat(indent * l))
        }
        when (value) {
            RValue.None -> out.append("null")
            is RValue.Bool -> out.append(if (value.value) "true" else "false")
            is RValue.Int -> out.append(value.value.toString())
            is RValue.Float -> out.append(
                when {
                    value.value.isNaN() -> "NaN"
                    value.value == Double.POSITIVE_INFINITY -> "Infinity"
                    value.value == Double.NEGATIVE_INFINITY -> "-Infinity"
                    else -> PyRepr.floatRepr(value.value)
                },
            )
            is RValue.Str -> quote(value.value, out, ensureAscii)
            is RValue.Date -> quote(PyRepr.str(value.value).replace(' ', 'T'), out, ensureAscii)
            is RValue.List, is RValue.Tuple -> {
                val items = RValues.sequence(value)!!
                if (items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) out.append(itemSep)
                    newline(level + 1)
                    encode(item, out, indent, level + 1, sortKeys, ensureAscii, itemSep, keySep)
                }
                newline(level)
                out.append(']')
            }
            is RValue.Dict -> {
                if (value.map.isEmpty()) {
                    out.append("{}")
                    return
                }
                val entries = value.entries.map { (k, v) -> keyText(k) to v }.let { if (sortKeys) it.sortedBy { e -> e.first } else it }
                out.append('{')
                entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(itemSep)
                    newline(level + 1)
                    quote(k, out, ensureAscii)
                    out.append(keySep)
                    encode(v, out, indent, level + 1, sortKeys, ensureAscii, itemSep, keySep)
                }
                newline(level)
                out.append('}')
            }
            is RValue.Hole -> throw HoleSignal(value)
            is RValue.Undefined -> throw RenderError(value.message)
            else -> throw RenderError("Object of type ${value.typeName} is not JSON serializable")
        }
    }

    private fun keyText(key: RValue): String = when (key) {
        is RValue.Str -> key.value
        is RValue.Int -> key.value.toString()
        is RValue.Float -> PyRepr.floatRepr(key.value)
        is RValue.Bool -> if (key.value) "true" else "false"
        RValue.None -> "null"
        else -> throw RenderError("keys must be str, int, float, bool or None, not ${key.typeName}")
    }

    private fun quote(text: String, out: StringBuilder, ensureAscii: Boolean) {
        out.append('"')
        for (c in text) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ' || ensureAscii && c.code > 126) out.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0')) else out.append(c)
            }
        }
        out.append('"')
    }
}
