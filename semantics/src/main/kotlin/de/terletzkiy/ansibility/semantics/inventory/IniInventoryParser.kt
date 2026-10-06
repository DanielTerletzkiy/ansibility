package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.PyLiteral
import de.terletzkiy.ansibility.semantics.coerce.PyLiteral.Lit
import de.terletzkiy.ansibility.semantics.coerce.PyLiteral.isHashable
import de.terletzkiy.ansibility.semantics.value.PyException
import de.terletzkiy.ansibility.semantics.value.PyRepr
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.SourceRange
import de.terletzkiy.ansibility.semantics.yaml.YEntry
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import java.math.BigInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Port of ansible-core's INI inventory plugin (`plugins/inventory/ini.py`), writing into the shared [InventoryBuilder]:
 *
 * - lines are Python's `str.splitlines()`, stripped; empty lines and lines starting with `#` or `;` are skipped;
 * - lines before any section are hosts of `ungrouped`; sections are `[g]`, `[g:hosts]`, `[g:vars]`, `[g:children]`,
 *   optionally followed by a `#` comment (a `;` after the header makes it no header at all), and may be declared
 *   after their first use; an `[all]` host ends up in `ungrouped`;
 * - a host line is split like `shlex.split(line, comments=True)`: the first token is a host pattern (ranges and
 *   `:port`, the port applied only when the host is created), every other token must be `key=value`; an unquoted `#`
 *   starts a comment even inside a token;
 * - a `:vars` line is split at the first `=` and both sides are stripped (quotes and `#` stay in the value);
 * - every value goes through `ast.literal_eval` and stays the raw text when that raises `ValueError` or
 *   `SyntaxError` (so `5` is an int on both kinds of line, while `"5"` is an int on a host line, whose quotes shlex
 *   removed, and a string in `:vars`); see [typedValue] for the values YAML cannot hold;
 * - a child or `:vars` section naming a group that is never declared, a children cycle, a malformed header or host
 *   line, an unbalanced quote and YAML content all fail the whole source, at the line ansible-core reports.
 *
 * Values become [YValue]s with ansible-core's type (a string is a quoted scalar whose `sourceText` is the INI
 * spelling), so the precedence engine and the previews need no INI case. Unlike [YamlInventoryParser.parseAll],
 * parsing stops at the first error, as ansible-core does: what was added before it stays in the builder.
 */
object IniInventoryParser {
    /**
     * Parses one INI inventory as ansible-core does when it is the only source: `reconcile_inventory()` runs when the
     * file parsed; when it failed, the result holds only what `all` reaches (normally nothing) plus the error.
     * [core] is the target ansible-core (null: the newest measured behaviour); it only matters for [typedValue].
     */
    fun parse(text: String, core: CoreVersion? = null): InventoryGraph {
        val builder = InventoryBuilder(failFast = true)
        return try {
            parseInto(builder, text, 0, core)
            builder.reconcile()
            builder.build()
        } catch (_: InventoryBuilder.SourceFailure) {
            builder.buildReachable()
        }
    }

    /**
     * `InventoryModule.parse` for [text] as source [index] of [builder], which must be fail-fast. Throws
     * [InventoryBuilder.SourceFailure] where `ini.py` raises; the problem is recorded with the offending line's range.
     */
    internal fun parseInto(builder: InventoryBuilder, text: String, index: Int, core: CoreVersion?) {
        builder.source = index
        IniSource(builder, text, core).parse()
    }

    /** The 1-based line of [offset] in [text], counting lines the way `str.splitlines()` does (as `ini.py` numbers them). */
    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (l in PyText.splitLines(text)) {
            if (offset < l.next) return line
            line++
        }
        return maxOf(1, line - 1)
    }

    /**
     * The ansible-core version that rewrote the INI value handling (data tagging): from it on, tuples and sets
     * become lists, `...` and complex numbers become strings, and bytes are decoded at every depth. Older versions
     * keep `literal_eval`'s result and only decode top-level bytes (2.18.8 measured; the source of every 2.18 and
     * 2.19 release checked for the S-L1 spike).
     */
    val TYPED_VALUES_SINCE = CoreVersion(2, 19, 0)

    /** A `pending_declarations` entry: a group used by a `:vars` or `:children` section before it is declared. */
    private class Pending(val state: String, val name: String, val range: SourceRange) {
        /** Each parent group with the range of the child line that named this group under it. */
        val parents = ArrayList<Pair<String, SourceRange>>()
    }

    private class IniSource(private val builder: InventoryBuilder, private val text: String, core: CoreVersion?) {
        private val modernValues = core == null || core >= TYPED_VALUES_SINCE
        private val pending = LinkedHashMap<String, Pending>()

        private fun fail(message: String, range: SourceRange?): Nothing {
            builder.problem(ProblemSeverity.ERROR, message, range)
            throw InventoryBuilder.SourceFailure(builder.problems.last())
        }

        private fun warn(message: String, range: SourceRange?) = builder.problem(ProblemSeverity.WARNING, message, range)

        /** `_parse`. */
        fun parse() {
            check(builder.failFast) { "INI sources stop at their first error: the builder must be fail-fast" }
            var groupname = InventoryGraph.UNGROUPED
            var state = "hosts"
            for (raw in PyText.splitLines(text)) {
                val line = PyText.strip(text, raw.start, raw.end)
                if (line.isEmpty() || line.text[0] == '#' || line.text[0] == ';') continue

                val section = SECTION.matcher(line.text)
                if (section.matches()) {
                    groupname = section.group(1)
                    state = section.group(2) ?: "hosts"
                    val nameRange = line.range(section, 1)
                    if (state !in STATES) {
                        fail("Section [$groupname:$state] has unknown type: $state", line.range)
                    }
                    if (groupname !in builder.groups && state == "vars" && groupname !in pending) {
                        pending[groupname] = Pending(state, groupname, nameRange)
                    }
                    builder.addGroup(groupname, nameRange)
                    val declared = pending[groupname]
                    if (declared != null && state != "vars") {
                        if (declared.state == "children") addPendingChildren(groupname, HashSet())
                        else if (declared.state == "vars") pending.remove(groupname)
                    }
                    continue
                } else if (line.text.startsWith("[") && line.text.endsWith("]")) {
                    fail(
                        "Invalid section entry: '${line.text}'. Please make sure that there are no spaces in the section " +
                            "entry, and that there are no other invalid characters",
                        line.range,
                    )
                }

                when (state) {
                    "hosts" -> hostDefinition(line, groupname)
                    "vars" -> variableDefinition(line, groupname)
                    "children" -> childDefinition(line, groupname)
                }
            }
            // Any declaration still pending is an unresolved reference; ansible-core reports the first one.
            val first = pending.values.firstOrNull() ?: return
            if (first.state == "vars") {
                fail("Section [${first.name}:vars] not valid for undefined group '${first.name}'.", first.range)
            }
            // The message names the last parent, the line is where the group was first named (`decl['line']`).
            fail("Section [${first.parents.last().first}:children] includes undefined group '${first.name}'.", first.range)
        }

        /** `_add_pending_children`. */
        private fun addPendingChildren(group: String, visiting: MutableSet<String>) {
            val declared = pending[group] ?: return
            if (!visiting.add(group)) return
            for ((parent, range) in declared.parents) {
                builder.addGroup(group, range)
                builder.addChild(parent, group, range)
                if (pending[parent]?.state == "children") addPendingChildren(parent, visiting)
            }
            pending.remove(group)
        }

        /** A `[g:children]` line (`_parse_group_name`). */
        private fun childDefinition(line: Line, groupname: String) {
            val m = GROUP_NAME.matcher(line.text)
            if (!m.matches()) fail("Expected group name, got: ${line.text}", line.range)
            val child = m.group(1)
            val range = line.range(m, 1)
            if (child !in builder.groups) {
                pending.getOrPut(child) { Pending("children", child, range) }.parents += groupname to range
            } else {
                builder.addGroup(child, range)
                builder.addChild(groupname, child, range)
            }
        }

        /** A `[g:vars]` line (`_parse_variable_definition`). */
        private fun variableDefinition(line: Line, groupname: String) {
            val eq = line.text.indexOf('=')
            if (eq < 0) fail("Expected key=value, got: ${line.text}", line.range)
            val key = PyText.strip(text, line.start, line.start + eq)
            val value = PyText.strip(text, line.start + eq + 1, line.end)
            val typed = typedValue(value.text, value.text, value.range)
            builder.setVariables(groupname, listOf(InventoryBuilder.VarEntry(key.text, typed, key.range)), line.range)
        }

        /** A host line (`_parse_host_definition` + `_populate_host_vars`). */
        private fun hostDefinition(line: Line, groupname: String) {
            val tokens = try {
                PyShlex.split(text, line.start, line.end)
            } catch (e: PyShlex.ShlexError) {
                fail("Error parsing host definition '${line.text}': ${e.message}", line.range)
            }
            if (tokens.isEmpty()) fail("Error parsing host definition '${line.text}': list index out of range", line.range)
            val pattern = tokens[0]
            val patternRange = SourceRange(pattern.start, pattern.end)
            val expansion = expandHostPattern(pattern.text, patternRange)

            val variables = LinkedHashMap<String, InventoryBuilder.VarEntry>()
            for (token in tokens.drop(1)) {
                val eq = token.text.indexOf('=')
                if (eq < 0) fail("Expected key=value host variable assignment, got: ${token.text}", SourceRange(token.start, token.end))
                val key = token.text.substring(0, eq)
                // The value as written, quotes included: from right after the `=` to the end of the token.
                val valueRange = SourceRange(minOf(token.offsetOf(eq) + 1, token.end), token.end)
                val raw = text.substring(valueRange.start, valueRange.end)
                val typed = typedValue(token.text.substring(eq + 1), raw, valueRange)
                // A dict: a repeated key keeps its first position and takes the last value.
                variables[key] = InventoryBuilder.VarEntry(key, typed, SourceRange(token.start, token.offsetOf(eq)))
            }
            val entries = variables.values.toList()
            val block = if (tokens.size > 1) SourceRange(tokens[1].start, tokens.last().end) else patternRange
            for (host in expansion.hosts) {
                builder.addHost(host, groupname, expansion.port, patternRange)
                if (entries.isNotEmpty()) builder.setVariables(host, entries, block)
            }
        }

        /** `InventoryModule._expand_hostpattern`: the base expansion plus the INI-specific refusals. */
        private fun expandHostPattern(pattern: String, range: SourceRange): HostRanges.Expansion {
            val expansion = try {
                HostRanges.expandHostPattern(pattern)
            } catch (e: HostRangeException) {
                fail("Invalid host pattern '$pattern': ${e.message}", range)
            }
            if (PyText.strip(pattern).endsWith(":") && expansion.port == null) {
                fail(
                    "Invalid host pattern '$pattern' supplied, ending in ':' is not allowed, this character is reserved " +
                        "to provide a port.",
                    range,
                )
            }
            if (expansion.hosts.any { PyText.strip(it) == "---" }) {
                fail("Invalid host pattern '$pattern' supplied, '---' is normally a sign this is a YAML file.", range)
            }
            return expansion
        }

        /**
         * `_parse_value`: `ast.literal_eval(value)`, or [value] itself when that raises `ValueError` or
         * `SyntaxError`. Any other exception (an unhashable set element or dict key: `TypeError`) fails the source.
         *
         * From ansible-core 2.19 ([TYPED_VALUES_SINCE]) the result is coerced recursively: tuples and sets become
         * lists, `...` and complex numbers their `str()`, bytes are decoded. Before 2.19 only top-level bytes are
         * decoded and the other Python types stay; YAML cannot hold a set, `...` or a complex number, so they are
         * shown as 2.19 shows them, with a warning that ansible-inventory cannot print such a value.
         */
        fun typedValue(value: String, raw: String, range: SourceRange): YValue {
            val literal = try {
                PyLiteral.literalEval(value)
            } catch (e: PyException) {
                if (e.pyClass == "ValueError" || e.pyClass == "SyntaxError") return string(value, raw, range)
                fail("Failed to parse inventory value '$value': ${e.message}", range)
            }
            if (literal is Lit.LStr) return string(literal.value, raw, range)
            if (literal is Lit.LBytes) return string(decode(literal.value), raw, range)
            val exotic = LinkedHashSet<String>()
            val converted = convert(literal, range, top = true, exotic)
            if (!modernValues && exotic.isNotEmpty()) {
                warn(
                    "ansible-core before 2.19 keeps ${exotic.joinToString(", ")} from '$value' as Python objects, " +
                        "which ansible-inventory cannot print",
                    range,
                )
            }
            return if (converted is YScalar) converted.copy(sourceText = raw) else converted
        }

        private fun convert(lit: Lit, range: SourceRange, top: Boolean, exotic: MutableSet<String>): YValue = when (lit) {
            Lit.LNone -> YScalar("null", ScalarStyle.PLAIN, range = range)
            is Lit.LBool -> YScalar(if (lit.value) "True" else "False", ScalarStyle.PLAIN, range = range)
            is Lit.LInt -> YScalar(lit.value.toString(), ScalarStyle.PLAIN, range = range)
            is Lit.LFloat -> YScalar(yamlFloat(lit.value), ScalarStyle.PLAIN, range = range)
            is Lit.LStr -> string(lit.value, lit.value, range)
            is Lit.LBytes -> {
                if (!top) exotic += "bytes"
                string(decode(lit.value), lit.value, range)
            }
            Lit.LEllipsis -> {
                exotic += "Ellipsis"
                string("...", "...", range)
            }
            is Lit.LComplex -> {
                exotic += "complex"
                complexRepr(lit).let { string(it, it, range) }
            }
            is Lit.LTuple -> YSeq(lit.items.map { convert(it, range, top = false, exotic) }, range)
            is Lit.LList -> YSeq(lit.items.map { convert(it, range, top = false, exotic) }, range)
            is Lit.LSet -> {
                exotic += "set"
                YSeq(setOrder(lit.items).map { convert(it, range, top = false, exotic) }, range)
            }
            is Lit.LDict -> YMap(dictEntries(lit, range, exotic), range)
        }

        /** Python's dict construction: equal keys (`1`, `1.0`, `True`) keep the first key and take the last value. */
        private fun dictEntries(dict: Lit.LDict, range: SourceRange, exotic: MutableSet<String>): List<YEntry> {
            val keys = ArrayList<Lit>()
            val values = ArrayList<Lit>()
            for ((k, v) in dict.pairs) {
                val i = keys.indexOfFirst { pyEquals(it, k) }
                if (i < 0) {
                    keys += k
                    values += v
                } else {
                    values[i] = v
                }
            }
            return keys.indices.map { i -> YEntry(keyScalar(keys[i], range, exotic), convert(values[i], range, top = false, exotic)) }
        }

        /** A dict key as a scalar; `json.dumps` writes str, int, float, bool and None keys and refuses the others. */
        private fun keyScalar(key: Lit, range: SourceRange, exotic: MutableSet<String>): YScalar = when (key) {
            is Lit.LStr, Lit.LNone, is Lit.LBool, is Lit.LInt, is Lit.LFloat -> convert(key, range, top = false, exotic) as YScalar
            is Lit.LBytes -> {
                exotic += "a bytes key"
                string(decode(key.value), key.value, range)
            }
            else -> {
                exotic += "a ${key.typeName} key"
                val text = literalRepr(key)
                string(text, text, range)
            }
        }


        private fun string(value: String, raw: String, range: SourceRange) =
            YScalar(value, ScalarStyle.DOUBLE_QUOTED, sourceText = raw, range = range)
    }

    // ------------------------------------------------------------------ Python values

    /** `to_text(bytes)`: the literal's characters are byte values; decoded as UTF-8 (invalid sequences replaced). */
    private fun decode(bytes: String): String = String(ByteArray(bytes.length) { bytes[it].code.toByte() }, Charsets.UTF_8)

    /** A float spelling that resolves to the same float under YAML 1.1 (which needs a dot and a signed exponent). */
    private fun yamlFloat(value: Double): String = when {
        value.isNaN() -> ".nan"
        value == Double.POSITIVE_INFINITY -> ".inf"
        value == Double.NEGATIVE_INFINITY -> "-.inf"
        else -> PyRepr.floatRepr(value).let { if ('e' in it && '.' !in it) it.replace("e", ".0e") else it }
    }

    /** `repr(complex)`: `1j`, `(1+2j)`, `(-0-1j)` (CPython's `complex_repr`, floats without an added `.0`). */
    private fun complexRepr(c: Lit.LComplex): String {
        fun part(x: Double) = PyRepr.floatRepr(x).removeSuffix(".0")
        if (c.real == 0.0 && 1.0 / c.real > 0) return part(c.imag) + "j"
        val imag = part(c.imag).let { if (it.startsWith("-")) it else "+$it" }
        return "(" + part(c.real) + imag + "j)"
    }

    /** `repr()` of a hashable literal, for keys JSON cannot hold (tuples, `...`, complex numbers). */
    private fun literalRepr(lit: Lit): String = when (lit) {
        Lit.LNone -> "None"
        is Lit.LBool -> if (lit.value) "True" else "False"
        is Lit.LInt -> lit.value.toString()
        is Lit.LFloat -> PyRepr.floatRepr(lit.value)
        is Lit.LStr -> PyRepr.strRepr(lit.value)
        is Lit.LBytes -> "b" + PyRepr.strRepr(lit.value)
        Lit.LEllipsis -> "Ellipsis"
        is Lit.LComplex -> complexRepr(lit)
        is Lit.LTuple -> if (lit.items.size == 1) "(${literalRepr(lit.items[0])},)" else lit.items.joinToString(", ", "(", ")") { literalRepr(it) }
        is Lit.LList, is Lit.LSet, is Lit.LDict -> lit.typeName
    }

    /**
     * The elements of a set literal in the order a list made from the set has: CPython's iteration order (hash table
     * order, `setobject.c`) when every element hashes to its integer value (ints, bools, integral floats), which is
     * deterministic; otherwise the literal's order without duplicates, since string hashes are randomised per process.
     */
    private fun setOrder(items: List<Lit>): List<Lit> {
        val hashes = items.map { intHash(it) ?: return distinct(items) }
        var mask = MIN_SET_SIZE - 1
        var table = arrayOfNulls<Int>(MIN_SET_SIZE) // indexes into items
        var fill = 0

        /** `set_insert_clean`: the first free slot of the probe sequence. */
        fun insertClean(into: Array<Int?>, intoMask: Int, item: Int) {
            val hash = hashes[item]
            var perturb = hash
            var i = (hash and intoMask.toLong()).toInt()
            while (true) {
                if (into[i] == null) return run { into[i] = item }
                if (i + LINEAR_PROBES <= intoMask) {
                    for (j in 1..LINEAR_PROBES) if (into[i + j] == null) return run { into[i + j] = item }
                }
                perturb = perturb ushr PERTURB_SHIFT
                i = ((i.toLong() * 5 + 1 + perturb) and intoMask.toLong()).toInt()
            }
        }

        items@ for ((index, item) in items.withIndex()) {
            val hash = hashes[index]
            var perturb = hash
            var i = (hash and mask.toLong()).toInt()
            // `set_add_entry`: probe until a free slot or an equal element.
            while (true) {
                val probes = if (i + LINEAR_PROBES <= mask) LINEAR_PROBES else 0
                for (j in 0..probes) {
                    val slot = table[i + j]
                    if (slot == null) {
                        table[i + j] = index
                        fill++
                        if (fill * 5L >= mask * 3L) {
                            // `set_table_resize(used * 4)`: re-insert in table order.
                            var size = MIN_SET_SIZE
                            while (size <= fill * 4) size = size shl 1
                            val resized = arrayOfNulls<Int>(size)
                            for (entry in table) if (entry != null) insertClean(resized, size - 1, entry)
                            table = resized
                            mask = size - 1
                        }
                        continue@items
                    }
                    if (hashes[slot] == hash && pyEquals(items[slot], item)) continue@items
                }
                perturb = perturb ushr PERTURB_SHIFT
                i = ((i.toLong() * 5 + 1 + perturb) and mask.toLong()).toInt()
            }
        }
        return table.filterNotNull().map { items[it] }
    }

    private fun distinct(items: List<Lit>): List<Lit> {
        val out = ArrayList<Lit>()
        for (item in items) if (out.none { pyEquals(it, item) }) out += item
        return out
    }

    private const val MIN_SET_SIZE = 8
    private const val LINEAR_PROBES = 9
    private const val PERTURB_SHIFT = 5
    private val HASH_MODULUS: BigInteger = BigInteger.ONE.shiftLeft(61) - BigInteger.ONE

    /** CPython's `hash()` of an int-like literal (`long_hash`: the value modulo 2**61 - 1, with -1 mapped to -2). */
    private fun intHash(lit: Lit): Long? {
        val value = when (lit) {
            is Lit.LInt -> lit.value
            is Lit.LBool -> if (lit.value) BigInteger.ONE else BigInteger.ZERO
            is Lit.LFloat -> if (lit.value.isFinite() && lit.value == Math.floor(lit.value)) java.math.BigDecimal(lit.value).toBigInteger() else return null
            else -> return null
        }
        val hash = value.abs().mod(HASH_MODULUS).toLong().let { if (value.signum() < 0) -it else it }
        return if (hash == -1L) -2L else hash
    }

    /** Python `==` between hashable literals: numbers compare by value across int, float, bool and complex. */
    private fun pyEquals(a: Lit, b: Lit): Boolean {
        if (!a.isHashable() || !b.isHashable()) return false
        val x = numeric(a)
        val y = numeric(b)
        if (x != null || y != null) return x != null && y != null && x.first == y.first && x.second == y.second
        return when (a) {
            is Lit.LTuple -> b is Lit.LTuple && a.items.size == b.items.size && a.items.indices.all { pyEquals(a.items[it], b.items[it]) }
            else -> a == b
        }
    }

    /** A number as (real, imag) with exact integers, or null for other values. */
    private fun numeric(lit: Lit): Pair<Any, Any>? = when (lit) {
        is Lit.LBool -> exact(if (lit.value) 1.0 else 0.0) to exact(0.0)
        is Lit.LInt -> lit.value to exact(0.0)
        is Lit.LFloat -> exact(lit.value) to exact(0.0)
        is Lit.LComplex -> exact(lit.real) to exact(lit.imag)
        else -> null
    }

    /** Integral finite floats compare equal to the same int (`1 == 1.0`); other floats compare as themselves. */
    private fun exact(d: Double): Any = if (d.isFinite() && d == Math.floor(d)) java.math.BigDecimal(d).toBigInteger() else d

    // ------------------------------------------------------------------ patterns

    private val STATES = setOf("hosts", "children", "vars")

    /** Python's `\s` (and `str.strip()`) for `str` patterns: every character `str.isspace()` accepts. */
    private const val PY_SPACE = "\\t\\n\\u000B\\u000C\\r\\u001C-\\u001F \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    /** `patterns['section']`: `[name]` or `[name:tag]`, then optional whitespace and a `#` comment. */
    private val SECTION: Pattern =
        Pattern.compile("""\[([^:\]$PY_SPACE]+)(?::([\p{L}\p{N}_]+))?\][$PY_SPACE]*(?:#.*)?""", Pattern.DOTALL)

    /** `patterns['groupname']`. */
    private val GROUP_NAME: Pattern = Pattern.compile("""([^:\]$PY_SPACE]+)[$PY_SPACE]*(?:#.*)?""", Pattern.DOTALL)

    /** A stripped line: its text and where it lies in the file. */
    private class Line(val text: String, val start: Int) {
        val end: Int get() = start + text.length
        val range: SourceRange get() = SourceRange(start, end)

        fun isEmpty() = text.isEmpty()

        fun range(m: Matcher, group: Int) = SourceRange(start + m.start(group), start + m.end(group))
    }

    /** Python `str` helpers with offsets into the source text. */
    private object PyText {
        /** One line of `str.splitlines()`: `[start, end)` without the terminator; [next] starts the following line. */
        class Raw(val start: Int, val end: Int, val next: Int)

        /** `str.splitlines()`: breaks at `\n`, `\r`, `\r\n`, `\v`, `\f`, `\x1c`–`\x1e`, `\x85`, ` `, ` `. */
        fun splitLines(text: String): List<Raw> {
            val out = ArrayList<Raw>()
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (isLineBreak(c)) {
                    val next = if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                    out += Raw(start, i, next)
                    start = next
                    i = next
                } else {
                    i++
                }
            }
            if (start < text.length) out += Raw(start, text.length, text.length)
            return out
        }

        private fun isLineBreak(c: Char) =
            c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c in '\u001C'..'\u001E' ||
                c == '\u0085' || c == ' ' || c == ' '

        /** `str.isspace()` for one character. */
        fun isSpace(c: Char) =
            c == ' ' || c in '\t'..'\r' || c in '\u001C'..'\u001F' || c == '\u0085' || c == ' ' || c == ' ' ||
                c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　'

        /** `text[start:end].strip()` with its position. */
        fun strip(text: String, start: Int, end: Int): Line {
            var s = start
            var e = end
            while (s < e && isSpace(text[s])) s++
            while (e > s && isSpace(text[e - 1])) e--
            return Line(text.substring(s, e), s)
        }

        fun strip(text: String): String = strip(text, 0, text.length).text
    }
}

/**
 * Port of Python's `shlex.split(s, comments=True, posix=True)` (a `shlex` with `whitespace_split=True`), keeping the
 * source offset of every character of every token.
 */
internal object PyShlex {
    /** `ValueError` from `shlex` ("No closing quotation", "No escaped character"). */
    class ShlexError(message: String) : Exception(message, null, false, false)

    /** One token: its text after quote and escape removal and the source span it came from. */
    class Token(val text: String, val start: Int, val end: Int, private val offsets: IntArray) {
        /** The source offset of character [index] of [text]; [text]'s length maps to [end]. */
        fun offsetOf(index: Int): Int = if (index < offsets.size) offsets[index] else end
    }

    private const val WHITESPACE = " \t\r\n"
    private const val QUOTES = "'\""
    private const val ESCAPE = '\\'
    private const val ESCAPED_QUOTES = "\""
    private const val COMMENTERS = "#"

    /** Splits `source[start, end)`; offsets refer to [source]. */
    fun split(source: String, start: Int = 0, end: Int = source.length): List<Token> {
        val tokens = ArrayList<Token>()
        var i = start
        var state = ' ' // ' ' between tokens, 'a' in a word, a quote character, or ESCAPE
        var eof = false
        while (!eof) {
            val token = StringBuilder()
            val offsets = ArrayList<Int>()
            var quoted = false
            var escapedState = ' '
            var tokenStart = i
            var tokenEnd: Int
            while (true) {
                val at = i
                val c: Char? = if (i < end) source[i++] else null
                when (state) {
                    ' ' -> when {
                        c == null -> {
                            eof = true
                            tokenEnd = at
                            break
                        }
                        c in WHITESPACE -> continue
                        c in COMMENTERS -> i = end // readline(): the rest of the line is a comment
                        else -> {
                            tokenStart = at
                            state = when {
                                c == ESCAPE -> {
                                    escapedState = 'a'
                                    ESCAPE
                                }
                                c in QUOTES -> c
                                else -> {
                                    token.append(c)
                                    offsets += at
                                    'a'
                                }
                            }
                        }
                    }
                    '\'', '"' -> {
                        quoted = true
                        when {
                            c == null -> throw ShlexError("No closing quotation")
                            c == state -> state = 'a'
                            c == ESCAPE && state in ESCAPED_QUOTES -> {
                                escapedState = state
                                state = ESCAPE
                            }
                            else -> {
                                token.append(c)
                                offsets += at
                            }
                        }
                    }
                    ESCAPE -> {
                        if (c == null) throw ShlexError("No escaped character")
                        // In quotes only the quote itself and the escape character may be escaped.
                        if (escapedState in QUOTES && c != ESCAPE && c != escapedState) {
                            token.append(ESCAPE)
                            offsets += at - 1
                        }
                        token.append(c)
                        offsets += at
                        state = escapedState
                    }
                    else -> when { // 'a'
                        c == null -> {
                            eof = true
                            tokenEnd = at
                            break
                        }
                        c in WHITESPACE -> {
                            state = ' '
                            tokenEnd = at
                            break
                        }
                        c in COMMENTERS -> {
                            i = end
                            state = ' '
                            tokenEnd = at
                            break
                        }
                        c in QUOTES -> state = c
                        c == ESCAPE -> {
                            escapedState = 'a'
                            state = ESCAPE
                        }
                        else -> {
                            token.append(c)
                            offsets += at
                        }
                    }
                }
            }
            // posix: an empty word ends the stream unless it was quoted ("" is an empty token).
            if (token.isNotEmpty() || quoted) tokens += Token(token.toString(), tokenStart, tokenEnd, offsets.toIntArray())
        }
        return tokens
    }
}
