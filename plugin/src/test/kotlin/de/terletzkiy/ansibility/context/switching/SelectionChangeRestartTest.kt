package de.terletzkiy.ansibility.context.switching

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.context.host.HostModeRestarts
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.model.inventory.ModelCaches

/**
 * D32: switching the Ansible context through the F8.1 UI never restarts inspections. Counted with the daemon's own
 * bookkeeping: a restart marks a file's highlighting dirty, so after the switches the open file must still be up to
 * date (the control restart at the end proves the probe sees a restart), [HostModeRestarts] did nothing (no host-mode
 * presentation is registered) and no model cache was recomputed.
 */
@RequiresInfraFixture
class SelectionChangeRestartTest : ContextSwitchingTestCase() {
    private val daemon: DaemonCodeAnalyzerImpl get() = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl

    /** How many of [files] the daemon has to re-highlight (0: no restart touched them). */
    private fun dirty(files: List<VirtualFile>): Int = files.count { file ->
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        !daemon.fileStatusMap.allDirtyScopesAreNullFor(document)
    }

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Opens [file] in the editor and highlights it until the daemon considers it up to date. */
    private fun highlight(file: VirtualFile) {
        myFixture.configureFromExistingVirtualFile(file)
        repeat(HIGHLIGHT_ROUNDS) {
            myFixture.doHighlighting()
            settle()
            if (dirty(listOf(file)) == 0) return
        }
        val status = daemon.fileStatusMap.toString(FileDocumentManager.getInstance().getDocument(file)!!)
        fail("highlighting is not up to date before the switches: $status")
    }

    fun testSwitchingTheContextRestartsNoInspection() {
        // The light fixture keeps one editor open; the daemon tracks the file of that editor.
        val files = listOf(vf(prod1Vars))
        highlight(files.single())
        // One warm-up pass computes what the popups list (environments, hosts, addresses, play hits) once.
        val environments = children(ContextPopupGroup(root("repos/falcon/ansible"), null, emptyList())).first() as ActionGroup
        texts(children(environments))
        texts(children(SwitchContextGroup(null)))
        val restarts = HostModeRestarts.getInstance(project).restartCount
        val models = ModelCaches.getInstance(project).snapshot()

        perform(child(environments, "prod"))
        perform(child(SwitchContextGroup(null), "falcon › test › test-test1"))
        ContextSwitcher.setFollowEditor(project, false)
        ContextSwitcher.setFollowEditor(project, true)
        perform(child(SwitchContextGroup(null), "falcon › All environments"))
        settle()

        assertEquals("no switch marked an open file dirty", 0, dirty(files))
        assertEquals("no host-mode presentation is registered, so nothing restarts", restarts, HostModeRestarts.getInstance(project).restartCount)
        assertEquals("switching recomputes no model cache", emptyMap<String, Long>(), ModelCaches.getInstance(project).snapshot().computationsSince(models))

        daemon.restart(PsiManager.getInstance(project).findFile(files.first())!!, "control")
        assertEquals("the probe sees a restart", 1, dirty(files))
    }

    private companion object {
        const val POLL_MILLIS = 20L
        const val SETTLE_ROUNDS = 15
        const val HIGHLIGHT_ROUNDS = 5
    }
}
