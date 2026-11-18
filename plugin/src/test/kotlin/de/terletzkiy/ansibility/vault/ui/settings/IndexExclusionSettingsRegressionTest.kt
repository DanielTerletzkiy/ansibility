package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.exclusion.IndexExclusionSettings
import javax.swing.JButton
import javax.swing.JComponent

/**
 * R30 review (adopted as a product test by the R30 review fixes): the suggestions' Add buttons followed the page only
 * when Add or Apply ran; Reset left a button off for a file that is no longer in the page's list.
 */
class IndexExclusionSettingsRegressionTest : VaultTestCase() {
    private val exclusions: IndexExclusionSettings get() = IndexExclusionSettings.getInstance()
    private lateinit var falcon: String

    override fun setUp() {
        super.setUp()
        exclusions.resetForTests()
        falcon = projectRoot("falcon", "[defaults]\nvault_identity_list = dev@secrets/dev.pw\n")
        write("$falcon/secrets/dev.pw", "${VaultVectors.DEV}\n")
    }

    override fun tearDown() {
        try {
            exclusions.resetForTests()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun addButton(component: JComponent): JButton =
        UIUtil.findComponentsOfType(component, JButton::class.java).single { it.name == VaultConfigurable.INDEX_ADD_COMPONENT + "dev.pw" }

    fun testResetTurnsAddBackOn() {
        root(falcon)
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            page.reset()
            PlatformTestUtil.waitWithEventsDispatching("suggestions", { page.suggestedIndexNames() != null }, 30)
            val area = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single { it.name == VaultConfigurable.INDEX_NAMES_COMPONENT }
            addButton(component).doClick()
            // R30 review fixes, decision 1: Add puts the file's exact path into the page's path list, not its name into the area.
            assertEquals("", area.text)
            assertEquals(listOf(vf("$falcon/secrets/dev.pw").canonicalPath), page.shownIndexPaths())
            assertFalse(addButton(component).isEnabled)

            page.reset()
            assertEquals("Reset empties the area again", "", area.text)
            assertEmpty("and the path list", page.shownIndexPaths())
            assertFalse(page.isModified)
            assertTrue("dev.pw is not in the area any more, so Add must work again", addButton(component).isEnabled)
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
