package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.exclusion.IndexExclusionSettings
import de.terletzkiy.ansibility.vault.exclusion.PasswordFileSuggestions
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.swing.JButton
import javax.swing.JCheckBox

/**
 * Plan amendment R30 (D224, D225, D228) on Settings › Ansibility › Vault: "Keep out of the IDE's indexes" suggests the
 * names of this project's password files (configured, conventional, the one a consented `.env.local` names), never a
 * script and never a name the list keeps out; Add, Apply (validated: file names only), "Also keep .env.local out".
 */
class IndexExclusionSettingsPageTest : VaultTestCase() {
    private val exclusions: IndexExclusionSettings get() = IndexExclusionSettings.getInstance()
    private lateinit var falcon: String

    override fun setUp() {
        super.setUp()
        exclusions.resetForTests()
        falcon = projectRoot("falcon", "[defaults]\nvault_identity_list = dev@secrets/dev.pw\nvault_password_file = scripts/vault-client.py\n")
        write("$falcon/secrets/dev.pw", "${VaultVectors.DEV}\n")
        val script = write("$falcon/scripts/vault-client.py", "#!/usr/bin/env python3\nprint('from the keyring')\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/.env.local", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=secrets/team.pw\n")
        write("$falcon/secrets/team.pw", "${VaultVectors.PROD}\n")
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

    fun testSuggestionsNameTheProjectsPasswordFilesNotYetKeptOut() {
        val root = root(falcon)
        val unread = PasswordFileSuggestions.compute(project)
        assertEquals("without a consent for .env.local, the file it is expected to name (.vault-pass, kept out)", listOf("dev.pw"), unread.map { it.name })
        assertEquals(listOf("repos/falcon/ansible/secrets/dev.pw"), unread.single().paths.map { it.substringAfter(base.fileName.toString() + "/") })
        assertEmpty("nothing secret was read", access.secretReads)

        assertEmpty("the consent dialog says yes", await { secrets.consentToEnvLocals(listOf(registry.discovery(root))) })
        assertEquals("the file a consented .env.local names", listOf("dev.pw", "team.pw"), PasswordFileSuggestions.compute(project).map { it.name })
        assertFalse("its key line only, never a password file", access.secretReads.any { it.fileName.toString().endsWith(".pw") || it.fileName.toString() == ".vault-pass" })

        exclusions.update(listOf("*.pw"), excludeEnvLocal = false)
        assertEmpty("a name the list keeps out is not suggested", PasswordFileSuggestions.compute(project))
    }

    fun testThePageAddsValidatesAndStoresTheNames() {
        root(falcon)
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            page.reset()
            PlatformTestUtil.waitWithEventsDispatching("suggestions", { page.suggestedIndexNames() != null }, 30)
            assertEquals(listOf("dev.pw"), page.suggestedIndexNames())
            val area = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single { it.name == VaultConfigurable.INDEX_NAMES_COMPONENT }
            val envLocal = UIUtil.findComponentsOfType(component, JCheckBox::class.java).single { it.name == VaultConfigurable.INDEX_ENV_LOCAL_COMPONENT }
            assertEquals("", area.text)
            assertFalse(envLocal.isSelected)
            assertFalse(page.isModified)

            val add = UIUtil.findComponentsOfType(component, JButton::class.java).single { it.name == VaultConfigurable.INDEX_ADD_COMPONENT + "dev.pw" }
            add.doClick()
            assertEquals("dev.pw", area.text)
            assertFalse("added once", UIUtil.findComponentsOfType(component, JButton::class.java).single { it.name == VaultConfigurable.INDEX_ADD_COMPONENT + "dev.pw" }.isEnabled)
            assertTrue(page.isModified)
            assertEquals("Apply stores it", emptyList<String>(), exclusions.extraNames)

            area.text = "dev.pw\n\n  team-*.pw  \nsecrets/ops.pw\n"
            val refused = try {
                page.apply()
                null
            } catch (e: ConfigurationException) {
                e.message
            }
            assertTrue("$refused", refused != null && refused.contains("secrets/ops.pw") && refused.contains("not a path"))
            assertEmpty("nothing stored", exclusions.extraNames)

            area.text = "dev.pw\n\n  team-*.pw  \n"
            envLocal.isSelected = true
            page.apply()
            assertEquals(listOf("dev.pw", "team-*.pw"), exclusions.extraNames)
            assertTrue(exclusions.excludeEnvLocal)
            assertEquals("the stored form", "dev.pw\nteam-*.pw", area.text)
            assertFalse(page.isModified)

            exclusions.update(listOf("other.pw"), excludeEnvLocal = false)
            page.reset()
            assertEquals("other.pw", area.text)
            assertFalse(envLocal.isSelected)
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
