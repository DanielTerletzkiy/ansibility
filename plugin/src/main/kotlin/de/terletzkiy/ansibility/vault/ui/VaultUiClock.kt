package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/** The durations of the vault UI (D24, F7.1). */
object VaultUiTimings {
    /** A Reveal popup closes after 15 s (D24). */
    const val REVEAL_MILLIS: Long = 15_000L

    /** A copied value leaves the clipboard and the IDE's paste history after 30 s (F7.1). */
    const val CLIPBOARD_MILLIS: Long = 30_000L

    /** [millis] in whole seconds, for the texts. */
    fun seconds(millis: Long): Long = millis / 1000
}

/** Runs the timed parts of the vault UI: the Reveal popup's timeout and the clipboard clear. */
fun interface VaultUiScheduler {
    /**
     * Runs [action] on the EDT (in any modality, so a dialog never delays a clear) after [delayMillis], unless
     * [parent] (alive when this is called) is disposed first.
     */
    fun schedule(delayMillis: Long, parent: Disposable, action: () -> Unit)
}

/**
 * The application's [VaultUiScheduler]. Timed closes run on a coroutine of this service, so a clipboard clear also
 * happens after the project that copied the value was closed. Tests install a virtual clock.
 */
@Service(Service.Level.APP)
class VaultUiClock(private val scope: CoroutineScope) {
    private val system: VaultUiScheduler = VaultUiScheduler { delayMillis, parent, action ->
        val job = scope.launch {
            delay(delayMillis)
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { action() }
        }
        val cancel = Disposable { job.cancel() }
        Disposer.register(parent, cancel)
        job.invokeOnCompletion { Disposer.dispose(cancel) }
    }

    @Volatile
    private var current: VaultUiScheduler = system

    /** The scheduler in use. */
    val scheduler: VaultUiScheduler get() = current

    /** Replaces the scheduler until [disposable] is disposed. */
    @TestOnly
    fun setSchedulerForTests(scheduler: VaultUiScheduler, disposable: Disposable) {
        current = scheduler
        Disposer.register(disposable) { current = system }
    }

    companion object {
        fun getInstance(): VaultUiClock = service()
    }
}
