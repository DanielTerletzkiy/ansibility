package de.terletzkiy.ansibility.golden.align

import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.vcsUtil.VcsUtil
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.history.LastChange
import de.terletzkiy.ansibility.golden.history.LastChangeLookup
import de.terletzkiy.ansibility.golden.history.LastChanges
import de.terletzkiy.ansibility.golden.vcs.impl.AlignMergeCustomizer
import de.terletzkiy.ansibility.golden.vcs.impl.AlignMergeProvider
import de.terletzkiy.ansibility.golden.vcs.impl.AlignMergeSession
import de.terletzkiy.ansibility.golden.vcs.impl.SideRevisionLookup
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.DriftOptions
import de.terletzkiy.ansibility.model.drift.RoleDriftService
import de.terletzkiy.ansibility.settings.GoldenRoot
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * The review fixes of Align (plan amendment R24, review S1, S6, S10, U1, U4, U7, U8 is in AlignSessionTest, U11 too)
 * on real files: a source taken by the window itself ends byte-identical (trailing spaces, separators, executable bit),
 * a merge result is saved as the user left it, blame data and last changes on the merge window's sides, `molecule/`
 * always, no drift without a golden root, never through a link, and the disk is read again first.
 */
class AlignReviewFixesTest : GoldenLocalTestCase() {
    private lateinit var ui: RecordingAlignUi
    private var stripBefore: String? = null
    private var ensureBefore: Boolean = false

    override fun setUp() {
        super.setUp()
        ui = RecordingAlignUi()
        AlignTestSupport.install(ui, testRootDisposable)
        val settings = EditorSettingsExternalizable.getInstance()
        stripBefore = settings.stripTrailingSpaces
        ensureBefore = settings.isEnsureNewLineAtEOF
        settings.stripTrailingSpaces = EditorSettingsExternalizable.STRIP_TRAILING_SPACES_WHOLE
        settings.isEnsureNewLineAtEOF = true
    }

    override fun tearDown() {
        try {
            AlignFallbackDialog.showForTests = null
            AlignFallback.showMergeForTests = null
            EditorSettingsExternalizable.getInstance().stripTrailingSpaces = stripBefore
            EditorSettingsExternalizable.getInstance().isEnsureNewLineAtEOF = ensureBefore
            LastChanges.getInstance(project).clearForTests()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun session(target: String, source: String, include: Boolean = false): AlignSession =
        GoldenTestSupport.await { AlignService.getInstance(project).session(AlignRequest(roleCopy(target), roleCopy(source), include)) }

    private fun row(session: AlignSession, relPath: String): AlignRow = session.row(relPath) ?: error("no row $relPath in ${session.rows}")

    private fun snapshot(team: String): Map<String, String> {
        GoldenTestSupport.fsync()
        val dir = base.resolve(DriftFixture.roleDir(team)).toRealPath()
        return Files.walk(dir).use { paths ->
            paths.filter { it.isRegularFile() }.toList().associate { it.relativeTo(dir).toString() to Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }
        }
    }

    /** The fallback window: Merge… on [relPath], the viewer closing with [result] (after [edit] of its output). */
    private fun fallbackMerge(session: AlignSession, relPath: String, result: MergeResult, edit: String? = null) {
        AlignTestSupport.install(emptyList(), testRootDisposable)
        AlignFallback.showMergeForTests = { request ->
            val text = request as TextMergeRequest
            text.onAssigned(true)
            if (edit != null) WriteCommandAction.runWriteCommandAction(project) { text.outputContent.document.setText(edit) }
            request.applyResult(result)
            text.onAssigned(false)
        }
        AlignFallbackDialog.showForTests = { fallback -> fallback.merge(fallback.rows.first { it.relPath == relPath }) }
        AlignService.getInstance(project).run(session)
    }

    // ------------------------------------------------------------------ S6: the source's exact bytes

    fun testAcceptRightInTheFallbackViewerTakesTheSourceBytesWithTrailingSpaces() {
        writeOnDisk("golden", "files/notes.txt", "x  \ny\n".toByteArray())
        writeOnDisk("tasks", "files/notes.txt", "a  \nb\n".toByteArray())
        val session = session("tasks", "golden")
        fallbackMerge(session, "files/notes.txt", MergeResult.RIGHT)
        assertEquals("x  \ny\n", String(bytes("tasks", "files/notes.txt")))
        assertEquals(AlignResolution.TAKEN_FROM_SOURCE, session.resolution(row(session, "files/notes.txt")))
    }

    fun testAcceptRightTakesTheSourceSeparatorsAndExecutableBit() {
        writeOnDisk("golden", "files/run.sh", "#!/bin/sh\r\necho golden\r\n".toByteArray())
        Files.setPosixFilePermissions(path("golden", "files/run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"))
        writeOnDisk("tasks", "files/run.sh", "#!/bin/sh\necho tasks\n".toByteArray())
        refresh()
        val session = session("tasks", "golden")
        fallbackMerge(session, "files/run.sh", MergeResult.RIGHT)
        assertEquals("#!/bin/sh\r\necho golden\r\n", String(bytes("tasks", "files/run.sh")))
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(path("tasks", "files/run.sh")))
    }

    fun testAMergeResultIsSavedAsTheUserLeftIt() {
        writeOnDisk("golden", "files/notes.txt", "x  \ny\n".toByteArray())
        writeOnDisk("tasks", "files/notes.txt", "a  \nb\n".toByteArray())
        val session = session("tasks", "golden")
        fallbackMerge(session, "files/notes.txt", MergeResult.RESOLVED, edit = "merged  \nby hand")
        assertEquals("no trailing space stripped, no line feed added", "merged  \nby hand", String(bytes("tasks", "files/notes.txt")))
        assertEquals(AlignResolution.MERGED, session.resolution(row(session, "files/notes.txt")))
    }

    /** A merge viewer closed with Cancel leaves the target's file as it was (also after a later save) and the row open. */
    fun testACancelledViewerLeavesTheFileAsItWas() {
        writeOnDisk("golden", "files/notes.txt", "x  \ny\n".toByteArray())
        writeOnDisk("tasks", "files/notes.txt", "a  \nb\n".toByteArray())
        val session = session("tasks", "golden")
        fallbackMerge(session, "files/notes.txt", MergeResult.CANCEL, edit = "half merged  \n")
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("a  \nb\n", String(bytes("tasks", "files/notes.txt")))
        assertNull(session.resolution(row(session, "files/notes.txt")))
    }

    /**
     * The Conflicts dialog's iterative flow: Accept Theirs on a changed text row puts the source's text into the
     * target's document through the platform's merge model, saves it and reports Accepted Theirs at close, without
     * `acceptFilesRevisions`. The target then holds the source's exact bytes.
     */
    fun testAnAcceptByThePlatformsMergeModelEndsByteIdentical() {
        writeOnDisk("golden", "files/notes.txt", "x  \r\ny\r\n".toByteArray())
        writeOnDisk("tasks", "files/notes.txt", "a  \nb\n".toByteArray())
        val session = session("tasks", "golden")
        val dialog = ScriptedMergeDialog { project: Project, shown: AlignSession ->
            val row = row(shown, "files/notes.txt")
            val documents = FileDocumentManager.getInstance()
            val document = documents.getDocument(row.file)!!
            WriteCommandAction.runWriteCommandAction(project) { document.setText("x  \ny\n") }
            ApplicationManager.getApplication().runWriteAction { documents.saveDocument(document) }
            AlignMergeSession(shown).conflictResolvedForFiles(listOf(row.file), MergeSession.Resolution.AcceptedTheirs)
        }
        AlignTestSupport.install(listOf(dialog), testRootDisposable)
        AlignService.getInstance(project).run(session)
        assertEquals("x  \r\ny\r\n", String(bytes("tasks", "files/notes.txt")))
        assertEquals(1, session.outcome.taken)
    }

    // ------------------------------------------------------------------ U1: blame data and last changes

    fun testTheSidesCarryTheirRevisionWhenTrackedUnchangedAndSaved() {
        val session = session("tasks", "golden")
        val row = row(session, "tasks/main.yml")
        val looked = ArrayList<VirtualFile>()
        val lookup = SideRevisionLookup { file -> looked += file; VcsUtil.getFilePath(file) to VcsRevisionNumber.Int(7) }
        val data = GoldenTestSupport.pooled { AlignMergeProvider(session, lookup).loadRevisions(row.file) }
        assertEquals(VcsUtil.getFilePath(row.entry.sourceFile!!), data.LAST_FILE_PATH)
        assertEquals(VcsRevisionNumber.Int(7), data.LAST_REVISION_NUMBER)
        assertEquals(VcsUtil.getFilePath(row.entry.targetFile!!), data.CURRENT_FILE_PATH)
        assertEquals(VcsRevisionNumber.Int(7), data.CURRENT_REVISION_NUMBER)

        // An unsaved source: the window shows other bytes than the revision's, so no blame there.
        val second = session("tasks", "golden")
        val document = FileDocumentManager.getInstance().getDocument(row.entry.sourceFile!!)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# typed\n") }
        try {
            val unsaved = GoldenTestSupport.pooled {
                runCatching { AlignMergeProvider(second, lookup).loadRevisions(row(second, "tasks/main.yml").file) }
            }
            // The source changed since the comparison: the window refuses the row; a fresh session has no revision.
            assertTrue(unsaved.isFailure)
            val fresh = session("tasks", "golden")
            val freshData = GoldenTestSupport.pooled { AlignMergeProvider(fresh, lookup).loadRevisions(row(fresh, "tasks/main.yml").file) }
            assertNull("no revision for a side with an unsaved document", freshData.LAST_FILE_PATH)
            assertNull(freshData.LAST_REVISION_NUMBER)
            assertNotNull("the saved side keeps it", freshData.CURRENT_REVISION_NUMBER)
        } finally {
            FileDocumentManager.getInstance().reloadFromDisk(document)
        }
    }

    fun testNoRevisionWhenTheVcsHasNoneAndNeverForKeyFiles() {
        writeOnDisk("golden", "files/ssl/web.key", "synthetic key material of golden\n".toByteArray())
        writeOnDisk("tasks", "files/ssl/web.key", "synthetic key material of tasks\n".toByteArray())
        val session = session("tasks", "golden", include = true)
        val asked = ArrayList<VirtualFile>()
        val lookup = SideRevisionLookup { file -> asked += file; null }
        val data = GoldenTestSupport.pooled { AlignMergeProvider(session, lookup).loadRevisions(row(session, "tasks/main.yml").file) }
        assertNull(data.LAST_REVISION_NUMBER)
        GoldenTestSupport.pooled { AlignMergeProvider(session, lookup).loadRevisions(row(session, "files/ssl/web.key").file) }
        assertTrue("key files are never looked up", asked.none { it.name == "web.key" })
    }

    fun testThePanelTitlesCarryTheLastChangeOnceKnown() {
        val date = Instant.parse("2026-09-12T10:00:00Z")
        ExtensionTestUtil.maskExtensions(
            LastChangeLookup.EP_NAME,
            listOf(object : LastChangeLookup {
                override fun lastChange(project: Project, file: VirtualFile): LastChange? =
                    if (file.path.contains("/golden/")) LastChange("alice", date, "Tune web", "abc1234") else LastChange("bob", date, "Local web", "def5678")

                override fun lastChangeUnder(project: Project, dir: VirtualFile): LastChange? = null
            }),
            testRootDisposable,
        )
        LastChanges.getInstance(project).clearForTests()
        val session = session("tasks", "golden")
        val row = row(session, "tasks/main.yml")
        val customizer = AlignMergeCustomizer(session)
        assertEquals("golden (source)", customizer.getRightPanelTitle(row.file, null))
        GoldenTestSupport.pooled { AlignMergeProvider(session) { null }.loadRevisions(row.file) }
        assertTrue(customizer.getRightPanelTitle(row.file, null), customizer.getRightPanelTitle(row.file, null).matches(Regex("golden \\(source\\) · 2026-09-1[12] · alice")))
        assertTrue(customizer.getLeftPanelTitle(row.file), customizer.getLeftPanelTitle(row.file).endsWith(" · bob"))
    }

    // ------------------------------------------------------------------ U4, U7, S1, S10

    fun testAlignAlwaysIncludesMolecule() {
        val drift = RoleDriftService.getInstance(project)
        val before = drift.options
        drift.options = DriftOptions(ignoreMolecule = true)
        try {
            assertEquals(listOf("molecule/default/verify.yml"), session("mol", "golden").rows.map { it.relPath })
            val preview = GoldenTestSupport.await { AlignSetup.preview(project, roleCopy("mol"), roleCopy("golden"), false) }
            assertEquals(1, preview.changed)
        } finally {
            drift.options = before
        }
    }

    fun testWithoutAGoldenRootTheOpeningStepComputesNoDrift() {
        val drift = DriftFixture.freshService(project, testRootDisposable)
        project.registerOrReplaceServiceInstance(RoleDriftService::class.java, drift, testRootDisposable)
        DriftFixture.useGolden(project, testRootDisposable, GoldenRoot.None)
        val walked = drift.counters.copiesWalked
        val setup = GoldenTestSupport.await { AlignSetup.load(project, roleCopy("mol")) }!!
        assertTrue("no tier without a golden root: ${setup.choices.map { it.tier }}", setup.choices.all { it.tier == null })
        assertEquals("nothing computed", walked, drift.counters.copiesWalked)
    }

    fun testALinkedTargetIsNeverAligned() {
        val mol = base.resolve(DriftFixture.roleDir("mol"))
        Files.walk(mol).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        Files.createSymbolicLink(mol, base.resolve(DriftFixture.roleDir("tasks")))
        refresh()
        val tasks = snapshot("tasks")
        val dialog = ScriptedMergeDialog { _, shown -> AlignMergeSession(shown).acceptFilesRevisions(shown.rows.map { it.file }, MergeSession.Resolution.AcceptedTheirs) }
        AlignTestSupport.install(listOf(dialog), testRootDisposable)
        val job = AlignService.getInstance(project).align(AlignRequest(roleCopy("mol"), roleCopy("golden")))
        GoldenTestSupport.waitFor("the alignment ends") { job.isCompleted }
        assertEquals("no window", 0, dialog.sessions.size)
        assertTrue(ui.notices.toString(), ui.notices.single().startsWith("web in mol is a link to"))
        assertEquals(tasks, snapshot("tasks"))

        val setup = GoldenTestSupport.await { AlignSetup.load(project, roleCopy("tasks")) }!!
        assertNotNull(setup.linkedTo(roleCopy("mol")))
        assertTrue("never the default target", setup.defaultTarget.copy.dir != roleDir("mol"))
    }

    fun testTheDiskIsReadAgainBeforeComparing() {
        val file = vf("${DriftFixture.roleDir("golden")}/handlers/main.yml")
        file.contentsToByteArray(true)
        Files.writeString(path("golden", "handlers/main.yml"), "---\n# changed on disk only\n")
        val session = session("tasks", "golden")
        assertNotNull("the change on disk is seen", session.row("handlers/main.yml"))
    }
}
