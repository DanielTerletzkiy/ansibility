package de.terletzkiy.ansibility.semantics.render

import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.Yaml11Resolver
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Python `re` on top of `java.util.regex`: the syntax both share is translated (`(?P<n>`, `\Z`, literal `{`), and a
 * pattern using anything whose meaning differs (nested classes, verbose mode, Java-only escapes) is not emulated.
 */
internal object Regexes {
    private class NotEmulated : RuntimeException(null, null, false, false)

    fun compile(pattern: String, ignorecase: Boolean, multiline: Boolean): Pattern? {
        val translated = translate(pattern) ?: return null
        var flags = Pattern.UNICODE_CHARACTER_CLASS or Pattern.UNIX_LINES
        if (ignorecase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (multiline) flags = flags or Pattern.MULTILINE
        return try {
            Pattern.compile(translated, flags)
        } catch (e: PatternSyntaxException) {
            throw RenderError("re.error: ${e.description}")
        }
    }

    private val QUANTIFIER = Regex("""\{\d*(?:,\d*)?}""")

    fun translate(pattern: String): String? {
        val out = StringBuilder()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1) ?: throw RenderError("re.error: bad escape (end of pattern)")
                    when (next) {
                        'Z' -> out.append("\\z")
                        'v' -> out.append("\\x0B")
                        'h', 'H', 'R', 'X', 'G', 'Q', 'E', 'p', 'P', 'e', 'c', 'k', 'K', 'i', 'I', 'j', 'J', 'l', 'L', 'm', 'M', 'o', 'O', 'q', 'y', 'Y', 'z' -> return null
                        'b' -> if (inClass) return null else out.append("\\b")
                        else -> out.append(c).append(next)
                    }
                    i += 2
                    continue
                }
                inClass -> {
                    if (c == '[' || c == '&' && pattern.getOrNull(i + 1) == '&') return null
                    if (c == ']') inClass = false
                    out.append(c)
                }
                c == '[' -> {
                    inClass = true
                    out.append(c)
                    if (pattern.getOrNull(i + 1) == '^') {
                        out.append('^')
                        i++
                    }
                    if (pattern.getOrNull(i + 1) == ']') {
                        out.append("\\]")
                        i++
                    }
                }
                c == '(' && pattern.startsWith("(?P<", i) -> {
                    out.append("(?<")
                    i += 4
                    continue
                }
                c == '(' && pattern.startsWith("(?P=", i) -> {
                    val close = pattern.indexOf(')', i)
                    if (close < 0) throw RenderError("re.error: missing ), unterminated name")
                    out.append("\\k<").append(pattern, i + 4, close).append('>')
                    i = close + 1
                    continue
                }
                c == '(' && pattern.startsWith("(?", i) && pattern.getOrNull(i + 2)?.let { it.isLetter() && pattern.substring(i + 2).takeWhile { ch -> ch.isLetter() }.contains('x') } == true -> return null
                c == '(' && pattern.startsWith("(?(", i) -> return null
                c == '{' -> if (QUANTIFIER.matchesAt(pattern, i) && out.isNotEmpty()) out.append(c) else out.append("\\{")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun flags(a: Args, ignorecaseIndex: Int, multilineIndex: Int): Pair<Boolean, Boolean> =
        a.bool(ignorecaseIndex, "ignorecase") to a.bool(multilineIndex, "multiline")

    fun replace(value: String, a: Args): RValue {
        val pattern = a.str(0, "pattern", "")!!
        val replacement = a.str(1, "replacement", "")!!
        val (ignorecase, multiline) = flags(a, 2, 3)
        val count = a.int(4, "count", 0)!!
        val mandatory = a.int(5, "mandatory_count", 0)!!
        val compiled = compile(pattern, ignorecase, multiline) ?: return notEmulated("regex_replace pattern")
        val template = try {
            parseReplacement(replacement, compiled)
        } catch (e: NotEmulated) {
            return notEmulated("regex_replace replacement")
        }
        val m = compiled.matcher(value)
        val out = StringBuilder()
        var last = 0
        var done = 0
        while ((count == 0 || done < count) && m.find()) {
            out.append(value, last, m.start())
            for (part in template) {
                when (part) {
                    is String -> out.append(part)
                    is Int -> out.append(m.group(part) ?: "")
                    is NamedRef -> out.append(named(m, part) ?: "")
                }
            }
            last = m.end()
            done++
        }
        out.append(value.substring(last))
        if (mandatory > 0 && done != mandatory) throw RenderError("'$pattern' should match $mandatory times, but matches $done times")
        return RValue.Str(out.toString())
    }

    /** Python's `re` replacement template: literal strings, group numbers (Int) and group names ([NamedRef]). */
    private fun parseReplacement(repl: String, pattern: Pattern): List<Any> {
        val groups = pattern.matcher("").groupCount()
        val parts = ArrayList<Any>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) parts += literal.toString()
            literal.clear()
        }
        var i = 0
        while (i < repl.length) {
            val c = repl[i]
            if (c != '\\') {
                literal.append(c)
                i++
                continue
            }
            val next = repl.getOrNull(i + 1) ?: throw RenderError("re.error: bad escape (end of pattern)")
            when {
                next == 'g' -> {
                    val close = repl.indexOf('>', i)
                    if (repl.getOrNull(i + 2) != '<' || close < 0) throw RenderError("re.error: missing <")
                    val ref = repl.substring(i + 3, close)
                    flush()
                    val number = ref.toIntOrNull()
                    if (number != null) {
                        if (number > groups) throw RenderError("re.error: invalid group reference $number")
                        parts += number
                    } else {
                        if (!Regex("[A-Za-z_]\\w*").matches(ref)) throw NotEmulated()
                        parts += NamedRef(ref)
                    }
                    i = close + 1
                    continue
                }
                next.isDigit() -> {
                    var j = i + 1
                    while (j < repl.length && j < i + 3 && repl[j].isDigit()) j++
                    if (next == '0' || j - i - 1 == 3) throw NotEmulated()
                    val number = repl.substring(i + 1, j).toInt()
                    if (number > groups) throw RenderError("re.error: invalid group reference $number")
                    flush()
                    parts += number
                    i = j
                    continue
                }
                next == 'n' -> literal.append('\n')
                next == 't' -> literal.append('\t')
                next == 'r' -> literal.append('\r')
                next == 'f' -> literal.append('\u000C')
                next == 'v' -> literal.append('\u000B')
                next == 'a' -> literal.append('\u0007')
                next == 'b' -> literal.append('\b')
                next == '\\' -> literal.append('\\')
                next.isLetter() -> throw RenderError("re.error: bad escape \\$next")
                else -> literal.append('\\').append(next)
            }
            i += 2
        }
        flush()
        return parts
    }

    private class NamedRef(val name: String)

    private fun named(m: Matcher, ref: NamedRef): String? = try {
        m.group(ref.name)
    } catch (e: IllegalArgumentException) {
        throw RenderError("unknown group name '${ref.name}'")
    }

    fun search(value: String, a: Args): RValue {
        val pattern = a.str(0, "regex") ?: throw RenderError("regex_search() missing regex")
        val ignorecase = a.keywords["ignorecase"]?.let { RValues.truthy(it) } ?: false
        val multiline = a.keywords["multiline"]?.let { RValues.truthy(it) } ?: false
        val compiled = compile(pattern, ignorecase, multiline) ?: return notEmulated("regex_search pattern")
        val refs = a.positional.drop(1).map { ref ->
            val text = (ref as? RValue.Str)?.value ?: throw RenderError("Unknown argument")
            Regex("""\\(\d+)""").matchEntire(text)?.let { it.groupValues[1].toInt() }
                ?: Regex("""\\g<(\w+)>""").matchEntire(text)?.let { NamedRef(it.groupValues[1]) }
                ?: throw RenderError("Unknown argument")
        }
        val m = compiled.matcher(value)
        if (!m.find()) return RValue.None
        if (refs.isEmpty()) return RValue.Str(m.group())
        return RValue.List(
            refs.map { ref ->
                val text = if (ref is Int) m.group(ref) else named(m, ref as NamedRef)
                if (text == null) RValue.None else RValue.Str(text)
            },
        )
    }

    fun findall(value: String, a: Args): RValue {
        val pattern = a.str(0, "regex") ?: throw RenderError("regex_findall() missing regex")
        val multiline = a.bool(1, "multiline")
        val ignorecase = a.bool(2, "ignorecase")
        val compiled = compile(pattern, ignorecase, multiline) ?: return notEmulated("regex_findall pattern")
        val m = compiled.matcher(value)
        val out = ArrayList<RValue>()
        while (m.find()) {
            out += when (m.groupCount()) {
                0 -> RValue.Str(m.group())
                1 -> RValue.Str(m.group(1) ?: "")
                else -> RValue.Tuple((1..m.groupCount()).map { RValue.Str(m.group(it) ?: "") })
            }
        }
        return RValue.List(out)
    }

    /** `re.escape` (Python 3.7+: only regex-special characters). */
    fun escape(s: String): String = buildString {
        for (c in s) {
            if (c in "()[]{}?*+-|^\$\\.&~# \t\n\r\u000B\u000C") append('\\')
            append(c)
        }
    }

    private fun notEmulated(what: String) = RValue.Hole(Placeholder.notEmulated(what))
}

/**
 * A CPython `set` of ints (Objects/setobject.c): the open-addressing table with linear probes, perturbation, the
 * resize rule and the algorithms of `|`, `&`, `-`, `^`, so `list(set(...))` iterates in CPython's order. Int hashes
 * do not depend on the hash seed, so this order is the one every run produces.
 */
internal class CPythonIntSet private constructor() {
    private var keys = arrayOfNulls<RValue>(MIN_SIZE)
    private var hashes = LongArray(MIN_SIZE)
    private var dummy = BooleanArray(MIN_SIZE)
    private var fill = 0
    private var used = 0
    private val mask: Int get() = keys.size - 1

    fun items(): List<RValue> = keys.indices.mapNotNull { i -> keys[i]?.takeUnless { dummy[i] } }

    private fun entries(): List<Pair<RValue, Long>> = keys.indices.mapNotNull { i -> keys[i]?.takeUnless { dummy[i] }?.let { it to hashes[i] } }

    fun contains(key: RValue, hash: Long): Boolean = find(key, hash) >= 0

    private fun find(key: RValue, hash: Long): Int {
        var i = (hash and mask.toLong()).toInt()
        var perturb = hash
        while (true) {
            var probes = if (i + LINEAR_PROBES <= mask) LINEAR_PROBES else 0
            var j = i
            while (true) {
                if (keys[j] == null) return -1
                if (!dummy[j] && hashes[j] == hash && RValues.pyEquals(keys[j]!!, key)) return j
                if (probes-- == 0) break
                j++
            }
            perturb = perturb ushr PERTURB_SHIFT
            i = ((i.toLong() * 5 + 1 + perturb) and mask.toLong()).toInt()
        }
    }

    fun add(key: RValue, hash: Long) {
        var i = (hash and mask.toLong()).toInt()
        var perturb = hash
        var freeslot = -1
        while (true) {
            var probes = if (i + LINEAR_PROBES <= mask) LINEAR_PROBES else 0
            var j = i
            while (true) {
                if (keys[j] == null) {
                    if (freeslot >= 0) {
                        keys[freeslot] = key
                        hashes[freeslot] = hash
                        dummy[freeslot] = false
                        used++
                        return
                    }
                    keys[j] = key
                    hashes[j] = hash
                    fill++
                    used++
                    if (fill.toLong() * 5 >= mask.toLong() * 3) resize(if (used > 50_000) used * 2 else used * 4)
                    return
                }
                if (dummy[j]) {
                    if (freeslot < 0) freeslot = j
                } else if (hashes[j] == hash && RValues.pyEquals(keys[j]!!, key)) {
                    return
                }
                if (probes-- == 0) break
                j++
            }
            perturb = perturb ushr PERTURB_SHIFT
            i = ((i.toLong() * 5 + 1 + perturb) and mask.toLong()).toInt()
        }
    }

    fun discard(key: RValue, hash: Long): Boolean {
        val j = find(key, hash)
        if (j < 0) return false
        dummy[j] = true
        hashes[j] = -1
        used--
        return true
    }

    private fun resize(minUsed: Int) {
        var size = MIN_SIZE
        while (size <= minUsed) size = size shl 1
        val old = entries()
        keys = arrayOfNulls(size)
        hashes = LongArray(size)
        dummy = BooleanArray(size)
        fill = old.size
        used = old.size
        for ((key, hash) in old) insertClean(key, hash)
    }

    private fun insertClean(key: RValue, hash: Long) {
        var i = (hash and mask.toLong()).toInt()
        var perturb = hash
        while (true) {
            if (keys[i] == null) return put(i, key, hash)
            if (i + LINEAR_PROBES <= mask) {
                for (j in 1..LINEAR_PROBES) {
                    if (keys[i + j] == null) return put(i + j, key, hash)
                }
            }
            perturb = perturb ushr PERTURB_SHIFT
            i = ((i.toLong() * 5 + 1 + perturb) and mask.toLong()).toInt()
        }
    }

    private fun put(i: Int, key: RValue, hash: Long) {
        keys[i] = key
        hashes[i] = hash
    }

    /** `set_merge`: what `set(s)`, `s.copy()` and `|` do with another set. */
    private fun merge(other: CPythonIntSet) {
        if (other.used == 0) return
        if ((fill + other.used).toLong() * 5 >= mask.toLong() * 3) resize((used + other.used) * 2)
        if (fill == 0 && mask == other.mask && other.fill == other.used) {
            other.keys.copyInto(keys)
            other.hashes.copyInto(hashes)
            fill = other.fill
            used = other.used
            return
        }
        if (fill == 0) {
            fill = other.used
            used = other.used
            for ((key, hash) in other.entries()) insertClean(key, hash)
            return
        }
        for ((key, hash) in other.entries()) add(key, hash)
    }

    private fun copy(): CPythonIntSet = CPythonIntSet().also { it.merge(this) }

    fun union(other: CPythonIntSet): CPythonIntSet = copy().also { it.merge(other) }

    fun intersection(other: CPythonIntSet): CPythonIntSet {
        val result = CPythonIntSet()
        val (big, small) = if (other.used > used) other to this else this to other
        for ((key, hash) in small.entries()) if (big.contains(key, hash)) result.add(key, hash)
        return result
    }

    fun difference(other: CPythonIntSet): CPythonIntSet {
        if ((used shr 2) > other.used) {
            val result = copy()
            for ((key, hash) in other.entries()) result.discard(key, hash)
            return result
        }
        val result = CPythonIntSet()
        for ((key, hash) in entries()) if (!other.contains(key, hash)) result.add(key, hash)
        return result
    }

    fun symmetricDifference(other: CPythonIntSet): CPythonIntSet {
        val result = other.copy()
        for ((key, hash) in entries()) if (!result.discard(key, hash)) result.add(key, hash)
        return result
    }

    companion object {
        private const val MIN_SIZE = 8
        private const val LINEAR_PROBES = 9
        private const val PERTURB_SHIFT = 5
        private val MODULUS = java.math.BigInteger.ONE.shiftLeft(61) - java.math.BigInteger.ONE

        /** `set(items)` of ints and bools. */
        fun of(items: List<RValue>): CPythonIntSet = CPythonIntSet().also { set -> items.forEach { set.add(it, hash(it)) } }

        /** `hash(n)` of an int: n mod 2**61-1 with the sign kept, -1 mapped to -2. */
        fun hash(value: RValue): Long {
            val n = when (value) {
                is RValue.Int -> value.value
                is RValue.Bool -> if (value.value) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                else -> throw IllegalArgumentException(value.typeName)
            }
            val h = n.abs().mod(MODULUS).toLong().let { if (n.signum() < 0) -it else it }
            return if (h == -1L) -2L else h
        }
    }
}

/**
 * PyYAML's `yaml.dump` as `to_yaml` (`default_flow_style=None`) and `to_nice_yaml` (`indent=4`,
 * `default_flow_style=False`) call it with `AnsibleDumper`. Returns null where the exact output is not reproduced:
 * multi-line or double-quoted strings, lines past the width (PyYAML folds them), tuples and unusual options.
 */
internal object YamlDump {
    private class Abstain : RuntimeException(null, null, false, false)

    private val OPTIONS = setOf("indent", "width", "sort_keys", "default_flow_style", "allow_unicode")

    fun dump(value: RValue, nice: Boolean, indent: Int?, a: Args): String? {
        RValues.requireKnown(value)
        if (a.keywords.keys.any { it !in OPTIONS }) return null
        val step = (if (nice) indent else a.keywords["indent"]?.let { (it as? RValue.Int)?.value?.toInt() }) ?: if (nice) 4 else 2
        if (step !in 2..9) return null
        val width = a.keywords["width"]?.let { (it as? RValue.Int)?.value?.toInt() } ?: 80
        val sortKeys = a.keywords["sort_keys"]?.let { RValues.truthy(it) } ?: true
        val flowStyle: Boolean? = when (val v = a.keywords["default_flow_style"]) {
            null -> if (nice) false else null
            RValue.None -> null
            else -> RValues.truthy(v)
        }
        if (a.keywords["allow_unicode"]?.let { !RValues.truthy(it) } == true) return null
        return try {
            val text = Writer(step, sortKeys, flowStyle).root(value)
            if (text.lines().any { it.length > width }) null else text
        } catch (e: Abstain) {
            null
        }
    }

    private class Writer(val step: Int, val sortKeys: Boolean, val flowStyle: Boolean?) {
        val out = StringBuilder()

        fun root(value: RValue): String {
            when {
                isBlock(value) && value is RValue.Dict -> mapping(value, 0, inline = false)
                isBlock(value) -> sequence(RValues.sequence(value)!!, 0, inline = false)
                isCollection(value) -> out.append(flow(value)).append('\n')
                else -> {
                    // PyYAML may end a plain top-level string with a `...` document end marker.
                    val (text, plain) = scalar(value, flow = false)
                    if (plain && value is RValue.Str) throw Abstain()
                    out.append(text).append('\n')
                }
            }
            return out.toString()
        }

        private fun isCollection(value: RValue) = value is RValue.Dict || value is RValue.List || value is RValue.Tuple

        private fun isBlock(value: RValue): Boolean {
            if (value is RValue.Tuple) throw Abstain()
            val items = when (value) {
                is RValue.Dict -> value.keys + value.map.values
                is RValue.List -> value.items
                else -> return false
            }
            if (items.isEmpty()) return false
            return when (flowStyle) {
                true -> false
                false -> true
                null -> items.any { isCollection(it) }
            }
        }

        private fun entries(dict: RValue.Dict): List<Pair<RValue, RValue>> {
            if (!sortKeys) return dict.entries
            return try {
                dict.entries.sortedWith { x, y -> RValues.compare(x.first, y.first, "<") }
            } catch (e: RenderError) {
                dict.entries
            }
        }

        private fun mapping(dict: RValue.Dict, indent: Int, inline: Boolean) {
            entries(dict).forEachIndexed { i, (key, value) ->
                if (i > 0 || !inline) out.append(" ".repeat(indent))
                if (isCollection(key)) throw Abstain()
                val (keyText, _) = scalar(key, flow = false, simpleKey = true)
                if (keyText.length > 128) throw Abstain()
                out.append(keyText).append(':')
                when {
                    isBlock(value) && value is RValue.Dict -> {
                        out.append('\n')
                        mapping(value, indent + step, inline = false)
                    }
                    isBlock(value) -> {
                        out.append('\n')
                        sequence(RValues.sequence(value)!!, indent, inline = false)
                    }
                    isCollection(value) -> out.append(' ').append(flow(value)).append('\n')
                    else -> out.append(' ').append(scalar(value, flow = false).first).append('\n')
                }
            }
        }

        private fun sequence(items: List<RValue>, indent: Int, inline: Boolean) {
            items.forEachIndexed { i, value ->
                if (i > 0 || !inline) out.append(" ".repeat(indent))
                out.append('-')
                when {
                    isBlock(value) && value is RValue.Dict -> {
                        out.append(" ".repeat(step - 1))
                        mapping(value, indent + step, inline = true)
                    }
                    isBlock(value) -> {
                        out.append(" ".repeat(step - 1))
                        sequence(RValues.sequence(value)!!, indent + step, inline = true)
                    }
                    isCollection(value) -> out.append(' ').append(flow(value)).append('\n')
                    else -> out.append(' ').append(scalar(value, flow = false).first).append('\n')
                }
            }
        }

        private fun flow(value: RValue): String = when (value) {
            is RValue.Dict -> if (value.map.isEmpty()) "{}" else entries(value).joinToString(", ", "{", "}") { (k, v) ->
                if (isCollection(k)) throw Abstain()
                scalar(k, flow = true, simpleKey = true).first + ": " + (if (isCollection(v)) flow(v) else scalar(v, flow = true).first)
            }
            is RValue.List -> if (value.items.isEmpty()) "[]" else value.items.joinToString(", ", "[", "]") { if (isCollection(it)) flow(it) else scalar(it, flow = true).first }
            else -> throw Abstain()
        }

        /** The scalar's text and whether it is plain. */
        fun scalar(value: RValue, flow: Boolean, simpleKey: Boolean = false): Pair<String, Boolean> = when (value) {
            RValue.None -> "null" to true
            is RValue.Bool -> (if (value.value) "true" else "false") to true
            is RValue.Int -> value.value.toString() to true
            is RValue.Float -> when {
                value.value.isNaN() -> ".nan"
                value.value == Double.POSITIVE_INFINITY -> ".inf"
                value.value == Double.NEGATIVE_INFINITY -> "-.inf"
                else -> PyRepr.floatRepr(value.value).lowercase().let { if ('.' !in it && 'e' in it) it.replaceFirst("e", ".0e") else it }
            } to true
            is RValue.Date -> PyRepr.str(value.value) to true
            is RValue.Str -> string(value.value, flow, simpleKey)
            else -> throw Abstain()
        }

        private fun string(s: String, flow: Boolean, simpleKey: Boolean): Pair<String, Boolean> {
            val analysis = analyze(s)
            val implicit = Yaml11Resolver.resolvePlain(s) is Resolved.Str
            if (analysis.special || analysis.lineBreaks) throw Abstain()
            if (implicit && !(simpleKey && analysis.empty) && (if (flow) analysis.flowPlain else analysis.blockPlain)) return s to true
            if (!analysis.singleQuoted) throw Abstain()
            return ("'" + s.replace("'", "''") + "'") to false
        }

        private class Analysis(val empty: Boolean, val lineBreaks: Boolean, val special: Boolean, val flowPlain: Boolean, val blockPlain: Boolean, val singleQuoted: Boolean)

        private val WHITESPACE = "\u0000 \t\r\n\u0085\u2028\u2029"
        private val BREAKS = "\n\u0085\u2028\u2029"

        /** PyYAML's `Emitter.analyze_scalar`. */
        private fun analyze(s: String): Analysis {
            if (s.isEmpty()) return Analysis(empty = true, lineBreaks = false, special = false, flowPlain = false, blockPlain = true, singleQuoted = true)
            var flowIndicators = false
            var blockIndicators = false
            var lineBreaks = false
            var special = false
            var leadingSpace = false
            var leadingBreak = false
            var trailingSpace = false
            var trailingBreak = false
            var breakSpace = false
            var spaceBreak = false
            var previousSpace = false
            var previousBreak = false
            if (s.startsWith("---") || s.startsWith("...")) {
                flowIndicators = true
                blockIndicators = true
            }
            var precededByWhitespace = true
            var followedByWhitespace = s.length == 1 || s[1] in WHITESPACE
            for (index in s.indices) {
                val ch = s[index]
                if (index == 0) {
                    if (ch in "#,[]{}&*!|>'\"%@`") {
                        flowIndicators = true
                        blockIndicators = true
                    }
                    if (ch in "?:") {
                        flowIndicators = true
                        if (followedByWhitespace) blockIndicators = true
                    }
                    if (ch == '-' && followedByWhitespace) {
                        flowIndicators = true
                        blockIndicators = true
                    }
                } else {
                    if (ch in ",?[]{}") flowIndicators = true
                    if (ch == ':') {
                        flowIndicators = true
                        if (followedByWhitespace) blockIndicators = true
                    }
                    if (ch == '#' && precededByWhitespace) {
                        flowIndicators = true
                        blockIndicators = true
                    }
                }
                if (ch in BREAKS) lineBreaks = true
                if (!(ch == '\n' || ch in '\u0020'..'\u007E')) {
                    val unicode = (ch == '\u0085' || ch in '\u00A0'..'\uD7FF' || ch in '\uE000'..'\uFFFD' || Character.isSurrogate(ch)) && ch != '\uFEFF'
                    if (!unicode) special = true
                }
                when {
                    ch == ' ' -> {
                        if (index == 0) leadingSpace = true
                        if (index == s.length - 1) trailingSpace = true
                        if (previousBreak) breakSpace = true
                        previousSpace = true
                        previousBreak = false
                    }
                    ch in BREAKS -> {
                        if (index == 0) leadingBreak = true
                        if (index == s.length - 1) trailingBreak = true
                        if (previousSpace) spaceBreak = true
                        previousSpace = false
                        previousBreak = true
                    }
                    else -> {
                        previousSpace = false
                        previousBreak = false
                    }
                }
                precededByWhitespace = ch in WHITESPACE
                followedByWhitespace = index + 2 >= s.length || s[index + 2] in WHITESPACE
            }
            var flowPlain = true
            var blockPlain = true
            var singleQuoted = true
            if (leadingSpace || leadingBreak || trailingSpace || trailingBreak) {
                flowPlain = false
                blockPlain = false
            }
            if (breakSpace) {
                flowPlain = false
                blockPlain = false
                singleQuoted = false
            }
            if (spaceBreak || special) {
                flowPlain = false
                blockPlain = false
                singleQuoted = false
            }
            if (lineBreaks) {
                flowPlain = false
                blockPlain = false
            }
            if (flowIndicators) flowPlain = false
            if (blockIndicators) blockPlain = false
            return Analysis(empty = false, lineBreaks = lineBreaks, special = special, flowPlain = flowPlain, blockPlain = blockPlain, singleQuoted = singleQuoted)
        }
    }
}
