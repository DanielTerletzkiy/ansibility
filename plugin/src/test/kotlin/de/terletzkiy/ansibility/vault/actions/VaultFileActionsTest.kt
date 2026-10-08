package de.terletzkiy.ansibility.vault.actions

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.envelope.VaultEnvelopes
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.tab.DecryptedTabPrompts
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultFile
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultTabs
import de.terletzkiy.ansibility.vault.tab.UnsavedTabsChoice
import de.terletzkiy.ansibility.vault.tab.UnsavedTabsRequest
import de.terletzkiy.ansibility.vault.tab.WholeFileVaultBanner
import de.terletzkiy.ansibility.vault.ui.AnsibilityVaultUiBundle
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * V6b: the file actions (F7.5, F7.6, F7.8) over a root with the ids dev, prod (`vault_identity_list`) and default
 * (`.vault-pass`): encrypt and decrypt in place, rekey and change id of folders with whole-file vaults and `!vault`
 * values, New › Ansibility Vault File, their registration, their "Ansibility Vault" texts by place (R19, D141/D142)
 * and the whole-file banner's links.
 */
class VaultFileActionsTest : VaultUiTestCase() {
    private lateinit var root: String
    private val operations: VaultFileOperations get() = VaultFileOperations.getInstance(project)
    private val tabs: DecryptedVaultTabs get() = DecryptedVaultTabs.getInstance(project)

    /** Unsaved decrypted edits are discarded when a test leaves them behind. */
    private object DiscardTabPrompts : DecryptedTabPrompts {
        override fun askUnsaved(project: Project, request: UnsavedTabsRequest): UnsavedTabsChoice = UnsavedTabsChoice.DISCARD
    }

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().replaceService(DecryptedTabPrompts::class.java, DiscardTabPrompts, testRootDisposable)
        root = idRoot("falcon")
    }

    override fun tearDown() {
        try {
            val editors = FileEditorManager.getInstance(project)
            editors.openFiles.filterIsInstance<DecryptedVaultFile>().forEach {
                it.session?.closingQuietly = true
                editors.closeFile(it)
            }
            tabs.openSessions.forEach { Disposer.dispose(it) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A root with `.vault-pass` (default), `dev.pw` and `prod.pw` listed in `vault_identity_list`. */
    private fun idRoot(name: String): String {
        val relative = defaultRoot(name, cfg = "[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\n")
        write("$relative/dev.pw", "${VaultVectors.DEV}\n")
        write("$relative/prod.pw", "${VaultVectors.PROD}\n")
        return relative
    }

    /** Waits until the last file operation finished, and the decrypted tab it opened. */
    private fun awaitOperation() {
        val job = operations.lastOperation ?: error("no operation was started")
        waitFor("file operation timed out") { job.isCompleted }
        tabs.lastAction?.let { tab -> waitFor("tab action timed out") { tab.isCompleted } }
    }

    /** The bytes of [relative] on disk, after the writes the IDE still has pending (262 writes saved documents asynchronously). */
    private fun bytes(relative: String): ByteArray {
        (LocalFileSystem.getInstance() as? AsyncableFileSystem)?.fsync()
        return Files.readAllBytes(base.resolve(relative))
    }

    private fun text(relative: String): String = String(bytes(relative), Charsets.UTF_8)

    /** The plaintext of [envelopeText] under [password], or null when it does not decrypt. */
    private fun decryptBytes(envelopeText: String, password: String): ByteArray? {
        val envelope = (VaultEnvelope.parse(envelopeText) as? EnvelopeParse.Ok)?.envelope ?: return null
        return SecretBytes.of(password.toByteArray()).use { VaultAes256.decrypt(envelope, it) }
    }

    private fun assertPrivate(relative: String) {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        assertEquals(relative, "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(base.resolve(relative))))
    }

    private fun assertBytes(expected: String, actual: ByteArray?) {
        assertNotNull(actual)
        assertEquals(expected, String(actual!!, Charsets.UTF_8))
        assertTrue("byte for byte", expected.toByteArray(Charsets.UTF_8).contentEquals(actual))
    }

    /** The `!vault` values of the YAML file open in the fixture, as YAML loads them. */
    private fun vaultValues(): List<String> = runReadActionBlocking {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        VaultValuePsi.vaultScalars(myFixture.file).map { it.textValue }
    }

    private fun textEditor(): TextEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)

    /** The presentation [id] gets for a Project-view selection of [files] (a test event: no place, `ActionUiKind.NONE`). */
    private fun presentation(id: String, vararg files: VirtualFile): Presentation {
        val action = ActionManager.getInstance().getAction(id) ?: error("$id is not registered")
        val event = TestActionEvent.createTestEvent(action, selection(*files))
        runReadActionBlocking { action.update(event) }
        return event.presentation
    }

    /** The text [id] shows for a selection of [files] at [place], shown as [uiKind] (a menu, Find Action, a toolbar). */
    private fun textAt(place: String, uiKind: ActionUiKind, id: String, vararg files: VirtualFile): String? {
        val action = ActionManager.getInstance().getAction(id) ?: error("$id is not registered")
        val event = AnActionEvent.createEvent(action, selection(*files), action.templatePresentation.clone(), place, uiKind, null)
        runReadActionBlocking { action.update(event) }
        assertTrue("$id at $place", event.presentation.isEnabledAndVisible)
        return event.presentation.text
    }

    private fun selection(vararg files: VirtualFile): DataContext =
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
            .add(CommonDataKeys.VIRTUAL_FILE, files.first()).build()

    // ------------------------------------------------------------------------------------------------ encrypt

    fun testEncryptRoundTripWritesTheChosenIdsHeaderMode0600AndIsOneUndoableStep() {
        val relative = "$root/environments/prod/group_vars/all/secrets.yml"
        val original = "db_password: s3cret\nusers:\n  - ümläut\n"
        write(relative, original)
        root(root)
        val file = vf(relative)
        myFixture.configureFromExistingVirtualFile(file)
        prompts.identity = { "prod" }

        operations.encrypt(listOf(file))
        awaitOperation()

        val written = text(relative)
        assertTrue(written, written.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertBytes(original, decryptBytes(written, VaultVectors.PROD))
        assertPrivate(relative)
        assertEmpty("one file: no confirmation", prompts.fileRequests)
        val request = prompts.identityRequests.single()
        assertEquals("ambiguous: every id is offered", listOf("dev", "prod", "default"), request.choices.map { it.label })
        val report = feedback().single()
        assertTrue(report, report.contains("secrets.yml (id prod)") && report.contains("git history"))
        assertFalse(report, report.contains("s3cret"))

        val undo = UndoManager.getInstance(project)
        val names = undo.getUndoActionNameAndDescription(textEditor())
        assertTrue(names.first, names.first.contains("Encrypt File with Ansible"))
        undo.undo(textEditor())
        assertEquals("one undo restores the plaintext", original, myFixture.editor.document.text)
    }

    fun testPasswordSourcesAreNeverEncrypted() {
        root(root)
        operations.encrypt(listOf(vf("$root/.vault-pass"), vf("$root/dev.pw")))
        awaitOperation()
        assertEquals("${VaultVectors.PW1}\n", text("$root/.vault-pass"))
        assertEquals("${VaultVectors.DEV}\n", text("$root/dev.pw"))
        val report = feedback().single()
        assertTrue(report, report.contains(".vault-pass (a vault password source of its root)"))
        assertTrue(report, report.contains("dev.pw (a vault password source of its root)"))
        assertFalse(report, report.contains(VaultVectors.PW1) || report.contains(VaultVectors.DEV))
        assertEmpty(prompts.identityRequests)
        assertEmpty(prompts.fileRequests)
    }

    fun testAlreadyEncryptedFilesAreSkippedAndListed() {
        val vault = "$root/files/ssl/demo.key"
        write(vault, VaultVectors.encrypt("-----KEY-----\n", VaultVectors.PW1).formatBytes())
        write("$root/files/ssl/plain.txt", "plain\n")
        root(root)
        val before = bytes(vault)
        prompts.identity = { "default" }

        operations.encrypt(listOf(vf(vault), vf("$root/files/ssl/plain.txt")))
        awaitOperation()

        assertTrue("left alone", before.contentEquals(bytes(vault)))
        val plain = text("$root/files/ssl/plain.txt")
        assertTrue(plain, plain.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertBytes("plain\n", decryptBytes(plain, VaultVectors.PW1))
        assertEmpty("only one file is encrypted: no confirmation", prompts.fileRequests)
        val report = feedback().single()
        assertTrue(report, report.contains("Skipped: demo.key (already encrypted)"))
    }

    /**
     * R21 (D159): a file that already holds an envelope Ansible does not see (a pasted `!vault |` block, an indented
     * envelope, a byte order mark first) is skipped and named with the fix, never encrypted a second time.
     */
    fun testFilesThatAreNotWholeFileVaultsAreSkippedInsteadOfEncryptedAgain() {
        val envelope = VaultVectors.encrypt("synthetic key material\n", VaultVectors.PW1)
        val pasted = "$root/files/ssl/pasted.key"
        write(pasted, "!vault |\n" + envelope.format())
        val indented = "$root/files/ssl/indented.key"
        write(indented, envelope.formatLines().joinToString("") { "          $it\n" })
        val bom = "$root/files/ssl/bom.key"
        write(bom, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + envelope.formatBytes())
        val plain = "$root/files/ssl/plain.txt"
        write(plain, "plain\n")
        root(root)
        val before = listOf(pasted, indented, bom).associateWith { bytes(it) }
        prompts.identity = { "default" }

        operations.encrypt(listOf(vf(pasted), vf(indented), vf(bom), vf(plain)))
        awaitOperation()

        for ((relative, original) in before) assertTrue("left alone: $relative", original.contentEquals(bytes(relative)))
        assertTrue(text(plain).startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEmpty("only one file is encrypted: no confirmation", prompts.fileRequests)
        val report = feedback().single()
        assertTrue(report, report.contains("pasted.key (it already holds a vault envelope that Ansible does not see; use Convert to whole-file vault, ANS-V107"))
        assertTrue(report, report.contains("indented.key (it already holds a vault envelope"))
        assertTrue(report, report.contains("bom.key (a vault file behind a byte order mark; remove the mark with File | File Properties | Remove BOM"))
        assertFalse(report, envelope.formatLines()[1].take(16) in report)
    }

    /** D168: the decrypt confirmations promise no check that is not built (ANS-X001 was never built). */
    fun testTheDecryptConfirmationsPromiseNoUnbuiltCheck() {
        val texts = listOf(
            AnsibilityVaultUiBundle.message("decrypt.message", "db_password", "vault.yml"),
            AnsibilityVaultUiBundle.message("file.confirm.decrypt.message", 1, "vault.yml"),
            AnsibilityVaultUiBundle.message("file.confirm.decrypt.message", 2, "a.yml, b.yml"),
        )
        for (text in texts) {
            assertFalse(text, "X001" in text || "flag" in text)
            assertTrue(text, "Local History" in text && "git history" in text)
        }
    }

    fun testAFolderIsEncryptedRecursivelyWithoutHiddenFilesAndTheIdIsAskedOncePerRoot() {
        write("$root/files/app/a.txt", "a\n")
        write("$root/files/app/sub/b.txt", "b\n")
        write("$root/files/app/.hidden", "h\n")
        write("$root/files/app/.cache/c.txt", "c\n")
        val other = idRoot("tern")
        write("$other/files/d.txt", "d\n")
        root(root)
        root(other)
        prompts.identity = { if (prompts.identityRequests.size == 1) "dev" else "prod" }

        operations.encrypt(listOf(vf("$root/files/app"), vf("$other/files/d.txt")))
        awaitOperation()

        val (operation, names) = prompts.fileRequests.single()
        assertEquals(FileOperation.ENCRYPT, operation)
        assertEquals(setOf("a.txt", "b.txt", "d.txt"), names.toSet())
        assertEquals("once per root", 2, prompts.identityRequests.size)
        for ((relative, plaintext) in listOf("$root/files/app/a.txt" to "a\n", "$root/files/app/sub/b.txt" to "b\n")) {
            val written = text(relative)
            assertTrue(written, written.startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
            assertBytes(plaintext, decryptBytes(written, VaultVectors.DEV))
        }
        assertTrue(text("$other/files/d.txt").startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertEquals("h\n", text("$root/files/app/.hidden"))
        assertEquals("c\n", text("$root/files/app/.cache/c.txt"))
    }

    // ------------------------------------------------------------------------------------------------ decrypt

    fun testDecryptInPlaceAsksFirstThenWritesTheExactPlaintextWithMode0600() {
        val relative = "$root/group_vars/all/vault.yml"
        val plaintext = "token: abc  \r\nname: été\r\n"
        write(relative, VaultVectors.encrypt(plaintext, VaultVectors.PW1).formatBytes())
        root(root)
        val before = bytes(relative)
        val attempts = crypto.decryptAttempts
        prompts.filesAnswer = false

        operations.decrypt(listOf(vf(relative)))
        awaitOperation()

        assertTrue("cancel leaves the file", before.contentEquals(bytes(relative)))
        assertEquals(FileOperation.DECRYPT to listOf("vault.yml"), prompts.fileRequests.single())
        assertEquals("nothing was decrypted before you agreed", attempts, crypto.decryptAttempts)

        prompts.filesAnswer = true
        operations.decrypt(listOf(vf(relative)))
        awaitOperation()

        assertBytes(plaintext, bytes(relative))
        assertPrivate(relative)
        assertTrue(feedback().last().contains("Decrypted 1 file in place: vault.yml"))
    }

    fun testDecryptInPlaceWritesTheByteOrderMarkOnceWhenTheExactBytesFollow() {
        val relative = "$root/group_vars/all/vault.yml"
        // A byte order mark and mixed line separators: the document cannot save these bytes, so the exact ones follow.
        val plaintext = "﻿a: 1\r\nb: 2\n"
        write(relative, VaultVectors.encrypt(plaintext, VaultVectors.PW1).formatBytes())
        root(root)
        myFixture.configureFromExistingVirtualFile(vf(relative))

        operations.decrypt(listOf(vf(relative)))
        awaitOperation()

        assertBytes(plaintext, bytes(relative))
        assertPrivate(relative)
    }

    fun testUndoRestoresTheByteOrderMarkAndLineSeparatorOfTheFile() {
        val vault = "$root/group_vars/all/vault.yml"
        val envelope = VaultVectors.encrypt("﻿a: 1\nb: 2\n", VaultVectors.PW1).formatBytes()
        write(vault, envelope)
        val plain = "$root/group_vars/all/vars.yml"
        val original = "﻿c: 3\r\nd: 4\r\n"
        write(plain, original)
        root(root)
        val undo = UndoManager.getInstance(project)
        // Opened as they are (the fixture's configure would save them with LF first).
        fun open(relative: String): TextEditor = FileEditorManager.getInstance(project).openFile(vf(relative), true).filterIsInstance<TextEditor>().single()

        val vaultEditor = open(vault)
        operations.decrypt(listOf(vf(vault)))
        awaitOperation()
        assertBytes("﻿a: 1\nb: 2\n", bytes(vault))
        assertTrue("the decrypt can be undone in its editor", undo.isUndoAvailable(vaultEditor))
        undo.undo(vaultEditor)
        FileDocumentManager.getInstance().saveAllDocuments()
        val after = bytes(vault)
        assertTrue(
            "no byte order mark: the vault as it was, got ${after.take(16).joinToString(" ") { "%02x".format(it) }}",
            envelope.contentEquals(after),
        )
        assertTrue(runReadActionBlocking { VaultEnvelopes.isWholeFileVault(vf(vault)) })

        val plainEditor = open(plain)
        prompts.identity = { "default" }
        operations.encrypt(listOf(vf(plain)))
        awaitOperation()
        assertTrue(text(plain).startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        undo.undo(plainEditor)
        FileDocumentManager.getInstance().saveAllDocuments()
        assertBytes(original, bytes(plain))
    }

    fun testDecryptInPlaceSetsTheModeBeforeWritingAndWritesNothingWhenItCannot() {
        val relative = "$root/group_vars/all/vault.yml"
        val envelope = VaultVectors.encrypt("token: s3cret\n", VaultVectors.PW1).formatBytes()
        write(relative, envelope)
        root(root)
        val seen = CopyOnWriteArrayList<ByteArray>()
        operations.setModesForTests({ path ->
            seen += Files.readAllBytes(path)
            throw IOException("refused")
        }, testRootDisposable)

        operations.decrypt(listOf(vf(relative)))
        awaitOperation()

        assertTrue("the mode is set while the file still holds the envelope", envelope.contentEquals(seen.single()))
        assertTrue("left alone", envelope.contentEquals(bytes(relative)))
        val report = feedback().last()
        assertTrue(report, report.contains("vault.yml: its mode could not be set to 0600, so nothing was written"))
        assertFalse(report, report.contains("Decrypted") || report.contains("s3cret"))
    }

    fun testAFailedSaveOfDecryptInPlaceLeavesNoPlaintextInTheDocument() {
        val relative = "$root/group_vars/all/vault.yml"
        val envelope = VaultVectors.encrypt("token: s3cret\n", VaultVectors.PW1).formatBytes()
        write(relative, envelope)
        root(root)
        val file = vf(relative)
        val documents = FileDocumentManager.getInstance()
        val vetoer = object : FileDocumentSynchronizationVetoer() {
            override fun maySaveDocument(document: Document, isSaveExplicit: Boolean): Boolean = documents.getFile(document) != file
        }
        FileDocumentSynchronizationVetoer.EP_NAME.point.registerExtension(vetoer, testRootDisposable)

        operations.decrypt(listOf(file))
        awaitOperation()

        assertTrue("left alone", envelope.contentEquals(bytes(relative)))
        val document = documents.getDocument(file)!!
        assertEquals("the old text is back, for autosave too", String(envelope, Charsets.US_ASCII), document.text)
        val report = feedback().last()
        assertTrue(report, report.contains("vault.yml: it could not be written"))
        assertFalse(report, report.contains("Decrypted") || report.contains("s3cret"))
        documents.reloadFromDisk(document)
    }

    fun testACancelledUnlockIsAskedOncePerRunAndItsFilesAreNamed() {
        for (name in listOf("a", "b", "c")) write("$root/files/$name.key", VaultVectors.encrypt("$name\n", VaultVectors.PW1).formatBytes())
        write("$root/files/plain/p.txt", "p\n")
        write("$root/files/plain/q.txt", "q\n")
        root(root)
        val before = bytes("$root/files/a.key")
        // No consent for the password file, then Cancel in the password prompt.
        prompter.onConsent = { VaultConsentDecision.NotNow }

        operations.decrypt(listOf(vf("$root/files")))
        awaitOperation()

        assertEquals("one password prompt for the run", 1, prompter.passwordRequests.size)
        assertTrue("left alone", before.contentEquals(bytes("$root/files/a.key")))
        val report = feedback().last()
        assertTrue(report, report.contains("Left alone because you cancelled or declined:"))
        for (name in listOf("a.key", "b.key", "c.key")) assertTrue(report, report.contains(name))
        assertFalse(report, report.contains("Nothing needed to change"))

        prompter.passwordRequests.clear()
        prompts.identity = { "default" }
        operations.encrypt(listOf(vf("$root/files/plain/p.txt"), vf("$root/files/plain/q.txt")))
        awaitOperation()

        assertEquals("one password prompt for the run", 1, prompter.passwordRequests.size)
        assertEquals("p\n", text("$root/files/plain/p.txt"))
        val encryptReport = feedback().last()
        assertTrue(encryptReport, encryptReport.contains("Left alone because you cancelled or declined: p.txt, q.txt"))
    }

    // ------------------------------------------------------------------------------------------------ rekey, change id

    fun testRekeyOfAFolderReencryptsWholeFilesAndValuesInOneCommandPerFile() {
        val dir = "$root/environments/prod/group_vars/all"
        write("$dir/vault.yml", VaultVectors.encrypt("whole: file\n", VaultVectors.PW1).formatBytes())
        write(
            "$dir/vars.yml",
            "plain: 1\n" + VaultVectors.inline("vault_a", VaultVectors.encrypt("alpha", VaultVectors.PW1)) +
                VaultVectors.inline("vault_b", VaultVectors.encrypt("beta", VaultVectors.DEV, "dev")),
        )
        root(root)
        myFixture.configureFromExistingVirtualFile(vf("$dir/vars.yml"))
        val before = myFixture.editor.document.text
        prompts.identity = { "prod" }

        operations.rekey(listOf(vf(dir)))
        awaitOperation()

        val request = prompts.identityRequests.single()
        assertTrue(request.message, request.message.contains("1 vault file, 2 !vault values in 1 YAML file"))
        val whole = text("$dir/vault.yml")
        assertTrue(whole, whole.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertBytes("whole: file\n", decryptBytes(whole, VaultVectors.PROD))
        val values = vaultValues()
        assertEquals(2, values.size)
        for (value in values) assertTrue(value, value.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertEquals(listOf("alpha", "beta"), values.map { String(decryptBytes(it, VaultVectors.PROD)!!, Charsets.UTF_8) })
        assertTrue("saved", text("$dir/vars.yml").startsWith("plain: 1\nvault_a: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        val report = feedback().single()
        assertTrue(report, report.contains("Rekeyed 2 files to vault id prod"))

        UndoManager.getInstance(project).undo(textEditor())
        assertEquals("both values of the file are one command", before, myFixture.editor.document.text)
    }

    fun testChangeIdRelabelsOnlyWhereTheTargetsSecretDecrypts() {
        val key = "$root/files/ssl/demo.key"
        write(key, VaultVectors.encrypt("-----KEY-----\n", VaultVectors.PW1, "new").formatBytes())
        write("$root/group_vars/all/vars.yml", VaultVectors.inline("vault_x", VaultVectors.encrypt("x", VaultVectors.PW1, "dev")))
        root(root)
        val before = text(key)
        prompts.identity = { "default" }

        operations.changeId(listOf(vf("$root/files"), vf("$root/group_vars/all/vars.yml")))
        awaitOperation()

        val after = text(key)
        assertTrue(after, after.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEquals("header only: the payload is unchanged", before.substringAfter('\n'), after.substringAfter('\n'))
        val vars = text("$root/group_vars/all/vars.yml")
        assertTrue(vars, vars.startsWith("vault_x: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEmpty("no re-encrypt question", prompts.rekeyQuestions)
        assertEquals("Change Vault Id", prompts.identityRequests.single().title)
    }

    fun testChangeIdAsksOnceBeforeReencryptingWhatTheTargetDoesNotDecrypt() {
        write("$root/files/a.key", VaultVectors.encrypt("a\n", VaultVectors.PW1).formatBytes())
        write("$root/files/b.key", VaultVectors.encrypt("b\n", VaultVectors.PW1).formatBytes())
        root(root)
        val before = bytes("$root/files/a.key")
        prompts.identity = { "prod" }
        prompts.rekeyForChangeId = false

        operations.changeId(listOf(vf("$root/files")))
        awaitOperation()

        assertEquals("one question for both files", listOf("prod" to "default"), prompts.rekeyQuestions.toList())
        assertTrue("declined: nothing written", before.contentEquals(bytes("$root/files/a.key")))

        prompts.rekeyQuestions.clear()
        prompts.rekeyForChangeId = true
        operations.changeId(listOf(vf("$root/files")))
        awaitOperation()

        assertEquals(1, prompts.rekeyQuestions.size)
        for ((name, plaintext) in listOf("a.key" to "a\n", "b.key" to "b\n")) {
            val written = text("$root/files/$name")
            assertTrue(written, written.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
            assertBytes(plaintext, decryptBytes(written, VaultVectors.PROD))
        }
    }

    fun testChangeIdAsksOncePerRootAndEachAnswerStaysWithItsRoot() {
        write("$root/files/a.key", VaultVectors.encrypt("a\n", VaultVectors.PW1).formatBytes())
        val other = idRoot("tern")
        write("$other/files/b.key", VaultVectors.encrypt("b\n", VaultVectors.PW1).formatBytes())
        root(root)
        root(other)
        val before = bytes("$other/files/b.key")
        prompts.identity = { if (prompts.identityRequests.size == 1) "prod" else "dev" }
        prompts.rekeyFilesForChangeId = { "falcon" in it }

        operations.changeId(listOf(vf("$root/files"), vf("$other/files")))
        awaitOperation()

        assertEquals("one question per root", listOf("prod" to "default", "dev" to "default"), prompts.rekeyQuestions.toList())
        assertEquals(listOf(true, false), prompts.rekeyRoots.map { "falcon" in it })
        assertTrue(prompts.rekeyRoots[1], "tern" in prompts.rekeyRoots[1])
        val a = text("$root/files/a.key")
        assertTrue(a, a.startsWith("\$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        assertBytes("a\n", decryptBytes(a, VaultVectors.PROD))
        assertTrue("declined in its own root", before.contentEquals(bytes("$other/files/b.key")))
        val report = feedback().last()
        assertTrue(report, report.contains("Left alone because you cancelled or declined: b.key"))
    }

    fun testChangeIdDecryptsEachValueWhereItIsNowWhenTheFileIsEditedMeanwhile() {
        val relative = "$root/group_vars/all/vars.yml"
        write(
            relative,
            VaultVectors.inline("vault_a", VaultVectors.encrypt("alpha", VaultVectors.PW1)) + VaultVectors.inline("vault_b", VaultVectors.encrypt("beta", VaultVectors.PW1)),
        )
        root(root)
        val file = vf(relative)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        prompts.identity = { "prod" }
        // While vault_a's question is open, a long comment goes in above the values: the offsets the scan captured now
        // point 200 characters too far, and vault_b's falls inside vault_a.
        val comment = "# " + "x".repeat(197) + "\n"
        prompts.onRekeyFilesQuestion = {
            prompts.onRekeyFilesQuestion = {}
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, comment) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        operations.changeId(listOf(file))
        awaitOperation()

        assertEquals("one question for both values", 1, prompts.rekeyQuestions.size)
        val written = text(relative)
        assertTrue(written, written.startsWith(comment + "vault_a: !vault |\n  \$ANSIBLE_VAULT;1.2;AES256;prod\n"))
        val values = runReadActionBlocking { VaultValuePsi.vaultScalars(PsiManager.getInstance(project).findFile(file)!!).map { it.textValue } }
        assertEquals("each value keeps its own plaintext", listOf("alpha", "beta"), values.map { String(decryptBytes(it, VaultVectors.PROD)!!, Charsets.UTF_8) })
    }

    fun testAFileWhoseDecryptedTabHasUnsavedEditsIsLeftAloneAndNamed() {
        val relative = "$root/group_vars/all/vault.yml"
        write(relative, VaultVectors.encrypt("a: 1\n", VaultVectors.PW1).formatBytes())
        root(root)
        val file = vf(relative)
        tabs.open(file)
        waitFor("the tab did not open") { tabs.lastAction?.isCompleted == true && tabs.openSessions.isNotEmpty() }
        val tab = FileEditorManager.getInstance(project).openFiles.filterIsInstance<DecryptedVaultFile>().single()
        val document = FileDocumentManager.getInstance().getDocument(tab)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("a: 2\n") }
        val before = bytes(relative)
        prompts.identity = { "prod" }

        operations.rekey(listOf(file))
        awaitOperation()

        assertTrue("left alone", before.contentEquals(bytes(relative)))
        val report = feedback().last()
        assertTrue(report, report.contains("vault.yml: its decrypted tab has unsaved edits"))
        assertFalse(report, report.contains("a: 2"))
        assertEquals("the edits stay", "a: 2\n", document.text)
    }

    // ------------------------------------------------------------------------------------------------ new file

    fun testNewVaultFileIsCreatedEncryptedAndOpensDecrypted() {
        root(root)
        prompts.identity = { "dev" }

        operations.newVaultFile(vf(root))
        awaitOperation()

        val relative = "$root/secrets.yml"
        val written = text(relative)
        assertTrue(written, written.startsWith("\$ANSIBLE_VAULT;1.2;AES256;dev\n"))
        assertBytes("---\n", decryptBytes(written, VaultVectors.DEV))
        assertPrivate(relative)
        val tab = FileEditorManager.getInstance(project).openFiles.filterIsInstance<DecryptedVaultFile>().single()
        assertEquals(vf(relative), tab.original)
        assertEquals("---\n", FileDocumentManager.getInstance().getDocument(tab)!!.text)

        operations.newVaultFile(vf(root))
        assertEquals(listOf("secrets.yml already exists."), prompts.nameErrors.toList())
    }

    // ------------------------------------------------------------------------------------------------ registration

    fun testTheActionsAreRegisteredInTheirMenusAndTheBannerLinksThem() {
        val actions = ActionManager.getInstance()
        fun group(id: String) = actions.getAction(id) as DefaultActionGroup
        val fileMenu = actions.getAction("Ansibility.Vault.FileMenu")
        for (menu in listOf("ProjectViewPopupMenu", "EditorPopupMenu", "EditorTabPopupMenu")) assertTrue(menu, group(menu).getChildren(null).contains(fileMenu))
        assertTrue(group("ToolsMenu").getChildren(null).contains(actions.getAction("Ansibility.Vault.ToolsMenu")))
        // R19 (D141): both submenus are "Ansibility Vault" (R18 had "Ansible Vault" and "Ansible Vault (Ansibility)").
        assertEquals("Ansibility Vault", actions.getAction("Ansibility.Vault.ToolsMenu").templateText)
        assertEquals("Ansibility Vault", fileMenu.templateText)
        assertTrue("Find Action lists the Tools submenu", (actions.getAction("Ansibility.Vault.ToolsMenu") as ActionGroup).isSearchable)
        assertFalse("and not the same submenu a second time", (fileMenu as ActionGroup).isSearchable)
        assertTrue(group("NewGroup").getChildren(null).contains(actions.getAction("Ansibility.Vault.NewFile")))
        for (id in listOf("Ansibility.Vault.EncryptFile", "Ansibility.Vault.DecryptFileInPlace", "Ansibility.Vault.RekeyFile", "Ansibility.Vault.ChangeFileId")) {
            assertTrue(id, group("Ansibility.Vault.FileMenu").getChildren(null).contains(actions.getAction(id)))
            assertFalse("$id has a description", actions.getAction(id).templatePresentation.description.isNullOrBlank())
        }

        val relative = "$root/files/ssl/demo.key"
        write(relative, VaultVectors.encrypt("-----KEY-----\n", VaultVectors.PW1, "new").formatBytes())
        root(root)
        val file = vf(relative)
        val editor = TextEditorProvider.getInstance().createEditor(project, file)
        try {
            val panel = WholeFileVaultBanner().collectNotificationData(project, file)!!.apply(editor)!!
            val links = UIUtil.findComponentsOfType(panel, HyperlinkLabel::class.java).map { it.text }
            assertTrue(links.toString(), links.containsAll(listOf("Rekey…", "Change id…", "Decrypt file…")))
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testTheActionsShowForTheFilesTheyApplyTo() {
        write("$root/files/plain.txt", "p\n")
        write("$root/files/other.txt", "o\n")
        write("$root/files/demo.key", VaultVectors.encrypt("k\n", VaultVectors.PW1).formatBytes())
        write("$root/group_vars/all/vars.yml", "a: 1\n")
        write("elsewhere/plain.txt", "p\n")
        root(root)
        val plain = vf("$root/files/plain.txt")
        val vault = vf("$root/files/demo.key")
        val yaml = vf("$root/group_vars/all/vars.yml")

        assertTrue(presentation("Ansibility.Vault.EncryptFile", plain).isEnabledAndVisible)
        // Outside a menu the texts carry the prefix (R19, D141; R18 said "… with Ansible Vault").
        assertEquals("Ansibility Vault: Encrypt File", presentation("Ansibility.Vault.EncryptFile", plain).text)
        assertEquals("Ansibility Vault: Encrypt 2 Files", presentation("Ansibility.Vault.EncryptFile", plain, vf("$root/files/other.txt")).text)
        assertEquals("Ansibility Vault: Encrypt Files", presentation("Ansibility.Vault.EncryptFile", vf("$root/files")).text)
        assertFalse("already encrypted", presentation("Ansibility.Vault.EncryptFile", vault).isEnabledAndVisible)
        assertFalse("outside every root", presentation("Ansibility.Vault.EncryptFile", vf("elsewhere/plain.txt")).isEnabledAndVisible)

        assertTrue(presentation("Ansibility.Vault.DecryptFileInPlace", vault).isEnabledAndVisible)
        assertFalse(presentation("Ansibility.Vault.DecryptFileInPlace", plain).isEnabledAndVisible)
        for (id in listOf("Ansibility.Vault.RekeyFile", "Ansibility.Vault.ChangeFileId")) {
            assertTrue(id, presentation(id, vault).isEnabledAndVisible)
            assertTrue(id, presentation(id, yaml).isEnabledAndVisible)
            assertTrue(id, presentation(id, vf("$root/files")).isEnabledAndVisible)
            assertFalse(id, presentation(id, plain).isEnabledAndVisible)
        }
        assertTrue(presentation("Ansibility.Vault.NewFile", vf("$root/files")).isEnabledAndVisible)
        assertFalse(presentation("Ansibility.Vault.NewFile", vf("elsewhere")).isEnabledAndVisible)
    }

    // ------------------------------------------------------------------------------------------------ R19 naming (D141, D142)

    fun testTheSubmenusShowShortTextsAndEverythingElseSaysAnsibilityVault() {
        write("$root/files/plain.txt", "p\n")
        write("$root/files/other.txt", "o\n")
        write("$root/files/demo.key", VaultVectors.encrypt("k\n", VaultVectors.PW1).formatBytes())
        write("$root/files/old.key", VaultVectors.encrypt("o\n", VaultVectors.PW1).formatBytes())
        root(root)
        val plain = vf("$root/files/plain.txt")
        val other = vf("$root/files/other.txt")
        val vault = vf("$root/files/demo.key")
        val old = vf("$root/files/old.key")
        val folder = vf("$root/files")

        val menus = listOf(
            ActionPlaces.PROJECT_VIEW_POPUP to ActionUiKind.POPUP,
            ActionPlaces.EDITOR_POPUP to ActionUiKind.POPUP,
            ActionPlaces.EDITOR_TAB_POPUP to ActionUiKind.POPUP,
            ActionPlaces.MAIN_MENU to ActionUiKind.MAIN_MENU,
        )
        for ((place, kind) in menus) {
            fun text(id: String, vararg files: VirtualFile) = textAt(place, kind, id, *files)
            assertEquals(place, "Encrypt File", text("Ansibility.Vault.EncryptFile", plain))
            assertEquals(place, "Encrypt 2 Files", text("Ansibility.Vault.EncryptFile", plain, other))
            assertEquals(place, "Encrypt Files", text("Ansibility.Vault.EncryptFile", folder))
            assertEquals(place, "Decrypt File in Place…", text("Ansibility.Vault.DecryptFileInPlace", vault))
            assertEquals(place, "Decrypt 2 Files in Place…", text("Ansibility.Vault.DecryptFileInPlace", vault, old))
            assertEquals(place, "Rekey…", text("Ansibility.Vault.RekeyFile", vault))
            assertEquals(place, "Rekey 2 Files…", text("Ansibility.Vault.RekeyFile", vault, old))
            assertEquals(place, "Change Vault Id…", text("Ansibility.Vault.ChangeFileId", vault))
            assertEquals(place, "Change Vault Id of 2 Files…", text("Ansibility.Vault.ChangeFileId", vault, old))
            assertEquals(place, "Ansibility Vault File", text("Ansibility.Vault.NewFile", folder))
        }

        val elsewhere = listOf(ActionPlaces.ACTION_SEARCH to ActionUiKind.SEARCH_POPUP, ActionPlaces.MAIN_TOOLBAR to ActionUiKind.TOOLBAR)
        for ((place, kind) in elsewhere) {
            fun text(id: String, vararg files: VirtualFile) = textAt(place, kind, id, *files)
            assertEquals(place, "Ansibility Vault: Encrypt File", text("Ansibility.Vault.EncryptFile", plain))
            assertEquals(place, "Ansibility Vault: Encrypt 2 Files", text("Ansibility.Vault.EncryptFile", plain, other))
            assertEquals(place, "Ansibility Vault: Decrypt File in Place…", text("Ansibility.Vault.DecryptFileInPlace", vault))
            assertEquals(place, "Ansibility Vault: Rekey…", text("Ansibility.Vault.RekeyFile", vault))
            assertEquals(place, "Ansibility Vault: Rekey 2 Files…", text("Ansibility.Vault.RekeyFile", vault, old))
            assertEquals(place, "Ansibility Vault: Change Vault Id…", text("Ansibility.Vault.ChangeFileId", vault))
            assertEquals(place, "Ansibility Vault: New Vault File", text("Ansibility.Vault.NewFile", folder))
        }
    }

    fun testFindActionAndTheKeymapNameTheFeatureAndKeepTheOldTextsAsSynonyms() {
        val actions = ActionManager.getInstance()
        val expected = mapOf(
            "Ansibility.Vault.EncryptFile" to ("Ansibility Vault: Encrypt File" to "Encrypt File with Ansible Vault"),
            "Ansibility.Vault.DecryptFileInPlace" to ("Ansibility Vault: Decrypt File in Place…" to "Decrypt Ansible Vault File in Place"),
            "Ansibility.Vault.RekeyFile" to ("Ansibility Vault: Rekey…" to "Rekey Ansible Vault"),
            "Ansibility.Vault.ChangeFileId" to ("Ansibility Vault: Change Vault Id…" to "Change Ansible Vault Id"),
            "Ansibility.Vault.NewFile" to ("Ansibility Vault: New Vault File" to "New Ansible Vault File"),
        )
        for ((id, texts) in expected) {
            val action = actions.getAction(id)
            assertEquals(id, texts.first, action.templateText)
            assertEquals(id, listOf(texts.second), action.synonyms.map { it.get() })
        }
    }

    fun testEveryAnsibilityVaultActionIsBrandedAnsibilityVault() {
        // DEV.md, Branding (R19, D141): the feature is "Ansibility Vault" wherever an action or a menu names it;
        // "Ansible Vault" names the file format only (banner, gutter, dialogs, summaries, undo names).
        val actions = ActionManager.getInstance()
        val ids = actions.getActionIdList("Ansibility.Vault.")
        assertTrue(
            ids.toString(),
            ids.containsAll(
                listOf(
                    "Ansibility.Vault.EncryptFile", "Ansibility.Vault.DecryptFileInPlace", "Ansibility.Vault.RekeyFile", "Ansibility.Vault.ChangeFileId",
                    "Ansibility.Vault.NewFile", "Ansibility.Vault.FileMenu", "Ansibility.Vault.ToolsMenu",
                ),
            ),
        )
        for (id in ids) {
            val text = actions.getAction(id).templateText.orEmpty()
            assertTrue("$id: '$text'", text.startsWith("Ansibility Vault"))
            assertFalse("$id: '$text'", text.startsWith("Ansible Vault"))
        }
    }

    fun testTheConfirmationsAreTitledLikeTheirMenuItems() {
        // D141: Decrypt File in Place and Change Vault Id are titled like their menu items; the others name the file
        // format, which stays "Ansible Vault" (D142).
        assertEquals("Decrypt File in Place", FileOperation.DECRYPT.confirmTitle)
        assertEquals("Change Vault Id", FileOperation.CHANGE_ID.confirmTitle)
        assertEquals("Rekey Ansible Vault", FileOperation.REKEY.confirmTitle)
        assertEquals("Encrypt with Ansible Vault", FileOperation.ENCRYPT.confirmTitle)
    }

    fun testVaultNotificationsAreTitledAnsibilityVault() {
        val shown = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification
                }
            },
        )
        root(root)

        operations.encrypt(listOf(vf("$root/.vault-pass")))
        awaitOperation()

        val report = feedback().single()
        val notification = shown.single { it.content == report }
        assertEquals("Ansibility Vault", notification.title)
        assertEquals("Ansibility", notification.groupId)
    }
}
