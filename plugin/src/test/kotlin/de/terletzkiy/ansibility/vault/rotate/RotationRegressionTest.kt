package de.terletzkiy.ansibility.vault.rotate

import com.intellij.openapi.ide.CopyPasteManager
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
import de.terletzkiy.ansibility.vault.reencrypt.ReencryptionWriter
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.security.SecureRandom

/**
 * The review of Rotate Vault Password… (plan amendment R29, Part 1; review of commit 95d434e): each test states an
 * invariant of the rotation that the reviewed commit broke (every test failed there), with the finding number of the
 * review. Where a decision of the fixes changes the expected outcome (the rotation now stops, or now rotates what it
 * used to skip), the assertion says so. Synthetic passwords and values only.
 */
class RotationRegressionTest : RotationTestCase() {
    private fun rootKey(relative: String): String = registry.discovery(root(relative)).rootKey

    private fun reveal(relative: String, key: String): VaultDecryptResult =
        await { VaultOperations.getInstance(project).decrypt(location(relative, key), VaultPurpose.REVEAL) }
            .also { (it as? VaultDecryptResult.Decrypted)?.plaintext?.close() }

    private fun useOnePassword(relative: String) {
        VaultProjectSettings.getInstance(project).update(rootKey(relative)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.ONE_PASSWORD, "op://Private/vault/password")))
        }
    }

    // ------------------------------------------------------------------------------------------------ finding 1

    /**
     * Finding 1: a second rotation while the first one's source (a password manager) was not updated yet. After the
     * idle lock the manager's old password is first in the chain, so the second rotation's "current password" opens
     * nothing; it still overwrites the IDE password store entry (the only place holding the password the files need)
     * and zeroes it in memory. Nothing the IDE keeps opens the vault files afterwards.
     */
    fun testASecondRotationBeforeTheManagerWasUpdatedKeepsThePasswordTheFilesNeed() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)

        rotate()
        val first = newPassword
        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", first))

        // The summary said "Update now"; before that happens the idle lock runs and the user rotates again.
        secrets.lockAll()
        newPassword = NewPassword.generate(SecureRandom()).use { String(it.chars()) }
        rotate()

        // Decisions 1 and 9: after the idle lock the manager's old password is skipped (it is the "before rotation" one)
        // and the stored first rotation's password is proven to open the value, so the second rotation rotates it.
        assertEquals(1, rotationPrompts.reviews.last().roots.single().values)
        assertEmpty(rotationPrompts.reviews.last().leftAlone)
        assertNotNull(service.lastSummary)
        val opens = listOfNotNull(stored(falcon), previous(falcon), newPassword).any { valueOf(vaultPath, "vault_db_password", it) != null }
        assertTrue("no password the IDE keeps opens vault_db_password any more (the first rotation's password is gone)", opens)
        assertTrue("the IDE can no longer reveal the value", reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted)
    }

    /**
     * Finding 1, second shape: `.vault-pass` still holds an old password, the team's current one was stored on the
     * settings page (the id's IDE password store entry). The IDE opens every value; Rotate takes the stale file's
     * password as "current", rotates nothing, and overwrites the stored password with the new one.
     */
    fun testAStalePasswordFileDoesNotCostTheStoredPasswordThatOpensTheFiles() {
        val teams = "team-password-after-a-rotation-elsewhere"
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", teams)))
        ready()
        val falcon = root(root)
        secrets.storePassword(falcon, "default", teams.toCharArray())
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)
        assertTrue("before: the IDE opens the value", reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted)

        rotate()

        // Decision 1: the password that opens the value comes from the IDE password store, not from the source the
        // dialog showed (.vault-pass, which holds another one): the rotation stops before anything is written or stored.
        assertEmpty("stopped before the review", rotationPrompts.reviews)
        val stop = feedback().single()
        assertTrue(stop, stop.startsWith("Nothing was rotated: in ") && stop.contains(".vault-pass, which holds another one"))
        assertEquals("the stored team password is kept", teams, stored(falcon))
        val opens = listOfNotNull(stored(falcon), previous(falcon), text("$root/.vault-pass").trim()).any { valueOf(vaultPath, "vault_db_password", it) != null }
        assertTrue("the stored team password was overwritten and nothing kept opens the value", opens)
        assertTrue("after: the IDE can no longer reveal the value", reveal(vaultPath, "vault_db_password") is VaultDecryptResult.Decrypted)
    }

    // ------------------------------------------------------------------------------------------------ finding 2

    /**
     * Finding 2 (D207): "a vaulted password file that this id opens is re-encrypted like any other vault file". The
     * `prod` password file is vaulted with the default password; it is not in the `ansible.vault` index, so the rotation
     * never sees it and the `prod` id can no longer be loaded after the rotation.
     */
    fun testAVaultedPasswordFileThisIdOpensIsRotated() {
        val kite = projectRoot("kite", "[defaults]\nvault_identity_list = default@.vault-pass, prod@prod.pw\n")
        write("$kite/.vault-pass", "${VaultVectors.PW1}\n")
        write("$kite/prod.pw", VaultVectors.encrypt(VaultVectors.PROD, VaultVectors.PW1).formatBytes())
        write(
            "$kite/group_vars/all/vault.yml",
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Kite0Old0Value001", VaultVectors.PW1)) +
                VaultVectors.inline("vault_prod_password", VaultVectors.encrypt("Prod0Old0Value001", VaultVectors.PROD, "prod")),
        )
        root = kite
        ready()
        val kiteRoot = root(kite)
        assertTrue("before: prod loads from its vaulted file", await { secrets.unlock(kiteRoot) } is VaultUnlockResult.Unlocked)
        assertTrue(secrets.unlockedLabels(registry.discovery(kiteRoot)).contains("prod"))

        rotate(vf(kite))

        assertEquals("prod.pw is rotated (D207)", VaultVectors.PROD, decryptWith(text("$kite/prod.pw"), newPassword))
    }

    // ------------------------------------------------------------------------------------------------ findings 3 and 4

    /** Finding 4 (D207): a vaulted template (`*.j2`, which Ansible's template action decrypts) is outside the index's input filter. */
    fun testAVaultedTemplateTheIndexDoesNotSeeIsRotated() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        write("$root/templates/app.conf.j2", VaultVectors.encrypt("listen 8080;\n", VaultVectors.PW1).formatBytes())
        ready()

        rotate()

        assertEquals("rotated with the value", "Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals("the vaulted template is rotated too (D207)", "listen 8080;\n", decryptWith(text("$root/templates/app.conf.j2"), newPassword))
    }

    /**
     * Finding 3 (D207): inline `!vault` values in a vars file without a YAML name (`group_vars/<group>`, which Ansible
     * loads and the `ansible.vault` index counts) are skipped: the preflight reads them through the IDE's PSI, which is
     * plain text for such a file. They are neither rotated nor listed as left alone.
     */
    fun testAnInlineValueInAVarsFileWithoutYamlNameIsRotated() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        write("$root/group_vars/webservers", VaultVectors.inline("vault_web_password", VaultVectors.encrypt("Web0Old0Value0001", VaultVectors.PW1)))
        ready()

        rotate()

        val request = rotationPrompts.requests.single()
        assertEquals("the dialog counts both values (index)", 2, request.identity.counts.inline)
        val review = rotationPrompts.reviews.single()
        assertEquals("rotated with the YAML file", "Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertEquals(
            "the value in group_vars/webservers is rotated (review: ${review.roots.single().values} values, left alone ${review.leftAlone})",
            "Web0Old0Value0001",
            valueOf("$root/group_vars/webservers", "vault_web_password", newPassword),
        )
    }

    /** Finding 4 (D207): a whole-file vault larger than the index's 1 MiB limit is not rotated. */
    fun testALargeWholeFileVaultIsRotated() {
        val random = SecureRandom()
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val plaintext = String(CharArray(300 * 1024) { alphabet[random.nextInt(alphabet.length)] })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        val big = write("$root/files/bundle.bin", VaultVectors.encrypt(plaintext, VaultVectors.PW1).formatBytes())
        assertTrue("larger than the index limit", Files.size(big) > 1024L * 1024)
        ready()

        rotate()

        assertEquals("Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        assertTrue("the large vault file is rotated too (D207)", decryptWith(text("$root/files/bundle.bin"), newPassword) == plaintext)
    }

    // ------------------------------------------------------------------------------------------------ finding 5

    /**
     * Finding 5 (D206, D207): heron reads the same password file for `default` as falcon but holds only a vault labelled
     * `new` (which the default password opens, as Ansible tries every secret). heron is not listed for `default`, so it
     * is neither grouped with falcon nor rotated, while its source gets the new password.
     */
    fun testARootSharingTheSourceWithOnlyOtherLabelsIsRotatedToo() {
        val shared = write("shared/vault.pw", "${VaultVectors.PW1}\n")
        val heron = projectRoot("heron")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        write("$heron/files/legacy.key", VaultVectors.encrypt("synthetic key material\n", VaultVectors.PW1, "new").formatBytes())
        ready()
        for (relative in listOf(root, heron)) {
            VaultProjectSettings.getInstance(project).update(rootKey(relative)) {
                it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.PASSWORD_FILE, shared.toString())))
            }
        }

        rotate(vf(root))

        val heronRoot = root(heron)
        val groups = rotationPrompts.requests.single().identity.groups
        // Decision 5: heron reads the same source for 'default', so it joins falcon's group (it was left out before).
        assertTrue("heron is in falcon's group", groups.single().roots.any { it.root == heronRoot })
        val sourceNow = String(Files.readAllBytes(shared)).trim()
        assertEquals("the shared file got the new password", newPassword, sourceNow)
        assertEquals(
            "heron's vault opens with what heron's source holds now",
            "synthetic key material\n",
            decryptWith(text("$heron/files/legacy.key"), sourceNow),
        )
    }

    // ------------------------------------------------------------------------------------------------ finding 6

    /**
     * Finding 6 (D210): mode 3 writes with `VirtualFile.setBinaryContent`, which for a local file opens the file with
     * `Files.newOutputStream` (truncate, then write; no safe write, since the requestor is the file itself). A write that
     * fails after the truncation (a full disk) leaves the failing file empty: it is not in the list of files put back.
     */
    fun testAWriteThatFailsMidwayLeavesTheFailingFileIntact() {
        write("$root/group_vars/all/a.yml", VaultVectors.inline("vault_a_password", VaultVectors.encrypt("A0Old0Value0001", VaultVectors.PW1)))
        write("$root/group_vars/all/b.yml", VaultVectors.inline("vault_b_password", VaultVectors.encrypt("B0Old0Value0001", VaultVectors.PW1)))
        ready()
        val b = bytes("$root/group_vars/all/b.yml")
        // The operating system truncated b.yml when it was opened for writing, then the write failed (ENOSPC).
        ReencryptionWriter.failWriteForTests = { file ->
            (file.name == "b.yml").also { if (it) Files.write(base.resolve("$root/group_vars/all/b.yml"), ByteArray(0)) }
        }

        rotate()

        assertEquals(listOf("Nothing was rotated: writing b.yml failed, and the files written before it have their old content again."), feedback())
        assertTrue("b.yml keeps its old envelopes ('Nothing was rotated')", b.contentEquals(bytes("$root/group_vars/all/b.yml")))
    }

    // ------------------------------------------------------------------------------------------------ finding 9

    /**
     * Finding 9 (D211): after a rotation whose source is not written (a password manager), the idle lock and the next
     * unlock put the manager's old password first in the chain again: Encrypt then uses the OLD password, so values
     * encrypted before the manager is updated stop opening once it is.
     */
    fun testEncryptingAfterTheIdleLockUsesTheNewPassword() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)

        rotate()
        secrets.lockAll()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)

        val encrypted = await { VaultOperations.getInstance(project).encrypt(falcon, "New0Value0000001".toByteArray(), "default", vf(vaultPath)) }
        val envelope = (encrypted as VaultEncryptResult.Encrypted).envelope
        assertEquals("a value encrypted after the rotation opens with the new password", "New0Value0000001", decryptWith(envelope, newPassword))
    }

    /** Finding 9 (D213): Copy New Password copies the id's first unlocked secret: after the idle lock that is the old password. */
    fun testCopyNewPasswordAfterTheIdleLockCopiesTheNewPassword() {
        secrets.setPasswordManagersForTests(PasswordManagerReader { _, _, _ -> VaultVectors.PW1.toByteArray() })
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        useOnePassword(root)
        val falcon = root(root)

        rotate()
        val summary = service.lastSummary!!
        secrets.lockAll()
        assertTrue(await { secrets.unlock(falcon) } is VaultUnlockResult.Unlocked)

        service.copyNewPassword(summary)
        // Copy New Password reads the IDE password store in the background now (never on the EDT).
        awaitRotation()

        val copied = CopyPasteManager.getInstance().contents?.getTransferData(DataFlavor.stringFlavor)
        assertFalse("Copy New Password copied the OLD password", copied == VaultVectors.PW1)
        assertEquals(newPassword, copied)
    }

    // ------------------------------------------------------------------------------------------------ finding 10

    /**
     * Finding 10 (D206, D211): step 1 writes the new password to `storedEntry`, which is a configured PasswordSafe entry
     * of the id whenever the chain has one, even when the rotation's source (the group key) is a password file before
     * it. A configured entry can be shared with roots outside the rotation ("team#shared"): heron, a separate
     * unchecked group reading that entry, gets the new password while its files keep the old envelopes.
     */
    fun testAnEntrySharedWithAnUnchosenRootIsNotOverwritten() {
        val heron = projectRoot("heron")
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Falcon0Old0Value01", VaultVectors.PW1)))
        write("$heron/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Heron0Old0Value001", VaultVectors.PW1)))
        ready()
        val sharedService = VaultPasswordSafeKeys.ownServiceName("team#shared")
        credentials.write(sharedService, "default", VaultVectors.PW1.toCharArray(), memoryOnly = false)
        VaultProjectSettings.getInstance(project).update(rootKey(root)) {
            it.copy(
                identities = listOf(
                    ExplicitIdentity("default", VaultSourceKind.PASSWORD_FILE, base.resolve("$root/.vault-pass").toString()),
                    ExplicitIdentity("default", VaultSourceKind.PASSWORD_SAFE, "team#shared"),
                ),
            )
        }
        VaultProjectSettings.getInstance(project).update(rootKey(heron)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.PASSWORD_SAFE, "team#shared")))
        }
        val heronRoot = root(heron)

        rotate(vf(root))

        val groups = rotationPrompts.requests.single().identity.groups
        assertFalse("heron's group was not chosen", groups.single { group -> group.roots.any { it.root == heronRoot } }.checked)
        val entry = credentials.read(sharedService, "default")?.let { String(it) }
        assertTrue("heron's source (the shared entry) was overwritten with the new password", entry == VaultVectors.PW1)
        secrets.lockAll()
        assertTrue(await { secrets.unlock(heronRoot) } is VaultUnlockResult.Unlocked)
        assertTrue("heron still opens its own values", reveal("$heron/group_vars/all/vault.yml", "vault_db_password") is VaultDecryptResult.Decrypted)
    }

    // ------------------------------------------------------------------------------------------------ finding 11

    /**
     * Finding 11 (D207 vs D209): `prod` has its own password file that happens to hold the same password as `default`.
     * The review says "kite's prod has the same password and is not included; it keeps the old one", but every `prod`
     * envelope is re-encrypted with default's NEW password, so prod's own source no longer opens prod's values (a run
     * that is given only the prod password, e.g. a prod CI job, fails).
     */
    fun testAnotherIdWithTheSamePasswordKeepsItsEnvelopes() {
        val kite = projectRoot("kite", "[defaults]\nvault_identity_list = default@.vault-pass, prod@prod.pw\n")
        write("$kite/.vault-pass", "${VaultVectors.PW1}\n")
        write("$kite/prod.pw", "${VaultVectors.PW1}\n")
        write(
            "$kite/group_vars/all/vault.yml",
            VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Kite0Old0Value001", VaultVectors.PW1)) +
                VaultVectors.inline("vault_prod_password", VaultVectors.encrypt("Prod0Old0Value001", VaultVectors.PW1, "prod")),
        )
        root = kite
        ready()
        val kiteRoot = root(kite)
        assertTrue(await { secrets.unlock(kiteRoot) } is VaultUnlockResult.Unlocked)

        rotate(vf(kite))

        val notes = rotationPrompts.reviews.single().equalPasswords
        assertEquals(listOf("${kiteRoot.displayName}'s prod has the same password and is not included; it keeps the old one."), notes)
        assertEquals("prod's file keeps the old password", VaultVectors.PW1, text("$kite/prod.pw").trim())
        assertEquals("prod's value still opens with prod's own password", "Prod0Old0Value001", valueOf("$kite/group_vars/all/vault.yml", "vault_prod_password", VaultVectors.PW1))
    }

    // ------------------------------------------------------------------------------------------------ finding 13

    /**
     * Finding 13 (D210): only an IOException of a file write is handled. Any other exception during the mode 3 loop
     * (simulated here by the test hook throwing) leaves the files written so far with new envelopes, the rest with old
     * ones and the IDE password store with the new password; nothing is put back and the message is a generic failure.
     */
    fun testAnUnexpectedErrorMidwayStillWritesAllOrNothing() {
        write("$root/group_vars/all/a.yml", VaultVectors.inline("vault_a_password", VaultVectors.encrypt("A0Old0Value0001", VaultVectors.PW1)))
        write("$root/group_vars/all/b.yml", VaultVectors.inline("vault_b_password", VaultVectors.encrypt("B0Old0Value0001", VaultVectors.PW1)))
        ready()
        val falcon = root(root)
        val a = bytes("$root/group_vars/all/a.yml")
        ReencryptionWriter.failWriteForTests = { file -> if (file.name == "b.yml") throw IllegalStateException("an unexpected failure") else false }

        try {
            rotate()
        } catch (_: Throwable) {
            // A logged error of the platform; the state below is what matters.
        }

        val aRotated = valueOf("$root/group_vars/all/a.yml", "vault_a_password", newPassword) != null
        val storeNew = stored(falcon) == newPassword
        assertTrue(
            "all or nothing broken: a.yml rotated=$aRotated, b.yml not, store holds the new password=$storeNew, feedback=${feedback()}",
            a.contentEquals(bytes("$root/group_vars/all/a.yml")),
        )
    }

    // ------------------------------------------------------------------------------------------------ finding 8

    /**
     * Finding 8 (D204, D211): Rotate Password… on Settings › Vault runs while the page has unapplied edits. A password
     * typed for the id on the page before rotating is stored by the page's Apply/OK afterwards, over the rotation's new
     * password; for an id whose source is the IDE password store that was the only copy of the new password.
     */
    fun testApplyingTheSettingsPageAfterRotatingThereKeepsTheNewPassword() {
        val owl = projectRoot("owl")
        write("$owl/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Owl0Old0Value0001", VaultVectors.PW1)))
        root = owl
        ready()
        val owlRoot = root(owl)
        VaultProjectSettings.getInstance(project).update(rootKey(owl)) {
            it.copy(identities = listOf(ExplicitIdentity("default", VaultSourceKind.PASSWORD_SAFE, null)))
        }
        val entry = VaultPasswordSafeKeys.serviceName(registry.discovery(owlRoot).canonicalRootPath, "default")
        credentials.write(entry, "default", VaultVectors.PW1.toCharArray(), memoryOnly = false)

        val page = de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable(project)
        try {
            val component = page.createComponent()
            @Suppress("UNCHECKED_CAST")
            val roots = com.intellij.util.ui.UIUtil.findComponentsOfType(component, com.intellij.openapi.ui.ComboBox::class.java).first() as com.intellij.openapi.ui.ComboBox<Any>
            com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("the roots did not load", { roots.itemCount > 0 }, 60)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            roots.selectedIndex = (0 until roots.itemCount).single { (roots.getItemAt(it) as de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable.RootEntry).name == owlRoot.displayName }
            val table = com.intellij.util.ui.UIUtil.findComponentsOfType(component, com.intellij.ui.table.TableView::class.java)
                .first { it.listTableModel.items.firstOrNull() is de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable.IdRow }
            val row = table.listTableModel.items.single() as de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable.IdRow
            // Typed for the id on the page (Edit…), not applied yet.
            row.password = "typed-on-the-page-before-rotating".toCharArray()
            val button = com.intellij.util.ui.UIUtil.findComponentsOfType(component, javax.swing.JButton::class.java)
                .single { it.name == de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable.ROTATE_COMPONENT }

            // Decision 8: with unapplied changes Rotate Password… starts nothing and says to apply first.
            button.doClick()
            assertNull("no rotation starts while the page has unapplied changes", service.lastRun)
            assertFalse(button.isEnabled)
            val blocked = com.intellij.util.ui.UIUtil.findComponentsOfType(component, javax.swing.JLabel::class.java)
                .single { it.name == de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable.ROTATE_BLOCKED_COMPONENT }
            assertTrue(blocked.isVisible)
            assertEquals("Apply your changes first.", blocked.text)
            // The typed password is discarded (Reset), then the rotation runs; the page reloads afterwards.
            page.reset()
            assertTrue(button.isEnabled)
            button.doClick()
            awaitRotation()
            assertEquals("rotated", "Owl0Old0Value0001", valueOf("$owl/group_vars/all/vault.yml", "vault_db_password", newPassword))
            assertTrue("the id's entry holds the new password", stored(owlRoot) == newPassword)
            assertFalse("nothing left to apply after the rotation", page.isModified)

            page.apply()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && stored(owlRoot) == newPassword) {
                com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(50)
            }
        } finally {
            page.disposeUIResources()
        }

        assertTrue(
            "after OK on the settings page the IDE password store (the id's only source) no longer holds the new password",
            stored(owlRoot) == newPassword,
        )
    }

    // ------------------------------------------------------------------------------------------------ finding 15

    /**
     * Finding 15 (D211, secret rules): a password remembered for this session only (PasswordSafe's memory-only store) is
     * written back PERSISTENTLY when a refused rotation puts the store back (`restoreStore` writes with
     * `memoryOnly = false`, and the label stays persistently remembered).
     */
    fun testARefusedRotationKeepsASessionOnlyPasswordOutOfTheKeychain() {
        val owl = projectRoot("owl")
        write("$owl/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Owl0Old0Value0001", VaultVectors.PW1)))
        root = owl
        ready()
        val owlRoot = root(owl)
        prompter.passwords.add(VaultVectors.PW1 to de.terletzkiy.ansibility.vault.secrets.RememberChoice.SESSION)
        assertTrue(await { secrets.unlock(owlRoot) } is VaultUnlockResult.Unlocked)
        val key = VaultPasswordSafeKeys.serviceName(registry.discovery(owlRoot).canonicalRootPath, "default") + "|default"
        assertTrue("remembered for this session only", key in credentials.memoryOnlyKeys)
        ReencryptionWriter.failWriteForTests = { it.name == "vault.yml" }

        rotate()

        assertEquals(listOf("Nothing was rotated: writing vault.yml failed, and the files written before it have their old content again."), feedback())
        assertTrue("the session-only password went to the persistent store", key in credentials.memoryOnlyKeys)
    }

    // ------------------------------------------------------------------------------------------------ finding 7

    /**
     * Finding 7 (D210: "a file that changed since the preflight stops the whole write before the first byte"): mode 3's
     * staleness check looks only at the VFS stamp and the document; a change on disk the VFS has not refreshed yet (a
     * `git pull` in a terminal while the review is open) is overwritten by `setBinaryContent` with the rotated old
     * text: the pulled change is lost.
     */
    fun testAChangeOnDiskDuringTheReviewIsNotOverwritten() {
        write(vaultPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("Db0Old0Value0001", VaultVectors.PW1)))
        ready()
        rotationPrompts.onReview = {
            // A teammate's value arrives through git while the review is open; the IDE has not refreshed yet.
            Files.write(base.resolve(vaultPath), (text(vaultPath) + "vault_added_meanwhile: not-a-secret\n").toByteArray())
            true
        }

        rotate()

        val now = text(vaultPath)
        assertTrue("the change made on disk during the review was overwritten", now.contains("vault_added_meanwhile"))
    }

    // ------------------------------------------------------------------------------------------------ finding 16

    /**
     * Finding 16 (D212, D213): a password file with raw random bytes (`openssl rand 32 > .vault-pass`) is a valid Ansible
     * password but no UTF-8 text: the rotation silently keeps no previous password, while the summary still says "the
     * old one is kept there as 'before rotation'". Rotating back (D212) is then impossible.
     */
    fun testABinaryOldPasswordIsKeptOrTheSummarySaysSo() {
        val old = byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte(), 0x7F, 0x41, 0xFE.toByte(), 0xFF.toByte(), 0x42, 0x80.toByte(), 0x43, 0x44)
        write("$root/.vault-pass", old)
        val envelope = de.terletzkiy.ansibility.semantics.vault.SecretBytes.of(old).use {
            de.terletzkiy.ansibility.semantics.vault.VaultAes256.encrypt("Db0Old0Value0001".toByteArray(), it, null)
        }
        write(vaultPath, VaultVectors.inline("vault_db_password", envelope))
        ready()
        val falcon = root(root)

        rotate()

        assertEquals("rotated", "Db0Old0Value0001", valueOf(vaultPath, "vault_db_password", newPassword))
        val summary = service.lastSummary!!.text
        assertTrue(
            "no previous password was kept (D212), yet the summary says it was: ${summary.contains("the old one is kept there")}",
            previous(falcon) != null || !summary.contains("the old one is kept there"),
        )
    }
}
