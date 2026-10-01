package de.terletzkiy.ansibility.coexist

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.AnsibilityCoexistenceSettings
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/** [CoexistenceSettings] is backed by the persistent Ansibility settings, read on every call. */
class CoexistenceSettingsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    fun testTheSettingsImplementationIsTheOnlyOneRegistered() {
        assertInstanceOf(CoexistenceSettings.getInstance(), AnsibilityCoexistenceSettings::class.java)
    }

    fun testDefaults() {
        val settings = CoexistenceSettings.getInstance()
        assertFalse("X85 is off: foreign items are only de-duplicated", settings.hideOtherAnsibleCompletions())
        assertTrue("the SchemaStore exclusion is on", settings.schemaStoreExclusion(project))
        assertTrue("X03 notifications are on", settings.conflictNotifications())
    }

    fun testTogglesApplyImmediately() {
        val settings = CoexistenceSettings.getInstance()
        AnsibilityAppSettings.getInstance().update {
            it.copy(coexistence = it.coexistence.copy(hideOtherAnsibleCompletions = true, conflictNotifications = false))
        }
        assertTrue(settings.hideOtherAnsibleCompletions())
        assertFalse(settings.conflictNotifications())

        AnsibilityProjectSettings.getInstance(project).update { it.copy(paths = it.paths.copy(schemaStoreExclusion = false)) }
        assertFalse(settings.schemaStoreExclusion(project))

        SettingsTestSupport.resetAll(project)
        assertFalse(settings.hideOtherAnsibleCompletions())
        assertTrue(settings.schemaStoreExclusion(project))
        assertTrue(settings.conflictNotifications())
    }
}
