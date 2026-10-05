package de.terletzkiy.ansibility.semantics.render.oracle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The soundness property of [OracleJudge] and the value rules of [OracleValues]. */
class OracleJudgeTest {

    @Test
    fun `replaying ansible-core's results matches every check of every case and core`() {
        for (case in corpus.cases) {
            for (core in case.cores) {
                val checks = case.checks(core)
                val report = OracleJudge.judge(case, core, checks, EchoRunner.run(case, core, checks))
                assertTrue(report.mismatches.isEmpty(), "${report.summary()}\n${report.mismatchReport()}")
                val abstained = checks.count { it.expected is Expected.Unprovable || EchoRunner.unorderedText(it) }
                assertEquals(checks.size - abstained, report.matched, report.summary())
                assertEquals(abstained, report.count(VerdictKind.PLACEHOLDER), report.summary())
            }
        }
    }

    @Test
    fun `placeholders and missing outcomes are never compared`() {
        val check = check(Expected.Text("x"))
        assertEquals(VerdictKind.PLACEHOLDER, OracleJudge.verdict(check, Outcome.Placeholder("runtime")).kind)
        assertEquals(VerdictKind.NOT_ATTEMPTED, OracleJudge.verdict(check, null).kind)
    }

    @Test
    fun `a known value that differs is a mismatch`() {
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Text("a")), Outcome.Known("b")).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Value(1L)), Outcome.Known(1.0)).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Value(1L)), Outcome.Known(true)).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Bytes("a\n".toByteArray())), Outcome.Known("a")).kind)
        assertEquals(VerdictKind.MATCH, OracleJudge.verdict(check(Expected.Bytes("é\n".toByteArray())), Outcome.Known("é\n")).kind)
        assertEquals(VerdictKind.MATCH, OracleJudge.verdict(check(Expected.Value(mapOf("b" to 2L, "a" to 1L))), Outcome.Known(mapOf("a" to 1, "b" to 2))).kind)
    }

    @Test
    fun `failures, successes and skips must agree with ansible-core`() {
        val failure = check(Expected.Failure("Task failed: 'x' is undefined. 'x' is undefined"))
        assertEquals(VerdictKind.MATCH, OracleJudge.verdict(failure, Outcome.Failed("'x' is  undefined")).kind)
        assertEquals(VerdictKind.MATCH, OracleJudge.verdict(failure, Outcome.Failed()).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(failure, Outcome.Failed("'y' is undefined")).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(failure, Outcome.Known("x")).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Text("x")), Outcome.Failed()).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Skipped), Outcome.Succeeded).kind)
        assertEquals(VerdictKind.MATCH, OracleJudge.verdict(check(Expected.Skipped), Outcome.Skipped).kind)
    }

    @Test
    fun `a message cut by the generator matches by prefix`() {
        val cut = "e".repeat(OracleChecks.MESSAGE_LIMIT)
        assertTrue(OracleJudge.messageMatches(cut + " and the rest", cut))
        assertFalse(OracleJudge.messageMatches("other", cut))
        assertFalse(OracleJudge.messageMatches("  ", "anything"))
    }

    @Test
    fun `an unprovable result requires the renderer to abstain`() {
        val check = check(Expected.Unprovable("controller Python"))
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check, Outcome.Known("X")).kind)
        assertEquals(VerdictKind.PLACEHOLDER, OracleJudge.verdict(check, Outcome.Placeholder("controller Python")).kind)
    }

    @Test
    fun `partial texts are compared at their known parts only`() {
        val expected = check(Expected.Text("server web1 port 8080;\n"))
        fun partial(vararg parts: Part) = OracleJudge.verdict(expected, Outcome.Partial(parts.toList())).kind
        assertEquals(VerdictKind.PARTIAL, partial(Part.Text("server "), Part.Hole, Part.Text(" port "), Part.Hole, Part.Text(";\n")))
        assertEquals(VerdictKind.PARTIAL, partial(Part.Hole))
        assertEquals(VerdictKind.MISMATCH, partial(Part.Text("server "), Part.Hole, Part.Text(" host ")))
        assertEquals(VerdictKind.MISMATCH, partial(Part.Text("client "), Part.Hole))
        assertEquals(VerdictKind.MISMATCH, partial(Part.Text("server web1 port 8080;\n"), Part.Hole, Part.Text(";\n")))
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Value(1L)), Outcome.Partial(listOf(Part.Hole))).kind)
    }

    @Test
    fun `an order mark compares members only, and a seed-dependent result needs one`() {
        val varies = OracleCheck("c", CheckSubject.OutputFile("c"), Expected.Value(listOf("b", "a", "c")), orderVaries = true)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(varies, Outcome.Known(listOf("b", "a", "c"))).kind)
        assertEquals(VerdictKind.MATCH, OracleJudge.verdict(varies, Outcome.Known(listOf("a", "b", "c"), OrderMark(listOf("a", "b", "c"), "hash seed"))).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(varies, Outcome.Known(null, OrderMark(listOf("a", "b"), "hash seed"))).kind)
        val printed = OracleCheck("t", CheckSubject.OutputFile("t"), Expected.Text("['b', 'a']"), orderVaries = true)
        assertEquals(VerdictKind.MATCH, OracleJudge.verdict(printed, Outcome.Known("['a', 'b']", OrderMark(listOf("a", "b"), "hash seed"))).kind)
        assertEquals(VerdictKind.MISMATCH, OracleJudge.verdict(check(Expected.Value(1L)), Outcome.Known(1L, OrderMark(listOf(1L), "x"))).kind)
    }

    @Test
    fun `python list reprs are read as members`() {
        assertEquals(listOf("b", "a"), OracleValues.pythonListMembers("['b', 'a']"))
        assertEquals(listOf(30L, 1L, 20L), OracleValues.pythonListMembers("[30, 1, 20]"))
        assertEquals(listOf(null, true, 1.5, "it's", "\n"), OracleValues.pythonListMembers("""[None, True, 1.5, "it's", '\n']"""))
        assertEquals(listOf(listOf(1L), emptyList<Any?>()), OracleValues.pythonListMembers("[[1], []]"))
        assertNull(OracleValues.pythonListMembers("{'a': 1}"))
        assertNull(OracleValues.pythonListMembers("[1, 2] tail"))
    }

    @Test
    fun `json values follow Python's distinctions`() {
        assertTrue(OracleValues.jsonEquals(1, 1L))
        assertTrue(OracleValues.jsonEquals(java.math.BigInteger.TEN, 10L))
        assertFalse(OracleValues.jsonEquals(1L, 1.0))
        assertFalse(OracleValues.jsonEquals(true, 1L))
        assertTrue(OracleValues.jsonEquals(listOf(1L, "a"), listOf(1, StringBuilder("a"))))
        assertFalse(OracleValues.jsonEquals(listOf(1L, 2L), listOf(2L, 1L)))
        assertTrue(OracleValues.sameMembers(listOf(1L, 2L, 2L), listOf(2L, 1L, 2L)))
        assertFalse(OracleValues.sameMembers(listOf(1L, 1L, 2L), listOf(2L, 1L, 2L)))
    }

    private fun check(expected: Expected) = OracleCheck("c", CheckSubject.OutputFile("c"), expected)

    /** Answers every check with ansible-core's own result: the judge must accept all of it. */
    private object EchoRunner : OracleRunner {
        override fun run(case: OracleCase, core: OracleCore, checks: List<OracleCheck>): Map<String, Outcome> =
            checks.associate { check ->
                check.id to when (val e = check.expected) {
                    is Expected.Bytes -> Outcome.Known(e.bytes)
                    is Expected.Text -> known(check, e.text, e.text.let(OracleValues::pythonListMembers))
                    is Expected.Value -> known(check, e.value, e.value as? List<*>)
                    is Expected.Failure -> Outcome.Failed(e.message)
                    Expected.Succeeded -> Outcome.Succeeded
                    Expected.Skipped -> Outcome.Skipped
                    is Expected.Unprovable -> Outcome.Placeholder(e.reason)
                }
            }

        /** An order-varying result that is no list (a set printed as text): no order mark can describe it. */
        fun unorderedText(check: OracleCheck): Boolean = check.orderVaries && when (val e = check.expected) {
            is Expected.Text -> OracleValues.pythonListMembers(e.text) == null
            is Expected.Value -> e.value !is List<*>
            else -> false
        }

        private fun known(check: OracleCheck, value: Any?, members: List<*>?): Outcome = when {
            !check.orderVaries -> Outcome.Known(value)
            members == null -> Outcome.Placeholder("order varies and the result is no list")
            else -> Outcome.Known(value, OrderMark(members.reversed(), "hash seed"))
        }
    }

    private companion object {
        val corpus by lazy { OracleCorpus.load() }
    }
}
