package de.terletzkiy.ansibility.semantics.inventory

import java.math.BigInteger
import java.util.regex.Pattern

/**
 * Port of `ansible.parsing.utils.addresses.parse_address`: splits an optional `:port` off a host identifier
 * and checks that what remains is an IPv4 address, an IPv6 address or a hostname, each possibly containing
 * `[x:y(:z)]` ranges.
 */
object HostAddress {
    /** A parsed host identifier; [port] is null when none was given. */
    data class Parsed(val host: String, val port: BigInteger?)

    private const val NUMERIC_RANGE = """\[(?:[0-9]+:[0-9]+)(?::[0-9]+)?\]"""
    private const val HEX_RANGE = """\[(?:[0-9a-f]+:[0-9a-f]+)(?::[0-9]+)?\]"""
    private const val ALNUM_RANGE = """\[(?:[a-z]:[a-z]|[0-9]+:[0-9]+)(?::[0-9]+)?\]"""
    private const val IPV6_COMPONENT = "(?:[0-9a-f]{1,4}|$HEX_RANGE)"
    private const val IPV4_COMPONENT = "(?:[01]?[0-9]{1,2}|2[0-4][0-9]|25[0-5]|$NUMERIC_RANGE)"
    private const val LABEL = """(?:[\w]|$ALNUM_RANGE)(?:[\w_-]|$ALNUM_RANGE)*(?<![_-])"""

    private val BRACKETED_HOSTPORT: Pattern = Pattern.compile("""^\[(.+)\]:([0-9]+)$""")
    private val HOSTPORT: Pattern = Pattern.compile("""^((?:[^:\[\]]|\[[^\]]*\])*):([0-9]+)$""")
    private val IPV4: Pattern = Pattern.compile("""^(?:$IPV4_COMPONENT\.){3}$IPV4_COMPONENT$""", Pattern.CASE_INSENSITIVE)
    private val IPV6: Pattern = run {
        val c = IPV6_COMPONENT
        val alternatives = listOf(
            "(?:$c:){7}$c",
            "(?:$c:){1,6}:",
            "(?:$c:)(?::$c){1,6}",
            "(?:$c:){2}(?::$c){1,5}",
            "(?:$c:){3}(?::$c){1,4}",
            "(?:$c:){4}(?::$c){1,3}",
            "(?:$c:){5}(?::$c){1,2}",
            "(?:$c:){6}(?::$c)",
            ":(?::$c){1,6}",
            "$c?::",
            """(?:0:){6}(?:$c\.){3}$c""",
            """::(?:ffff:)?(?:$c\.){3}$c""",
            """(?:0:){5}ffff:(?:$c\.){3}$c""",
        )
        Pattern.compile("^(" + alternatives.joinToString("|") + ")$", Pattern.CASE_INSENSITIVE)
    }
    private val HOSTNAME: Pattern = Pattern.compile(
        """^$LABEL(?:\.$LABEL)*$""",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS,
    )

    /** True when [text] is an IPv4 or IPv6 address (ranges allowed), as `Host.get_magic_vars` checks. */
    fun isIpAddress(text: String): Boolean = IPV4.matcher(text).find() || IPV6.matcher(text).find()

    /**
     * Returns the host and optional port, or null where ansible-core raises "Not a valid network hostname"
     * (or finds a range while [allowRanges] is false).
     */
    fun parse(address: String, allowRanges: Boolean = false): Parsed? {
        var rest = address
        var port: BigInteger? = null
        // Both forms are tried in turn, exactly like the `for ... continue` loop in parse_address.
        for (pattern in listOf(BRACKETED_HOSTPORT, HOSTPORT)) {
            val m = pattern.matcher(rest)
            if (m.find()) {
                rest = m.group(1)
                port = BigInteger(m.group(2))
            }
        }
        val valid = IPV4.matcher(rest).find() || IPV6.matcher(rest).find() || HOSTNAME.matcher(rest).find()
        if (!valid) return null
        if (!allowRanges && '[' in rest) return null
        return Parsed(rest, port)
    }
}

/** Thrown where ansible-core's range expansion raises an error; the message follows Ansible's wording. */
class HostRangeException(message: String) : IllegalArgumentException(message)

/**
 * Port of `detect_range` / `expand_hostname_range` (ansible.plugins.inventory) and of
 * `BaseFileInventoryPlugin._expand_hostpattern`, which the YAML inventory plugin applies to every host key.
 *
 * Supports numeric (`web[01:10]`, zero padding from the begin value), alphabetic (`db-[a:c]`) and stepped
 * (`[1:10:3]`) ranges, several ranges in one name, and a `:port` suffix.
 */
object HostRanges {
    private const val ASCII_LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /**
     * Upper bound on the hosts one pattern may expand to. ansible-core has none; the IDE refuses to materialise
     * absurd ranges (e.g. `web[1:99999999]`) and reports them instead.
     */
    const val MAX_EXPANSION = 10_000

    /** Result of `_expand_hostpattern`: the host names and the optional port that applies to all of them. */
    data class Expansion(val hosts: List<String>, val port: BigInteger?)

    fun detectRange(line: String): Boolean = '[' in line

    /** `_expand_hostpattern`: strips a valid `:port`, then expands ranges. Throws [HostRangeException]. */
    fun expandHostPattern(hostPattern: String): Expansion {
        val parsed = HostAddress.parse(hostPattern, allowRanges = true)
        val pattern = parsed?.host ?: hostPattern
        val port = parsed?.port
        val hosts = if (detectRange(pattern)) expand(pattern) else listOf(pattern)
        return Expansion(hosts, port)
    }

    /** `expand_hostname_range`. Throws [HostRangeException] where ansible-core raises. */
    fun expand(line: String): List<String> {
        val out = ArrayList<String>()
        expandInto(line, out)
        return out
    }

    private fun expandInto(line: String, out: MutableList<String>) {
        if (line.isEmpty()) return
        // (head, nrange, tail) = line.replace('[', '|', 1).replace(']', '|', 1).split('|')
        val parts = line.replaceFirst("[", "|").replaceFirst("]", "|").split('|')
        if (parts.size != 3) {
            throw HostRangeException("host range must be of the form head[begin:end]tail, got '$line'")
        }
        val (head, nrange, tail) = parts
        val bounds = nrange.split(':')
        if (bounds.size != 2 && bounds.size != 3) throw HostRangeException("host range must be begin:end or begin:end:step")
        val beg = bounds[0].ifEmpty { "0" }
        val end = bounds[1]
        val stepText = if (bounds.size == 3) bounds[2] else "1"
        if (end.isEmpty()) throw HostRangeException("host range must specify end value")
        val width = if (beg[0] == '0' && beg.length > 1) {
            if (beg.length != end.length) throw HostRangeException("host range must specify equal-length begin and end formats")
            beg.length
        } else {
            null
        }

        val seq: List<String> = alphabeticSequence(beg, end, stepText) ?: numericSequence(beg, end, stepText)
        for (item in seq) {
            val name = head + (if (width != null) Py.zfill(item, width) else item) + tail
            if (detectRange(name)) expandInto(name, out) else out += name
            if (out.size > MAX_EXPANSION) throw HostRangeException("host range expands to more than $MAX_EXPANSION hosts")
        }
    }

    /**
     * The `try` branch: `string.ascii_letters.index(beg)` (a substring search, as in Python) and a stepped slice.
     * Returns null where Python raises ValueError and falls back to the numeric branch.
     */
    private fun alphabeticSequence(beg: String, end: String, stepText: String): List<String>? {
        val iBeg = ASCII_LETTERS.indexOf(beg)
        if (iBeg < 0) return null
        val iEnd = ASCII_LETTERS.indexOf(end)
        if (iEnd < 0) return null
        if (iBeg > iEnd) throw HostRangeException("host range must have begin <= end")
        val step = Py.parseInt(stepText) ?: return null
        if (step.signum() == 0) return null
        if (step.signum() < 0) return emptyList() // ascii_letters[i:j:-n] with i <= j is empty
        val stepInt = step.min(BigInteger.valueOf(ASCII_LETTERS.length.toLong())).toInt()
        return (iBeg..iEnd step stepInt).map { ASCII_LETTERS[it].toString() }
    }

    /** The `except ValueError` branch: `range(int(beg), int(end) + 1, int(step))`. */
    private fun numericSequence(beg: String, end: String, stepText: String): List<String> {
        val b = Py.parseInt(beg) ?: throw HostRangeException("invalid literal for int() with base 10: '$beg'")
        val e = Py.parseInt(end) ?: throw HostRangeException("invalid literal for int() with base 10: '$end'")
        val step = Py.parseInt(stepText) ?: throw HostRangeException("invalid literal for int() with base 10: '$stepText'")
        if (step.signum() == 0) throw HostRangeException("range() arg 3 must not be zero")
        val stop = e + BigInteger.ONE
        val out = ArrayList<String>()
        var x = b
        while (if (step.signum() > 0) x < stop else x > stop) {
            out += x.toString()
            if (out.size > MAX_EXPANSION) throw HostRangeException("host range expands to more than $MAX_EXPANSION hosts")
            x += step
        }
        return out
    }
}
