package de.terletzkiy.ansibility.run.notify

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationsConfiguration
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import de.terletzkiy.ansibility.run.AnsiblePlaybookConfigurationType
import de.terletzkiy.ansibility.run.events.RunEventDecoder
import de.terletzkiy.ansibility.run.molecule.MoleculeCommand
import de.terletzkiy.ansibility.run.settings.RunNotificationSettings
import de.terletzkiy.ansibility.run.settings.RunNotificationSettings.When
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.view.AnsibleRunView
import de.terletzkiy.ansibility.run.view.RunEventCollector
import de.terletzkiy.ansibility.run.view.RunViewActions
import java.nio.file.Files
import java.nio.file.Path

/**
 * When a run's end notifies and what the notification offers (plan amendment R19, D147–D149): the settings and "in
 * view" matrix, the group, type and actions, one notification per run key, the system notification, the actions.
 */
class RunNotifierTest : BasePlatformTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityNotifierTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var recorded: RecordedNotifications
    private val notifier get() = RunNotifier.getInstance(project)
    private val settings get() = RunNotificationSettings.getInstance()
    private val asked = ArrayList<String>()
    private var watching = false

    override fun setUp() {
        super.setUp()
        recorded = RecordedNotifications(project, testRootDisposable)
        notifier.inViewForTests = { target ->
            asked += target.key
            watching
        }
    }

    private fun target(key: String = "site", console: AnsibleRunConsole? = null) =
        RunTarget(key, DefaultRunExecutor.getRunExecutorInstance(), NopProcessHandler(), console)

    private val passed = RunOutcomes.playbook("site.yml [staging]", null, 0, false, 3.0)
    private val failed = RunOutcomes.playbook("site.yml [staging]", null, 2, false, 3.0)

    private fun destroy(exitCode: Int) = RunOutcomes.molecule("web › default", MoleculeCommand.DESTROY, null, exitCode, false, 3.0, automatic = true)

    private fun actions(vararg texts: String) = texts.map { text -> RunAction(text) { ran += text } }
    private val ran = ArrayList<String>()

    fun testTheSettingsDecideWhenARunNotifies() {
        val kinds = RunNotificationKind.entries
        for (whenToNotify in When.entries) for (notifyPassed in listOf(true, false)) for (kind in kinds) for (automatic in listOf(true, false)) for (inView in listOf(true, false)) {
            var askedInView = false
            val shows = RunNotifier.shows(whenToNotify, notifyPassed, kind, automatic) { askedInView = true; inView }
            val hidden = kind == RunNotificationKind.PASSED && (automatic || !notifyPassed)
            val expected = !hidden && when (whenToNotify) {
                When.NEVER -> false
                When.ALWAYS -> true
                When.NOT_IN_VIEW -> !inView
            }
            assertEquals("$whenToNotify $notifyPassed $kind $automatic $inView", expected, shows)
            if (whenToNotify != When.NOT_IN_VIEW || hidden) assertFalse("only asked when it matters", askedInView)
        }
    }

    fun testANotificationSaysWhatHappenedAndOffersThreeActions() {
        val shown = notifier.runEnded(failed, target(), actions("One", "Two", "Three", "Four"))!!
        assertSame(shown, recorded.runs.single())
        assertEquals("Ansibility.Runs", shown.groupId)
        assertEquals("ansibility.run.failed", shown.displayId)
        assertEquals(NotificationType.ERROR, shown.type)
        assertEquals("site.yml [staging]: failed (exit code 2)", shown.title)
        assertTrue(shown.content, shown.content.startsWith("3.0 s · the console shows why"))
        assertEquals("at most three actions", listOf("One", "Two", "Three"), RecordedNotifications.texts(shown))
        assertEquals("the system notification: the title and counts", listOf("site.yml [staging]: failed (exit code 2)" to "exit code 2 · 3.0 s"), recorded.system)
        assertEquals("not watching: asked once", listOf("site"), asked)

        recorded.click(shown, "Two")
        assertEquals(listOf("Two"), ran)
        assertFalse("the notification stays in the history", shown.isExpired)

        val ok = notifier.runEnded(passed, target("other"), emptyList())!!
        assertEquals(NotificationType.INFORMATION, ok.type)
        assertEquals("ansibility.run.passed", ok.displayId)
    }

    fun testAGroupSetToNoPopupStaysSilentOutsideTheIdeToo() {
        val configuration = NotificationsConfiguration.getNotificationsConfiguration()
        val before = configuration.getDisplayType(RunNotifier.GROUP_ID)
        try {
            // Settings | Appearance & Behavior | Notifications: "Ansibility runs" shows no popup.
            configuration.setDisplayType(RunNotifier.GROUP_ID, NotificationDisplayType.NONE)
            assertNotNull("the notification still goes to the history", notifier.runEnded(failed, target(), emptyList()))
            assertTrue("no system notification either", recorded.system.isEmpty())
            configuration.setDisplayType(RunNotifier.GROUP_ID, NotificationDisplayType.STICKY_BALLOON)
            assertNotNull(notifier.runEnded(failed, target(), emptyList()))
            assertEquals("any other style keeps it", 1, recorded.system.size)
        } finally {
            configuration.setDisplayType(RunNotifier.GROUP_ID, before)
        }
    }

    fun testNothingShowsWhileTheUserWatchesTheRunOrWhenTheSettingsSaySo() {
        watching = true
        assertNull("watching the run's tab", notifier.runEnded(failed, target(), emptyList()))
        settings.whenToNotify = When.ALWAYS
        assertNotNull("always", notifier.runEnded(failed, target(), emptyList()))
        settings.whenToNotify = When.NEVER
        asked.clear()
        assertNull(notifier.runEnded(failed, target(), emptyList()))
        assertTrue("never: not even asked", asked.isEmpty())

        watching = false
        settings.whenToNotify = When.NOT_IN_VIEW
        settings.notifyPassed = false
        assertNull("passed runs only on request", notifier.runEnded(passed, target(), emptyList()))
        assertNotNull("failures always", notifier.runEnded(failed, target(), emptyList()))
        assertEquals(2, recorded.runs.size)
        assertEquals("one system notification per balloon", 2, recorded.system.size)
    }

    fun testAnAutomaticDestroyNotifiesOnlyWhenItFails() {
        assertNull(notifier.runEnded(destroy(0), target(), emptyList()))
        val failedDestroy = notifier.runEnded(destroy(1), target(), emptyList())!!
        assertEquals("Molecule destroy failed: web › default", failedDestroy.title)
        val manual = RunOutcomes.molecule("web › default", MoleculeCommand.DESTROY, null, 0, false, 3.0, automatic = false)
        assertEquals("Molecule destroy finished: web › default", notifier.runEnded(manual, target("manual"), emptyList())!!.title)
    }

    fun testARerunReplacesTheNotificationOfTheSameRun() {
        val first = notifier.runEnded(failed, target("site"), emptyList())!!
        val other = notifier.runEnded(failed, target("db"), emptyList())!!
        val second = notifier.runEnded(passed, target("site"), emptyList())!!
        assertTrue("the same run's older notification goes", first.isExpired)
        assertFalse(other.isExpired)
        assertFalse(second.isExpired)
        // Out of date even when the new end does not notify.
        settings.whenToNotify = When.NEVER
        assertNull(notifier.runEnded(failed, target("site"), emptyList()))
        assertTrue(second.isExpired)
    }

    fun testShowFailedTaskSelectsTheTaskInThePlaysTab() {
        val collector = RunEventCollector(testRootDisposable)
        Files.readAllLines(Path.of("src/test/testData/run-events/failure/2.21.4.jsonl")).forEach { RunEventDecoder.decode(it)?.let(collector::accept) }
        collector.finish(2)
        collector.drainForTests()
        val view = AnsibleRunView(project, collector, RunViewActions.NONE) { null }
        val console = AnsibleRunConsole(TextConsoleBuilderFactory.getInstance().createBuilder(project).console, view)
        val disposable = Disposer.newDisposable("tab")
        Disposer.register(testRootDisposable, disposable)
        Disposer.register(disposable, console)
        val target = target(console = console)
        val task = RunOutcomes.failedTask(collector.model)!!
        assertEquals("the first failed host's task", "Fails everywhere", task.name)

        notifier.showFailedTask(target, task)!!.run()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertSame(task, view.selectedTask())
        assertEquals("Show Run", notifier.showRun(target)!!.text)

        Disposer.dispose(disposable)
        assertTrue(console.isDisposed)
        assertNull("the tab is gone: nothing to show", notifier.showRun(target))
        assertNull(notifier.showFailedTask(target, task))
    }

    fun testRunAgainRestartsTheRun() {
        val settings = RunManager.getInstance(project).createConfiguration("site.yml", AnsiblePlaybookConfigurationType.getInstance().factory)
        val environment = ExecutionEnvironment(DefaultRunExecutor.getRunExecutorInstance(), TestRunner, settings, project)
        val restarted = ArrayList<Pair<RunnerAndConfigurationSettings, Executor>>()
        notifier.runAgainForTests = { configuration, executor -> restarted += configuration to executor }
        val again = notifier.runAgain(environment)!!
        assertEquals("Run Again", again.text)
        again.run()
        assertEquals("the configuration again, nothing of the run held", listOf(settings to DefaultRunExecutor.getRunExecutorInstance()), restarted)
        val profileOnly = ExecutionEnvironmentBuilder(project, DefaultRunExecutor.getRunExecutorInstance()).runProfile(settings.configuration).runner(TestRunner).build()
        assertNull("no configuration, nothing to run again", notifier.runAgain(profileOnly))
    }

    fun testTheSettingsAreStoredAndRestored() {
        assertEquals("the defaults", When.NOT_IN_VIEW, settings.whenToNotify)
        assertTrue(settings.notifyPassed)
        settings.whenToNotify = When.NEVER
        settings.notifyPassed = false
        val stored = XmlSerializer.serialize(settings.state)
        val restored = RunNotificationSettings()
        restored.loadState(XmlSerializer.deserialize(stored, RunNotificationSettings.Options::class.java))
        assertEquals(When.NEVER, restored.whenToNotify)
        assertFalse(restored.notifyPassed)
        settings.loadState(RunNotificationSettings.Options())
        assertEquals(When.NOT_IN_VIEW, settings.whenToNotify)
        assertTrue(settings.notifyPassed)
    }
}
