package de.terletzkiy.ansibility.vault.vcs

import com.intellij.configurationStore.serialize
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.CommitExecutor
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.vcsUtil.VcsUtil
import de.terletzkiy.ansibility.inspections.vault.TestHashes
import de.terletzkiy.ansibility.inspections.vault.TestKeys
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.commit.CommitSecretKind
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.monitor.SecretFix
import de.terletzkiy.ansibility.vault.monitor.pasted
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable
import de.terletzkiy.ansibility.vault.vcs.impl.FixableSecretCommitProblem
import de.terletzkiy.ansibility.vault.vcs.impl.SecretCheckinHandlerFactory
import de.terletzkiy.ansibility.vault.vcs.impl.SecretCommitCheck
import de.terletzkiy.ansibility.vault.vcs.impl.SecretCommitProblem
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The commit check (plan amendment R21, D167) on a synthetic root `repos/falcon/ansible` with `.vault-pass` (id
 * default, password pw1 of `tools/vault/SYNTHETIC.md`) in a real temporary directory, driven with synthetic
 * [Change]s: the working tree's files (`CurrentContentRevision`) and staged contents ([Staged], a
 * `ByteBackedContentRevision` holding the bytes the commit takes). Keys are made at test time ([TestKeys]).
 */
class SecretCommitCheckTest : VaultUiTestCase() {
    private lateinit var root: String

    /** The bytes a commit takes from the staging area, which may differ from the working tree; counts its reads. */
    private class Staged(private val path: FilePath, private val bytes: ByteArray) : ByteBackedContentRevision {
        var reads = 0

        override fun getContentAsBytes(): ByteArray = bytes.also { reads++ }

        override fun getContent(): String = String(bytes, Charsets.UTF_8).also { reads++ }

        override fun getFile(): FilePath = path

        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    /** A before-revision whose reads wait (at most 10 s) until as many as [gate] counts run at the same time. */
    private class Concurrent(private val path: FilePath, private val bytes: ByteArray, private val gate: CountDownLatch, private val active: AtomicInteger, private val most: AtomicInteger) :
        ByteBackedContentRevision {
        override fun getContentAsBytes(): ByteArray {
            most.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            gate.countDown()
            gate.await(10, TimeUnit.SECONDS)
            active.decrementAndGet()
            return bytes
        }

        override fun getContent(): String = String(contentAsBytes, Charsets.UTF_8)

        override fun getFile(): FilePath = path

        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    /** A revision the VCS cannot read. */
    private class Unreadable(private val path: FilePath) : ByteBackedContentRevision {
        override fun getContentAsBytes(): ByteArray = throw VcsException("cannot read")

        override fun getContent(): String = throw VcsException("cannot read")

        override fun getFile(): FilePath = path

        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    private class Info(override val committedChanges: List<Change>, override val isVcsCommit: Boolean = true) : CommitInfo {
        override val commitContext: CommitContext = CommitContext()
        override val executor: CommitExecutor? = null
        override val affectedVcses: List<AbstractVcs> = emptyList()
        override val commitMessage: String = "falcon: certificates"
        override val commitActionText: String = "Commit"
    }

    override fun setUp() {
        super.setUp()
        root = defaultRoot("falcon", cfg = "[defaults]\nvault_password_file = .vault-pass\n")
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun path(relative: String): FilePath = VcsUtil.getFilePath(base.resolve(relative), false)

    /** A file of the working tree, committed as it is (a new file: no before-revision). */
    private fun added(relative: String, text: String? = null): Change {
        text?.let { write(relative, it) }
        return Change(null, CurrentContentRevision(VcsUtil.getFilePath(vf(relative))))
    }

    /** A binary file of the working tree, committed as it is. */
    private fun added(relative: String, bytes: ByteArray): Change {
        write(relative, bytes)
        return added(relative)
    }

    /** A file whose staged [bytes] are committed (whatever the working tree holds). */
    private fun staged(relative: String, bytes: ByteArray, before: ByteArray? = null): Change =
        Change(before?.let { Staged(path(relative), it) }, Staged(path(relative), bytes))

    /** A file of the working tree that was [before] in the last commit. */
    private fun modified(relative: String, text: String, before: ByteArray): Change {
        write(relative, text)
        return Change(Staged(path(relative), before), CurrentContentRevision(VcsUtil.getFilePath(vf(relative))))
    }

    private fun check(vararg changes: Change, vcsCommit: Boolean = true): CommitProblem? {
        root(root)
        return await { SecretCommitCheck(project).runCheck(Info(changes.toList(), vcsCommit)) }
    }

    private fun kinds(problem: CommitProblem?): List<Pair<CommitSecretKind, String>> = when (problem) {
        is SecretCommitProblem -> problem.secrets
        is FixableSecretCommitProblem -> problem.secrets
        null -> emptyList()
        else -> error("not the commit check's problem: $problem")
    }.map { it.kind to it.name }

    private fun vault(text: String = "value"): String = VaultVectors.encrypt(text, VaultVectors.PW1).format()

    private fun disk(relative: String): String {
        (LocalFileSystem.getInstance() as? AsyncableFileSystem)?.fsync()
        return String(Files.readAllBytes(base.resolve(relative)), Charsets.UTF_8)
    }

    private fun decrypt(text: String): String? {
        val envelope = (VaultEnvelope.parse(text) as? EnvelopeParse.Ok)?.envelope ?: return null
        return SecretBytes.of(VaultVectors.PW1.toByteArray()).use { VaultAes256.decrypt(envelope, it)?.let(::String) }
    }

    private fun awaitOperation() {
        val job = VaultFileOperations.getInstance(project).lastOperation ?: error("no operation was started")
        waitFor("file operation timed out") { job.isCompleted }
    }

    // ------------------------------------------------------------------------------------------------ keys

    fun testPlaintextKeysStopTheCommitWithEncryptFilesAndNamesOnly() {
        val web = TestKeys.plaintextKey()
        val db = "# db\n" + TestKeys.certificate() + TestKeys.plaintextKey()
        val problem = check(added("$root/roles/web/files/ssl/web.key", web), added("$root/roles/db/files/ssl/db.pem", db))
        assertTrue(problem.toString(), problem is CommitProblemWithDetails)
        assertEquals("Ansibility Vault: 2 files would be committed with a plaintext private key: web.key, db.pem", problem!!.text)
        assertEquals("Encrypt Files…", (problem as CommitProblemWithDetails).showDetailsAction)
        for (text in listOf(problem.text, problem.toString())) {
            assertFalse("no key material: $text", TestKeys.bodyLine(web) in text || TestKeys.bodyLine(db) in text || TestKeys.PRIVATE in text)
        }
    }

    fun testBinaryPlaintextKeysStopTheCommitButTheWeakerSignalsDoNot() {
        val keystore = KeyStore.getInstance("JKS").apply { load(null, null) }.let { store ->
            java.io.ByteArrayOutputStream().also { store.store(it, "changeit".toCharArray()) }.toByteArray()
        }
        val problem = check(
            added("$root/roles/web/files/ssl/web.der", TestKeys.pkcs8()),
            added("$root/roles/web/files/ssl/web.p12", TestKeys.pkcs12WithPlainKey()),
            added("$root/roles/web/files/ssh/deploy.key", TestKeys.protectedKey()),
            added("$root/roles/web/files/ssl/legacy.key", "not a key yet\n"),
            added("$root/roles/web/files/app.jceks", TestKeys.jceksWithSecretKey()),
            added("$root/roles/web/files/truststore.jks", keystore),
        )
        assertEquals(
            "an unencrypted DER key and an unprotected PKCS#12 key bag are plaintext keys (D161: ERROR)",
            listOf(CommitSecretKind.PLAINTEXT_KEY to "web.der", CommitSecretKind.PLAINTEXT_KEY to "web.p12"),
            kinds(problem),
        )
        assertEquals("Ansibility Vault: 2 files would be committed with a plaintext private key: web.der, web.p12", problem!!.text)
        assertNull(
            "D167 stops plaintext keys: a protected key, a keystore and a key-like name are warnings of the editor and the tab",
            check(added("$root/roles/web/files/ssh/deploy.key"), added("$root/roles/web/files/ssl/legacy.key"), added("$root/roles/web/files/app.jceks")),
        )
    }

    fun testAnAllowlistGlobThatMatchesEveryPathIsIgnored() {
        VaultProjectSettings.getInstance(project).plaintextKeyAllowlist = listOf("**")
        assertEquals(listOf(CommitSecretKind.PLAINTEXT_KEY to "web.key"), kinds(check(added("$root/roles/web/files/ssl/web.key", TestKeys.plaintextKey()))))
        assertEquals(listOf(CommitSecretKind.PASSWORD_FILE to ".vault-pass"), kinds(check(added("$root/.vault-pass"))))
    }

    fun testCertificatesProperVaultsAndCleanFilesPass() {
        val problem = check(
            added("$root/roles/web/files/ssl/web.crt", TestKeys.certificate()),
            added("$root/roles/web/files/ssl/web.key", vault(TestKeys.plaintextKey())),
            added("$root/group_vars/all/vault.yml", vault("vault_db_password: secret\n")),
            added("$root/group_vars/all/main.yml", "db_password: \"{{ vault_db_password }}\"\n" + VaultVectors.inline("db_token", VaultVectors.encrypt("token", VaultVectors.PW1))),
            added("$root/README.md", "Keys look like ${TestKeys.begin(TestKeys.PRIVATE)} followed by MIIE…\n"),
            added("$root/roles/web/files/ssl/empty.key", ""),
        )
        assertNull("nothing to stop: $problem", problem)
    }

    fun testMoleculeTestKeysAndTheAllowlistAreLeftAlone() {
        val key = TestKeys.plaintextKey()
        assertNull(check(added("$root/roles/web/molecule/default/files/ssl/test.key", key)))
        VaultProjectSettings.getInstance(project).plaintextKeyAllowlist = listOf("**/legacy/**")
        assertNull("an allowlisted folder", check(added("$root/legacy/certs/web.key", key)))
        assertEquals(
            "the replaced allowlist no longer covers Molecule",
            listOf(CommitSecretKind.PLAINTEXT_KEY to "test.key"),
            kinds(check(added("$root/roles/web/molecule/default/files/ssl/test.key"))),
        )
    }

    // ------------------------------------------------------------------------------------------------ committed bytes

    fun testTheCommittedBytesDecideNotTheWorkingTree() {
        val relative = "$root/roles/web/files/ssl/web.key"
        write(relative, TestKeys.certificate())
        val key = TestKeys.plaintextKey().toByteArray()
        val staged = staged(relative, key)
        assertEquals(
            "the staged key is committed although the working tree holds a certificate",
            listOf(CommitSecretKind.PLAINTEXT_KEY to "web.key"),
            kinds(check(staged)),
        )
        assertEquals("the staged bytes were read", 1, (staged.afterRevision as Staged).reads)

        val key2 = TestKeys.plaintextKey()
        write(relative, key2)
        assertNull("a staged vault passes although the working tree holds the key in plaintext", check(staged(relative, vault(key2).toByteArray())))
    }

    fun testAStagedFileTheWorkingTreeNoLongerHasIsCheckedByItsPath() {
        val problem = check(staged("$root/roles/web/files/ssl/gone.key", TestKeys.plaintextKey().toByteArray()))
        assertTrue("no working-tree file to encrypt: Commit Anyway only ($problem)", problem is SecretCommitProblem)
        assertEquals(listOf(CommitSecretKind.PLAINTEXT_KEY to "gone.key"), kinds(problem))
        assertNull(
            "the allowlist applies by path",
            check(staged("$root/roles/web/molecule/default/files/ssl/gone.key", TestKeys.plaintextKey().toByteArray())),
        )
        val wrapped = check(staged("$root/roles/web/files/ssl/gone.pem", pasted(VaultVectors.encrypt("web key", VaultVectors.PW1)).toByteArray()))
        assertTrue("nothing to convert in the working tree ($wrapped)", wrapped is SecretCommitProblem)
        assertEquals(listOf(CommitSecretKind.BROKEN_VAULT to "gone.pem"), kinds(wrapped))
    }

    fun testAnUnreadableRevisionNeverStopsACommit() {
        val relative = "$root/roles/web/files/ssl/web.key"
        write(relative, TestKeys.plaintextKey())
        assertNull(check(Change(null, Unreadable(path(relative)))))
    }

    // ------------------------------------------------------------------------------------------------ vaults

    fun testBrokenVaultsStopTheCommitAndConvertFixesTheWorkingTree() {
        val pastedKey = "$root/roles/web/files/ssl/web.key"
        val envelope = VaultVectors.encrypt("web key", VaultVectors.PW1)
        val problem = check(
            added(pastedKey, pasted(envelope)),
            added("$root/roles/web/files/app.vault", "\$ANSIBLE_VAULT;1.1;AES256\n6162zz\n"),
        )
        assertEquals(
            listOf(CommitSecretKind.BROKEN_VAULT to "web.key", CommitSecretKind.BROKEN_VAULT to "app.vault"),
            kinds(problem),
        )
        assertEquals("Ansibility Vault: 2 files would be committed with a vault Ansible does not decrypt: web.key, app.vault", problem!!.text)
        val fixable = problem as FixableSecretCommitProblem
        assertEquals("Convert to Whole-File Vault", fixable.showDetailsAction)
        assertEquals("only the pasted block can be converted", listOf("web.key"), fixable.fix.files.map { it.name })

        fixable.showDetails(project)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("the working tree is a whole-file vault now", envelope.format(), disk(pastedKey))
        assertNull("committing again passes the converted file", check(added(pastedKey)))

        val malformed = check(added("$root/roles/web/files/app.vault"))
        assertTrue("a malformed vault has no fix: Commit Anyway only", malformed is SecretCommitProblem)
    }

    fun testAVaultThatIsPlaintextNowStopsTheCommitAndEncryptFilesEncryptsItAgain() {
        val relative = "$root/group_vars/all/vault.yml"
        val plaintext = "vault_db_password: secret\n"
        val problem = check(modified(relative, plaintext, vault(plaintext).toByteArray()))
        assertEquals(listOf(CommitSecretKind.DECRYPTED_VAULT to "vault.yml"), kinds(problem))
        assertEquals("Ansibility Vault: 1 file that was a vault would be committed in plaintext: vault.yml", problem!!.text)
        val fixable = problem as FixableSecretCommitProblem
        assertEquals("Encrypt Files…", fixable.showDetailsAction)
        assertEquals(SecretFix.ENCRYPT, fixable.fix.fix)

        fixable.showDetails(project)
        awaitOperation()
        val written = disk(relative)
        assertTrue(written, written.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEquals(plaintext, decrypt(written))

        assertNull("it was plaintext before as well", check(modified("$root/group_vars/all/main.yml", "db_port: 5432\n", "db_port: 5433\n".toByteArray())))
        assertNull("a vault that stays a vault", check(modified("$root/group_vars/db/vault.yml", vault("a: 1\n"), vault("a: 2\n").toByteArray())))
    }

    fun testMovingAVaultIntoInlineValuesIsNoDecryptedVault() {
        val relative = "$root/group_vars/all/vault.yml"
        val before = Staged(path(relative), vault("db_password: secret\napi_token: other\n").toByteArray())
        write(relative, "db_user: falcon\n" + VaultVectors.inline("db_password", VaultVectors.encrypt("secret", VaultVectors.PW1)))
        assertNull(check(Change(before, CurrentContentRevision(VcsUtil.getFilePath(vf(relative))))))
        assertEquals("inline !vault values are no plaintext: the past is not read", 0, before.reads)
    }

    /**
     * User report 2026-10-08: key-like files of password hashes are no ANS-V108 finding and no Vault-tab entry, but the
     * commit check does not ask (D167): a file the team vaulted that is plaintext now still stops the commit, hashes or
     * not (a MySQL native hash is unsalted, and git history keeps it). A hash file that was never a vault passes, as every
     * key-like name does.
     */
    fun testAVaultThatHoldsPasswordHashesNowStillStopsTheCommit() {
        val hashes = listOf(
            "$root/roles/db/files/mysql/users/alice.password" to TestHashes.MYSQL_NATIVE + "\n",
            "$root/roles/db/files/ssh/users/alice.password" to TestHashes.SHA512_CRYPT + "\n",
            "$root/roles/web/files/htpasswd/web.password" to "alice:${TestHashes.BCRYPT}\n",
            "$root/roles/web/files/app/secret.txt" to TestHashes.SHA512_CRYPT + "\n",
        )
        for ((relative, text) in hashes) {
            val name = relative.substringAfterLast('/')
            assertEquals(relative, listOf(CommitSecretKind.DECRYPTED_VAULT to name), kinds(check(modified(relative, text, vault(text).toByteArray()))))
        }
        assertEquals(
            "a staged hash file whose working-tree file is gone",
            listOf(CommitSecretKind.DECRYPTED_VAULT to "gone.password"),
            kinds(check(staged("$root/roles/db/files/ssh/users/gone.password", TestHashes.SHA512_CRYPT.toByteArray(), before = vault("x").toByteArray()))),
        )

        assertNull("a hash file that was never a vault", check(added("$root/roles/db/files/mysql/users/bob.password", TestHashes.MYSQL_NATIVE + "\n")))
        val key = "$root/roles/db/files/ssh/users/deploy.password"
        assertEquals(
            "a private key after a hash is a plaintext key",
            listOf(CommitSecretKind.PLAINTEXT_KEY to "deploy.password"),
            kinds(check(added(key, TestHashes.SHA512_CRYPT + "\n" + TestKeys.plaintextKey()))),
        )
    }

    fun testOnlyContentReadCompletelyIsComparedWithItsPast() {
        val relative = "$root/group_vars/all/big.yml"
        val before = Staged(path(relative), vault("a: 1\n").toByteArray())
        write(relative, "# padding\n".repeat(120_000) + "a: 1\n")
        assertNull(check(Change(before, CurrentContentRevision(VcsUtil.getFilePath(vf(relative))))))
        assertEquals("a file larger than the window is no decrypted vault of its size: no read of a large blob", 0, before.reads)
    }

    fun testBeforeRevisionsAreReadSeveralAtATime() {
        val gate = CountDownLatch(3)
        val active = AtomicInteger()
        val most = AtomicInteger()
        val changes = (1..3).map { n ->
            val relative = "$root/group_vars/all/vars$n.yml"
            write(relative, "n: $n\n")
            Change(Concurrent(path(relative), "n: 0\n".toByteArray(), gate, active, most), CurrentContentRevision(VcsUtil.getFilePath(vf(relative))))
        }
        val started = System.nanoTime()
        assertNull(check(*changes.toTypedArray()))
        assertEquals("the three reads ran at the same time", 3, most.get())
        assertTrue("no read waited for another", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(9))
    }

    fun testAStagedFindingSaysToStageTheFixedFilesAgain() {
        val relative = "$root/roles/web/files/ssl/web.key"
        write(relative, TestKeys.plaintextKey())
        val problem = check(staged(relative, TestKeys.plaintextKey().toByteArray()))
        assertTrue(problem.toString(), problem is FixableSecretCommitProblem)
        assertEquals(
            "Ansibility Vault: 1 file would be committed with a plaintext private key: web.key. " +
                "The fix changes the working tree: stage the fixed files again, then commit.",
            problem!!.text,
        )
        assertFalse(
            "a working-tree commit needs no staging",
            check(added(relative))!!.text.contains("stage"),
        )
    }

    fun testEncryptFilesEncryptsTheKeyFilesInTheWorkingTree() {
        val relative = "$root/roles/web/files/ssl/web.key"
        val key = TestKeys.plaintextKey()
        val problem = check(added(relative, key), added("$root/.vault-pass")) as FixableSecretCommitProblem
        assertEquals(
            "the password file is named but never encrypted",
            listOf(CommitSecretKind.PASSWORD_FILE to ".vault-pass", CommitSecretKind.PLAINTEXT_KEY to "web.key").sortedBy { it.first },
            kinds(problem).sortedBy { it.first },
        )
        assertEquals(listOf("web.key"), problem.fix.files.map { it.name })
        problem.showDetails(project)
        awaitOperation()
        val written = disk(relative)
        assertTrue(written, VaultEnvelope.isEncrypted(written))
        assertEquals("the key, encrypted byte for byte", key, decrypt(written))
        assertEquals("${VaultVectors.PW1}\n", disk("$root/.vault-pass"))
    }

    fun testPasswordScriptsAndVaultedPasswordFilesPass() {
        val tern = projectRoot("tern", "[defaults]\nvault_password_file = scripts/vault-client.py\n")
        val script = write("$tern/scripts/vault-client.py", "#!/usr/bin/env python3\nprint(keyring.get_password('falcon', 'vault'))\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        root(tern)
        assertNull("a password client script holds no password", check(added("$tern/scripts/vault-client.py")))
        write("$root/.vault-pass", vault("${VaultVectors.DEV}\n"))
        assertNull("a vaulted password file is no leak (Ansible decrypts it)", check(added("$root/.vault-pass")))
    }

    fun testAddingTheVaultPasswordFileStopsTheCommitWithoutReadingIt() {
        val password = staged("$root/.vault-pass", "${VaultVectors.PW1}\n".toByteArray())
        val problem = check(password)
        assertTrue("no fix for a password file: Commit Anyway only ($problem)", problem is SecretCommitProblem)
        assertEquals("Ansibility Vault: 1 vault password file would be committed: .vault-pass", problem!!.text)
        assertEquals("its content is never read", 0, (password.afterRevision as Staged).reads)
        assertFalse(VaultVectors.PW1 in problem.text)
    }

    // ------------------------------------------------------------------------------------------------ switches

    fun testNothingIsCheckedWhenSwitchedOffOrForOtherCommits() {
        val change = added("$root/roles/web/files/ssl/web.key", TestKeys.plaintextKey())
        val handler = SecretCommitCheck(project)
        assertTrue(handler.isEnabled())
        assertEquals(CommitCheck.ExecutionOrder.EARLY, handler.getExecutionOrder())
        assertTrue("reads no index", handler.isDumbAware())
        assertNull("a patch is no commit", check(change, vcsCommit = false))

        VaultUserState.getInstance().checkCommits = false
        assertFalse(handler.isEnabled())
        assertNull(check(change))
        VaultUserState.getInstance().checkCommits = true
        assertEquals(listOf(CommitSecretKind.PLAINTEXT_KEY to "web.key"), kinds(check(change)))
    }

    fun testNothingIsCheckedInAProjectWithoutAnAnsibleRoot() {
        val change = added("$root/roles/web/files/ssl/web.key", TestKeys.plaintextKey())
        assertNull("no root registered yet", await { SecretCommitCheck(project).runCheck(Info(listOf(change))) })
        assertEquals("the same change once the root is known", 1, kinds(check(change)).size)
    }

    fun testTheSwitchIsPerUserOnTheVaultPageAndTheFactoryIsRegistered() {
        assertTrue(
            "the optional VCS fragment registers the factory",
            CheckinHandlerFactory.EP_NAME.extensionList.any { it is SecretCheckinHandlerFactory },
        )
        val state = VaultUserState.getInstance()
        assertNull("on by default: nothing is written", serialize(state.state)?.getAttribute("checkCommits"))
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()
            val box = UIUtil.findComponentsOfType(component, JBCheckBox::class.java).single { it.name == VaultConfigurable.COMMIT_CHECK_COMPONENT }
            page.reset()
            assertTrue(box.isSelected)
            box.isSelected = false
            assertTrue(page.isModified)
            page.apply()
            assertFalse(state.checkCommits)
            val saved = XmlSerializer.deserialize(serialize(state.state)!!, VaultUserState.StateBean::class.java)
            assertFalse("kept per user", saved.checkCommits)
            state.checkCommits = true
            page.reset()
            assertTrue(box.isSelected)
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
