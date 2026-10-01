package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.vfs.VfsUtil
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * ANS-V003 guard forms and exclusion classes (plan amendment R7/R8, F8.12 (a) and (c); acceptance 5) on the synthetic
 * [ProbeRole]: every template line states what it expects, and the YAML task cases are marked `# M-…` (reported) or
 * `# C-…` (clean).
 */
class GuardFormsTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        for ((path, text) in ProbeRole.FILES) add(path, text)
    }

    private fun reportedByLine(path: String): Map<Int, Set<String>> =
        analyse(path).groupBy({ lineOf(path, it.use.nameRange.startOffset) }, { it.name }).mapValues { it.value.toSet() }

    private fun assertExpectations(path: String) {
        val expected = ProbeRole.expectations(VfsUtil.loadText(vf(path)))
        val actual = reportedByLine(path)
        val text = VfsUtil.loadText(vf(path)).lines()
        val wrong = expected.mapNotNull { (line, names) ->
            val got = actual[line].orEmpty()
            if (got == names) null else "line $line `${text[line - 1]}`: expected $names, got $got"
        }
        assertEmpty(wrong.joinToString("\n"), wrong)
        assertEmpty("findings on lines without expectations", actual.keys - expected.keys)
    }

    fun testTemplateGuardFormsAndExclusions() {
        assertExpectations(ProbeRole.TEMPLATE)
    }

    fun testLoopVariableOfTheRenderingTask() {
        assertExpectations(ProbeRole.LOOPED)
    }

    fun testKindsAndWitnessHosts() {
        val findings = analyse(ProbeRole.TEMPLATE).associateBy { "${lineOf(ProbeRole.TEMPLATE, it.use.nameRange.startOffset)}:${it.name}" }
        val first = findings.getValue("1:probe_opt")
        assertEquals(UndefinedKind.MISSING, first.kind)
        assertEquals(ProbeRole.ALL_HOSTS, first.missing.map { "${it.environment}/${it.host}" })
        assertEquals(listOf("probe"), first.roles.map { it.ref.name })
        val everywhere = findings.values.single { it.name == "probe_env" }
        assertEquals(UndefinedKind.EVERYWHERE_SET, everywhere.kind)
        assertTrue(everywhere.message, "works only because every current environment sets it" in everywhere.message)
        val partial = findings.values.single { it.name == "probe_partial" }
        assertEquals(UndefinedKind.MISSING, partial.kind)
        assertEquals(listOf("ops/ops-ops1", "test/test-test1"), partial.missing.map { "${it.environment}/${it.host}" })
        val undeclared = findings.values.single { it.name == "probe_undeclared" }
        assertEquals("an undeclared name only has the ERROR row", UndefinedKind.MISSING, undeclared.kind)
        assertTrue(undeclared.message, "nothing gives it a default" !in undeclared.message && "role probe has no default" in undeclared.message)
    }

    fun testMandatoryKeepsOnlyTheErrorRow() {
        val mandatory = analyse(ProbeRole.TEMPLATE).single { it.use.raw.mandatory }
        assertEquals("the unconditional one; the one inside an if is exempt", UndefinedKind.MISSING, mandatory.kind)
        replace(ProbeRole.TEMPLATE, "{{ probe_opt | mandatory }}", "{{ probe_env | mandatory }}")
        assertTrue("every environment sets probe_env: the mandatory use is exempt", analyse(ProbeRole.TEMPLATE).none { it.use.raw.mandatory })
    }

    /** The `# M-…`/`# W-…` marker of each finding of [path] with its kind; every `# C-…` line stays clean. */
    private fun taskMarkers(path: String): Map<String, UndefinedKind> {
        val lines = VfsUtil.loadText(vf(path)).lines()
        return analyse(path).associate { lines[lineOf(path, it.use.nameRange.startOffset) - 1].substringAfter("# ", "none") to it.kind }
    }

    fun testYamlTaskUses() {
        val markers = taskMarkers(ProbeRole.OPEN_TASKS)
        val expected = VfsUtil.loadText(vf(ProbeRole.OPEN_TASKS)).lines().mapNotNull { Regex("# ([MW]-[a-z-]+)").find(it)?.groupValues?.get(1) }
        assertSameElements(markers.keys, expected)
        for ((marker, kind) in markers) {
            assertEquals(marker, if (marker.startsWith("M-")) UndefinedKind.MISSING else UndefinedKind.MISSING_WHEN_RUN, kind)
        }
        assertEmpty("the include chain's when guards guarded.yml", analyse(ProbeRole.GUARDED_TASKS))
        assertEmpty("role tasks guard their own include conditions", analyse("${ProbeRole.ROLE}/tasks/main.yml"))
    }

    fun testHandlersRunOnlyWhenNotified() {
        assertEquals(mapOf("W-handler" to UndefinedKind.MISSING_WHEN_RUN), taskMarkers(ProbeRole.HANDLERS))
    }

    fun testRoleVarsValuesAreTemplatedLazily() {
        assertEquals(mapOf("W-lazy" to UndefinedKind.MISSING_WHEN_RUN), taskMarkers(ProbeRole.VARS))
    }

    fun testRolesEntryConditionGuardsAndGates() {
        assertExpectations(ProbeRole.GATED_TEMPLATE)
        val other = analyse(ProbeRole.GATED_TEMPLATE).single()
        assertEquals(UndefinedKind.MISSING_WHEN_RUN, other.kind)
        assertEquals(ProbeRole.ALL_HOSTS, other.missing.map { "${it.environment}/${it.host}" })
        assertTrue(other.message, "so rendering fails there whenever it runs" in other.message)
    }

    fun testConditionalTemplateUsesAreNoCertainFailures() {
        val text = VfsUtil.loadText(vf(ProbeRole.TEMPLATE)).lines()
        val byLine = analyse(ProbeRole.TEMPLATE).groupBy { text[lineOf(ProbeRole.TEMPLATE, it.use.nameRange.startOffset) - 1] }
        fun kinds(prefix: String) = byLine.entries.single { it.key.startsWith(prefix) }.value.map { it.kind }.toSet()
        assertEquals(setOf(UndefinedKind.MISSING), kinds("{{ probe_opt }}{#"))
        assertEquals("the body of another condition", setOf(UndefinedKind.MISSING_WHEN_RUN), kinds("{% if probe_task_var %}{{ probe_opt }}"))
        assertEquals("a macro body", setOf(UndefinedKind.MISSING_WHEN_RUN), kinds("{% macro probe_macro()"))
        assertEquals("a for body", setOf(UndefinedKind.MISSING), kinds("{% for i in probe_opt %}"))
        assertEquals(
            "the failing condition is certain, its body runs only when it holds",
            setOf(UndefinedKind.MISSING, UndefinedKind.MISSING_WHEN_RUN),
            kinds("{% if probe_opt | length > 0 %}"),
        )
    }

    fun testPlaybookTaskHasOnlyTheErrorRow() {
        val findings = analyse(ProbeRole.PLAYBOOK)
        assertEquals(listOf("probe_play_only"), findings.map { it.name })
        assertEquals(UndefinedKind.MISSING, findings.single().kind)
        assertEquals(ProbeRole.ALL_HOSTS, findings.single().missing.map { "${it.environment}/${it.host}" })
        assertTrue(findings.single().message, "nothing gives it a default" in findings.single().message)
    }

    fun testVersionDependentRules() {
        TargetVersionDetector.getInstance(project).overrideFor = { CoreVersion(2, 18, 8) }
        assertEquals("2.18: type tests tolerate undefined, filters before default fail", listOf(2, 3, 4), lines(ProbeRole.VERSIONS))
        assertEquals("2.18: the int filter fails in the condition, so its body is reported too", 2, analyse(ProbeRole.VERSIONS).count { lineOf(ProbeRole.VERSIONS, it.use.nameRange.startOffset) == 4 })
        TargetVersionDetector.getInstance(project).overrideFor = { CoreVersion(2, 21, 4) }
        assertEquals("2.19+: type tests fail, undefined passes filters (also in conditions)", listOf(1), lines(ProbeRole.VERSIONS))
    }

    fun testRequiredSpecVariableIsLeftToP003() {
        replace(ProbeRole.TEMPLATE, "{{ probe_req }}{# expect: #}", "{{ probe_req }}{% if probe_req %}{% endif %}{# expect: #}")
        assertTrue(analyse(ProbeRole.TEMPLATE).none { it.name == "probe_req" })
    }

    private fun lines(path: String): List<Int> = analyse(path).map { lineOf(path, it.use.nameRange.startOffset) }.distinct()
}
