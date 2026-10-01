package de.terletzkiy.ansibility.context.switching

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorNotificationProvider
import de.terletzkiy.ansibility.api.ContextWidgetSegment
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowPanel

/** The `<!-- ha3 -->` block of `ansibility-host.xml`, in a project without any Ansible root. */
class ContextSwitchingRegistrationTest : BasePlatformTestCase() {
    private fun action(id: String) = ActionManager.getInstance().getAction(id) ?: error("$id is not registered")

    private fun group(id: String) = action(id) as DefaultActionGroup

    fun testActionsJoinTheirGroups() {
        assertTrue(group("ToolsMenu").getChildren(null).contains(action(SWITCH_CONTEXT_ACTION_ID)))
        assertTrue(group("EditorPopupMenu").getChildren(null).contains(action(ToolWindowContextAction.USE_AS_CONTEXT_ACTION_ID)))
        assertTrue(group("Ansibility.ToolWindow.Popup").getChildren(null).contains(action(ToolWindowContextAction.USE_AS_CONTEXT_ACTION_ID)))
        val extra = group(AnsibleToolWindowPanel.TOOLBAR_EXTRA_GROUP).getChildren(null).toList()
        assertEquals(
            "R9 D45: [Scope] first, then the context button, whatever the fragment include order",
            listOf(action("Ansibility.Scope.Combo"), action("Ansibility.ToolWindow.Context")),
            extra.take(2),
        )
    }

    fun testRegisteredActionsNameTheProduct() {
        // DEV.md, Branding: action texts say "Ansibility:"; "Ansible context" stays the name of the concept.
        assertEquals("Ansibility: Switch Context…", action(SWITCH_CONTEXT_ACTION_ID).templateText)
        assertEquals("Ansibility: Use as Ansible Context", action(ToolWindowContextAction.USE_AS_CONTEXT_ACTION_ID).templateText)
        assertEquals("Ansibility: Ansible Context Selector", action("Ansibility.ToolWindow.Context").templateText)
        for (id in listOf(SWITCH_CONTEXT_ACTION_ID, ToolWindowContextAction.USE_AS_CONTEXT_ACTION_ID, "Ansibility.ToolWindow.Context")) {
            assertFalse("$id has a description", action(id).templatePresentation.description.isNullOrBlank())
        }
    }

    fun testTheFileScopeSegmentAndTheBannersAreRegistered() {
        assertTrue(ContextWidgetSegment.EP_NAME.extensionList.first() is FileScopeSegment)
        val providers = EditorNotificationProvider.EP_NAME.getExtensions(project)
        assertTrue(providers.any { it is ContextOverrideBannerProvider })
        assertTrue(providers.any { it is PlaybookDirBannerProvider })
    }

    fun testWithoutAnyRootTheActionsAreHidden() {
        for (id in listOf(SWITCH_CONTEXT_ACTION_ID, ToolWindowContextAction.USE_AS_CONTEXT_ACTION_ID, "Ansibility.ToolWindow.Context")) {
            val action = action(id)
            val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
            val event = TestActionEvent.createTestEvent(action, data)
            runReadActionBlocking { action.update(event) }
            assertFalse(id, event.presentation.isEnabledAndVisible)
        }
    }
}
