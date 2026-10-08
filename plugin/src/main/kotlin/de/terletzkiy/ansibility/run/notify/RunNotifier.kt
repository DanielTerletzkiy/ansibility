package de.terletzkiy.ansibility.run.notify

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationsConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SystemNotifications
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.settings.RunNotificationSettings
import de.terletzkiy.ansibility.run.settings.RunnerConfigurable
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.settings.RootKeys
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.lang.ref.WeakReference
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Where a run shows: its tab (found by [executor] and [handler]), its [console] with the Plays tab, and [key], which
 * names the run across reruns (its configuration; a bulk run's roles). A notification outlives its run in the
 * notification history, so the handler and the console (which hold the run's model) are held weakly: the open tab
 * keeps them.
 */
class RunTarget(val key: String, val executor: Executor, handler: ProcessHandler, console: AnsibleRunConsole?) {
    private val handlerRef = WeakReference(handler)
    private val consoleRef = console?.let(::WeakReference)

    val handler: ProcessHandler? get() = handlerRef.get()

    val console: AnsibleRunConsole? get() = consoleRef?.get()

    /** Whether the run's tab is gone: closed (or collected since). */
    val tabClosed: Boolean get() = consoleRef != null && console?.isDisposed != false
}

/** One action of a run's notification. */
class RunAction(@Nls val text: String, val run: () -> Unit)

/**
 * Tells the user how a run ended (plan amendment R19, D147–D149): a balloon of the group [GROUP_ID] ("Ansibility
 * runs", whose style the user sets in Settings › Notifications) unless [RunNotificationSettings] says otherwise or
 * the user watches the run's tab (the IDE is active, the run's tool window has the focus and shows this run), plus a
 * system notification with the title and counts only, which the platform shows while the IDE is in the background
 * (not when the user set the group to "No popup").
 * A run's notification replaces the previous one of the same [RunTarget.key]. EDT.
 */
@Service(Service.Level.PROJECT)
class RunNotifier(private val project: Project) {
    private val shown = HashMap<String, Notification>()

    /**
     * The run of [target] ended with [outcome]: shows its notification with the first [MAX_ACTIONS] of [actions], or
     * nothing. Returns the notification shown, or null.
     */
    fun runEnded(outcome: RunOutcome, target: RunTarget, actions: List<RunAction>): Notification? {
        ThreadingAssertions.assertEventDispatchThread()
        if (project.isDisposed) return null
        // Whatever this run says, the previous run's notification is out of date.
        shown.remove(target.key)?.expire()
        val text = RunNotificationTexts.of(outcome)
        val automatic = outcome is RunOutcome.Molecule && outcome.automatic
        val settings = RunNotificationSettings.getInstance()
        if (!shows(settings.whenToNotify, settings.notifyPassed, text.kind, automatic) { inView(target) }) return null
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(text.htmlTitle, text.htmlContent, text.kind.type)
            .setDisplayId("ansibility.run.${text.kind.name.lowercase()}")
        for (action in actions.take(MAX_ACTIONS)) {
            notification.addAction(NotificationAction.create(action.text) { _, clicked ->
                clicked.hideBalloon()
                action.run()
            })
        }
        shown[target.key] = notification
        notification.whenExpired { shown.remove(target.key, notification) }
        notification.notify(project)
        // A group the user set to "No popup" (Settings | Notifications) stays silent outside the IDE too.
        if (NotificationsConfiguration.getNotificationsConfiguration().getDisplayType(GROUP_ID) != NotificationDisplayType.NONE) {
            val system = systemForTests
            if (system != null) system(text.title, text.summary) else SystemNotifications.getInstance().notify(message("notification.group.runs"), text.title, text.summary)
        }
        return notification
    }

    /** "Show Run": the run's tab and its Plays tab in front; null when the tab was closed. */
    fun showRun(target: RunTarget): RunAction? =
        if (target.tabClosed) null else RunAction(message("run.notification.action.show")) { show(target, null) }

    /** "Show Failed Task": the run's tab with [task] selected in the Plays tab; null when the tab was closed. */
    fun showFailedTask(target: RunTarget, task: Any): RunAction? {
        if (target.tabClosed) return null
        val ref = WeakReference(task)
        return RunAction(message("run.notification.action.task")) { show(target, ref.get()) }
    }

    /**
     * "Run Again": the run's configuration again (the platform reuses the run's finished tab for it); null without a
     * configuration. Holds only the configuration, not the run.
     */
    fun runAgain(environment: ExecutionEnvironment): RunAction? {
        val settings = environment.runnerAndConfigurationSettings ?: return null
        val executor = environment.executor
        return RunAction(message("run.notification.action.again")) {
            val hook = runAgainForTests
            if (hook != null) hook(settings, executor) else ProgramRunnerUtil.executeConfiguration(settings, executor)
        }
    }

    /** "Open Runner Settings" of the root of [path] (a playbook, a role directory; null: the page as it opens). */
    fun openSettings(path: String?): RunAction = RunAction(message("run.notification.action.settings")) {
        val key = rootKeyOf(path)
        openSettingsForTests?.let { return@RunAction it(key) }
        ShowSettingsUtil.getInstance().editConfigurable(project, RunnerConfigurable(project, key))
    }

    /** Brings the run's tab to the front, then its Plays tab, with [select]'s row selected when given. */
    fun show(target: RunTarget, select: Any?) {
        if (target.tabClosed) return
        val manager = RunContentManager.getInstance(project)
        val handler = target.handler
        if (handler != null) manager.findContentDescriptor(target.executor, handler)?.let { descriptor ->
            manager.toFrontRunContent(target.executor, descriptor)
            manager.getToolWindowByDescriptor(descriptor)?.activate(null)
        }
        // After the tool window shows the run (it does so later on the EDT).
        ApplicationManager.getApplication().invokeLater({ target.console?.showPlays(select) }, ModalityState.nonModal(), project.disposed)
    }

    /** Whether the user watches the run's tab: the IDE is active, the run's tool window has the focus and shows the run. */
    private fun inView(target: RunTarget): Boolean {
        inViewForTests?.let { return it(target) }
        if (!ApplicationManager.getApplication().isActive) return false
        val manager = RunContentManager.getInstance(project)
        val handler = target.handler ?: return false
        val descriptor = manager.findContentDescriptor(target.executor, handler) ?: return false
        val window = manager.getToolWindowByDescriptor(descriptor) ?: return false
        if (!window.isVisible || !window.isActive) return false
        val content = descriptor.attachedContent ?: return false
        if (window.contentManager.selectedContent !== content) return false
        // The run's own window (the project's frame, or the tool window's when it floats) has the focus.
        return SwingUtilities.getWindowAncestor(window.component)?.isActive ?: false
    }

    /**
     * The settings key of the root [path] lies in (the root whose inventory its runs use), or null. A path that is gone
     * (a run's playbook was deleted) counts by its nearest existing parent.
     */
    private fun rootKeyOf(path: String?): String? {
        val start = path?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        val file = generateSequence(start) { it.parent }.firstNotNullOfOrNull { LocalFileSystem.getInstance().findFileByNioFile(it) } ?: return null
        return runReadActionBlocking {
            if (project.isDisposed) return@runReadActionBlocking null
            val own = AnsibleWorkspace.getInstance(project).rootFor(file) ?: return@runReadActionBlocking null
            val root = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(own) ?: own
            RootKeys.keyOf(project, root.dir)
        }
    }

    /** Replaces "is the user watching the run's tab" (tests). */
    @TestOnly
    var inViewForTests: ((RunTarget) -> Boolean)? = null

    /** Receives the system notifications' title and text instead of the platform (tests). */
    @TestOnly
    var systemForTests: ((String, String) -> Unit)? = null

    /** Replaces "Run Again" (tests). */
    @TestOnly
    var runAgainForTests: ((RunnerAndConfigurationSettings, Executor) -> Unit)? = null

    /** Replaces opening the Runner settings; gets the root's key (tests). */
    @TestOnly
    var openSettingsForTests: ((String?) -> Unit)? = null

    @TestOnly
    fun resetForTests() {
        shown.values.toList().forEach { it.expire() }
        shown.clear()
        inViewForTests = null
        systemForTests = null
        runAgainForTests = null
        openSettingsForTests = null
    }

    companion object {
        /** The notification group of run results: "Ansibility runs" (`ansibility-run.xml`). */
        const val GROUP_ID = "Ansibility.Runs"

        /** The actions a notification offers at most; the platform folds more into a menu. */
        const val MAX_ACTIONS = 3

        fun getInstance(project: Project): RunNotifier = project.service()

        /**
         * Whether a run's end notifies: never with [When.NEVER]; a passed run only with [notifyPassed]; an [automatic]
         * destroy only when it did not pass; with [When.NOT_IN_VIEW] only while the user does not watch the run's tab
         * ([inView] is asked last, and only then).
         */
        fun shows(
            whenToNotify: RunNotificationSettings.When,
            notifyPassed: Boolean,
            kind: RunNotificationKind,
            automatic: Boolean,
            inView: () -> Boolean,
        ): Boolean {
            if (kind == RunNotificationKind.PASSED && (automatic || !notifyPassed)) return false
            return when (whenToNotify) {
                RunNotificationSettings.When.NEVER -> false
                RunNotificationSettings.When.ALWAYS -> true
                RunNotificationSettings.When.NOT_IN_VIEW -> !inView()
            }
        }
    }
}
