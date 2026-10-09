package de.terletzkiy.ansibility.golden.editor

import com.intellij.diff.chains.DiffRequestChain
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.RecordingDiffUi
import de.terletzkiy.ansibility.golden.align.AlignTestSupport
import de.terletzkiy.ansibility.golden.align.ScriptedMergeDialog
import de.terletzkiy.ansibility.golden.take.RecordingTakeUi
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftListener
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.GoldenRoot
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * X120 (plan amendment R24): the editor banner on a role file that differs from golden. When it shows (changed, only
 * here; never golden, identical files, roles without a golden copy, no golden root, detached roots, unknown drift),
 * its texts ("differs from golden", never "outdated"), key and vault files (content not shown, no Take), its links
 * (Compare, Take Golden's Version, Align with Golden…, Hide until the file changes: an action's link carries its menu
 * text), its refresh on drift and settings changes, and that it computes its own role's drift only.
 */
class GoldenBannerTest : GoldenLocalTestCase() {
    private lateinit var drift: RoleDriftService

    override fun setUp() {
        super.setUp()
        drift = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, drift, testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).openFiles.forEach(FileEditorManager.getInstance(project)::closeFile)
            GoldenBannerState.getInstance(project).loadState(GoldenBannerState.StateBean())
            val settings = AnsibilityProjectSettings.getInstance(project)
            settings.update { it.copy(drift = it.drift.copy(ignoreMolecule = false)) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun file(team: String, relative: String, role: String = "web"): VirtualFile = vf("${DriftFixture.roleDir(team, role)}/$relative")

    /** The drift of web, computed now (cached afterwards). */
    private fun known() {
        DriftFixture.drift(drift, "web")
    }

    private fun banner(team: String, relative: String, role: String = "web"): GoldenBanner? =
        runReadActionBlocking { GoldenBanners.of(project, file(team, relative, role)) }

    /** The panel the provider shows on [team]'s [relative] (null: no banner), with the file open in an editor. */
    private fun panel(team: String, relative: String): EditorNotificationPanel? {
        val file = file(team, relative)
        val data = runReadActionBlocking { GoldenBannerProvider().collectNotificationData(project, file) } ?: return null
        val editor = FileEditorManager.getInstance(project).openFile(file, false).first()
        return data.apply(editor) as EditorNotificationPanel
    }

    private fun links(panel: EditorNotificationPanel): List<String> =
        listOf("Compare", TAKE, ALIGN, "Hide").filter { panel.findLabelByName(it) != null }

    // ------------------------------------------------------------------ when it shows

    fun testNothingShowsUntilTheDriftIsKnownAndTheRoleIsRequested() {
        assertNull("nothing cached yet", banner("tasks", "tasks/main.yml"))
        GoldenTestSupport.waitFor("the requested drift of web") { drift.cached("web") != null }
        assertEquals("Differs from golden (golden/roles/web/tasks/main.yml)", banner("tasks", "tasks/main.yml")!!.text)
    }

    fun testOnAChangedFileAndAFileOnlyThisCopyHas() {
        writeOnDisk("tasks", "files/notes/extra.txt", "only in tasks\n".toByteArray())
        known()
        val changed = banner("tasks", "tasks/main.yml")!!
        assertEquals("Differs from golden (golden/roles/web/tasks/main.yml)", changed.text)
        assertFalse(changed.onlyHere)
        val onlyHere = banner("tasks", "files/notes/extra.txt")!!
        assertEquals("Only in this copy; golden has no such file", onlyHere.text)
        assertEquals("Differs from golden (golden/roles/web/molecule/default/verify.yml)", banner("mol", "molecule/default/verify.yml")!!.text)
        for (text in listOf(changed.text, onlyHere.text)) assertFalse(text, text.contains("outdated", ignoreCase = true))
    }

    fun testNotOnGoldenIdenticalFilesRolesWithoutAGoldenCopyOrWithoutAGoldenRoot() {
        known()
        DriftFixture.drift(drift, "solo")
        assertNull("golden itself", banner("golden", "tasks/main.yml"))
        assertNull("an identical file of a differing copy", banner("tasks", "tasks/install.yml"))
        assertNull("an identical copy", banner("same", "tasks/main.yml"))
        assertNull("solo has no golden copy", banner("same", "tasks/main.yml", "solo"))
        assertNull("not in a role", runReadActionBlocking { GoldenBanners.of(project, vf("repos/tasks/ansible/ansible.cfg")) })
        assertNull("a folder", runReadActionBlocking { GoldenBanners.of(project, file("tasks", "tasks")) })

        DriftFixture.useGolden(project, testRootDisposable, GoldenRoot.None)
        assertNull("no golden root", banner("tasks", "tasks/main.yml"))
    }

    fun testNotInADetachedRoot() {
        val worktree = "checkouts/.claude/worktrees/wt-banner/repos/tasks/ansible"
        Files.createDirectories(base.resolve("$worktree/roles/web/tasks"))
        Files.writeString(base.resolve("$worktree/ansible.cfg"), "[defaults]\n")
        Files.writeString(base.resolve("$worktree/roles/web/tasks/main.yml"), "---\n# a worktree edit\n")
        refresh()
        val detached = vf("$worktree/roles/web/tasks/main.yml")
        assertTrue("the fixture root is detached", runReadActionBlocking { AnsibleWorkspace.getInstance(project).rootFor(detached) }!!.detached)
        known()
        assertNull(runReadActionBlocking { GoldenBanners.of(project, detached) })
    }

    fun testKeyAndVaultFilesSayContentNotShownAndOfferNoTake() {
        writeOnDisk("golden", "files/ssl/web.key", "synthetic key material A\n".toByteArray())
        writeOnDisk("tasks", "files/ssl/web.key", "synthetic key material B\n".toByteArray())
        writeOnDisk("golden", "vars/secrets.yml", DriftFixture.syntheticVault("golden").toByteArray())
        writeOnDisk("tasks", "vars/secrets.yml", DriftFixture.syntheticVault("tasks").toByteArray())
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        known()
        for (relative in listOf("files/ssl/web.key", "vars/secrets.yml")) {
            val panel = panel("tasks", relative)!!
            assertEquals(relative, "Differs from golden (content not shown)", panel.text)
            assertEquals(relative, listOf("Compare", ALIGN, "Hide"), links(panel))
        }
        val ui = RecordingDiffUi()
        GoldenTestSupport.install(ui, testRootDisposable)
        panel("tasks", "files/ssl/web.key")!!.findLabelByName("Compare")!!.doClick()
        GoldenTestSupport.waitFor("the compare chain") { ui.chains.isNotEmpty() }
        val chain = ui.chains.single()
        assertEquals("files/ssl/web.key", chain.requests[chain.index].name)
        assertTrue("Compare opens the placeholder", GoldenTestSupport.process(chain.requests[chain.index]) is com.intellij.diff.requests.MessageDiffRequest)
        assertEquals("nothing is decrypted", 0, guard.calls.get())
    }

    // ------------------------------------------------------------------ links

    fun testCompareTakeAndAlignLinks() {
        known()
        val ui = RecordingDiffUi()
        GoldenTestSupport.install(ui, testRootDisposable)
        val takes = RecordingTakeUi.install(testRootDisposable)
        val dialog = ScriptedMergeDialog()
        AlignTestSupport.install(listOf(dialog), testRootDisposable)

        val panel = panel("tasks", "tasks/main.yml")!!
        assertEquals(listOf("Compare", TAKE, ALIGN, "Hide"), links(panel))

        panel.findLabelByName("Compare")!!.doClick()
        GoldenTestSupport.waitFor("the compare chain") { ui.chains.isNotEmpty() }
        val chain: DiffRequestChain = ui.chains.single()
        assertEquals("starts at this file", "tasks/main.yml", chain.requests[chain.index].name)

        panel.findLabelByName(ALIGN)!!.doClick()
        GoldenTestSupport.waitFor("the merge window") { dialog.sessions.isNotEmpty() }
        val session = dialog.sessions.single()
        assertEquals("this copy is the target", roleDir("tasks"), session.target.dir)
        assertEquals("golden is the source", roleDir("golden"), session.source.dir)

        panel.findLabelByName(TAKE)!!.doClick()
        GoldenTestSupport.waitFor("the take") { takes.notes.isNotEmpty() }
        assertEquals(String(bytes("golden", "tasks/main.yml")), String(bytes("tasks", "tasks/main.yml")))
        known()
        assertNull("the file no longer differs", banner("tasks", "tasks/main.yml"))
    }

    fun testHideLastsUntilTheFileChangesOnDisk() {
        known()
        val file = file("tasks", "tasks/main.yml")
        panel("tasks", "tasks/main.yml")!!.findLabelByName("Hide")!!.doClick()
        assertNull("hidden", banner("tasks", "tasks/main.yml"))
        assertNotNull("other files keep theirs", banner("mol", "molecule/default/verify.yml"))
        val state = GoldenBannerState.getInstance(project)
        assertTrue(state.isDismissed(file))
        val stored = state.state.dismissed.single()
        assertTrue("stamp and URL only: $stored", stored.endsWith(" ${file.url}"))

        val reloaded = GoldenBannerState()
        reloaded.loadState(state.state)
        assertTrue("kept in the workspace file", reloaded.isDismissed(file))

        writeOnDisk("tasks", "tasks/main.yml", "---\n# changed on disk, still not golden's\n".toByteArray())
        known()
        assertFalse(state.isDismissed(file))
        assertNotNull("shown again once the file changed", banner("tasks", "tasks/main.yml"))
    }

    fun testHiddenBannersAreBounded() {
        val state = GoldenBannerState()
        val bean = GoldenBannerState.StateBean()
        bean.dismissed = MutableList(GoldenBannerState.MAX_ENTRIES + 5) { "1:1 file:///x/$it" }
        state.loadState(bean)
        assertEquals(GoldenBannerState.MAX_ENTRIES, state.state.dismissed.size)
        assertEquals("the oldest go first", "1:1 file:///x/5", state.state.dismissed.first())
        state.dismiss(file("tasks", "tasks/main.yml"))
        assertEquals(GoldenBannerState.MAX_ENTRIES, state.state.dismissed.size)
        assertTrue(state.state.dismissed.last().endsWith(file("tasks", "tasks/main.yml").url))
    }

    fun testEachLinkThatRunsAnActionIsNamedLikeItsMenuItem() {
        known()
        val panel = panel("tasks", "tasks/main.yml")!!
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file("tasks", "tasks/main.yml")).build()
        // The Align link runs Align with Golden… (the copy into line with golden), the Take link Take Golden's Version.
        for (id in listOf(GoldenActionIds.ALIGN_WITH_GOLDEN, "Ansibility.Golden.TakeGoldens")) {
            val action = ActionManager.getInstance().getAction(id)
            val event = AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), ActionPlaces.EDITOR_POPUP, ActionUiKind.POPUP, null)
            runReadActionBlocking { action.update(event) }
            val menuText = event.presentation.text
            assertNotNull("$id: the banner has a link named like its menu item '$menuText'", panel.findLabelByName(menuText))
        }
        assertEquals(listOf("Compare", "Take Golden's Version", "Align with Golden…", "Hide"), links(panel))
        assertNull("never the name of the opposite action, Align Role…", panel.findLabelByName("Align Role…"))
    }

    // ------------------------------------------------------------------ what it computes (review fix P1/U6)

    fun testTheBannerComputesItsOwnRoleOnlyAndKeepsItFresh() {
        val webCopies = DriftFixture.WEB_REPOS.size + 1
        assertNull("nothing cached yet", banner("tasks", "tasks/main.yml"))
        GoldenTestSupport.waitFor("the drift of the banner's role") { drift.cached("web") != null }
        assertNotNull(banner("tasks", "tasks/main.yml"))
        // Give a worker that went on to every other name the time to do so.
        val until = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < until && listOf("base", "solo", "app-same").none(drift::isComputed)) {
            Thread.sleep(50)
        }
        assertFalse("no other role name is computed", listOf("base", "solo", "app-same").any(drift::isComputed))
        assertEquals("only web's copies were walked", webCopies.toLong(), drift.counters.copiesWalked)

        Files.writeString(base.resolve("${DriftFixture.roleDir("same", "base")}/tasks/main.yml"), "---\n# base, edited on disk\n")
        writeOnDisk("tasks", "tasks/main.yml", bytes("golden", "tasks/main.yml"))
        GoldenTestSupport.waitFor("an edit of web's copy is followed without asking") { drift.cached("web")?.copyOf(roleDir("tasks"))?.tier?.differs == false }
        val walks = drift.counters.copiesWalked
        assertTrue("web was computed again, and only web: $walks walks", walks >= 2L * webCopies && walks % webCopies == 0L)
        assertFalse("an edit of a role nobody asked for computes nothing", drift.isComputed("base"))
        assertNull("the file no longer differs", banner("tasks", "tasks/main.yml"))
    }

    // ------------------------------------------------------------------ refresh and registration

    fun testTheRefresherUpdatesTheOpenFilesOfTheRoleAndEveryBannerOnADriftSettingChange() {
        val notifications = CountingNotifications()
        project.replaceService(EditorNotifications::class.java, notifications, testRootDisposable)
        val manager = FileEditorManager.getInstance(project)
        val web = listOf(file("tasks", "tasks/main.yml"), file("golden", "handlers/main.yml"))
        val other = listOf(file("same", "tasks/main.yml", "base"), vf("repos/tasks/ansible/ansible.cfg"))
        (web + other).forEach { manager.openFile(it, false) }
        notifications.files.clear()
        val allBefore = notifications.all

        project.messageBus.syncPublisher(RoleDriftListener.TOPIC).driftChanged("web")
        assertEquals("the open files of web's copies only", web.toSet(), notifications.files.toSet())

        notifications.files.clear()
        val settings = AnsibilityProjectSettings.getInstance(project)
        settings.update { it.copy(drift = it.drift.copy(ignoreMolecule = true)) }
        assertEquals("a drift setting updates every banner", allBefore + 1, notifications.all)
        val runTests = settings.settings.molecule.runTests
        settings.update { it.copy(molecule = it.molecule.copy(runTests = !runTests)) }
        assertEquals("other settings do not", allBefore + 1, notifications.all)
        settings.update { it.copy(molecule = it.molecule.copy(runTests = runTests)) }
    }

    fun testTheProviderIsRegistered() {
        assertTrue(EditorNotificationProvider.EP_NAME.getExtensions(project).any { it is GoldenBannerProvider })
    }

    private companion object {
        /** The links of Take Golden's Version and Align with Golden…, named like their menu items. */
        const val TAKE = "Take Golden's Version"
        const val ALIGN = "Align with Golden…"
    }

    /** Counts the refresh requests. */
    private class CountingNotifications : EditorNotifications() {
        val files = CopyOnWriteArrayList<VirtualFile>()

        @Volatile
        var all = 0

        override fun updateNotifications(file: VirtualFile) {
            files += file
        }

        @Suppress("OVERRIDE_DEPRECATION") // abstract in 262, so a subclass must implement it
        override fun updateNotifications(provider: EditorNotificationProvider) = Unit

        override fun updateAllNotifications() {
            all++
        }
    }
}
