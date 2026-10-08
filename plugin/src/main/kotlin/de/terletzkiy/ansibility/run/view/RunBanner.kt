package de.terletzkiy.ansibility.run.view

import com.intellij.ui.EditorNotificationPanel
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** A link of a [RunBanner]. */
class BannerAction(@Nls val text: String, val run: () -> Unit)

/**
 * A message above a run's tabs, hidden until it has one: what the run prepares (info), why it did not start (error),
 * a Molecule run's destroy countdown (warning). Each tab (the Plays tab, the console) gets a component of its own;
 * [show] and [hide] update them all. EDT.
 */
class RunBanner {
    /** How a message looks. */
    enum class Status(internal val panel: EditorNotificationPanel.Status) {
        INFO(EditorNotificationPanel.Status.Info),
        WARNING(EditorNotificationPanel.Status.Warning),
        ERROR(EditorNotificationPanel.Status.Error),
    }

    private class Placement(val holder: JPanel) {
        var panel: EditorNotificationPanel? = null
    }

    private val placements = ArrayList<Placement>()
    private var message: String? = null
    private var actions: List<BannerAction> = emptyList()
    private var status: Status = Status.WARNING

    /** A new component for one tab, showing the current message. */
    fun component(): JComponent {
        val placement = Placement(JPanel(BorderLayout()))
        placements += placement
        rebuild(placement)
        return placement.holder
    }

    /** Shows [message] with [actions]; a new message with the same actions and [status] only changes the text. */
    fun show(@Nls message: String, actions: List<BannerAction>, status: Status = Status.WARNING) {
        val sameLinks = this.message != null && this.status == status && actions.map { it.text } == this.actions.map { it.text }
        this.message = message
        this.actions = actions
        this.status = status
        for (placement in placements) {
            val panel = placement.panel
            if (sameLinks && panel != null) panel.text(message) else rebuild(placement)
        }
    }

    fun hide() {
        message = null
        actions = emptyList()
        placements.forEach(::rebuild)
    }

    private fun rebuild(placement: Placement) {
        placement.holder.removeAll()
        val message = message
        placement.panel = message?.let { text ->
            EditorNotificationPanel(status.panel).text(text).also { panel ->
                actions.forEach { action -> panel.createActionLabel(action.text) { action.run() } }
                placement.holder.add(panel, BorderLayout.CENTER)
            }
        }
        placement.holder.isVisible = message != null
        placement.holder.revalidate()
        placement.holder.repaint()
    }

    @TestOnly
    fun messageForTests(): String? = message

    @TestOnly
    fun statusForTests(): Status? = status.takeIf { message != null }

    @TestOnly
    fun linksForTests(): List<String> = actions.map { it.text }

    /** Clicks the link [text] (tests). */
    @TestOnly
    fun clickForTests(text: String) = actions.first { it.text == text }.run()
}
