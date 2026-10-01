package de.terletzkiy.ansibility.toolwindow

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeDetails
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The details pane (plan F6.2): renders a [NodeDetails] as a title, a grey subtitle and titled sections whose items
 * are labels or links. Links call [navigate]. Everything shown is plain text; nothing is interpreted as HTML.
 */
class AnsibleDetailsView(private val navigate: (NavigationTarget) -> Unit) {
    private val content = JPanel(BorderLayout())

    /** The component to put into the tool window. */
    val component: JComponent = JBScrollPane(content).apply { border = JBUI.Borders.empty() }

    /** What is shown now; null shows the hint. */
    var details: NodeDetails? = null
        private set

    init {
        show(null)
    }

    /** Replaces the content with [details], or with the selection hint when null. */
    fun show(details: NodeDetails?) {
        this.details = details
        content.removeAll()
        content.add(render(details), BorderLayout.NORTH)
        content.revalidate()
        content.repaint()
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
