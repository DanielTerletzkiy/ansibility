package de.terletzkiy.ansibility.vault

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultEnvelopeKind
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultStatus
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * [VaultStatusService]: header facts, the candidate id and its lock state, and the verification cache, for inline
 * values (from the key, the value or the tag) and whole-file vaults. It never decrypts and never reads a secret.
 */
class VaultStatusServiceTest : VaultTestCase() {
    private val status: VaultStatusService get() = VaultStatusService.getInstance(project)

    private fun falcon(): String {
        val falcon = projectRoot("falcon")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        val labelled = VaultVectors.encrypt("ANSIBILITY-SENTINEL-51", VaultVectors.PW1, "new")
        write("$falcon/files/ssl/star.autogen.key", labelled.formatBytes())
        write(
            "$falcon/group_vars/all/vault.yml",
            VaultVectors.raw("v01") + "plain: \$ANSIBLE_VAULT;1.1;AES256\nother: 42\nlist:\n  - !vault |\n" +
                VaultVectors.raw("v01").lines().drop(1).filter { it.isNotBlank() }.joinToString("\n", postfix = "\n") { "  $it" },
        )
        return falcon
    }

    private fun keyValue(relative: String, key: String): YAMLKeyValue = runReadActionBlocking {
        val file = PsiManager.getInstance(project).findFile(vf(relative)) as YAMLFile
        file.documents.single().topLevelValue!!.children.filterIsInstance<YAMLKeyValue>().single { it.keyText == key }
    }

    private fun statusOf(location: SourceLocation): VaultStatus? = runReadActionBlocking { status.status(location) }

    fun testInlineValueFromKeyValueAndTag() {
        val falcon = falcon()
        root(falcon)
        val greeting = keyValue("$falcon/group_vars/all/vault.yml", "greeting")
        val fromKey = runReadActionBlocking { status.status(greeting.key!!)!! }
        val fromValue = runReadActionBlocking { status.status(greeting.value!!)!! }
        val fromTag = runReadActionBlocking { status.status(greeting.value!!.firstChild)!! }
        for (it in listOf(fromKey, fromValue, fromTag)) {
            assertEquals(VaultEnvelopeKind.INLINE, it.kind)
            assertEquals("1.1", it.header.version)
            assertEquals("AES256", it.header.cipher)
            assertNull(it.header.label)
            assertEquals("default", it.identity!!.label)
            assertEquals(VaultLockState.LOCKED, it.lockState)
            assertFalse(it.verified)
            assertEquals(0..15, it.plaintextLength)
        }
        assertNull("an untagged \$ANSIBLE_VAULT string is no vault", runReadActionBlocking { status.status(keyValue("$falcon/group_vars/all/vault.yml", "plain")) })
        assertNull(runReadActionBlocking { status.status(keyValue("$falcon/group_vars/all/vault.yml", "other")) })
        assertNotNull("a sequence item", statusOf(location("$falcon/group_vars/all/vault.yml", "list").let { SourceLocation(it.file, it.offset + 12) }))
        assertNotNull(statusOf(location("$falcon/group_vars/all/vault.yml", "greeting")))
    }

    fun testWholeFileVault() {
        val falcon = falcon()
        root(falcon)
        val file = vf("$falcon/files/ssl/star.autogen.key")
        val fromFile = runReadActionBlocking { status.status(PsiManager.getInstance(project).findFile(file)!!)!! }
        val fromOffset = statusOf(SourceLocation(file, 40))!!
        for (it in listOf(fromFile, fromOffset)) {
            assertEquals(VaultEnvelopeKind.FILE, it.kind)
            assertEquals("1.2", it.header.version)
            assertEquals("new", it.header.label)
            assertEquals("with vault_id_match off, default is a candidate for label new", "default", it.identity!!.label)
            assertEquals(VaultLockState.LOCKED, it.lockState)
        }
    }

    fun testStatusTurnsUnlockedAndVerifiedAfterAnAction() {
        val falcon = falcon()
        val root = root(falcon)
        val greeting = location("$falcon/group_vars/all/vault.yml", "greeting")
        assertTrue(await { VaultOperations.getInstance(project).unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals(VaultLockState.UNLOCKED, statusOf(greeting)!!.lockState)
        assertFalse("unlocking alone verifies nothing", statusOf(greeting)!!.verified)
        await { VaultOperations.getInstance(project).decrypt(greeting, VaultPurpose.REVEAL) }.let { (it as de.terletzkiy.ansibility.api.VaultDecryptResult.Decrypted).plaintext.close() }
        assertTrue(statusOf(greeting)!!.verified)
        assertEquals("default", statusOf(greeting)!!.decryptsWith)
    }

    fun testStatusAndConfigNeverDecryptOrReadSecrets() {
        val falcon = falcon()
        val root = root(falcon)
        val attempts = crypto.decryptAttempts
        val greeting = location("$falcon/group_vars/all/vault.yml", "greeting")
        val key = SourceLocation(vf("$falcon/files/ssl/star.autogen.key"), 0)
        repeat(20) {
            statusOf(greeting)
            statusOf(key)
            status.config(root)
        }
        assertEquals(attempts, crypto.decryptAttempts)
        assertEmpty(access.secretReads)
        assertEmpty(prompter.consentRequests)
        assertEmpty(prompter.passwordRequests)
    }

    fun testMalformedBodyStillReportsItsHeader() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", "broken: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;dev\n  zz\n")
        root(falcon)
        val broken = statusOf(location("$falcon/vault.yml", "broken"))!!
        assertEquals("dev", broken.header.label)
        assertNull(broken.plaintextLength)
        assertFalse(broken.verified)
    }

    fun testOutsideAnyRootThereIsNoIdentity() {
        write("loose/vault.yml", VaultVectors.raw("v01"))
        refresh()
        val loose = statusOf(location("loose/vault.yml", "greeting"))!!
        assertEquals(VaultLockState.NO_IDENTITY, loose.lockState)
        assertNull(loose.identity)
    }

    fun testConfigReportsTheQuirkAndTheEncryptIdentity() {
        val falcon = projectRoot("falcon", "[defaults]\nvault_id_match = false\nvault_encrypt_identity = prod\nvault_identity = team\n")
        val config = status.config(root(falcon))
        assertEquals("false", config.idMatchRaw)
        assertTrue(config.idMatch)
        assertEquals("prod", config.encryptIdentity)
        assertEquals("team", config.defaultIdentity)
        assertEquals(listOf("team"), config.identities.map { it.label })
        assertFalse(config.anyUnlocked)
    }
}
