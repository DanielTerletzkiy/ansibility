package de.terletzkiy.ansibility.semantics.render

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Python's arithmetic operators (`/` is true division, `//` floors, `%` formats strings, `+` never stringifies). */
internal object Ops {
    fun negate(value: RValue): RValue {
        RValues.requireKnown(value)
        return when (val v = RValues.requireDefined(value)) {
            is RValue.Bool -> RValue.Int(if (v.value) -1 else 0)
            is RValue.Int -> RValue.Int(v.value.negate())
            is RValue.Float -> RValue.Float(-v.value)
            else -> throw RenderError("bad operand type for unary -: '${v.typeName}'")
        }
    }

    fun positive(value: RValue): RValue {
        RValues.requireKnown(value)
        return when (val v = RValues.requireDefined(value)) {
            is RValue.Bool -> RValue.Int(if (v.value) 1 else 0)
            is RValue.Int, is RValue.Float -> v
            else -> throw RenderError("bad operand type for unary +: '${v.typeName}'")
        }
    }

    fun arithmetic(op: String, a: RValue, b: RValue, tuples: Boolean): RValue {
        if (op == "+") concat(a, b)?.let { return it }
        if (op == "*") repeat(a, b)?.let { return it }
        if (op == "%" && a is RValue.Str) return RValue.Str(PyFormat.percent(a.value, b, tuples))
        val x = RValues.number(a)
        val y = RValues.number(b)
        if (x == null || y == null) throw RenderError("unsupported operand type(s) for $op: '${a.typeName}' and '${b.typeName}'")
        if (x is BigInteger && y is BigInteger) return integer(op, x, y)
        val p = toDouble(x)
        val q = toDouble(y)
        return RValue.Float(
            when (op) {
                "+" -> p + q
                "-" -> p - q
                "*" -> p * q
                "/" -> if (q == 0.0) throw RenderError("float division by zero") else p / q
                "//" -> if (q == 0.0) throw RenderError("float floor division by zero") else Math.floor(p / q)
                "%" -> if (q == 0.0) throw RenderError("float modulo") else p - q * Math.floor(p / q)
                "**" -> Math.pow(p, q)
                else -> throw RenderError("unsupported operator $op")
            },
        )
    }

    private fun toDouble(n: Number): Double = if (n is BigInteger) n.toDouble() else n as Double

    private fun integer(op: String, x: BigInteger, y: BigInteger): RValue = when (op) {
        "+" -> RValue.Int(x + y)
        "-" -> RValue.Int(x - y)
        "*" -> RValue.Int(x * y)
        "/" -> if (y.signum() == 0) throw RenderError("division by zero") else RValue.Float(BigDecimal(x).divide(BigDecimal(y), 20, RoundingMode.HALF_EVEN).toDouble())
        "//" -> if (y.signum() == 0) throw RenderError("integer division or modulo by zero") else RValue.Int(floorDiv(x, y))
        "%" -> if (y.signum() == 0) throw RenderError("integer modulo by zero") else RValue.Int(x - y * floorDiv(x, y))
        "**" -> if (y.signum() < 0) RValue.Float(Math.pow(x.toDouble(), y.toDouble()))
        else if (y > BigInteger.valueOf(10_000)) throw RenderError("exponent too large") else RValue.Int(x.pow(y.toInt()))
        else -> throw RenderError("unsupported operator $op")
    }

    private fun floorDiv(x: BigInteger, y: BigInteger): BigInteger {
        val (q, r) = x.divideAndRemainder(y)
        return if (r.signum() != 0 && (r.signum() != y.signum())) q - BigInteger.ONE else q
    }

    private fun concat(a: RValue, b: RValue): RValue? = when {
        a is RValue.Str && b is RValue.Str -> RValue.Str(a.value + b.value)
        a is RValue.List && b is RValue.List -> RValue.List(a.items + b.items)
        a is RValue.Tuple && b is RValue.Tuple -> RValue.Tuple(a.items + b.items)
        a is RValue.Str || b is RValue.Str || a is RValue.List || b is RValue.List ->
            if (RValues.number(a) != null && RValues.number(b) != null) null
            else throw RenderError(
                if (a is RValue.Str) "can only concatenate str (not \"${b.typeName}\") to str"
                else if (a is RValue.List) "can only concatenate list (not \"${b.typeName}\") to list"
                else "unsupported operand type(s) for +: '${a.typeName}' and '${b.typeName}'",
            )
        else -> null
    }

    private fun repeat(a: RValue, b: RValue): RValue? {
        val (seq, n) = when {
            b is RValue.Int && (a is RValue.Str || a is RValue.List) -> a to b.value.toInt()
            a is RValue.Int && (b is RValue.Str || b is RValue.List) -> b to a.value.toInt()
            else -> return null
        }
        if (n > 100_000) throw RenderError("repetition too large")
        return when (seq) {
            is RValue.Str -> RValue.Str(seq.value.repeat(maxOf(0, n)))
            is RValue.List -> RValue.List((0 until maxOf(0, n)).flatMap { seq.items })
            else -> null
        }
    }
}

/** Jinja's `getattr`/`getitem` (`environment.getattr`: attribute first, then item; `getitem`: item first). */
internal object Access {
    fun attribute(target: RValue, name: String, options: RenderOptions): RValue {
        when (target) {
            is RValue.Hole -> return target
            is RValue.Undefined -> return target
            is RValue.Obj -> return target.attributes[name] ?: missing(target, name, options)
            is RValue.Date -> {
                val t = target.value.timestamp
                val field = when (name) {
                    "year" -> t.year
                    "month" -> t.month
                    "day" -> t.day
                    "hour" -> t.hour.takeIf { t.isDateTime }
                    "minute" -> t.minute.takeIf { t.isDateTime }
                    "second" -> t.second.takeIf { t.isDateTime }
                    "microsecond" -> t.microsecond.takeIf { t.isDateTime }
                    else -> null
                }
                return field?.let { RValues.int(it) } ?: RValue.Hole(Placeholder.notEmulated("${target.typeName}.$name"))
            }
            else -> Unit
        }
        Methods.bound(target, name, options)?.let { return it }
        return itemOrNull(target, RValue.Str(name)) ?: missing(target, name, options)
    }

    fun item(target: RValue, key: RValue, options: RenderOptions): RValue {
        if (target is RValue.Hole) return target
        if (target is RValue.Undefined) return target
        if (key is RValue.Hole) return key
        if (key is RValue.Undefined) throw RenderError(key.message)
        itemOrNull(target, key)?.let { return it }
        if (key is RValue.Str) {
            if (target is RValue.Obj) target.attributes[key.value]?.let { return it }
            Methods.bound(target, key.value, options)?.let { return it }
        }
        return missing(target, if (key is RValue.Str) key.value else RValues.repr(key, options.tuplesAsLists), options)
    }

    private fun itemOrNull(target: RValue, key: RValue): RValue? = when (target) {
        is RValue.Dict -> target[key]
        is RValue.List, is RValue.Tuple, is RValue.Str -> {
            val index = (key as? RValue.Int)?.value?.toInt() ?: (key as? RValue.Bool)?.let { if (it.value) 1 else 0 }
            if (index == null) null
            else when (target) {
                is RValue.Str -> {
                    val cps = target.value.codePoints().toArray()
                    val i = if (index < 0) cps.size + index else index
                    if (i in cps.indices) RValue.Str(String(Character.toChars(cps[i]))) else null
                }
                else -> {
                    val items = RValues.sequence(target)!!
                    val i = if (index < 0) items.size + index else index
                    items.getOrNull(i)
                }
            }
        }
        else -> null
    }

    private fun missing(target: RValue, name: String, options: RenderOptions): RValue {
        val message = if (options.native) {
            "object of type '${target.typeName}' has no attribute '$name'"
        } else {
            val what = when (target) {
                is RValue.Dict -> "dict object"
                is RValue.List -> "list object"
                is RValue.Str -> "str object"
                RValue.None -> "None"
                else -> "${target.typeName} object"
            }
            "'$what' has no attribute '$name'"
        }
        return RValue.Undefined(name, message)
    }

    /** `x[start:stop:step]` on a list, tuple or string. */
    fun slice(target: RValue, bounds: List<RValue?>): RValue {
        fun int(v: RValue?): Int? = when (v) {
            null, RValue.None -> null
            is RValue.Int -> v.value.toInt()
            is RValue.Bool -> if (v.value) 1 else 0
            else -> throw RenderError("slice indices must be integers or None or have an __index__ method")
        }
        val step = int(bounds.getOrNull(2)) ?: 1
        if (step == 0) throw RenderError("slice step cannot be zero")
        fun <T> cut(items: List<T>): List<T> {
            val n = items.size
            fun norm(i: Int?, default: Int): Int {
                if (i == null) return default
                val j = if (i < 0) i + n else i
                return if (step > 0) j.coerceIn(0, n) else j.coerceIn(-1, n - 1)
            }
            val start = norm(int(bounds.getOrNull(0)), if (step > 0) 0 else n - 1)
            val stop = norm(int(bounds.getOrNull(1)), if (step > 0) n else -1)
            val out = ArrayList<T>()
            var i = start
            while (if (step > 0) i < stop else i > stop) {
                out += items[i]
                i += step
            }
            return out
        }
        return when (target) {
            is RValue.List -> RValue.List(cut(target.items))
            is RValue.Tuple -> RValue.Tuple(cut(target.items))
            is RValue.Str -> {
                val cps = target.value.codePoints().toArray().toList()
                RValue.Str(cut(cps).joinToString("") { String(Character.toChars(it)) })
            }
            else -> throw RenderError("'${target.typeName}' object is not subscriptable")
        }
    }
}

/** The Python methods templates call (`.split`, `.append`, `.items` …); anything else is not emulated. */
internal object Methods {
    private val STR = setOf(
        "split", "rsplit", "splitlines", "startswith", "endswith", "strip", "lstrip", "rstrip", "lower", "upper", "replace",
        "find", "rfind", "index", "join", "format", "capitalize", "title", "count", "isdigit", "isalpha", "isalnum", "zfill",
        "center", "ljust", "rjust", "partition", "rpartition", "casefold", "swapcase", "isupper", "islower", "isspace",
    )
    private val LIST = setOf("append", "extend", "insert", "pop", "index", "count", "remove", "reverse", "sort", "copy")
    private val DICT = setOf("items", "keys", "values", "get", "update", "copy", "pop", "setdefault")

    fun bound(target: RValue, name: String, options: RenderOptions): RValue? {
        val known = when (target) {
            is RValue.Str -> name in STR
            is RValue.List -> name in LIST
            is RValue.Dict -> name in DICT
            else -> false
        }
        if (!known) return null
        return RValue.Callable(name, target.typeName) { args, kwargs -> call(target, name, args, kwargs, options) }
    }

    private fun arg(args: List<RValue>, i: Int): RValue? = args.getOrNull(i)?.also { RValues.requireKnown(it); RValues.requireDefined(it) }

    private fun strArg(args: List<RValue>, i: Int): String? = when (val a = arg(args, i)) {
        null, RValue.None -> null
        is RValue.Str -> a.value
        else -> throw RenderError("must be str or None, not ${a.typeName}")
    }

    private fun call(target: RValue, name: String, args: List<RValue>, kwargs: Map<String, RValue>, options: RenderOptions): RValue {
        val tuples = options.tuplesAsLists
        return when (target) {
            is RValue.Str -> str(target.value, name, args, kwargs, tuples)
            is RValue.List -> list(target, name, args)
            is RValue.Dict -> dict(target, name, args, kwargs)
            else -> throw RenderError("no method $name")
        }
    }

    private fun str(s: String, name: String, args: List<RValue>, kwargs: Map<String, RValue>, tuples: Boolean): RValue = when (name) {
        "split", "rsplit" -> {
            val sep = strArg(args, 0) ?: kwargs["sep"]?.let { (it as? RValue.Str)?.value }
            val max = (arg(args, 1) as? RValue.Int)?.value?.toInt() ?: (kwargs["maxsplit"] as? RValue.Int)?.value?.toInt() ?: -1
            RValue.List(PyStrings.split(s, sep, max, name == "rsplit").map { RValue.Str(it) })
        }
        "splitlines" -> RValue.List(s.split(Regex("\r\n|\r|\n")).let { if (s.endsWith("\n") || s.endsWith("\r")) it.dropLast(1) else it }.let { if (s.isEmpty()) emptyList() else it }.map { RValue.Str(it) })
        "startswith", "endswith" -> {
            val prefixes = when (val a = arg(args, 0)) {
                is RValue.Str -> listOf(a.value)
                is RValue.Tuple -> a.items.map { (it as? RValue.Str)?.value ?: throw RenderError("tuple for startswith must only contain str") }
                else -> throw RenderError("$name first arg must be str or a tuple of str")
            }
            RValues.bool(prefixes.any { if (name == "startswith") s.startsWith(it) else s.endsWith(it) })
        }
        "strip" -> RValue.Str(PyStrings.strip(s, strArg(args, 0), left = true, right = true))
        "lstrip" -> RValue.Str(PyStrings.strip(s, strArg(args, 0), left = true, right = false))
        "rstrip" -> RValue.Str(PyStrings.strip(s, strArg(args, 0), left = false, right = true))
        "lower", "casefold" -> RValue.Str(s.lowercase())
        "upper" -> RValue.Str(s.uppercase())
        "swapcase" -> RValue.Str(s.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString(""))
        "capitalize" -> RValue.Str(s.lowercase().replaceFirstChar { it.uppercaseChar() })
        "title" -> RValue.Str(PyStrings.title(s))
        "replace" -> {
            val old = strArg(args, 0) ?: throw RenderError("replace() argument 1 must be str")
            val new = strArg(args, 1) ?: throw RenderError("replace() argument 2 must be str")
            val count = (arg(args, 2) as? RValue.Int)?.value?.toInt() ?: -1
            RValue.Str(PyStrings.replace(s, old, new, count))
        }
        "find", "index" -> {
            val i = s.indexOf(strArg(args, 0) ?: "")
            if (i < 0 && name == "index") throw RenderError("substring not found")
            RValues.int(if (i < 0) -1 else s.codePointCount(0, i))
        }
        "rfind" -> RValues.int(s.lastIndexOf(strArg(args, 0) ?: "").let { if (it < 0) -1 else s.codePointCount(0, it) })
        "count" -> RValues.int(PyStrings.count(s, strArg(args, 0) ?: ""))
        "join" -> {
            val items = RValues.iterate(arg(args, 0) ?: throw RenderError("join() takes exactly one argument"))
            RValue.Str(items.joinToString(s) { (it as? RValue.Str)?.value ?: throw RenderError("sequence item: expected str instance, ${it.typeName} found") })
        }
        "format" -> RValue.Str(PyFormat.format(s, args, kwargs, tuples))
        "isdigit" -> RValues.bool(s.isNotEmpty() && s.all { it.isDigit() })
        "isalpha" -> RValues.bool(s.isNotEmpty() && s.all { it.isLetter() })
        "isalnum" -> RValues.bool(s.isNotEmpty() && s.all { it.isLetterOrDigit() })
        "isupper" -> RValues.bool(s.any { it.isLetter() } && s.none { it.isLowerCase() })
        "islower" -> RValues.bool(s.any { it.isLetter() } && s.none { it.isUpperCase() })
        "isspace" -> RValues.bool(s.isNotEmpty() && s.all { it.isWhitespace() })
        "zfill" -> {
            val width = (arg(args, 0) as? RValue.Int)?.value?.toInt() ?: 0
            val sign = if (s.startsWith("-") || s.startsWith("+")) s.substring(0, 1) else ""
            RValue.Str(sign + s.substring(sign.length).padStart(width - sign.length, '0'))
        }
        "center", "ljust", "rjust" -> {
            val width = (arg(args, 0) as? RValue.Int)?.value?.toInt() ?: 0
            val fill = strArg(args, 1)?.firstOrNull() ?: ' '
            RValue.Str(PyStrings.pad(s, width, fill, name))
        }
        "partition", "rpartition" -> {
            val sep = strArg(args, 0) ?: throw RenderError("must be str")
            val i = if (name == "partition") s.indexOf(sep) else s.lastIndexOf(sep)
            val parts = if (i < 0) (if (name == "partition") listOf(s, "", "") else listOf("", "", s)) else listOf(s.substring(0, i), sep, s.substring(i + sep.length))
            RValue.Tuple(parts.map { RValue.Str(it) })
        }
        else -> RValue.Hole(Placeholder.notEmulated("method str.$name"))
    }

    private fun list(target: RValue.List, name: String, args: List<RValue>): RValue = when (name) {
        "append" -> RValue.None.also { target.items += args.firstOrNull() ?: throw RenderError("append() takes exactly one argument") }
        "extend" -> RValue.None.also { target.items += RValues.iterate(args.firstOrNull() ?: throw RenderError("extend() takes exactly one argument")) }
        "insert" -> RValue.None.also {
            val i = (arg(args, 0) as? RValue.Int)?.value?.toInt() ?: 0
            val at = (if (i < 0) target.items.size + i else i).coerceIn(0, target.items.size)
            target.items.add(at, args.getOrNull(1) ?: RValue.None)
        }
        "pop" -> {
            if (target.items.isEmpty()) throw RenderError("pop from empty list")
            val i = (arg(args, 0) as? RValue.Int)?.value?.toInt() ?: -1
            target.items.removeAt(if (i < 0) target.items.size + i else i)
        }
        "index" -> {
            val v = args.firstOrNull() ?: RValue.None
            val i = target.items.indexOfFirst { RValues.pyEquals(it, v) }
            if (i < 0) throw RenderError("value is not in list")
            RValues.int(i)
        }
        "count" -> RValues.int(target.items.count { RValues.pyEquals(it, args.firstOrNull() ?: RValue.None) })
        "remove" -> RValue.None.also {
            val i = target.items.indexOfFirst { RValues.pyEquals(it, args.firstOrNull() ?: RValue.None) }
            if (i < 0) throw RenderError("list.remove(x): x not in list")
            target.items.removeAt(i)
        }
        "reverse" -> RValue.None.also { target.items.reverse() }
        "sort" -> RValue.None.also { target.items.sortWith { a, b -> RValues.compare(a, b, "<") } }
        "copy" -> RValue.List(target.items.toList())
        else -> RValue.Hole(Placeholder.notEmulated("method list.$name"))
    }

    private fun dict(target: RValue.Dict, name: String, args: List<RValue>, kwargs: Map<String, RValue>): RValue = when (name) {
        "items" -> RValue.List(target.entries.map { RValue.Tuple(listOf(it.first, it.second)) })
        "keys" -> RValue.List(target.keys)
        "values" -> RValue.List(target.map.values.toList())
        "get" -> {
            val key = args.firstOrNull() ?: throw RenderError("get expected at least 1 argument, got 0")
            RValues.requireKnown(key)
            target[key] ?: args.getOrNull(1) ?: RValue.None
        }
        "update" -> RValue.None.also {
            (args.firstOrNull() as? RValue.Dict)?.entries?.forEach { (k, v) -> target[k] = v }
            kwargs.forEach { (k, v) -> target[k] = v }
        }
        "copy" -> RValue.Dict.of(target.entries)
        "pop" -> {
            val key = args.firstOrNull() ?: throw RenderError("pop expected at least 1 argument, got 0")
            target.map.remove(RKey(key)) ?: args.getOrNull(1) ?: throw RenderError(RValues.repr(key, false))
        }
        "setdefault" -> {
            val key = args.firstOrNull() ?: throw RenderError("setdefault expected at least 1 argument, got 0")
            target[key] ?: (args.getOrNull(1) ?: RValue.None).also { target[key] = it }
        }
        else -> RValue.Hole(Placeholder.notEmulated("method dict.$name"))
    }
}

/** Python string helpers with Python's edge cases. */
internal object PyStrings {
    fun split(s: String, sep: String?, max: Int, fromRight: Boolean): List<String> {
        if (sep == null) {
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (max < 0 || words.size <= max + 1) return words
            return if (!fromRight) {
                val parts = ArrayList<String>()
                var rest = s.trimStart()
                repeat(max) {
                    val m = Regex("\\s+").find(rest) ?: return@repeat
                    parts += rest.substring(0, m.range.first)
                    rest = rest.substring(m.range.last + 1)
                }
                parts + rest
            } else {
                words.dropLast(max).joinToString(" ").let { listOf(it) } + words.takeLast(max)
            }
        }
        if (sep.isEmpty()) throw RenderError("empty separator")
        if (max < 0) return s.split(sep)
        return if (!fromRight) s.split(sep, limit = max + 1)
        else s.reversed().split(sep.reversed(), limit = max + 1).map { it.reversed() }.reversed()
    }

    fun strip(s: String, chars: String?, left: Boolean, right: Boolean): String {
        val set: (Char) -> Boolean = if (chars == null) { c -> c.isWhitespace() } else { c -> c in chars }
        var a = 0
        var b = s.length
        if (left) while (a < b && set(s[a])) a++
        if (right) while (b > a && set(s[b - 1])) b--
        return s.substring(a, b)
    }

    fun replace(s: String, old: String, new: String, count: Int): String {
        if (count < 0) {
            if (old.isEmpty()) return new + s.toList().joinToString(new) + new
            return s.replace(old, new)
        }
        var out = s
        var done = 0
        var from = 0
        val sb = StringBuilder()
        while (done < count) {
            val i = out.indexOf(old, from)
            if (i < 0) break
            sb.append(out, from, i).append(new)
            from = i + old.length
            done++
            if (old.isEmpty()) break
        }
        sb.append(out.substring(from))
        out = sb.toString()
        return out
    }

    fun count(s: String, sub: String): Int {
        if (sub.isEmpty()) return s.codePointCount(0, s.length) + 1
        var n = 0
        var i = s.indexOf(sub)
        while (i >= 0) {
            n++
            i = s.indexOf(sub, i + sub.length)
        }
        return n
    }

    fun title(s: String): String {
        val sb = StringBuilder()
        var prevCased = false
        for (c in s) {
            sb.append(if (prevCased) c.lowercaseChar() else c.uppercaseChar())
            prevCased = c.isLetter()
        }
        return sb.toString()
    }

    fun pad(s: String, width: Int, fill: Char, how: String): String {
        val n = width - s.length
        if (n <= 0) return s
        return when (how) {
            "ljust" -> s + fill.toString().repeat(n)
            "rjust" -> fill.toString().repeat(n) + s
            else -> {
                val left = n / 2 + (n and width and 1)
                fill.toString().repeat(left) + s + fill.toString().repeat(n - left)
            }
        }
    }
}
