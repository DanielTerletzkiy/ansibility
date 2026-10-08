package de.terletzkiy.ansibility.vault.monitor

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndexExtension
import de.terletzkiy.ansibility.index.secrets.SecretIndex
import kotlinx.coroutines.runBlocking

/** The monitoring's registrations (plan amendment R21, D164–D166): the index, the start, the groups, the prompts. */
class MonitorRegistrationTest : BasePlatformTestCase() {
    fun testTheSecretsIndexIsRegistered() {
        val index = FileBasedIndexExtension.EXTENSION_POINT_NAME.extensionList.filterIsInstance<SecretIndex>().single()
        assertEquals("ansibility.secrets", index.name.name)
        assertTrue(index.dependsOnFileContent())
    }

    fun testTheServiceStartsWithTheProjectButNotInTests() {
        val activity = ExtensionPointName<Any>("com.intellij.postStartupActivity").findExtension(SecretHealthActivity::class.java)
        assertNotNull("postStartupActivity in ansibility-vault-ui.xml", activity)
        val service = SecretHealthService.getInstance(project)
        service.stopForTests()
        runBlocking { SecretHealthActivity().execute(project) }
        assertFalse("a light project outlives a test: tests start the service themselves", service.isStarted)
    }

    fun testNotificationGroupsAreStickyForErrorsOnly() {
        val manager = NotificationGroupManager.getInstance()
        val errors = manager.getNotificationGroup(SecretNotifier.GROUP_ID)
        val warnings = manager.getNotificationGroup(SecretNotifier.WARNINGS_GROUP_ID)
        assertEquals(NotificationDisplayType.STICKY_BALLOON, errors.displayType)
        assertEquals("Ansibility Vault", NotificationGroup.getGroupTitle(SecretNotifier.GROUP_ID))
        assertEquals(NotificationDisplayType.BALLOON, warnings.displayType)
        assertEquals("Ansibility Vault warnings", NotificationGroup.getGroupTitle(SecretNotifier.WARNINGS_GROUP_ID))
    }

    fun testTheTabPromptsAreDialogsOutsideTests() {
        assertInstanceOf(SecretTabPrompts.getInstance(), SecretTabDialogPrompts::class.java)
    }
}
