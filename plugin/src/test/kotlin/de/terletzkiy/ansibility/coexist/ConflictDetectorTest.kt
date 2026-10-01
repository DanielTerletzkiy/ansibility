package de.terletzkiy.ansibility.coexist

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.settings.AnsibilityAppSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport

class ConflictDetectorTest : BasePlatformTestCase() {
    private val properties: PropertiesComponent get() = PropertiesComponent.getInstance()

    override fun setUp() {
        super.setUp()
        properties.unsetValue(ConflictDetector.NOTIFIED_KEY)
    }

    override fun tearDown() {
        try {
            properties.unsetValue(ConflictDetector.NOTIFIED_KEY)
        } finally {
            super.tearDown()
        }
    }

    private fun plugin(id: String) = ConflictDetector.KNOWN.single { it.id == id }

    fun testKnownPluginsIncludeTheInstalledCompetitor() {
        assertTrue(ConflictDetector.KNOWN.any { it.id == "ir.msdehghan.plugins.ansible" })
        assertEquals("ids are unique", ConflictDetector.KNOWN.size, ConflictDetector.KNOWN.map { it.id }.toSet().size)
    }

    fun testComplementaryPluginsAreNotConflicts() {
        val ids = ConflictDetector.KNOWN.map { it.id }
        assertFalse(ids.contains("ru.sadv1r.ansible-vault-editor-idea-plugin"))
        assertFalse(ids.contains("de.achimonline.ansible_lint"))
        assertFalse(ids.contains("de.terletzkiy.ansibility"))
    }

    fun testEnabledConflictsUsesThePluginState() {
        val enabled = setOf("ir.msdehghan.plugins.ansible", "dev.vetro.ansible", "some.unrelated.plugin")
        assertEquals(
            listOf("ir.msdehghan.plugins.ansible", "dev.vetro.ansible"),
            ConflictDetector.enabledConflicts { it in enabled }.map { it.id },
        )
        assertTrue("the test IDE runs none of them", ConflictDetector.enabledConflicts().isEmpty())
    }

    fun testNotifiesOncePerPlugin() {
        val msdehghan = plugin("ir.msdehghan.plugins.ansible")
        val first = ConflictDetector.notifyIfNeeded(project, listOf(msdehghan), properties)
        assertNotNull(first)
        assertEquals("Ansibility", first!!.groupId)
        assertEquals("Another Ansible plugin is enabled", first.title)
        assertTrue(first.content.contains("Ansible (msdehghan)"))
        assertEquals(listOf("Open Plugins Settings"), first.actions.map { it.templateText })
        first.expire()

        assertNull("already reported", ConflictDetector.notifyIfNeeded(project, listOf(msdehghan), properties))

        val orchide = plugin("com.taff.plugin.orchide")
        val second = ConflictDetector.notifyIfNeeded(project, listOf(msdehghan, orchide), properties)
        assertNotNull(second)
        assertTrue(second!!.content.contains("OrchidE"))
        assertFalse("only new plugins are listed", second.content.contains("msdehghan"))
        second.expire()
        assertEquals(setOf(msdehghan.id, orchide.id), properties.getList(ConflictDetector.NOTIFIED_KEY).orEmpty().toSet())
    }

    fun testPluralWording() {
        val notification = ConflictDetector.createNotification(project, ConflictDetector.KNOWN.take(2))
        assertEquals("Other Ansible plugins are enabled", notification.title)
        assertTrue(notification.content.contains("overlap with Ansibility"))
        assertTrue(notification.content.contains("disabling them"))
    }

    fun testNothingToReport() {
        assertNull(ConflictDetector.notifyIfNeeded(project, emptyList(), properties))
    }

    fun testStartupCheckFollowsTheX03Setting() {
        val msdehghan = plugin("ir.msdehghan.plugins.ansible")
        val app = AnsibilityAppSettings.getInstance()
        properties.setList(ConflictDetector.NOTIFIED_KEY, null)
        try {
            app.update { it.copy(coexistence = it.coexistence.copy(conflictNotifications = false)) }
            var asked = false
            val off = ConflictDetector.notifyOnStartup(project, conflicts = { asked = true; listOf(msdehghan) }, properties = properties)
            assertNull("notifications off: nothing is shown", off)
            assertFalse("the plugin state is not even read", asked)
            assertTrue("and nothing is remembered, so turning it on later still reports", properties.getList(ConflictDetector.NOTIFIED_KEY).isNullOrEmpty())

            app.update { it.copy(coexistence = it.coexistence.copy(conflictNotifications = true)) }
            val on = ConflictDetector.notifyOnStartup(project, conflicts = { listOf(msdehghan) }, properties = properties)
            assertNotNull(on)
            on!!.expire()
        } finally {
            SettingsTestSupport.resetAll(project)
        }
    }
}
