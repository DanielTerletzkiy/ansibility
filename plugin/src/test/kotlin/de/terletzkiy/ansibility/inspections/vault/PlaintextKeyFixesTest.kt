package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.preview.IntentionPreviewPopupUpdateProcessor
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.FileOperation
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.tab.DecryptedTabPrompts
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultFile
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultTabs
import de.terletzkiy.ansibility.vault.tab.UnsavedTabsChoice
import de.terletzkiy.ansibility.vault.tab.UnsavedTabsRequest
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * ANS-V108's fixes and the signals that need a real file system (plan amendment R21, D160–D162): "Encrypt file" and
 * "Encrypt value" on a synthetic root `repos/falcon/ansible` with `.vault-pass` (id default, password pw1 of
 * `tools/vault/SYNTHETIC.md`), their previews, "Fix all", where no fix is offered, the committed vault password file,
 * and decrypted tabs. Keys are made at test time ([TestKeys]); the VCS status is scripted ([FakeStatusLookup]).
 */
class PlaintextKeyFixesTest : VaultUiTestCase() {
    private lateinit var root: String
    private lateinit var vcs: FakeStatusLookup
    private val operations: VaultFileOperations get() = VaultFileOperations.getInstance(project)
    private val tabs: DecryptedVaultTabs get() = DecryptedVaultTabs.getInstance(project)

    private object DiscardTabPrompts : DecryptedTabPrompts {
        override fun askUnsaved(project: Project, request: UnsavedTabsRequest): UnsavedTabsChoice = UnsavedTabsChoice.DISCARD
    }

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().replaceService(DecryptedTabPrompts::class.java, DiscardTabPrompts, testRootDisposable)
        myFixture.enableInspections(AnsiblePlaintextPrivateKeyInspection())
        vcs = FakeStatusLookup(TrackedStatus.TRACKED)
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(vcs), testRootDisposable)
        root = defaultRoot("falcon", cfg = "[defaults]\nvault_password_file = .vault-pass\n")
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

    /** Writes [text] to [relative], rescans the roots and opens the file; its ANS-V108 highlights. */
    private fun open(relative: String, text: String): List<HighlightInfo> {
        write(relative, text)
        root(root)
        myFixture.configureFromExistingVirtualFile(vf(relative))
        return highlight()
    }

    private fun highlight(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME }

    private fun fixes(): List<String> = myFixture.getAllQuickFixes().map { it.text }.filter { it in FIX_NAMES }

    /** The HTML preview of [action] (classic fixes describe themselves; nothing runs). */
    private fun previewOf(action: IntentionAction): String {
        val info = IntentionPreviewPopupUpdateProcessor.getPreviewInfo(project, action, myFixture.file, myFixture.editor)
        assertTrue(info.toString(), info is IntentionPreviewInfo.Html)
        return (info as IntentionPreviewInfo.Html).content().toString()
    }

    private fun awaitOperation() {
        val job = operations.lastOperation ?: error("no operation was started")
        waitFor("file operation timed out") { job.isCompleted }
    }

    /** The text of [relative] on disk, after the writes the IDE still has pending (262 writes saved documents asynchronously). */
    private fun text(relative: String): String {
        (LocalFileSystem.getInstance() as? AsyncableFileSystem)?.fsync()
        return String(Files.readAllBytes(base.resolve(relative)), Charsets.UTF_8)
    }

    private fun decrypt(envelopeText: String): String? {
        val envelope = (VaultEnvelope.parse(envelopeText.trim()) as? EnvelopeParse.Ok)?.envelope ?: return null
        return SecretBytes.of(VaultVectors.PW1.toByteArray()).use { VaultAes256.decrypt(envelope, it)?.let(::String) }
    }

    /** Reports a VCS status change of [file] and lets the restart it causes run. */
    private fun changed(file: VirtualFile) {
        vcs.fire(file)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** The ANS-V108 problems of [file] as the batch inspection reports them. */
    private fun descriptors(file: VirtualFile): List<ProblemDescriptor> = runReadActionBlocking {
        val psi = PsiManager.getInstance(project).findFile(file)!!
        AnsiblePlaintextPrivateKeyInspection().checkFile(psi, InspectionManager.getInstance(project), false).orEmpty().toList()
    }

    // ------------------------------------------------------------------------------------------------ Encrypt file

    fun testEncryptFileEncryptsTheKeyInPlaceAfterAPreviewThatRunsNothing() {
        val relative = "$root/roles/web/files/ssl/web.key"
        val key = TestKeys.plaintextKey()
        val info = open(relative, key).single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals(listOf("Encrypt file"), fixes())
        val batch = descriptors(vf(relative)).single()
        assertEquals("exports show the marker only", TestKeys.begin(TestKeys.PRIVATE), runReadActionBlocking { ProblemDescriptorUtil.extractHighlightedText(batch, batch.psiElement) })
        val action = myFixture.getAllQuickFixes().single { it.text == "Encrypt file" }

        val before = operations.lastOperation
        val preview = previewOf(action)
        assertTrue(preview, preview.startsWith("Encrypts web.key in place with Ansibility Vault (mode 0600)"))
        assertTrue(preview, "rotate a key that was ever committed" in preview)
        assertSame("the preview runs nothing", before, operations.lastOperation)
        assertEquals(key, text(relative))

        myFixture.launchAction(action)
        awaitOperation()
        val written = text(relative)
        assertTrue(written, written.startsWith("\$ANSIBLE_VAULT;1.1;AES256\n"))
        assertEquals("the key, encrypted byte for byte", key, decrypt(written))
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(base.resolve(relative))))
        }
        assertEmpty("a whole-file vault now", highlight())
    }

    fun testFixAllEncryptsEveryFileWithOneConfirmation() {
        val first = "$root/roles/web/files/ssl/web.key"
        val second = "$root/roles/db/files/ssh/db.key"
        write(first, TestKeys.plaintextKey())
        write(second, TestKeys.protectedKey())
        write("$root/group_vars/all/tls.yml", "tls_key: |\n" + TestKeys.plaintextKey(indent = 2))
        root(root)
        val descriptors = listOf(vf(first), vf(second), vf("$root/group_vars/all/tls.yml")).flatMap(::descriptors)
        assertEquals(3, descriptors.size)
        val fix = descriptors.first().fixes!!.single() as EncryptFileFix
        fix.applyFix(project, descriptors.toTypedArray<CommonProblemDescriptor>(), emptyList(), null)
        awaitOperation()
        assertEquals("one question for both files; the YAML value is Encrypt value's", listOf(FileOperation.ENCRYPT to listOf("web.key", "db.key")), prompts.fileRequests)
        assertTrue(VaultEnvelope.isEncrypted(text(first)))
        assertTrue(VaultEnvelope.isEncrypted(text(second)))
        assertFalse(VaultEnvelope.isEncrypted(text("$root/group_vars/all/tls.yml")))
    }

    // ------------------------------------------------------------------------------------------------ Encrypt value

    fun testEncryptValueTurnsAKeyInAYamlValueIntoAVaultValue() {
        val key = TestKeys.plaintextKey(indent = 2)
        val relative = "$root/group_vars/all/tls.yml"
        val info = open(relative, "---\ntls_port: 443\ntls_key: |\n$key").single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals(listOf("Encrypt value"), fixes())
        val action = myFixture.getAllQuickFixes().single { it.text == "Encrypt value" }
        val before = VaultValueActions.getInstance(project).lastAction
        val preview = previewOf(action)
        assertTrue(preview, preview.startsWith("Replaces the value of tls_key with a !vault | block"))
        assertSame("the preview runs nothing", before, VaultValueActions.getInstance(project).lastAction)

        myFixture.launchAction(action)
        awaitAction()
        val document = myFixture.editor.document.text
        assertTrue(document, document.startsWith("---\ntls_port: 443\ntls_key: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n"))
        val envelope = document.substringAfter("!vault |\n").lines().joinToString("\n") { it.trim() }
        assertEquals("the value YAML loads, encrypted", key.lines().joinToString("\n") { it.trim() }.trimEnd() + "\n", decrypt(envelope))
        assertEmpty(highlight())
    }

    // ------------------------------------------------------------------------------------------------ no fix

    fun testNoEncryptFileOutsideARoot() {
        val info = open("docker/certs/web.key", TestKeys.plaintextKey()).single()
        assertEquals("reported outside roots too", HighlightSeverity.ERROR, info.severity)
        assertEmpty("but Encrypt File refuses files outside a root", fixes())
        assertFalse("nor does the message recommend it: ${info.description}", "Ansibility Vault" in info.description!!)
        assertTrue(info.description, info.description!!.startsWith("Plaintext private key (PKCS#8) in a committed file outside every Ansible root"))
    }

    fun testPasswordScriptsAndVaultedPasswordFilesAreNoFinding() {
        val tern = projectRoot("tern", "[defaults]\nvault_password_file = scripts/vault-client.py\n")
        val script = write("$tern/scripts/vault-client.py", "#!/usr/bin/env python3\nprint(keyring.get_password('tern', 'vault'))\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        root(tern)
        myFixture.configureFromExistingVirtualFile(vf("$tern/scripts/vault-client.py"))
        assertEmpty("a password client script holds no password", highlight())
        write("$root/.vault-pass", VaultVectors.encrypt("synthetic-tern", VaultVectors.PW1).format())
        root(root)
        myFixture.configureFromExistingVirtualFile(vf("$root/.vault-pass"))
        assertEmpty("a vaulted password file: Ansible decrypts it, nothing leaks", highlight())
    }

    fun testACommittedVaultPasswordFileIsAnErrorWithoutAFix() {
        root(root)
        val file = vf("$root/.vault-pass")
        myFixture.configureFromExistingVirtualFile(file)
        val info = highlight().single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals(
            "This root's vault password file is committed: it unlocks every vault of the root. Remove it from git, .gitignore it and rekey the vaults, because git history keeps the password.",
            info.description,
        )
        assertEquals("nothing of the password is highlighted", info.startOffset, info.endOffset)
        assertEmpty("never encrypted: Ansible could not read it", fixes())
        assertFalse(VaultVectors.PW1 in info.description!!)
        val batch = descriptors(file).single()
        assertEquals("Inspect Code and Qodana export no text of the file", "", runReadActionBlocking { ProblemDescriptorUtil.extractHighlightedText(batch, batch.psiElement) })

        // Each status change reaches the editor through the VCS listener's restart (vault.vcs.TrackedStatuses).
        vcs.statuses[".vault-pass"] = TrackedStatus.UNTRACKED
        changed(file)
        assertEquals(HighlightSeverity.WARNING, highlight().single().severity)
        assertTrue(highlight().single().description!!.startsWith("This root's vault password file is not ignored by git"))
        vcs.statuses[".vault-pass"] = TrackedStatus.IGNORED
        changed(file)
        assertEmpty("the normal setup", highlight())
        vcs.statuses[".vault-pass"] = TrackedStatus.NO_VCS
        changed(file)
        assertEmpty("without version control a local password file is the normal setup too", highlight())
    }

    fun testDecryptedTabsAndWholeFileVaultsAreNeverReported() {
        val relative = "$root/roles/web/files/ssl/sealed.key"
        write(relative, VaultVectors.encrypt(TestKeys.plaintextKey(), VaultVectors.PW1).formatBytes())
        root(root)
        val file = vf(relative)
        assertEmpty("the vault itself", descriptors(file))
        tabs.open(file)
        tabs.lastAction?.let { job -> waitFor("tab action timed out") { job.isCompleted } }
        val tab = FileEditorManager.getInstance(project).openFiles.filterIsInstance<DecryptedVaultFile>().single { it.original == file }
        assertTrue("the tab holds the key", FileDocumentManager.getInstance().getDocument(tab)!!.text.contains(TestKeys.PRIVATE))
        assertEmpty("its decrypted tab", descriptors(tab))
    }

    private companion object {
        const val SHORT_NAME = "AnsiblePlaintextPrivateKey"
        val FIX_NAMES = setOf("Encrypt file", "Encrypt value")
    }
}
