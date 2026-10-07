package de.terletzkiy.ansibility.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.become.BecomePasswordAnswer
import de.terletzkiy.ansibility.run.become.BecomePasswordRequest
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.become.BecomePrompter
import de.terletzkiy.ansibility.run.become.BecomeResult
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.vault.identity.MasterPasswordPrompt
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.secrets.PasswordSafeCredentialStore
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import com.intellij.util.EnvironmentUtil
import com.intellij.testFramework.replaceService
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/** Become passwords from the sources of the runner settings, and the prompt with its remember choices. */
class BecomePasswordsTest : VaultTestCase() {
    private class FakeBecomePrompter : BecomePrompter {
        val requests = CopyOnWriteArrayList<BecomePasswordRequest>()
        val answers = ConcurrentLinkedDeque<Triple<String, RememberChoice, Boolean>>()

        override fun ask(project: Project, request: BecomePasswordRequest): BecomePasswordAnswer? {
            requests += request
            val (password, remember, only) = answers.pollFirst() ?: return null
            return BecomePasswordAnswer(password.toCharArray(), remember, only)
        }
    }

    private lateinit var become: FakeBecomePrompter
    private lateinit var root: BecomeRoot
    private val variables = HashMap<String, String>()
    private val managerReads = CopyOnWriteArrayList<String>()

    private val passwords: BecomePasswords get() = BecomePasswords.getInstance(project)

    override fun setUp() {
        super.setUp()
        become = FakeBecomePrompter()
        ApplicationManager.getApplication().replaceService(BecomePrompter::class.java, become, testRootDisposable)
        root = BecomeRoot(Files.createDirectories(base.resolve("repos/falcon/ansible")), "falcon")
        val manager = object : PasswordManagerReader {
            override fun read(manager: PasswordManager, reference: String, prompt: MasterPasswordPrompt): ByteArray? {
                managerReads += "${manager.name} $reference"
                return if (reference == "op://Private/sudo/password") "from-1password\n".toByteArray() else null
            }
        }
        passwords.setSourcesForTests(PasswordSafeCredentialStore, manager) { variables[it] }
    }

    override fun tearDown() {
        try {
            passwords.forgetSession()
            passwords.setSourcesForTests(PasswordSafeCredentialStore, PasswordManagers) { EnvironmentUtil.getValue(it) }
        } finally {
            super.tearDown()
        }
    }

    fun testConfiguredSourcesAreReadWithoutAPrompt() {
        val file = Files.writeString(root.path.resolve(".become-pass"), "  from-file\n")
        variables["SUDO_PASS"] = "from-env"
        passwords.store(root, "prod", "from-store".toCharArray())
        val settings = RunnerRootSettings(
            become = mapOf(
                "*" to BecomeSource(VaultSourceKind.PASSWORD_FILE, ".become-pass"),
                "dev" to BecomeSource(VaultSourceKind.ENVIRONMENT, "SUDO_PASS"),
                "prod" to BecomeSource(VaultSourceKind.PASSWORD_SAFE),
                "live" to BecomeSource(VaultSourceKind.ONE_PASSWORD, "op://Private/sudo/password"),
            ),
        )
        assertEquals("from-file", obtained(settings, "stage"))
        assertEquals("from-env", obtained(settings, "dev"))
        assertEquals("from-store", obtained(settings, "prod"))
        assertEquals("from-1password", obtained(settings, "live"))
        assertEquals(listOf("ONE_PASSWORD op://Private/sudo/password"), managerReads)
        assertEmpty(become.requests)

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        become.answers += Triple("typed", RememberChoice.NONE, false)
        assertEquals("an executable file is never run: the user is asked", "typed", obtained(settings, "stage"))
        assertTrue(become.requests.single().error!!.contains(".become-pass"))
    }

    fun testAPromptRemembersAsChosen() {
        val settings = RunnerRootSettings()
        become.answers += Triple("for-prod", RememberChoice.KEYCHAIN, true)
        assertEquals("for-prod", obtained(settings, "prod"))
        assertEquals("prod", become.requests.single().environment)
        assertEquals("remembered for prod only", "for-prod", obtained(settings, "prod"))
        assertTrue(passwords.hasStored(root, "prod"))
        assertFalse(passwords.hasStored(root, RunnerRootSettings.ALL_ENVIRONMENTS))

        become.answers += Triple("everywhere", RememberChoice.SESSION, false)
        assertEquals("everywhere", obtained(settings, "dev"))
        assertEquals("everywhere", obtained(settings, "stage"))
        assertEquals("the environment's own comes first", "for-prod", obtained(settings, "prod"))
        assertEquals(2, become.requests.size)

        secrets.lockAll()
        become.answers += Triple("again", RememberChoice.NONE, false)
        assertEquals("the vault's lock forgets session passwords", "again", obtained(settings, "dev"))
        become.answers += Triple("once more", RememberChoice.NONE, false)
        assertEquals("once more", obtained(settings, "dev"))

        assertEquals(BecomeResult.Cancelled, await { passwords.obtain(root, settings, "dev") })
    }

    fun testAPasswordManagerIsAskedOncePerSession() {
        val settings = RunnerRootSettings(become = mapOf("*" to BecomeSource(VaultSourceKind.ONE_PASSWORD, "op://Private/sudo/password")))
        assertEquals("from-1password", obtained(settings, "dev"))
        assertEquals("from-1password", obtained(settings, "prod"))
        assertEquals("the CLI (and macOS's app data question) runs once", 1, managerReads.size)

        val other = RunnerRootSettings(become = mapOf("*" to BecomeSource(VaultSourceKind.ONE_PASSWORD, "op://Private/other/password")))
        become.answers += Triple("typed", RememberChoice.NONE, false)
        assertEquals("another reference is read on its own", "typed", obtained(other, "dev"))
        assertEquals(2, managerReads.size)

        secrets.lockAll()
        assertEquals("from-1password", obtained(settings, "dev"))
        assertEquals("the vault's lock forgets it", 3, managerReads.size)
    }

    fun testTheDialogDescribesTheSource() {
        val settings = RunnerRootSettings(
            become = mapOf("*" to BecomeSource(VaultSourceKind.BITWARDEN, "sudo"), "prod" to BecomeSource(VaultSourceKind.ENVIRONMENT, "SUDO_PROD")),
        )
        assertEquals("from Bitwarden (all environments)", passwords.describe(settings, "dev"))
        assertEquals("from the variable SUDO_PROD (prod)", passwords.describe(settings, "prod"))
        assertEquals("asked before the run unless remembered", passwords.describe(RunnerRootSettings(), null))
    }

    private fun obtained(settings: RunnerRootSettings, environment: String?): String {
        val result = await { passwords.obtain(root, settings, environment) }
        val password = (result as BecomeResult.Obtained).password
        return String(password).also { password.fill('\u0000') }
    }
}
