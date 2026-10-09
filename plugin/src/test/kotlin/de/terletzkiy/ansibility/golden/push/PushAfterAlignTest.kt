package de.terletzkiy.ansibility.golden.push

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import de.terletzkiy.ansibility.golden.GoldenLocalTestCase
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.align.AlignRequest
import de.terletzkiy.ansibility.golden.align.AlignResolution
import de.terletzkiy.ansibility.golden.align.AlignService
import de.terletzkiy.ansibility.golden.align.AlignTestSupport
import de.terletzkiy.ansibility.golden.align.ScriptedMergeDialog
import de.terletzkiy.ansibility.model.drift.DriftFixture
import de.terletzkiy.ansibility.model.drift.RoleDriftService

/**
 * Align, then Push from its notification (plan amendment R24, D187/D188): the notification's Push Role to Repos… passes the
 * aligned copy as `ROLE_COPY`, and the Push dialog's plans and badges are computed when it opens, so they include what
 * Align just wrote, also while the merged document is still unsaved (the Conflicts dialog's own merge model).
 */
class PushAfterAlignTest : GoldenLocalTestCase() {
    private lateinit var ui: RecordingPushUi

    override fun setUp() {
        super.setUp()
        ui = RecordingPushUi()
        PushTestSupport.install(ui, testRootDisposable)
    }

    override fun tearDown() {
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testPushFromAJustAlignedGoldenOffersTheOtherCopiesWithFreshCountsAndBadges() {
        // The drift known before the alignment: mol and mol2 differ in molecule/default/verify.yml, same does not.
        DriftFixture.drift(RoleDriftService.getInstance(project), "web")
        val notifications = AlignTestSupport.notifications(project, testRootDisposable)
        val dialog = ScriptedMergeDialog { project, session ->
            // As the Conflicts dialog's merge model does: the target document takes the source text, saved later.
            val row = session.row("molecule/default/verify.yml")!!
            val document = FileDocumentManager.getInstance().getDocument(row.file)!!
            val text = String(bytes("mol", "molecule/default/verify.yml"), Charsets.UTF_8)
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
            session.resolved(listOf(row), AlignResolution.TAKEN_FROM_SOURCE)
        }
        AlignTestSupport.install(listOf(dialog), testRootDisposable)
        val session = GoldenTestSupport.await { AlignService.getInstance(project).session(AlignRequest(roleCopy("golden"), roleCopy("mol"))) }
        AlignService.getInstance(project).run(session)
        val summary = notifications.last()
        assertEquals("Aligned web in golden: 1 taken from mol", summary.content)

        var offered: List<PushRow>? = null
        ui.pick = { rows -> offered = rows; null }
        AlignTestSupport.click(project, summary, "Push Role to Repos…")
        GoldenTestSupport.waitFor("the Push dialog") { offered != null }

        assertEquals("Push gets the aligned copy", roleDir("golden"), ui.models.single().source.dir)
        val rows = offered!!.associateBy { it.name }
        assertEquals("nothing to do", rows.getValue("mol").countsText(PushOptions()))
        assertEquals("nothing to do", rows.getValue("mol2").countsText(PushOptions()))
        assertEquals("1 changed", rows.getValue("same").countsText(PushOptions()))
        assertEquals("the badges are as fresh as the counts", "= golden", rows.getValue("mol").badge)
        assertEquals("≈ molecule only", rows.getValue("same").badge)
    }
}
