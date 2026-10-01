package de.terletzkiy.ansibility.context

import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The `ansibility-core.xml` registrations of WU0.3. */
class CoreRegistrationTest : BasePlatformTestCase() {
    fun testWorkspaceServiceImplementation() {
        assertInstanceOf(AnsibleWorkspace.getInstance(project), AnsibleWorkspaceImpl::class.java)
    }

    fun testStatusBarWidgetFactoryIsRegistered() {
        val factory = StatusBarWidgetFactory.EP_NAME.findExtension(AnsibleContextWidgetFactory::class.java)
        assertNotNull(factory)
        assertEquals(AnsibleContextWidget.ID, factory!!.id)
        assertEquals("Ansibility Context", factory.displayName)
        assertTrue(factory.isAvailable(project))
    }

    fun testWidgetStartsHidden() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val widget = AnsibleContextWidgetFactory().createWidget(project, scope) as AnsibleContextWidget
            assertEquals(AnsibleContextWidget.ID, widget.ID())
            assertFalse("nothing is shown before a file is selected", widget.component.isVisible)
            widget.dispose()
        } finally {
            scope.cancel()
        }
    }

    fun testNotificationGroupIsRegistered() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup(AnsibleOnboarding.NOTIFICATION_GROUP))
    }
}
