package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultEncryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.PasswordManagerReader
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.secrets.RememberChoice
import de.terletzkiy.ansibility.vault.secrets.VaultCredentialStore
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import kotlinx.coroutines.Job
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lockout review of Rotate Vault Password… after its first review fixes (plan amendment R29, Part 1; review of
 * f3d6d69). Each test states the overriding property (every vault file opens with a durably stored password; the
 * source the IDE reads first gives that password, or a pending rotation makes the IDE use the stored new one; nothing
 * that opened files is overwritten without being kept) for one scenario, and failed on f3d6d69. Where a decision of
 * the second fix round changes the outcome (the value is rotated instead of left alone, the second rotation is refused,
 * Rotate needs a safe copy of the new password), the test says so. Synthetic passwords and values only.
 */
class RotationLockoutTest : RotationTestCase() {
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

    /** An IDE restart as far as the vault state goes: memory gone, the application state saved and loaded again. */
    private fun restart() {
        val state = VaultUserState.getInstance()
        state.loadState(state.state)
        secrets.lockAll()
    }

    private fun encryptNew(rootRelative: String, plaintext: String): String {
        val result = await { VaultOperations.getInstance(project).encrypt(root(rootRelative), plaintext.toByteArray(), "default", vf(vaultPath)) }
        return (result as VaultEncryptResult.Encrypted).envelope
    }

    /** The own entry and the "before rotation" entries of `default` in [store] (one per rotation since round 2, decision 2). */
    private fun storedIn(store: VaultCredentialStore, relative: String): List<String> {
        val canonical = registry.discovery(root(relative)).canonicalRootPath
        val previous = VaultUserState.getInstance().previousPasswords(canonical, "default").map { VaultPasswordSafeKeys.previousServiceName(canonical, "default", it.date) }
        return (listOf(VaultPasswordSafeKeys.serviceName(canonical, "default")) + previous).mapNotNull { store.read(it, "default")?.let(::String) }
    }

    // ------------------------------------------------------------------------------------------------ 1

    /**
     * A second rotation replaces the "before rotation" entry although the preflight left alone a value that only that
     * entry's password opens (a teammate added it with the old password before learning of the first rotation). The
     * review says "the current password does not open it"; the summary says the old password is kept. Afterwards no
     * password the IDE keeps opens that value.
     */
    fun testASecondRotationKeepsThePasswordThatOpensWhatItLeavesAlone() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)

        rotate()
        assertEquals("the first rotation wrote the password file", newPassword, text("$root/.vault-pass"))
        assertEquals("the first rotation kept the old password", VaultVectors.PW1, previous(falcon))

        // A teammate who has not got the new password yet adds a value with the old one; the user pulls.
        write(vaultPath, text(vaultPath) + VaultVectors.inline("vault_api_token", VaultVectors.encrypt("Api0Old0Value0001", VaultVectors.PW1)))
        vf(vaultPath)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertNotNull("before: the 'before rotation' entry opens the pulled value", valueOf(vaultPath, "vault_api_token", previous(falcon)!!))

        newPassword = another()
        rotate()

        // Round 2, decision 2: the kept "before rotation" password opens the pulled value, so it is rotated too (it was
        // left alone, "the current password does not open it", on f3d6d69).
        val review = rotationPrompts.reviews.last()
        assertTrue("the pulled value is not left alone: ${review.leftAlone}", review.leftAlone.none { it.contains("vault_api_token") })
        assertTrue("the review names it: ${review.fromPrevious}", review.fromPrevious.any { it.contains("vault_api_token") && it.contains("opened with the password from before the rotation of") })
        assertEquals("rotated with the rest", "Api0Old0Value0001", valueOf(vaultPath, "vault_api_token", newPassword))
        assertEquals("both previous passwords are kept, none overwritten", 2, previousAll(falcon).size)
        val kept = listOfNotNull(stored(falcon), previous(falcon), text("$root/.vault-pass").trim())
        assertTrue(
            "after the second rotation no password the IDE keeps opens vault_api_token (the 'before rotation' entry now holds the first rotation's password)",
            kept.any { valueOf(vaultPath, "vault_api_token", it) != null },
        )
    }

    // ------------------------------------------------------------------------------------------------ 2

    /**
     * The pending rotation (decision 9) is recorded only in step 4. When anything ends the run after the vault files
     * were written (an exception in step 3 or 4, or the IDE killed there; simulated by the step hook throwing at
     * SOURCES), the files hold the new envelopes, the manager the old password, and no pending record exists: after a
     * restart the manager's old password is first again and encrypting uses it.
     */
    fun testAnInterruptionAfterTheVaultFilesKeepsTheNewPasswordFirst() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)
        service.onStepForTests = { step -> if (step == RotationService.Step.SOURCES) throw IllegalStateException("interrupted after the vault files") }

        try {
            rotate()
        } catch (_: Throwable) {
            // Logged by the run; the state is what matters.
        }
        service.onStepForTests = null

        assertEquals("the vault file holds the new envelope", "Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals("the new password is stored", newPassword, stored(falcon))
        val pending = VaultUserState.getInstance().pendingRotation(registry.discovery(falcon).canonicalRootPath, "default")
        val feedback = feedback()

        restart()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        val envelope = encryptNew(root, "New0Value0000001")
        assertEquals(
            "a value encrypted after the interrupted rotation opens with the password the file's other values need " +
                "(pending record: $pending, feedback: $feedback)",
            "New0Value0000001",
            decryptWith(envelope, newPassword),
        )
    }

    // ------------------------------------------------------------------------------------------------ 3

    /**
     * After a rotation whose source was not written (pending), the user rolls the rotated files back (Rollback in the
     * commit tool window, `git checkout`, or Local History's "Before rotating vault id 'default'"). The manager still
     * gives the password that opens them, but the pending record skips it at every unlock (it equals "before
     * rotation"), so the IDE no longer opens its own files.
     */
    fun testRollingBackTheRotatedFilesLeavesThemOpenable() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)
        val before = bytes(vaultPath)

        rotate()
        assertNotNull(VaultUserState.getInstance().pendingRotation(registry.discovery(falcon).canonicalRootPath, "default"))

        Files.write(base.resolve(vaultPath), before)
        vf(vaultPath)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals("the source gives the password that opens the rolled-back file", "Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", VaultVectors.PW1))

        restart()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertTrue(
            "after a rollback the IDE skips the source that opens the files and cannot reveal the value",
            reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted,
        )
    }

    // ------------------------------------------------------------------------------------------------ 4

    /**
     * While a rotation is pending the team rotates elsewhere: the manager and the pulled files hold a third password.
     * The unlock loads it (not "before rotation"), but the stale rotated entry stays first, so encrypting uses a
     * password neither the source nor the files have: teammates and CI cannot open the new value.
     */
    fun testAPendingSourceThatGivesAThirdPasswordIsWhatEncryptingUses() {
        var managed = VaultVectors.PW1
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> managed.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)

        rotate()
        val team = another()
        managed = team
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Team0Value001", team)))
        vf(vaultPath)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        restart()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertTrue("the team's value opens", reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted)
        val envelope = encryptNew(root, "New0Value0000001")
        assertEquals(
            "a value encrypted now opens with the password the source and the files hold (not the stale rotated one)",
            "New0Value0000001",
            decryptWith(envelope, team),
        )
    }

    // ------------------------------------------------------------------------------------------------ 5

    /** A [VaultCredentialStore] whose reads give nothing while [failing] (PasswordSafe returns null on a Keychain error). */
    private class FlakyCredentials(private val delegate: VaultCredentialStore) : VaultCredentialStore {
        @Volatile
        var failing = false

        override fun read(serviceName: String, label: String): CharArray? = if (failing) null else delegate.read(serviceName, label)

        override fun write(serviceName: String, label: String, password: CharArray?, memoryOnly: Boolean) = delegate.write(serviceName, label, password, memoryOnly)

        override val isMemoryOnly: Boolean get() = delegate.isMemoryOnly
    }

    /**
     * One unlock while the IDE password store cannot be read (the Keychain access prompt denied after an IDE update, a
     * locked Secret Service) ends the pending rotation and forgets the remembered label for good. The rotated password
     * is still in the store, but the IDE never reads it again: the manager's old password is the id's only secret and
     * the rotated files no longer open.
     */
    fun testAnUnreadableStoreOnceDoesNotEndThePendingRotation() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)
        val flaky = FlakyCredentials(credentials)
        secrets.setCredentialsForTests(flaky)

        rotate()
        val canonical = registry.discovery(falcon).canonicalRootPath
        assertNotNull(VaultUserState.getInstance().pendingRotation(canonical, "default"))

        restart()
        flaky.failing = true
        await { secrets.unlock(falcon) }
        flaky.failing = false

        restart()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertEquals("the rotated password is still in the store", newPassword, stored(falcon))
        assertTrue(
            "after one unreadable store the IDE no longer opens the rotated files " +
                "(pending=${VaultUserState.getInstance().pendingRotation(canonical, "default")}, remembered=${VaultUserState.getInstance().rememberedLabels(canonical)})",
            reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted,
        )
    }

    // ------------------------------------------------------------------------------------------------ 6

    /**
     * Two rotations at once (started from two places; nothing excluded the second on f3d6d69). A checked its
     * destinations, then B did; A stored, B stored over it and wrote every file; A then found the files changed and put
     * back its snapshot of the store, taken before either wrote: the entry that held B's password was empty again. The
     * files were on B's password, the manager held the old one, and nothing durable opened them. Round 2, decision 5:
     * the second start is refused ("a rotation is running"), so the test releases the latches B would have released;
     * the property is asserted as before.
     */
    fun testTwoRotationsAtOnceNeverLoseTheStoredPassword() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)
        val passwords = listOf(another(), another())
        val calls = AtomicInteger()
        rotationPrompts.onRotation = { NewPassword(passwords[calls.getAndIncrement()].toCharArray(), generated = false) }

        val stores = AtomicInteger()
        val files = AtomicInteger()
        val bAtStore = CountDownLatch(1)
        val aAtFiles = CountDownLatch(1)
        val bAtMemory = CountDownLatch(1)
        val jobs = ConcurrentHashMap<String, Job>()
        service.onStepForTests = { step ->
            when (step) {
                RotationService.Step.STORE -> if (stores.getAndIncrement() == 0) {
                    bAtStore.await(60, TimeUnit.SECONDS)
                } else {
                    bAtStore.countDown()
                    aAtFiles.await(60, TimeUnit.SECONDS)
                }
                RotationService.Step.FILES -> if (files.getAndIncrement() == 0) {
                    aAtFiles.countDown()
                    bAtMemory.await(60, TimeUnit.SECONDS)
                    val deadline = System.currentTimeMillis() + 60_000
                    // B runs to its end (summary included) while A waits here, between its store and its file write.
                    while (jobs.size < 2 || jobs.values.count { it.isActive } > 1 && System.currentTimeMillis() < deadline) Thread.sleep(20)
                }
                RotationService.Step.MEMORY -> bAtMemory.countDown()
                RotationService.Step.SOURCES -> Unit
            }
        }

        service.rotate(emptyList())
        val first = service.lastRun!!
        service.rotate(emptyList())
        val second = service.lastRun!!
        // Round 2, decision 5: B never runs, so it never reaches the steps that release A.
        bAtStore.countDown()
        bAtMemory.countDown()
        jobs["first"] = first
        jobs["second"] = second
        waitFor("the rotations did not end") { first.isCompleted && second.isCompleted }
        service.onStepForTests = null

        val said = feedback()
        assertTrue("the second rotation was refused: $said", said.contains("A vault password rotation is running. Wait until it ends, then start the next one."))
        assertEquals("only the first rotation asked for a password", 1, calls.get())
        val password = passwords.firstOrNull { valueOf(vaultPath, "vault_db_password", it) != null }
        assertNotNull("one rotation wrote the file (feedback: $said)", password)
        val durable = storedIn(credentials, root) + VaultVectors.PW1
        assertTrue(
            "the file is on rotation ${passwords.indexOf(password) + 1}'s password, which nothing durable holds any more " +
                "(store entries left: ${durable.size - 1}, manager: the old password, feedback: $said, summary: ${service.lastSummary})",
            durable.any { valueOf(vaultPath, "vault_db_password", it) != null },
        )
    }

    // ------------------------------------------------------------------------------------------------ 7

    /**
     * A `git pull` while the review is open brings a new vault file with values of the id, encrypted with the old
     * password by a teammate who does not know of the rotation yet. Decision 7 compares only the files the preflight
     * saw: the rotation writes the new password to `.vault-pass`, and the new file opens only with "before rotation".
     */
    fun testAVaultFileArrivingDuringTheReviewIsNotLeftOnTheOldPassword() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        val pulled = "$root/host_vars/web3/vault.yml"
        rotationPrompts.onReview = {
            write(pulled, VaultVectors.inline("vault_web3_password", VaultVectors.encrypt("Web30Old0Value01", VaultVectors.PW1)))
            true
        }

        rotate()

        val source = text("$root/.vault-pass").trim()
        assertNotNull(
            "the pulled vault file opens with what .vault-pass holds now, or the rotation stopped (feedback: ${feedback()})",
            valueOf(pulled, "vault_web3_password", source),
        )
    }

    // ------------------------------------------------------------------------------------------------ 8

    /**
     * PasswordSafe set to "Do not save, forget passwords after restart" ([VaultCredentialStore.isMemoryOnly]): D211's
     * "durable on this machine whatever happens next" does not hold, and on f3d6d69 the rotation did not check it. For
     * an id whose password is typed when asked (no source to write; the summary offered no Copy), the new password
     * existed only in memory: after a restart nothing opened the rotated files. Round 2, decision 3: the review offers
     * Copy and Rotate needs "I have stored the new password somewhere safe"; without it nothing is rotated. The copy you
     * stored counts as durable (here: what the review's Copy put on the clipboard).
     */
    fun testWithAMemoryOnlyPasswordStoreTheNewPasswordSurvivesARestart() {
        val owl = projectRoot("owl")
        write("$owl/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Owl0Old0Value0001", VaultVectors.PW1)))
        root = owl
        ready()
        val memoryOnly = MemoryOnlyCredentials()
        secrets.setCredentialsForTests(memoryOnly)
        prompter.passwords.add(VaultVectors.PW1 to RememberChoice.NONE)
        val before = bytes(vaultPath)

        // Rotate without confirming a safe copy: nothing is rotated.
        rotate()
        assertTrue("the review asked for a safe copy", rotationPrompts.reviews.last().needsSafeCopy)
        assertTrue("nothing written", before.contentEquals(bytes(vaultPath)))
        assertEquals(listOf("Nothing was rotated: confirm that you stored the new password somewhere safe. The IDE keeps passwords only until it restarts."), feedback())

        var copied: String? = null
        rotationPrompts.onReview = { review ->
            review.copyNewPassword!!()
            copied = CopyPasteManager.getInstance().contents?.getTransferData(DataFlavor.stringFlavor) as String?
            review.storedSafely = true
            true
        }
        rotate()
        assertEquals("rotated", "Owl0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        val summary = service.lastSummary!!
        assertTrue("the summary repeats it: ${summary.text}", summary.memoryOnly && summary.text.contains("The IDE keeps passwords only until it restarts"))

        memoryOnly.restart()
        restart()
        val durable = storedIn(memoryOnly, owl) + listOfNotNull(copied)
        assertTrue(
            "after a restart nothing durable opens the rotated value (summary offered Copy: ${summary.unwritten.isNotEmpty() || summary.memoryOnly})",
            durable.any { valueOf(vaultPath, "vault_db_password", it) != null },
        )
    }

    /** PasswordSafe in memory-only mode: every entry is kept in memory, whatever the caller asks, and gone at restart. */
    private class MemoryOnlyCredentials : VaultCredentialStore {
        private val entries = ConcurrentHashMap<String, CharArray>()

        override fun read(serviceName: String, label: String): CharArray? = entries["$serviceName|$label"]?.copyOf()

        override fun write(serviceName: String, label: String, password: CharArray?, memoryOnly: Boolean) {
            if (password == null) entries.remove("$serviceName|$label") else entries["$serviceName|$label"] = password.copyOf()
        }

        override val isMemoryOnly: Boolean get() = true

        fun restart() = entries.clear()
    }

    // ------------------------------------------------------------------------------------------------ 9

    /**
     * `prod`'s password file is vaulted with `default`'s password, and `default` comes from a password manager. After a
     * rotation of `default` (pending: the manager is not written), the unlock skips the manager's old password, and
     * the rotated password sits in the remembered entry at the end of the chain: the vaulted `prod.pw` has nothing
     * before it to open it, so `prod` can no longer be unlocked in the IDE until the manager is updated.
     */
    fun testAPendingRotationDoesNotLockOutAnIdWhosePasswordFileItRotated() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        val kite = projectRoot("kite", "[defaults]\nvault_identity_list = prod@prod.pw\n")
        write("$kite/prod.pw", VaultVectors.encrypt(VaultVectors.PROD, VaultVectors.PW1).formatBytes())
        write(
            "$kite/group_vars/all/vault.yml",
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Kite0Old0Value001", VaultVectors.PW1)) +
                VaultVectors.inline("vault_prod_password", VaultVectors.encrypt("Prod0Old0Value001", VaultVectors.PROD, "prod")),
        )
        root = kite
        ready()
        useOnePassword(kite)
        val kiteRoot = root(kite)
        assertTrue(await { secrets.unlock(kiteRoot) } is VaultUnlockResult.Unlocked)
        assertTrue("before: prod loads from its vaulted file", secrets.unlockedLabels(registry.discovery(kiteRoot)).contains("prod"))

        rotate(vf(kite))
        assertEquals("prod.pw was rotated", VaultVectors.PROD, decryptWith(text("$kite/prod.pw"), newPassword))

        restart()
        await { secrets.unlock(kiteRoot) }
        assertTrue(
            "after the idle lock prod cannot be unlocked any more: ${secrets.unlockedLabels(registry.discovery(kiteRoot))}",
            secrets.unlockedLabels(registry.discovery(kiteRoot)).contains("prod"),
        )
    }

    // ------------------------------------------------------------------------------------------------ 10

    /**
     * Decision 5 is only half done: a root whose `.env.local` was not read yet gets a key of its own (the file it is
     * expected to name, or the `.env.local` itself), so its own group is not written. But the file that `.env.local`
     * really names may be another group's password file, and that group is written: heron's `.env.local` names
     * falcon's shared password file; rotating falcon replaces it, and heron's vaults keep the old password while
     * heron's source now gives the new one.
     */
    fun testAnUnreadEnvLocalThatNamesTheRotatedFileKeepsItsRootOpenable() {
        val shared = write("shared/vault.pw", "${VaultVectors.PW1}\n")
        val heron = projectRoot("heron")
        write("$heron/.env.local", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=$shared\n")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        write("$heron/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Heron0Old0Value001", VaultVectors.PW1)))
        ready()
        VaultProjectSettings.getInstance(project).update(rootKey(root)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.PASSWORD_FILE, shared.toString())))
        }

        rotate(vf(root))

        val groups = rotationPrompts.requests.single().identity.groups
        val heronRoot = root(heron)
        val heronGroup = groups.single { group -> group.roots.any { it.root == heronRoot } }
        val sourceNow = String(Files.readAllBytes(shared)).trim()
        assertEquals("falcon's group wrote the shared file", newPassword, sourceNow)
        assertTrue("heron reads that file through its .env.local", await { secrets.unlock(heronRoot) } is VaultUnlockResult.Unlocked)
        assertEquals(
            "heron's vault opens with what heron's source gives now (heron was a separate, unchecked group: checked=${heronGroup.checked}, " +
                "key=${heronGroup.source.key})",
            "Heron0Old0Value001",
            valueOf("$heron/group_vars/all/vault.yml", "vault_db_password", sourceNow),
        )
    }
}
