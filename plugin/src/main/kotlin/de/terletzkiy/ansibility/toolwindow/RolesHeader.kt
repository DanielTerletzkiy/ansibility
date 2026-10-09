package de.terletzkiy.ansibility.toolwindow

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nls
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** A link of the Roles tab's header line. */
class HeaderLink(@Nls val text: String, val action: () -> Unit)

/**
 * The one header line of the Roles tab (plan amendment R24, D178/D179): the golden root with the counts
 * ("Golden root: golden · 65 names · 12 drifting · Change…"), or a configured golden root that cannot be used
 * ("Golden root … not found · Choose…"). Hidden without a golden root. Plain Swing; the panel decides the content
 * ([update]) and the visibility on the EDT.
 */
class RolesHeader {
    private val label = JBLabel()
    private val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
        border = JBUI.Borders.empty(2, 4)
    }

    /** The component above the tree. */
    val component: JComponent get() = panel

    /** The text shown now (tests). */
    var text: String = ""
        private set

    /** The links shown now, in order (tests). */
    var links: List<HeaderLink> = emptyList()
        private set

    /** Shows [text] followed by [links]. */
    fun update(@Nls text: String, links: List<HeaderLink>) {
        if (text == this.text && links.map { it.text } == this.links.map { it.text }) {
            this.links = links
            return
        }
        this.text = text
        this.links = links
        panel.removeAll()
        label.text = text
        panel.add(label)
        for (link in links) panel.add(ActionLink(link.text) { link.action() })
        panel.revalidate()
        panel.repaint()
    }

    /** Runs the link named [text] (tests and accessibility); false when there is none. */
    fun click(text: String): Boolean {
        val link = links.firstOrNull { it.text == text } ?: return false
        link.action()
        return true
    }

    private companion object {
        const val GAP = 8
    }
}
