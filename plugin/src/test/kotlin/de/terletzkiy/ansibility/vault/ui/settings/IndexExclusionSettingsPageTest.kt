package de.terletzkiy.ansibility.vault.ui.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.IndexingTestUtil
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
        assertEquals("Add adds the exact path (R30 review fixes, decision 1)", listOf(vf("$falcon/secrets/dev.pw").canonicalPath), unread.single().files)
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
            // R30 review fixes, decision 1: Add puts the file's exact path into the page's path list, not its name into the area.
            val devPath = vf("$falcon/secrets/dev.pw").canonicalPath!!
            assertEquals("", area.text)
            assertEquals(listOf(devPath), page.shownIndexPaths())
            assertFalse("added once", UIUtil.findComponentsOfType(component, JButton::class.java).single { it.name == VaultConfigurable.INDEX_ADD_COMPONENT + "dev.pw" }.isEnabled)
            assertTrue(page.isModified)
            assertEquals("Apply stores it", emptyList<String>(), exclusions.paths)

            area.text = "dev.pw\n\n  team-*.pw  \nsecrets/ops.pw\n"
            val refused = try {
                page.apply()
                null
            } catch (e: ConfigurationException) {
                e.message
            }
            assertTrue("$refused", refused != null && refused.contains("secrets/ops.pw") && refused.contains("not a path"))
            assertEmpty("nothing stored", exclusions.extraNames)
            assertEmpty("nothing stored", exclusions.paths)

            area.text = "dev.pw\n\n  team-*.pw  \n"
            envLocal.isSelected = true
            page.apply()
            assertEquals(listOf("dev.pw", "team-*.pw"), exclusions.extraNames)
            assertEquals(listOf(devPath), exclusions.paths)
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

    /**
     * R30 review fixes, decision 6: Apply merges with the entries the Vault tab added while the page was open (the page
     * is a snapshot), and removes only what the page removed.
     */
    fun testApplyKeepsWhatTheVaultTabAddedMeanwhile() {
        exclusions.update(listOf("old.pw"), excludeEnvLocal = false, paths = listOf("/work/one.pw"))
        root(falcon)
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            page.reset()
            val area = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single { it.name == VaultConfigurable.INDEX_NAMES_COMPONENT }
            assertEquals("old.pw", area.text)
            assertEquals(listOf("/work/one.pw"), page.shownIndexPaths())

            // The Vault tab keeps a file out while the page is open.
            exclusions.addPaths(listOf("/work/two.pw"))
            assertFalse("the page is a snapshot: not modified by the tab", page.isModified)

            area.text = "new.pw"
            page.apply()
            assertEquals("old.pw removed, new.pw added", listOf("new.pw"), exclusions.extraNames)
            assertEquals("the tab's entry stays", listOf("/work/one.pw", "/work/two.pw"), exclusions.paths)
            assertEquals("the page shows the stored list", listOf("/work/one.pw", "/work/two.pw"), page.shownIndexPaths())
            assertFalse(page.isModified)
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        assertEquals(listOf("b", "c", "d"), VaultConfigurable.merged(loaded = listOf("a", "b"), page = listOf("b", "d"), stored = listOf("a", "b", "c")))
    }

    /**
     * R30 review fixes, decision 1: Apply refuses a typed name that names a file Ansibility reads as vars or vault
     * content (an environment's `group_vars` file, here), or more than a handful of files, and says which.
     */
    fun testApplyRefusesANameThatNamesAVarsFileOrTooManyFiles() {
        write("$falcon/group_vars/prod", "app_port: 8080\n")
        write("$falcon/secrets/prod", "${VaultVectors.PW1}\n")
        for (i in 1..6) write("$falcon/notes/n$i.notesx", "x\n")
        root(falcon)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            page.reset()
            val area = UIUtil.findComponentsOfType(component, JBTextArea::class.java).single { it.name == VaultConfigurable.INDEX_NAMES_COMPONENT }
            fun refusal(text: String): String? {
                area.text = text
                return try {
                    page.apply()
                    null
                } catch (e: ConfigurationException) {
                    e.message
                }
            }
            val vars = refusal("prod")
            assertTrue("$vars", vars != null && vars.contains("'prod' cannot be kept out") && vars.contains("group_vars/prod") && vars.contains("vars or vault"))
            val many = refusal("*.notesx")
            assertTrue("$many", many != null && many.contains("names 6 files") && many.contains("notes/n"))
            assertEmpty("nothing stored", exclusions.extraNames)
            assertNull("a name for the password file alone is fine", refusal("dev.pw"))
            assertEquals(listOf("dev.pw"), exclusions.extraNames)
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
