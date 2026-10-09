package de.terletzkiy.ansibility.golden.push

import com.intellij.notification.Notification
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import de.terletzkiy.ansibility.golden.GoldenTestSupport
import de.terletzkiy.ansibility.golden.history.LocalChangesLookup
import de.terletzkiy.ansibility.model.role.RoleCopy
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [PushUi] that records instead of showing: [pick] chooses rows and options once the rows are computed (null: the
 * dialog is cancelled), [confirm] answers the binary-file confirmation, [answerUndo] the one Undo question.
 */
class RecordingPushUi : PushUi {
    val models = CopyOnWriteArrayList<PushModel>()
    val binaryLists = CopyOnWriteArrayList<List<String>>()
    val notices = CopyOnWriteArrayList<String>()
    val notifications = CopyOnWriteArrayList<Notification>()
    val undoQuestions = CopyOnWriteArrayList<String>()

    @Volatile
    var pick: ((List<PushRow>) -> PushChoice?)? = null

    @Volatile
    var confirm: (List<String>) -> Boolean = { true }

    @Volatile
    var answerUndo: (String) -> Boolean = { true }

    override fun confirmUndo(project: Project, question: String): Boolean {
        undoQuestions += question
        return answerUndo(question)
    }

    override fun choose(project: Project, model: PushModel): PushChoice? {
        models += model
        val pick = pick ?: return null
        PlatformTestUtil.waitWithEventsDispatching("the push rows are computed", { model.rows.isCompleted }, 60)
        return pick(model.rowsIfReady() ?: error("the rows failed"))
    }

    override fun confirmBinary(project: Project, roleName: String, binaries: List<String>): Boolean {
        binaryLists += binaries
        return confirm(binaries)
    }

    override fun inform(project: Project, message: String) {
        notices += message
    }

    override fun notify(project: Project, notification: Notification) {
        notifications += notification
    }
}

/** A [LocalChangesLookup] that answers true for [changed] directories and null elsewhere. */
class FakeLocalChanges(private val changed: Set<VirtualFile>) : LocalChangesLookup {
    override fun hasLocalChanges(project: Project, dir: VirtualFile): Boolean? = if (dir in changed) true else null
}

object PushTestSupport {
    /** Installs [ui] as the [PushUi] until [parent] is disposed. */
    fun install(ui: PushUi, parent: Disposable) {
        ApplicationManager.getApplication().replaceService(PushUi::class.java, ui, parent)
    }

    /** The Push rows of [source], computed now. */
    fun rows(project: Project, source: RoleCopy): List<PushRow> = GoldenTestSupport.await { PushRows.compute(project, source) }

    /** A model whose rows are known already. */
    fun model(project: Project, source: RoleCopy, rows: List<PushRow>): PushModel = PushModel(project, source, CompletableDeferred(rows))

    /** [rows] by root name. */
    fun byName(rows: List<PushRow>, vararg names: String): List<PushRow> = names.map { name -> rows.single { it.name == name } }

    /** Pushes [source] to the rows named [names] with [options], waiting for the outcome. */
    fun push(project: Project, source: RoleCopy, names: List<String>, options: PushOptions = PushOptions()): PushOutcome {
        val rows = rows(project, source)
        return GoldenTestSupport.await { PushService.getInstance(project).execute(source, PushChoice(byName(rows, *names.toTypedArray()), options)) }
    }

    /** Clicks the action [text] of [notification], as its balloon would. */
    fun click(project: Project, notification: Notification, text: String) {
        val action = notification.actions.singleOrNull { it.templateText == text } ?: error("no action $text in ${notification.actions.map { it.templateText }}")
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(Notification.KEY, notification).build()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
    }

    /** The texts of [notification]'s actions. */
    fun actions(notification: Notification): List<String?> = notification.actions.map { it.templateText }
}
