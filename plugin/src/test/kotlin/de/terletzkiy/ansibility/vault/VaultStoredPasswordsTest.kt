package de.terletzkiy.ansibility.vault

import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.MasterPasswordPrompt
import de.terletzkiy.ansibility.vault.identity.PasswordManager
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.PasswordManagers
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import java.util.concurrent.CopyOnWriteArrayList

/** Settings › Ansibility › Vault: passwords kept in the IDE password store, and password manager references. */
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
        configure(root, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, "op://Infra/ansible dev/password"))
        val reads = CopyOnWriteArrayList<String>()
        secrets.setPasswordManagersForTests { manager, reference, _ -> reads += "$manager $reference"; VaultVectors.DEV.toByteArray() }

        val identity = VaultStatusService.getInstance(project).config(root).identities.single()
        assertEquals(VaultSourceKind.ONE_PASSWORD, identity.source.kind)
        assertEquals(VaultSourceOrigin.SETTINGS, identity.source.origin)
        assertEmpty("nothing is read before an unlock", reads)

        assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(root) })
        assertEquals(listOf("ONE_PASSWORD op://Infra/ansible dev/password"), reads)
        assertEmpty(prompter.consentRequests)
        assertEmpty(prompter.passwordRequests)
        val result = await { ops.decrypt(location("$falcon/vault.yml", "db_password"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted
        assertEquals("dev only secret", result.plaintext.use { p -> p.read { String(it) } })
    }

    fun testEveryManagerKindIsReadThroughItsCli() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        val cases = listOf(
            Triple(VaultSourceKind.BITWARDEN, "Ansible dev", PasswordManager.BITWARDEN),
            Triple(VaultSourceKind.PROTON_PASS, "pass://Infra/Ansible dev/password", PasswordManager.PROTON_PASS),
            Triple(VaultSourceKind.KEEPASSXC, "secrets/team.kdbx#Ansible/dev", PasswordManager.KEEPASSXC),
        )
        for ((kind, reference, manager) in cases) {
            secrets.lockAll()
            configure(root, ExplicitIdentity("dev", kind, reference))
            val reads = CopyOnWriteArrayList<Pair<PasswordManager, String>>()
            secrets.setPasswordManagersForTests { m, ref, _ -> reads += m to ref; VaultVectors.DEV.toByteArray() }
            val identity = VaultStatusService.getInstance(project).config(root).identities.single()
            assertEquals(kind, identity.source.kind)
            assertEquals("the card shows the reference as configured", reference, identity.source.location)
            assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(root) })
            val expected = if (manager == PasswordManager.KEEPASSXC) "${base.resolve(falcon).resolve("secrets/team.kdbx")}#Ansible/dev" else reference
            assertEquals(listOf(manager to expected), reads)
        }
        assertEmpty(prompter.consentRequests)
    }

    fun testAMasterPasswordIsAskedOnlyInAnInteractiveUnlock() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        configure(root, ExplicitIdentity("dev", VaultSourceKind.KEEPASSXC, "/vaults/team.kdbx#Ansible/dev"))
        val typed = CopyOnWriteArrayList<String>()
        secrets.setPasswordManagersForTests { manager, reference, prompt ->
            val master = prompt.ask(manager, PasswordManagers.keePass(reference)!!.first, retry = false) ?: return@setPasswordManagersForTests null
            typed += String(master)
            master.fill('\u0000')
            VaultVectors.DEV.toByteArray()
        }

        assertFalse(await { secrets.unlock(root, interactive = false) } is VaultUnlockResult.Unlocked)
        assertEmpty(prompter.masterRequests)

        prompter.masterPasswords += "database password"
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(root) })
        assertEquals("KeePassXC", prompter.masterRequests.single().managerName)
        assertEquals("/vaults/team.kdbx", prompter.masterRequests.single().target)
        assertEquals(listOf("database password"), typed)
    }

    fun testLockingAllForgetsTheManagerUnlocks() {
        var forgotten = 0
        secrets.setPasswordManagersForTests(object : PasswordManagerReader {
            override fun read(manager: PasswordManager, reference: String, prompt: MasterPasswordPrompt): ByteArray? = null
            override fun forgetUnlocks() {
                forgotten++
            }
        })
        secrets.lockAll()
        assertEquals(1, forgotten)
    }

    fun testAFailedManagerReadDoesNotUnlock() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        configure(root, ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, "op://Infra/dev/password"))
        secrets.setPasswordManagersForTests { _, _, _ -> null }
        assertFalse(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
    }

    fun testManagerReferences() {
        val op = PasswordManager.ONE_PASSWORD
        assertTrue(PasswordManagers.isReference(op, "op://Infra/ansible/password"))
        assertTrue(PasswordManagers.isReference(op, "op://Infra/ansible/section/field"))
        assertTrue(PasswordManagers.isReference(op, "op://Infra/ansible dev/password"))
        assertFalse(PasswordManagers.isReference(op, "op://Infra"))
        assertFalse(PasswordManagers.isReference(op, "op://Infra/ansible/password "))
        assertFalse(PasswordManagers.isReference(op, "op://Infra/ansible\n/password"))
        assertFalse(PasswordManagers.isReference(op, "Infra/ansible/password"))

        val proton = PasswordManager.PROTON_PASS
        assertTrue(PasswordManagers.isReference(proton, "pass://Production/Database/password"))
        assertFalse("a field is required", PasswordManagers.isReference(proton, "pass://Production/Database"))
        assertFalse(PasswordManagers.isReference(proton, "pass://Production/Database/"))

        assertTrue(PasswordManagers.isReference(PasswordManager.BITWARDEN, "99ee88d2-6046-4ea7-92c2-acac464b1412"))
        assertFalse(PasswordManagers.isReference(PasswordManager.BITWARDEN, " Ansible"))

        assertEquals("~/team.kdbx" to "Ansible/#1 prod", PasswordManagers.keePass("~/team.kdbx#Ansible/#1 prod"))
        assertEquals("db" to "entry", PasswordManagers.keePass("db#entry"))
        assertNull(PasswordManagers.keePass("team.kdbx"))
        assertNull(PasswordManagers.keePass("team.kdbx#"))
    }

    private fun configure(root: de.terletzkiy.ansibility.api.AnsibleRoot, identity: ExplicitIdentity) {
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) { VaultRootSettings(identities = listOf(identity)) }
    }
}
