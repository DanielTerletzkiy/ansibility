package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.ui.UiInterceptors
import com.intellij.ui.awt.RelativePoint
import de.terletzkiy.ansibility.semantics.vault.EnvelopeParse
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import de.terletzkiy.ansibility.semantics.vault.VaultAes256
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.VaultActionPrompts
import de.terletzkiy.ansibility.vault.actions.VaultDecryptRequest
import de.terletzkiy.ansibility.vault.actions.VaultEncryptRequest
import de.terletzkiy.ansibility.vault.actions.VaultIdentityRequest
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import java.util.concurrent.CopyOnWriteArrayList

/** A virtual clock for the vault UI: scheduled closes run only when the test advances time. */
class ManualScheduler : VaultUiScheduler {
    private class Task(val due: Long, val action: () -> Unit) {
        @Volatile
        var cancelled = false
    }

    private val tasks = ArrayList<Task>()
    var now: Long = 0
        private set

    /** The tasks not run yet. */
    val pending: Int get() = tasks.count { !it.cancelled }

    override fun schedule(delayMillis: Long, parent: Disposable, action: () -> Unit) {
        val task = Task(now + delayMillis, action)
        Disposer.register(parent) { task.cancelled = true }
        tasks += task
    }

    /** Advances the clock by [millis] and runs every task that came due (on the EDT, in due order). */
    fun advance(millis: Long) {
        now += millis
        val due = tasks.filter { it.due <= now }.sortedBy { it.due }
        tasks.removeAll(due)
        for (task in due) if (!task.cancelled) task.action()
    }
}

/** Scripted answers to the value actions' questions; records every request. */
class FakePrompts : VaultActionPrompts {
    val identityRequests = CopyOnWriteArrayList<VaultIdentityRequest>()
    val encryptRequests = CopyOnWriteArrayList<VaultEncryptRequest>()
    val decryptRequests = CopyOnWriteArrayList<VaultDecryptRequest>()
    val rekeyQuestions = CopyOnWriteArrayList<Pair<String, String>>()
    var discardQuestions = 0

    var identity: (VaultIdentityRequest) -> String? = { it.preselected ?: it.choices.firstOrNull()?.label }
    var encrypt: (VaultEncryptRequest) -> String? = { it.preselected ?: it.choices.firstOrNull()?.label }
    var decrypt: Boolean = true
    var rekeyForChangeId: Boolean = true
    var discard: Boolean = true

    override fun chooseIdentity(project: Project, request: VaultIdentityRequest): String? = identity(request.also { identityRequests += it })
    override fun confirmEncrypt(project: Project, request: VaultEncryptRequest): String? = encrypt(request.also { encryptRequests += it })
    override fun confirmDecryptToPlain(project: Project, request: VaultDecryptRequest): Boolean = decrypt.also { decryptRequests += request }
    override fun confirmRekeyForChangeId(project: Project, target: String, decryptsWith: String): Boolean =
        rekeyForChangeId.also { rekeyQuestions += target to decryptsWith }

    override fun confirmDiscard(project: Project): Boolean = discard.also { discardQuestions++ }
}

/**
 * Base for vault UI tests: [VaultTestCase] plus a virtual UI clock, scripted action prompts, and every popup the
 * code shows captured instead of opened (headless tests cannot open windows).
 */
abstract class VaultUiTestCase : VaultTestCase() {
    protected lateinit var scheduler: ManualScheduler
    protected lateinit var prompts: FakePrompts
    protected val popups = CopyOnWriteArrayList<JBPopup>()

    override fun setUp() {
        super.setUp()
        scheduler = ManualScheduler()
        VaultUiClock.getInstance().setSchedulerForTests(scheduler, testRootDisposable)
        prompts = FakePrompts()
        ApplicationManager.getApplication().replaceService(VaultActionPrompts::class.java, prompts, testRootDisposable)
        UiInterceptors.registerPersistent(testRootDisposable, object : UiInterceptors.PersistentUiInterceptor<JBPopup>(JBPopup::class.java) {
            override fun shouldIntercept(component: JBPopup): Boolean = true
            override fun doIntercept(component: JBPopup, owner: RelativePoint?) {
                popups += component
            }
        })
        VaultUiFeedback.drainForTests()
    }

    override fun tearDown() {
        try {
            VaultRevealService.getInstance(project).dispose()
            VaultEditService.getInstance(project).dispose()
            for (popup in popups) if (!popup.isDisposed) popup.cancel()
            VaultUiFeedback.drainForTests()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A root `repos/<name>/ansible` with `.vault-pass` = `test-pass-1` (id `default`). */
    protected fun defaultRoot(name: String = "falcon", cfg: String = "[defaults]\n"): String {
        val root = projectRoot(name, cfg)
        write("$root/.vault-pass", "${VaultVectors.PW1}\n")
        return root
    }

    /** Waits until the last value action finished and the events it posted ran. */
    protected fun awaitAction() {
        val job = VaultValueActions.getInstance(project).lastAction ?: return
        PlatformTestUtil.waitWithEventsDispatching("vault action timed out", { job.isCompleted }, 60)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** Waits until [condition] holds, dispatching events. */
    protected fun waitFor(message: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 60)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /** The plaintext of [envelopeText] under [password], or null when it does not decrypt. */
    protected fun decryptWith(envelopeText: String, password: String): String? {
        val envelope = (VaultEnvelope.parse(envelopeText) as? EnvelopeParse.Ok)?.envelope ?: return null
        return SecretBytes.of(password.toByteArray()).use { secret -> VaultAes256.decrypt(envelope, secret)?.let { String(it) } }
    }

    /** The reports of [VaultUiFeedback] since the last call. */
    protected fun feedback(): List<String> = VaultUiFeedback.drainForTests()
}
