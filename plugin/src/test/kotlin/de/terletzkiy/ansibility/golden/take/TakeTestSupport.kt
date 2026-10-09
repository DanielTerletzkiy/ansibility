package de.terletzkiy.ansibility.golden.take

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.replaceService
import java.util.concurrent.CopyOnWriteArrayList

/** A [TakeUi] that records: [deleteAnswer] and [sensitiveAnswer] answer the confirmations. */
class RecordingTakeUi : TakeUi {
    val deleteQuestions = CopyOnWriteArrayList<String>()
    val sensitiveQuestions = CopyOnWriteArrayList<String>()
    val notices = CopyOnWriteArrayList<String>()
    val notes = CopyOnWriteArrayList<String>()

    @Volatile
    var deleteAnswer: Boolean = true

    @Volatile
    var sensitiveAnswer: Boolean = true

    override fun confirmDelete(project: Project, title: String, message: String): Boolean {
        deleteQuestions += message
        return deleteAnswer
    }

    override fun confirmSensitive(project: Project, title: String, message: String): Boolean {
        sensitiveQuestions += message
        return sensitiveAnswer
    }

    override fun inform(project: Project, message: String) {
        notices += message
    }

    override fun done(project: Project, message: String) {
        notes += message
    }

    companion object {
        /** Installs a new recording UI until [parent] is disposed. */
        fun install(parent: Disposable): RecordingTakeUi =
            RecordingTakeUi().also { ApplicationManager.getApplication().replaceService(TakeUi::class.java, it, parent) }
    }
}
