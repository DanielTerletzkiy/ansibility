package de.terletzkiy.ansibility.completion.jinja

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.vars.LoopIncludeFixture
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.ENTRY_APPLY
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.IMPORTED
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.PROBE
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.RULESET
import de.terletzkiy.ansibility.vars.LoopIncludeFixture.TEMPLATE

/**
 * Completion of loop variables across includes ([LoopIncludeFixture]), the same bindings hover, Ctrl+B and ANS-V003 use
 * (`resolve.include.IncludeBindings`): the members of a looping `include_tasks` item in the included file and in the
 * template it renders, the loop variable of an `include_role` with `tasks_from` in the role's entry file; an
 * `import_tasks` loop and a Molecule converge play's include loop bind nothing.
 */
class IncludeLoopCompletionTest : JinjaCompletionTestCase() {
    override fun setUp() {
        super.setUp()
        LoopIncludeFixture.FILES.forEach { (path, text) -> myFixture.tempDirFixture.createFile(path, text + "\n") }
        refreshRoots()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testMembersOfAnIncludeLoopItemInTheIncludedFileAndTheTemplate() {
        val inFile = completeAfterEdit(RULESET, 5, "fw_ruleset.dest", "fw_ruleset.")
        assertContainsElements(strings(inFile), "ip_version", "dest", "chains")
        reset(RULESET)
        val inTemplate = completeAfterEdit(TEMPLATE, 1, "fw_ruleset.ip_version", "fw_ruleset.")
        assertContainsElements(strings(inTemplate), "ip_version", "dest", "chains")
    }

    fun testIncludeLoopNamesAreLoopNames() {
        val items = completeAfterEdit(RULESET, 5, "fw_ruleset.dest", "fw_")
        assertTier(items, "fw_ruleset", Tier.LOOP)
        assertTier(items, "fw_index", Tier.LOOP)
        reset(RULESET)
        val entry = completeAfterEdit(ENTRY_APPLY, 4, "fw_target.dest", "fw_target.")
        assertContainsElements(strings(entry), "dest")
    }

    fun testAnImportLoopAndAMoleculeIncludeLoopBindNothing() {
        val imported = completeAfterEdit(IMPORTED, 4, "fw_each", "fw_")
        assertFalse("an import's loop binds nothing", isLoop(imported, "fw_each"))
        reset(IMPORTED)
        val probe = completeAfterEdit(PROBE, 4, "fw_probe", "fw_")
        assertFalse("a Molecule include binds nothing in a production file", isLoop(probe, "fw_probe"))
    }

    private fun isLoop(items: List<LookupElement>, name: String): Boolean {
        val element = items.firstOrNull { it.lookupString == name } ?: return false
        val priority = priority(element)
        val next = Tier.entries.getOrNull(Tier.LOOP.ordinal - 1)?.base ?: Double.MAX_VALUE
        return priority >= Tier.LOOP.base && priority < next
    }

    private fun assertTier(items: List<LookupElement>, name: String, tier: Tier) {
        val priority = priority(item(items, name))
        val next = Tier.entries.getOrNull(tier.ordinal - 1)?.base ?: Double.MAX_VALUE
        assertTrue("$name: $priority not in $tier", priority >= tier.base && priority < next)
    }
}
