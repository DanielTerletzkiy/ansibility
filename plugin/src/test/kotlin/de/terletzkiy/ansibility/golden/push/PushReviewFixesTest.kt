package de.terletzkiy.ansibility.golden.push

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.RoleFiles
import de.terletzkiy.ansibility.golden.sync.WriteResult
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftOptions
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.model.role.RoleCopy
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * The review fixes of Push (plan amendment R24, review S1, S2, S4, S8, S10, S11, U4, U5, U12, P5) on real files: a
 * linked copy is never offered or written, every push names its own commands, what the dialog showed is pushed or
 * nothing, the disk is read again first, errors are told, `molecule/` is always mirrored, an Edit › Undo of one repo is
 * noticed and the rest can be undone, badges only from golden, and each source file is read once.
 */
class PushReviewFixesTest : GoldenLocalTestCase() {
    private lateinit var ui: RecordingPushUi

    override fun setUp() {
        super.setUp()
        ui = RecordingPushUi()
        PushTestSupport.install(ui, testRootDisposable)
    }

    override fun tearDown() {
        try {
            PushService.getInstance(project).hookForTests = null
            FileEditorManager.getInstance(project).openFiles.forEach(FileEditorManager.getInstance(project)::closeFile)
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun golden(): RoleCopy = roleCopy("golden")

    private fun rows(source: RoleCopy = golden()): List<PushRow> = PushTestSupport.rows(project, source)

    private fun execute(rows: List<PushRow>, vararg names: String, options: PushOptions = PushOptions(), source: RoleCopy = golden()): PushOutcome =
        GoldenTestSupport.await { PushService.getInstance(project).execute(source, PushChoice(PushTestSupport.byName(rows, *names), options)) }

    private fun push(vararg names: String, options: PushOptions = PushOptions()): PushOutcome = execute(rows(), *names, options = options)

    private fun plan(source: String, target: String): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, roleDir(source), roleDir(target), PlanOptions()) }

    private fun snapshot(team: String): Map<String, String> {
        GoldenTestSupport.fsync()
        val dir = base.resolve(DriftFixture.roleDir(team)).toRealPath()
        return Files.walk(dir).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate { it.relativeTo(dir).toString() to Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }
        }
    }

    private fun clickUndo(notification: com.intellij.notification.Notification, text: String = "Undo") {
        TestDialogManager.setTestDialog { Messages.OK }
        try {
            PushTestSupport.click(project, notification, text)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    // ------------------------------------------------------------------ S1: linked copies

    fun testACopyLinkedToGoldenIsShownLinkedAndNeverWritten() {
        val mol = base.resolve(DriftFixture.roleDir("mol"))
        Files.walk(mol).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        Files.createSymbolicLink(mol, base.resolve(DriftFixture.roleDir("golden")))
        refresh()
        val goldenFiles = snapshot("golden")
        writeOnDisk("tasks", "files/extra.txt", "only in tasks\n".toByteArray())

        // Pushing tasks' copy: mol (a link to golden) must not be written, or golden would be.
        val rows = rows(roleCopy("tasks"))
        val linked = rows.single { it.name == "mol" }
        assertEquals(base.resolve(DriftFixture.roleDir("golden")).toRealPath().toString(), linked.linkedTo)
        assertFalse(linked.pushable)
        assertFalse("never ticked by Select differing", linked.differs(PushOptions()))

        val outcome = execute(rows, "mol", "same", source = roleCopy("tasks"))
        assertEquals(outcome.toString(), PushOutcome.Status.NOT_WRITTEN, outcome.status)
        assertEquals(WriteResult.Status.INVALID_TARGET, outcome.results.getValue(roleDir("mol")).status)
        assertEquals("golden is untouched", goldenFiles, snapshot("golden"))
        assertTrue(ui.notices.single(), ui.notices.single().contains("is a link to"))

        val dialog = PushDialog(project, PushTestSupport.model(project, roleCopy("tasks"), rows))
        try {
            assertFalse("the row cannot be ticked", dialog.checkBoxes.getValue(linked).isEnabled)
            assertEquals("linked to ${linked.linkedTo}", dialog.linkedLabels.getValue(linked).text)
            dialog.selectDiffering()
            assertFalse(linked in dialog.tickedRows())
        } finally {
            dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    // ------------------------------------------------------------------ S2: one push's commands only

    fun testUndoOfAPushNeverUndoesAnEarlierPush() {
        val first = push("mol")
        writeOnDisk("mol", "tasks/main.yml", "---\n# edited after the first push\n".toByteArray())
        val second = push("mol")
        // Edit › Undo reverts the second push: the first push's command is on top now.
        TestDialogManager.setTestDialog { Messages.OK }
        try {
            UndoManager.getInstance(project).undo(null)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        val undone = snapshot("mol")

        assertEquals("the second push's Undo is no longer on top", PushUndo.Result.NOT_ON_TOP, second.undo!!.undo())
        clickUndo(second.notification!!)
        assertEquals("the first push stays: nothing was undone", undone, snapshot("mol"))
        assertTrue("its Undo did not run", ui.undoQuestions.isEmpty())
        assertTrue("each push names its commands", first.undo!!.commands.single() != second.undo.commands.single())
    }

    // ------------------------------------------------------------------ S4 and S10: what the dialog showed, from disk

    fun testASourceChangedSinceTheDialogWritesNothing() {
        val rows = rows()
        val mol = snapshot("mol")
        for (file in listOf("tasks/install.yml", "tasks/service.yml", "tasks/configure.yml")) Files.delete(path("golden", file))
        refresh()
        val outcome = execute(rows, "mol")
        assertEquals(outcome.toString(), PushOutcome.Status.SOURCE_CHANGED, outcome.status)
        assertEquals("nothing was deleted in mol", mol, snapshot("mol"))
        assertTrue(ui.notices.single(), ui.notices.single().startsWith("Files of web in golden changed since you opened the dialog (tasks/configure.yml, tasks/install.yml, tasks/service.yml)"))
    }

    fun testATargetChangedSinceTheDialogWritesNothing() {
        val rows = rows()
        writeOnDisk("same", "files/notes/new.txt", "new in same\n".toByteArray())
        val mol = snapshot("mol")
        val outcome = execute(rows, "mol", "same")
        assertEquals(outcome.toString(), PushOutcome.Status.NOT_WRITTEN, outcome.status)
        assertEquals(mol, snapshot("mol"))
        assertTrue(ui.notices.single(), ui.notices.single().contains("of web in same changed since you opened the dialog (files/notes/new.txt)"))
    }

    fun testTheDiskIsReadAgainBeforeWriting() {
        val verify = vf("${DriftFixture.roleDir("golden")}/molecule/default/verify.yml")
        verify.contentsToByteArray(true)
        val rows = rows()
        val mol = snapshot("mol")
        // A change on disk the VFS has not seen yet (a git checkout while the dialog is open).
        Files.writeString(path("golden", "molecule/default/verify.yml"), "---\n# changed on disk only\n")
        val outcome = execute(rows, "mol")
        assertEquals(outcome.toString(), PushOutcome.Status.SOURCE_CHANGED, outcome.status)
        assertEquals("nothing stale was written", mol, snapshot("mol"))
    }

    // ------------------------------------------------------------------ S8: a file never replaces a folder

    fun testAFileOverAFolderWritesNothingAnywhere() {
        writeOnDisk("golden", "files/conf", "a file in golden\n".toByteArray())
        writeOnDisk("tasks", "files/conf/a.yml", "---\n".toByteArray())
        val mol = snapshot("mol")
        val outcome = push("mol", "tasks")
        assertEquals(outcome.toString(), PushOutcome.Status.NOT_WRITTEN, outcome.status)
        assertEquals(WriteResult.Status.STALE, outcome.results.getValue(roleDir("tasks")).status)
        assertTrue(Files.exists(path("tasks", "files/conf/a.yml")))
        assertEquals(mol, snapshot("mol"))
    }

    // ------------------------------------------------------------------ S11: errors are told

    fun testAPushThatFailsSaysSo() {
        PushService.getInstance(project).hookForTests = object : PushService.Hook {
            override fun afterPlan(row: PushRow) = throw IllegalStateException("simulated")
        }
        ui.pick = { rows -> PushChoice(rows.filter { it.name == "mol" }, PushOptions()) }
        val deferred = PushService.getInstance(project).push(golden())!!
        GoldenTestSupport.waitFor("the push ends") { deferred.isCompleted }
        assertNull("the failure is handled, not left in the result", deferred.getCompletionExceptionOrNull())
        assertEquals(PushOutcome.Status.NOT_WRITTEN, GoldenTestSupport.await { deferred.await() }.status)
        assertTrue(ui.notices.toString(), ui.notices.single().startsWith("The push of web from golden stopped with an error"))
    }

    // ------------------------------------------------------------------ U4: molecule/ always

    fun testAPushMirrorsMoleculeWhateverTheDriftSetting() {
        val drift = RoleDriftService.getInstance(project)
        val before = drift.options
        drift.options = DriftOptions(ignoreMolecule = true)
        try {
            val row = rows().single { it.name == "mol" }
            assertEquals("the molecule-only difference is pushed", listOf("molecule/default/verify.yml"), row.plan.entries.map { it.relPath })
            assertEquals(PushOutcome.Status.PUSHED, execute(listOf(row), "mol").status)
            assertTrue(plan("golden", "mol").isIdentical)
        } finally {
            drift.options = before
        }
    }

    // ------------------------------------------------------------------ U5: an Edit › Undo of one repo

    fun testAnEditorUndoOfOneRepoSaysWhatIsStillPushedAndUndoesTheRest() {
        val mol = snapshot("mol")
        val tasks = snapshot("tasks")
        val outcome = push("mol", "tasks")
        val file = vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml")
        val editor = FileEditorManager.getInstance(project).openFile(file, true).filterIsInstance<TextEditor>().single()
        TestDialogManager.setTestDialog { Messages.OK }
        try {
            UndoManager.getInstance(project).undo(editor)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals(tasks, snapshot("tasks"))

        val partial = ui.notifications.last()
        assertNotSame(outcome.notification, partial)
        assertEquals("Undid the push to tasks only; mol is still pushed.", partial.content)
        assertEquals(listOf("Undo the Rest"), PushTestSupport.actions(partial))

        clickUndo(partial, "Undo the Rest")
        assertEquals("one question for the rest", listOf("Undo the push of web to mol?"), ui.undoQuestions)
        assertEquals("the rest is undone", mol, snapshot("mol"))
        assertTrue(partial.isExpired && outcome.notification!!.isExpired)
    }

    // ------------------------------------------------------------------ U12: badges only from golden

    fun testBadgesOnlyWhenTheSourceIsGolden() {
        assertTrue("from golden: badges", rows().any { it.badge != null })
        assertTrue("from another copy the counts are relative to it, so no golden badge", rows(roleCopy("mol")).all { it.badge == null })
    }

    // ------------------------------------------------------------------ P5: each source file once

    fun testEachSourceFileIsReadOncePerPush() {
        writeOnDisk("golden", "files/shared.txt", "golden's shared file\n".toByteArray())
        val rows = rows()
        val reads = RoleFiles.Counters.fullReads.get()
        val outcome = execute(rows, "mol", "mol2", "same")
        assertEquals(PushOutcome.Status.PUSHED, outcome.status)
        // mol and mol2: verify.yml and shared.txt; same: shared.txt. Two distinct source files, each read once.
        assertEquals(2, RoleFiles.Counters.fullReads.get() - reads)
        assertTrue(plan("golden", "mol2").isIdentical)
    }
}
