package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.configurationStore.serialize
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings

/**
 * ANS-V108's allowlist in the shared vault settings (plan amendment R21, D162): Molecule's folders by default, saved
 * in `.idea/ansibility-vault.xml` only when changed (an emptied list stays empty), and edited on the Vault settings
 * page one glob per line.
 */
class PlaintextKeyAllowlistSettingsTest : BasePlatformTestCase() {
    private val settings: VaultProjectSettings get() = VaultProjectSettings.getInstance(project)

    override fun tearDown() {
        try {
            settings.loadState(VaultProjectSettings.StateBean())
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [settings] written the way the component store writes them and read back into fresh settings. */
    private fun roundTrip(): List<String> {
        val element = serialize(settings.state)
        val loaded = VaultProjectSettings()
        loaded.loadState(element?.let { XmlSerializer.deserialize(it, VaultProjectSettings.StateBean::class.java) } ?: VaultProjectSettings.StateBean())
        return loaded.plaintextKeyAllowlist
    }

    fun testTheAllowlistDefaultsToMoleculeAndSurvivesSaving() {
        assertEquals(listOf("**/molecule/**"), settings.plaintextKeyAllowlist)
        assertNull("the defaults write nothing", serialize(settings.state))
        assertEquals(listOf("**/molecule/**"), roundTrip())

        settings.plaintextKeyAllowlist = listOf(" docker/** ", "", "docker/**", "legacy/certs/*.key")
        assertEquals("trimmed, without blanks and duplicates", listOf("docker/**", "legacy/certs/*.key"), settings.plaintextKeyAllowlist)
        assertEquals(listOf("docker/**", "legacy/certs/*.key"), roundTrip())
        assertEquals(2, settings.plaintextKeyAllowlistGlobs().size)

        settings.plaintextKeyAllowlist = emptyList()
        assertEquals("an emptied allowlist stays empty (no Molecule default comes back)", emptyList<String>(), roundTrip())
        assertEmpty(settings.plaintextKeyAllowlistGlobs())
    }

    fun testTheVaultPageEditsTheAllowlistOneGlobPerLine() {
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            val area = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single { it.name == VaultConfigurable.ALLOWLIST_COMPONENT }
            page.reset()
            assertEquals("**/molecule/**", area.text)
            assertFalse(page.isModified)

            area.text = "**/molecule/**\n\n  docker/certs/**  \n"
            assertTrue(page.isModified)
            page.apply()
            assertEquals(listOf("**/molecule/**", "docker/certs/**"), settings.plaintextKeyAllowlist)

            settings.plaintextKeyAllowlist = listOf("legacy/**")
            page.reset()
            assertEquals("legacy/**", area.text)
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
