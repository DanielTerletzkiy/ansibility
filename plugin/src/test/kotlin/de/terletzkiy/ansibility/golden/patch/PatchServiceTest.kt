package de.terletzkiy.ansibility.golden.patch

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTarget
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.remote.GoldenMirrorState
import de.terletzkiy.ansibility.golden.remote.GoldenMirrors
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * "Copy as Patch for Golden…" (plan amendment R25, X126) on real files: the patch of a copy row and of one file, its
 * paths relative to the golden root, `git apply` in a temp repository holding the golden tree making golden
 * byte-identical to the copy (changed, added, deleted, CRLF, a missing final newline, an executable bit), binary files
 * noted without content, key and vault files left out unless included (never decrypted), `molecule/` per drift's option
 * for a copy and always for a selected file, unsaved edits of the copy, the clipboard and the file through the seam,
 * cancelling, nothing to patch, and an external golden root's repository paths.
 */
class PatchServiceTest : GoldenLocalTestCase() {
    private lateinit var ui: RecordingPatchUi

    override fun setUp() {
        super.setUp()
        ui = RecordingPatchUi.install(testRootDisposable)
    }

    private fun run(team: String, relPath: String? = null): PatchOutcome =
        GoldenTestSupport.await { PatchService.getInstance(project).run(GoldenTarget(roleCopy(team), relPath)) }

    private fun patchText(outcome: PatchOutcome): String = outcome.patch?.text ?: error("no patch: $outcome")

    private fun clipboard(): String? = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)

    /** A git repository holding a copy of the golden root (`golden/`, whose `roles/web` is the golden copy). */
    private fun goldenRepo() = PatchGit.repoOf(base.resolve("golden"), FileUtil.createTempDirectory("ansibility-x126-golden", null, true).toPath().resolve("golden"))

    private fun copyFiles(team: String): Map<String, List<Byte>> {
        GoldenTestSupport.fsync()
        return PatchGit.files(base.resolve(DriftFixture.roleDir(team)))
    }

    private fun chmodX(team: String, relative: String) {
        Files.setPosixFilePermissions(path(team, relative), PosixFilePermissions.fromString("rwxr-xr-x"))
        refresh()
    }

    // ------------------------------------------------------------------ the patch and git apply

    fun testTheCopyPatchAppliesInTheGoldenRepositoryAndMakesGoldenByteIdentical() {
        writeOnDisk("missing", "templates/extra.j2", "server_name {{ web_name }};\n".toByteArray())
        writeOnDisk("golden", "files/crlf.conf", "a\r\nb\r\nc\r\n".toByteArray())
        writeOnDisk("missing", "files/crlf.conf", "a\r\nB\r\nc\r\n".toByteArray())
        writeOnDisk("golden", "files/noeol.txt", "x\ny".toByteArray())
        writeOnDisk("missing", "files/noeol.txt", "x\ny\n".toByteArray())
        writeOnDisk("golden", "files/run.sh", "echo golden\n".toByteArray())
        writeOnDisk("missing", "files/run.sh", "echo missing\n".toByteArray())
        chmodX("missing", "files/run.sh")

        val outcome = run("missing")
        assertEquals(PatchOutcome.Status.COPIED, outcome.status)
        val patch = outcome.patch!!
        assertEquals(
            "changed, added and deleted files",
            listOf(
                "defaults/main.yml", "files/crlf.conf", "files/noeol.txt", "files/run.sh", "handlers/main.yml", "meta/argument_specs.yml",
                "molecule/default/converge.yml", "molecule/default/molecule.yml", "molecule/default/prepare.yml", "molecule/default/verify.yml",
                "tasks/configure.yml", "tasks/install.yml", "tasks/main.yml", "tasks/service.yml", "templates/extra.j2", "templates/site.conf.j2",
            ),
            patch.files,
        )
        val text = patchText(outcome)
        assertTrue(text, "diff --git a/roles/web/templates/extra.j2 b/roles/web/templates/extra.j2\nnew file mode 100644\n--- /dev/null\n+++ b/roles/web/templates/extra.j2\n" in text)
        assertTrue(text, "diff --git a/roles/web/tasks/install.yml b/roles/web/tasks/install.yml\ndeleted file mode 100644\n--- a/roles/web/tasks/install.yml\n+++ /dev/null\n" in text)
        assertTrue("CRLF kept", " a\r\n-b\r\n+B\r\n c\r\n" in text)
        assertTrue("the missing final newline", "-y\n\\ No newline at end of file\n+y\n" in text)
        assertTrue("the executable bit", "diff --git a/roles/web/files/run.sh b/roles/web/files/run.sh\nold mode 100644\nnew mode 100755\n" in text)

        val repo = goldenRepo()
        PatchGit.apply(repo, patch.bytes)
        assertEquals("golden's copy of web is now byte-identical to missing's", copyFiles("missing"), PatchGit.files(repo.dir.resolve("roles/web")))
        assertTrue(PatchGit.executable(repo.dir.resolve("roles/web/files/run.sh")))
        assertEquals("the rest of the golden repository is untouched", PatchGit.files(base.resolve("golden/roles/base")), PatchGit.files(repo.dir.resolve("roles/base")))
    }

    fun testPathsAreRelativeToTheGoldenRoot() {
        val text = patchText(run("tasks"))
        assertEquals(
            listOf("diff --git a/roles/web/tasks/main.yml b/roles/web/tasks/main.yml", "--- a/roles/web/tasks/main.yml", "+++ b/roles/web/tasks/main.yml"),
            text.lines().filter { it.startsWith("diff ") || it.startsWith("--- ") || it.startsWith("+++ ") },
        )
        assertFalse("no path of the copy or the disk", text.contains("repos/") || text.contains(base.toString()))
        assertTrue(text, text.endsWith("+\n+- name: Flush handlers\n+  ansible.builtin.meta: flush_handlers\n"))
    }

    // ------------------------------------------------------------------ selection

    fun testASelectedFileGivesThatFileAloneAndAFileOnlyGoldenHasIsDeleted() {
        val one = run("missing", "handlers/main.yml")
        assertEquals(listOf("handlers/main.yml"), one.patch!!.files)
        assertEquals(listOf("handlers/main.yml"), ui.models.last().rows.map { it.relPath })
        val deleted = patchText(run("missing", "meta/argument_specs.yml"))
        assertTrue(deleted, deleted.contains("diff --git a/roles/web/meta/argument_specs.yml b/roles/web/meta/argument_specs.yml\ndeleted file mode 100644\n"))
        val repo = goldenRepo()
        PatchGit.apply(repo, run("missing", "meta/argument_specs.yml").patch!!.bytes)
        assertFalse(Files.exists(repo.dir.resolve("roles/web/meta/argument_specs.yml")))
    }

    fun testASelectedFolderGivesWhatDiffersBelowIt() {
        assertEquals(listOf("tasks/configure.yml", "tasks/install.yml", "tasks/main.yml", "tasks/service.yml"), run("missing", "tasks").patch!!.files)
    }

    fun testMoleculeFollowsTheDriftOptionForACopyButASelectedFileIsIncluded() {
        assertEquals("molecule/ counts while drift does not ignore it", listOf("molecule/default/verify.yml"), run("mol").patch!!.files)
        AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(ignoreMolecule = true)) }
        try {
            val copy = run("mol")
            assertEquals("ignored: nothing differs in the copy", PatchOutcome.Status.SAME, copy.status)
            assertEquals(listOf("web in mol is the same as in golden; there is nothing for a patch."), ui.notices)
            val file = run("mol", "molecule/default/verify.yml")
            assertEquals("the user picked the file", listOf("molecule/default/verify.yml"), file.patch!!.files)
            assertEquals("a molecule folder too", listOf("molecule/default/verify.yml"), run("mol", "molecule").patch!!.files)
        } finally {
            AnsibilityProjectSettings.getInstance(project).update { it.copy(drift = it.drift.copy(ignoreMolecule = false)) }
        }
    }

    // ------------------------------------------------------------------ binary, key and vault files

    fun testBinaryFilesAreNotedWithoutContent() {
        // A binary file type, even with bytes that read as text; NUL bytes; a NUL after the 8 KB the plan looks at.
        writeOnDisk("golden", "files/logo.png", "GOLDEN-PIXELS\n".toByteArray())
        writeOnDisk("tasks", "files/logo.png", "TASKS-PIXELS\n".toByteArray())
        writeOnDisk("tasks", "files/new.bin", byteArrayOf(1, 0, 1) + "NEW-BYTES\n".toByteArray())
        writeOnDisk("golden", "files/late.txt", ("a\n".repeat(5000) + "LATE-GOLDEN\n").toByteArray())
        writeOnDisk("tasks", "files/late.txt", ("a\n".repeat(5000) + "LATE-\u0000\n").toByteArray())

        val outcome = run("tasks")
        val text = patchText(outcome)
        val firstDiff = text.indexOf("diff --git ")
        val note = text.substring(0, firstDiff)
        assertTrue(note, "Binary files a/roles/web/files/logo.png and b/roles/web/files/logo.png differ\n" in note)
        assertTrue(note, "Binary files /dev/null and b/roles/web/files/new.bin differ\n" in note)
        assertTrue(note, "Binary files a/roles/web/files/late.txt and b/roles/web/files/late.txt differ\n" in note)
        assertFalse("never a diff of a binary file", text.contains("diff --git a/roles/web/files/logo.png") || text.contains("diff --git a/roles/web/files/new.bin"))
        assertFalse("never binary content", text.contains("PIXELS") || text.contains("NEW-BYTES") || text.contains("LATE-"))
        assertEquals(listOf("files/late.txt", "files/logo.png", "files/new.bin"), outcome.patch!!.binaries)
        assertEquals(listOf("tasks/main.yml"), outcome.patch!!.files)
        assertEquals(
            "Patch for web (1 file) copied — apply it in the golden repository with git apply. 3 binary files not included (copy by hand)",
            ui.notifications.single(),
        )
        // The note before the first diff is skipped by git apply: the text diffs still apply.
        PatchGit.apply(goldenRepo(), outcome.patch!!.bytes, checkOnly = true)
    }

    fun testOnlyBinaryDifferencesMakeNoPatch() {
        writeOnDisk("same", "files/logo.png", byteArrayOf(1, 0, 3))
        val outcome = run("same")
        assertEquals(PatchOutcome.Status.NOTHING_TO_PATCH, outcome.status)
        assertTrue("no dialog", ui.models.isEmpty())
        assertEquals(listOf("Only binary files of web in same differ from golden; a patch cannot hold them. Copy them by hand."), ui.notices)
    }

    fun testKeyAndVaultFilesAreLeftOutUnlessIncludedAndNeverDecrypted() {
        val guard = GoldenTestSupport.guardVault(project, testRootDisposable)
        writeOnDisk("golden", "files/ssl/web.key", "synthetic key material of golden\n".toByteArray())
        writeOnDisk("tasks", "files/ssl/web.key", "synthetic key material of tasks\n".toByteArray())
        writeOnDisk("golden", "vars/vault.yml", DriftFixture.syntheticVault("golden marker").toByteArray())
        writeOnDisk("tasks", "vars/vault.yml", DriftFixture.syntheticVault("tasks marker").toByteArray())

        val left = run("tasks")
        assertEquals(2, ui.models.single().sensitiveCount)
        val text = patchText(left)
        assertFalse("no key material", text.contains("synthetic key material"))
        assertFalse("no vault body", text.contains("\$ANSIBLE_VAULT"))
        assertTrue(text, "Left out (key or vault file): roles/web/files/ssl/web.key\nLeft out (key or vault file): roles/web/vars/vault.yml\n" in text)
        assertEquals(listOf("tasks/main.yml"), left.patch!!.files)
        assertTrue(ui.notifications.single(), ui.notifications.single().endsWith("2 key or vault files left out"))

        ui.choice = { PatchChoice(PatchOutput.CLIPBOARD, includeSensitive = true) }
        val included = run("tasks")
        val all = patchText(included)
        assertTrue("as it is", "-synthetic key material of golden\n+synthetic key material of tasks\n" in all)
        assertTrue("the vault stays encrypted", "\$ANSIBLE_VAULT;1.1;AES256" in all)
        assertFalse("never decrypted", all.contains("tasks marker") || all.contains("golden marker"))
        assertEquals(0, guard.calls.get())
        val repo = goldenRepo()
        PatchGit.apply(repo, included.patch!!.bytes)
        assertEquals(copyFiles("tasks"), PatchGit.files(repo.dir.resolve("roles/web")))
    }

    // ------------------------------------------------------------------ unsaved edits

    fun testUnsavedEditsOfTheCopyAreInThePatchAndGoldenIsTakenFromDisk() {
        val copyDocument = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("tasks")}/handlers/main.yml"))!!
        val goldenDocument = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("golden")}/tasks/main.yml"))!!
        // Golden differs from the copy only by an unsaved edit here: on disk, where git applies, nothing differs.
        val goldenOnly = FileDocumentManager.getInstance().getDocument(vf("${DriftFixture.roleDir("golden")}/vars/main.yml"))!!
        WriteCommandAction.runWriteCommandAction(project) {
            copyDocument.insertString(0, "# unsaved edit\n")
            goldenDocument.insertString(0, "# unsaved golden edit\n")
            goldenOnly.insertString(0, "# unsaved golden edit\n")
        }
        try {
            val outcome = run("tasks")
            assertEquals("vars/main.yml is offered (it differs as you see it)", 3, ui.models.single().rows.size)
            assertEquals("but it is the same on disk", listOf("handlers/main.yml", "tasks/main.yml"), outcome.patch!!.files)
            val text = patchText(outcome)
            assertTrue(text, "+# unsaved edit\n" in text)
            assertFalse("golden as git sees it: its file on disk", text.contains("unsaved golden edit"))
            PatchGit.apply(goldenRepo(), outcome.patch!!.bytes, checkOnly = true)
        } finally {
            FileDocumentManager.getInstance().reloadFromDisk(copyDocument)
            FileDocumentManager.getInstance().reloadFromDisk(goldenDocument)
            FileDocumentManager.getInstance().reloadFromDisk(goldenOnly)
        }
    }

    // ------------------------------------------------------------------ the seam: clipboard and file

    fun testCopyPutsThePatchOnTheClipboard() {
        CopyPasteManager.getInstance().setContents(StringSelection("before"))
        val outcome = run("tasks")
        assertEquals(PatchOutcome.Status.COPIED, outcome.status)
        assertEquals(patchText(outcome), clipboard())
        assertTrue(clipboard()!!.startsWith("Ansibility: patch for web in golden, from tasks (1 file)\n"))
        assertEquals(listOf("Patch for web (1 file) copied — apply it in the golden repository with git apply"), ui.notifications)
        val model = ui.models.single()
        assertEquals("web-tasks-to-golden.patch", model.defaultFileName)
        assertEquals("roles/web", model.base.prefix)
    }

    fun testSaveWritesThePatchFile() {
        val target = FileUtil.createTempDirectory("ansibility-x126-save", null, true).toPath().resolve("out/web-tasks-to-golden.patch")
        ui.choice = { PatchChoice(PatchOutput.FILE) }
        ui.saveTo = target
        CopyPasteManager.getInstance().setContents(StringSelection("before"))
        val outcome = run("tasks")
        assertEquals(PatchOutcome.Status.SAVED, outcome.status)
        assertEquals(listOf("web-tasks-to-golden.patch"), ui.saveNames)
        assertEquals(outcome.patch!!.bytes.toList(), Files.readAllBytes(target).toList())
        assertEquals("the clipboard is left alone", "before", clipboard())
        assertEquals(listOf("Patch for web (1 file) saved as web-tasks-to-golden.patch — apply it in the golden repository with git apply"), ui.notifications)
        PatchGit.apply(goldenRepo(), Files.readAllBytes(target), checkOnly = true)
    }

    fun testCancellingCopiesAndSavesNothing() {
        CopyPasteManager.getInstance().setContents(StringSelection("before"))
        ui.choice = { null }
        assertEquals(PatchOutcome.Status.CANCELLED, run("tasks").status)
        ui.choice = { PatchChoice(PatchOutput.FILE) }
        ui.saveTo = null
        assertEquals("the save dialog cancelled", PatchOutcome.Status.CANCELLED, run("tasks").status)
        assertEquals("before", clipboard())
        assertTrue(ui.notifications.isEmpty())
    }

    fun testNothingDiffersTellsSoWithoutADialog() {
        assertEquals(PatchOutcome.Status.SAME, run("same").status)
        assertEquals(PatchOutcome.Status.SAME, run("tasks", "handlers/main.yml").status)
        assertTrue(ui.models.isEmpty())
        assertEquals(
            listOf(
                "web in same is the same as in golden; there is nothing for a patch.",
                "handlers/main.yml of web in tasks is the same as in golden; there is nothing for a patch.",
            ),
            ui.notices,
        )
    }

    fun testTheGoldenCopyItselfAndARoleWithoutGoldenGetNoPatch() {
        assertEquals(PatchOutcome.Status.NOT_APPLICABLE, run("golden").status)
        assertEquals(PatchOutcome.Status.NOT_APPLICABLE, run("tasks", "../other").status)
        assertTrue(ui.models.isEmpty() && ui.notices.isEmpty())
    }

    // ------------------------------------------------------------------ an external golden root (R25)

    fun testAnExternalGoldenRootGivesItsRepositoryPaths() {
        project.replaceService(GoldenMirrors::class.java, FakeMirrors(base), testRootDisposable)
        val text = patchText(run("tasks"))
        assertTrue(text, text.contains("diff --git a/golden/roles/web/tasks/main.yml b/golden/roles/web/tasks/main.yml\n"))
        assertTrue(text, text.contains("Apply it in git@git.example.org:infra/golden.git (main): git apply web-tasks-to-golden.patch\n"))
        assertEquals("golden/roles/web", ui.models.single().base.prefix)

        project.replaceService(GoldenMirrors::class.java, FakeMirrors(FileUtil.createTempDirectory("ansibility-x126-elsewhere", null, true).toPath()), testRootDisposable)
        assertTrue("golden is not in that mirror: the golden root's paths", patchText(run("tasks")).contains("diff --git a/roles/web/tasks/main.yml "))
    }

    /** An external golden root whose repository is [baseDir]. */
    private class FakeMirrors(private val baseDir: Path) : GoldenMirrors {
        override fun state(): GoldenMirrorState = GoldenMirrorState(
            kind = GoldenMirrorState.Kind.GIT, rolesDir = baseDir.resolve("golden/roles"), baseDir = baseDir, name = "golden",
            source = "git@git.example.org:infra/golden.git (main)", commit = "3f2a1c9d", commitInstant = null, commitAuthor = null,
            commitSubject = null, fetchedAt = null, fetching = false, error = null, pausedReason = null, needsConsent = false,
        )

        override fun fetchNow(interactive: Boolean) = Unit

        override fun consent(allowed: Boolean) = Unit
    }
}
