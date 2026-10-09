package de.terletzkiy.ansibility.golden.sync

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.model.drift.DriftFixture
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * The review fixes of [RoleWriter] (plan amendment R24, review S1, S3, S8, S11, S12) on real files: never through a
 * symbolic link (the target, a file, a dangling link, two roots that are one folder), a VFS write never makes the
 * command refuse Undo, a file never replaces a folder, an error never escapes and never drops the other operations,
 * and Undo puts the executable bits back.
 */
class RoleWriterReviewFixesTest : GoldenLocalTestCase() {
    override fun tearDown() {
        try {
            RoleWriter.beforeOpForTests = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun plan(source: String, target: String): RoleFilePlan =
        GoldenTestSupport.await { RoleFilePlan.compute(project, roleDir(source), roleDir(target), PlanOptions()) }

    private fun mirror(plan: RoleFilePlan): List<FileOp> = GoldenTestSupport.await { SyncOps.mirror(project, plan) }

    private fun apply(target: String, ops: List<FileOp>): WriteResult =
        RoleWriter.apply(project, roleDir(target), ops, "Before aligning web in $target", "Align web in $target")

    private fun snapshot(team: String): Map<String, String> {
        GoldenTestSupport.fsync()
        val dir = base.resolve(DriftFixture.roleDir(team)).toRealPath()
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

    // ------------------------------------------------------------------ S1: symbolic links

    /** A repo copy that is a link to golden's copy looks like a copy of its own; writing it would write golden. */
    fun testACopyLinkedToGoldenIsNeverWritten() {
        val mol = base.resolve(DriftFixture.roleDir("mol"))
        Files.walk(mol).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        Files.createSymbolicLink(mol, base.resolve(DriftFixture.roleDir("golden")))
        refresh()
        val golden = snapshot("golden")
        val linked = roleDir("mol")
        assertNotNull("the link shows as a role directory", linked)
        assertEquals(base.resolve(DriftFixture.roleDir("golden")).toRealPath().toString(), RoleLinks.linkedTo(project, linked))

        val ops = listOf(
            FileOp.Write("tasks/main.yml", OpContent.Text("---\n# written through the link\n")),
            FileOp.Delete("handlers/main.yml"),
            FileOp.Create("files/new.txt", OpContent.Text("new\n")),
        )
        val result = RoleWriter.apply(project, linked, ops, "Before", "Push web to mol")
        assertEquals(WriteResult.Status.INVALID_TARGET, result.status)
        assertTrue(result.message!!, result.message!!.contains("is a link to"))
        assertEquals("golden is untouched", golden, snapshot("golden"))
        assertNull(RoleLinks.linkedTo(project, roleDir("tasks")))
    }

    fun testAFileThatIsALinkIsNeverWrittenThroughAndADanglingLinkIsNeverCreated() {
        val outside = Files.createDirectories(base.resolve("outside"))
        val shared = Files.writeString(outside.resolve("shared.conf"), "shared\n")
        Files.delete(path("tasks", "files/motd.txt"))
        Files.createSymbolicLink(path("tasks", "files/motd.txt"), shared)
        Files.createSymbolicLink(path("tasks", "files/dangling.txt"), outside.resolve("missing.txt"))
        refresh()

        val motd = vf("${DriftFixture.roleDir("tasks")}/files/motd.txt")
        val result = apply(
            "tasks",
            listOf(
                FileOp.Write("files/motd.txt", OpContent.Text("through the link\n"), expected = FileStamp.of(motd)),
                FileOp.Create("files/dangling.txt", OpContent.Text("through a dangling link\n"), expected = null),
            ),
        )
        assertEquals(listOf("files/motd.txt: NEVER_TOUCHED", "files/dangling.txt: NEVER_TOUCHED"), result.skipped.map { it.toString() })
        assertEquals("the linked file is untouched", "shared\n", Files.readString(shared))
        assertFalse("nothing is created outside the repo", Files.exists(outside.resolve("missing.txt")))

        // The walk leaves linked files out, like linked directories: they never differ and are never mirrored.
        val plan = plan("golden", "tasks")
        assertNull(plan.entry("files/motd.txt"))
        assertNull(plan.entry("files/dangling.txt"))
    }

    fun testTwoRootsThatAreOneFolderOnDiskWriteNothing() {
        // repos/wren/ansible is a link to repos/mol/ansible: wren's copy of web is mol's.
        Files.createDirectories(base.resolve("repos/wren"))
        Files.createSymbolicLink(base.resolve("repos/wren/ansible"), base.resolve("repos/mol/ansible"))
        refresh()
        val mol = snapshot("mol")
        val wren = vf("repos/wren/ansible/roles/web")
        val perRoot = linkedMapOf(
            roleDir("mol") to listOf<FileOp>(FileOp.Create("files/a.txt", OpContent.Text("a\n"))),
            wren to listOf<FileOp>(FileOp.Create("files/b.txt", OpContent.Text("b\n"))),
        )
        val results = RoleWriter.multi(project, perRoot, "Before pushing web from golden to", "Push web to")
        assertEquals(listOf(WriteResult.Status.INVALID_TARGET, WriteResult.Status.INVALID_TARGET), results.values.map { it.status })
        assertEquals("nothing was written", mol, snapshot("mol"))
        assertNotNull(RoleLinks.overlapping(listOf(roleDir("mol"), wren)))
        assertNull(RoleLinks.overlapping(listOf(roleDir("mol"), roleDir("tasks"))))
    }

    // ------------------------------------------------------------------ S3: a VFS write never poisons the command

    fun testBytesNoDocumentCanHoldLeaveTheCommandUndoable() {
        val before = snapshot("tasks")
        val tasks = vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml")
        val defaults = vf("${DriftFixture.roleDir("tasks")}/defaults/main.yml")
        val handlers = vf("${DriftFixture.roleDir("tasks")}/handlers/main.yml")
        val result = apply(
            "tasks",
            listOf(
                FileOp.Write("tasks/main.yml", OpContent.Bytes("a\r\nb\nc\n".toByteArray()), expected = FileStamp.of(tasks)),
                FileOp.Write("defaults/main.yml", OpContent.Text("---\nweb_port: 9\n"), expected = FileStamp.of(defaults)),
                FileOp.Delete("handlers/main.yml", FileStamp.of(handlers)),
            ),
        )
        assertTrue(result.toString(), result.applied)
        assertEquals(listOf("tasks/main.yml"), result.notUndoable)

        undoOnce()
        val after = snapshot("tasks")
        assertEquals("the text write is undone", before["defaults/main.yml"], after["defaults/main.yml"])
        assertEquals("the deletion is undone", before["handlers/main.yml"], after["handlers/main.yml"])
    }

    fun testBytesNoDocumentCanHoldLeaveTheCommandUndoableWhenTheDocumentIsLoaded() {
        val before = snapshot("tasks")
        val tasks = vf("${DriftFixture.roleDir("tasks")}/tasks/main.yml")
        val loaded = FileDocumentManager.getInstance().getDocument(tasks)!!
        val defaults = vf("${DriftFixture.roleDir("tasks")}/defaults/main.yml")
        val result = apply(
            "tasks",
            listOf(
                FileOp.Write("tasks/main.yml", OpContent.Bytes("a\r\nb\nc\n".toByteArray()), expected = FileStamp.of(tasks)),
                FileOp.Write("defaults/main.yml", OpContent.Text("---\nweb_port: 9\n"), expected = FileStamp.of(defaults)),
            ),
        )
        assertTrue(result.toString(), result.applied)
        assertEquals("the loaded document follows the exact bytes", "a\nb\nc\n", loaded.text)

        undoOnce()
        assertEquals("the text write is undone", before["defaults/main.yml"], snapshot("tasks")["defaults/main.yml"])
        assertEquals("the reloaded document is undone too", String(Base64.getDecoder().decode(before["tasks/main.yml"])), loaded.text)
    }

    // ------------------------------------------------------------------ S8: a file never replaces a folder

    fun testAFileNeverReplacesAFolder() {
        writeOnDisk("tasks", "files/conf/a.yml", "---\na: 1\n".toByteArray())
        writeOnDisk("golden", "files/conf", "a file in golden\n".toByteArray())
        val plan = plan("golden", "tasks")
        assertEquals(PlanKind.ONLY_IN_SOURCE, plan.entry("files/conf")!!.kind)
        assertEquals(PlanKind.ONLY_IN_TARGET, plan.entry("files/conf/a.yml")!!.kind)
        val before = snapshot("tasks")

        val result = apply("tasks", mirror(plan))
        assertEquals(WriteResult.Status.STALE, result.status)
        assertTrue(result.message!!, result.message!!.contains("a folder on one side and a file on the other (files/conf)"))
        assertEquals("nothing was written; the folder's file is kept", before, snapshot("tasks"))

        // The other way round works: the file is deleted before the folder's file is created (path order).
        writeOnDisk("same", "files/conf", "a file in same\n".toByteArray())
        writeOnDisk("golden", "files/notes/conf/a.yml", "---\n".toByteArray())
        val text = OpContent.Text("x\n")
        val blocked = apply("same", listOf(FileOp.Create("files/conf/b.txt", text)))
        assertEquals("a file where a folder is needed, and no delete of it", WriteResult.Status.STALE, blocked.status)
        assertTrue(Files.isRegularFile(path("same", "files/conf")))
        val replaced = apply("same", listOf(FileOp.Delete("files/conf"), FileOp.Create("files/conf/b.txt", text)))
        assertEquals(replaced.toString(), WriteResult.Status.APPLIED, replaced.status)
        assertEquals("x\n", text("same", "files/conf/b.txt"))
    }

    // ------------------------------------------------------------------ S11: errors never escape

    fun testAnyErrorFailsOnlyItsOperationAndIsReported() {
        RoleWriter.beforeOpForTests = { op -> if (op.relPath == "files/b.txt") throw AssertionError("simulated error") }
        val text = OpContent.Text("x\n")
        val result = apply("tasks", listOf(FileOp.Create("files/a.txt", text), FileOp.Create("files/b.txt", text), FileOp.Create("files/c.txt", text)))
        assertEquals(WriteResult.Status.APPLIED, result.status)
        assertEquals(listOf("files/a.txt", "files/c.txt"), result.created)
        assertEquals(listOf("files/b.txt: FAILED"), result.skipped.map { it.toString() })

        // In several roots: the error of one root never stops the next one, and every root reports.
        RoleWriter.beforeOpForTests = { op -> if (op.relPath == "files/d.txt") throw OutOfMemoryError("simulated") }
        val results = RoleWriter.multi(
            project,
            linkedMapOf(roleDir("mol") to listOf<FileOp>(FileOp.Create("files/d.txt", text)), roleDir("mol2") to listOf<FileOp>(FileOp.Create("files/e.txt", text))),
            "Before pushing web from golden to", "Push web to",
        )
        assertEquals(listOf("files/d.txt: FAILED"), results.getValue(roleDir("mol")).skipped.map { it.toString() })
        assertEquals(listOf("files/e.txt"), results.getValue(roleDir("mol2")).created)
        assertTrue(Files.exists(path("mol2", "files/e.txt")))
    }

    fun testACancellationStopsTheRestAndReportsIt() {
        RoleWriter.beforeOpForTests = { op -> if (op.relPath == "files/b.txt") throw com.intellij.openapi.progress.ProcessCanceledException() }
        val text = OpContent.Text("x\n")
        val result = apply("tasks", listOf(FileOp.Create("files/a.txt", text), FileOp.Create("files/b.txt", text), FileOp.Create("files/c.txt", text)))
        assertEquals(listOf("files/a.txt"), result.created)
        assertEquals(listOf("files/b.txt: FAILED", "files/c.txt: FAILED"), result.skipped.map { it.toString() })
    }

    // ------------------------------------------------------------------ S12: Undo restores the executable bits

    fun testUndoRestoresTheExecutableBits() {
        writeOnDisk("golden", "files/run.sh", "#!/bin/sh\necho golden\n".toByteArray())
        Files.setPosixFilePermissions(path("golden", "files/run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"))
        writeOnDisk("tasks", "files/run.sh", "#!/bin/sh\necho tasks\n".toByteArray())
        Files.setPosixFilePermissions(path("tasks", "files/run.sh"), PosixFilePermissions.fromString("rw-r--r--"))
        writeOnDisk("tasks", "files/stop.sh", "#!/bin/sh\necho stop\n".toByteArray())
        Files.setPosixFilePermissions(path("tasks", "files/stop.sh"), PosixFilePermissions.fromString("rwxr-x---"))
        refresh()
        val stop = vf("${DriftFixture.roleDir("tasks")}/files/stop.sh")
        val ops = mirror(plan("golden", "tasks")).filter { it.relPath == "files/run.sh" } +
            FileOp.Write("files/stop.sh", OpContent.Text("#!/bin/sh\necho stopped\n"), false, FileStamp.of(stop))
        assertTrue(apply("tasks", ops).applied)
        GoldenTestSupport.fsync()
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(path("tasks", "files/run.sh")))
        assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(path("tasks", "files/stop.sh")))

        undoOnce()
        GoldenTestSupport.fsync()
        assertEquals("the old mode is back", PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(path("tasks", "files/run.sh")))
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(path("tasks", "files/stop.sh")))
        assertEquals("#!/bin/sh\necho tasks\n", text("tasks", "files/run.sh"))
    }

    fun testTheModeIsNeverSetThroughALink() {
        val outside = Files.createDirectories(base.resolve("outside"))
        val script = Files.writeString(outside.resolve("tool.sh"), "#!/bin/sh\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rw-r--r--"))
        val link = path("tasks", "files/tool.sh")
        Files.createSymbolicLink(link, script)
        try {
            RoleWriter.setExecutable(link, true)
        } catch (_: IOException) {
            // A file system without links' own modes: nothing set either way.
        }
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(script))
    }
}
