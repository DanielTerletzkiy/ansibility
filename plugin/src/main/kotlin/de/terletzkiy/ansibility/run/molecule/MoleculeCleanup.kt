package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.notify.RunAction
import de.terletzkiy.ansibility.run.view.BannerAction
import de.terletzkiy.ansibility.run.view.RunBanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Destroys the instances a Molecule run leaves (Converge, Verify, Idempotence) after a grace period unless the user
 * keeps them (plan amendment R15). A role has one countdown at most: a new run of the role cancels it, so instances are
 * never destroyed under a running test, and that run starts its own when it ends; a run that then does not start puts
 * it back ([runNotStarted]). The destroys it launches ([destroy]) are tracked until they ended: a run of the role waits
 * for them before it starts ([awaitDestroys]), so a new run never works on instances a destroy removes. A destroy
 * counts from when it is marked (a stopped test marks its own before its run reports the end, [destroyLaunching]),
 * is handed to the platform once no modal dialog holds it back, is under way once its configuration's run took it
 * (whatever the platform then waits for, such as indexing), and ends with its run, with the platform's word that the
 * run did not start, or when the platform never takes it. Closing the project cancels every countdown (the instances
 * stay). EDT, except where a method says otherwise.
 */
@Service(Service.Level.PROJECT)
class MoleculeCleanup(private val project: Project) : Disposable {
    /** Role directory → its countdown, counting or kept. */
    private val countdowns = HashMap<String, MoleculeCountdown>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Role directory → the destroys launched for it that have not ended, oldest first. Guarded by itself; any thread. */
    private val destroys = HashMap<String, MutableList<PendingDestroy>>()

    /**
     * A countdown a run's start cancelled ([runStarting]): what [runNotStarted] puts back when that run does not start.
     * [banner] is the tab of the run that started the countdown, [root] the display name of the role's root that the
     * countdown names (null: the workspace has one root).
     */
    class Rearm internal constructor(
        val spec: MoleculeSpec,
        val minutes: Int,
        val kept: Boolean,
        internal val banner: RunBanner?,
        internal val root: String?,
    )

    /**
     * A destroy of [spec]'s role that [destroy] launches and that has not ended: runs of the role wait for it. It
     * counts from when it is marked ([destroyLaunching]); it is handed to the platform ([destroyHandedOver]), its run
     * takes it ([destroyLaunched]) and it ends ([destroyEnded]). Any thread.
     */
    class PendingDestroy internal constructor(val spec: MoleculeSpec) {
        /** When it was handed to the platform ([System.nanoTime]); meaningful once [handedOver] completed. */
        @Volatile
        internal var handedAt = 0L
        internal val handedOver = CompletableDeferred<Unit>()
        internal val launched = CompletableDeferred<Unit>()
        internal val done = CompletableDeferred<Unit>()
    }

    init {
        // The platform's word that a destroy's run did not start (a before-run task failed, it could not run on its
        // target, ...): its configuration's run took the destroy into its environment.
        project.messageBus.connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) = notStarted(env)

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) = notStarted(env)

            private fun notStarted(env: ExecutionEnvironment) {
                env.getUserData(MoleculeConfiguration.PENDING_DESTROY)?.let(::destroyEnded)
            }
        })
    }

    /**
     * A run of [spec] starts: its role's countdown (or kept banner) goes, before the run goes on. Returns what it was,
     * for [runNotStarted] when the run then does not start; null when the role had none. Any thread.
     */
    fun runStarting(spec: MoleculeSpec): Rearm? {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            var rearm: Rearm? = null
            application.invokeAndWait({ rearm = runStarting(spec) }, ModalityState.any())
            return rearm
        }
        val countdown = countdowns.remove(spec.roleDir) ?: return null
        val rearm = countdown.rearm()
        countdown.cancel()
        return rearm
    }

    /**
     * A run whose start cancelled [rearm]'s countdown did not start its process (its preparation failed, a question
     * was cancelled, Stop came while it prepared): the instances are still there, so a fresh countdown of the same
     * minutes (kept when it was kept) takes its place, in the tab of the run that started it, with a [balloon] of its
     * own unless the caller shows its links ([MoleculeCountdown.attach], else [MoleculeCountdown.showBalloon]). Nothing
     * when the role counts down again, a destroy of it runs or another run of it runs (that run counts down when it
     * ends). Returns the new countdown, or null.
     */
    fun runNotStarted(rearm: Rearm?, balloon: Boolean = true): MoleculeCountdown? {
        ThreadingAssertions.assertEventDispatchThread()
        if (rearm == null || project.isDisposed) return null
        val roleDir = rearm.spec.roleDir
        if (roleDir in countdowns || isDestroying(roleDir) || MoleculeResults.getInstance(project).isRunning(roleDir)) return null
        val countdown = MoleculeCountdown(project, rearm.spec, now() + rearm.minutes * 60_000L, rearm.minutes, rearm.banner, ::now, rearm.root) { done -> countdowns.remove(roleDir, done) }
        countdowns[roleDir] = countdown
        countdown.start(balloon = balloon && !rearm.kept)
        if (rearm.kept) countdown.keep()
        scheduleTick()
        return countdown
    }

    /**
     * A destroy of [spec]'s role is about to launch: runs of the role wait for it from now, until it ended, however
     * long it takes to be handed to the platform ([destroyHandedOver]; the project's end ends it otherwise). Any thread.
     */
    internal fun destroyLaunching(spec: MoleculeSpec): PendingDestroy {
        val pending = PendingDestroy(spec)
        synchronized(destroys) { destroys.getOrPut(spec.roleDir, ::ArrayList) += pending }
        return pending
    }

    /**
     * [pending] was handed to the platform ([MoleculeLauncher.run] returned): its run takes it soon, else it never
     * will ([awaitDestroys] stops waiting for it after the launch timeout). Any thread.
     */
    internal fun destroyHandedOver(pending: PendingDestroy) {
        pending.handedAt = System.nanoTime()
        pending.handedOver.complete(Unit)
    }

    /**
     * [pending]'s run took it (its configuration's `getState`): it is under way, whatever the platform still waits for
     * (indexing, before-run tasks); runs of the role wait until it ended, however long. Any thread.
     */
    internal fun destroyLaunched(pending: PendingDestroy) {
        pending.launched.complete(Unit)
    }

    /** [pending] ended (or ended not started, or never launches): runs of the role go on. Any thread, more than once. */
    internal fun destroyEnded(pending: PendingDestroy) {
        synchronized(destroys) {
            val list = destroys[pending.spec.roleDir]
            if (list != null && list.remove(pending) && list.isEmpty()) destroys.remove(pending.spec.roleDir)
        }
        pending.handedOver.complete(Unit)
        pending.launched.complete(Unit)
        pending.done.complete(Unit)
    }

    /** Whether a destroy of the role at [roleDir] was launched and has not ended. Any thread. */
    fun isDestroying(roleDir: String): Boolean = synchronized(destroys) { destroys[roleDir].orEmpty().isNotEmpty() }

    /**
     * Returns once no destroy of the role at [roleDir] runs; [waiting] hears of each destroy it waits for. A destroy
     * not yet handed to the platform (a modal dialog holds its launch back, or the stopped test that marked it has not
     * reported its end on the EDT yet) will be. One that its run has not taken [launchTimeoutMillis] after it was
     * handed over never will be (the platform dropped it): it no longer counts. Cancellable (Stop ends the wait). Off
     * the EDT.
     */
    internal suspend fun awaitDestroys(roleDir: String, waiting: (MoleculeSpec) -> Unit) {
        var told: PendingDestroy? = null
        while (true) {
            val pending = synchronized(destroys) { destroys[roleDir]?.firstOrNull() } ?: return
            if (pending !== told) {
                waiting(pending.spec)
                told = pending
            }
            if (!pending.launched.isCompleted) pending.handedOver.await()
            val left = launchTimeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.handedAt)
            val launched = pending.launched.isCompleted || (left > 0 && withTimeoutOrNull(left) { pending.launched.await() } != null)
            if (!launched) {
                LOG.info("The Molecule destroy of ${pending.spec.roleName} did not start within ${launchTimeoutMillis / 1000} s; runs of the role no longer wait for it")
                destroyEnded(pending)
                continue
            }
            pending.done.await()
        }
    }

    private val launchTimeoutMillis: Long get() = launchTimeoutMillisForTests ?: LAUNCH_TIMEOUT_MILLIS

    /**
     * A run of [spec] ended: when its command leaves instances and [minutes] > 0, counts down to `molecule destroy`
     * in [banner] and, with [balloon], a balloon of its own (without, the run's end notification carries its links:
     * [MoleculeCountdown.attach], else [MoleculeCountdown.showBalloon]). A test the user [stopped] could not clean up
     * after itself: its instances are destroyed right away (whatever [minutes] says: a test always destroys its
     * instances; [pending] is that destroy when the run marked it already), a stopped "keep instances" test counts
     * down like a converge. The countdown names the role's [root] (the workspace has several). Returns the countdown,
     * or null.
     */
    fun runEnded(
        spec: MoleculeSpec,
        minutes: Int,
        banner: RunBanner?,
        stopped: Boolean = false,
        balloon: Boolean = true,
        root: String? = null,
        pending: PendingDestroy? = null,
    ): MoleculeCountdown? {
        ThreadingAssertions.assertEventDispatchThread()
        if (stopped && spec.command == MoleculeCommand.TEST) {
            banner?.show(message("molecule.cleanup.stopped", subject(spec)), emptyList())
            destroy(project, spec, pending = pending)
            return null
        }
        // Only a stopped test destroys.
        pending?.let(::destroyEnded)
        val leaves = spec.command.leavesInstances || (stopped && spec.command == MoleculeCommand.TEST_KEEP)
        if (minutes <= 0 || !leaves) return null
        val countdown = MoleculeCountdown(project, spec, now() + minutes * 60_000L, minutes, banner, ::now, root) { done -> countdowns.remove(spec.roleDir, done) }
        countdowns.put(spec.roleDir, countdown)?.cancel()
        countdown.start(balloon)
        scheduleTick()
        return countdown
    }

    /** The countdown of the role at [roleDir], counting or kept (tests, the run view). */
    fun countdownOf(roleDir: String): MoleculeCountdown? = countdowns[roleDir]

    /** Counts every countdown down once (the alarm does so every second). */
    fun tick() {
        countdowns.values.toList().forEach { it.tick() }
        if (countdowns.values.any { it.state == MoleculeCountdown.State.COUNTING }) scheduleTick()
    }

    private fun scheduleTick() {
        if (alarm.isDisposed || alarm.activeRequestCount > 0) return
        alarm.addRequest(::tick, TICK_MILLIS, ModalityState.any())
    }

    private fun now(): Long = (clockForTests ?: System::currentTimeMillis)()

    override fun dispose() {
        countdowns.values.toList().forEach { it.cancel() }
        countdowns.clear()
        // Nothing waits for a destroy any more (the project closes, or a test ends).
        synchronized(destroys) { destroys.values.flatten().also { destroys.clear() } }.forEach(::destroyEnded)
    }

    /** Cancels every countdown and forgets the destroys in flight (tests: the project outlives a test). */
    @TestOnly
    fun resetForTests() {
        dispose()
        launchTimeoutMillisForTests = null
    }

    /** Replaces the clock (tests move time on and call [tick]). */
    @TestOnly
    @Volatile
    internal var clockForTests: (() -> Long)? = null

    /** Replaces how long a destroy may take to get under way before runs of its role stop waiting for it (tests). */
    @TestOnly
    @Volatile
    internal var launchTimeoutMillisForTests: Long? = null

    companion object {
        private const val TICK_MILLIS = 1000

        /** How long a destroy may take to get under way (its run's tab exists) before it counts as never started. */
        private const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        private val LOG = logger<MoleculeCleanup>()

        fun getInstance(project: Project): MoleculeCleanup = project.service()

        /**
         * "web › default", "web (all scenarios)"; with the role's [root] (when the workspace has several roots) "web
         * (tern) › default", "web (tern, all scenarios)".
         */
        fun subject(spec: MoleculeSpec, root: String? = null): String = when {
            root != null && spec.scenario.isBlank() -> message("run.notification.molecule.subject.root.all", spec.roleName, root)
            root != null -> message("run.notification.molecule.subject.root", spec.roleName, spec.scenario, root)
            spec.scenario.isBlank() -> message("molecule.cleanup.subject.all", spec.roleName)
            else -> message("molecule.cleanup.subject", spec.roleName, spec.scenario)
        }

        /**
         * Runs `molecule destroy` of [spec]'s role and scenario the way [spec] ran (locally or in its Compose service,
         * the role's `MOLECULE_RUN_ID`), in a run tab of its own and not under a modal dialog. An [automatic] destroy
         * (a countdown ran out, a test was stopped) notifies only when it fails (R19, D149). The destroy counts from
         * now, or from when [pending] was marked ([destroyLaunching]): a run of the role that starts before it ended
         * waits for it ([awaitDestroys]). Any thread.
         */
        fun destroy(project: Project, spec: MoleculeSpec, automatic: Boolean = true, pending: PendingDestroy? = null) {
            // The project's end ends every destroy that counts (MoleculeCleanup.dispose).
            if (project.isDisposed) return
            val destroy = spec.copy(command = MoleculeCommand.DESTROY)
            val cleanup = getInstance(project)
            val marked = pending ?: cleanup.destroyLaunching(destroy)
            ApplicationManager.getApplication().invokeLater(
                {
                    var handedOver = false
                    try {
                        MoleculeLauncher.run(project, destroy, automatic = automatic, pending = marked)
                        handedOver = true
                    } catch (e: Exception) {
                        LOG.warn("The Molecule destroy of ${destroy.roleName} could not be started", e)
                    } finally {
                        // Its run takes it soon, or never: nothing may wait for it for good.
                        if (handedOver) cleanup.destroyHandedOver(marked) else cleanup.destroyEnded(marked)
                    }
                },
                ModalityState.nonModal(),
                project.disposed,
            )
        }
    }
}

/**
 * One countdown to `molecule destroy` of [spec]'s role and scenario, shown in [banner] (the run's tab) and in a
 * notification with "Keep Instances" and "Destroy Now": the run's end notification ([attach], R19), else a balloon of
 * its own ([showBalloon]). Kept, the banner stays with "Destroy Now"; done (destroyed or cancelled), it hides. When
 * the countdown ends, its own balloon expires and the run's notification only hides (it stays in the notification
 * history, its links do nothing any more, and it holds neither this countdown nor the run's tab). The destroy runs
 * like the run did (locally or in its Compose service, the role's `MOLECULE_RUN_ID`), in a run tab of its own; at the
 * deadline it is an automatic one. EDT.
 */
class MoleculeCountdown internal constructor(
    private val project: Project,
    val spec: MoleculeSpec,
    val deadline: Long,
    val minutes: Int,
    /** The run tab's banner, until the countdown is done: then the run's tab may go without this countdown keeping it. */
    private var banner: RunBanner?,
    private val clock: () -> Long,
    /** The display name of the role's root, when the workspace has several ("web (tern) › default"). */
    private val root: String?,
    private val onDone: (MoleculeCountdown) -> Unit,
) {
    enum class State { COUNTING, KEPT, DESTROYED, CANCELLED }

    var state: State = State.COUNTING
        private set

    /** The countdown's own balloon, or null. */
    private var notification: Notification? = null

    /** The run's end notification that carries the countdown's links, or null. */
    private var runNotification: Notification? = null

    private val subject: String = MoleculeCleanup.subject(spec, root)

    /** The remaining time as `m:ss`. */
    val remaining: String
        get() {
            val seconds = ((deadline - clock()).coerceAtLeast(0) + 999) / 1000
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }

    internal fun start(balloon: Boolean) {
        render()
        if (balloon) showBalloon()
    }

    /** Shows the countdown's own balloon (R15): no run notification carries its links. */
    fun showBalloon() {
        if (state != State.COUNTING || notification != null) return
        notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message("molecule.cleanup.notification.title"), message("molecule.cleanup.notification", subject, minutes), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(message("molecule.cleanup.keep")) { keep() })
            .addAction(NotificationAction.createSimpleExpiring(message("molecule.cleanup.destroy.now")) { destroyNow() })
            .also { it.notify(project) }
    }

    /**
     * "Keep Instances" and "Destroy Now" for the run's end notification (R19). They reach this countdown weakly: the
     * notification stays in the history after the countdown ended, and must not keep it, its banner and so the run's
     * closed tab with the run's model. While it counts or is kept, [MoleculeCleanup] holds it.
     */
    fun notificationActions(): List<RunAction> {
        val countdown = WeakReference(this)
        return listOf(
            RunAction(message("molecule.cleanup.keep")) { countdown.get()?.keep() },
            RunAction(message("molecule.cleanup.destroy.now")) { countdown.get()?.destroyNow() },
        )
    }

    /** The run's end notification carries [notificationActions] (R19): it hides when the countdown ends. */
    fun attach(notification: Notification) {
        runNotification = notification
        if (state != State.COUNTING) notification.hideBalloon()
    }

    /** Updates the remaining time; at the deadline, destroys (an automatic destroy). */
    fun tick() {
        if (state != State.COUNTING) return
        if (clock() >= deadline) destroy(automatic = true) else render()
    }

    /** Keeps the instances: the banner stays, with "Destroy Now" for later. */
    fun keep() {
        if (state != State.COUNTING) return
        state = State.KEPT
        notification?.expire()
        runNotification?.hideBalloon()
        banner?.show(message("molecule.cleanup.kept", subject), listOf(BannerAction(message("molecule.cleanup.destroy.now")) { destroyNow() }))
    }

    /** Destroys the instances now (counting or kept): the user asked for it. */
    fun destroyNow() {
        if (state == State.COUNTING || state == State.KEPT) destroy(automatic = false)
    }

    /** What [MoleculeCleanup.runNotStarted] puts back when the run that cancels this countdown does not start. */
    internal fun rearm(): MoleculeCleanup.Rearm? =
        if (state == State.COUNTING || state == State.KEPT) MoleculeCleanup.Rearm(spec, minutes, state == State.KEPT, banner, root) else null

    /** Another run of the role started (or the project closes): nothing is destroyed from here. */
    internal fun cancel() {
        if (state != State.COUNTING && state != State.KEPT) return
        state = State.CANCELLED
        finish()
    }

    @TestOnly
    fun notificationForTests(): Notification? = notification

    @TestOnly
    fun runNotificationForTests(): Notification? = runNotification

    private fun destroy(automatic: Boolean) {
        state = State.DESTROYED
        finish()
        MoleculeCleanup.destroy(project, spec, automatic)
    }

    private fun finish() {
        notification?.expire()
        runNotification?.hideBalloon()
        banner?.hide()
        banner = null
        onDone(this)
    }

    private fun render() {
        banner?.show(
            message("molecule.cleanup.counting", subject, remaining),
            listOf(BannerAction(message("molecule.cleanup.keep")) { keep() }, BannerAction(message("molecule.cleanup.destroy.now")) { destroyNow() }),
        )
    }

    private companion object {
        const val NOTIFICATION_GROUP = "Ansibility"
    }
}
