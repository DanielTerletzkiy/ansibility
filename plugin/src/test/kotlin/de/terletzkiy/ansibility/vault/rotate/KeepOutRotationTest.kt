package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.inspections.vault.FakeStatusLookup
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.exclusion.FileSetRebuilds
import de.terletzkiy.ansibility.vault.exclusion.IndexExclusionPrompts
import de.terletzkiy.ansibility.vault.exclusion.IndexExclusionSettings
import de.terletzkiy.ansibility.vault.exclusion.KeepOutOfIndexes
import de.terletzkiy.ansibility.vault.exclusion.PasswordFileReindex
import de.terletzkiy.ansibility.vault.monitor.Monitor
import de.terletzkiy.ansibility.vault.monitor.SecretCategory
import de.terletzkiy.ansibility.vault.monitor.SecretFix
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import java.nio.file.Files

/**
 * R30 review (adopted as a product test by the R30 review fixes): the Vault tab's "Keep Out of the IDE's Indexes" used
 * to add a password file's NAME as an exclusion pattern, which the platform also matches against folder names. A
 * password file named after an environment (`secrets/prod`, as a `vault_identity_list` of `prod@secrets/prod` reads
 * naturally) then took every `prod` folder out of the project, and the rotation left their vault files on the old
 * password. Since decision 1 the fix adds the file's exact path, so only that file is kept out.
 */
class KeepOutRotationTest : RotationTestCase() {
    private lateinit var rebuilds: FileSetRebuilds
    private val exclusions: IndexExclusionSettings get() = IndexExclusionSettings.getInstance()
    private val prodVault: String get() = "$root/group_vars/prod/vault.yml"

    private class Later : IndexExclusionPrompts {
        override fun askReopen(project: Project, names: List<String>): Boolean = false

        override fun reopen(project: Project) = Unit
    }

    override fun setUp() {
        super.setUp()
        exclusions.resetForTests()
        rebuilds = FileSetRebuilds(project)
        ApplicationManager.getApplication().replaceService(IndexExclusionPrompts::class.java, Later(), testRootDisposable)
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(FakeStatusLookup(TrackedStatus.IGNORED)), testRootDisposable)
    }

    override fun tearDown() {
        try {
            exclusions.resetForTests()
            rebuilds.close()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testKeepOutOfAPasswordFileNamedLikeAnEnvironmentDoesNotLoseThatEnvironmentsVaultFiles() {
        Files.delete(base.resolve("$root/.vault-pass"))
        write("$root/ansible.cfg", "[defaults]\nvault_password_file = secrets/prod\n")
        write("$root/secrets/prod", "${VaultVectors.PW1}\n")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        write(prodVault, VaultVectors.inline("vault_prod_password", VaultVectors.encrypt("Prod0Old0Value01", VaultVectors.PW1)))
        ready()

        // The Vault tab lists the password file and offers its fix; the tab action passes the file names.
        val monitor = Monitor(project, testRootDisposable).start()
        val finding = monitor.await { s -> s.findings.any { it.category == SecretCategory.INDEXED_PASSWORD_FILES } }
            .findings.single { it.category == SecretCategory.INDEXED_PASSWORD_FILES }
        assertEquals("secrets/prod", finding.path)
        assertEquals(SecretFix.KEEP_OUT_OF_INDEXES, finding.fix)
        // R30 review fixes, decision 1: the exact path of the file, not its name.
        assertEquals(listOf(finding.file.canonicalPath), KeepOutOfIndexes.perform(project, listOf(finding.file)))

        // The project is reopened: file sets built again, then the start-up re-index.
        rebuilds.rebuild()
        await { PasswordFileReindex.runIfDue(project) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        rotate()

        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        // R30 review fixes, decision 1: the prod vault file stays in the project, so the rotation counts and rotates it too.
        assertEquals("the rotation summary", 2, service.lastSummary?.values)
        assertNull("not left on the old password", valueOf(prodVault, "vault_prod_password", VaultVectors.PW1))
        assertEquals(
            "a vault file below a folder named like the password file is rotated too",
            "Prod0Old0Value01",
            valueOf(prodVault, "vault_prod_password", newPassword),
        )
    }
}
