package de.terletzkiy.ansibility.vault

import com.intellij.openapi.diagnostic.Logger
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.semantics.vault.LabelledSecret
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.vault.crypto.OwnedPlaintext
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import de.terletzkiy.ansibility.vault.secrets.SecretLease
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.secrets.VaultPasswordAnswer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DEV.md rule 11 and F7.13: secret holders print `***`, and vault code logs only the file, the YAML key and the error
 * class. The log is captured with a logger factory installed for the test (a custom appender): every line and every
 * throwable that reaches any logger while the flows run is searched for the synthetic secrets and sentinels.
 */
class VaultSecretHygieneTest : VaultTestCase() {
    private val ops: VaultOperations get() = VaultOperations.getInstance(project)

    fun testSecretHoldersNeverPrintTheirContent() {
        val secret = SecretBytes.of("ANSIBILITY-SENTINEL-61".toByteArray())
        val holders = listOf(
            OwnedPlaintext("ANSIBILITY-SENTINEL-62".toByteArray()),
            VaultPasswordAnswer("ANSIBILITY-SENTINEL-63".toCharArray(), RememberChoice.KEYCHAIN),
            SecretLease(listOf(LabelledSecret("default", secret))),
            VaultDecryptResult.Decrypted("default", OwnedPlaintext("ANSIBILITY-SENTINEL-64".toByteArray())),
            secret,
        )
        for (holder in holders) {
            val text = holder.toString()
            assertFalse(text, "SENTINEL" in text)
            assertTrue(text, "***" in text)
        }
        assertEquals("***", OwnedPlaintext(ByteArray(1)).toString())
        assertFalse("not a data class", OwnedPlaintext::class.isData)
        assertFalse(VaultPasswordAnswer::class.isData)
        assertFalse(SecretLease::class.isData)
    }

    fun testTheLogCarriesOnlyFileKeyAndErrorClass() {
        val falcon = projectRoot("falcon", "[defaults]\nvault_identity_list = dev@dev.pw\n")
        write("$falcon/.env.local", "SSH_JUMP_HOST_USER=ANSIBILITY-SENTINEL-71\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\nTOKEN=ANSIBILITY-SENTINEL-72\n")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/dev.pw", "${VaultVectors.DEV}\n")
        write("$falcon/vault.yml", VaultVectors.inline("good", VaultVectors.encrypt("ANSIBILITY-SENTINEL-74", VaultVectors.PW1)) +
            VaultVectors.inline("foreign", VaultVectors.encrypt("ANSIBILITY-SENTINEL-75", VaultVectors.PROD, "prod")) +
            "broken: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  ANSIBILITY-SENTINEL-76\n")
        // A vaulted password file that no earlier secret decrypts: only the SecretLoadFailure class is logged.
        val heron = projectRoot("heron", "[defaults]\nvault_identity_list = broken@vaulted.pw\n")
        write("$heron/vaulted.pw", VaultVectors.encrypt("ANSIBILITY-SENTINEL-73", "runtime-only-pass").format())
        val thrush = projectRoot("thrush")
        write("$thrush/vault.yml", VaultVectors.raw("v01"))

        val captured = capture {
            val root = root(falcon)
            assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
            (await { ops.decrypt(location("$falcon/vault.yml", "good"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted).plaintext.close()
            assertEquals(VaultFailure.WRONG_SECRET, (await { ops.decrypt(location("$falcon/vault.yml", "foreign"), VaultPurpose.REVEAL) } as VaultDecryptResult.Failed).failure)
            assertEquals(VaultFailure.FORMAT, (await { ops.decrypt(location("$falcon/vault.yml", "broken"), VaultPurpose.REVEAL) } as VaultDecryptResult.Failed).failure)
            assertTrue(await { ops.encrypt(root, "ANSIBILITY-SENTINEL-77".toByteArray(), "dev", null) } is VaultEncryptResult.Encrypted)
            assertEquals(VaultUnlockResult.Failed(VaultFailure.CANCELLED), await { ops.unlock(root(heron)) })
            ops.lockAll()

            // The prompt path: a wrong password, then a cancel.
            prompter.passwords += "ANSIBILITY-SENTINEL-78" to RememberChoice.KEYCHAIN
            val envelope = VaultVectors.envelope(VaultVectors.raw("v01").substringAfter("|\n").trimIndent() + "\n")
            assertEquals(VaultUnlockResult.Failed(VaultFailure.CANCELLED), await { secrets.unlock(root(thrush), verify = envelope) })

            // A changed password file whose new consent is declined.
            write("$falcon/.vault-pass", "test-pass-X\n")
            prompter.onConsent = { VaultConsentDecision.NotNow }
            assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(root(falcon)) })
        }

        val forbidden = listOf(VaultVectors.PW1, VaultVectors.DEV, VaultVectors.PROD, "test-pass-X", "runtime-only-pass", "SENTINEL")
        for (line in captured) {
            for (secret in forbidden) assertFalse("log line leaks '$secret': $line", secret in line)
        }
        val vaultLines = captured.filter { it.startsWith("vault ") }
        for (line in vaultLines) assertFalse("no envelope body: $line", Regex("[0-9a-fA-F]{32,}").containsMatchIn(line))
        assertTrue("the failures are logged: $vaultLines", vaultLines.any { "WRONG_SECRET" in it && "vault.yml" in it && "key=foreign" in it })
        assertTrue(vaultLines.any { "FORMAT" in it && "key=broken" in it })
        assertTrue(vaultLines.any { "VAULTED_NOT_DECRYPTED" in it && "vaulted.pw" in it })
        assertTrue(vaultLines.any { "WRONG_SECRET" in it && "key=default" in it })
        assertTrue(vaultLines.any { "CONSENT_DECLINED" in it })
    }

    /** Runs [block] with every logger (vault and platform) writing into the returned list as well. */
    private fun capture(block: () -> Unit): List<String> {
        val lines = CopyOnWriteArrayList<String>()
        val previous = Logger.getFactory()
        val sink = Sink(lines)
        Logger.setFactory(CapturingFactory(previous, sink))
        try {
            block()
        } finally {
            sink.closed = true
            Logger.setFactory(previous)
        }
        return lines
    }

    private class Sink(val lines: MutableList<String>) {
        @Volatile
        var closed = false

        fun record(message: String?, error: Throwable?, details: Array<out String> = emptyArray()) {
            if (closed) return
            message?.let(lines::add)
            details.forEach(lines::add)
            var cause = error
            while (cause != null) {
                lines += cause.toString()
                cause.stackTrace.forEach { lines += it.toString() }
                cause = cause.cause
            }
        }
    }

    /** A factory whose loggers record everything (debug included) and pass it on to the previous factory's logger. */
    private class CapturingFactory(private val delegate: Logger.Factory, private val sink: Sink) : Logger.Factory {
        override fun getLoggerInstance(category: String): Logger = CapturingLogger(delegate.getLoggerInstance(category), sink)
    }

    private class CapturingLogger(private val delegate: Logger, private val sink: Sink) : Logger() {
        override fun isDebugEnabled(): Boolean = !sink.closed || delegate.isDebugEnabled

        override fun debug(message: String?, t: Throwable?) {
            sink.record(message, t)
            if (delegate.isDebugEnabled) delegate.debug(message, t)
        }

        override fun info(message: String?, t: Throwable?) {
            sink.record(message, t)
            delegate.info(message, t)
        }

        override fun warn(message: String?, t: Throwable?) {
            sink.record(message, t)
            delegate.warn(message, t)
        }

        override fun error(message: String?, t: Throwable?, vararg details: String) {
            sink.record(message, t, details)
            delegate.error(message, t, *details)
        }
    }
}
