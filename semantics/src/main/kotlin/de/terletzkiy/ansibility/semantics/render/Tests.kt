package de.terletzkiy.ansibility.semantics.render

import java.math.BigInteger

/**
 * Jinja's and ansible-core's tests (`x is defined`, `x is version('2.0', '>=')` …). Type tests on an undefined
 * value are false; tests that read controller files are not emulated.
 */
object Tests {
    private val NOT_EMULATED = setOf("file", "directory", "dir", "exists", "link", "mount", "same_file", "is_file", "is_dir", "is_link", "is_mount", "escaped", "vault_encrypted", "vaulted_file")

    fun apply(name: String, value: RValue, args: List<RValue>, kwargs: Map<String, RValue>, ctx: FilterContext): RValue {
        val short = Filters.shortName(name)
        val a = Args(args, kwargs)
        when (short) {
            "defined" -> return defined(value) ?: value
            "undefined" -> return defined(value)?.let { RValues.bool(!it.value) } ?: value
        }
        if (short in NOT_EMULATED || name.contains('.') && !name.startsWith("ansible.builtin.")) return RValue.Hole(Placeholder.notEmulated("test $name"))
        if (value is RValue.Undefined && short in TYPE_TESTS) return RValues.FALSE
        RValues.requireKnown(value)
        RValues.requireDefined(value)
        return RValues.bool(
            when (short) {
                "none" -> value == RValue.None
                "string" -> value is RValue.Str
                "number" -> value is RValue.Int || value is RValue.Float || value is RValue.Bool
                "integer" -> value is RValue.Int
                "float" -> value is RValue.Float
                "boolean" -> value is RValue.Bool
                "mapping" -> value is RValue.Dict
                "sequence" -> value is RValue.Str || value is RValue.List || value is RValue.Tuple || value is RValue.Dict
                "iterable" -> value is RValue.Str || value is RValue.List || value is RValue.Tuple || value is RValue.Dict
                "callable" -> value is RValue.Callable
                "sameas" -> {
                    val other = a.known(0, "other") ?: RValue.None
                    value === other || value is RValue.Bool && other is RValue.Bool && value.value == other.value || value == RValue.None && other == RValue.None
                }
                "eq", "equalto", "==" -> RValues.pyEquals(value, arg(a))
                "ne", "!=" -> !RValues.pyEquals(value, arg(a))
                "lt", "lessthan", "<" -> RValues.compare(value, arg(a), "<") < 0
                "le", "<=" -> RValues.compare(value, arg(a), "<=") <= 0
                "gt", "greaterthan", ">" -> RValues.compare(value, arg(a), ">") > 0
                "ge", ">=" -> RValues.compare(value, arg(a), ">=") >= 0
                "in" -> RValues.contains(arg(a), value)
                "contains" -> RValues.contains(value, arg(a))
                "subset" -> RValues.iterate(value).all { RValues.contains(arg(a), it) }
                "superset" -> RValues.iterate(arg(a)).all { RValues.contains(value, it) }
                "divisibleby" -> integer(value).mod(integer(arg(a)).abs().takeIf { it.signum() != 0 } ?: throw RenderError("integer modulo by zero")).signum() == 0
                "even" -> !integer(value).testBit(0)
                "odd" -> integer(value).testBit(0)
                "lower" -> (value as? RValue.Str)?.value?.let { it == it.lowercase() } ?: throw RenderError("'${value.typeName}' object has no attribute 'islower'")
                "upper" -> (value as? RValue.Str)?.value?.let { it == it.uppercase() } ?: throw RenderError("'${value.typeName}' object has no attribute 'isupper'")
                "truthy" -> truthy(value, a)
                "falsy" -> !truthy(value, a)
                "match", "search", "regex" -> {
                    val pattern = a.str(0, "pattern") ?: throw RenderError("$short test requires a pattern")
                    val ignorecase = a.bool(1, "ignorecase")
                    val multiline = a.bool(2, "multiline")
                    val type = if (short == "regex") a.str(3, "match_type", "search")!! else short
                    val text = (value as? RValue.Str)?.value ?: ctx.str(value)
                    val compiled = Regexes.compile(pattern, ignorecase, multiline) ?: return RValue.Hole(Placeholder.notEmulated("$short pattern"))
                    val m = compiled.matcher(text)
                    when (type) {
                        "match" -> m.lookingAt()
                        "search" -> m.find()
                        "fullmatch" -> m.matches()
                        else -> throw RenderError("Invalid match_type")
                    }
                }
                "version", "version_compare" -> return version(value, a, ctx)
                "any" -> RValues.iterate(value).any { RValues.truthy(it) }
                "all" -> RValues.iterate(value).all { RValues.truthy(it) }
                "nan" -> value is RValue.Float && value.value.isNaN()
                "abs", "is_abs" -> ((value as? RValue.Str)?.value ?: ctx.str(value)).startsWith("/")
                "failed", "failure" -> RValues.truthy(result(value, short)["failed"] ?: RValues.FALSE)
                "succeeded", "success", "successful" -> !RValues.truthy(result(value, short)["failed"] ?: RValues.FALSE)
                "changed", "change" -> RValues.truthy(result(value, short)["changed"] ?: RValues.FALSE)
                "skipped", "skip" -> RValues.truthy(result(value, short)["skipped"] ?: RValues.FALSE)
                "finished" -> RValues.truthy(result(value, short)["finished"] ?: RValues.int(1))
                "started" -> RValues.truthy(result(value, short)["started"] ?: RValues.int(1))
                "reachable" -> !RValues.truthy(result(value, short)["unreachable"] ?: RValues.FALSE)
                "unreachable" -> RValues.truthy(result(value, short)["unreachable"] ?: RValues.FALSE)
                else -> return RValue.Hole(Placeholder.notEmulated("test $name"))
            },
        )
    }

    private val TYPE_TESTS = setOf("none", "string", "number", "integer", "float", "boolean", "mapping", "sequence", "iterable", "callable")

    /** `is defined` on a hole: a secret, a runtime value or `omit` is defined; a fact or unknown name may not be. */
    private fun defined(value: RValue): RValue.Bool? = when (value) {
        is RValue.Undefined -> RValues.FALSE
        is RValue.Hole -> when (value.placeholder.kind) {
            Placeholder.Kind.SECRET, Placeholder.Kind.RUNTIME, Placeholder.Kind.OMIT, Placeholder.Kind.CONTROLLER -> RValues.TRUE
            else -> null
        }
        else -> RValues.TRUE
    }

    private fun arg(a: Args): RValue = a.known(0, "other") ?: throw RenderError("test requires an argument")

    private fun integer(value: RValue): BigInteger = when (value) {
        is RValue.Int -> value.value
        is RValue.Bool -> if (value.value) BigInteger.ONE else BigInteger.ZERO
        else -> throw RenderError("not all arguments converted during string formatting")
    }

    private fun truthy(value: RValue, a: Args): Boolean {
        if (a.bool(0, "convert_bool") && value is RValue.Str) {
            when (value.value.lowercase()) {
                "yes", "on", "1", "true", "y", "t" -> return true
                "no", "off", "0", "false", "n", "f", "" -> return false
            }
        }
        return RValues.truthy(value)
    }

    private fun result(value: RValue, test: String): RValue.Dict =
        value as? RValue.Dict ?: throw RenderError("The '$test' test expects a dictionary")

    private val OPERATORS = mapOf(
        "==" to "eq", "=" to "eq", "eq" to "eq", "<" to "lt", "lt" to "lt", "<=" to "le", "le" to "le",
        ">" to "gt", "gt" to "gt", ">=" to "ge", "ge" to "ge", "!=" to "ne", "<>" to "ne", "ne" to "ne",
    )

    private fun version(value: RValue, a: Args, ctx: FilterContext): RValue {
        val other = a.known(0, "version") ?: throw RenderError("Version parameter to compare against is required")
        val op = OPERATORS[a.str(1, "operator", "eq")] ?: throw RenderError("Invalid operator type (${a.str(1, "operator")})")
        val strict = a.bool(2, "strict")
        val type = a.str(3, "version_type") ?: if (strict) "strict" else "loose"
        if (type != "loose") return RValue.Hole(Placeholder.notEmulated("version_type '$type'"))
        val left = looseVersion(ctx.str(value))
        val right = looseVersion(ctx.str(other))
        val c = compareLoose(left, right)
        return RValues.bool(
            when (op) {
                "eq" -> c == 0
                "ne" -> c != 0
                "lt" -> c < 0
                "le" -> c <= 0
                "gt" -> c > 0
                else -> c >= 0
            },
        )
    }

    private val LOOSE = Regex("""(\d+|[a-z]+|\.)""", RegexOption.IGNORE_CASE)

    /** `LooseVersion.parse`: split into numbers and words, dots dropped. */
    private fun looseVersion(s: String): List<Any> {
        val parts = ArrayList<Any>()
        var last = 0
        for (m in LOOSE.findAll(s)) {
            if (m.range.first > last) parts += s.substring(last, m.range.first)
            if (m.value != ".") parts += m.value.toBigIntegerOrNull() ?: m.value
            last = m.range.last + 1
        }
        if (last < s.length) parts += s.substring(last)
        return parts.filter { it != "" }
    }

    private fun compareLoose(a: List<Any>, b: List<Any>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]
            val y = b[i]
            val c = when {
                x is BigInteger && y is BigInteger -> x.compareTo(y)
                x is String && y is String -> x.compareTo(y)
                else -> throw RenderError("Version comparison failed: '<' not supported between instances of '${if (x is String) "str" else "int"}' and '${if (y is String) "str" else "int"}'")
            }
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }
}

/** The lookups that only read their terms and the variables: `items`, `dict`, `sequence`, `vars` … */
object Lookups {
    fun pure(name: String, terms: List<RValue>, kwargs: Map<String, RValue>, variable: (String) -> RValue, tuples: Boolean): RValue? {
        val short = Filters.shortName(name)
        return when (short) {
            "items" -> RValue.List(terms.flatMap { RValues.sequence(it) ?: listOf(it) })
            "list" -> RValue.List(terms)
            "flattened" -> RValue.List(flattened(terms))
            "indexed_items" -> RValue.List(terms.flatMap { RValues.sequence(it) ?: listOf(it) }.mapIndexed { i, item -> RValue.Tuple(listOf(RValues.int(i), item)) })
            "dict" -> RValue.List(
                terms.flatMap { term ->
                    val dict = term as? RValue.Dict ?: throw RenderError("with_dict expects a dict")
                    dict.entries.map { (k, v) -> RValue.Dict.ofStrings("key" to k, "value" to v) }
                },
            )
            "nested" -> {
                val lists = terms.map { RValues.sequence(it) ?: listOf(it) }
                if (lists.isEmpty()) throw RenderError("with_nested requires at least one element in the nested list")
                RValue.List(lists.fold(listOf(emptyList<RValue>())) { acc, list -> acc.flatMap { prefix -> list.map { prefix + it } } }.map { RValue.List(it) })
            }
            "together" -> {
                val lists = terms.map { RValues.sequence(it) ?: listOf(it) }
                if (lists.isEmpty()) throw RenderError("with_together requires at least one element in each list")
                RValue.List((0 until lists.maxOf { it.size }).map { i -> RValue.List(lists.map { it.getOrNull(i) ?: RValue.None }) })
            }
            "sequence" -> RValue.List(terms.flatMap { sequence(it, kwargs) } + if (terms.isEmpty()) sequence(null, kwargs) else emptyList())
            "vars" -> RValue.List(
                terms.map { term ->
                    val varName = (term as? RValue.Str)?.value ?: throw RenderError("Invalid setting identifier, \"${RValues.repr(term, tuples)}\" is not a string, its a ${term.typeName}")
                    val value = variable(varName)
                    if (value is RValue.Undefined) kwargs["default"] ?: throw RenderError("No variable found with this name: $varName") else value
                },
            )
            else -> null
        }
    }

    private fun flattened(items: List<RValue>): List<RValue> = items.flatMap { item -> RValues.sequence(item)?.let { flattened(it) } ?: listOf(item) }

    private val SHORTCUT = Regex("""^(?:(-?(?:0x)?[0-9a-f]+)-)?(-?(?:0x)?[0-9a-f]+)(?:/(-?(?:0x)?[0-9a-f]+))?(?::(.+))?$""", RegexOption.IGNORE_CASE)

    /** `lookup('sequence', 'start=1 end=5 format=web%02d')` or the shortcut `1-5/2:web%02d`. */
    private fun sequence(term: RValue?, kwargs: Map<String, RValue>): List<RValue> {
        val options = HashMap<String, String>()
        kwargs.forEach { (k, v) -> options[k] = RValues.str(v, false) }
        val text = (term as? RValue.Str)?.value?.trim()
        if (text != null && text.isNotEmpty()) {
            if ('=' in text) {
                for (part in text.split(Regex("\\s+"))) {
                    val (k, v) = part.split('=', limit = 2).let { if (it.size == 2) it[0] to it[1] else throw RenderError("can't parse arg ${it[0]}=") }
                    options[k] = v
                }
            } else {
                val m = SHORTCUT.matchEntire(text) ?: throw RenderError("unknown argument: $text")
                m.groups[1]?.let { options["start"] = it.value }
                options["end"] = m.groupValues[2]
                m.groups[3]?.let { options["stride"] = it.value }
                m.groups[4]?.let { options["format"] = it.value }
            }
        }
        fun number(key: String, default: Long?): Long? {
            val raw = options[key] ?: return default
            return raw.toLongOrNull() ?: raw.lowercase().removePrefix("0x").toLongOrNull(16)?.takeIf { raw.lowercase().startsWith("0x") }
                ?: raw.removePrefix("0").toLongOrNull(8)?.takeIf { raw.startsWith("0") }
                ?: throw RenderError("can't parse $key=$raw as integer")
        }
        val start = number("start", 1)!!
        val count = number("count", null)
        var end = number("end", null)
        var stride = number("stride", 1)!!
        if (count != null) {
            if (end != null) throw RenderError("can't specify both count and end in with_sequence")
            end = start + count * stride - 1
            if (count == 0L) return emptyList()
        }
        if (end == null) throw RenderError("must specify count or end in with_sequence")
        if (stride > 0 && end < start) throw RenderError("to count backwards make stride negative")
        if (stride < 0 && end > start) throw RenderError("to count forward don't make stride negative")
        if (stride == 0L) return emptyList()
        val format = options["format"] ?: "%d"
        val out = ArrayList<RValue>()
        var i = start
        while (if (stride > 0) i <= end else i >= end) {
            out += RValue.Str(PyFormat.percent(format, RValue.Int(i), false))
            if (out.size > 10_000) throw RenderError("sequence too long")
            i += stride
        }
        return out
    }
}
