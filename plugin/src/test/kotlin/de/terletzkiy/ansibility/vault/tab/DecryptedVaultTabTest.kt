package de.terletzkiy.ansibility.vault.tab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import java.util.concurrent.CopyOnWriteArrayList

class DecryptedVaultTabTest : VaultUiTestCase() {
    private class ScriptedTabPrompts : DecryptedTabPrompts {
        val requests = CopyOnWriteArrayList<UnsavedTabsRequest>()
        var answer: UnsavedTabsChoice = UnsavedTabsChoice.DISCARD

        override fun askUnsaved(project: Project, request: UnsavedTabsRequest): UnsavedTabsChoice = answer.also { requests += request }
    }

    private lateinit var tabPrompts: ScriptedTabPrompts
    private val tabs: DecryptedVaultTabs get() = DecryptedVaultTabs.getInstance(project)

    override fun setUp() {
        super.setUp()
        tabPrompts = ScriptedTabPrompts()
        ApplicationManager.getApplication().replaceService(DecryptedTabPrompts::class.java, tabPrompts, testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.filterIsInstance<DecryptedVaultFile>().forEach {
                it.session?.closingQuietly = true
                FileEditorManager.getInstance(project).closeFile(it)
            }
            tabs.openSessions.forEach { com.intellij.openapi.util.Disposer.dispose(it) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A root with `.vault-pass` and a whole-file vault `group_vars/all/vault.yml` holding [plaintext]. */
    private fun vaultFile(plaintext: String, label: String? = null): VirtualFile {
        val root = defaultRoot()
        write("$root/group_vars/all/vault.yml", VaultVectors.encrypt(plaintext, VaultVectors.PW1, label).formatBytes())
        root(root)
        return vf("$root/group_vars/all/vault.yml")
    }

    private fun open(file: VirtualFile): DecryptedVaultFile {
        tabs.open(file)
        awaitTab()
        return FileEditorManager.getInstance(project).openFiles.filterIsInstance<DecryptedVaultFile>().single { it.original == file }
    }

    private fun awaitTab() {
        val job = tabs.lastAction ?: return
        waitFor("tab action timed out") { job.isCompleted }
    }

    private fun banner(file: VirtualFile) = runReadAction { WholeFileVaultBanner.vaultBanner(project, file) }

    fun testBannerFromHeaderOnlyThenVerifiedAfterOpen() {
        val file = vaultFile("db_password: s3cr3t\n")
        val attempts = crypto.decryptAttempts
        val locked = banner(file)!!
        assertEquals("\uD83D\uDD12 Ansible Vault \u00B7 AES256 \u00B7 1.1 \u00B7 id default", locked.text)
        assertTrue(locked.unlock)
        assertTrue(locked.open)
        assertEquals("the banner never decrypts", attempts, crypto.decryptAttempts)

        open(file)
        val unlocked = banner(file)!!
        assertEquals("\uD83D\uDD12 Ansible Vault \u00B7 AES256 \u00B7 1.1 \u00B7 id default \u00B7 decrypts with: default", unlocked.text)
        assertFalse(unlocked.unlock)
    }

    fun testLabelledHeaderShowsItsId() {
        val root = defaultRoot()
        write("$root/group_vars/all/vault.yml", VaultVectors.encrypt("a: 1\n", VaultVectors.DEV, "dev").formatBytes())
        root(root)
        assertEquals("\uD83D\uDD12 Ansible Vault \u00B7 AES256 \u00B7 1.2 \u00B7 id dev", banner(vf("$root/group_vars/all/vault.yml"))!!.text)
    }

    fun testNoBannerForPlainFiles() {
        val root = defaultRoot()
        write("$root/group_vars/all/main.yml", "a: 1\n")
        root(root)
        assertNull(banner(vf("$root/group_vars/all/main.yml")))
        assertNull(runReadAction { VaultTemplateRefDocumentation.line(project, vf("$root/group_vars/all/main.yml"), copy = false) })
    }

    fun testOpenShowsPlaintextAndStaysOutOfHistoryAndIndex() {
        val file = vaultFile("token: zebrasentinel42\n")
        val tab = open(file)
        assertEquals("vault.yml (decrypted)", tab.name)
        assertEquals("token: zebrasentinel42\n", FileDocumentManager.getInstance().getDocument(tab)!!.text)
        assertFalse(tab in EditorHistoryManager.getInstance(project).fileList)
        var found = false
        runReadAction {
            PsiSearchHelper.getInstance(project).processAllFilesWithWord("zebrasentinel42", GlobalSearchScope.allScope(project), { found = true; false }, true)
        }
        assertFalse("the plaintext must not be indexed", found)
    }

    fun testSaveWritesNewEnvelopeWithSameHeaderAndUnchangedSaveWritesNothing() {
        val file = vaultFile("a: 1\n")
        val tab = open(file)
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(tab)!!

        val before = file.contentsToByteArray()
        documents.saveAllDocuments()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue("an unchanged save writes nothing", before.contentEquals(file.contentsToByteArray()))

        WriteCommandAction.runWriteCommandAction(project) { document.setText("a: 2\n") }
        documents.saveDocument(document)
        waitFor("the save did not finish") { !documents.isDocumentUnsaved(document) }
        val written = String(file.contentsToByteArray(), Charsets.UTF_8)
        assertTrue(written, written.startsWith("\$ANSIBLE_VAULT;1.1;AES256"))
        assertEquals("a: 2\n", decryptWith(written, VaultVectors.PW1))
    }

    fun testAutosaveWaitsWhenEncryptOnlyOnExplicitSave() {
        val file = vaultFile("a: 1\n")
        val tab = open(file)
        de.terletzkiy.ansibility.vault.identity.VaultProjectSettings.getInstance(project).encryptOnlyOnExplicitSave = true
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(tab)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("a: 3\n") }
        val before = file.contentsToByteArray()
        assertFalse(tab.maySave(explicit = false))
        assertTrue(before.contentEquals(file.contentsToByteArray()))
        assertTrue(documents.isDocumentUnsaved(document))
    }

    fun testClosingWithUnsavedEditsAsks() {
        val file = vaultFile("a: 1\n")
        val tab = open(file)
        val document = FileDocumentManager.getInstance().getDocument(tab)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("a: 4\n") }
        FileEditorManager.getInstance(project).closeFile(tab)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(UnsavedTabsReason.CLOSE), tabPrompts.requests.map { it.reason })
        assertNull("discarding ends the session", tabs.sessionOf(file))
        assertFalse(tab.isValid)
    }

    fun testLockClosesTheTab() {
        val file = vaultFile("a: 1\n")
        val tab = open(file)
        secrets.lockAll()
        waitFor("the tab did not close") { !FileEditorManager.getInstance(project).isFileOpen(tab) }
        assertNull(tabs.sessionOf(file))
        assertTrue(tabPrompts.requests.isEmpty())
    }

    fun testTemplateRefLineForWholeFileVault() {
        val file = vaultFile("a: 1\n")
        assertEquals(
            "\uD83D\uDD12 whole-file vault \u00B7 id default \u00B7 Ansible decrypts it when the task runs",
            runReadAction { VaultTemplateRefDocumentation.line(project, file, copy = false) },
        )
        assertTrue(runReadAction { VaultTemplateRefDocumentation.line(project, file, copy = true) }!!.endsWith("`decrypt: false` copies the envelope"))
    }
}
