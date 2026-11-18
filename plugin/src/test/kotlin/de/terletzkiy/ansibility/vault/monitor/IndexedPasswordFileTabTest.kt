package de.terletzkiy.ansibility.vault.monitor

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.inspections.vault.FakeStatusLookup
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.exclusion.FileSetRebuilds
import de.terletzkiy.ansibility.vault.exclusion.IndexExclusionPrompts
import de.terletzkiy.ansibility.vault.exclusion.IndexExclusionSettings
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan amendment R30 (D227): the Vault tab's category "Password files the IDE indexes": a vault password file of a root
 * inside the project content (git-ignored or not: the IDE indexes ignored files too), never one the list keeps out
 * (`.vault-pass`), never a password script, an empty or a vaulted password file. Its fix "Keep Out of the IDE's
 * Indexes" adds the name and offers to reopen; until then the finding says the name is listed; once the content root's
 * file sets are built again (a reopen), the finding is gone. The notification offers the same fix.
 */
class IndexedPasswordFileTabTest : VaultUiTestCase() {
    private lateinit var root: String
    private lateinit var vcs: FakeStatusLookup
    private lateinit var monitor: Monitor
    private lateinit var reopen: FakeReopen
    private val exclusions: IndexExclusionSettings get() = IndexExclusionSettings.getInstance()

    /** Scripted answers to the reopen question. */
    private class FakeReopen : IndexExclusionPrompts {
        val asked = CopyOnWriteArrayList<List<String>>()
        val reopened = CopyOnWriteArrayList<Project>()
        var answer = false

        override fun askReopen(project: Project, names: List<String>): Boolean = answer.also { asked += names }

        override fun reopen(project: Project) {
            reopened += project
        }
    }

    private lateinit var rebuilds: FileSetRebuilds

    override fun setUp() {
        super.setUp()
        exclusions.resetForTests()
        rebuilds = FileSetRebuilds(project)
        vcs = FakeStatusLookup(TrackedStatus.TRACKED)
        vcs.statuses[".vault-pass"] = TrackedStatus.IGNORED
        vcs.statuses["dev.pw"] = TrackedStatus.IGNORED
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(vcs), testRootDisposable)
        reopen = FakeReopen()
        ApplicationManager.getApplication().replaceService(IndexExclusionPrompts::class.java, reopen, testRootDisposable)
        root = defaultRoot("falcon", cfg = "[defaults]\nvault_identity_list = dev@secrets/dev.pw\nvault_password_file = .vault-pass\n")
        monitor = Monitor(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            exclusions.resetForTests()
            rebuilds.close()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun start(): SecretSnapshot {
        root(root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        monitor.start()
        return monitor.await { it.statusesKnown }
    }

    private fun indexed(snapshot: SecretSnapshot): List<SecretFinding> = snapshot.findings.filter { it.category == SecretCategory.INDEXED_PASSWORD_FILES }

    private var productionDataManager = false

    private fun perform(panel: SecretHealthPanel, action: AnAction): Boolean {
        if (!productionDataManager) {
            HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
            productionDataManager = true
        }
        val event = TestActionEvent.createTestEvent(action, DataManager.getInstance().getDataContext(panel.tree))
        action.update(event)
        if (!event.presentation.isEnabled) return false
        action.actionPerformed(event)
        return true
    }

    fun testAPasswordFileTheIdeIndexesIsAWarningGitIgnoredOrNot() {
        write("$root/secrets/dev.pw", "${VaultVectors.DEV}\n")
        start()
        val finding = indexed(monitor.await { indexed(it).isNotEmpty() }).single()
        assertEquals("secrets/dev.pw", finding.path)
        assertEquals(Level.WARNING, finding.level)
        assertNull("no inspection reports it", finding.code)
        assertEquals(TrackedStatus.IGNORED, finding.status)
        assertEquals(SecretFix.KEEP_OUT_OF_INDEXES, finding.fix)
        assertEquals(listOf("the vault password file of falcon: word search, text search and Local History keep its content"), finding.details)
        assertFalse("the .vault-pass of the same root is kept out", monitor.service.snapshot.findings.any { it.file.name == ".vault-pass" })

        vcs.statuses["dev.pw"] = TrackedStatus.TRACKED
        vcs.fire(null)
        val committed = monitor.await { s -> s.findings.any { it.category == SecretCategory.VAULT_PASSWORD_FILES } }
        assertEquals(
            "committed: both categories",
            listOf(SecretCategory.VAULT_PASSWORD_FILES, SecretCategory.INDEXED_PASSWORD_FILES),
            committed.findings.filter { it.file.name == "dev.pw" }.map { it.category },
        )
    }

    fun testScriptsEmptyAndVaultedPasswordFilesAreNotListed() {
        root = projectRoot(
            "tern",
            "[defaults]\nvault_identity_list = dev@secrets/dev.pw, ops@secrets/ops.pw, empty@secrets/empty.pw\nvault_password_file = scripts/vault-client.py\n",
        )
        val script = write("$root/scripts/vault-client.py", "#!/usr/bin/env python3\nprint('from the keyring')\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        write("$root/secrets/dev.pw", VaultVectors.encrypt("synthetic-tern", VaultVectors.PW1).format())
        write("$root/secrets/ops.pw", "${VaultVectors.PROD}\n")
        write("$root/secrets/empty.pw", "")
        start()
        val snapshot = monitor.await { indexed(it).isNotEmpty() }
        assertEquals("only the plaintext, non-empty password file", listOf("secrets/ops.pw"), indexed(snapshot).map { it.path })
    }

    fun testKeepOutAddsTheNameOffersToReopenAndTheReopenClearsTheFinding() {
        write("$root/secrets/dev.pw", "${VaultVectors.DEV}\n")
        start()
        monitor.await { indexed(it).isNotEmpty() }
        val panel = SecretHealthPanel(project)
        Disposer.register(testRootDisposable, panel)
        PlatformTestUtil.waitWithEventsDispatching("the tab shows the finding", { indexed(panel.shown).isNotEmpty() }, 30)
        assertTrue(panel.rowTexts().toString(), panel.rowTexts().any { it.trim().startsWith("Password files the IDE indexes") })
        panel.tree.selectionPath = (0 until panel.tree.rowCount).map { panel.tree.getPathForRow(it) }.first { it.lastPathComponent.toString().startsWith("secrets/dev.pw") }

        reopen.answer = true
        assertTrue(perform(panel, SecretTabActions.KeepOut()))
        assertEquals(listOf("dev.pw"), exclusions.extraNames)
        assertEquals(listOf(listOf("dev.pw")), reopen.asked)
        assertEquals("reopened when you agree", listOf(project), reopen.reopened.toList())
        val listed = monitor.await { s -> indexed(s).singleOrNull()?.details?.single()?.contains("listed") == true }
        assertEquals(
            "D225: listed, but kept out only after the project opens again",
            listOf("the vault password file of falcon: listed in Keep out of the IDE's indexes, applies when you reopen the project"),
            indexed(listed).single().details,
        )
        assertFalse("not a plaintext key check", perform(panel, SecretTabActions.ExcludePath()))

        rebuilds.rebuild()
        monitor.refresh()
        assertEmpty("kept out after the file sets are built again", indexed(monitor.await { indexed(it).isEmpty() }))
    }

    fun testTheNotificationOffersKeepOut() {
        val notifications = VaultNotifications(project, testRootDisposable)
        write("$root/secrets/dev.pw", "${VaultVectors.DEV}\n")
        start()
        monitor.await { indexed(it).isNotEmpty() }
        monitor.refresh()
        val notification = notifications.vault.single()
        assertEquals("falcon: 1 password file the IDE indexes (dev.pw)", notification.content)
        assertEquals(listOf("Show", "Keep Out of the IDE's Indexes", "Don't Show Again for These"), VaultNotifications.actions(notification))
        notifications.click(notification, "Keep Out of the IDE's Indexes")
        assertEquals(listOf("dev.pw"), exclusions.extraNames)
        assertEquals("asked, not reopened (answered Later)", listOf(listOf("dev.pw")), reopen.asked)
        assertEmpty(reopen.reopened)
    }
}
