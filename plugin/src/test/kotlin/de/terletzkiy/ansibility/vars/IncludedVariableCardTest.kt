package de.terletzkiy.ansibility.vars

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.host.card.TaskVars
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.inspections.undefined.UndefinedTestCase
import de.terletzkiy.ansibility.resolve.include.IncludeFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The variable card of a name the include tasks that run a file give it ([IncludeFixture]), with a host selected (host
 * mode): the includers' definitions are not "not for" the host, there is no "Not set for" / "No runtime default", the
 * Effective section does not call the host "not set", and the header does not call it an inventory variable.
 */
@RequiresInfraFixture
class IncludedVariableCardTest : UndefinedTestCase() {
    override fun addFixtureFiles() {
        IncludeFixture.FILES.forEach { (path, text) -> add(path, text) }
    }

    override fun setUp() {
        super.setUp()
        val root = AnsibleWorkspace.getInstance(project).roots().single { it.dir == vf(FALCON) }
        AnsibleContextService.getInstance(project).setSelection(root, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
    }

    /** The card (and hint) of the variable reference at [anchor] (+1) in [path]. */
    private fun card(path: String, anchor: String): String = card(path, anchor) { it is AnsibleSite.VarRef }

    /** The card (and hint) of the site at [anchor] (+1) in [path] that [accept] takes (a reference, a key). */
    private fun card(path: String, anchor: String, accept: (AnsibleSite) -> Boolean): String = runReadActionBlocking {
        val file = vf(path)
        val offset = FileDocumentManager.getInstance().getDocument(file)!!.text.indexOf(anchor).also { assertTrue("$anchor not in $path", it >= 0) } + 1
        val psi = PsiManager.getInstance(project).findFile(file)!!
        val site = SiteClassifier.EP_NAME.extensionList.firstNotNullOf { classifier -> classifier.classify(psi, offset)?.takeIf(accept) }
        val target = SiteDocumentation.EP_NAME.extensionList.firstNotNullOf { it.documentation(site, psi) }
        target.computeDocumentation().toString() + target.computeDocumentationHint()
    }

    /** The part of [html] from [start] on, up to [end] when given ("" when [start] is not in it). */
    private fun section(html: String, start: String, end: String? = null): String {
        val from = html.indexOf(start)
        if (from < 0) return ""
        val rest = html.substring(from)
        return if (end == null) rest else rest.substringBefore(end)
    }

    private fun assertNoHostGaps(html: String) {
        assertFalse(html, "not for prod-prod1" in html)
        assertFalse(html, "Not set for" in html)
        assertFalse(html, "No runtime default" in html)
        assertFalse(html, "not set for prod" in html)
        assertFalse(html, "inventory variable" in html)
    }

    fun testIncludeVarsAreNotForeignToTheSelectedHost() {
        val html = card(IncludeFixture.VARS_RULESET, "fwv_ruleset.dest")
        assertNoHostGaps(html)
        assertTrue(html, "include variable" in html)
        assertTrue(html, "applies through the include" in html)
        assertTrue(html, "set by the include tasks that run this file" in html)
    }

    fun testTheLoopVarOfALoopingIncludeIsALoopVariableWithoutHostGaps() {
        val html = card(IncludeFixture.LOOP_RULESET, "fwl_ruleset.dest")
        assertNoHostGaps(html)
        assertTrue(html, "loop variable" in html)
    }

    fun testTheTemplateTheIncludedFileRendersHasNoHostGapsEither() {
        val html = card(IncludeFixture.VARS_TEMPLATE, "fwv_ruleset.chains")
        assertNoHostGaps(html)
    }

    fun testOneIncludeVarEveryPathGoesThroughWinsOnTheHost() {
        val html = card(IncludeFixture.ENTRY_APPLY, "fwe_mode")
        assertFalse(html, "not for prod-prod1" in html)
        assertFalse(html, "inventory variable" in html)
        assertTrue(html, "fast" in html)
    }

    fun testOnlySomeIncludesSetItNamesTheOthers() {
        val html = card(IncludeFixture.TWO_PART, "fwt_mode")
        assertTrue(html, "when included from roles/fw_two/tasks/main.yml:7" in html)
        assertFalse(html, "not for prod-prod1" in html)
        assertTrue(html, "set only by some of the include tasks that run this file" in html)
    }

    fun testAMoleculeIncluderDoesNotMakeAProductionCardAnIncludeVariable() {
        val html = card(IncludeFixture.MOL_APPLY, "fwm_flag")
        assertFalse(html, "include variable" in html)
        assertFalse(html, "applies through the include" in html)
    }

    /**
     * With "Show Molecule in navigation and search" on, a converge play's include still binds nothing in a production
     * file (plan amendment R20): not in the header, the Effective note, nor the Rendered outcomes.
     */
    fun testAMoleculeIncluderBindsNothingInAProductionCardWithMoleculeShown() {
        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = it.molecule.copy(showInNavigation = true)) }
        val html = card(IncludeFixture.MOL_APPLY, "fwm_flag")
        assertFalse(html, "include variable" in html)
        assertFalse(html, "set only by some of the include tasks" in html)
        assertFalse(html, "converge.yml:" in section(html, "Rendered"))
    }

    /**
     * Case B, hovering the include task's own `vars:` key: an include variable, the other include's definition applies
     * through the include, no host is "not set", and the Effective winner (a mapping with Jinja inside) renders.
     */
    fun testTheVarsKeyOfAnIncludeTaskIsAnIncludeVariable() {
        val html = card(IncludeFixture.VARS_RULES, "fwv_ruleset:") { it is AnsibleSite.VarKey }
        assertNoHostGaps(html)
        assertTrue(html, "include variable" in html)
        assertTrue(html, "applies through the include" in html)
        assertFalse(html, "the run fails here" in html)
        assertTrue("the winner's chains render: $html", "-p tcp --dport 22 -j ACCEPT" in html)
    }

    /** Include params beat the included task's own `vars:` (ansible-core's include params): the include's definition wins. */
    fun testAnIncludeParamWinsOverTheTasksOwnVars() {
        val taskVars = runReadActionBlocking {
            val file = vf(IncludeFixture.PREC_PART)
            val offset = FileDocumentManager.getInstance().getDocument(file)!!.text.indexOf("fwr_policy }}")
            val root = AnsibleWorkspace.getInstance(project).contextOf(file)!!.root
            TaskVars.at(project, root, file, offset, "fwr_policy")
        }
        assertEquals("main.yml", taskVars.applied?.file?.name)
        assertTrue(taskVars.includeParam)
        val html = card(IncludeFixture.PREC_PART, "fwr_policy }}")
        assertTrue(html, "DROP" in section(html, "Rendered"))
    }

    /**
     * Case B with an inventory value too: every path sets the name through an include param, which beats the inventory,
     * so the inventory definition is no winner on the host (it is shadowed) while the includes' definitions are named.
     */
    fun testIncludeParamsOfEveryPathBeatAnInventoryValue() {
        add("$FALCON/group_vars/all/fwv.yml", "---\nfwv_ruleset:\n  dest: /tmp/x\n")
        refreshRoots()
        val html = card(IncludeFixture.VARS_RULESET, "fwv_ruleset.dest")
        val effective = section(html, "Effective", "Set in")
        assertFalse(effective, "/tmp/x" in effective)
        assertTrue(effective, "set by the include tasks that run this file" in effective)
        assertFalse(html, "not for prod-prod1" in html)
    }
}
