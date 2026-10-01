package de.terletzkiy.ansibility.vault

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.util.Disposer
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultStatusListener
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.secrets.PasswordSafeCredentialStore
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import de.terletzkiy.ansibility.vault.secrets.VaultLockReason
import de.terletzkiy.ansibility.vault.secrets.VaultSecretsService
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger

/**
 * [VaultSecretsService]: lazy unlock, lock, Lock all, the idle lock on a virtual clock, lock on project close, the
 * lock tracker and listener events, the D26 prompt with its remember choices, vaulted password files and the corpus
 * guard.
 */
class VaultSecretsServiceTest : VaultTestCase() {
    private val ops: VaultOperations get() = VaultOperations.getInstance(project)
    private val vaultFile = "repos/falcon/ansible/environments/prod/group_vars/all/vault.yml"

    private fun falconWithVaultPass(): String {
        val falcon = projectRoot("falcon")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/environments/prod/group_vars/all/vault.yml", VaultVectors.raw("v01"))
        return falcon
    }

    private fun decryptGreeting(): VaultDecryptResult = await { ops.decrypt(location(vaultFile, "greeting"), VaultPurpose.REVEAL) }

    private fun events(): AtomicInteger {
        val count = AtomicInteger()
        project.messageBus.connect(testRootDisposable).subscribe(VaultStatusListener.TOPIC, VaultStatusListener { count.incrementAndGet() })
        return count
    }

    fun testUnlockAndLockBumpTheTrackerAndNotify() {
        val root = root(falconWithVaultPass())
        val events = events()
        val tracker = VaultStatusService.getInstance(project).lockTracker
        val before = tracker.modificationCount
        assertEquals(VaultLockState.LOCKED, VaultStatusService.getInstance(project).config(root).identities.single().lockState)

        assertEquals(VaultUnlockResult.Unlocked(listOf("default")), await { ops.unlock(root) })
        assertTrue(tracker.modificationCount > before)
        assertTrue(events.get() >= 1)
        assertEquals(VaultLockState.UNLOCKED, VaultStatusService.getInstance(project).config(root).identities.single().lockState)
        assertEquals("one read per unlock: the consent's read is reused", 1, access.secretReads.count { it.fileName.toString() == ".vault-pass" })
        assertEquals(VaultUnlockResult.Unlocked(listOf("default")), await { ops.unlock(root) })
        assertEquals(1, access.secretReads.count { it.fileName.toString() == ".vault-pass" })

        val afterUnlock = tracker.modificationCount
        val eventsAfterUnlock = events.get()
        secrets.lock(root)
        assertTrue(tracker.modificationCount > afterUnlock)
        assertTrue(events.get() > eventsAfterUnlock)
        assertEquals(VaultLockState.LOCKED, VaultStatusService.getInstance(project).config(root).identities.single().lockState)

        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals("the password file is read again on each unlock", 2, access.secretReads.count { it.fileName.toString() == ".vault-pass" })
        ops.lockAll()
        assertFalse(secrets.anyUnlocked())
    }

    fun testDecryptNeverPromptsAndUnlockIsLazy() {
        root(falconWithVaultPass())
        val result = decryptGreeting()
        assertEquals(VaultFailure.LOCKED, (result as VaultDecryptResult.Failed).failure)
        assertEmpty(prompter.consentRequests)
        assertEmpty(prompter.passwordRequests)
        assertEmpty(access.secretReads)
    }

    fun testIdleLockAfterThirtyMinutesOnTheVirtualClock() {
        val root = root(falconWithVaultPass())
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        val events = events()

        clock.advanceMinutes(29)
        secrets.checkIdle()
        assertTrue("29 minutes idle keeps it unlocked", secrets.anyUnlocked())

        assertTrue(decryptGreeting() is VaultDecryptResult.Decrypted)
        clock.advanceMinutes(29)
        secrets.checkIdle()
        assertTrue("activity restarts the idle time", secrets.anyUnlocked())

        clock.advanceMinutes(1)
        secrets.checkIdle()
        assertFalse("30 minutes idle locks", secrets.anyUnlocked())
        assertTrue(events.get() >= 1)
        assertTrue("the caches are empty after the idle lock", crypto.isEmpty)
        assertEquals(VaultFailure.LOCKED, (decryptGreeting() as VaultDecryptResult.Failed).failure)
    }

    fun testAutoLockOffKeepsTheSecrets() {
        val root = root(falconWithVaultPass())
        VaultUserState.getInstance().autoLockMinutes = 0
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        clock.advanceMinutes(24 * 60)
        secrets.checkIdle()
        assertTrue(secrets.anyUnlocked())
    }

    fun testPlaintextCacheExpiresAfterFiveIdleMinutesAndOnRootChange() {
        val root = root(falconWithVaultPass())
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        (decryptGreeting() as VaultDecryptResult.Decrypted).plaintext.close()
        assertEquals(1, crypto.plaintextCount)

        val attempts = crypto.decryptAttempts
        (decryptGreeting() as VaultDecryptResult.Decrypted).plaintext.close()
        assertEquals("served from the plaintext cache", attempts, crypto.decryptAttempts)

        clock.advanceMinutes(4)
        secrets.checkIdle()
        assertEquals(1, crypto.plaintextCount)
        clock.advanceMinutes(1)
        secrets.checkIdle()
        assertEquals("5 minutes idle drop the plaintext", 0, crypto.plaintextCount)
        assertTrue("the verification stays", crypto.verificationCount > 0)
        assertTrue("still unlocked", secrets.anyUnlocked())

        (decryptGreeting() as VaultDecryptResult.Decrypted).plaintext.close()
        assertEquals(1, crypto.plaintextCount)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
        assertEquals("a root change drops the plaintext", 0, crypto.plaintextCount)
    }

    fun testLockAllClearsBothCaches() {
        val root = root(falconWithVaultPass())
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        (decryptGreeting() as VaultDecryptResult.Decrypted).plaintext.close()
        assertFalse(crypto.isEmpty)
        ops.lockAll()
        assertTrue(crypto.isEmpty)
        assertEquals(0, crypto.verificationCount)
    }

    fun testProjectCloseLocksWithoutNotifying() {
        val root = root(falconWithVaultPass())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = VaultSecretsService(project, scope)
        try {
            service.setClockForTests(clock)
            assertTrue(await { service.unlock(root) } is VaultUnlockResult.Unlocked)
            assertTrue(service.anyUnlocked())
            val lease = service.lease(registry.discovery(root))
            val events = events()
            Disposer.dispose(service)
            assertFalse(service.anyUnlocked())
            assertEquals("no events while the project closes", 0, events.get())
            assertEquals("a lease taken before stays usable until closed", 1, lease.secrets.size)
            lease.close()
            assertTrue(lease.secrets.single().secret.isZeroed)
        } finally {
            scope.cancel()
        }
    }

    fun testPromptRememberedInPasswordSafeIsReusedAfterALock() {
        val thrush = projectRoot("thrush")
        write("$thrush/vault.yml", VaultVectors.raw("v01"))
        val root = root(thrush)
        val credentials = InMemoryCredentials()
        secrets.setCredentialsForTests(credentials)
        prompter.passwords += VaultVectors.PW1 to RememberChoice.KEYCHAIN

        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        val request = prompter.passwordRequests.single()
        assertEquals("default", request.label)
        assertEquals("thrush", request.rootDisplayName)
        val canonical = base.resolve(thrush).toRealPath().toString()
        val service = VaultPasswordSafeKeys.serviceName(canonical, "default")
        assertEquals(VaultVectors.PW1, String(credentials.entries.getValue("$service|default")))
        assertFalse(credentials.memoryOnlyKeys.contains("$service|default"))
        assertEquals(listOf("default"), VaultUserState.getInstance().rememberedLabels(canonical))
        assertEquals(VaultSourceKind.PASSWORD_SAFE, VaultStatusService.getInstance(project).config(root).identities.single().source.kind)

        ops.lockAll()
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals("no second prompt", 1, prompter.passwordRequests.size)
        assertEquals("hello world", (await { ops.decrypt(location("$thrush/vault.yml", "greeting"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted).plaintext.use { p -> p.read { String(it) } })
    }

    fun testSessionOnlyAndDontRememberChoices() {
        val thrush = projectRoot("thrush")
        val root = root(thrush)
        val credentials = InMemoryCredentials()
        secrets.setCredentialsForTests(credentials)
        val canonical = base.resolve(thrush).toRealPath().toString()
        val key = "${VaultPasswordSafeKeys.serviceName(canonical, "default")}|default"

        prompter.passwords += VaultVectors.PW1 to RememberChoice.SESSION
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertTrue("this session only: PasswordSafe's memory store", key in credentials.memoryOnlyKeys)
        val persisted = VaultUserState.getInstance().state.remembered
        assertEmpty("a session entry is not saved", persisted)
        assertEquals(listOf("default"), VaultUserState.getInstance().rememberedLabels(canonical))

        ops.lockAll()
        VaultUserState.getInstance().forget(canonical, "default")
        prompter.passwords += VaultVectors.PW1 to RememberChoice.NONE
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertFalse("don't remember removes the entry", credentials.entries.containsKey(key))
        ops.lockAll()
        prompter.passwords += VaultVectors.PW1 to RememberChoice.NONE
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals("asked again after a lock", 3, prompter.passwordRequests.size)
    }

    fun testPasswordSafeRoundTripThroughThePlatformApi() {
        assertSame("the test store is installed", passwordSafe, PasswordSafe.instance)
        val service = VaultPasswordSafeKeys.serviceName("/synthetic/root", "default")
        val attributes = CredentialAttributes(service, "default")
        PasswordSafeCredentialStore.write(service, "default", VaultVectors.PW1.toCharArray(), memoryOnly = false)
        assertEquals(VaultVectors.PW1, passwordSafe.persistent.get(attributes)?.getPasswordAsString())
        assertEquals(VaultVectors.PW1, PasswordSafeCredentialStore.read(service, "default")?.let(::String))
        assertEquals("reading does not consume the entry", VaultVectors.PW1, PasswordSafeCredentialStore.read(service, "default")?.let(::String))

        PasswordSafeCredentialStore.write(service, "default", "other".toCharArray(), memoryOnly = true)
        assertNull(passwordSafe.persistent.get(attributes))
        assertEquals("other", PasswordSafeCredentialStore.read(service, "default")?.let(::String))

        PasswordSafeCredentialStore.write(service, "default", null, memoryOnly = false)
        assertNull(PasswordSafeCredentialStore.read(service, "default"))
        assertFalse(PasswordSafeCredentialStore.isMemoryOnly)
    }

    fun testRememberedPromptRoundTripsThroughPasswordSafe() {
        val thrush = projectRoot("thrush")
        write("$thrush/vault.yml", VaultVectors.raw("v01"))
        val root = root(thrush)
        secrets.setCredentialsForTests(PasswordSafeCredentialStore)
        prompter.passwords += VaultVectors.PW1 to RememberChoice.KEYCHAIN
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        val service = VaultPasswordSafeKeys.serviceName(base.resolve(thrush).toRealPath().toString(), "default")
        assertEquals(VaultVectors.PW1, passwordSafe.persistent.get(CredentialAttributes(service, "default"))?.getPasswordAsString())

        ops.lockAll()
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals("read back from PasswordSafe", 1, prompter.passwordRequests.size)
        val greeting = await { ops.decrypt(location("$thrush/vault.yml", "greeting"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted
        assertEquals("hello world", greeting.plaintext.use { p -> p.read { String(it) } })
    }

    fun testAWrongPromptedPasswordIsAskedAgainWhenTheValueIsKnown() {
        val thrush = projectRoot("thrush")
        write("$thrush/vault.yml", VaultVectors.raw("v01"))
        val root = root(thrush)
        prompter.passwords += "wrong-pass" to RememberChoice.KEYCHAIN
        prompter.passwords += VaultVectors.PW1 to RememberChoice.NONE
        val envelope = VaultVectors.envelope(VaultVectors.raw("v01").substringAfter("|\n").trimIndent() + "\n")

        assertTrue(await { secrets.unlock(root, verify = envelope) } is VaultUnlockResult.Unlocked)
        assertEquals(2, prompter.passwordRequests.size)
        assertNull(prompter.passwordRequests[0].error)
        assertNotNull(prompter.passwordRequests[1].error)
        assertEmpty("the refused password was never remembered", VaultUserState.getInstance().rememberedLabels(base.resolve(thrush).toRealPath().toString()))
    }

    fun testAVaultedPasswordFileDecryptsWithTheSecretBeforeIt() {
        val falcon = projectRoot("falcon", "[defaults]\nvault_identity_list = default@pw1.txt, prod@prod.vault\n")
        write("$falcon/pw1.txt", "${VaultVectors.PW1}\n")
        write("$falcon/prod.vault", VaultVectors.encrypt(VaultVectors.PROD, VaultVectors.PW1).format())
        write("$falcon/vault.yml", VaultVectors.raw("v03"))
        val root = root(falcon)

        assertEquals(VaultUnlockResult.Unlocked(listOf("default", "prod")), await { ops.unlock(root) })
        val result = await { ops.decrypt(location("$falcon/vault.yml", "db_password"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted
        assertEquals("prod", result.identity)
        assertEquals("prod secret", result.plaintext.use { p -> p.read { String(it) } })
    }

    fun testCorpusGuardRefusesTheProtectedTree() {
        val root = root(falconWithVaultPass())
        VaultCorpusGuard.protectForTests(listOf(base))
        assertThrows(VaultCorpusGuardException::class.java) { await { ops.unlock(root) } }
        assertThrows(VaultCorpusGuardException::class.java) { decryptGreeting() }
        assertThrows(VaultCorpusGuardException::class.java) { access.readSecret(base.resolve("repos/falcon/ansible/.vault-pass"), 100) }
        assertEmpty(prompter.consentRequests)
        val message = runCatching { await { ops.unlock(root) } }.exceptionOrNull()!!.message!!
        assertFalse(VaultVectors.PW1 in message)

        VaultCorpusGuard.protectForTests(null)
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
    }

    fun testLockReasonsAreDistinguished() {
        val root = root(falconWithVaultPass())
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        val events = events()
        secrets.lockAll(VaultLockReason.PROJECT_CLOSE)
        assertEquals(0, events.get())
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        secrets.lockAll(VaultLockReason.IDLE)
        assertTrue(events.get() >= 1)
    }
}
