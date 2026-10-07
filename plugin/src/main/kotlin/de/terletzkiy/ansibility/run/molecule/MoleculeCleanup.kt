package de.terletzkiy.ansibility.run.molecule

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.concurrency.ThreadingAssertions
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.view.BannerAction
import de.terletzkiy.ansibility.run.view.RunBanner
import org.jetbrains.annotations.TestOnly

/**
 * Destroys the instances a Molecule run leaves (Converge, Verify, Idempotence) after a grace period unless the user
 * keeps them (plan amendment R15). A role has one countdown at most: a new run of the role cancels it, so instances are
 * never destroyed under a running test, and that run starts its own when it ends. Closing the project cancels every
 * countdown (the instances stay). EDT.
 */
@Service(Service.Level.PROJECT)
class MoleculeCleanup(private val project: Project) : Disposable {
    /** Role directory → its countdown, counting or kept. */
    private val countdowns = HashMap<String, MoleculeCountdown>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** A run of [spec] starts: its role's countdown (or kept banner) goes, before the run goes on. Any thread. */
    fun runStarting(spec: MoleculeSpec) {
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) return application.invokeAndWait({ runStarting(spec) }, ModalityState.any())
        countdowns.remove(spec.roleDir)?.cancel()
    }

    /**
     * A run of [spec] ended: when its command leaves instances and [minutes] > 0, counts down to `molecule destroy`
     * in [banner] and a balloon. A test the user [stopped] could not clean up after itself: its instances are destroyed
     * right away (whatever [minutes] says: a test always destroys its instances); a stopped "keep instances" test counts
     * down like a converge. Returns the countdown, or null when there is none.
     */
    fun runEnded(spec: MoleculeSpec, minutes: Int, banner: RunBanner?, stopped: Boolean = false): MoleculeCountdown? {
        ThreadingAssertions.assertEventDispatchThread()
        if (stopped && spec.command == MoleculeCommand.TEST) {
            banner?.show(message("molecule.cleanup.stopped", subject(spec)), emptyList())
            destroy(project, spec)
            return null
        }
        val leaves = spec.command.leavesInstances || (stopped && spec.command == MoleculeCommand.TEST_KEEP)
        if (minutes <= 0 || !leaves) return null
        val countdown = MoleculeCountdown(project, spec, now() + minutes * 60_000L, minutes, banner, ::now) { done -> countdowns.remove(spec.roleDir, done) }
        countdowns.put(spec.roleDir, countdown)?.cancel()
        countdown.start()
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
    }

    /** Cancels every countdown (tests: the project outlives a test). */
    @TestOnly
    fun resetForTests() = dispose()

    /** Replaces the clock (tests move time on and call [tick]). */
    @TestOnly
    @Volatile
    internal var clockForTests: (() -> Long)? = null

    companion object {
        private const val TICK_MILLIS = 1000

        fun getInstance(project: Project): MoleculeCleanup = project.service()

        /** "web › default", "web (all scenarios)". */
        fun subject(spec: MoleculeSpec): String =
            if (spec.scenario.isBlank()) message("molecule.cleanup.subject.all", spec.roleName) else message("molecule.cleanup.subject", spec.roleName, spec.scenario)

        /**
         * Runs `molecule destroy` of [spec]'s role and scenario the way [spec] ran (locally or in its Compose service,
         * the role's `MOLECULE_RUN_ID`), in a run tab of its own and not under a modal dialog. Any thread.
         */
        fun destroy(project: Project, spec: MoleculeSpec) {
            ApplicationManager.getApplication().invokeLater(
                { MoleculeLauncher.run(project, spec.copy(command = MoleculeCommand.DESTROY)) },
                ModalityState.nonModal(),
                project.disposed,
            )
        }
    }
}

/**
 * One countdown to `molecule destroy` of [spec]'s role and scenario, shown in [banner] (the run's tab) and a balloon
 * with "Keep Instances" and "Destroy Now". Kept, the banner stays with "Destroy Now"; done (destroyed or cancelled), it
 * hides. The destroy runs like the run did (locally or in its Compose service, the role's `MOLECULE_RUN_ID`), in a
 * run tab of its own. EDT.
 */
class MoleculeCountdown internal constructor(
    private val project: Project,
    val spec: MoleculeSpec,
    val deadline: Long,
    private val minutes: Int,
    private val banner: RunBanner?,
    private val clock: () -> Long,
    private val onDone: (MoleculeCountdown) -> Unit,
) {
    enum class State { COUNTING, KEPT, DESTROYED, CANCELLED }

    var state: State = State.COUNTING
        private set

    private var notification: Notification? = null

    private val subject: String = MoleculeCleanup.subject(spec)

    /** The remaining time as `m:ss`. */
    val remaining: String
        get() {
            val seconds = ((deadline - clock()).coerceAtLeast(0) + 999) / 1000
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }

    internal fun start() {
        render()
        notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message("molecule.cleanup.notification.title"), message("molecule.cleanup.notification", subject, minutes), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(message("molecule.cleanup.keep")) { keep() })
            .addAction(NotificationAction.createSimpleExpiring(message("molecule.cleanup.destroy.now")) { destroyNow() })
            .also { it.notify(project) }
    }

    /** Updates the remaining time; at the deadline, destroys. */
    fun tick() {
        if (state != State.COUNTING) return
        if (clock() >= deadline) destroy() else render()
    }

    /** Keeps the instances: the banner stays, with "Destroy Now" for later. */
    fun keep() {
        if (state != State.COUNTING) return
        state = State.KEPT
        notification?.expire()
        banner?.show(message("molecule.cleanup.kept", subject), listOf(BannerAction(message("molecule.cleanup.destroy.now")) { destroyNow() }))
    }

    /** Destroys the instances now (counting or kept). */
    fun destroyNow() {
        if (state == State.COUNTING || state == State.KEPT) destroy()
    }

    /** Another run of the role started (or the project closes): nothing is destroyed from here. */
    internal fun cancel() {
        if (state != State.COUNTING && state != State.KEPT) return
        state = State.CANCELLED
        finish()
    }

    @TestOnly
    fun notificationForTests(): Notification? = notification

    private fun destroy() {
        state = State.DESTROYED
        finish()
        MoleculeCleanup.destroy(project, spec)
    }

    private fun finish() {
        notification?.expire()
        banner?.hide()
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
