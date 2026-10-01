package de.terletzkiy.ansibility.vault

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.secrets.ConsentFingerprint
import de.terletzkiy.ansibility.vault.secrets.ConsentItem
import de.terletzkiy.ansibility.vault.secrets.ConsentReason
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * D25: nothing secret is read before the user consents for a root; one dialog lists every root with discovered
 * sources; consents live per user at application level, keyed by the real path, size and a salted fingerprint, and
 * entries planted in project files have no effect.
 */
class VaultConsentTest : VaultTestCase() {
    private val ops: VaultOperations get() = VaultOperations.getInstance(project)

    /** falcon's layout: `.env.local` → `.vault-pass` (pw1) and one `greeting` value (vector v01). */
    private fun falcon(): String {
        val falcon = projectRoot("falcon")
        write("$falcon/.env.local", "SSH_JUMP_HOST_USER=ANSIBILITY-SENTINEL-31\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")
        write("$falcon/.env.local.skel", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/environments/prod/group_vars/all/vault.yml", VaultVectors.raw("v01"))
        return falcon
    }

    private fun decryptText(relative: String, key: String): String? {
        val result = await { ops.decrypt(location(relative, key), VaultPurpose.REVEAL) }
        return (result as? VaultDecryptResult.Decrypted)?.plaintext?.use { plaintext -> plaintext.read { String(it, Charsets.UTF_8) } }
    }

    private fun setMode(path: Path, mode: String) = Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))

    fun testNothingIsOpenedBeforeTheConsent() {
        val falcon = falcon()
        val envLocal = base.resolve("$falcon/.env.local")
        val vaultPass = base.resolve("$falcon/.vault-pass")
        val root = root(falcon)
        // Unreadable until the consent: any read before it would fail at the OS level, not only in the recorder.
        setMode(envLocal, "---------")
        setMode(vaultPass, "---------")
        try {
            val greeting = location("$falcon/environments/prod/group_vars/all/vault.yml", "greeting")
            val status = runReadActionBlocking { VaultStatusService.getInstance(project).status(greeting) }
            assertNotNull(status)
            VaultStatusService.getInstance(project).config(root)
            var readsAtConsent: List<Path>? = null
            prompter.onConsent = { request ->
                readsAtConsent = access.secretReads.toList()
                assertEquals(ConsentReason.FIRST_USE, request.reason)
                val item = request.roots.single().items.single()
                assertEquals(ConsentItem.Kind.ENV_LOCAL_KEY, item.kind)
                assertEquals(".env.local", item.display)
                assertEquals(".vault-pass", item.expected)
                setMode(envLocal, "rw-------")
                setMode(vaultPass, "rw-------")
                VaultConsentDecision.UseAll
            }
            assertEquals(VaultUnlockResult.Unlocked(listOf("default")), await { ops.unlock(root) })
            assertEquals("nothing was read before the answer", emptyList<Path>(), readsAtConsent)
            assertTrue(access.readSecretNamed(".env.local"))
            assertTrue(access.readSecretNamed(".vault-pass"))
            assertEquals("hello world", decryptText("$falcon/environments/prod/group_vars/all/vault.yml", "greeting"))
        } finally {
            setMode(envLocal, "rw-------")
            setMode(vaultPass, "rw-------")
        }
    }

    fun testOneDialogListsEveryRootWithDiscoveredSources() {
        val falcon = falcon()
        val heron = projectRoot("heron")
        write("$heron/.env.local.skel", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")
        write("$heron/.vault-pass", "${VaultVectors.PW1}\n")
        projectRoot("thrush")
        val falconRoot = root(falcon)
        val heronRoot = root(heron)

        assertTrue(await { ops.unlock(falconRoot) } is VaultUnlockResult.Unlocked)
        val request = prompter.consentRequests.single()
        assertEquals(listOf(registry.rootKey(falconRoot), registry.rootKey(heronRoot)), request.roots.map { it.rootKey })
        assertEquals(listOf("heron"), request.roots.drop(1).map { it.displayName })
        assertEquals(ConsentItem.Kind.PASSWORD_FILE, request.roots[1].items.single().kind)
        assertFalse("heron is not unlocked by falcon's consent", secrets.anyUnlockedIn(heronRoot))

        assertTrue(await { ops.unlock(heronRoot) } is VaultUnlockResult.Unlocked)
        assertEquals("no second dialog", 1, prompter.consentRequests.size)
    }

    fun testChooseGrantsOnlyTheChosenRoots() {
        val falcon = falcon()
        val heron = projectRoot("heron")
        write("$heron/.vault-pass", "${VaultVectors.PW1}\n")
        val falconRoot = root(falcon)
        val heronRoot = root(heron)
        prompter.onConsent = { VaultConsentDecision.Choose(setOf(registry.rootKey(heronRoot))) }
        prompter.passwords += VaultVectors.PW1 to RememberChoice.NONE

        assertTrue(await { ops.unlock(falconRoot) } is VaultUnlockResult.Unlocked)
        assertFalse("falcon's files stay unread", access.readSecretNamed(".env.local"))
        assertEquals("falcon falls back to the prompt", listOf("default"), prompter.passwordRequests.map { it.label })
        assertEquals("hello world", decryptText("$falcon/environments/prod/group_vars/all/vault.yml", "greeting"))

        assertTrue(await { ops.unlock(heronRoot) } is VaultUnlockResult.Unlocked)
        assertEquals(1, prompter.consentRequests.size)
        assertEquals(1, prompter.passwordRequests.size)
    }

    fun testNotNowFallsBackToThePromptAndIsNotAskedAgainThisSession() {
        val falcon = falcon()
        val root = root(falcon)
        prompter.onConsent = { VaultConsentDecision.NotNow }

        assertEquals(VaultUnlockResult.Failed(VaultFailure.CANCELLED), await { ops.unlock(root) })
        assertEquals(1, prompter.passwordRequests.size)
        assertEmpty(access.secretReads)

        prompter.passwords += VaultVectors.PW1 to RememberChoice.NONE
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals("declined for the session", 1, prompter.consentRequests.size)
        assertEmpty(access.secretReads)
    }

    fun testConsentsPlantedInProjectFilesAreIgnored() {
        val falcon = falcon()
        val vaultPass = base.resolve("$falcon/.vault-pass").toRealPath()
        val envLocal = base.resolve("$falcon/.env.local").toRealPath()
        val planted = """
            <project version="4">
              <component name="AnsibilityVaultUser">
                <consents>
                  <consent locator="$vaultPass" size="${Files.size(vaultPass)}" fingerprint="${ConsentFingerprint.of(Files.readAllBytes(vaultPass))}" />
                  <consent locator="$envLocal" size="${Files.size(envLocal)}" fingerprint="${ConsentFingerprint.of(Files.readAllBytes(envLocal))}" />
                </consents>
              </component>
              <component name="AnsibilityWorkspaceState" trustAllVaultSources="true" />
            </project>
        """.trimIndent()
        write("$falcon/.idea/workspace.xml", planted)
        write(".idea/workspace.xml", planted)
        access.secretReads.clear()
        val root = root(falcon)

        assertNull(VaultUserState.getInstance().consent(vaultPass.toString()))
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals("the planted consent did not skip the dialog", 1, prompter.consentRequests.size)

        val state = VaultUserState::class.java.getAnnotation(State::class.java)
        val storage = state.storages.single()
        assertEquals("ansibility-vault-user.xml", storage.value)
        assertEquals(RoamingType.DISABLED, storage.roamingType)
        assertFalse(storage.value.startsWith(StoragePathMacros.WORKSPACE_FILE))
    }

    fun testAChangedSourceAsksAgain() {
        val falcon = falcon()
        val root = root(falcon)
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        ops.lockAll()

        // Same size, other content: only the fingerprint tells.
        write("$falcon/.vault-pass", "test-pass-9\n")
        prompter.onConsent = { request ->
            assertEquals(ConsentReason.CHANGED, request.reason)
            assertEquals(".vault-pass", request.roots.single().items.single().display)
            VaultConsentDecision.UseAll
        }
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals(2, prompter.consentRequests.size)
        assertEquals("the new password does not decrypt v01", null, decryptText("$falcon/environments/prod/group_vars/all/vault.yml", "greeting"))

        // Another size: asked before anything is read.
        ops.lockAll()
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n\n")
        access.secretReads.clear()
        prompter.onConsent = { request ->
            assertFalse(access.readSecretNamed(".vault-pass"))
            assertEquals(ConsentReason.FIRST_USE, request.reason)
            assertTrue("shown as changed", request.roots.single().items.single().changed)
            VaultConsentDecision.UseAll
        }
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals("hello world", decryptText("$falcon/environments/prod/group_vars/all/vault.yml", "greeting"))
    }

    fun testEnvLocalNamingAnotherFileAsksForThatFile() {
        val falcon = projectRoot("falcon")
        write("$falcon/.env.local.skel", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")
        write("$falcon/.vault-pass", "wrong-pass\n")
        write("$falcon/.env.local", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=secrets/other.pw\n")
        write("$falcon/secrets/other.pw", "${VaultVectors.PW1}\n")
        write("$falcon/vault.yml", VaultVectors.raw("v01"))
        val root = root(falcon)
        val reasons = ArrayList<ConsentReason>()
        prompter.onConsent = { request ->
            reasons += request.reason
            if (request.reason == ConsentReason.NAMED_BY_ENV_LOCAL) {
                assertFalse("secrets/other.pw is read only after its own consent", access.readSecretNamed("other.pw"))
                assertEquals("secrets/other.pw", request.roots.single().items.single().display)
            }
            VaultConsentDecision.UseAll
        }

        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals(listOf(ConsentReason.FIRST_USE, ConsentReason.NAMED_BY_ENV_LOCAL), reasons)
        assertEquals("hello world", decryptText("$falcon/vault.yml", "greeting"))
    }

    fun testALinkedPasswordFileShowsWhereItPoints() {
        val falcon = projectRoot("falcon")
        val target = write("home/keys/id_ed25519", "${VaultVectors.PW1}\n")
        Files.createSymbolicLink(base.resolve("$falcon/.vault-pass"), target)
        val root = root(falcon)
        val display = registry.discovery(root).identities.single().source.location
        assertEquals(".vault-pass → ~/keys/id_ed25519", display)
        assertTrue(await { ops.unlock(root) } is VaultUnlockResult.Unlocked)
        assertEquals(".vault-pass → ~/keys/id_ed25519", prompter.consentRequests.single().roots.single().items.single().display)
        assertNotNull("the consent is keyed by the real file", VaultUserState.getInstance().consent(target.toRealPath().toString()))
    }

    fun testFingerprintsAreSaltedAndRevealNoDigest() {
        val content = "${VaultVectors.PW1}\n".toByteArray()
        val first = ConsentFingerprint.of(content)
        val second = ConsentFingerprint.of(content)
        assertFalse("a fresh salt every time", first == second)
        assertTrue(ConsentFingerprint.matches(content, first))
        assertFalse(ConsentFingerprint.matches("other\n".toByteArray(), first))
        val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        assertFalse("not the bare SHA-256", sha in first)
        assertFalse(VaultVectors.PW1 in first)
        assertTrue(ConsentFingerprint.matches(ByteArray(0), ConsentFingerprint.of(ByteArray(0))))
    }

    private fun de.terletzkiy.ansibility.vault.secrets.VaultSecretsService.anyUnlockedIn(root: de.terletzkiy.ansibility.api.AnsibleRoot): Boolean =
        unlockedLabels(registry.discovery(root)).isNotEmpty()
}
