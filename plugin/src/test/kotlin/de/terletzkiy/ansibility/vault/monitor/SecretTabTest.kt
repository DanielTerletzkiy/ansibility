package de.terletzkiy.ansibility.vault.monitor

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.AsyncableFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ide.impl.HeadlessDataManager
import de.terletzkiy.ansibility.inspections.vault.FakeStatusLookup
import de.terletzkiy.ansibility.inspections.vault.TestKeys
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowIcons
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.FileOperation
import de.terletzkiy.ansibility.vault.actions.VaultFileOperations
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.ui.VaultUiTestCase
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon
import javax.swing.tree.TreePath

/**
 * The Vault tab of the Ansibility tool window (plan amendment R21, D165) on a synthetic root `repos/falcon/ansible`
 * (with `.vault-pass`, id default, password pw1 of `tools/vault/SYNTHETIC.md`) in a real temporary directory: the tree
 * (repository › category › file, details and VCS status), live updates, the clean state, opening files, its actions
 * (Encrypt Files…, Convert to Whole-File Vault, Exclude Path…, Show in Project View), the committed vault password
 * file, and the tool window's badge. Keys are made at test time ([TestKeys]); the VCS status is scripted.
 */
class SecretTabTest : VaultUiTestCase() {
    private lateinit var root: String
    private lateinit var vcs: FakeStatusLookup
    private lateinit var monitor: Monitor
    private lateinit var panel: SecretHealthPanel
    private lateinit var tabPrompts: FakeTabPrompts

    /** Scripted answers of the tab's questions. */
    private class FakeTabPrompts : SecretTabPrompts {
        val suggestions = CopyOnWriteArrayList<String>()
        val shownInProjectView = CopyOnWriteArrayList<VirtualFile>()
        var glob: String? = null

        override fun askExcludeGlob(project: Project, suggestion: String): String? = glob.also { suggestions += suggestion }

        override fun selectInProjectView(project: Project, file: VirtualFile) {
            shownInProjectView += file
        }
    }

    /** A headless tool window that keeps the icon it is given. */
    private class IconWindow(project: Project) : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
        val icons = CopyOnWriteArrayList<Icon>()
        private var current: Icon? = AnsibilityToolWindowIcons.ToolWindow

        override fun setIcon(icon: Icon) {
            icons += icon
            current = icon
        }

        override fun getIcon(): Icon? = current
    }

    override fun setUp() {
        super.setUp()
        vcs = FakeStatusLookup(TrackedStatus.TRACKED)
        vcs.statuses[".vault-pass"] = TrackedStatus.IGNORED
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf<TrackedStatusLookup>(vcs), testRootDisposable)
        tabPrompts = FakeTabPrompts()
        ApplicationManager.getApplication().replaceService(SecretTabPrompts::class.java, tabPrompts, testRootDisposable)
        root = defaultRoot("falcon", cfg = "[defaults]\nvault_password_file = .vault-pass\n")
        monitor = Monitor(project, testRootDisposable)
    }

    private fun start(files: Map<String, String> = emptyMap(), count: Int = files.size): SecretSnapshot {
        files.forEach { (path, text) -> write("$root/$path", text) }
        root(root)
        monitor.start()
        panel = SecretHealthPanel(project)
        Disposer.register(testRootDisposable, panel)
        return awaitTab(count)
    }

    /** Waits until the tab shows a snapshot with [count] findings. */
    private fun awaitTab(count: Int): SecretSnapshot {
        val snapshot = monitor.await { it.findings.size == count }
        PlatformTestUtil.waitWithEventsDispatching({ "the tab shows ${panel.shown.findings}" }, { panel.shown == snapshot }, 30)
        return snapshot
    }

    private fun envelope(): VaultEnvelope = VaultVectors.encrypt("value", VaultVectors.PW1)

    /** The visible row of the tree whose node text starts with [prefix]. */
    private fun rowPath(prefix: String): TreePath {
        val rows = (0 until panel.tree.rowCount).map { panel.tree.getPathForRow(it) }
        return rows.firstOrNull { it.lastPathComponent.toString().startsWith(prefix) } ?: error("no row $prefix in ${panel.rowTexts()}")
    }

    /** Selects the row of the tree whose node text starts with [prefix]. */
    private fun selectRow(prefix: String) {
        panel.tree.selectionPath = rowPath(prefix)
    }

    private var productionDataManager = false

    /** The data context of the panel's tree, as the toolbar and the context menu see it. */
    private fun dataContext(): DataContext {
        if (!productionDataManager) {
            HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
            productionDataManager = true
        }
        return DataManager.getInstance().getDataContext(panel.tree)
    }

    /** Updates and, when enabled, performs [action] on the panel's data context; whether it was enabled. */
    private fun perform(action: AnAction): Boolean {
        val event = TestActionEvent.createTestEvent(action, dataContext())
        action.update(event)
        if (!event.presentation.isEnabled) return false
        action.actionPerformed(event)
        return true
    }

    private fun text(relative: String): String {
        (LocalFileSystem.getInstance() as? AsyncableFileSystem)?.fsync()
        return String(Files.readAllBytes(base.resolve(relative)), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------------------------------------ the tree

    fun testTheTreeListsRepositoryCategoryAndFilesWithDetailsAndStatusLive() {
        vcs.statuses["old.key"] = TrackedStatus.UNTRACKED
        val key = TestKeys.plaintextKey()
        val wrapped = envelope()
        start(
            mapOf(
                "roles/web/files/ssl/wrapped.key" to pasted(wrapped),
                "roles/web/files/ssl/db.key" to key,
                "roles/web/files/ssl/old.key" to TestKeys.protectedKey(),
                "roles/web/files/ssl/plain.key" to "not a key and not a vault\n",
            ),
        )
        assertEquals(
            listOf(
                "falcon  2 errors, 2 warnings",
                "  Broken vault files  1",
                "    roles/web/files/ssl/wrapped.key  a !vault line before the envelope, line 1 · committed",
                "  Plaintext private keys  1",
                "    roles/web/files/ssl/db.key  PKCS#8, line 1 · committed",
                "  Protected keys and keystores  1",
                "    roles/web/files/ssl/old.key  RSA, PKCS#1, passphrase-protected, line 1 · not committed yet",
                "  Key-like files not vaulted  1",
                "    roles/web/files/ssl/plain.key  not vaulted: a *.key file below files/ssl · committed",
            ),
            panel.rowTexts(),
        )
        val shown = panel.rowTexts().joinToString("\n")
        assertFalse("no key material", TestKeys.bodyLine(key).take(12) in shown)
        assertFalse("no envelope payload", wrapped.formatLines()[1].take(12) in shown)
        selectRow("Plaintext private keys")
        assertEquals("a category row stands for its files", listOf("roles/web/files/ssl/db.key"), panel.selectedFindings().map { it.path })
        selectRow("Protected keys and keystores")
        assertEquals(listOf("roles/web/files/ssl/old.key"), panel.selectedFindings().map { it.path })
        assertEmpty("selecting rows opens nothing", FileEditorManager.getInstance(project).openFiles.toList())

        val broken = rowPath("Broken vault files")
        panel.tree.collapsePath(broken)
        Files.delete(base.resolve("$root/roles/web/files/ssl/db.key"))
        vf("$root/roles/web/files/ssl").refresh(false, true)
        awaitTab(3)
        assertFalse(panel.rowTexts().joinToString("\n"), panel.rowTexts().any { "db.key" in it })
        assertEquals("the selection is kept", listOf("roles/web/files/ssl/old.key"), panel.selectedFindings().map { it.path })
        assertTrue("the selected row itself", panel.tree.selectionPaths!!.single().lastPathComponent.toString() == "Protected keys and keystores")
        assertFalse("a collapsed row stays collapsed", panel.tree.isExpanded(rowPath("Broken vault files")))
        assertTrue(panel.tree.isExpanded(rowPath("Key-like files not vaulted")))
    }

    fun testTheCleanStateNamesTheRoots() {
        start()
        assertEmpty(panel.rowTexts())
        assertEquals("No vault or secret problems in 1 root", panel.tree.emptyText.text)
    }

    fun testOpensTextFilesButNeverABinaryKeystore() {
        write("$root/roles/web/files/ssl/web.der", TestKeys.pkcs8())
        start(mapOf("roles/web/files/ssl/db.key" to TestKeys.certificate() + TestKeys.plaintextKey()), count = 2)
        val byPath = panel.shown.findings.associateBy { it.path }
        val der = byPath.getValue("roles/web/files/ssl/web.der")
        assertTrue("an unencrypted DER key is a plaintext key", der.isError)
        assertEquals(listOf("DER"), der.details)
        assertFalse("a binary key is never opened", panel.open(der))
        assertEmpty(FileEditorManager.getInstance(project).openFiles.toList())
        selectRow("roles/web/files/ssl/web.der")
        assertNull("no navigatable for a binary file", CommonDataKeys.NAVIGATABLE_ARRAY.getData(dataContext()))

        selectRow("roles/web/files/ssl/db.key")
        val navigatable = CommonDataKeys.NAVIGATABLE_ARRAY.getData(dataContext())!!.single() as OpenFileDescriptor
        assertEquals("the key's line, after the certificate", TestKeys.certificate().lines().size - 1, navigatable.line)
        assertTrue(panel.open(byPath.getValue("roles/web/files/ssl/db.key")))
        assertEquals(listOf("db.key"), FileEditorManager.getInstance(project).openFiles.map { it.name })
    }

    // ------------------------------------------------------------------------------------------------ actions

    fun testEncryptFilesEncryptsTheSelectedKeyFiles() {
        start(mapOf("roles/web/files/ssl/db.key" to TestKeys.plaintextKey(), "roles/web/files/ssl/web.key" to TestKeys.plaintextKey()))
        selectRow("Plaintext private keys")
        assertFalse("nothing to convert", perform(SecretTabActions.Convert()))
        assertTrue(perform(SecretTabActions.Encrypt()))
        val job = VaultFileOperations.getInstance(project).lastOperation ?: error("no operation")
        waitFor("encrypting timed out") { job.isCompleted }
        assertEquals(FileOperation.ENCRYPT to listOf("db.key", "web.key"), prompts.fileRequests.single())
        for (name in listOf("db.key", "web.key")) {
            val encrypted = text("$root/roles/web/files/ssl/$name")
            assertTrue(encrypted.startsWith(VaultEnvelope.MAGIC))
            assertNotNull("decrypts with pw1", decryptWith(encrypted.trim(), VaultVectors.PW1))
        }
        awaitTab(0)
    }

    fun testConvertToWholeFileVaultConvertsTheSelectedFiles() {
        val envelope = envelope()
        start(mapOf("roles/web/files/ssl/wrapped.key" to pasted(envelope), "roles/web/files/app.vault" to "# a preamble\n" + envelope.format()))
        selectRow("Broken vault files")
        assertFalse("no key to encrypt", perform(SecretTabActions.Encrypt()))
        assertTrue(perform(SecretTabActions.Convert()))
        for (path in listOf("roles/web/files/ssl/wrapped.key", "roles/web/files/app.vault")) {
            val converted = text("$root/$path")
            assertEquals(envelope.format(), converted)
            assertEquals("value", decryptWith(converted.trim(), VaultVectors.PW1))
        }
        assertEquals(listOf("Converted to whole-file vaults: app.vault, wrapped.key."), feedback())
        awaitTab(0)
    }

    fun testConvertLeavesAByteOrderMarkAndAMalformedEnvelopeAlone() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + envelope().format().toByteArray()
        write("$root/roles/web/files/bom.vault", bom)
        val broken = "!vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  616\n"
        start(mapOf("roles/web/files/broken.vault" to broken), count = 2)
        assertEquals("the byte order mark has no fix", null, panel.shown.findings.single { it.path.endsWith("bom.vault") }.fix)
        WholeFileConversions.convert(project, panel.shown.findings.map { it.file })
        assertEquals(broken, text("$root/roles/web/files/broken.vault"))
        val report = feedback().single()
        assertTrue(report, report.startsWith("Nothing was converted. Left alone: "))
        assertTrue(report, "bom.vault (a byte order mark: remove it with File | File Properties | Remove BOM)" in report)
        assertTrue(report, "broken.vault (its envelope is malformed; it needs its author)" in report)
    }

    fun testExcludePathAddsAGlobToTheAllowlist() {
        start(mapOf("roles/web/files/ssl/db.key" to TestKeys.plaintextKey(), "roles/web/files/ssl/wrapped.key" to pasted(envelope())))
        selectRow("roles/web/files/ssl/wrapped.key")
        assertFalse("only plaintext key findings can be excluded", perform(SecretTabActions.ExcludePath()))
        selectRow("roles/web/files/ssl/db.key")
        tabPrompts.glob = "**/files/ssl/db.key"
        assertTrue(perform(SecretTabActions.ExcludePath()))
        assertEquals(listOf(vf("$root/roles/web/files/ssl/db.key").path), tabPrompts.suggestions)
        assertEquals(listOf("**/molecule/**", "**/files/ssl/db.key"), VaultProjectSettings.getInstance(project).plaintextKeyAllowlist)
        awaitTab(1)
        assertEquals(listOf(SecretCategory.BROKEN_VAULT_FILES), panel.shown.findings.map { it.category })
    }

    fun testExcludePathNeverSuggestsOrTakesMoreThanOneRepository() {
        val tern = defaultRoot("tern")
        write("$tern/roles/db/files/ssl/db.key", TestKeys.plaintextKey())
        root(tern)
        start(mapOf("roles/web/files/ssl/web.key" to TestKeys.plaintextKey(), "roles/web/files/ssh/deploy.key" to TestKeys.plaintextKey()), count = 3)
        val findings = panel.shown.findings
        assertEquals(2, findings.map { it.group }.distinct().size)
        val first = findings.first().file
        assertEquals("two repositories: the first file only, never a glob above them", first.path, SecretTabActions.suggestion(project, findings))
        val falcon = findings.filter { it.group == findings.first().group }
        assertEquals(vf("$root/roles/web/files").path + "/**", SecretTabActions.suggestion(project, falcon))

        selectRow("falcon")
        tabPrompts.glob = "**"
        assertTrue(perform(SecretTabActions.ExcludePath()))
        assertEquals("a glob that matches every path is refused", listOf("**/molecule/**"), VaultProjectSettings.getInstance(project).plaintextKeyAllowlist)
    }

    fun testPasswordScriptsAndVaultedPasswordFilesAreNotListed() {
        vcs.statuses[".vault-pass"] = TrackedStatus.TRACKED
        val tern = projectRoot("tern", "[defaults]\nvault_password_file = scripts/vault-client.py\n")
        val script = write("$tern/scripts/vault-client.py", "#!/usr/bin/env python3\nprint(keyring.get_password('tern', 'vault'))\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        write("$root/.vault-pass", VaultVectors.encrypt("synthetic-tern", VaultVectors.PW1).format())
        root(tern)
        assertEmpty("a committed client script and a vaulted password file leak nothing", start().findings)
        write("$root/.vault-pass", "${VaultVectors.PW1}\n")
        vf("$root/.vault-pass").refresh(false, false)
        monitor.refresh()
        assertEquals(listOf(SecretCategory.VAULT_PASSWORD_FILES), awaitTab(1).findings.map { it.category })
    }

    fun testTheNotificationEncryptsWhenEveryNewFindingIsAKeyFile() {
        val notifications = VaultNotifications(project, testRootDisposable)
        start(mapOf("roles/web/files/ssl/db.key" to TestKeys.plaintextKey(), "roles/web/files/ssl/web.key" to TestKeys.plaintextKey()))
        monitor.refresh()
        val notification = notifications.vault.single()
        assertEquals("falcon: 2 plaintext private keys (db.key, web.key)", notification.content)
        assertEquals(listOf("Show", "Encrypt Files…", "Don't Show Again for These"), VaultNotifications.actions(notification))
        notifications.click(notification, "Encrypt Files…")
        val job = VaultFileOperations.getInstance(project).lastOperation ?: error("no operation")
        waitFor("encrypting timed out") { job.isCompleted }
        assertTrue(text("$root/roles/web/files/ssl/web.key").startsWith(VaultEnvelope.MAGIC))
        awaitTab(0)
        assertTrue("its findings are gone, so is the notification", notification.isExpired)
    }

    fun testShowInProjectViewSelectsTheFirstFile() {
        start(mapOf("roles/web/files/ssl/db.key" to TestKeys.plaintextKey()))
        selectRow("falcon")
        assertTrue(perform(SecretTabActions.ShowInProjectView()))
        assertEquals(listOf(vf("$root/roles/web/files/ssl/db.key")), tabPrompts.shownInProjectView)
    }

    fun testAVaultPasswordFileInVersionControlIsListed() {
        vcs.statuses[".vault-pass"] = TrackedStatus.TRACKED
        val committed = start(count = 1).findings.single()
        assertEquals("a password file is no private key (D161)", SecretCategory.VAULT_PASSWORD_FILES, committed.category)
        assertTrue(committed.isError)
        assertEquals(listOf("the vault password file of falcon"), committed.details)
        assertNull("no fix: it must leave version control", committed.fix)
        selectRow(".vault-pass")
        assertFalse(perform(SecretTabActions.Encrypt()))

        vcs.statuses[".vault-pass"] = TrackedStatus.UNTRACKED
        vcs.fire(null)
        assertFalse("not committed yet: a warning", monitor.await { it.findings.singleOrNull()?.isError == false }.findings.single().isError)
        vcs.statuses[".vault-pass"] = TrackedStatus.NO_VCS
        vcs.fire(null)
        monitor.await("a local password file outside version control is the normal setup") { it.findings.isEmpty() }
    }

    // ------------------------------------------------------------------------------------------------ badge

    fun testTheToolWindowIconHasARedBadgeWhileErrorsExist() {
        val window = IconWindow(project)
        start()
        SecretTab.installBadge(project, window, testRootDisposable)
        assertEmpty("no errors: the icon is not touched", window.icons)

        write("$root/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        vf("$root/roles/web/files/ssl").refresh(false, true)
        awaitTab(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // The badged icon itself (BadgeIconSupplier.getErrorIcon(true)) is the plain icon under the tests' dummy IconManager.
        assertEquals("an error puts the badge on once", 1, window.icons.size)
        monitor.refresh()
        assertEquals("the same state does not touch the icon again", 1, window.icons.size)

        vcs.statuses["db.key"] = TrackedStatus.UNTRACKED
        vcs.fire(null)
        monitor.await { it.findings.singleOrNull()?.isError == false }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("a warning has no badge", 2, window.icons.size)
        assertSame(AnsibilityToolWindowIcons.ToolWindow, window.icons.last())
    }

    fun testTheFactoryAddsTheVaultTabLast() {
        start()
        val window = IconWindow(project)
        de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowFactory().createToolWindowContent(project, window)
        assertEquals(listOf("Repos", "Roles", "Environments", "Vault"), window.contentManager.contents.map { it.displayName })
        val vault = window.contentManager.contents.last()
        assertFalse(vault.isCloseable)
        assertTrue(vault.component is SecretHealthPanel)
        window.contentManager.removeAllContents(true)
    }
}
