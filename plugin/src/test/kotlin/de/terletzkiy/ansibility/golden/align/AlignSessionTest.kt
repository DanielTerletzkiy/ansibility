package de.terletzkiy.ansibility.golden.align

import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.ConflictType
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.history.LocalHistory
import com.intellij.notification.Notification
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.util.Consumer
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.sync.PlanKind
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.vcs.impl.AlignMergeCustomizer
import de.terletzkiy.ansibility.golden.vcs.impl.AlignMergeProvider
import de.terletzkiy.ansibility.golden.vcs.impl.AlignMergeSession
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.model.drift.DriftTier
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.run.molecule.MoleculeConfiguration
import de.terletzkiy.ansibility.run.molecule.MoleculeConfigurationType
import de.terletzkiy.ansibility.run.molecule.MoleculeLauncher
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import com.intellij.execution.RunManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * Align (plan amendment R24, D184–D187) on real files: the rows and their merge data, Accept Theirs and Accept Yours
 * through the Conflicts dialog's provider and session (as the dialog calls them), Merge…, whole-only rows, key and vault
 * files, the stale, read-only and changed-source guards, the Local History label, the summary notification, the
 * fallback window and the drift badge afterwards. The window is an extension, so a scripted one drives it.
 */
class AlignSessionTest : GoldenLocalTestCase() {
    private lateinit var ui: RecordingAlignUi
    private lateinit var notifications: MutableList<Notification>

    override fun setUp() {
        super.setUp()
        ui = RecordingAlignUi()
        AlignTestSupport.install(ui, testRootDisposable)
        notifications = AlignTestSupport.notifications(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            AlignFallbackDialog.showForTests = null
            AlignFallback.showMergeForTests = null
            MoleculeLauncher.executeForTests = null
            val runs = RunManager.getInstance(project)
            runs.getConfigurationSettingsList(MoleculeConfigurationType.getInstance()).forEach(runs::removeConfiguration)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun session(target: String, source: String, include: Boolean = false): AlignSession =
        GoldenTestSupport.await { AlignService.getInstance(project).session(AlignRequest(roleCopy(target), roleCopy(source), include)) }

    private fun plan(source: String, target: String): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, roleDir(source), roleDir(target), PlanOptions()) }

    /** Every file of [team]'s copy of web on disk, as path → Base64 of its bytes. */
    private fun snapshot(team: String): Map<String, String> {
        GoldenTestSupport.fsync()
        val dir = base.resolve(DriftFixture.roleDir(team))
        return Files.walk(dir).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate { it.relativeTo(dir).toString() to Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }
        }
    }

    private fun undoOnce() {
        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            val undo = UndoManager.getInstance(project)
            assertTrue("global undo is available", undo.isUndoAvailable(null))
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    private fun text(team: String, relative: String): String = String(bytes(team, relative), Charsets.UTF_8)

    /** Accept as the Conflicts dialog does: `acceptFilesRevisions` on a background thread, then `conflictResolvedForFiles`. */
    private fun accept(session: AlignSession, rows: List<AlignRow>, resolution: MergeSession.Resolution) {
        val merge = AlignMergeSession(session)
        val files = rows.map { it.file }
        GoldenTestSupport.pooled { merge.acceptFilesRevisions(files, resolution) }
        GoldenTestSupport.pooled { merge.conflictResolvedForFiles(files, resolution) }
    }

    /** The failure of an accept, as the dialog would show it (and then leave the files unresolved). */
    private fun acceptFailure(session: AlignSession, rows: List<AlignRow>): Throwable? = GoldenTestSupport.pooled {
        runCatching { AlignMergeSession(session).acceptFilesRevisions(rows.map { it.file }, MergeSession.Resolution.AcceptedTheirs) }.exceptionOrNull()
    }

    private fun load(session: AlignSession, row: AlignRow): MergeData = GoldenTestSupport.pooled { AlignMergeProvider(session).loadRevisions(row.file) }

    /**
     * Merge… as the Conflicts dialog builds it: the request from `loadRevisions` with the row's file as output; the
     * viewer's edit ([result]), then its callback (save the document, report Merged).
     */
    private fun merge(session: AlignSession, row: AlignRow, result: String) {
        val data = load(session, row)
        val request = DiffRequestFactory.getInstance().createMergeRequest(
            project, row.file, listOf(data.CURRENT, data.ORIGINAL, data.LAST), data.CONFLICT_TYPE, "merge", listOf("target", "result", "source"),
            Consumer { merged ->
                val document = FileDocumentManager.getInstance().getCachedDocument(row.file)!!
                ApplicationManager.getApplication().runWriteAction { FileDocumentManager.getInstance().saveDocument(document) }
                assertEquals(MergeResult.RESOLVED, merged)
                AlignMergeSession(session).conflictResolvedForFiles(listOf(row.file), MergeSession.Resolution.Merged)
            },
        ) as TextMergeRequest
        request.onAssigned(true)
        WriteCommandAction.runWriteCommandAction(project) { request.outputContent.document.setText(result) }
        request.applyResult(MergeResult.RESOLVED)
        request.onAssigned(false)
    }

    private fun row(session: AlignSession, relPath: String): AlignRow = session.row(relPath) ?: error("no row $relPath in ${session.rows}")

    private var scripted: ScriptedMergeDialog? = null

    /** Runs [session] through [AlignService.run] with a scripted window (installed once per test). */
    private fun run(session: AlignSession, script: (AlignSession) -> Unit): ScriptedMergeDialog {
        val dialog = scripted ?: ScriptedMergeDialog().also {
            AlignTestSupport.install(listOf(it), testRootDisposable)
            scripted = it
        }
        dialog.script = { _, shown -> script(shown) }
        AlignService.getInstance(project).run(session)
        return dialog
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)

    // ------------------------------------------------------------------ rows and merge data

    fun testRowsCarryThePlanAsMergeDataWithTheTargetAsBase() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val session = session("missing", "golden")
        assertEquals(13, session.rows.size)
        val provider = AlignMergeProvider(session)

        val changed = row(session, "tasks/main.yml")
        assertEquals("a changed text file is the real target file (Merge… writes its document)", vf("${DriftFixture.roleDir("missing")}/tasks/main.yml"), changed.file)
        val data = load(session, changed)
        assertEquals(text("missing", "tasks/main.yml"), String(data.CURRENT, Charsets.UTF_8))
        assertEquals(text("golden", "tasks/main.yml"), String(data.LAST, Charsets.UTF_8))
        assertTrue("D186: the target is the base", data.ORIGINAL.contentEquals(data.CURRENT))
        assertEquals(ConflictType.DEFAULT, data.CONFLICT_TYPE)
        assertFalse(provider.isBinary(changed.file))
        assertTrue(AlignMergeSession(session).canMerge(changed.file))

        val added = row(session, "molecule/default/verify.yml")
        assertEquals(PlanKind.ONLY_IN_SOURCE, added.kind)
        val placeholder = added.file as AlignPlaceholder
        assertFalse("never a file on disk", placeholder.isInLocalFileSystem)
        assertEquals("${roleDir("missing").path}/molecule/default/verify.yml", placeholder.path)
        assertEquals("the deepest existing directory is its parent", roleDir("missing"), placeholder.parent)
        val addedData = load(session, added)
        assertNotNull(addedData.CURRENT)
        assertEquals("a missing side is an empty array", 0, addedData.CURRENT.size)
        assertEquals(0, addedData.ORIGINAL.size)
        assertEquals(text("golden", "molecule/default/verify.yml"), String(addedData.LAST, Charsets.UTF_8))
        assertEquals(ConflictType.DELETED_MODIFIED, addedData.CONFLICT_TYPE)
        assertTrue("accepted whole only", provider.isBinary(added.file))
        assertFalse(AlignMergeSession(session).canMerge(added.file))

        val extra = row(session, "files/notes/extra.txt")
        assertEquals(PlanKind.ONLY_IN_TARGET, extra.kind)
        assertTrue("the dialog never writes the real file of a whole-only row", extra.file is AlignPlaceholder)
        val extraData = load(session, extra)
        assertEquals("only in missing\n", String(extraData.CURRENT, Charsets.UTF_8))
        assertTrue(extraData.ORIGINAL.contentEquals(extraData.CURRENT))
        assertEquals(0, extraData.LAST.size)
        assertEquals("theirs deleted", ConflictType.MODIFIED_DELETED, extraData.CONFLICT_TYPE)

        val columns = AlignMergeSession(session).mergeInfoColumns
        assertEquals(listOf("Target", "Source"), columns.map { it.name })
        @Suppress("UNCHECKED_CAST")
        fun cell(column: Int, row: AlignRow) = (columns[column] as com.intellij.util.ui.ColumnInfo<VirtualFile, String>).valueOf(row.file)
        assertEquals(listOf("Changed", "Changed"), listOf(cell(0, changed), cell(1, changed)))
        assertEquals(listOf("Missing", "Only in source"), listOf(cell(0, added), cell(1, added)))
        assertEquals(listOf("Only in target", "Missing"), listOf(cell(0, extra), cell(1, extra)))

        val customizer = AlignMergeCustomizer(session)
        assertEquals("Align web: missing ← golden", customizer.getMultipleFileDialogTitle())
        assertEquals("Target missing · source golden", customizer.getMultipleFileMergeDescription(session.rows.map { it.file }))
        assertEquals("missing (target)", customizer.getLeftPanelTitle(changed.file))
        assertEquals("Result", customizer.getCenterPanelTitle(changed.file))
        assertEquals("golden (source)", customizer.getRightPanelTitle(changed.file, null))
        assertEquals("web › tasks/main.yml: missing ← golden", customizer.getMergeWindowTitle(changed.file))
        assertEquals(listOf("Target", "Source"), customizer.getColumnNames())
    }

    // ------------------------------------------------------------------ accepting

    fun testAcceptTheirsWritesCreatesAndDeletesAsOneUndoableCommand() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val original = snapshot("missing")
        val session = session("missing", "golden")
        accept(session, session.rows, MergeSession.Resolution.AcceptedTheirs)

        assertTrue("missing is byte-identical to golden now", plan("golden", "missing").isIdentical)
        assertFalse(Files.exists(path("missing", "files/notes/extra.txt")))
        assertEquals("13 taken", 13, session.outcome.taken)
        assertEquals(0, session.outcome.open)
        val undoName = UndoManager.getInstance(project).getUndoActionNameAndDescription(null).first
        assertTrue("one undoable command: $undoName", undoName.contains("Align web in missing"))

        undoOnce()
        assertEquals("one undo restores the copy", original, snapshot("missing"))
    }

    /**
     * The Conflicts dialog calls `acceptFilesRevisions` from a coroutine under `runWithModalProgressBlocking` on the
     * EDT; the write then has to run on the EDT with that progress' modality (not "non-modal", which would wait for the
     * progress forever).
     */
    fun testAcceptRunsUnderTheDialogsModalProgress() {
        writeOnDisk("tasks", "files/extra.txt", "only in tasks\n".toByteArray())
        val session = session("tasks", "golden")
        val merge = AlignMergeSession(session)
        val files = session.rows.map { it.file }
        runWithModalProgressBlocking(project, "Resolving conflicts") {
            withContext(Dispatchers.Default) {
                merge.acceptFilesRevisions(files, MergeSession.Resolution.AcceptedTheirs)
                merge.conflictResolvedForFiles(files, MergeSession.Resolution.AcceptedTheirs)
            }
        }
        assertTrue(plan("golden", "tasks").isIdentical)
        assertEquals(2, session.outcome.taken)
    }

    fun testAcceptYoursWritesNothing() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val original = snapshot("missing")
        val session = session("missing", "golden")
        accept(session, session.rows, MergeSession.Resolution.AcceptedYours)
        assertEquals(original, snapshot("missing"))
        assertEquals(13, session.outcome.kept)
        assertEquals(0, session.outcome.taken)
    }

    // ------------------------------------------------------------------ Merge… and the revert point

    fun testTheMergePathWritesTheResultIntoTheTargetAndMarksItMergedAfterTheLabel() {
        val original = snapshot("tasks")
        val session = session("tasks", "golden")
        val merged = "---\n- name: Merged by hand\n  ansible.builtin.meta: noop\n"
        var labelBeforeWrite: String? = null
        run(session) { shown ->
            labelBeforeWrite = shown.labelId
            assertTrue("the revert point exists before the first write", GoldenTestSupport.await { LocalHistory.getInstance().isLabelValid(project, labelBeforeWrite!!) })
            assertEquals(original, snapshot("tasks"))
            merge(shown, row(shown, "tasks/main.yml"), merged)
        }
        assertEquals(merged, text("tasks", "tasks/main.yml"))
        assertEquals(AlignResolution.MERGED, session.resolution(row(session, "tasks/main.yml")))
        assertEquals(1, session.outcome.merged)

        GoldenTestSupport.await { LocalHistory.getInstance().revertToLabel(project, labelBeforeWrite!!, roleDir("tasks")) }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("reverting to the label restores the target", original, snapshot("tasks"))
    }

    // ------------------------------------------------------------------ whole-only rows, keys and vaults

    fun testBinaryAndKeyFilesAreAcceptedWholeOnlyAndNeverDecrypted() {
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        writeOnDisk("golden", "files/logo.png", png)
        writeOnDisk("tasks", "files/logo.png", png + byteArrayOf(1))
        writeOnDisk("golden", "files/ssl/web.key", "synthetic key material of golden\n".toByteArray())
        writeOnDisk("tasks", "files/ssl/web.key", "synthetic key material of tasks\n".toByteArray())
        writeOnDisk("golden", "vars/vault.yml", DriftFixture.syntheticVault("golden marker").toByteArray())
        writeOnDisk("tasks", "vars/vault.yml", DriftFixture.syntheticVault("tasks marker").toByteArray())

        val session = session("tasks", "golden", include = true)
        val provider = AlignMergeProvider(session)
        for (relPath in listOf("files/logo.png", "files/ssl/web.key", "vars/vault.yml")) {
            val row = row(session, relPath)
            assertTrue(relPath, row.acceptWholeOnly)
            assertTrue("$relPath is accepted whole", provider.isBinary(row.file))
            assertFalse("$relPath has no Merge…", AlignMergeSession(session).canMerge(row.file))
            assertTrue("$relPath: the dialog never writes the real file", row.file is AlignPlaceholder)
        }
        for (relPath in listOf("files/ssl/web.key", "vars/vault.yml")) {
            val data = load(session, row(session, relPath))
            assertEquals("$relPath: content never read for a viewer", listOf(0, 0, 0), listOf(data.CURRENT.size, data.ORIGINAL.size, data.LAST.size))
            assertTrue(AlignMergeSession(session).mergeInfoColumns.isNotEmpty())
        }
        assertEquals("Changed (binary)", session.texts.targetStatus(row(session, "files/logo.png")))
        assertEquals("Changed · content not shown", session.texts.sourceStatus(row(session, "files/ssl/web.key")))

        accept(session, session.rows, MergeSession.Resolution.AcceptedTheirs)
        assertTrue(plan("golden", "tasks").isIdentical)
        assertEquals(0, guard.calls.get())
        assertEquals("nothing is decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    fun testKeyAndVaultFilesAreLeftOutByDefault() {
        writeOnDisk("golden", "files/ssl/web.key", "synthetic key material of golden\n".toByteArray())
        writeOnDisk("tasks", "files/ssl/web.key", "synthetic key material of tasks\n".toByteArray())
        val session = session("tasks", "golden")
        assertEquals(listOf("tasks/main.yml"), session.rows.map { it.relPath })
        assertEquals("Target tasks · source golden · 1 key/vault file left out", session.texts.description)
        accept(session, session.rows, MergeSession.Resolution.AcceptedTheirs)
        assertEquals("the key file stays", "synthetic key material of tasks\n", text("tasks", "files/ssl/web.key"))
    }

    fun testAMergeWindowThatTookTheSourceOfAWholeOnlyFileWritesTheRealFile() {
        writeOnDisk("golden", "files/logo.png", png)
        writeOnDisk("tasks", "files/logo.png", png + byteArrayOf(1))
        writeOnDisk("tasks", "files/extra.txt", "only in tasks\n".toByteArray())
        val session = session("tasks", "golden")
        // A double click opens the platform's binary merge viewer; "Accept Right" writes the source side into the
        // row's file (the placeholder, in memory) and reports Accepted Theirs without acceptFilesRevisions.
        for (relPath in listOf("files/logo.png", "files/extra.txt")) {
            val row = row(session, relPath)
            val data = load(session, row)
            val request: MergeRequest = DiffRequestFactory.getInstance().createBinaryMergeRequest(
                project, row.file, listOf(data.CURRENT, data.ORIGINAL, data.LAST), "merge", listOf("a", "b", "c"),
                Consumer { AlignMergeSession(session).conflictResolvedForFiles(listOf(row.file), MergeSession.Resolution.AcceptedTheirs) },
            )
            request.applyResult(MergeResult.RIGHT)
        }
        assertTrue("the binary file is golden's", bytes("tasks", "files/logo.png").contentEquals(png))
        assertFalse("the file only the target had is deleted", Files.exists(path("tasks", "files/extra.txt")))
        assertEquals(2, session.outcome.taken)
    }

    // ------------------------------------------------------------------ guards

    fun testAStaleTargetLeavesTheRowOpenWithAMessage() {
        val session = session("tasks", "golden")
        writeOnDisk("tasks", "tasks/main.yml", "---\n# edited elsewhere\n".toByteArray())
        val row = row(session, "tasks/main.yml")
        val failure = acceptFailure(session, listOf(row))
        assertTrue("$failure", failure is VcsException)
        assertTrue(failure!!.message!!, failure.message!!.contains("tasks/main.yml"))
        assertEquals("---\n# edited elsewhere\n", text("tasks", "tasks/main.yml"))
        assertNull(session.resolution(row))
        assertEquals(1, session.outcome.open)
        val load = GoldenTestSupport.pooled { runCatching { AlignMergeProvider(session).loadRevisions(row.file) }.exceptionOrNull() }
        assertTrue("Merge… stops too: $load", load is VcsException && load.message!!.contains("since the comparison"))
    }

    fun testAReadOnlyTargetLeavesTheRowOpenWithAMessage() {
        val session = session("tasks", "golden")
        Files.setPosixFilePermissions(path("tasks", "tasks/main.yml"), PosixFilePermissions.fromString("r--r--r--"))
        refresh()
        try {
            val before = text("tasks", "tasks/main.yml")
            val row = row(session, "tasks/main.yml")
            val failure = acceptFailure(session, listOf(row))
            assertTrue("$failure", failure is VcsException && failure.message!!.contains("read-only"))
            assertEquals(before, text("tasks", "tasks/main.yml"))
            assertNull(session.resolution(row))
        } finally {
            Files.setPosixFilePermissions(path("tasks", "tasks/main.yml"), PosixFilePermissions.fromString("rw-r--r--"))
            refresh()
        }
    }

    /**
     * A merge viewer that was closed with Cancel puts the target's text back into its document; the row must still be
     * acceptable afterwards (the target holds what the viewer was given), while a real edit stays stale.
     */
    fun testACancelledMergeViewerLeavesTheRowAcceptable() {
        val session = session("tasks", "golden")
        val row = row(session, "tasks/main.yml")
        val data = load(session, row)
        val request = DiffRequestFactory.getInstance().createMergeRequest(
            project, row.file, listOf(data.CURRENT, data.ORIGINAL, data.LAST), data.CONFLICT_TYPE, "merge", listOf("a", "b", "c"),
        ) as TextMergeRequest
        request.onAssigned(true)
        WriteCommandAction.runWriteCommandAction(project) { request.outputContent.document.setText("---\n# half merged\n") }
        request.applyResult(MergeResult.CANCEL)
        request.onAssigned(false)
        val document = FileDocumentManager.getInstance().getDocument(row.file)!!
        assertEquals("Cancel put the text back", String(data.CURRENT, Charsets.UTF_8), document.text)
        assertTrue("the document is left unsaved: its stamp is not the plan's", FileDocumentManager.getInstance().isDocumentUnsaved(document))

        accept(session, listOf(row), MergeSession.Resolution.AcceptedTheirs)
        assertEquals(text("golden", "tasks/main.yml"), text("tasks", "tasks/main.yml"))
        assertEquals(AlignResolution.TAKEN_FROM_SOURCE, session.resolution(row))
    }

    fun testAnEditAfterTheViewerReadTheRowIsStale() {
        val session = session("tasks", "golden")
        val row = row(session, "tasks/main.yml")
        load(session, row)
        writeOnDisk("tasks", "tasks/main.yml", "---\n# edited elsewhere\n".toByteArray())
        val failure = acceptFailure(session, listOf(row))
        assertTrue("$failure", failure is VcsException && failure.message!!.contains("tasks/main.yml"))
        assertEquals("---\n# edited elsewhere\n", text("tasks", "tasks/main.yml"))
    }

    fun testAChangedSourceStopsAcceptAndMerge() {
        val session = session("tasks", "golden")
        writeOnDisk("golden", "tasks/main.yml", "---\n# golden edited\n".toByteArray())
        val row = row(session, "tasks/main.yml")
        val before = text("tasks", "tasks/main.yml")
        val failure = acceptFailure(session, listOf(row))
        assertTrue("$failure", failure is VcsException && failure.message!!.contains("golden"))
        assertEquals(before, text("tasks", "tasks/main.yml"))
        val load = GoldenTestSupport.pooled { runCatching { AlignMergeProvider(session).loadRevisions(row.file) }.exceptionOrNull() }
        assertTrue("$load", load is VcsException)
    }

    /** D187 (U11): unsaved changes in the source stop Align before the window opens ("save or discard them first"). */
    fun testUnsavedSourceChangesStopTheAlignment() {
        val document = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("golden")}/tasks/main.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# typed in golden\n") }
        try {
            val before = snapshot("tasks")
            val dialog = ScriptedMergeDialog { _, shown -> accept(shown, shown.rows, MergeSession.Resolution.AcceptedTheirs) }
            AlignTestSupport.install(listOf(dialog), testRootDisposable)
            val job = AlignService.getInstance(project).align(AlignRequest(roleCopy("tasks"), roleCopy("golden")))
            GoldenTestSupport.waitFor("the alignment ends") { job.isCompleted }
            assertEquals("no window", 0, dialog.sessions.size)
            assertEquals(
                listOf("Save or discard the unsaved changes in golden first (tasks/main.yml). Align merges what is on disk; nothing was opened."),
                ui.notices,
            )
            assertEquals("nothing taken", before, snapshot("tasks"))
            assertFalse(text("tasks", "tasks/main.yml").startsWith("# typed in golden"))
        } finally {
            FileDocumentManager.getInstance().reloadFromDisk(document)
        }
    }

    // ------------------------------------------------------------------ the end of the window

    fun testTheSummaryNotificationCountsAndOffersPushMoleculeAndLocalHistory() {
        writeOnDisk("tasks", "files/extra.txt", "only in tasks\n".toByteArray())
        writeOnDisk("tasks", "files/extra2.txt", "only in tasks too\n".toByteArray())
        writeOnDisk("golden", "files/new.txt", "only in golden\n".toByteArray())
        val pushed = CopyOnWriteArrayList<VirtualFile>()
        val actions = ActionManager.getInstance()
        // Restored afterwards in its popups, so the popup-order tests that run later still see it.
        GoldenTestSupport.unregisterUntil(GoldenActionIds.PUSH_TO_REPOS, testRootDisposable)
        val launched = CopyOnWriteArrayList<String>()
        MoleculeLauncher.executeForTests = { settings, _ -> launched += (settings.configuration as MoleculeConfiguration).spec.roleDir }

        // Without a Push action: no Push button. (A merge that keeps the target's text changes no file.)
        val first = session("tasks", "golden")
        run(first) { shown ->
            merge(shown, row(shown, "tasks/main.yml"), text("tasks", "tasks/main.yml"))
            accept(shown, listOf(row(shown, "files/new.txt")), MergeSession.Resolution.AcceptedYours)
        }
        val withoutPush = notifications.last()
        assertEquals("Aligned web in tasks: 1 merged, 1 kept, 2 left open", withoutPush.content)
        assertEquals(listOf("Run Molecule Tests", "Show Local History"), AlignTestSupport.buttons(withoutPush))

        actions.registerAction(GoldenActionIds.PUSH_TO_REPOS, object : DumbAwareAction("Push") {
            override fun actionPerformed(e: AnActionEvent) {
                pushed += e.getData(GoldenDataKeys.ROLE_COPY)!!
            }
        })
        val session = session("tasks", "golden")
        run(session) { shown ->
            merge(shown, row(shown, "tasks/main.yml"), "---\n- name: Merged\n  ansible.builtin.meta: noop\n")
            accept(shown, listOf(row(shown, "files/extra.txt"), row(shown, "files/new.txt")), MergeSession.Resolution.AcceptedTheirs)
            accept(shown, listOf(row(shown, "files/extra2.txt")), MergeSession.Resolution.AcceptedYours)
        }
        val summary = notifications.last()
        assertEquals("Ansibility Golden", summary.groupId)
        assertEquals("Aligned web in tasks: 1 merged, 2 taken from golden, 1 kept", summary.content)
        assertEquals(listOf("Push Role to Repos…", "Run Molecule Tests", "Show Local History"), AlignTestSupport.buttons(summary))

        AlignTestSupport.click(project, summary, "Push Role to Repos…")
        assertEquals("Push gets the aligned copy", listOf(roleDir("tasks")), pushed)
        AlignTestSupport.click(project, summary, "Run Molecule Tests")
        assertEquals(listOf(roleDir("tasks").path), launched)
        val history = AlignNotifier.historyContext(project, roleDir("tasks"))
        assertEquals(roleDir("tasks"), history.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE))

        // U8: a window closed without merging or taking anything aligned nothing and offers nothing to follow up.
        val missing = session("missing", "golden")
        run(missing) { }
        assertEquals("Nothing aligned in web (missing)", notifications.last().content)
        assertEquals(emptyList<String>(), AlignTestSupport.buttons(notifications.last()))
        val kept = session("missing", "golden")
        run(kept) { shown -> accept(shown, shown.rows, MergeSession.Resolution.AcceptedYours) }
        assertEquals("only kept: nothing aligned either", "Nothing aligned in web (missing)", notifications.last().content)
        assertEquals(emptyList<String>(), AlignTestSupport.buttons(notifications.last()))
    }

    fun testAnIdenticalPairOnlyInforms() {
        val dialog = ScriptedMergeDialog()
        AlignTestSupport.install(listOf(dialog), testRootDisposable)
        val job = AlignService.getInstance(project).align(AlignRequest(roleCopy("same"), roleCopy("golden")))
        GoldenTestSupport.waitFor("the notice") { job.isCompleted }
        assertEquals(listOf("web in same is the same as in golden"), ui.notices)
        assertEquals("no window", 0, dialog.sessions.size)
    }

    fun testTheFallbackWithoutTheVcsWindow() {
        AlignTestSupport.install(emptyList(), testRootDisposable)
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val session = session("missing", "golden")
        val merged = "---\nweb_port: 8081\n"
        AlignFallback.showMergeForTests = { request ->
            val text = request as TextMergeRequest
            text.onAssigned(true)
            WriteCommandAction.runWriteCommandAction(project) { text.outputContent.document.setText(merged) }
            request.applyResult(MergeResult.RESOLVED)
            text.onAssigned(false)
        }
        var shown: AlignFallback? = null
        AlignFallbackDialog.showForTests = { fallback ->
            shown = fallback
            assertEquals(13, fallback.rows.size)
            fallback.merge(fallback.rows.first { it.relPath == "defaults/main.yml" })
            fallback.takeSource(fallback.rows.filter { it.kind == PlanKind.ONLY_IN_SOURCE })
            // Merge… is offered for changed text files only: nothing happens for a file only the target has.
            val extra = fallback.rows.first { it.relPath == "files/notes/extra.txt" }
            fallback.merge(extra)
            assertNull(fallback.session.resolution(extra))
            fallback.keepTarget(listOf(extra))
        }
        AlignService.getInstance(project).run(session)
        assertNotNull("the fallback is used without a window extension", shown)
        assertEquals(merged, text("missing", "defaults/main.yml"))
        assertTrue(Files.exists(path("missing", "molecule/default/verify.yml")))
        assertTrue(Files.exists(path("missing", "files/notes/extra.txt")))
        assertEquals("Aligned web in missing: 1 merged, 9 taken from golden, 1 kept, 2 left open", notifications.last().content)
        assertEquals(listOf("handlers/main.yml", "tasks/main.yml"), session.openRows.map { it.relPath })
    }

    fun testTheDriftBadgeTurnsGoldenAfterAligningEverything() {
        val drift = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, drift, testRootDisposable)
        val before = DriftFixture.drift(drift, "web")
        val tasksBefore = before.copyOf(roleDir("tasks"))!!
        assertEquals(DriftTier.BEHAVIOUR, tasksBefore.tier)
        assertEquals("Δ tasks/templates", DriftTexts.badge(before, tasksBefore))

        val session = session("tasks", "golden")
        run(session) { shown -> merge(shown, row(shown, "tasks/main.yml"), text("golden", "tasks/main.yml")) }

        GoldenTestSupport.waitFor("drift sees the aligned copy") {
            DriftFixture.drift(drift, "web").copyOf(roleDir("tasks"))?.tier == DriftTier.IDENTICAL
        }
        val after = DriftFixture.drift(drift, "web")
        assertEquals("= golden", DriftTexts.badge(after, after.copyOf(roleDir("tasks"))!!))
    }
}
