package de.terletzkiy.ansibility.vault.monitor

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.content.ContentFactory
import com.intellij.util.xmlb.XmlSerializer
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.inspections.vault.FakeStatusLookup
import de.terletzkiy.ansibility.inspections.vault.TestKeys
import de.terletzkiy.ansibility.toolwindow.AnsibleToolWindowFactory
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.vcs.TrackedStatus
import de.terletzkiy.ansibility.vault.vcs.TrackedStatusLookup
import javax.swing.JPanel

/**
 * The vault notification (plan amendment R21, D166) on a synthetic root `site/` with a scripted VCS status: once per new
 * set of findings and never again for the same set, not again when a finding comes back soon (a branch switch) but after
 * a week, again when a warning becomes an error, nothing before the VCS knows the statuses or while the Vault tab is in
 * view, remembered per user at application level, Don't Show Again for These, the groups and fix actions, Show, and
 * names and counts only.
 */
class SecretNotifierTest : BasePlatformTestCase() {
    private lateinit var vcs: FakeStatusLookup
    private lateinit var monitor: Monitor
    private lateinit var notifications: VaultNotifications

    /** A VCS that answers [whenReady] only when the test says so. */
    private class LateLookup(private val delegate: FakeStatusLookup) : TrackedStatusLookup by delegate {
        var ready: (() -> Unit)? = null

        override fun whenReady(project: Project, ready: () -> Unit) {
            this.ready = ready
        }
    }

    override fun setUp() {
        super.setUp()
        vcs = FakeStatusLookup(TrackedStatus.TRACKED)
        if (name != "testNothingIsAnnouncedBeforeTheVcsKnowsTheStatuses") mask(vcs)
        monitor = Monitor(project, testRootDisposable)
        notifications = VaultNotifications(project, testRootDisposable)
        create("site/ansible.cfg", "[defaults]\nroles_path = roles\n")
    }

    private fun mask(lookup: TrackedStatusLookup) =
        ExtensionTestUtil.maskExtensions(TrackedStatusLookup.EP_NAME, listOf(lookup), testRootDisposable)

    private fun create(path: String, text: String): VirtualFile =
        myFixture.tempDirFixture.createFile(path, text).also {
            (AnsibleWorkspaceImpl.getInstance(project) ?: error("AnsibleWorkspace is not AnsibleWorkspaceImpl")).structureChanged()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }

    private fun delete(file: VirtualFile) = WriteAction.runAndWait<Throwable> { file.delete(this) }

    private fun wrapped() = pasted(VaultVectors.encrypt("value", VaultVectors.PW1))

    /** Waits until the notifier saw a snapshot with [count] findings (the notifications of it are recorded by then). */
    private fun settle(count: Int) {
        monitor.await { it.findings.size == count }
        monitor.refresh()
    }

    private fun content(index: Int = notifications.vault.lastIndex): String = notifications.vault[index].content

    fun testNewFindingsAreAnnouncedOncePerSetWithNamesAndCountsOnly() {
        val key = TestKeys.plaintextKey()
        create("site/roles/web/files/ssl/wrapped.key", wrapped())
        create("site/roles/web/files/ssl/db.key", key)
        monitor.start()
        settle(2)
        val first = notifications.vault.single()
        assertEquals(SecretNotifier.GROUP_ID, first.groupId)
        assertEquals(NotificationType.ERROR, first.type)
        assertEquals("Ansibility Vault", first.title)
        assertEquals("site: 1 broken vault file (wrapped.key), 1 plaintext private key (db.key)", first.content)
        assertEquals(listOf("Show", "Don't Show Again for These"), VaultNotifications.actions(first))
        assertFalse(TestKeys.bodyLine(key).take(12) in first.content)

        monitor.refresh()
        assertEquals("the same set is not announced again", 1, notifications.vault.size)

        create("site/roles/web/files/ssl/new.key", key)
        settle(3)
        assertEquals(2, notifications.vault.size)
        assertEquals("only the new finding", "site: 1 plaintext private key (new.key)", content())
        assertTrue("the older notification is replaced", first.isExpired)
    }

    fun testAFindingThatComesBackSoonIsNotAnnouncedAgainButAWeekLaterItIs() {
        var now = 1_000_000_000L
        monitor.notifier.nowForTests = { now }
        val file = create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        monitor.start()
        settle(1)
        val first = notifications.vault.single()
        delete(file)
        settle(0)
        assertTrue("its findings are all gone", first.isExpired)
        now += 60_000
        val back = create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        settle(1)
        assertEquals("back after a branch switch: announced already", 1, notifications.vault.size)

        delete(back)
        settle(0)
        now += SecretNotificationMemory.FORGET_AFTER_MS + 60_000
        monitor.refresh()
        create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        settle(1)
        assertEquals("a week after its fix, the key is new again", 2, notifications.vault.size)
    }

    fun testAWarningThatBecomesAnErrorIsAnnouncedAgain() {
        vcs.statuses["db.key"] = TrackedStatus.UNTRACKED
        create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        monitor.start()
        settle(1)
        val warning = notifications.vault.single()
        assertEquals(SecretNotifier.WARNINGS_GROUP_ID, warning.groupId)
        notifications.click(warning, "Don't Show Again for These")

        // Committed from the terminal: the commit check never saw it.
        vcs.statuses["db.key"] = TrackedStatus.TRACKED
        vcs.fire(null)
        monitor.await { it.findings.singleOrNull()?.isError == true }
        monitor.refresh()
        assertEquals("a warning silenced or announced does not cover the error", 2, notifications.vault.size)
        val error = notifications.vault.last()
        assertEquals(SecretNotifier.GROUP_ID, error.groupId)
        assertEquals(NotificationType.ERROR, error.type)
        assertEquals("site: 1 plaintext private key (db.key)", error.content)

        vcs.statuses["db.key"] = TrackedStatus.UNTRACKED
        vcs.fire(null)
        monitor.await { it.findings.singleOrNull()?.isError == false }
        monitor.refresh()
        assertEquals("back to a warning: covered by the error", 2, notifications.vault.size)
    }

    fun testTheMemoryKeepsEveryPresentFindingBeyondItsBound() {
        val memory = SecretNotificationMemory.getInstance()
        val many = (1..SecretNotificationMemory.MAX_KEYS + 1000).associate { "site|files/$it.key|PLAINTEXT_KEYS" to true }
        memory.remember("loc", many, 1L)
        assertTrue("every present finding stays known", many.keys.all { memory.isAnnounced("loc", it, error = true) })
        memory.remember("loc", many, 2L)
        assertEquals(many.size, memory.known("loc").size)
        memory.remember("loc", mapOf("site|files/w.key|KEY_LIKE_FILES" to false), 3L)
        assertTrue(memory.isAnnounced("loc", "site|files/w.key|KEY_LIKE_FILES", error = false))
        assertFalse("known as a warning, new as an error", memory.isAnnounced("loc", "site|files/w.key|KEY_LIKE_FILES", error = true))
        assertTrue("absent ones go beyond the bound, least recently seen first", memory.known("loc").size <= SecretNotificationMemory.MAX_KEYS + 1000)
    }

    fun testNothingIsAnnouncedBeforeTheVcsKnowsTheStatuses() {
        val late = LateLookup(vcs)
        mask(late)
        create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        monitor.start()
        monitor.await { it.computed && !it.statusesKnown }
        monitor.refresh()
        assertEmpty("the key waits for its status: it may be git-ignored", monitor.service.snapshot.findings)
        assertEmpty(notifications.vault)
        val ready = late.ready ?: error("the service did not ask whether the VCS is ready")
        ready()
        PlatformTestUtil.waitWithEventsDispatching("no notification once the statuses are known", { notifications.vault.isNotEmpty() }, 30)
    }

    fun testNothingWhileTheVaultTabIsInViewAndWhatItShowedCountsAsSeen() {
        monitor.notifier.inViewForTests = { true }
        create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        monitor.start()
        settle(1)
        assertEmpty(notifications.vault)
        monitor.notifier.inViewForTests = { false }
        monitor.refresh()
        assertEmpty("seen in the tab", notifications.vault)
    }

    fun testRememberedPerUserAtApplicationLevel() {
        create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        monitor.start()
        settle(1)
        assertEquals(1, notifications.vault.size)
        val memory = SecretNotificationMemory.getInstance()
        val location = SecretNotifier.locationOf(project)
        assertEquals(setOf("site|roles/web/files/ssl/db.key|PLAINTEXT_KEYS"), memory.known(location))
        val xml = JDOMUtil.write(XmlSerializer.serialize(memory.state))
        assertTrue(xml, "roles/web/files/ssl/db.key" in xml && location in xml)

        // A restart: the notifier starts over, the memory is the user's.
        val saved = memory.state
        memory.resetForTests()
        memory.loadState(saved)
        monitor.notifier.resetForTests()
        monitor.notifier.statusesReady()
        monitor.refresh()
        assertEquals("known after a restart", 1, notifications.vault.size)

        val annotation = SecretNotificationMemory::class.java.getAnnotation(State::class.java)
        assertEquals("ansibility-vault-notifications.xml", annotation.storages.single().value)
        assertEquals("per user, never roaming, never a project file", RoamingType.DISABLED, annotation.storages.single().roamingType)
    }

    fun testDontShowAgainForTheseSilencesThoseFindings() {
        val file = create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        monitor.start()
        settle(1)
        val notification = notifications.vault.single()
        notifications.click(notification, "Don't Show Again for These")
        assertTrue(notification.isExpired)
        assertEquals(setOf("site|roles/web/files/ssl/db.key|PLAINTEXT_KEYS"), SecretNotificationMemory.getInstance().silenced(SecretNotifier.locationOf(project)))
        delete(file)
        settle(0)
        create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        settle(1)
        assertEquals("silenced for good", 1, notifications.vault.size)
    }

    fun testWarningsOnlyUseTheWarningsGroupAndOneKindOfFixIsOffered() {
        create("site/roles/web/files/ssl/old.key", TestKeys.protectedKey())
        monitor.start()
        settle(1)
        val warning = notifications.vault.single()
        assertEquals(SecretNotifier.WARNINGS_GROUP_ID, warning.groupId)
        assertEquals(NotificationType.WARNING, warning.type)

        create("site/roles/web/files/a.vault", wrapped())
        create("site/roles/web/files/b.vault", wrapped())
        settle(3)
        val convert = notifications.vault.last()
        assertEquals("site: 2 broken vault files (a.vault, b.vault)", convert.content)
        assertEquals(listOf("Show", "Convert to Whole-File Vault", "Don't Show Again for These"), VaultNotifications.actions(convert))
    }

    fun testShowSelectsTheVaultTabAndTheNewFindingsWithoutOpeningAFile() {
        val manager = ToolWindowManager.getInstance(project) as ToolWindowHeadlessManagerImpl
        val window = manager.doRegisterToolWindow(AnsibleToolWindowFactory.ID)
        Disposer.register(testRootDisposable, Disposable { manager.unregisterToolWindow(AnsibleToolWindowFactory.ID) })
        val other = ContentFactory.getInstance().createContent(JPanel(), "Repos", false)
        window.contentManager.addContent(other)
        val tab = SecretTab.addTo(project, window)
        window.contentManager.setSelectedContent(other)
        create("site/roles/web/files/ssl/db.key", TestKeys.plaintextKey())
        create("site/roles/web/files/ssl/other.key", TestKeys.plaintextKey())
        monitor.start()
        settle(2)
        val panel = tab.component as SecretHealthPanel
        PlatformTestUtil.waitWithEventsDispatching("the tab shows the snapshot", { panel.shown.findings.size == 2 }, 30)

        notifications.click(notifications.vault.single(), "Show")
        assertSame(tab, window.contentManager.selectedContent)
        assertEquals(listOf("roles/web/files/ssl/db.key", "roles/web/files/ssl/other.key"), panel.selectedFiles().map { it.path })
        assertEmpty("no file is opened", com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.toList())
        assertFalse("an invisible window is not in view", SecretTab.isInView(project))
    }
}
