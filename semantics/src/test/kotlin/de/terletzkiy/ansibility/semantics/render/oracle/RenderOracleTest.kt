package de.terletzkiy.ansibility.semantics.render.oracle

import de.terletzkiy.ansibility.semantics.CoreVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The render oracle (plan R11, testing item 1): every case × core of `render-oracle/` that `supported.json` lists is
 * rendered by its runner and judged by [OracleJudge]'s soundness property; every other case × core is listed as
 * unsupported. A mismatch is a bug in the port, never a reason to edit a golden.
 */
class RenderOracleTest {

    @Test
    fun `corpus holds the 17 cases measured on both primary cores`() {
        assertEquals(CASES, corpus.cases.map { it.id })
        for (case in corpus.cases) {
            assertEquals(PRIMARY, case.cores.filter { it.primary }.associate { it.label to it.version }, "$case primary cores")
            assertTrue(case.inputFiles().isNotEmpty(), "$case has no input")
            assertTrue(case.title.isNotBlank() && case.pins.isNotEmpty(), "$case has no title or pins")
            for (core in case.cores) assertTrue(case.checks(core).isNotEmpty(), "$case has no checks on $core")
        }
    }

    @Test
    fun `extra cores of spike S-R1 are overlays of 2_19 and 2_20`() {
        for (case in corpus.cases) {
            val extra = case.cores.filterNot { it.primary }
            assertTrue(extra.all { it.label == "2.19" || it.label == "2.20" }, "$case extra cores $extra")
        }
    }

    @Test
    fun `supported json names known cases, cores and runners`() {
        val problems = support.problems(corpus)
        assertTrue(problems.isEmpty(), problems.joinToString("\n", "supported.json:\n"))
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("caseCores")
    fun `renders like ansible-core`(caseId: String, coreLabel: String) {
        val case = corpus.case(caseId)
        val core = case.core(coreLabel)
        val checks = case.checks(core)
        val entry = support.entry(caseId, coreLabel)
        if (entry == null) {
            println("$caseId × $coreLabel: unsupported (${checks.size} checks)")
            return
        }
        val report = OracleJudge.judge(case, core, checks, entry.newRunner().run(case, core, checks))
        println(report.summary())
        assertTrue(report.unknownIds.isEmpty(), "$caseId × $coreLabel: outcomes for unknown checks ${report.unknownIds.take(10)}")
        val limit = System.getProperty("oracle.mismatches")?.toIntOrNull() ?: 25
        assertTrue(report.mismatches.isEmpty(), "$caseId × $coreLabel contradicts ansible-core:\n${report.mismatchReport(limit)}")
        val floor = entry.minMatched[coreLabel] ?: 0
        assertTrue(report.matched >= floor, "$caseId × $coreLabel: coverage fell to ${report.matched}, supported.json requires $floor")
    }

    @Test
    fun `unsupported cases are listed`() {
        val all = corpus.cases.flatMap { case -> case.cores.map { case.id to it.label } }
        val unsupported = all.filter { (case, core) -> support.entry(case, core) == null }
        assertEquals(all.size, unsupported.size + support.entries.sumOf { it.cores.size })
        println(
            "render oracle: ${all.size - unsupported.size} of ${all.size} case × core supported; unsupported:\n" +
                unsupported.groupBy({ it.first }, { it.second }).entries.joinToString("\n") { (case, cores) -> "  $case: ${cores.joinToString()}" },
        )
    }

    companion object {
        private val corpus by lazy { OracleCorpus.load() }
        private val support by lazy { OracleSupport.load() }

        private val PRIMARY = mapOf("2.21" to CoreVersion(2, 21, 4), "2.18" to CoreVersion(2, 18, 8))

        private val CASES = listOf(
            "01_user_example_fileglob",
            "02_template_env_defaults",
            "03_trailing_newlines",
            "04_value_rendering",
            "04n_value_rendering_jinja2_native",
            "05_filters_tests_operators",
            "06_filters_more",
            "07_undefined",
            "08_control_structures",
            "09_backslash_escaping",
            "10_magic_vars",
            "11_ansible_managed",
            "11b_ansible_managed_cfg",
            "12_lookup_search_paths",
            "13_loop_items",
            "14_set_filter_order",
            "15_regex_python_vs_java",
        )

        @JvmStatic
        fun caseCores(): List<Arguments> =
            corpus.cases.flatMap { case -> case.cores.map { Arguments.of(case.id, it.label) } }
    }
}
