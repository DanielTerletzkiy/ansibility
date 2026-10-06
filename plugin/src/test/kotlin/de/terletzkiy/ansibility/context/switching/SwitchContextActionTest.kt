package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/** "Ansibility: Switch Context…" (F8.1): registration, root-qualified entries, preferred roots, choosing env, host and play. */
@RequiresInfraFixture
class SwitchContextActionTest : ContextSwitchingTestCase() {
    private val action get() = ActionManager.getInstance().getAction(SWITCH_CONTEXT_ACTION_ID)

    fun testItIsRegisteredWithoutAShortcutAndEnabledWithInventories() {
        assertTrue(action is SwitchContextAction)
        assertEquals("Ansibility: Switch Context…", action.templateText)
        assertEquals("F8.1 names no default shortcut", 0, action.shortcutSet.shortcuts.size)
        assertTrue(updated(action).presentation.isEnabledAndVisible)
    }

    fun testEntriesAreRootQualifiedWithTheEditorsRootFirst() {
        val group = SwitchContextGroup(vf("repos/platform/ansible/environments/prod/hosts.yml"))
        val lines = texts(children(group))
        assertEquals("-- platform", lines.first())
        assertEquals("the default selection is checked", "[x] platform › All environments", lines[1].substringBefore(" |"))
        val falcon = lines.indexOf("-- falcon")
        assertTrue(lines.toString(), falcon > 1)
        assertEquals("[x] falcon › All environments | 10 hosts", lines[falcon + 1])
        assertEquals("falcon › ops | 1 host", lines[falcon + 2])
        assertTrue(lines.toString(), "falcon › prod › prod-prod1 | 192.0.2.29" in lines)
        assertTrue(lines.toString(), "falcon › test › test-test1 | 1 of 7 names on 192.0.2.43" in lines)
        assertFalse("roots without inventory are not listed", "-- golden" in lines)
    }

    fun testChoosingAHostSetsEnvironmentAndHostTogether() {
        val group = SwitchContextGroup(null)
        val entry = child(group, "falcon › test › test-test1") as TargetChoiceGroup
        assertTrue("an entry performs and opens its plays", updated(entry).presentation.isPerformGroup)
        perform(entry)
        assertEquals(RootContext(EnvironmentChoice.Named("test"), "test-test1"), context.selection(root("repos/falcon/ansible")))
        assertTrue("the current choice is preselected", (child(SwitchContextGroup(null), "falcon › test › test-test1") as TargetChoiceGroup).isCurrent)
        assertEquals("other roots are untouched", RootContext.DEFAULT, context.selection(root("repos/platform/ansible")))
    }

    fun testAnEntrysSubmenuChoosesThePlayToo() {
        val entry = child(SwitchContextGroup(null), "falcon › prod › prod-prod1") as TargetChoiceGroup
        val plays = children(entry)
        assertEquals("nothing is checked under an entry that is not the selection", "Auto | every play that hits the selection", texts(plays).first())
        perform(plays.first { updated(it).presentation.text == "playbook-setup-system.yml › KeepAliveD" })
        assertEquals(
            RootContext(EnvironmentChoice.Named("prod"), "prod-prod1", "playbook-setup-system.yml#4"),
            context.selection(root("repos/falcon/ansible")),
        )
        val again = child(SwitchContextGroup(null), "falcon › prod › prod-prod1") as TargetChoiceGroup
        assertEquals("[x] playbook-setup-system.yml › KeepAliveD", texts(children(again)).first { it.contains("KeepAliveD") })
    }

    fun testEnvironmentEntriesClearTheHost() {
        context.setSelection(root("repos/falcon/ansible"), RootContext(EnvironmentChoice.Named("prod"), "prod-prod1"))
        perform(child(SwitchContextGroup(null), "falcon › test"))
        assertEquals(RootContext(EnvironmentChoice.Named("test")), context.selection(root("repos/falcon/ansible")))
        perform(child(SwitchContextGroup(null), "falcon › All environments"))
        assertEquals(RootContext.DEFAULT, context.selection(root("repos/falcon/ansible")))
    }

    fun testEveryEntryShowsItsSecondaryTextAndNeverHidesWhenEmpty() {
        for (entry in children(SwitchContextGroup(null)).filterIsInstance<TargetChoiceGroup>().take(5)) {
            val presentation = updated(entry).presentation
            assertFalse(presentation.isHideGroupIfEmpty)
            assertFalse(presentation.isDisableGroupIfEmpty)
            assertNotNull(presentation.getClientProperty(ActionUtil.SECONDARY_TEXT))
        }
        assertTrue(children(SwitchContextGroup(null)).first() is Separator)
    }
}
