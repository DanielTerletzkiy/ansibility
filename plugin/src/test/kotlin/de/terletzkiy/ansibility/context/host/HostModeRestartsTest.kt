package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * [HostModeRestarts] (plan amendment R7/R8, A.9 change 6): a selection change restarts the daemon only while host-mode
 * presentation is registered, and only for the open files of the root whose selection changed.
 */
class HostModeRestartsTest : HostContextTestCase() {
    private val restarts: HostModeRestarts get() = HostModeRestarts.getInstance(project)

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting until $what")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(POLL_MILLIS)
        }
    }

    fun testSelectionChangesRestartOnlyTheOpenFilesOfTheirRoot() {
        val editors = FileEditorManager.getInstance(project)
        val falconFile = vf(FALCON_PROD_ALL)
        val platformFile = vf("$PLATFORM/environments/prod/hosts.yml")
        editors.openFile(falconFile, false)
        editors.openFile(platformFile, false)
        try {
            val start = restarts.restartCount
            context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("prod")))
            settle()
            assertEquals("nothing is restarted while no host-mode presentation is registered", start, restarts.restartCount)

            val registration = Disposer.newDisposable(testRootDisposable, "host-mode presentation")
            restarts.register(registration)
            context.setSelection(root(FALCON), RootContext(EnvironmentChoice.Named("test")))
            waitFor("the falcon file is restarted") { restarts.restartCount == start + 1 }
            settle()
            assertEquals("only falcon's open file", start + 1, restarts.restartCount)

            context.setSelection(root(PLATFORM), RootContext(EnvironmentChoice.Named("prod")))
            waitFor("the platform file is restarted") { restarts.restartCount == start + 2 }

            Disposer.dispose(registration)
            context.setSelection(root(FALCON), RootContext.DEFAULT)
            settle()
            assertEquals(start + 2, restarts.restartCount)
        } finally {
            editors.closeFile(falconFile)
            editors.closeFile(platformFile)
        }
    }

    private companion object {
        const val POLL_MILLIS = 20L
        const val SETTLE_ROUNDS = 15
        const val TIMEOUT_MILLIS = 30_000L
    }
}
