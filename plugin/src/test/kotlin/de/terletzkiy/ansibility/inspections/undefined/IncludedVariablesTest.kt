package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.CardContext
import de.terletzkiy.ansibility.api.CardSubject
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.resolve.include.IncludeFixture

/**
 * ANS-V003 and its card rows for names the include tasks that run a file give it ([IncludeFixture]): a looping
 * `include_tasks` with `loop_var` (case A), two `include_tasks` with `vars:` (case B), the template the included file
 * renders, `include_role` with `tasks_from` and a loop, the "any includer" rule, Molecule includers, `import_tasks`.
 */
@RequiresInfraFixture
class IncludedVariablesTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        IncludeFixture.FILES.forEach { (path, text) -> add(path, text) }
    }

    private fun falcon(): AnsibleRoot = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }

    private fun named(path: String, name: String): List<String> = findings(path).filter { it.endsWith(" $name") || " $name " in it }

    /** The card rows of ANS-V003 for [name] shown at [anchor] (+1) in [path]. */
    private fun card(path: String, name: String, anchor: String): String? = runReadActionBlocking {
        val text = FileDocumentManager.getInstance().getDocument(vf(path))!!.text
        val offset = text.indexOf(anchor).also { assertTrue("$anchor not in $path", it >= 0) } + 1
        UndefinedCardSection().section(CardSubject.Variable(falcon(), name, emptyList(), null), CardContext(project, vf(path), offset))?.toString()
    }

    fun testLoopVarOfALoopingIncludeIsSetInTheIncludedFileAndItsTemplate() {
        assertEmpty(named(IncludeFixture.LOOP_RULESET, "fwl_ruleset"))
        assertEmpty(named(IncludeFixture.LOOP_TEMPLATE, "fwl_ruleset"))
        assertNull(card(IncludeFixture.LOOP_RULESET, "fwl_ruleset", "fwl_ruleset.dest"))
    }

    fun testVarsOfEveryIncludeSetTheNameInTheIncludedFile() {
        assertEmpty(named(IncludeFixture.VARS_RULESET, "fwv_ruleset"))
        assertEmpty(highlights(IncludeFixture.VARS_RULESET))
    }

    fun testVarsOfTheIncludesReachTheTemplateTheIncludedFileRenders() {
        assertEmpty(named(IncludeFixture.VARS_TEMPLATE, "fwv_ruleset"))
    }

    fun testCardHasNoNotSetOrNoRuntimeDefaultWhenEveryIncludeSetsTheName() {
        assertNull(card(IncludeFixture.VARS_RULESET, "fwv_ruleset", "fwv_ruleset.dest"))
        assertNull(card(IncludeFixture.VARS_TEMPLATE, "fwv_ruleset", "fwv_ruleset.chains"))
    }

    fun testIncludeRoleWithTasksFromGivesItsVarsAndLoopToTheEntryFile() {
        assertEmpty(named(IncludeFixture.ENTRY_APPLY, "fwe_mode"))
        assertEmpty(named(IncludeFixture.ENTRY_APPLY, "fwe_set"))
        assertNull(card(IncludeFixture.ENTRY_APPLY, "fwe_mode", "fwe_mode"))
    }

    fun testOneIncluderOfTwoIsEnoughAndTheCardNamesTheOther() {
        assertEmpty(named(IncludeFixture.TWO_PART, "fwt_mode"))
        val html = card(IncludeFixture.TWO_PART, "fwt_mode", "fwt_mode")!!
        assertTrue(html, "Not set" in html && "when included from roles/fw_two/tasks/main.yml:7" in html)
        assertFalse(html, "main.yml:2" in html)
        assertFalse("no environments instead", "Not set for" in html || "env prod" in html)
        assertTrue(html, "No runtime default" in html)
    }

    fun testAMoleculeIncluderSetsNothingForProductionFiles() {
        assertEquals(
            listOf("4 MISSING fwm_flag ops/ops-ops1,prod/prod-prod1,prod/prod-prod2,test/test-test1"),
            named(IncludeFixture.MOL_APPLY, "fwm_flag"),
        )
        val html = card(IncludeFixture.MOL_APPLY, "fwm_flag", "fwm_flag")!!
        assertTrue(html, "Not set for" in html)
    }

    fun testImportVarsApplyButAnImportLoopBindsNothing() {
        assertEmpty(named(IncludeFixture.IMP_PART, "fwi_mode"))
        // the loop of an import_tasks is invalid: its loop_var is only silenced by the root-wide loop variable rule
        assertEmpty(named(IncludeFixture.IMP_LOOPED, "fwi_each"))
    }

    /** The card rows for a definition card on the key at [anchor] (+1) of [path] (an include task's own `vars:` key). */
    private fun definitionCard(path: String, name: String, anchor: String): String? = runReadActionBlocking {
        val text = FileDocumentManager.getInstance().getDocument(vf(path))!!.text
        val offset = text.indexOf(anchor).also { assertTrue("$anchor not in $path", it >= 0) }
        val subject = CardSubject.Variable(falcon(), name, emptyList(), SourceLocation(vf(path), offset))
        UndefinedCardSection().section(subject, CardContext(project, vf(path), offset))?.toString()
    }

    /**
     * A role entry file a play applies directly (`roles:`) while a play's `include_role` passes the variable: the hosts
     * of the direct run lack it (MISSING there, as before include vars counted), and the card says the direct run does.
     */
    fun testADirectlyAppliedEntryFileStillReportsTheHostsOfItsDirectRun() {
        val found = named(IncludeFixture.DIR_MAIN, "fwd_mode")
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), "MISSING" in found.single() && "test/test-test1" in found.single())
        assertFalse("the include's hosts have it", "ops/ops-ops1" in found.single())
        val html = card(IncludeFixture.DIR_MAIN, "fwd_mode", "fwd_mode")!!
        assertTrue(html, "when a play applies the role directly" in html)
    }

    /** With an inventory value on every host, an include path without the variable leaves nothing unset: no "Not set" row. */
    fun testNoNotSetRowWhenTheInventorySetsItEverywhere() {
        assertEmpty(named(IncludeFixture.INV_PART, "fwq_mode"))
        val html = card(IncludeFixture.INV_PART, "fwq_mode", "fwq_mode")!!
        assertFalse(html, "Not set" in html)
        assertTrue("the role still has no runtime default: $html", "No runtime default" in html)
    }

    /** On an include task's own `vars:` key, the card judges the files that include runs: every include sets it there. */
    fun testTheCardOfAnIncludeTasksVarsKeyJudgesTheFilesItRuns() {
        assertNull(definitionCard(IncludeFixture.VARS_RULES, "fwv_ruleset", "fwv_ruleset:"))
        val html = definitionCard(IncludeFixture.TWO_MAIN, "fwt_mode", "fwt_mode:")!!
        assertTrue("the other include of part.yml does not set it: $html", "when included from roles/fw_two/tasks/main.yml:7" in html)
    }

    fun testWithoutTheIncludeVarsTheUsesAreReportedAgain() {
        replace(IncludeFixture.VARS_RULES, "    fwv_ruleset:\n      ip_version: ipv4", "    fwv_other:\n      ip_version: ipv4")
        replace(IncludeFixture.VARS_RULES, "    fwv_ruleset:\n      ip_version: ipv6", "    fwv_other:\n      ip_version: ipv6")
        assertTrue(findings(IncludeFixture.VARS_RULESET).toString(), named(IncludeFixture.VARS_RULESET, "fwv_ruleset").all { "MISSING" in it })
        assertNotEmpty(named(IncludeFixture.VARS_RULESET, "fwv_ruleset"))
    }
}
