package de.terletzkiy.ansibility.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.golden.compare.GoldenActionTexts
import de.terletzkiy.ansibility.model.drift.AnsibilityDriftBundle
import de.terletzkiy.ansibility.model.drift.DriftTexts
import de.terletzkiy.ansibility.toolwindow.host.EffectiveVarsContent
import de.terletzkiy.ansibility.toolwindow.host.PlayChoice
import de.terletzkiy.ansibility.toolwindow.model.DriftDetailsContent
import de.terletzkiy.ansibility.toolwindow.model.DriftFileKind
import de.terletzkiy.ansibility.toolwindow.model.GoldenActionIds
import de.terletzkiy.ansibility.toolwindow.model.NavigationTarget
import de.terletzkiy.ansibility.toolwindow.model.NodeDetails
import java.awt.BorderLayout
import java.awt.Font
import java.awt.font.TextAttribute
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The details pane (plan F6.2): renders a [NodeDetails] as a title, a grey subtitle and titled sections whose items
 * are labels or links, then its content: for a host's Effective vars (HA7a) the play selector and the tree table
 * ([EffectiveVarsView]), which fills the rest of the pane; for a role copy's drift (plan amendment R24, D180,
 * [DriftDetailsContent]) the differing files in their VCS status colours with a [Compare] link each, and the golden
 * actions as buttons. Links and rows call [navigate]; choosing a play calls [onPlayChoice]; Compare and the buttons call
 * [runAction] with the action id, the copy and the file's path inside the role (null for a button). A link or button shows only
 * while [isActionAvailable] (the action is registered). Everything shown is plain text; nothing is interpreted as
 * HTML, and no file content is ever read here.
 *
 * The tree asks for the details again whenever it re-renders; details equal to the shown ones keep the pane as it is,
 * and a new table of the same host keeps the expanded and selected variables of the previous one.
 */
class AnsibleDetailsView(
    private val navigate: (NavigationTarget) -> Unit,
    private val onPlayChoice: (HostKey, PlayChoice) -> Unit = { _, _ -> },
    private val runAction: (actionId: String, copyDir: VirtualFile, rolePath: String?) -> Unit = { _, _, _ -> },
    private val isActionAvailable: (actionId: String) -> Boolean = { ActionManager.getInstance().getAction(it) != null },
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
            val drift = details?.content as? DriftDetailsContent
            val body = if (drift == null) render(details) else JPanel(BorderLayout()).apply {
                add(render(details), BorderLayout.NORTH)
                add(renderDrift(drift), BorderLayout.CENTER)
            }
            val text = JPanel(BorderLayout()).apply { add(body, BorderLayout.NORTH) }
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

    /**
     * The differing files of a copy and the golden actions (plan amendment R24, D180/D181): a sensitive file says
     * "differs (content not shown)"; a file only golden has is struck through.
     */
    private fun renderDrift(content: DriftDetailsContent): JComponent {
        val compare = isActionAvailable(GoldenActionIds.COMPARE_WITH_GOLDEN)
        val actions = content.actions.filter(isActionAvailable)
        return panel {
            if (content.files.isNotEmpty()) {
                group(AnsibilityDriftBundle.message("drift.details.section.differences", content.golden), indent = true) {
                    for (file in content.files) {
                        row {
                            label(file.relPath).applyToComponent {
                                foreground = DriftColors.of(file.kind.color)
                                if (file.kind == DriftFileKind.ONLY_IN_GOLDEN) font = font.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON))
                            }
                            val note = if (file.sensitive) AnsibilityDriftBundle.message("drift.join", file.group, DriftTexts.contentNotShown()) else file.group
                            label(note).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
                            if (compare) link(AnsibilityDriftBundle.message("drift.details.compare")) { runAction(GoldenActionIds.COMPARE_WITH_GOLDEN, content.copyDir, file.relPath) }
                        }
                    }
                }
            }
            if (actions.isNotEmpty()) {
                group(AnsibilityDriftBundle.message("drift.details.section.actions"), indent = true) {
                    row {
                        for (id in actions) button(actionText(id)) { runAction(id, content.copyDir, null) }
                    }
                }
            }
        }.apply { border = JBUI.Borders.empty(0, 10, 6, 10) }
    }

    /**
     * The button's text: the registered action's own short menu text (Compare with Golden, Align with Golden…, Merge
     * into Golden…, Push Role to Repos…), as its menu item shows it, not the "Ansibility: …" template text Find Action
     * shows. One name per action: the button and the menu item never differ.
     */
    private fun actionText(id: String): String {
        val text = ActionManager.getInstance().getAction(id)?.templatePresentation?.text?.takeIf { it.isNotBlank() } ?: return id
        return GoldenActionTexts.menuText(text)
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
