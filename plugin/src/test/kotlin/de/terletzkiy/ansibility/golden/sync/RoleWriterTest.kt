package de.terletzkiy.ansibility.golden.sync

import com.intellij.history.LocalHistory
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * [RoleWriter] on real files (plan amendment R24, D187/D190): writes, creations and deletions; ONE undo reverts them
 * all; the Local History label is the revert point; a stale target and a read-only file abort before anything is
 * written; executable bits; binary files listed as not undoable; paths never touched; one command per root; the undo
 * confirmation is the caller's (none by default).
 */
class RoleWriterTest : GoldenLocalTestCase() {
    private fun plan(source: String, target: String, options: PlanOptions = PlanOptions()): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, roleDir(source), roleDir(target), options) }

    private fun mirror(plan: RoleFilePlan, deleteExtra: Boolean = true): List<FileOp> =
        GoldenTestSupport.await { SyncOps.mirror(project, plan, deleteExtra) }

    private fun apply(target: String, ops: List<FileOp>): WriteResult =
        RoleWriter.apply(project, roleDir(target), ops, "Before aligning web in $target", "Align web in $target")

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

    private fun chmod(team: String, relative: String, mode: String) {
        Files.setPosixFilePermissions(path(team, relative), PosixFilePermissions.fromString(mode))
        refresh()
    }

    private fun mode(team: String, relative: String): Set<PosixFilePermission> {
        GoldenTestSupport.fsync()
        return Files.getPosixFilePermissions(path(team, relative))
    }

    private fun assertBytes(expected: ByteArray, actual: ByteArray) =
        assertEquals(String(expected, Charsets.ISO_8859_1), String(actual, Charsets.ISO_8859_1))

    // ------------------------------------------------------------------ writes

    fun testMirrorWritesCreatesAndDeletes() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val before = plan("golden", "missing")
        assertEquals(3, before.count(PlanKind.CHANGED))
        assertEquals(9, before.count(PlanKind.ONLY_IN_SOURCE))
        assertEquals(1, before.count(PlanKind.ONLY_IN_TARGET))

        val result = apply("missing", mirror(before))
        assertEquals(result.toString(), WriteResult.Status.APPLIED, result.status)
        assertEquals(listOf("defaults/main.yml", "handlers/main.yml", "tasks/main.yml"), result.written)
        assertEquals(before.entries.filter { it.kind == PlanKind.ONLY_IN_SOURCE }.map { it.relPath }, result.created)
        assertEquals(listOf("files/notes/extra.txt"), result.deleted)
        assertEquals(emptyList<String>(), result.notUndoable)
        assertEquals(emptyList<WriteResult.Skip>(), result.skipped)
        assertEquals("3 changed, 9 added, 1 deleted", result.summary())
        assertEquals("Align web in missing", result.commandName)

        assertTrue("the copy is byte-identical to golden now", plan("golden", "missing").isIdentical)
        for (relative in result.written + result.created) assertBytes(bytes("golden", relative), bytes("missing", relative))
        assertFalse(Files.exists(path("missing", "files/notes/extra.txt")))
    }

    fun testOneUndoRevertsWritesCreationsAndDeletions() {
        writeOnDisk("missing", "files/notes/extra.txt", "only in missing\n".toByteArray())
        val before = plan("golden", "missing")
        val original = snapshot("missing")
        val result = apply("missing", mirror(before))
        assertTrue(result.applied)
        assertFalse(snapshot("missing") == original)

        undoOnce()
        assertEquals("one undo restores the copy exactly", original, snapshot("missing"))
        refresh()
        assertEquals(before.entries.map { it.relPath to it.kind }, plan("golden", "missing").entries.map { it.relPath to it.kind })
        assertFalse("the created molecule directory is gone too", Files.exists(path("missing", "molecule/default/verify.yml")))
    }

    fun testTheLocalHistoryLabelIsTheRevertPoint() {
        val original = snapshot("tasks")
        val result = apply("tasks", mirror(plan("golden", "tasks")))
        val labelId = result.labelId
        assertNotNull(labelId)
        assertTrue(GoldenTestSupport.await { LocalHistory.getInstance().isLabelValid(project, labelId!!) })
        assertTrue(plan("golden", "tasks").isIdentical)

        GoldenTestSupport.await { LocalHistory.getInstance().revertToLabel(project, labelId!!, roleDir("tasks")) }
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("reverting to the label restores the copy", original, snapshot("tasks"))
    }

    // ------------------------------------------------------------------ preconditions

    fun testAStaleTargetAbortsBeforeAnythingIsWritten() {
        val plan = plan("golden", "missing")
        val ops = mirror(plan)
        val original = snapshot("missing")
        writeOnDisk("missing", "tasks/main.yml", "---\n- name: Edited elsewhere\n  ansible.builtin.meta: noop\n".toByteArray())
        val edited = snapshot("missing")

        val result = apply("missing", ops)
        assertEquals(WriteResult.Status.STALE, result.status)
        assertEquals(listOf("tasks/main.yml"), result.stale)
        assertTrue(result.message!!, result.message!!.contains("tasks/main.yml"))
        assertNull(result.labelId)
        assertEquals("nothing was written", edited, snapshot("missing"))
        assertFalse(edited == original)
    }

    fun testUnsavedEditsAndNewFilesSinceThePlanAreStaleToo() {
        val ops = mirror(plan("golden", "missing"))
        val document = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("missing")}/handlers/main.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# typed\n") }
        writeOnDisk("missing", "tasks/install.yml", "---\n".toByteArray())

        val result = apply("missing", ops)
        assertEquals(WriteResult.Status.STALE, result.status)
        assertEquals(listOf("handlers/main.yml", "tasks/install.yml"), result.stale.sorted())
        assertTrue("the typed text stays", document.text.startsWith("# typed\n"))
        FileDocumentManager.getInstance().reloadFromDisk(document)
    }

    fun testAReadOnlyFileAbortsBeforeAnythingIsWritten() {
        val ops = mirror(plan("golden", "missing"))
        chmod("missing", "tasks/main.yml", "r--r--r--")
        val original = snapshot("missing")
        try {
            val result = apply("missing", ops)
            assertEquals(WriteResult.Status.READ_ONLY, result.status)
            assertEquals(listOf("tasks/main.yml"), result.readOnly)
            assertTrue(result.message!!, result.message!!.contains("tasks/main.yml"))
            assertEquals("nothing was written", original, snapshot("missing"))
        } finally {
            chmod("missing", "tasks/main.yml", "rw-r--r--")
        }
    }

    fun testPathsThatAreNeverTouched() {
        val outside = Files.createDirectories(base.resolve("outside"))
        Files.createSymbolicLink(path("tasks", "files/linked"), outside)
        refresh()
        val text = OpContent.Text("x\n")
        val ops = listOf(
            FileOp.Create(".git/config", text), FileOp.Create("__pycache__/x.pyc", text), FileOp.Create("files/.DS_Store", text),
            FileOp.Create("library/helper.pyc", text), FileOp.Create("../escape.txt", text), FileOp.Create("/abs.txt", text),
            FileOp.Create("files/./x.txt", text), FileOp.Create("files/linked/x.txt", text), FileOp.Create("files/.git", text),
        )
        val result = apply("tasks", ops)
        assertEquals(WriteResult.Status.NOTHING_TO_DO, result.status)
        assertEquals(ops.map { it.relPath }, result.skipped.map { it.relPath })
        assertTrue(result.skipped.all { it.reason == WriteResult.SkipReason.NEVER_TOUCHED })
        assertFalse(Files.exists(path("tasks", ".git")) || Files.exists(path("tasks", "__pycache__")) || Files.exists(base.resolve("outside/x.txt")))
        assertFalse(Files.exists(path("tasks", "files/.git")) || Files.exists(path("tasks", "files/.DS_Store")))
    }

    fun testSymlinkedDirectoriesTakeNoPartInAPlan() {
        val outside = Files.createDirectories(base.resolve("outside"))
        Files.write(outside.resolve("linked.yml"), "---\n".toByteArray())
        Files.createSymbolicLink(path("same", "files/linked"), outside)
        refresh()
        assertTrue(vf("${DriftFixture.roleDir("same")}/files/linked/linked.yml").isValid)
        assertTrue("a symlinked directory is never walked (as in drift)", plan("golden", "same").isIdentical)
    }

    fun testAFailedOperationIsReportedAndTheOthersStillRun() {
        val text = OpContent.Text("x\n")
        // A file where a folder is needed is refused up front now (S8, RoleWriterReviewFixesTest); an I/O error while
        // writing still fails that operation only.
        RoleWriter.beforeOpForTests = { op -> if (op.relPath == "files/fails.txt") throw java.io.IOException("simulated") }
        try {
            val result = apply("tasks", listOf(FileOp.Create("files/fails.txt", text), FileOp.Create("files/after.txt", text)))
            assertEquals(WriteResult.Status.APPLIED, result.status)
            assertEquals(listOf("files/fails.txt: FAILED"), result.skipped.map { it.toString() })
            assertEquals(listOf("files/after.txt"), result.created)
            assertTrue(Files.exists(path("tasks", "files/after.txt")))
        } finally {
            RoleWriter.beforeOpForTests = null
        }
    }

    fun testOperationsWithoutStampsSkipMissingAndExistingFiles() {
        val text = OpContent.Text("x\n")
        val result = apply(
            "tasks",
            listOf(FileOp.Write("files/none.txt", text), FileOp.Delete("files/none.txt"), FileOp.Create("files/motd.txt", text, expected = null), FileOp.Create("files/new.txt", text), FileOp.Create("files/new.txt", text)),
        )
        assertEquals(WriteResult.Status.APPLIED, result.status)
        assertEquals(listOf("files/new.txt"), result.created)
        assertEquals(
            listOf(
                "files/none.txt: NOT_FOUND", "files/none.txt: DUPLICATE", "files/motd.txt: EXISTS", "files/new.txt: DUPLICATE",
            ),
            result.skipped.map { it.toString() },
        )
    }

    // ------------------------------------------------------------------ content

    fun testTextKeepsTheTargetLineSeparators() {
        writeOnDisk("tasks", "templates/web.conf.j2", "user a;\r\nlisten 80;\r\n".toByteArray())
        val file = vf("${DriftFixture.roleDir("tasks")}/templates/web.conf.j2")
        val result = apply("tasks", listOf(FileOp.Write("templates/web.conf.j2", OpContent.Text("user b;\nlisten 81;\n"), expected = FileStamp.of(file))))
        assertTrue(result.applied)
        assertEquals(emptyList<String>(), result.notUndoable)
        assertBytes("user b;\r\nlisten 81;\r\n".toByteArray(), bytes("tasks", "templates/web.conf.j2"))
    }

    fun testTrailingSpacesAndAMissingFinalNewlineAreWrittenAsGiven() {
        val settings = EditorSettingsExternalizable.getInstance()
        val strip = settings.stripTrailingSpaces
        val ensure = settings.isEnsureNewLineAtEOF
        settings.stripTrailingSpaces = EditorSettingsExternalizable.STRIP_TRAILING_SPACES_WHOLE
        settings.isEnsureNewLineAtEOF = true
        try {
            val file = vf("${DriftFixture.roleDir("tasks")}/defaults/main.yml")
            val text = "---\nweb_port: 8080   \nweb_user: www-data"
            assertTrue(apply("tasks", listOf(FileOp.Write("defaults/main.yml", OpContent.Text(text), expected = FileStamp.of(file)))).applied)
            assertBytes(text.toByteArray(), bytes("tasks", "defaults/main.yml"))
        } finally {
            settings.stripTrailingSpaces = strip
            settings.isEnsureNewLineAtEOF = ensure
        }
    }

    fun testExactBytesGoThroughTheDocumentAndUndoRestoresTheFileForm() {
        val original = bytes("tasks", "tasks/main.yml")
        val exact = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "---\r\n- name: Exact\r\n  ansible.builtin.meta: noop\r\n".toByteArray()
        val file = vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml")
        val result = apply("tasks", listOf(FileOp.Write("tasks/main.yml", OpContent.Bytes(exact), expected = FileStamp.of(file))))
        assertTrue(result.applied)
        assertEquals("written through the document: undoable", emptyList<String>(), result.notUndoable)
        assertBytes(exact, bytes("tasks", "tasks/main.yml"))

        undoOnce()
        assertBytes(original, bytes("tasks", "tasks/main.yml"))
    }

    fun testBytesTheDocumentCannotHoldAreWrittenExactlyAndListedAsNotUndoable() {
        val mixed = "a\r\nb\nc\n".toByteArray()
        val file = vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml")
        val result = apply("tasks", listOf(FileOp.Write("tasks/main.yml", OpContent.Bytes(mixed), expected = FileStamp.of(file))))
        assertTrue(result.applied)
        assertEquals(listOf("tasks/main.yml"), result.notUndoable)
        assertBytes(mixed, bytes("tasks", "tasks/main.yml"))
    }

    fun testBinaryFilesAreWrittenAndListedAsNotUndoable() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        writeOnDisk("golden", "files/logo.png", png)
        writeOnDisk("tasks", "files/logo.png", png + byteArrayOf(1))
        writeOnDisk("tasks", "files/extra.png", png + byteArrayOf(2))
        val plan = plan("golden", "tasks")
        assertTrue(plan.entry("files/logo.png")!!.binary)

        val result = apply("tasks", mirror(plan))
        assertTrue(result.applied)
        assertEquals(listOf("files/logo.png", "tasks/main.yml"), result.written)
        assertEquals(listOf("files/extra.png"), result.deleted)
        assertEquals("binary content: Undo cannot restore it", listOf("files/extra.png", "files/logo.png"), result.notUndoable)
        assertBytes(png, bytes("tasks", "files/logo.png"))
        assertTrue(plan("golden", "tasks").isIdentical)
    }

    fun testTheExecutableBitIsKeptOrSet() {
        writeOnDisk("golden", "files/run.sh", "#!/bin/sh\necho golden\n".toByteArray())
        chmod("golden", "files/run.sh", "rwxr-xr-x")
        writeOnDisk("tasks", "files/run.sh", "#!/bin/sh\necho tasks\n".toByteArray())
        writeOnDisk("golden", "files/new.sh", "#!/bin/sh\n".toByteArray())
        chmod("golden", "files/new.sh", "rwxr-x---")
        writeOnDisk("same", "files/motd.txt", bytes("golden", "files/motd.txt"))
        chmod("same", "files/motd.txt", "rwxr--r--")
        assertNull("a mode difference alone is no difference (as in drift)", plan("golden", "same").entry("files/motd.txt"))

        val plan = plan("golden", "tasks")
        val run = plan.entry("files/run.sh")!!
        assertTrue(run.sourceExecutable && !run.targetExecutable && run.executableDiffers)
        assertTrue(plan.entry("files/new.sh")!!.sourceExecutable)
        assertTrue(apply("tasks", mirror(plan)).applied)
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), mode("tasks", "files/run.sh"))
        assertTrue(PosixFilePermission.OWNER_EXECUTE in mode("tasks", "files/new.sh"))

        writeOnDisk("tasks", "files/keep.sh", "#!/bin/sh\n".toByteArray())
        chmod("tasks", "files/keep.sh", "rwxr-xr-x")
        val keep = vf("${DriftFixture.roleDir("tasks")}/files/keep.sh")
        assertTrue(apply("tasks", listOf(FileOp.Write("files/keep.sh", OpContent.Text("#!/bin/sh\necho kept\n"), null, FileStamp.of(keep)))).applied)
        assertBytes("#!/bin/sh\necho kept\n".toByteArray(), bytes("tasks", "files/keep.sh"))
        assertEquals("a write keeps the mode", PosixFilePermissions.fromString("rwxr-xr-x"), mode("tasks", "files/keep.sh"))
        val keepNow = vf("${DriftFixture.roleDir("tasks")}/files/keep.sh")
        assertTrue(apply("tasks", listOf(FileOp.Write("files/keep.sh", OpContent.Text("#!/bin/sh\n"), false, FileStamp.of(keepNow)))).applied)
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), mode("tasks", "files/keep.sh"))
    }

    fun testSourceChangedSinceThePlanStopsTheMirror() {
        val plan = plan("golden", "tasks")
        writeOnDisk("golden", "tasks/main.yml", "---\n# golden edited\n".toByteArray())
        val failure = GoldenTestSupport.await { runCatching { SyncOps.mirror(project, plan) } }.exceptionOrNull()
        assertTrue("a changed source must not be mirrored: $failure", failure is SyncOps.SourceChanged)
        assertEquals(listOf("tasks/main.yml"), (failure as SyncOps.SourceChanged).relPaths)
        assertEquals(1, mirror(plan("golden", "tasks")).size)
        assertEquals("nothing differs, nothing to do", 0, mirror(plan("mol", "mol2")).size)
    }

    // ------------------------------------------------------------------ several roots

    fun testMultiWritesOneUndoableCommandPerRoot() {
        val mol = snapshot("mol")
        val mol2 = snapshot("mol2")
        val perRoot = linkedMapOf(
            roleDir("mol") to mirror(plan("golden", "mol")),
            roleDir("mol2") to mirror(plan("golden", "mol2")),
        )
        val results = RoleWriter.multi(project, perRoot, "Before pushing web from golden to", "Push web from golden to")
        assertEquals(listOf(WriteResult.Status.APPLIED, WriteResult.Status.APPLIED), results.values.map { it.status })
        assertEquals(listOf("Push web from golden to mol", "Push web from golden to mol2"), results.values.map { it.commandName })
        assertEquals(2, results.values.mapNotNull { it.labelId }.distinct().size)
        assertTrue(plan("golden", "mol").isIdentical && plan("golden", "mol2").isIdentical)

        undoOnce()
        assertEquals("the last root's command is undone first", mol2, snapshot("mol2"))
        assertFalse(mol == snapshot("mol"))
        undoOnce()
        assertEquals(mol, snapshot("mol"))
    }

    fun testTheUndoConfirmationIsTheCallersAndNoneByDefault() {
        val asked = AtomicInteger()
        fun undo() {
            TestDialogManager.setTestDialog { asked.incrementAndGet(); Messages.OK }
            try {
                UndoManager.getInstance(project).undo(null)
            } finally {
                TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            }
            FileDocumentManager.getInstance().saveAllDocuments()
        }
        val before = snapshot("tasks")

        assertEquals(WriteResult.Status.APPLIED, apply("tasks", mirror(plan("golden", "tasks"))).status)
        undo()
        assertEquals("the default (the builder's own): no question", 0, asked.get())
        assertEquals(before, snapshot("tasks"))

        val ops = mirror(plan("golden", "tasks"))
        val asking = RoleWriter.apply(project, roleDir("tasks"), ops, "Before", "Align web in tasks", UndoConfirmationPolicy.REQUEST_CONFIRMATION)
        assertEquals(WriteResult.Status.APPLIED, asking.status)
        undo()
        assertEquals("the caller's policy: the platform asks", 1, asked.get())
        assertEquals(before, snapshot("tasks"))
    }

    fun testMultiWritesNothingWhenOneRootFailsItsPreconditions() {
        val mol = snapshot("mol")
        val perRoot = linkedMapOf(
            roleDir("mol") to mirror(plan("golden", "mol")),
            roleDir("mol2") to mirror(plan("golden", "mol2")),
        )
        writeOnDisk("mol2", "molecule/default/verify.yml", "---\n# edited elsewhere\n".toByteArray())
        val mol2 = snapshot("mol2")
        val results = RoleWriter.multi(project, perRoot, "Before pushing web from golden to", "Push web from golden to")
        assertEquals(WriteResult.Status.NOT_RUN, results.getValue(roleDir("mol")).status)
        assertEquals(WriteResult.Status.STALE, results.getValue(roleDir("mol2")).status)
        assertEquals(mol, snapshot("mol"))
        assertEquals(mol2, snapshot("mol2"))
    }
}
