package de.terletzkiy.ansibility.vault

import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.OnePasswordCli
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import java.util.concurrent.CopyOnWriteArrayList

/** Settings › Ansibility › Vault: passwords kept in the IDE password store, and 1Password references. */
class VaultStoredPasswordsTest : VaultTestCase() {
    private val ops: VaultOperations get() = VaultOperations.getInstance(project)

    fun testAStoredPasswordUnlocksAnExplicitIdWithoutAPrompt() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v03"))
        val root = root(falcon)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("prod", VaultSourceKind.PASSWORD_SAFE)))
        }
        assertFalse(secrets.hasStoredPassword(root, "prod"))

        secrets.storePassword(root, "prod", VaultVectors.PROD.toCharArray())
        assertTrue(secrets.hasStoredPassword(root, "prod"))
        assertEquals(VaultUnlockResult.Unlocked(listOf("prod")), await { ops.unlock(root) })
        assertEmpty(prompter.passwordRequests)
        assertEmpty(prompter.consentRequests)

        secrets.storePassword(root, "prod", null)
        assertFalse(secrets.hasStoredPassword(root, "prod"))
    }

    fun testAStoredPasswordWithoutAnExplicitIdIsDiscovered() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v03"))
        val root = root(falcon)
        secrets.storePassword(root, "prod", VaultVectors.PROD.toCharArray())

        val identity = VaultStatusService.getInstance(project).config(root).identities.single { it.label == "prod" }
        assertEquals(VaultSourceKind.PASSWORD_SAFE, identity.source.kind)
        assertEquals(VaultUnlockResult.Unlocked(listOf("prod")), await { ops.unlock(root) })
        assertEmpty(prompter.passwordRequests)
    }

    fun testAStoredPasswordGoesToTheEntryAnExplicitIdNames() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v03"))
        val root = root(falcon)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("prod", VaultSourceKind.PASSWORD_SAFE, "team#prod")))
        }
        secrets.storePassword(root, "prod", VaultVectors.PROD.toCharArray())
        assertTrue(secrets.hasStoredPassword(root, "prod"))
        assertEquals(VaultUnlockResult.Unlocked(listOf("prod")), await { ops.unlock(root) })
        assertEmpty(prompter.passwordRequests)
    }

    fun testAOnePasswordReferenceUnlocksWithoutAConsentOrPrompt() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, "op://Infra/ansible dev/password")))
        }
        val reads = CopyOnWriteArrayList<String>()
        secrets.setOnePasswordForTests { reference -> reads += reference; VaultVectors.DEV.toByteArray() }

        val identity = VaultStatusService.getInstance(project).config(root).identities.single()
        assertEquals(VaultSourceKind.ONE_PASSWORD, identity.source.kind)
        assertEquals(VaultSourceOrigin.SETTINGS, identity.source.origin)
        assertEmpty("nothing is read before an unlock", reads)

        assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(root) })
        assertEquals(listOf("op://Infra/ansible dev/password"), reads)
        assertEmpty(prompter.consentRequests)
        assertEmpty(prompter.passwordRequests)
        val result = await { ops.decrypt(location("$falcon/vault.yml", "db_password"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted
        assertEquals("dev only secret", result.plaintext.use { p -> p.read { String(it) } })
    }

    fun testAFailedOnePasswordReadDoesNotUnlock() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, "op://Infra/dev/password")))
        }
        secrets.setOnePasswordForTests { null }
        assertFalse(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
    }

    fun testOnePasswordReferences() {
        assertTrue(OnePasswordCli.isReference("op://Infra/ansible/password"))
        assertTrue(OnePasswordCli.isReference("op://Infra/ansible/section/field"))
        assertFalse(OnePasswordCli.isReference("op://Infra"))
        assertTrue(OnePasswordCli.isReference("op://Infra/ansible dev/password"))
        assertFalse(OnePasswordCli.isReference("op://Infra/ansible/password "))
        assertFalse(OnePasswordCli.isReference("op://Infra/ansible\n/password"))
        assertFalse(OnePasswordCli.isReference("Infra/ansible/password"))
    }
}
