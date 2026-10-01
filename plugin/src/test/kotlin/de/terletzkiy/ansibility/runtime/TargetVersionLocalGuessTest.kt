package de.terletzkiy.ansibility.runtime

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.api.LocalAnsibleInstall
import de.terletzkiy.ansibility.api.LocalAnsibleRuntime
import de.terletzkiy.ansibility.context.ContextPresentation
import de.terletzkiy.ansibility.context.TargetVersion
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.context.TargetVersionSource
import de.terletzkiy.ansibility.semantics.CoreVersion

/** D10 step 4: with no pin anywhere, the target is guessed from the local install, without blocking. */
class TargetVersionLocalGuessTest : RuntimeTestCase() {
    private val local = FakeLocalRuntime()
    private val detector: TargetVersionDetector get() = TargetVersionDetector.getInstance(project)

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().replaceService(LocalAnsibleRuntime::class.java, local, testRootDisposable)
        detector.overrideFor = { null }
    }

    fun testUnknownUntilTheProbeAnswers() {
        val root = createRoot("site")
        assertEquals(TargetVersion.UNKNOWN, detector.targetVersion(root))
        assertTrue("the first lookup starts the probe", local.requests >= 1)

        local.answer(LocalAnsibleInstall(CoreVersion(2, 21, 4), "/opt/homebrew/bin/ansible"))
        val guessed = detector.targetVersion(root)
        assertEquals(CoreVersion(2, 21, 4), guessed.version)
        assertEquals(TargetVersionSource.LOCAL_GUESSED, guessed.source)
        assertEquals("/opt/homebrew/bin/ansible", guessed.detail)
        assertTrue(guessed.guessed)
        assertEquals("2.21.4 (target guessed from local install /opt/homebrew/bin/ansible)", ContextPresentation.targetText(guessed))
    }

    fun testPinsWinOverTheLocalInstall() {
        local.answer(LocalAnsibleInstall(CoreVersion(2, 21, 4), "/opt/homebrew/bin/ansible"))
        val pinned = createRoot("pinned", mapOf("docker/ansible-playbook/Dockerfile" to "RUN pip install \"ansible-core==2.18.8\"\n"))
        val unpinned = createRoot("unpinned")
        assertEquals(TargetVersionSource.DOCKERFILE, detector.targetVersion(pinned).source)
        assertEquals("the majority pin beats the local guess", TargetVersionSource.MAJORITY, detector.targetVersion(unpinned).source)
        assertEquals(CoreVersion.PINNED, detector.targetVersion(unpinned).version)
    }

    fun testTheLocalGuessIsAskedOnlyWhenNeeded() {
        var asked = 0
        val guess = { asked++; TargetVersion(CoreVersion(2, 21, 4), TargetVersionSource.LOCAL_GUESSED, "x") }
        val root = createRoot("site")
        val settings = TargetVersionDetector.resolve(listOf(root), { CoreVersion(2, 19, 0) }, { emptyList() }, guess)
        assertEquals(TargetVersionSource.SETTINGS, settings.getValue(root.dir).source)
        assertEquals(0, asked)
        val guessed = TargetVersionDetector.resolve(listOf(root), { null }, { emptyList() }, guess)
        assertEquals(TargetVersionSource.LOCAL_GUESSED, guessed.getValue(root.dir).source)
        assertEquals(1, asked)
    }

    fun testDocsFollowTheGuessedTarget() {
        val root = createRoot("site")
        local.answer(LocalAnsibleInstall(CoreVersion(2, 21, 4), "/opt/homebrew/bin/ansible"))
        val source = de.terletzkiy.ansibility.api.AnsibleDocService.getInstance(project).primarySource(root)
        assertEquals(CoreVersion(2, 21, 4), source.core)
        assertTrue(source.matchesTarget)
    }

    /** A [LocalAnsibleRuntime] whose probe answers when the test says so. */
    private class FakeLocalRuntime : LocalAnsibleRuntime {
        private val tracker = SimpleModificationTracker()

        @Volatile
        private var install: LocalAnsibleInstall? = null
        var requests = 0

        fun answer(value: LocalAnsibleInstall) {
            install = value
            tracker.incModificationCount()
        }

        override fun localInstall(project: Project): LocalAnsibleInstall? {
            requests++
            return install
        }

        override val probeTracker: ModificationTracker get() = tracker
    }
}
