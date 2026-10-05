package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.toolwindow.host.EffectiveVarsContent
import de.terletzkiy.ansibility.toolwindow.host.PlayChoice
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeDetails
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The details pane (plan F6.2): renders a [NodeDetails] as a title, a grey subtitle and titled sections whose items
 * are labels or links, then its content: for a host's Effective vars (HA7a) the play selector and the tree table
 * ([EffectiveVarsView]), which fills the rest of the pane. Links and rows call [navigate]; choosing a play calls
 * [onPlayChoice]. Everything shown is plain text; nothing is interpreted as HTML.
 *
 * The tree asks for the details again whenever it re-renders; details equal to the shown ones keep the pane as it is,
 * and a new table of the same host keeps the expanded and selected variables of the previous one.
 */
class AnsibleDetailsView(
    private val navigate: (NavigationTarget) -> Unit,
    private val onPlayChoice: (HostKey, PlayChoice) -> Unit = { _, _ -> },
) {
    private val root = JPanel(BorderLayout())

    /** The component to put into the tool window. */
    val component: JComponent = root

    /** What is shown now; null shows the hint. */
    var details: NodeDetails? = null
        private set

    /** The Effective vars table shown now, or null (tests). */
    var effectiveView: EffectiveVarsView? = null
        private set

    init {
        show(null)
    }

    /**
     * Replaces the content with [details], or with the selection hint when null; does nothing when [details] equals what
     * is shown. Returns whether the content was replaced.
     */
    fun show(details: NodeDetails?): Boolean {
        if (details != null && details == this.details) return false
        val previous = effectiveView
        this.details = details
        root.removeAll()
        val effective = details?.content as? EffectiveVarsContent
        var restored: EffectiveVarsView? = null
        if (effective == null) {
            effectiveView = null
            val text = JPanel(BorderLayout()).apply { add(render(details), BorderLayout.NORTH) }
            root.add(JBScrollPane(text).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        } else {
            val view = EffectiveVarsView(effective, onPlayChoice, navigate)
            // A new table of the same host (a play chosen, a var file edited) keeps what the user expanded and selected.
            previous?.takeIf { it.content.table.context.host == effective.table.context.host }?.let {
                view.restore(it.state())
                restored = view
            }
            effectiveView = view
            root.add(render(details), BorderLayout.NORTH)
            root.add(view.component, BorderLayout.CENTER)
        }
        root.revalidate()
        root.repaint()
        // Scroll once the layout the revalidation queued has given the selected row its place.
        restored?.let { view -> ApplicationManager.getApplication().invokeLater({ if (effectiveView === view) view.scrollToSelection() }, ModalityState.any()) }
        return true
    }

    private fun render(details: NodeDetails?): JComponent {
        val hint = AnsibilityToolWindowBundle.message("details.empty")
        return panel {
            if (details == null) {
                row { label(hint).applyToComponent { foreground = UIUtil.getContextHelpForeground() } }
                return@panel
            }
            row { label(details.title).bold() }
            details.subtitle?.let { subtitle -> row { label(subtitle).applyToComponent { foreground = UIUtil.getContextHelpForeground() } } }
            for (section in details.sections) {
                group(section.title, indent = true) {
                    for (item in section.items) {
                        row {
                            val target = item.target
                            if (target != null) {
                                link(item.text) { navigate(target) }.applyToComponent { if (item.emphasized) font = font.deriveFont(Font.BOLD) }
                            } else {
                                label(item.text).applyToComponent { if (item.emphasized) font = font.deriveFont(Font.BOLD) }
                            }
                            item.note?.let { note -> label(note).applyToComponent { foreground = UIUtil.getContextHelpForeground() } }
                        }
                    }
                }
            }
        }.apply { border = JBUI.Borders.empty(6, 10) }
    }
}
