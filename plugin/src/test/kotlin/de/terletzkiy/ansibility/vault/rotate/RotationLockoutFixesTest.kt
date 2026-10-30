package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.SecretPlan
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import de.terletzkiy.ansibility.vault.secrets.PendingRotationRecord
import de.terletzkiy.ansibility.vault.secrets.PendingState
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.secrets.VaultCredentialStore
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.SecureRandom

/**
 * The fixes of the lockout review (plan amendment R29, Part 1, second fix round) that the review's own tests
 * ([RotationLockoutTest]) do not cover, one or more per decision. Synthetic passwords and values only.
 */
class RotationLockoutFixesTest : RotationTestCase() {
    private fun rootKey(relative: String): String = registry.discovery(root(relative)).rootKey

    private fun another(): String = NewPassword.generate(SecureRandom()).use { String(it.chars()) }

    private fun reveal(relative: String, key: String): VaultDecryptResult =
        await { VaultOperations.getInstance(project).decrypt(location(relative, key), VaultPurpose.REVEAL) }
            .also { (it as? VaultDecryptResult.Decrypted)?.plaintext?.close() }

    private fun useOnePassword(relative: String) {
        VaultProjectSettings.getInstance(project).update(rootKey(relative)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.ONE_PASSWORD, "op://Private/vault/password")))
        }
    }

    private val state: VaultUserState get() = VaultUserState.getInstance()

    // ------------------------------------------------------------------------------------------------ decision 1

    /**
     * Decision 1: while a rotation is pending, the source and the stored new password are both unlocked; a source that
     * then gives a third password (the team rotated again) ends the pending state, and a hint says so. What the IDE
     * rotated still opens.
     */
    fun testAThirdPasswordFromTheSourceEndsThePendingRotationWithAHint() {
        var managed = VaultVectors.PW1
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> managed.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)
        val canonical = registry.discovery(falcon).canonicalRootPath

        rotate()
        assertNotNull(state.pendingRotation(canonical, "default"))

        // Still the old password: pending, both unlocked.
        secrets.lockAll()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertNotNull("the source still gives the old password", state.pendingRotation(canonical, "default"))
        assertEquals("both passwords are unlocked", 2, secrets.secretsOf(falcon, "default").onEach { it.close() }.size)
        RotationHints.getInstance(project).drainForTests()

        managed = another()
        secrets.lockAll()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)

        assertNull("a third password ends it", state.pendingRotation(canonical, "default"))
        val hint = RotationHints.getInstance(project).drainForTests().single()
        assertTrue(hint, hint.startsWith("The vault password for 'default' in ") && hint.contains("was probably rotated again elsewhere"))
        assertTrue("the rotated value still opens", reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted)
    }

    // ------------------------------------------------------------------------------------------------ decision 3

    /** Decision 3: with a memory-only IDE password store, a password file the rotation writes is the durable home: no safe copy is asked. */
    fun testAMemoryOnlyStoreNeedsNoSafeCopyWhenThePasswordFileIsWritten() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val memoryOnly = object : VaultCredentialStore by credentials {
            override val isMemoryOnly: Boolean get() = true
        }
        secrets.setCredentialsForTests(memoryOnly)

        rotate()

        assertFalse(rotationPrompts.reviews.single().needsSafeCopy)
        assertEquals(newPassword, text("$root/.vault-pass"))
        assertFalse(service.lastSummary!!.memoryOnly)
    }

    // ------------------------------------------------------------------------------------------------ decision 4

    /**
     * Decision 4: heron's `.env.local` stays unread (its consent is declined) and heron has the id, so it may name the
     * shared password file falcon's rotation writes. The rotation stops before anything is written, or, when you choose
     * so, runs without writing any password file (you update it yourself; the rotation stays pending).
     */
    fun testAnUnreadEnvLocalStopsTheRotationOrItRunsWithoutWritingPasswordFiles() {
        val shared = write("shared/vault.pw", "${VaultVectors.PW1}\n")
        val heron = projectRoot("heron")
        write("$heron/.env.local", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=$shared\n")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        write("$heron/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Heron0Old0Value001", VaultVectors.PW1)))
        ready()
        VaultProjectSettings.getInstance(project).update(rootKey(root)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.PASSWORD_FILE, shared.toString())))
        }
        val falcon = root(root)
        val falconKey = registry.discovery(falcon).rootKey
        // Reading heron's .env.local is declined; falcon's own unlock reads falcon's password file.
        prompter.onConsent = { request -> if (request.roots.any { it.rootKey == falconKey }) VaultConsentDecision.Choose(setOf(falconKey)) else VaultConsentDecision.NotNow }
        val before = bytes(vaultPath)

        rotate(vf(root))

        val heronName = root(heron).displayName
        assertEquals(listOf(listOf(heronName)), rotationPrompts.unresolved.toList())
        assertEquals(
            listOf("Nothing was rotated: the IDE cannot tell which password file the .env.local of $heronName names. Allow reading it, or run the rotation without writing password files."),
            feedback(),
        )
        assertTrue("nothing written", before.contentEquals(bytes(vaultPath)))
        assertEquals(VaultVectors.PW1, String(Files.readAllBytes(shared)).trim())
        assertNull("nothing stored", stored(falcon))

        rotationPrompts.onUnresolved = { true }
        rotate(vf(root))

        assertEquals("rotated", "Falcon0Old0Value01", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals("the shared file is not written", VaultVectors.PW1, String(Files.readAllBytes(shared)).trim())
        assertEquals("heron keeps opening with what its source gives", "Heron0Old0Value001", valueOf("$heron/group_vars/all/vault.yml", "vault_db_password", VaultVectors.PW1))
        val summary = service.lastSummary!!
        assertTrue(summary.text, summary.text.contains("Update now: "))
        assertNotNull("pending until the file holds the new password", state.pendingRotation(registry.discovery(falcon).canonicalRootPath, "default"))
    }

    // ------------------------------------------------------------------------------------------------ decision 5

    /** Decision 5: a refused rotation puts the store entry back only while it holds what the rotation wrote; an entry changed meanwhile stays, and the message says so. */
    fun testARefusedRotationLeavesAStoreEntryChangedMeanwhile() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val canonical = registry.discovery(falcon).canonicalRootPath
        val other = another()
        service.onStepForTests = { step ->
            if (step == RotationService.Step.FILES) credentials.write(VaultPasswordSafeKeys.serviceName(canonical, "default"), "default", other.toCharArray(), memoryOnly = false)
        }
        ReencryptionWriter.failWriteForTests = { it.name == "vault.yml" }

        rotate()

        assertEquals("the entry changed meanwhile is left as it is", other, stored(falcon))
        val said = feedback()
        assertTrue(said.toString(), said.contains("Nothing was rotated: writing vault.yml failed, and the files written before it have their old content again."))
        assertTrue(said.toString(), said.any { it.startsWith("The IDE password store entry of 'default' for ") && it.endsWith("changed during the rotation and was left as it is.") })
        assertEmpty("the 'before rotation' entry the rotation added is gone", state.previousPasswords())
        assertNull(state.pendingRotation(canonical, "default"))
    }

    // ------------------------------------------------------------------------------------------------ decision 6

    /**
     * Decision 6: the rotation is recorded before the first vault file changes and after each step; a rotation that
     * puts everything back restores the record it found (here an earlier pending rotation).
     */
    fun testTheRotationIsRecordedBeforeTheFilesAndARefusedOnePutsTheEarlierRecordBack() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val canonical = registry.discovery(falcon).canonicalRootPath
        val seen = ArrayList<PendingState?>()
        service.onStepForTests = { step ->
            if (step == RotationService.Step.FILES || step == RotationService.Step.SOURCES) seen += state.pendingRotation(canonical, "default")?.state
        }

        rotate()

        assertEquals(listOf(PendingState.WRITING_FILES, PendingState.WRITING_SOURCES), seen)
        assertNull("the password file was written: nothing is pending", state.pendingRotation(canonical, "default"))

        val fileSlot = registry.discovery(falcon).identities.first { it.label == "default" && it.plan is SecretPlan.PasswordFile }.slot
        val earlier = PendingRotationRecord(canonical, "default", fileSlot, 1L)
        state.recordPendingRotation(earlier)
        newPassword = another()
        ReencryptionWriter.failWriteForTests = { it.name == "vault.yml" }
        rotate()

        assertTrue(feedback().contains("Nothing was rotated: writing vault.yml failed, and the files written before it have their old content again."))
        assertEquals("a refused rotation puts the earlier record back", earlier, state.pendingRotation(canonical, "default"))
    }

    /**
     * Decision 6: a rotation left in progress (the IDE stopped while it wrote) is announced when the project opens,
     * once, with Copy New Password, and becomes a pending rotation. The record keeps its step across a restart.
     */
    fun testARotationLeftInProgressIsAnnouncedAtTheNextStart() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val canonical = registry.discovery(falcon).canonicalRootPath
        state.recordPendingRotation(PendingRotationRecord(canonical, "default", null, System.currentTimeMillis(), PendingState.WRITING_FILES))
        state.loadState(state.state)
        assertEquals("kept across a restart", PendingState.WRITING_FILES, state.pendingRotation(canonical, "default")?.state)
        service.drainInterruptedForTests()

        await { service.notifyInterruptedRotations() }

        val shown = service.drainInterruptedForTests().single()
        assertTrue(shown, shown.startsWith("The rotation of vault id 'default' in ${falcon.displayName} did not finish."))
        assertEquals(PendingState.PENDING, state.pendingRotation(canonical, "default")?.state)
        await { service.notifyInterruptedRotations() }
        assertEmpty("once", service.drainInterruptedForTests())
    }

    /** Decisions 1 and 2: records of the first build (no step, no dated entry) still load; the undated "before rotation" entry is read and forgotten. */
    fun testRecordsOfTheFirstBuildStillLoad() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val canonical = registry.discovery(falcon).canonicalRootPath
        val bean = VaultUserState.StateBean().apply {
            previous = mutableListOf(VaultUserState.PreviousBean().apply { root = canonical; label = "default"; date = 5L })
            pendingRotations = mutableListOf(VaultUserState.PendingBean().apply { root = canonical; label = "default"; date = 5L })
        }
        state.loadState(bean)
        credentials.write(VaultPasswordSafeKeys.previousServiceName(canonical, "default"), "default", VaultVectors.PW1.toCharArray(), memoryOnly = false)

        assertEquals(PendingState.PENDING, state.pendingRotation(canonical, "default")?.state)
        val record = state.previousPasswords(canonical, "default").single()
        assertFalse(record.dated)
        val kept = secrets.keptPasswords(falcon, "default")
        try {
            assertEquals(VaultVectors.PW1, String(kept.single().secret.read { it.copyOf() }))
        } finally {
            kept.forEach { it.close() }
        }
        secrets.forgetPreviousPassword(record)
        assertNull(credentials.read(VaultPasswordSafeKeys.previousServiceName(canonical, "default"), "default"))
        assertEmpty(state.previousPasswords())
    }

    // ------------------------------------------------------------------------------------------------ decision 8

    /** Decision 8: forgetting the id's stored password while a rotation of it is pending needs a confirmation. */
    fun testForgettingTheStoredPasswordWhileARotationIsPendingNeedsAConfirmation() {
        val owl = projectRoot("owl")
        write("$owl/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Owl0Old0Value0001", VaultVectors.PW1)))
        root = owl
        ready()
        val owlRoot = root(owl)
        val canonical = registry.discovery(owlRoot).canonicalRootPath
        VaultProjectSettings.getInstance(project).update(rootKey(owl)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.PASSWORD_SAFE, null)))
        }
        credentials.write(VaultPasswordSafeKeys.serviceName(canonical, "default"), "default", newPassword.toCharArray(), memoryOnly = false)
        state.recordPendingRotation(PendingRotationRecord(canonical, "default", null, System.currentTimeMillis()))

        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()
            @Suppress("UNCHECKED_CAST")
            val roots = UIUtil.findComponentsOfType(component, ComboBox::class.java).first() as ComboBox<Any>
            PlatformTestUtil.waitWithEventsDispatching("the roots did not load", { roots.itemCount > 0 }, 60)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            roots.selectedIndex = (0 until roots.itemCount).single { (roots.getItemAt(it) as VaultConfigurable.RootEntry).name == owlRoot.displayName }
            @Suppress("UNCHECKED_CAST")
            val table = UIUtil.findComponentsOfType(component, TableView::class.java)
                .first { it.listTableModel.items.firstOrNull() is VaultConfigurable.IdRow } as TableView<VaultConfigurable.IdRow>
            waitFor("the stored entry was not found") { table.listTableModel.items.singleOrNull()?.stored == true }
            table.selection = listOf(table.listTableModel.items.single())

            page.remove()
            assertEquals("asked", listOf("default"), rotationPrompts.pendingEntries.toList())
            assertEquals("declined: the row stays", 1, table.listTableModel.items.size)
            assertFalse(page.isModified)

            rotationPrompts.onPendingEntry = { true }
            page.remove()
            assertEquals(0, table.listTableModel.items.size)
            assertTrue("confirmed: forgotten on Apply", page.isModified)
        } finally {
            page.disposeUIResources()
        }
        assertEquals("nothing applied: the rotated password is still stored", newPassword, stored(owlRoot))
    }

    // ------------------------------------------------------------------------------------------------ decision 9

    /** Decision 9: leftover cleanup never runs while a rotation holds the lock, and only removes files older than a minute. */
    fun testLeftoverCleanupWaitsForARunningRotationAndSparesYoungFiles() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        val young = write("$root/.ansibility-rotate-young.tmp", "a write in progress")
        val old = base.resolve("$root/.ansibility-rotate-old.tmp")
        ready()
        val falcon = root(root)
        val discovery = registry.discovery(falcon)
        val during = ArrayList<Boolean>()
        service.onStepForTests = { step ->
            if (step == RotationService.Step.FILES) {
                // A crash's leftover appears while the rotation runs (an unlock of another root cleans up meanwhile).
                Files.write(old, "left by a crash".toByteArray())
                Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 120_000))
                RotationTempFiles.removeLeftovers(discovery)
                during += Files.exists(old)
            }
        }

        rotate()

        assertEquals("not removed while the rotation runs", listOf(true), during)
        assertTrue("the rotation's own scan spared the young file", Files.exists(young))
        service.onStepForTests = null
        secrets.lockAll()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertFalse("the old leftover goes at the next unlock", Files.exists(old))
        assertTrue("a young temporary file stays", Files.exists(young))
    }
}
