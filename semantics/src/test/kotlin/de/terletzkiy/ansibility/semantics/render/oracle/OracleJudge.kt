package de.terletzkiy.ansibility.semantics.render.oracle

/** How one outcome compares with ansible-core. */
enum class VerdictKind {
    /** Proven and equal to ansible-core's result (members only, when the outcome marks the order). */
    MATCH,

    /** Text with placeholders whose known parts all occur in ansible-core's output, in order. */
    PARTIAL,

    /** The renderer abstained. */
    PLACEHOLDER,

    /** The runner returned nothing for the check. */
    NOT_ATTEMPTED,

    /** A proven result that ansible-core contradicts: always a bug in the port, never a reason to edit a golden. */
    MISMATCH,
}

/** The verdict on one check; [detail] explains a mismatch. */
class Verdict(val check: OracleCheck, val kind: VerdictKind, val outcome: Outcome?, val detail: String? = null) {
    override fun toString(): String = "${check.id}: $kind" + (detail?.let { " — $it" } ?: "")
}

/** The verdicts of one case on one core, and the coverage they add up to. */
class OracleReport(val case: OracleCase, val core: OracleCore, val verdicts: List<Verdict>, val unknownIds: Set<String>) {
    fun count(kind: VerdictKind): Int = verdicts.count { it.kind == kind }

    val matched: Int get() = count(VerdictKind.MATCH)
    val mismatches: List<Verdict> get() = verdicts.filter { it.kind == VerdictKind.MISMATCH }

    /** One line: matched, partial, placeholder, not attempted and mismatched checks out of all checks. */
    fun summary(): String =
        "${case.id} × ${core.label}: ${matched}/${verdicts.size} matched, ${count(VerdictKind.PARTIAL)} partial, " +
            "${count(VerdictKind.PLACEHOLDER)} placeholders, ${count(VerdictKind.NOT_ATTEMPTED)} not attempted, " +
            "${mismatches.size} mismatches"

    /** The first [limit] mismatches with what was expected and what came out. */
    fun mismatchReport(limit: Int = 25): String = mismatches.take(limit).joinToString("\n") {
        "  ${it.check.id}: ${it.detail}\n    expected ${it.check.expected}\n    got      ${it.outcome}"
    } + if (mismatches.size > limit) "\n  … ${mismatches.size - limit} more" else ""
}

/**
 * The soundness property of the render oracle (plan, R11 testing item 1): every known outcome equals ansible-core's
 * result; a placeholder is never compared; a partial text is compared only at its known parts. Coverage (the
 * matched checks) is reported and may only grow (`min_matched` in `supported.json`).
 */
object OracleJudge {

    /** Judges every check of [case] on [core] against [outcomes] (by check id). */
    fun judge(case: OracleCase, core: OracleCore, checks: List<OracleCheck>, outcomes: Map<String, Outcome>): OracleReport {
        val ids = checks.mapTo(HashSet()) { it.id }
        return OracleReport(case, core, checks.map { verdict(it, outcomes[it.id]) }, outcomes.keys - ids)
    }

    /** The verdict on [outcome] for [check]; null means not attempted. */
    fun verdict(check: OracleCheck, outcome: Outcome?): Verdict {
        fun v(kind: VerdictKind, detail: String? = null) = Verdict(check, kind, outcome, detail)
        fun mismatch(detail: String) = v(VerdictKind.MISMATCH, detail)
        val expected = check.expected
        if (outcome == null) return v(VerdictKind.NOT_ATTEMPTED)
        if (outcome is Outcome.Placeholder) return v(VerdictKind.PLACEHOLDER)
        if (expected is Expected.Unprovable) return mismatch("no core proves this result (${expected.reason}); the renderer must abstain")
        return when (outcome) {
            is Outcome.Known -> known(check, outcome)?.let(::mismatch) ?: v(VerdictKind.MATCH)
            is Outcome.Partial -> when (val text = expectedText(expected)) {
                null -> mismatch("a partial text where ansible-core gives ${describe(expected)}")
                else -> if (matchesWithHoles(outcome.parts, text)) v(VerdictKind.PARTIAL) else mismatch("a known part does not occur in ansible-core's output")
            }
            is Outcome.Failed -> when {
                expected !is Expected.Failure -> mismatch("ansible-core does not fail here: it gives ${describe(expected)}")
                outcome.message == null || messageMatches(outcome.message, expected.message) -> v(VerdictKind.MATCH)
                else -> mismatch("ansible-core fails with another message")
            }
            Outcome.Succeeded -> if (expected == Expected.Succeeded) v(VerdictKind.MATCH) else mismatch("ansible-core gives ${describe(expected)}")
            Outcome.Skipped -> if (expected == Expected.Skipped) v(VerdictKind.MATCH) else mismatch("ansible-core gives ${describe(expected)}")
            is Outcome.Placeholder -> v(VerdictKind.PLACEHOLDER)
        }
    }

    /** Null when the known outcome agrees with the expected result, otherwise why not. */
    private fun known(check: OracleCheck, outcome: Outcome.Known): String? {
        val expected = check.expected
        if (expected is Expected.Failure || expected == Expected.Succeeded || expected == Expected.Skipped) {
            return "a value where ansible-core gives ${describe(expected)}"
        }
        val order = outcome.order
        if (order != null) {
            val members = when (expected) {
                is Expected.Value -> expected.value as? List<*>
                else -> expectedText(expected)?.let(OracleValues::pythonListMembers)
            } ?: return "an order mark on a result that is not a list: ${describe(expected)}"
            return if (OracleValues.sameMembers(order.members, members)) null else "other members than ansible-core's"
        }
        if (check.orderVaries) return "the order depends on the hash seed, so a known result must carry an order mark (D79)"
        return when (expected) {
            is Expected.Bytes -> when (val v = outcome.value) {
                is ByteArray -> if (v.contentEquals(expected.bytes)) null else "other bytes"
                is CharSequence -> if (v.toString().toByteArray(Charsets.UTF_8).contentEquals(expected.bytes)) null else "other text"
                else -> "a file check needs text or bytes, got ${v?.javaClass?.simpleName}"
            }
            is Expected.Text -> when (val v = outcome.value) {
                is CharSequence -> if (v.toString() == expected.text) null else "other text"
                else -> "a text check needs text, got ${v?.javaClass?.simpleName}"
            }
            is Expected.Value -> if (OracleValues.jsonEquals(outcome.value, expected.value)) null else "another value"
            else -> "unexpected ${describe(expected)}"
        }
    }

    private fun expectedText(expected: Expected): String? = when (expected) {
        is Expected.Text -> expected.text
        is Expected.Bytes -> String(expected.bytes, Charsets.UTF_8)
        else -> null
    }

    private fun describe(expected: Expected): String = when (expected) {
        is Expected.Failure -> "the error \"${expected.message}\""
        Expected.Succeeded -> "success"
        Expected.Skipped -> "a skip"
        else -> expected.toString()
    }

    /**
     * True when [message] (whitespace collapsed) occurs in ansible-core's [expected] message, or, where the generator
     * cut the message at [OracleChecks.MESSAGE_LIMIT] characters, starts with it.
     */
    fun messageMatches(message: String, expected: String): Boolean {
        val m = message.replace(Regex("\\s+"), " ").trim()
        return m.isNotEmpty() && (m in expected || (expected.length >= OracleChecks.MESSAGE_LIMIT && m.startsWith(expected)))
    }

    /** True when [text] is the concatenation of the [parts] with every [Part.Hole] replaced by some text. */
    fun matchesWithHoles(parts: List<Part>, text: String): Boolean {
        val segments = ArrayList<String>()
        var current = StringBuilder()
        var holes = 0
        for (part in parts) {
            when (part) {
                is Part.Text -> current.append(part.text)
                Part.Hole -> {
                    segments += current.toString()
                    current = StringBuilder()
                    holes++
                }
            }
        }
        segments += current.toString()
        if (holes == 0) return segments.single() == text
        val first = segments.first()
        val last = segments.last()
        if (!text.startsWith(first) || !text.endsWith(last) || first.length + last.length > text.length) return false
        var pos = first.length
        val end = text.length - last.length
        for (segment in segments.subList(1, segments.size - 1)) {
            if (segment.isEmpty()) continue
            val at = text.indexOf(segment, pos)
            if (at < 0 || at + segment.length > end) return false
            pos = at + segment.length
        }
        return true
    }
}
