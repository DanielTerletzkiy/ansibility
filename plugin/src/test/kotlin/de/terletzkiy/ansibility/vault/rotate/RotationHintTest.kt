package de.terletzkiy.ansibility.vault.rotate

import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import java.security.SecureRandom

/**
 * Plan amendment R29, X129: an explicit operation that fails with a wrong secret for an envelope whose id is unlocked,
 * while that secret opened other envelopes of the id in the root, suggests that the password was rotated elsewhere;
 * once per root and id; never after a rotation in this IDE; nothing is decrypted for it.
 */
class RotationHintTest : RotationTestCase() {
    private val hints: RotationHints get() = RotationHints.getInstance(project)

    /** A password a teammate rotated to (synthetic, generated now). */
    private val teammate: String = NewPassword.generate(SecureRandom()).use { String(it.chars()) }

    private fun decrypt(key: String, purpose: VaultPurpose = VaultPurpose.REVEAL): VaultDecryptResult =
        await { VaultOperations.getInstance(project).decrypt(location(vaultPath, key), purpose) }.also { (it as? VaultDecryptResult.Decrypted)?.plaintext?.close() }

    private fun failure(result: VaultDecryptResult): VaultFailure? = (result as? VaultDecryptResult.Failed)?.failure

    fun testAWrongSecretThatWorksElsewhereSuggestsARotationOncePerRootAndId() {
        write(
            vaultPath,
            VaultVectors.inline("vault_a_password", VaultVectors.encrypt("A0Old0Value000001", VaultVectors.PW1)) +
                VaultVectors.inline("vault_b_password", VaultVectors.encrypt("B0New0Value000001", teammate)),
        )
        ready()
        assertTrue(await { secrets.unlock(root(root)) } is VaultUnlockResult.Unlocked)

        assertEquals(VaultFailure.WRONG_SECRET, failure(decrypt("vault_b_password")))
        assertEmpty("the secret has not opened anything yet", hints.drainForTests())
        assertTrue(decrypt("vault_a_password") is VaultDecryptResult.Decrypted)
        val attempts = VaultCrypto.getInstance(project).decryptAttempts

        assertEquals(VaultFailure.WRONG_SECRET, failure(decrypt("vault_b_password")))
        val shown = hints.drainForTests().single()
        assertTrue(shown, shown.startsWith("The vault password for 'default' may have been rotated. Update "))
        assertTrue(shown, shown.endsWith(".vault-pass."))
        assertEquals("nothing extra was decrypted for the hint", attempts + 1, VaultCrypto.getInstance(project).decryptAttempts)

        assertEquals(VaultFailure.WRONG_SECRET, failure(decrypt("vault_b_password", VaultPurpose.COPY)))
        assertEmpty("once per root and id", hints.drainForTests())
    }

    fun testOnlyExplicitOperationsShowIt() {
        write(
            vaultPath,
            VaultVectors.inline("vault_a_password", VaultVectors.encrypt("A0Old0Value000001", VaultVectors.PW1)) +
                VaultVectors.inline("vault_b_password", VaultVectors.encrypt("B0New0Value000001", teammate)),
        )
        ready()
        assertTrue(await { secrets.unlock(root(root)) } is VaultUnlockResult.Unlocked)
        assertTrue(decrypt("vault_a_password") is VaultDecryptResult.Decrypted)

        assertEquals(VaultFailure.WRONG_SECRET, failure(decrypt("vault_b_password", VaultPurpose.TEST)))
        assertEquals(VaultFailure.WRONG_SECRET, failure(decrypt("vault_b_password", VaultPurpose.RENDER_PREVIEW)))
        assertEmpty(hints.drainForTests())
        assertEquals(VaultFailure.WRONG_SECRET, failure(decrypt("vault_b_password", VaultPurpose.EDIT)))
        assertEquals(1, hints.drainForTests().size)
    }

    fun testARotationInThisIdeDoesNotTriggerIt() {
        write(
            vaultPath,
            VaultVectors.inline("vault_a_password", VaultVectors.encrypt("A0Old0Value000001", VaultVectors.PW1)) +
                // Left alone by the rotation: the current password does not open it.
                VaultVectors.inline("vault_b_password", VaultVectors.encrypt("B0New0Value000001", teammate)),
        )
        ready()

        rotate()

        assertTrue(rotationPrompts.reviews.single().leftAlone.single().contains("vault_b_password"))
        assertTrue(decrypt("vault_a_password") is VaultDecryptResult.Decrypted)
        assertEquals(VaultFailure.WRONG_SECRET, failure(decrypt("vault_b_password")))
        assertEmpty(hints.drainForTests())
    }
}
