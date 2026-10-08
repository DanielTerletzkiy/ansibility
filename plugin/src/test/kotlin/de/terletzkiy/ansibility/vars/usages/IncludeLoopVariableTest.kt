package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2.GTDUOutcome
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.testFramework.DumbModeTestUtils
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.LoopVarKind
import de.terletzkiy.ansibility.api.LoopVarSite
import de.terletzkiy.ansibility.dispatch.SitePresentation
import de.terletzkiy.ansibility.refactoring.VarRenameHandler
import de.terletzkiy.ansibility.vars.LoopIncludeFixture
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.CONVERGE
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.ENTRY_APPLY
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.IMPORTED
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.MAIN
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.OTHER
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.PROBE
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.RULES
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.RULESET
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.SITE
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.TEMPLATE
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.TWICE
import de.terletzkiy.ansibility.vars.LoopItems
import de.terletzkiy.ansibility.vars.VarCardHtml
import de.terletzkiy.ansibility.vars.VarDocumentationTarget
import de.terletzkiy.ansibility.vars.VarTargetElement

/**
 * Loop variables across includes ([LoopIncludeFixture]): `rules.yml` includes `ruleset.yml` in a loop with
 * `loop_var: fw_ruleset`, and `ruleset.yml` and the template it renders read it. Hover on the `loop_var` value, in the
 * included file and in the template shows the loop card; Ctrl+B goes to the `loop_var` value (members of the literal
 * items to their keys); Find Usages, highlighting and rename see one loop variable from all three places, without the
 * uses of a shadowing inner loop or of a file nothing includes.
 */
class IncludeLoopVariableTest : UsagesTestCase() {
    override fun setUp() {
        super.setUp()
        LoopIncludeFixture.FILES.forEach { (path, text) -> myFixture.tempDirFixture.createFile(path, text + "\n") }
        refreshRoots()
        settle()
    }

    private fun card(path: String, line: Int, marker: String, delta: Int = 1): String = text(html(hover(path, offsetAt(path, line, marker, delta))))

    private fun targets(path: String, line: Int, marker: String, delta: Int = 1): List<String> =
        gotoTargets(path, offsetAt(path, line, marker, delta)).map(::describe)

    private val loopLine = "loop variable of 'Apply and persist rulesets' (include_tasks in roles/fw/tasks/rules.yml:7), iterates a list of 2 items"
    private val usedIn = "Used in rules.yml 1 · ruleset.yml 4 · rules.j2 2 — Show usages"

    private fun assertLoopCard(card: String) {
        assertTrue(card, card.startsWith("fw_ruleset : dict"))
        assertTrue(card, loopLine in card)
        val options = section(card, "Options", "Used in")
        for (row in listOf("ip_version str", "dest str", "chains raw")) assertTrue(options, row in options)
        assertTrue(card, usedIn in card)
        for (absent in listOf("inventory variable", "Set in", "This definition", "Not set", "No runtime default")) assertFalse(card, absent in card)
    }

    // ------------------------------------------------------------------------------------------------ the site

    fun testTheLoopVarAndIndexVarValuesAreSites() {
        val start = offsetAt(RULES, 17, "fw_ruleset")
        val site = classify(RULES, start + 2)
        assertEquals(LoopVarSite("fw_ruleset", LoopVarKind.LOOP_VAR, TextRange.from(start, "fw_ruleset".length)), site)
        assertEquals(LoopVarKind.INDEX_VAR, (classify(RULES, offsetAt(RULES, 18, "fw_index", 1)) as LoopVarSite).kind)
        assertTrue("the loop_var key stays a keyword", classify(RULES, offsetAt(RULES, 17, "loop_var", 1)) is AnsibleSite.KeywordKey)
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals("found while indexing too", site, classify(RULES, start + 2))
        }
        assertEquals("Loop variable fw_ruleset (loop_control.loop_var value)", SitePresentation.describe(site!!))
        assertEquals("fw_ruleset", SitePresentation.variableName(site))
    }

    // ------------------------------------------------------------------------------------------------ hover

    fun testHoverOnTheLoopVarValueShowsTheLoopCard() {
        assertLoopCard(card(RULES, 17, "fw_ruleset"))
    }

    fun testHoverInTheIncludedFileAndInTheTemplateShowsTheSameLoopCard() {
        assertLoopCard(card(RULESET, 5, "fw_ruleset"))
        assertLoopCard(card(TEMPLATE, 2, "fw_ruleset"))
        val member = card(RULESET, 5, "fw_ruleset.dest", "fw_ruleset.".length + 1)
        assertTrue(member, member.startsWith("fw_ruleset.dest : str"))
        assertTrue(member, loopLine in member)
        val chains = card(TEMPLATE, 2, "chains")
        assertTrue(chains, chains.startsWith("fw_ruleset.chains : raw"))
        val hint = hint(hover(RULESET, offsetAt(RULESET, 5, "fw_ruleset", 1)))
        assertTrue(hint, hint.startsWith("fw_ruleset: dict"))
        assertTrue(hint, "loop variable" in hint)
    }

    /**
     * The "Used in" row's root-wide lookup runs for the full card only: building the card and its Ctrl-hover hint
     * computes no uses. `item` (a name of every loop) keeps only the "Show usages" link, never a root-wide count.
     */
    fun testTheHintComputesNoUsesAndItemGetsTheLinkOnly() {
        val target = hover(RULESET, offsetAt(RULESET, 5, "fw_ruleset", 1)) as VarDocumentationTarget
        val card = inBackgroundReadAction { target.card() }!!
        inBackgroundReadAction { VarCardHtml.hint(card) }
        assertFalse("the hint never counts uses", card.loopUses!!.isInitialized())
        val items = "${LoopIncludeFixture.ROLE}/tasks/items.yml"
        myFixture.tempDirFixture.createFile(items, "---\n- name: Each\n  ansible.builtin.debug:\n    msg: \"{{ item }}\"\n  loop: [a, b]\n")
        refreshRoots()
        settle()
        val item = card(items, 4, "item")
        assertTrue(item, "loop variable of" in item)
        assertTrue(item, "Used in Show usages" in item)
        assertFalse(item, "items.yml 1" in item)
    }

    fun testTheIndexVarIsAnIntOfTheSameLoop() {
        val card = card(RULESET, 10, "fw_index")
        assertTrue(card, card.startsWith("fw_index : int"))
        assertTrue(card, "index variable of 'Apply and persist rulesets' (include_tasks in roles/fw/tasks/rules.yml:7), iterates a list of 2 items" in card)
        assertEquals(listOf("$RULES:18"), targets(RULESET, 10, "fw_index"))
    }

    // ------------------------------------------------------------------------------------------------ Ctrl+B

    fun testCtrlBGoesToTheLoopVarValueAndMembersToTheItemKeys() {
        assertEquals(listOf("$RULES:17"), targets(RULESET, 5, "fw_ruleset"))
        val target = gotoTargets(RULESET, offsetAt(RULESET, 5, "fw_ruleset", 1)).single() as VarTargetElement
        assertEquals("loop_var of fw_ruleset · roles/fw/tasks/rules.yml:17", target.locationString)
        assertEquals("inside another task's loop expression", listOf("$RULES:17"), targets(RULESET, 11, "fw_ruleset"))
        assertEquals(listOf("$RULES:11", "$RULES:14"), targets(RULESET, 5, "fw_ruleset.dest", "fw_ruleset.".length + 1))
        assertEquals("from the template", listOf("$RULES:17"), targets(TEMPLATE, 2, "fw_ruleset"))
        assertEquals(listOf("$RULES:12", "$RULES:15"), targets(TEMPLATE, 2, "chains"))
        assertEquals("a shadowing loop of the same name keeps its own", listOf("$RULESET:18"), targets(RULESET, 15, "fw_ruleset"))
    }

    /**
     * The `loop_var` value is the declaration: Ctrl+B there has no targets (a scalar value is no declaration for the
     * platform, so it does not switch to Show Usages, D-FU4), and Alt+F7 there searches the loop variable.
     */
    fun testCtrlBOnTheLoopVarValueHasNoTargetsAndAltF7SearchesTheLoop() {
        assertEquals(emptyList<String>(), targets(RULES, 17, "fw_ruleset"))
        val offset = at(RULES, 17, "fw_ruleset")
        assertFalse("never navigates away from the declaration", gtdu(offset) == GTDUOutcome.GTD)
        val symbol = targetAtCaret()!!
        assertEquals("fw_ruleset", symbol.name)
        assertTrue(symbol.scope is VarScope.Loop)
    }

    fun testEveryIncludingLoopIsATargetAndAnImportLoopBindsNothing() {
        assertEquals(listOf("$MAIN:15", "$MAIN:21"), targets(TWICE, 4, "fw_pass"))
        assertEquals(
            listOf("loop_var of fw_pass · roles/fw/tasks/main.yml:15", "loop_var of fw_pass · roles/fw/tasks/main.yml:21"),
            gotoTargets(TWICE, offsetAt(TWICE, 4, "fw_pass", 1)).map { (it as VarTargetElement).locationString },
        )
        val card = card(TWICE, 4, "fw_pass")
        assertTrue(card, "loop variable of 'First pass' (include_tasks in roles/fw/tasks/main.yml:11), iterates a list of 2 items" in card)
        assertTrue(card, "loop variable of 'Second pass' (include_tasks in roles/fw/tasks/main.yml:17), iterates a list of 1 item" in card)
        assertNull(runReadActionBlocking { LoopItems.bindingAt(project, vf(IMPORTED), offsetAt(IMPORTED, 4, "fw_each", 1), "fw_each") })
        assertFalse("no loop card for an import's loop name", "loop variable of" in card(IMPORTED, 4, "fw_each"))
    }

    // ------------------------------------------------------------------------------------------------ include_role, Molecule

    /** An `include_role` with `tasks_from` in a loop binds its loop variable in the role's entry file. */
    fun testAnIncludeRoleLoopBindsTheEntryFile() {
        val card = card(ENTRY_APPLY, 4, "fw_target")
        assertTrue(card, card.startsWith("fw_target : dict"))
        assertTrue(card, "loop variable of 'Apply the entry rules' (include_role in site.yml:7), iterates a list of 2 items" in card)
        assertFalse(card, "inventory variable" in card)
        assertEquals(listOf("$SITE:15"), targets(ENTRY_APPLY, 4, "fw_target"))
        assertEquals(listOf("$SITE:12", "$SITE:13"), targets(ENTRY_APPLY, 5, "fw_target.dest", "fw_target.".length + 1))
        at(ENTRY_APPLY, 4, "fw_target")
        val expected = listOf("$ENTRY_APPLY:4:fw_target R", "$ENTRY_APPLY:5:fw_target R", "$SITE:15:fw_target W").sorted()
        assertEquals(expected, describeUsages(findUsagesViaAction()))
        at(SITE, 15, "fw_target")
        assertEquals("from the loop_var value", expected, describeUsages(findUsagesViaAction()))
    }

    /**
     * Plan amendment R20: a Molecule converge play's looping `include_role` binds nothing in a production file, for its
     * card, Ctrl+B and usages started there. A search that starts at the converge loop sees everything (D154): its uses
     * in the production file it runs are its own.
     */
    fun testAMoleculeIncludeLoopBindsNothingInAProductionFile() {
        assertNull(runReadActionBlocking { LoopItems.bindingAt(project, vf(PROBE), offsetAt(PROBE, 4, "fw_probe", 1), "fw_probe") })
        val card = card(PROBE, 4, "fw_probe")
        assertFalse(card, "loop variable of" in card)
        assertFalse(targets(PROBE, 4, "fw_probe").any { it.startsWith(CONVERGE) })
        at(PROBE, 4, "fw_probe")
        assertFalse("a root variable, not the converge loop's", targetAtCaret()!!.scope is VarScope.Loop)
        at(CONVERGE, 11, "fw_probe")
        assertEquals(listOf("$CONVERGE:11:fw_probe W", "$PROBE:4:fw_probe R"), describeUsages(findUsagesViaAction()))
    }

    /** Rename keeps renaming Molecule occurrences (D155): from the converge loop it renames the production file it runs too. */
    fun testRenameFromAMoleculeIncludeLoopRenamesTheProductionFileItRuns() {
        rename(CONVERGE, 11, "fw_probe", "fw_check")
        assertText(CONVERGE, "loop_var: fw_check\n")
        assertText(PROBE, "msg: \"{{ fw_check }}\"")
    }

    // ------------------------------------------------------------------------------------------------ usages, rename

    private val expected = listOf(
        "$RULES:17:fw_ruleset W",
        "$RULES:19:fw_ruleset R",
        "$RULESET:2:fw_ruleset R",
        "$RULESET:5:fw_ruleset R",
        "$RULESET:10:fw_ruleset R",
        "$RULESET:11:fw_ruleset R",
        "$TEMPLATE:1:fw_ruleset R",
        "$TEMPLATE:2:fw_ruleset R",
    ).sorted()

    fun testFindUsagesFromTheValueTheIncludedFileAndTheTemplateAgree() {
        at(RULES, 17, "fw_ruleset")
        val fromValue = targetAtCaret()!!
        assertEquals("from the loop_var value", expected, describeUsages(findUsagesViaAction()))
        at(RULESET, 5, "fw_ruleset")
        assertEquals("one symbol from the included file", fromValue, targetAtCaret())
        assertEquals("from a use in the included file", expected, describeUsages(findUsagesViaAction()))
        at(TEMPLATE, 2, "fw_ruleset")
        assertEquals("one symbol from the template", fromValue, targetAtCaret())
        assertEquals("from the template", expected, describeUsages(findUsagesViaAction()))
    }

    fun testTheShadowingLoopIsItsOwnVariable() {
        at(RULESET, 15, "fw_ruleset")
        assertEquals(listOf("$RULESET:15:fw_ruleset R", "$RULESET:18:fw_ruleset W"), describeUsages(findUsagesViaAction()))
    }

    fun testHighlightingInTheIncludedFile() {
        at(RULESET, 5, "fw_ruleset")
        val handler = highlightHandlerAtCaret()!!
        assertEquals(listOf("10:fw_ruleset", "11:fw_ruleset", "2:fw_ruleset", "5:fw_ruleset"), handler.readUsages.map { lineAndText(RULESET, it) }.sorted())
        assertEmpty("the loop_var value is in rules.yml", handler.writeUsages)
    }

    /** Renames the variable at [marker] on [line] of [path] to [newName] through the variables' rename handler. */
    private fun rename(path: String, line: Int, marker: String, newName: String) {
        myFixture.configureFromExistingVirtualFile(vf(path))
        myFixture.editor.caretModel.moveToOffset(offsetAt(path, line, marker, 1))
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(PsiElementRenameHandler.DEFAULT_NAME, newName)
            .build()
        val handler = RenameHandlerRegistry.getInstance().getRenameHandler(context)
        assertTrue("the variables' handler: $handler", handler is VarRenameHandler)
        handler!!.invoke(project, myFixture.editor, myFixture.file, context)
    }

    /**
     * Two include tasks of `twice.yml` bind `loop_var: fw_pass`: from either value, from the included file, the search
     * and rename see both values and the read, so a rename keeps every run of `twice.yml` working.
     */
    fun testTwoIncludersOfOneFileAreOneLoopVariableFromEitherValue() {
        val expected = listOf("$MAIN:15:fw_pass W", "$MAIN:21:fw_pass W", "$TWICE:4:fw_pass R")
        at(MAIN, 15, "fw_pass")
        val fromFirst = targetAtCaret()!!
        assertEquals("from the first value", expected, describeUsages(findUsagesViaAction()))
        at(MAIN, 21, "fw_pass")
        assertEquals("one symbol from the second value", fromFirst, targetAtCaret())
        at(TWICE, 4, "fw_pass")
        assertEquals("one symbol from the included file", fromFirst, targetAtCaret())
        assertEquals("from the included file", expected, describeUsages(findUsagesViaAction()))
        val card = card(MAIN, 15, "fw_pass")
        assertTrue(card, "loop variable of 'Second pass'" in card)

        rename(MAIN, 15, "fw_pass", "fw_round")
        val main = FileDocumentManager.getInstance().getDocument(vf(MAIN))!!.text
        assertEquals(main, 2, main.split("loop_var: fw_round\n").size - 1)
        assertFalse(main, "fw_pass" in main)
        assertText(TWICE, "msg: \"{{ fw_round }}\"")
    }

    fun testRenameFromTheLoopVarValueRenamesTheIncludedFileAndTheTemplate() {
        rename(RULES, 17, "fw_ruleset", "fw_set")
        assertText(RULES, "loop_var: fw_set\n", "label: \"{{ fw_set.dest }}\"")
        assertText(
            RULESET, "Render the {{ fw_set.ip_version }} rules", "dest: \"{{ fw_set.dest }}\"", "in {{ fw_set.dest }}",
            "loop: \"{{ fw_set.chains }}\"", "msg: \"{{ fw_ruleset }}\"", "loop_var: fw_ruleset\n",
        )
        assertText(TEMPLATE, "# {{ fw_set.ip_version }}", "{% for rule in fw_set.chains %}")
        assertText(OTHER, "{{ fw_ruleset }}")
    }

    private fun assertText(path: String, vararg parts: String) {
        val text = FileDocumentManager.getInstance().getDocument(vf(path))!!.text
        for (part in parts) assertTrue("'$part' in $path:\n$text", part in text)
    }
}
