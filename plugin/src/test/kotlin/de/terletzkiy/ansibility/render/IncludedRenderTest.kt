package de.terletzkiy.ansibility.render

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.inspections.undefined.UndefinedTestCase
import de.terletzkiy.ansibility.vars.LoopItems as VarLoopItems
import de.terletzkiy.ansibility.render.bind.LoopItems
import de.terletzkiy.ansibility.render.service.PreviewReport
import de.terletzkiy.ansibility.render.service.TemplatePreviewService
import de.terletzkiy.ansibility.render.service.ValueReport
import de.terletzkiy.ansibility.resolve.include.IncludeFixture

/**
 * Rendered values and template previews of files that include tasks run ([IncludeFixture]): the includers' `vars:` and
 * loops bind there, one outcome group per include path labelled by its includer (`rules.yml:8 · item 1 (…)`); an
 * includer loop that cannot be evaluated is a placeholder; an includer that does not set a name keeps the real error.
 */
@RequiresInfraFixture
class IncludedRenderTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        IncludeFixture.FILES.forEach { (path, text) -> add(path, text) }
    }

    private fun value(path: String, text: String, anchor: String = text): ValueReport = runReadActionBlocking {
        val document = FileDocumentManager.getInstance().getDocument(vf(path))!!
        val offset = document.text.indexOf(anchor).also { assertTrue("$anchor not in $path", it >= 0) }
        TemplatePreviewService.getInstance(project).renderValue(vf(path), offset, text, expression = false)!!
    }

    private fun preview(path: String, pick: Int = 0): PreviewReport = runReadActionBlocking {
        val file = vf(path)
        val text = FileDocumentManager.getInstance().getDocument(file)!!.text
        val service = TemplatePreviewService.getInstance(project)
        val first = service.render(file, text, null)
        if (pick == 0) first else service.render(file, text, first.choices[pick].pick)
    }

    private fun texts(report: ValueReport): Set<String> = report.outcomes.map { it.text }.toSet()

    private fun ValueReport.describe(): String = outcomes.joinToString("\n") { "${it.text} | ${it.error} | ${it.who.take(2)}" }

    fun testALoopingIncludeRendersOneOutputPerItemWithoutError() {
        val report = value(IncludeFixture.LOOP_RULESET, "{{ fwl_ruleset.dest }}")
        assertEquals(report.describe(), setOf("/etc/fw/rules.v4", "/etc/fw/rules.v6"), texts(report))
        assertTrue(report.describe(), report.outcomes.all { it.error == null })
        assertEquals(2, report.items)
        val who = report.outcomes.first { it.text == "/etc/fw/rules.v4" }.who
        assertTrue(who.toString(), who.any { it.endsWith("rules.yml:8 · item 1 (/etc/fw/rules.v4)") })
    }

    fun testTheIncludersLoopItemReachesTheIncludedTasksOwnLoop() {
        val report = value(IncludeFixture.LOOP_RULESET, "{{ item.chain }} in {{ fwl_ruleset.dest }}")
        assertEquals(report.describe(), setOf("INPUT in /etc/fw/rules.v4", "INPUT in /etc/fw/rules.v6"), texts(report))
        assertTrue(report.describe(), report.outcomes.all { it.error == null })
    }

    fun testIncludeVarsRenderOneOutputPerInclude() {
        val report = value(IncludeFixture.VARS_RULESET, "{{ fwv_ruleset.dest }}")
        assertEquals(report.describe(), setOf("/etc/fw/rules.v4", "/etc/fw/rules.v6"), texts(report))
        assertTrue(report.describe(), report.outcomes.all { it.error == null })
        assertTrue(report.describe(), report.outcomes.first { it.text == "/etc/fw/rules.v4" }.who.all { it.endsWith("rules.yml:2") })
        assertTrue(report.describe(), report.outcomes.first { it.text == "/etc/fw/rules.v6" }.who.all { it.endsWith("rules.yml:10") })
    }

    fun testTemplatePreviewRendersEveryItemOfTheLoopingInclude() {
        val first = preview(IncludeFixture.LOOP_TEMPLATE)
        assertNull(first.problem)
        assertEquals(first.choices.map { it.label }.toString(), 2, first.choices.size)
        assertTrue(first.choices.first().label, "rules.yml:8 · item 1 (/etc/fw/rules.v4)" in first.choices.first().label)
        assertEquals("# ipv4\n-A INPUT -p tcp --dport 22 -j ACCEPT\nCOMMIT", first.text.trimEnd())
        assertTrue(first.rendered!!.errors.toString(), first.rendered!!.errors.isEmpty())
        assertTrue(first.headline, "rules.yml:8" in first.headline)

        val second = preview(IncludeFixture.LOOP_TEMPLATE, pick = 1)
        assertEquals("# ipv6\n-A INPUT -j DROP\nCOMMIT", second.text.trimEnd())
    }

    fun testTemplatePreviewRendersEveryIncludeWithItsVars() {
        val first = preview(IncludeFixture.VARS_TEMPLATE)
        assertEquals(first.choices.map { it.label }.toString(), 2, first.choices.size)
        assertTrue(first.choices.map { it.label }.toString(), first.choices[0].label.endsWith("rules.yml:2") && first.choices[1].label.endsWith("rules.yml:10"))
        assertEquals("# ipv4\n-A INPUT -p tcp --dport 22 -j ACCEPT\nCOMMIT", first.text.trimEnd())
        assertEquals("# ipv6\n-A INPUT -j DROP\nCOMMIT", preview(IncludeFixture.VARS_TEMPLATE, pick = 1).text.trimEnd())
    }

    fun testIncludeRoleWithTasksFromBindsItsLoopAndVarsInTheEntryFile() {
        val report = value(IncludeFixture.ENTRY_APPLY, "{{ fwe_mode }}:{{ fwe_set.dest }}", anchor = "fwe_set.dest")
        assertEquals(report.describe(), setOf("fast:/etc/fw/entry.v4", "fast:/etc/fw/entry.v6"), texts(report))
        assertTrue(report.describe(), report.outcomes.all { it.error == null })
        assertTrue(report.describe(), report.outcomes.flatMap { it.who }.all { "playbook-fw.yml:" in it })
    }

    fun testAnIncluderThatDoesNotSetTheNameKeepsTheErrorUnderItsLabel() {
        val report = value(IncludeFixture.TWO_PART, "echo mode={{ fwt_mode }}")
        val strict = report.outcomes.single { it.text == "echo mode=strict" }
        assertNull(strict.error)
        assertTrue(strict.who.toString(), strict.who.all { it.endsWith("main.yml:2") })
        val failing = report.outcomes.single { it.error != null }
        assertTrue(failing.error, "'fwt_mode' is undefined" in failing.error!!)
        assertTrue(failing.who.toString(), failing.who.all { it.endsWith("main.yml:7") })
    }

    fun testAnIncluderLoopThatCannotBeEvaluatedIsAPlaceholder() {
        val literal = "  loop:\n" +
            "    - ip_version: ipv4\n      dest: /etc/fw/rules.v4\n      chains: \"{{ fwl_rules + fwl_rules_extra }}\"\n" +
            "    - ip_version: ipv6\n      dest: /etc/fw/rules.v6\n      chains: \"{{ fwl_rules_ipv6 + fwl_rules_extra_ipv6 }}\"\n"
        replace(IncludeFixture.LOOP_RULES, literal, "  loop: \"{{ fwl_unknown_rulesets }}\"\n")
        val report = value(IncludeFixture.LOOP_RULESET, "{{ fwl_ruleset.dest }}")
        assertTrue(report.describe(), report.outcomes.isNotEmpty() && report.outcomes.all { it.error == null && it.placeholders > 0 })
    }

    fun testAnUnknownPartOfALiteralItemIsAPlaceholderAndTheOtherKeysRender() {
        replace(IncludeFixture.LOOP_RULES, "chains: \"{{ fwl_rules + fwl_rules_extra }}\"", "chains: \"{{ fwl_rules + fwl_not_set_anywhere }}\"")
        val dest = value(IncludeFixture.LOOP_RULESET, "{{ fwl_ruleset.dest }}")
        assertEquals(dest.describe(), setOf("/etc/fw/rules.v4", "/etc/fw/rules.v6"), texts(dest))
        val first = preview(IncludeFixture.LOOP_TEMPLATE)
        assertTrue(first.rendered!!.errors.toString(), first.rendered!!.errors.isEmpty())
        assertFalse("an unknown part is a placeholder", first.complete)
    }

    fun testAMoleculeIncluderBindsNothingForAProductionFile() {
        val report = value(IncludeFixture.MOL_APPLY, "echo {{ fwm_flag }}")
        assertTrue(report.describe(), report.outcomes.all { it.error?.contains("'fwm_flag' is undefined") == true })
        assertFalse(report.describe(), report.outcomes.flatMap { it.who }.any { "converge.yml" in it })
    }

    /** A role entry file a play also applies directly: the direct run keeps its error under its own label, the include renders. */
    fun testADirectRunKeepsItsErrorBesideTheInclude() {
        val report = value(IncludeFixture.DIR_MAIN, "echo {{ fwd_mode }}")
        val failing = report.outcomes.filter { it.error != null }
        assertTrue(report.describe(), failing.isNotEmpty() && failing.all { "'fwd_mode' is undefined" in it.error!! })
        assertTrue(report.describe(), failing.flatMap { it.who }.all { it.endsWith("run directly by a play") })
        val strict = report.outcomes.single { it.text == "echo strict" }
        assertTrue(report.describe(), strict.who.any { it.endsWith("playbook-fw-direct.yml:12") })
    }

    /** Include params (a dynamic include's `vars:`) win over the included task's own `vars:`, as in ansible-core. */
    fun testAnIncludeParamWinsOverTheTasksOwnVars() {
        assertEquals(setOf("policy DROP"), texts(value(IncludeFixture.PREC_PART, "policy {{ fwr_policy }}")))
    }

    /** A rendering task's own `vars:` do not hide the loop of the include that runs its file (include params win). */
    fun testTheIncludersLoopReachesATemplateWhoseRenderTaskSetsTheName() {
        val binding = runReadActionBlocking { VarLoopItems.bindingAt(project, vf(IncludeFixture.PREC_TEMPLATE), 0, "fwr_item") }
        assertEquals(listOf("main.yml"), binding?.tasks?.map { it.task.file.name })
        val first = preview(IncludeFixture.PREC_TEMPLATE)
        assertEquals(first.choices.map { it.label }.toString(), 2, first.choices.size)
        assertEquals("a", first.text.trim())
    }

    /** An includer's loop is evaluated where the include runs: the included task's own `vars:` do not reach it. */
    fun testAnIncludersLoopIsEvaluatedWhereTheIncludeRuns() {
        assertEquals(setOf("set a", "set b"), texts(value(IncludeFixture.SCOPE_PART, "set {{ fws_set }}")))
    }

    /** Two paths through one direct includer get distinct labels, extended outward. */
    fun testPathsThroughOneIncluderGetDistinctLabels() {
        val report = value(IncludeFixture.LAB_RULESET, "mode {{ fwb_mode }}")
        assertEquals(report.describe(), setOf("mode a", "mode b"), texts(report))
        assertTrue(report.describe(), report.outcomes.single { it.text == "mode a" }.who.all { it.endsWith("rules.yml:2 \u2190 main.yml:2") })
        assertTrue(report.describe(), report.outcomes.single { it.text == "mode b" }.who.all { it.endsWith("rules.yml:2 \u2190 main.yml:7") })
    }

    /** Include runs × the rendering task's own items stay within one loop's bound of preview choices. */
    fun testPreviewChoicesStayWithinOneLoopsBound() {
        val report = preview(IncludeFixture.CAP_TEMPLATE)
        assertEquals(LoopItems.MAX_ITEMS, report.choices.size)
    }

    /** The placeholder of an includer loop that cannot be evaluated is the render bundle's text. */
    fun testTheIncluderLoopPlaceholderTextComesFromTheBundle() {
        val literal = "  loop:\n" +
            "    - ip_version: ipv4\n      dest: /etc/fw/rules.v4\n      chains: \"{{ fwl_rules + fwl_rules_extra }}\"\n" +
            "    - ip_version: ipv6\n      dest: /etc/fw/rules.v6\n      chains: \"{{ fwl_rules_ipv6 + fwl_rules_extra_ipv6 }}\"\n"
        replace(IncludeFixture.LOOP_RULES, literal, "  loop: \"{{ fwl_unknown_rulesets }}\"\n")
        val text = preview(IncludeFixture.LOOP_TEMPLATE).text
        val expected = AnsibilityRenderBundle.message("include.placeholder.loop", "fwl_ruleset", "rules.yml:8", "the loop value has an unknown part")
        assertTrue(text, expected in text)
    }

    fun testImportVarsBindButAnImportLoopDoesNot() {
        assertEquals(setOf("echo imported"), texts(value(IncludeFixture.IMP_PART, "echo {{ fwi_mode }}")))
        val looped = value(IncludeFixture.IMP_LOOPED, "{{ fwi_each }}")
        assertTrue(looped.describe(), looped.outcomes.all { it.error?.contains("'fwi_each' is undefined") == true })
    }
}
