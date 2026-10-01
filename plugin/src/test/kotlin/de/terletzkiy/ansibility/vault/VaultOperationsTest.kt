package de.terletzkiy.ansibility.vault

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPlaintext
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [VaultOperations] on synthetic vectors: decrypt in Ansible's order, encrypt with Ansible's id choice, the
 * verification cache, caller-owned plaintext, the D31 refusal, malformed envelopes and Reveal.
 */
class VaultOperationsTest : VaultTestCase() {
    private val ops: VaultOperations get() = VaultOperations.getInstance(project)

    /** A root with `default` (`.vault-pass`), `dev` and `prod` and one file per vector. */
    private fun multiVault(cfg: String = "[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\n"): String {
        val falcon = projectRoot("falcon", cfg)
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/dev.pw", "${VaultVectors.DEV}\n")
        write("$falcon/prod.pw", "${VaultVectors.PROD}\n")
        for (vector in listOf("v01", "v02", "v03", "v13", "v14", "v16")) write("$falcon/vars/$vector.yml", VaultVectors.raw(vector))
        return falcon
    }

    private fun decrypt(location: SourceLocation, purpose: VaultPurpose = VaultPurpose.REVEAL): VaultDecryptResult =
        await { ops.decrypt(location, purpose) }

    private fun text(result: VaultDecryptResult): String =
        (result as VaultDecryptResult.Decrypted).plaintext.use { plaintext -> plaintext.read { String(it, Charsets.UTF_8) } }

    fun testDecryptsTheSyntheticVectorsWithTheRightIds() {
        val falcon = multiVault()
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev", "prod", "default")), await { ops.unlock(root(falcon)) })

        val cases = mapOf(
            "v01" to ("greeting" to ("default" to "hello world")),
            "v02" to ("db_password" to ("dev" to "dev only secret")),
            "v03" to ("db_password" to ("prod" to "prod secret")),
            "v13" to ("mislabeled" to ("prod" to "label lies")),
            "v14" to ("unlabeled_prod" to ("prod" to "unlabeled prod")),
        )
        for ((vector, expected) in cases) {
            val (key, outcome) = expected
            val result = decrypt(location("$falcon/vars/$vector.yml", key))
            assertEquals(vector, outcome.first, (result as VaultDecryptResult.Decrypted).identity)
            assertEquals(vector, outcome.second, text(result))
        }
        // v16's id `ops` is not configured: with vault_id_match off every secret is tried, none works.
        val v16 = decrypt(location("$falcon/vars/v16.yml", "utf8_pw")) as VaultDecryptResult.Failed
        assertEquals(VaultFailure.WRONG_SECRET, v16.failure)
        assertEquals(listOf("dev", "prod", "default"), v16.tried)
    }

    fun testEncryptChoosesTheIdLikeAnsible() {
        val single = projectRoot("single")
        write("$single/.vault-pass", "${VaultVectors.PW1}\n")
        val singleRoot = root(single)
        assertTrue(await { ops.unlock(singleRoot) } is VaultUnlockResult.Unlocked)
        val plain = "ANSIBILITY-SENTINEL-41".toByteArray()
        val one = await { ops.encrypt(singleRoot, plain, null, null) } as VaultEncryptResult.Encrypted
        assertEquals("default", one.identity)
        assertEquals("1.1", one.header.version)
        assertNull(one.header.label)
        assertTrue(one.envelope.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertTrue(one.envelope.lines().drop(1).filter { it.isNotEmpty() }.all { it.length <= 80 && it == it.lowercase() })
        assertEquals("ANSIBILITY-SENTINEL-41", decryptWith(one.envelope, VaultVectors.PW1))
        assertEquals("the caller's array is untouched", "ANSIBILITY-SENTINEL-41", String(plain))

        val falcon = multiVault()
        val root = root(falcon)
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals(VaultEncryptResult.Failed(VaultFailure.ENCRYPT_IDENTITY_REQUIRED), await { ops.encrypt(root, plain, null, null) })
        val dev = await { ops.encrypt(root, plain, "dev", null) } as VaultEncryptResult.Encrypted
        assertTrue(dev.envelope.startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        assertEquals("ANSIBILITY-SENTINEL-41", decryptWith(dev.envelope, VaultVectors.DEV))
        assertEquals(VaultEncryptResult.Failed(VaultFailure.NO_IDENTITY), await { ops.encrypt(root, plain, "qa", null) })
        assertEquals(VaultEncryptResult.Failed(VaultFailure.FORMAT), await { ops.encrypt(root, dev.envelope.toByteArray(), "dev", null) })

        // The env → id mapping chooses for files under environments/<env>/.
        write("$falcon/environments/prod/group_vars/all/vault.yml", "x: 1\n")
        val rescanned = root(falcon)
        val target = vf("$falcon/environments/prod/group_vars/all/vault.yml")
        VaultProjectSettings.getInstance(project).update(registry.rootKey(rescanned)) { VaultRootSettings(environmentIdentities = mapOf("prod" to "prod")) }
        val mapped = await { ops.encrypt(rescanned, plain, null, target) } as VaultEncryptResult.Encrypted
        assertEquals("prod", mapped.identity)
        assertEquals("prod", mapped.header.label)

        ops.lockAll()
        assertEquals(VaultEncryptResult.Failed(VaultFailure.LOCKED), await { ops.encrypt(root, plain, "dev", null) })
    }

    fun testVaultEncryptIdentityFromAnsibleCfgWins() {
        val falcon = multiVault("[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\nvault_encrypt_identity = prod\n")
        val root = root(falcon)
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        val result = await { ops.encrypt(root, "x".toByteArray(), null, null) } as VaultEncryptResult.Encrypted
        assertEquals("prod", result.identity)
        assertTrue(result.envelope.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
    }

    fun testVerificationCacheIsFilledByActionsAndClearedOnLock() {
        val falcon = projectRoot("falcon", "[defaults]\nvault_identity_list = dev@dev.pw\n")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/dev.pw", "${VaultVectors.DEV}\n")
        write("$falcon/vault.yml", VaultVectors.raw("v01"))
        val root = root(falcon)
        val greeting = location("$falcon/vault.yml", "greeting")
        val status = { runReadActionBlocking { VaultStatusService.getInstance(project).status(greeting)!! } }
        assertFalse(status().verified)

        assertTrue(await { ops.unlock(root, "dev") } is VaultUnlockResult.Unlocked)
        val locked = decrypt(greeting) as VaultDecryptResult.Failed
        assertEquals("default may still decrypt it", VaultFailure.LOCKED, locked.failure)
        assertEquals(listOf("dev"), locked.tried)
        assertTrue("no unlocked id decrypts it (ANS-V104)", status().verified)
        assertNull(status().decryptsWith)

        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertFalse("an unlock drops the negative entry", status().verified)
        assertEquals("hello world", text(decrypt(greeting)))
        assertEquals("default", status().decryptsWith)

        ops.lockAll()
        assertFalse(status().verified)
    }

    fun testPlaintextIsOwnedByTheCallerAndZeroedOnClose() {
        val falcon = multiVault()
        assertTrue(await { ops.unlock(root(falcon)) } is VaultUnlockResult.Unlocked)
        val greeting = location("$falcon/vars/v01.yml", "greeting")
        val result = decrypt(greeting) as VaultDecryptResult.Decrypted
        val plaintext = result.plaintext
        var seen: ByteArray? = null
        assertEquals(11, plaintext.size)
        plaintext.read { seen = it }
        plaintext.close()
        assertTrue("closing zeroes the bytes", seen!!.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { plaintext.read { it.size } }
        plaintext.close()
        assertEquals("***", plaintext.toString())
        assertEquals("Decrypted(default, ***)", result.toString())
        assertEquals("the cached copy is independent", "hello world", text(decrypt(greeting)))
    }

    fun testAnalysisNeedsTheRootsOptIn() {
        val falcon = multiVault()
        val root = root(falcon)
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        val greeting = location("$falcon/vars/v01.yml", "greeting")
        assertEquals(VaultFailure.ANALYSIS_DISABLED, (decrypt(greeting, VaultPurpose.ANALYSIS) as VaultDecryptResult.Failed).failure)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) { it.copy(analyzeDecryptedValues = true) }
        assertEquals("hello world", text(decrypt(greeting, VaultPurpose.ANALYSIS)))
    }

    fun testMalformedEnvelopesAreFormatErrors() {
        val falcon = multiVault()
        val envelope = VaultVectors.encrypt("x", VaultVectors.PW1).formatLines()
        write("$falcon/bad.yml", buildString {
            append("folded: !vault >\n")
            envelope.forEach { append("  ").append(it).append('\n') }
            append("cipher: !vault |\n")
            append("  ").append(envelope.first().replace("AES256", "aes256")).append('\n')
            envelope.drop(1).forEach { append("  ").append(it).append('\n') }
            append("plain: not a vault\n")
        })
        assertTrue(await { ops.unlock(root(falcon)) } is VaultUnlockResult.Unlocked)
        assertEquals(VaultFailure.UNKNOWN_CIPHER, (decrypt(location("$falcon/bad.yml", "folded")) as VaultDecryptResult.Failed).failure)
        assertEquals(VaultFailure.UNKNOWN_CIPHER, (decrypt(location("$falcon/bad.yml", "cipher")) as VaultDecryptResult.Failed).failure)
        assertEquals(VaultFailure.FORMAT, (decrypt(location("$falcon/bad.yml", "plain")) as VaultDecryptResult.Failed).failure)
    }

    fun testRevealUnlocksLazilyAndHandsThePlaintextToThePresenter() {
        val falcon = multiVault()
        root(falcon)
        val presenter = RecordingPresenter()
        ExtensionTestUtil.maskExtensions(VaultRevealPresenter.EP_NAME, listOf(presenter), testRootDisposable)
        val greeting = location("$falcon/vars/v01.yml", "greeting")

        ops.reveal(greeting, null)
        PlatformTestUtil.waitWithEventsDispatching("no reveal", { presenter.shown.isNotEmpty() || presenter.failures.isNotEmpty() }, 30)
        assertEmpty(presenter.failures)
        assertEquals(listOf("default:hello world"), presenter.shown)
        assertEquals("the first action asked for the consent", 1, prompter.consentRequests.size)
        assertTrue("the presenter closed it", presenter.plaintexts.single().size == 0)
    }

    fun testRevealWithoutAPresenterDecryptsNothing() {
        val falcon = multiVault()
        root(falcon)
        ExtensionTestUtil.maskExtensions(VaultRevealPresenter.EP_NAME, emptyList(), testRootDisposable)
        val attempts = crypto.decryptAttempts
        ops.reveal(location("$falcon/vars/v01.yml", "greeting"), null)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(attempts, crypto.decryptAttempts)
        assertEmpty(prompter.consentRequests)
        assertEmpty(access.secretReads)
    }

    private fun decryptWith(envelope: String, password: String): String =
        SecretBytes.of(password.toByteArray()).use { String(VaultAes256.decrypt(VaultVectors.envelope(envelope), it)!!) }

    /** Records what Reveal shows and closes the plaintext, as the popup would. */
    private class RecordingPresenter : VaultRevealPresenter {
        val shown = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<VaultFailure>()
        val plaintexts = CopyOnWriteArrayList<VaultPlaintext>()

        override fun present(project: Project, location: SourceLocation, editor: Editor?, identity: String, plaintext: VaultPlaintext) {
            plaintexts += plaintext
            shown += identity + ":" + plaintext.read { String(it) }
            plaintext.close()
        }

        override fun failed(project: Project, location: SourceLocation, editor: Editor?, failure: VaultFailure) {
            failures += failure
        }
    }
}
