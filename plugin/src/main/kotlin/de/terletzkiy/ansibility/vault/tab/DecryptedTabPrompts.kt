package de.terletzkiy.ansibility.vault.tab

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.ThreadingAssertions

/** When unsaved edits of decrypted tabs need an answer. */
enum class UnsavedTabsReason {
    /** You closed the tab. */
    CLOSE,

    /** Lock all is about to lock the ids; the edits can still be encrypted. */
    BEFORE_LOCK,

    /** The tab's id was locked (Lock all, idle lock, a root's lock); saving needs an unlock first. */
    LOCKED,

    /** The project closes. */
    PROJECT_CLOSE,
}

/** What to do with unsaved edits of decrypted tabs. */
enum class UnsavedTabsChoice {
    /** Encrypt them into the real files (after an unlock, for [UnsavedTabsReason.LOCKED]). */
    SAVE,

    /** Drop them. */
    DISCARD,

    /** Keep the tabs as they are (not offered for [UnsavedTabsReason.LOCKED]: a locked id's tab never stays open). */
    CANCEL,
}

/** The question about unsaved edits: the tab names only, never their content. */
class UnsavedTabsRequest(val reason: UnsavedTabsReason, val tabNames: List<String>) {
    /** Whether [UnsavedTabsChoice.CANCEL] may be chosen. */
    val cancellable: Boolean get() = reason != UnsavedTabsReason.LOCKED

    override fun toString(): String = "UnsavedTabsRequest($reason, $tabNames)"
}

/**
 * The decrypted tabs' questions (an application service, so tests can script the answers). Asked on the EDT.
 */
interface DecryptedTabPrompts {
    fun askUnsaved(project: Project, request: UnsavedTabsRequest): UnsavedTabsChoice

    companion object {
        fun getInstance(): DecryptedTabPrompts = service()
    }
}

/** [DecryptedTabPrompts] as platform message dialogs. */
class DecryptedTabDialogPrompts : DecryptedTabPrompts {
    override fun askUnsaved(project: Project, request: UnsavedTabsRequest): UnsavedTabsChoice {
        ThreadingAssertions.assertEventDispatchThread()
        val names = request.tabNames.joinToString("\n") { "  $it" }
        val prefix = when (request.reason) {
            UnsavedTabsReason.CLOSE -> "close"
            UnsavedTabsReason.BEFORE_LOCK -> "before.lock"
            UnsavedTabsReason.LOCKED -> "locked"
            UnsavedTabsReason.PROJECT_CLOSE -> "project.close"
        }
        val title = TabTexts.message("tab.unsaved.title")
        val message = TabTexts.message("tab.unsaved.$prefix.message", request.tabNames.size, names)
        val save = TabTexts.message("tab.unsaved.$prefix.save")
        val discard = TabTexts.message("tab.unsaved.$prefix.discard")
        if (!request.cancellable) {
            val answer = Messages.showDialog(project, message, title, arrayOf(save, discard), 0, Messages.getWarningIcon())
            return if (answer == 0) UnsavedTabsChoice.SAVE else UnsavedTabsChoice.DISCARD
        }
        return when (Messages.showYesNoCancelDialog(project, message, title, save, discard, Messages.getCancelButton(), Messages.getWarningIcon())) {
            Messages.YES -> UnsavedTabsChoice.SAVE
            Messages.NO -> UnsavedTabsChoice.DISCARD
            else -> UnsavedTabsChoice.CANCEL
        }
    }
}
