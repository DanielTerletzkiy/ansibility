package de.terletzkiy.ansibility.golden.take

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import de.terletzkiy.ansibility.golden.GoldenDataKeys
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.sync.PlanOptions
import de.terletzkiy.ansibility.golden.sync.RoleFilePlan
import de.terletzkiy.ansibility.golden.sync.WriteResult
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * The single-file takes (plan amendment R24, X121) on real files: exactly one path written, created or deleted, in
 * both directions, as ONE undoable command after a Local History label; the delete question; key and vault files only
 * after a confirmation and never decrypted; binary files with a "cannot be undone" note; unsaved, read-only and
 * identical files; and the actions from the editor and from a "Differences from golden" row.
 */
class TakeServiceTest : GoldenLocalTestCase() {
    private lateinit var ui: RecordingTakeUi

    override fun setUp() {
        super.setUp()
        ui = RecordingTakeUi.install(testRootDisposable)
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun take(team: String, relPath: String, direction: TakeDirection = TakeDirection.FROM_GOLDEN): TakeOutcome =
        GoldenTestSupport.await { TakeService.getInstance(project).take(GoldenTarget(roleCopy(team), relPath), direction) }

    private fun plan(source: String, target: String): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, roleDir(source), roleDir(target), PlanOptions(includeSensitive = true)) }

    /** Every file of [team]'s copy of web on disk, as path → Base64 of its bytes. */
    private fun snapshot(team: String): Map<String, String> {
        GoldenTestSupport.fsync()
        val dir = base.resolve(DriftFixture.roleDir(team))
        return Files.walk(dir).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate { it.relativeTo(dir).toString() to Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }
        }
    }

    private fun text(team: String, relative: String): String = String(bytes(team, relative), Charsets.UTF_8)

    /** Edit › Undo once (the platform asks before a global command; the test answers OK). */
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

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)

    // ------------------------------------------------------------------ Take Golden's Version

    fun testTakeGoldensReplacesOneFileAsOneUndoableCommand() {
        val before = snapshot("tasks")
        val outcome = take("tasks", "tasks/main.yml")
        assertEquals(outcome.toString(), TakeOutcome.Status.TAKEN, outcome.status)
        assertEquals(text("golden", "tasks/main.yml"), text("tasks", "tasks/main.yml"))
        assertTrue("tasks only differed there", plan("golden", "tasks").isIdentical)
        val result = outcome.result!!
        assertEquals(listOf("tasks/main.yml"), result.written)
        assertEquals("Take tasks/main.yml from golden into tasks", result.commandName)
        assertNotNull("a Local History label first", result.labelId)
        assertEquals(listOf("Took tasks/main.yml from golden into tasks"), ui.notes)
        assertTrue("no dialog", ui.deleteQuestions.isEmpty() && ui.sensitiveQuestions.isEmpty() && ui.notices.isEmpty())
        assertTrue(UndoManager.getInstance(project).getUndoActionNameAndDescription(null).second.endsWith("Take tasks/main.yml from golden into tasks"))

        undoOnce()
        assertEquals("one Undo restores it", before, snapshot("tasks"))
    }

    fun testTakeGoldensCreatesAFileOnlyGoldenHasAndNothingElse() {
        val differing = plan("golden", "missing").entries.map { it.relPath }
        assertTrue("meta/argument_specs.yml" in differing)
        val outcome = take("missing", "meta/argument_specs.yml")
        assertEquals(outcome.toString(), TakeOutcome.Status.TAKEN, outcome.status)
        assertEquals(listOf("meta/argument_specs.yml"), outcome.result!!.created)
        assertEquals(text("golden", "meta/argument_specs.yml"), text("missing", "meta/argument_specs.yml"))
        assertEquals("exactly that path", differing - "meta/argument_specs.yml", plan("golden", "missing").entries.map { it.relPath })

        undoOnce()
        assertFalse("Undo removes the created file", Files.exists(path("missing", "meta/argument_specs.yml")))
    }

    fun testTakeGoldensDeletesAFileOnlyThisCopyHasAfterAQuestion() {
        writeOnDisk("mol", "files/notes/extra.txt", "only in mol\n".toByteArray())
        ui.deleteAnswer = false
        val declined = take("mol", "files/notes/extra.txt")
        assertEquals(TakeOutcome.Status.DECLINED, declined.status)
        assertTrue(Files.exists(path("mol", "files/notes/extra.txt")))
        assertEquals(
            listOf("files/notes/extra.txt exists only in mol; golden has no such file.\n\nDelete it from mol? Undo restores it."),
            ui.deleteQuestions,
        )

        ui.deleteAnswer = true
        val deleted = take("mol", "files/notes/extra.txt")
        assertEquals(deleted.toString(), TakeOutcome.Status.TAKEN, deleted.status)
        assertEquals(listOf("files/notes/extra.txt"), deleted.result!!.deleted)
        assertFalse(Files.exists(path("mol", "files/notes/extra.txt")))
        assertEquals("Deleted files/notes/extra.txt from mol, as golden has no such file", ui.notes.last())

        undoOnce()
        assertEquals("Undo brings it back with its content", "only in mol\n", text("mol", "files/notes/extra.txt"))
    }

    fun testNothingDiffersOnlyInforms() {
        val outcome = take("same", "tasks/main.yml")
        assertEquals(TakeOutcome.Status.SAME, outcome.status)
        assertEquals(listOf("tasks/main.yml is the same in golden and same; nothing to take."), ui.notices)
        assertNull(outcome.result)
    }

    fun testAMoleculeFileIsTakenEvenWhenDriftIgnoresMolecule() {
        AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(ignoreMolecule = true)) }
        try {
            val outcome = take("mol", "molecule/default/verify.yml")
            assertEquals("the user picked the file", TakeOutcome.Status.TAKEN, outcome.status)
            assertEquals(text("golden", "molecule/default/verify.yml"), text("mol", "molecule/default/verify.yml"))
        } finally {
            AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(ignoreMolecule = false)) }
        }
    }

    // ------------------------------------------------------------------ Take This into Golden

    fun testTakeIntoGoldenWritesGoldenOnly() {
        val others = listOf("same", "mol2", "tasks", "mol").associateWith { snapshot(it) }
        val outcome = take("mol", "molecule/default/verify.yml", TakeDirection.INTO_GOLDEN)
        assertEquals(outcome.toString(), TakeOutcome.Status.TAKEN, outcome.status)
        assertEquals(text("mol", "molecule/default/verify.yml"), text("golden", "molecule/default/verify.yml"))
        assertTrue(plan("golden", "mol").isIdentical)
        assertEquals("Take molecule/default/verify.yml from mol into golden", outcome.result!!.commandName)
        for ((team, files) in others) assertEquals("$team is untouched", files, snapshot(team))
    }

    fun testTakeIntoGoldenCreatesAndDeletesInGolden() {
        writeOnDisk("tasks", "files/notes/new.txt", "only in tasks\n".toByteArray())
        val created = take("tasks", "files/notes/new.txt", TakeDirection.INTO_GOLDEN)
        assertEquals(listOf("files/notes/new.txt"), created.result!!.created)
        assertEquals("only in tasks\n", text("golden", "files/notes/new.txt"))

        val deleted = take("missing", "meta/argument_specs.yml", TakeDirection.INTO_GOLDEN)
        assertEquals(deleted.toString(), TakeOutcome.Status.TAKEN, deleted.status)
        assertEquals(
            listOf("meta/argument_specs.yml exists only in golden; missing has no such file.\n\nDelete it from golden? Undo restores it."),
            ui.deleteQuestions,
        )
        assertFalse(Files.exists(path("golden", "meta/argument_specs.yml")))
    }

    // ------------------------------------------------------------------ key, vault and binary files

    fun testKeyAndVaultFilesOnlyAfterAnExplicitConfirmationAndNeverDecrypted() {
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        writeOnDisk("golden", "files/ssl/web.key", "synthetic key material A\n".toByteArray())
        writeOnDisk("mol", "files/ssl/web.key", "synthetic key material B\n".toByteArray())
        writeOnDisk("golden", "vars/secrets.yml", DriftFixture.syntheticVault("golden").toByteArray())
        writeOnDisk("mol", "vars/secrets.yml", DriftFixture.syntheticVault("mol").toByteArray())

        ui.sensitiveAnswer = false
        assertEquals(TakeOutcome.Status.DECLINED, take("mol", "files/ssl/web.key").status)
        assertEquals("synthetic key material B\n", text("mol", "files/ssl/web.key"))
        assertEquals(
            "files/ssl/web.key names key material or a secret, or it is a vault file. It is copied as it is from golden into mol: never decrypted, never shown.\n\nTake it?",
            ui.sensitiveQuestions.single(),
        )

        ui.sensitiveAnswer = true
        assertEquals(TakeOutcome.Status.TAKEN, take("mol", "files/ssl/web.key").status)
        assertEquals("synthetic key material A\n", text("mol", "files/ssl/web.key"))
        assertEquals(TakeOutcome.Status.TAKEN, take("mol", "vars/secrets.yml").status)
        assertEquals("the ciphertext as it is", DriftFixture.syntheticVault("golden"), text("mol", "vars/secrets.yml"))
        assertEquals(3, ui.sensitiveQuestions.size)
        assertEquals("nothing is decrypted", 0, guard.calls.get())
    }

    fun testBinaryFilesWorkWithACannotBeUndoneNote() {
        writeOnDisk("golden", "files/logo.png", png)
        writeOnDisk("mol", "files/logo.png", png + byteArrayOf(1))
        val outcome = take("mol", "files/logo.png")
        assertEquals(outcome.toString(), TakeOutcome.Status.TAKEN, outcome.status)
        assertTrue(bytes("mol", "files/logo.png").contentEquals(png))
        assertEquals(listOf("files/logo.png"), outcome.result!!.notUndoable)
        assertEquals(listOf("Took files/logo.png from golden into mol. Undo cannot restore it (binary content); git can."), ui.notices)
        assertTrue("no status-bar note instead", ui.notes.isEmpty())
    }

    // ------------------------------------------------------------------ guards

    fun testUnsavedChangesAreNeverDeletedAndAWriteOverThemIsUndoable() {
        writeOnDisk("mol", "files/notes/extra.txt", "only in mol\n".toByteArray())
        val extra = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("mol")}/files/notes/extra.txt"))!!
        WriteCommandAction.runWriteCommandAction(project) { extra.insertString(0, "typed\n") }
        try {
            val refused = take("mol", "files/notes/extra.txt")
            assertEquals(TakeOutcome.Status.NOT_WRITTEN, refused.status)
            assertEquals(listOf("files/notes/extra.txt has unsaved changes in mol. Nothing was deleted; save or discard them first."), ui.notices)
            assertTrue("not even asked", ui.deleteQuestions.isEmpty())
            assertTrue(Files.exists(path("mol", "files/notes/extra.txt")))
        } finally {
            FileDocumentManager.getInstance().reloadFromDisk(extra)
        }

        val main = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) { main.insertString(0, "# typed\n") }
        val typed = main.text
        assertEquals(TakeOutcome.Status.TAKEN, take("tasks", "tasks/main.yml").status)
        assertEquals(text("golden", "tasks/main.yml"), main.text)
        undoOnce()
        assertEquals("Undo brings back what was typed", typed, main.text)
    }

    fun testAReadOnlyFileWritesNothing() {
        Files.setPosixFilePermissions(path("tasks", "tasks/main.yml"), PosixFilePermissions.fromString("r--r--r--"))
        refresh()
        try {
            val before = text("tasks", "tasks/main.yml")
            val outcome = take("tasks", "tasks/main.yml")
            assertEquals(TakeOutcome.Status.NOT_WRITTEN, outcome.status)
            assertEquals(WriteResult.Status.READ_ONLY, outcome.result!!.status)
            assertEquals(before, text("tasks", "tasks/main.yml"))
            assertEquals(listOf("Nothing was written in tasks: A file is read-only (tasks/main.yml). Nothing was written."), ui.notices)
        } finally {
            Files.setPosixFilePermissions(path("tasks", "tasks/main.yml"), PosixFilePermissions.fromString("rw-r--r--"))
            refresh()
        }
    }

    // ------------------------------------------------------------------ the actions

    private fun context(file: VirtualFile? = null, roleCopy: VirtualFile? = null, rolePath: String? = null): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        file?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        roleCopy?.let { builder.add(GoldenDataKeys.ROLE_COPY, it) }
        rolePath?.let { builder.add(GoldenDataKeys.ROLE_PATH, it) }
        return builder.build()
    }

    private fun perform(id: String, context: DataContext) {
        val action = ActionManager.getInstance().getAction(id)
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
    }

    fun testTheActionsTakeFromTheEditorAndFromADifferencesRow() {
        perform("Ansibility.Golden.TakeGoldens", context(file = vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml")))
        GoldenTestSupport.waitFor("the editor's file is golden's") { ui.notes.isNotEmpty() }
        assertEquals(text("golden", "tasks/main.yml"), text("tasks", "tasks/main.yml"))

        perform("Ansibility.Golden.TakeGoldens", context(roleCopy = roleDir("missing"), rolePath = "meta/argument_specs.yml"))
        GoldenTestSupport.waitFor("the only-in-golden row is created") { ui.notes.size == 2 }
        assertEquals(text("golden", "meta/argument_specs.yml"), text("missing", "meta/argument_specs.yml"))

        perform("Ansibility.Golden.TakeIntoGolden", context(roleCopy = roleDir("mol"), rolePath = "molecule/default/verify.yml"))
        GoldenTestSupport.waitFor("golden takes mol's file") { ui.notes.size == 3 }
        assertEquals(text("mol", "molecule/default/verify.yml"), text("golden", "molecule/default/verify.yml"))
    }
}
